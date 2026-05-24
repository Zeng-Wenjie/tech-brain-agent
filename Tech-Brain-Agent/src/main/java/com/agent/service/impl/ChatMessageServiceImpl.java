package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.ConversationMemory;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.ChatMessageService;
import com.agent.service.ConversationMemoryService;
import com.agent.toolcalling.core.ToolChatHistoryMessage;
import com.agent.toolcalling.core.ToolCallingChatService;
import com.agent.toolcalling.core.ToolCallingStreamCallback;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 前端正式聊天入口 {@code POST /chat/message} 的业务服务实现。
 *
 * <p>适用范围：处理用户在正式聊天页发送的一轮消息，包括会话归属校验、用户消息落库、Tool Calling
 * 流式回调转发、assistant 完整消息保存和 conversation 更新时间刷新。</p>
 * <p>当前主调用链：ChatMessageController -> ChatMessageServiceImpl
 * -> ToolCallingChatService.chatStream(rawUserMessage, memorySummary, toolHistoryMessages, callback) -> ToolRegistry/RagSearchTool
 * -> Milvus -> DeepSeek stream -> SSE token 返回前端。</p>
 * <p>多轮上下文当前处于 4.6.3 阶段：本类读取 conversation_memory.summary 作为长期记忆摘要，同时读取最近短期历史并转换为ToolChatHistoryMessage，
 * 两者都只作为最终回答上下文传给ToolCallingChatService，不拼接multiTurnQuestion，不参与force ragSearch和RAG query。</p>
 * <p>长期记忆写入仍在 assistant 完整回复保存、conversation 更新时间刷新、SSE done 完成后执行，不阻塞前端完成事件。</p>
 */
