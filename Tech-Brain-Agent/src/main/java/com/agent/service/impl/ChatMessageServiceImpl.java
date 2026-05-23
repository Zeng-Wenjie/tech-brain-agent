package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.ChatMessageService;
import com.agent.toolcalling.core.ToolCallingChatService;
import com.agent.toolcalling.core.ToolCallingStreamCallback;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 前端真实聊天入口 POST /chat/message 的业务实现。
 *
 * <p>当前唯一正式链路为：ChatMessageController -> ChatMessageServiceImpl -> ToolCallingChatService.chatStream(rawUserMessage, callback)
 * -> ToolRegistry/RagSearchTool/Milvus -> DeepSeekClient.streamChatCompletions -> SSE token 返回前端。</p>
 * <p>本类只负责会话归属校验、用户消息落库、SSE token 转发、assistant 完整消息保存和 conversation 更新时间，不再保留 legacy RAG Prompt 分支。</p>
 */
@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LENGTH = 20;
    private static final long SSE_TIMEOUT = 120000L; // SSE 连接最长等待时间，给 DeepSeek 流式生成保留足够窗口。

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private ToolCallingChatService toolCallingChatService; // /chat/message 唯一正式编排器，负责 Tool Calling 与 DeepSeek 流式输出。

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

        saveMessage(conversation.getId(), userId, ROLE_USER, rawUserMessage, now); // 用户消息先落库，assistant 消息在流式完成后落库。

        log.info("[ChatMessage] use ToolCallingChatService.chatStream: true"); // 标记当前 /chat/message 已实际调用 Tool Calling 流式编排器。
        log.info("[ChatMessage] tool-calling raw user message: {}", rawUserMessage); // Tool Calling 暂不接旧字符串多轮拼接，只用当前轮输入。
        StringBuilder fullAnswer = new StringBuilder(); // 流式 token 先在内存聚合，完成后一次性保存 assistant 消息。
        toolCallingChatService.chatStream(rawUserMessage, new ToolCallingStreamCallback() { // 调用公共 Tool Calling 流式编排器。
            @Override
            public void onToken(String token) { // 收到最终回答的增量 token。
                try {
                    log.info("[ChatMessage] stream token received"); // 只记录收到 token，不打印具体内容，避免日志过长。
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
                }
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

    private void updateConversationTime(Long conversationId, Long userId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>() // 回复完成后刷新会话时间，用于会话列表按最近活跃排序。
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getUpdateTime, LocalDateTime.now()));
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