@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LENGTH = 20;
    private static final int HISTORY_CONTEXT_LIMIT = 100; // 4.3 阶段读取并转换最近 6 条结构化历史，供Tool Calling最终回答阶段使用。
    private static final long SSE_TIMEOUT = 120000L; // SSE 连接最长等待时间，给 DeepSeek 流式生成保留足够窗口。

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ToolCallingChatService toolCallingChatService; // /chat/message 唯一正式编排器，负责 Tool Calling 与 DeepSeek 流式输出。

    @Autowired
    private ConversationMemoryService conversationMemoryService; // assistant 回复完成后异步线程内更新 conversation_memory 长期记忆。

    @Override
    public SseEmitter sendMessage(ChatRequestDTO dto) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT); // HTTP 响应先返回 emitter，后续 token 通过同一连接持续推送。
        Long userId = UserContext.getUserId();

        CompletableFuture.runAsync(() -> { // 流式输出不放进 Controller 线程，避免连接等待阻塞请求线程。
            try {
                UserContext.setUserId(userId); // 异步线程手动恢复当前用户，保证 ragSearch/Milvus 检索按用户过滤。
                doSendMessage(dto, userId, emitter);
            } catch (Exception e) {
                sendError(emitter, e);
            } finally {
                UserContext.removeUserId();
            }
        });

        return emitter;
    }

    private void doSendMessage(ChatRequestDTO dto, Long userId, SseEmitter emitter) {
        validateRequest(dto); // POST /chat/message 的入口校验，后续流程默认 msg 可用。
        String rawUserMessage = dto.getMsg(); // 当前轮用户原始输入，Tool Calling 工具路由只基于它判断。
        log.info("[ChatMessage] route: /chat/message"); // 明确前端真实聊天页当前走 POST /chat/message。
        log.info("[ChatMessage] current answer mode: tool-calling-stream"); // 清理 legacy 后固定走 Tool Calling 流式链路。
        log.info("[ChatMessage] user id: {}", userId); // 打印当前用户，确认后续历史和 Milvus 检索按用户隔离。
        log.info("[ChatMessage] raw user message: {}", rawUserMessage); // 打印未经多轮拼接的用户原始输入。

        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = resolveConversation(dto, userId, now); // 无会话则新建，有会话则校验归属后复用。
        log.info("[ChatMessage] conversation id: {}", conversation.getId()); // 打印真实会话 ID，便于确认新会话创建或旧会话复用。

        List<ChatMessage> historyMessages = loadRecentHistoryMessages(conversation.getId(), userId); // 保存本轮消息前读取历史，避免包含当前user输入。
        logHistoryMessages(historyMessages); // 打印历史数量和 preview，便于验证结构化历史读取能力。
        List<ToolChatHistoryMessage> toolHistoryMessages = convertToToolHistoryMessages(historyMessages); // 转换为Tool Calling专用历史模型，不做字符串拼接。
        logToolHistoryMessages(toolHistoryMessages); // 打印Tool历史数量和preview，便于验证4.3历史传入能力。
        String memorySummary = loadMemorySummary(conversation.getId(), userId); // 读取会话长期记忆摘要，失败时返回空字符串，不影响聊天主流程。

        saveMessage(conversation.getId(), userId, ROLE_USER, rawUserMessage, now); // 用户消息先落库，assistant 消息在流式完成后落库。

        log.info("[ChatMessage] use ToolCallingChatService.chatStream: true"); // 标记当前 /chat/message 已实际调用 Tool Calling 流式编排器。
        log.info("[ChatMessage] tool-calling raw user message: {}", rawUserMessage); // Tool Calling当前轮输入仍然只使用原始用户消息。
        StringBuilder fullAnswer = new StringBuilder(); // 流式 token 先在内存聚合，完成后一次性保存 assistant 消息。
        toolCallingChatService.chatStream(rawUserMessage, memorySummary, toolHistoryMessages, new ToolCallingStreamCallback() { // 传入长期记忆和结构化历史，禁止恢复multiTurnQuestion。
            @Override
            public void onToken(String token) { // 收到最终回答的增量 token。
                try {
                    fullAnswer.append(token); // 累积完整 assistant 回复，完成后写入 chat_message。
                    Map<String, String> data = new HashMap<>(); // 沿用当前 SSE message 事件数据结构，前端无需改造。
                    data.put("content", token); // 每次只发送增量 token，保持前端逐 token 流式显示。
                    emitter.send(SseEmitter.event().name("message").data(data)); // 通过 SSE message 事件推送 token。
                } catch (IOException e) {
                    throw new RuntimeException("Tool Calling 流式 token 发送失败", e); // 交给上层 onError/异常兜底处理。
                }
            }

            @Override
            public void onComplete() { // DeepSeek 流式输出结束。
                try {
                    log.info("[ChatMessage] stream complete, save assistant message"); // 标记流式完成后开始保存完整 assistant 回复。
                    saveMessage(conversation.getId(), userId, ROLE_ASSISTANT, fullAnswer.toString(), LocalDateTime.now()); // 保存完整 assistant 消息，保证聊天历史完整。
                    updateConversationTime(conversation.getId(), userId); // 刷新会话更新时间，保持会话列表排序正确。
                    Map<String, Object> doneData = new HashMap<>(); // done 事件携带 conversationId，保持前端完成事件格式不变。
                    doneData.put("conversationId", conversation.getId()); // 返回真实会话 ID，兼容新会话首轮发送场景。
                    log.info("[ChatMessage] send done event after stream"); // 标记准备发送 Tool Calling 流式 done 事件。
                    emitter.send(SseEmitter.event().name("done").data(doneData)); // 通知前端本轮流式回答完成。
                    emitter.complete(); // 正常结束 SSE 连接。
                } catch (Exception e) {
                    sendError(emitter, e); // 保存消息或发送 done 失败时走统一错误事件。
                    return; // 主流程失败时不更新长期记忆，避免记录不完整回答。
                }
                updateConversationMemoryAfterAnswer(conversation.getId(), userId, rawUserMessage, fullAnswer.toString()); // SSE完成后再更新长期记忆，避免阻塞前端done。
            }

            @Override
            public void onError(Throwable error) { // Tool Calling 编排、工具执行或 DeepSeek 流式调用异常。
                sendError(emitter, error); // 统一发送 SSE error 事件并结束连接。
            }
        });
    }

    private void validateRequest(ChatRequestDTO dto) {
        if (dto == null || dto.getMsg() == null || dto.getMsg().trim().isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }

    private Conversation resolveConversation(ChatRequestDTO dto, Long userId, LocalDateTime now) {
        if (dto.getConversationId() == null) {
            Conversation conversation = new Conversation(); // 未传会话 ID 时自动创建新会话。
            conversation.setUserId(userId);
            conversation.setTitle(buildTitle(dto.getMsg()));
            conversation.setCreateTime(now);
            conversation.setUpdateTime(now);
            conversationMapper.insert(conversation);
            return conversation;
        }

        Conversation conversation = conversationMapper.selectById(dto.getConversationId()); // 已有会话必须查库确认真实存在。
        if (conversation == null || !userId.equals(conversation.getUserId())) { // 校验 conversation.userId，防止用户伪造会话 ID 继续对话。
            throw new IllegalArgumentException("无权访问该会话");
        }
        return conversation;
    }

    private void saveMessage(Long conversationId, Long userId, String role, String content, LocalDateTime createTime) {
        ChatMessage message = new ChatMessage(); // 用户消息先保存，assistant 消息在流式完成后保存，保证历史可追溯。
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(createTime);
        chatMessageMapper.insert(message);
    }

    private List<ChatMessage> loadRecentHistoryMessages(Long conversationId, Long userId) {
        try {
            List<ChatMessage> recentMessages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>() // 先按倒序查最近历史，避免读取整段会话。
                    .eq(ChatMessage::getConversationId, conversationId)
                    .eq(ChatMessage::getUserId, userId)
                    .in(ChatMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                    .isNotNull(ChatMessage::getContent)
                    .orderByDesc(ChatMessage::getCreateTime)
                    .orderByDesc(ChatMessage::getId)
                    .last("LIMIT " + HISTORY_CONTEXT_LIMIT));
            List<ChatMessage> historyMessages = new ArrayList<>(recentMessages); // 拷贝一份列表，后续过滤和反转不影响 MyBatis 返回对象。
            historyMessages.removeIf(message -> message == null
                    || message.getContent() == null
                    || message.getContent().trim().isEmpty()
                    || !isHistoryRole(message.getRole())); // 只保留 user/assistant 且 content 非空的历史消息。
            Collections.reverse(historyMessages); // 倒序查询后反转为时间正序，后续可直接按顺序传模型。
            return historyMessages;
        } catch (Exception e) {
            log.warn("[ChatContext] load recent history messages failed, conversationId: {}, userId: {}", conversationId, userId, e);
            return Collections.emptyList(); // 历史读取失败不能影响当前 /chat/message 主流程。
        }
    }

    private boolean isHistoryRole(String role) {
        return ROLE_USER.equals(role) || ROLE_ASSISTANT.equals(role); // 历史上下文只允许普通用户消息和 assistant 回复。
    }

    private List<ToolChatHistoryMessage> convertToToolHistoryMessages(List<ChatMessage> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return Collections.emptyList(); // 没有历史时直接返回空列表，不影响当前主流程。
        }

        List<ToolChatHistoryMessage> toolHistoryMessages = new ArrayList<>(); // 创建新列表，避免修改原始ChatMessage历史集合。
        for (ChatMessage historyMessage : historyMessages) {
            if (historyMessage == null
                    || !isHistoryRole(historyMessage.getRole())
                    || historyMessage.getContent() == null
                    || historyMessage.getContent().trim().isEmpty()) {
                continue; // 转换阶段再次兜底过滤非法role和空content。
            }
            toolHistoryMessages.add(ToolChatHistoryMessage.builder()
                    .role(historyMessage.getRole())
                    .content(historyMessage.getContent())
                    .createTime(historyMessage.getCreateTime())
                    .build()); // 按原顺序转换为Tool Calling公共历史消息模型。
        }
        return toolHistoryMessages;
    }

    private void logHistoryMessages(List<ChatMessage> historyMessages) {
        log.info("[ChatContext] load recent history messages"); // 每次 /chat/message 请求都打印历史读取入口日志。
        log.info("[ChatContext] history limit: {}", HISTORY_CONTEXT_LIMIT); // 固定输出当前历史窗口大小，方便验收确认。
        log.info("[ChatContext] history message count: {}", historyMessages.size()); // 打印最终过滤并正序排列后的历史数量。
        for (ChatMessage message : historyMessages) {
            log.info("[ChatContext] history item role: {}, content preview: {}", message.getRole(), previewContent(message.getContent())); // 单条 preview 限制长度，避免日志过大。
        }
    }

    private void logToolHistoryMessages(List<ToolChatHistoryMessage> toolHistoryMessages) {
        log.info("[ChatContext] tool history message count: {}", toolHistoryMessages.size()); // 打印转换后的Tool Calling历史数量。
        for (ToolChatHistoryMessage message : toolHistoryMessages) {
            log.info("[ChatContext] tool history item role: {}, content preview: {}", message.getRole(), previewContent(message.getContent())); // Tool历史preview同样限制长度。
        }
    }

    private String loadMemorySummary(Long conversationId, Long userId) {
        try { // 长期记忆读取失败不能影响 /chat/message 主链路。
            ConversationMemory memory = conversationMemoryService.getByConversationAndUser(conversationId, userId); // 按会话和用户读取已存在的长期记忆，不主动创建。
            String memorySummary = memory == null ? "" : memory.getSummary(); // 没有 memory 记录时使用空字符串。
            logMemorySummary(memorySummary); // 打印长期记忆读取状态和短preview。
            return memorySummary == null ? "" : memorySummary; // 传给ToolCalling前兜底为非null字符串。
        } catch (Exception e) {
            log.warn("[ChatContext] load memory summary failed, conversationId: {}, userId: {}", conversationId, userId, e); // 读取失败只打印warn。
            logMemorySummary(""); // 失败时明确打印未启用长期记忆。
            return ""; // 使用空长期记忆继续当前聊天。
        }
    }

    private void logMemorySummary(String memorySummary) {
        boolean loaded = memorySummary != null && !memorySummary.trim().isEmpty(); // summary 非空才视为成功加载。
        String normalizedSummary = memorySummary == null ? "" : memorySummary.trim(); // 日志长度和preview使用trim后的内容。
        log.info("[ChatContext] memory summary loaded: {}", loaded); // 打印长期记忆是否可用。
        log.info("[ChatContext] memory summary length: {}", normalizedSummary.length()); // 打印长期记忆长度。
        log.info("[ChatContext] memory summary preview: {}", previewContent(normalizedSummary)); // 只打印80字以内预览。
    }

    private String previewContent(String content) {
        if (content == null) {
            return "";
        }
        String normalizedContent = content.replace('\n', ' ').replace('\r', ' '); // 日志预览去掉换行，保持单行可读。
        return normalizedContent.length() <= 80 ? normalizedContent : normalizedContent.substring(0, 80) + "..."; // 只保留前 80 字作为日志预览。
    }

    private void updateConversationTime(Long conversationId, Long userId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>() // 回复完成后刷新会话时间，用于会话列表按最近活跃排序。
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getUpdateTime, LocalDateTime.now()));
    }

    private void updateConversationMemoryAfterAnswer(Long conversationId,
                                                     Long userId,
                                                     String rawUserMessage,
                                                     String assistantAnswer) {
        try { // 长期记忆写入失败不能影响已经完成的前端聊天结果。
            log.info("[ChatMessage] update conversation memory after assistant answer"); // 标记 assistant 回复完成后的长期记忆更新入口。
            log.info("[ChatMessage] conversation memory update submitted"); // 标记已提交给 ConversationMemoryService 处理。
            conversationMemoryService.updateMemoryAfterChat(conversationId, userId, rawUserMessage, assistantAnswer); // 只传当前原始输入和完整助手回复，不传历史拼接字符串。
            log.info("[ChatMessage] conversation memory update done"); // 标记长期记忆更新调用完成。
        } catch (Exception e) {
            log.warn("[ChatMessage] conversation memory update failed, conversationId: {}, userId: {}, error: {}",
                    conversationId, userId, e.getMessage(), e); // 兜底捕获，避免长期记忆异常向外扩散。
        }
    }

    private String buildTitle(String msg) {
        String title = msg == null ? "新会话" : msg.trim();
        if (title.isEmpty()) {
            return "新会话";
        }
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }

    private void sendError(SseEmitter emitter, Throwable error) {
        try { // error 事件尽量发给前端，便于 UI 结束加载态并展示失败原因。
            Map<String, String> errorData = new HashMap<>(); // error 也使用 JSON 对象，保持前端解析一致。
            errorData.put("message", error.getMessage());
            emitter.send(SseEmitter.event().name("error").data(errorData));
        } catch (IOException ignored) {
            // 连接断开时忽略二次发送失败。
        }
        emitter.completeWithError(error);
    }
}
