package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.AgentService;
import com.agent.service.ChatMessageService;
import com.agent.toolcalling.core.ToolCallingChatService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LENGTH = 20;
    private static final int HISTORY_LIMIT = 100; // 控制多轮上下文长度，避免历史过长导致 Prompt 超限或响应变慢。
    private static final long SSE_TIMEOUT = 120000L; // SSE 连接最长等待时间，给流式模型生成保留足够窗口。

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private AgentService agentService; // 复用 AgentService 的 RAG Prompt 构造链路，避免聊天入口重复实现检索逻辑。

    @Autowired
    private StreamingChatLanguageModel streamingChatLanguageModel; // 流式模型按 token 回调，支撑 POST /chat/message 的 SSE 输出。

    @Autowired
    private ToolCallingChatService toolCallingChatService; // Tool Calling 完整答案编排器，仅在 answer-mode=tool-calling 时使用。

    @Value("${chat.message.answer-mode:legacy}")
    private String answerMode; // /chat/message 回答模式开关，默认 legacy，后续第 3.5.4 再启用 tool-calling。

    @Override
    public SseEmitter sendMessage(ChatRequestDTO dto) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT); // HTTP 响应先返回 emitter，后续 token 通过同一连接持续推送。
        Long userId = UserContext.getUserId();

        CompletableFuture.runAsync(() -> { // 流式输出不放进 Controller 线程，也不包长事务，避免连接等待阻塞请求线程。
            try {
                // 异步线程手动恢复当前用户，保证Milvus检索按用户过滤。
                UserContext.setUserId(userId);
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
        log.info("[ChatMessage] route: /chat/message"); // 明确前端真实聊天页当前走 POST /chat/message。
        log.info("[ChatMessage] current answer mode: {}", answerMode); // 打印当前配置的回答模式，确认前端聊天页实际走哪条链路。
        log.info("[ChatMessage] user id: {}", userId); // 打印当前用户，确认后续历史和 Milvus 检索按用户隔离。
        log.info("[ChatMessage] raw user message: {}", dto.getMsg()); // 打印未经多轮拼接的用户原始输入。

        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = resolveConversation(dto, userId, now); // 无会话则新建，有会话则校验归属后复用。
        log.info("[ChatMessage] conversation id: {}", conversation.getId()); // 打印真实会话 ID，便于确认新会话创建或旧会话复用。

        // 读取最近历史，构造多轮问题。
        List<ChatMessage> historyMessages = queryRecentHistory(conversation.getId(), userId);
        String multiTurnQuestion = buildMultiTurnQuestion(dto.getMsg(), historyMessages); // 把最近历史拼入当前问题，补足代词和上下文依赖。
        log.info("[ChatMessage] multi turn question: {}", multiTurnQuestion); // 打印多轮拼接后的问题，便于确认旧链路如何进入 RAG。

        // 用户消息先落库，AI回复生成完成后再落库。
        saveMessage(conversation.getId(), userId, ROLE_USER, dto.getMsg(), now);

        log.info("[ChatMessage] configured answer mode: {}", answerMode); // 打印配置文件中的回答模式，确认当前默认仍是 legacy。
        if ("tool-calling".equalsIgnoreCase(answerMode)) { // 配置为 tool-calling 时切换到新的 Tool Calling 完整答案链路。
            log.info("[ChatMessage] use ToolCallingChatService: true"); // 标记当前 /chat/message 已实际调用 ToolCallingChatService。
            log.info("[ChatMessage] answer mode: tool-calling"); // 明确当前分支为 tool-calling。
            log.info("[ChatMessage] multi turn question: {}", multiTurnQuestion); // 再次打印传给 Tool Calling 的多轮问题，方便定位工具调用输入。
            String answer = toolCallingChatService.chat(multiTurnQuestion); // 调用公共 Tool Calling 编排器，由模型决定是否调用 ragSearch。
            sendFullAnswer(answer, conversation.getId(), userId, emitter); // Tool Calling 返回完整答案后，通过 SSE 一次性推给前端并保存 assistant 消息。
            return; // tool-calling 分支已完成本轮响应，避免继续执行 legacy 流式分支。
        }
        log.info("[ChatMessage] use legacy answer mode"); // 默认模式下明确使用 legacy 链路。
        log.info("[ChatMessage] use legacy buildFinalPrompt: true"); // legacy 分支仍调用 buildFinalPrompt 主动拼 RAG Prompt。
        log.info("[ChatMessage] use streamingChatLanguageModel: true"); // legacy 分支仍由 streamingChatLanguageModel 进行 SSE token 输出。
        // 当前仍是旧 RAG + SSE 流式链路：
        // 后端主动调用 buildFinalPrompt 做 Milvus 检索并拼接 Prompt，
        // 再使用 streamingChatLanguageModel 进行流式输出。
        // 后续第 3.5.4 会通过配置切换到 ToolCallingChatService。
        String finalPrompt = agentService.buildFinalPrompt(multiTurnQuestion); // 多轮问题进入 RAG：先 embedding 检索，再选择纯聊天或 RAG Prompt。
        streamAnswer(finalPrompt, conversation.getId(), userId, emitter);
    }

    private void streamAnswer(String finalPrompt, Long conversationId, Long userId, SseEmitter emitter) {
        StringBuilder fullAnswer = new StringBuilder(); // token 只在内存聚合，完整回复等 onComplete 再一次性落库。

        streamingChatLanguageModel.generate(finalPrompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                try {
                    fullAnswer.append(token); // onNext 只拼接和推送 token，不写数据库，避免高频写库拖慢流式输出。

                    // token 用JSON对象发送，避免前端按纯文本解析时丢片段。
                    Map<String, String> data = new HashMap<>();
                    data.put("content", token);
                    emitter.send(SseEmitter.event().name("message").data(data));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                try {
                    // 只在生成完成后保存完整AI回复，避免每个token写库。
                    saveMessage(conversationId, userId, ROLE_ASSISTANT, fullAnswer.toString(), LocalDateTime.now());
                    updateConversationTime(conversationId, userId);

                    Map<String, Object> doneData = new HashMap<>(); // done 事件返回 conversationId，前端新会话首轮发送后可拿到真实会话 ID。
                    doneData.put("conversationId", conversationId);
                    emitter.send(SseEmitter.event().name("done").data(doneData));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onError(Throwable error) {
                sendError(emitter, error);
            }
        });
    }

    private void sendFullAnswer(String answer, Long conversationId, Long userId, SseEmitter emitter) {
        try {
            log.info("[ChatMessage] send full answer by SSE"); // 标记当前走完整答案一次性推送，后续切换 ToolCallingChatService 时用于排查链路。
            log.info("[ChatMessage] full answer length: {}", answer == null ? 0 : answer.length()); // 打印完整回答长度，便于判断是否拿到模型最终结果。
            Map<String, String> data = new HashMap<>(); // 沿用当前 SSE message 事件的数据结构，前端无需改解析逻辑。
            data.put("content", answer); // 将完整回答作为一次 message 内容发送给前端。
            emitter.send(SseEmitter.event().name("message").data(data)); // 通过 SSE 推送完整回答，兼容前端现有 message 事件监听。

            log.info("[ChatMessage] save assistant message after full answer"); // 标记完整回答发送后开始保存 assistant 消息。
            saveMessage(conversationId, userId, ROLE_ASSISTANT, answer, LocalDateTime.now()); // 完整答案模式下仍保存 assistant 消息，保证聊天历史可追溯。
            updateConversationTime(conversationId, userId); // 回复完成后刷新会话更新时间，保持会话列表排序正确。

            Map<String, Object> doneData = new HashMap<>(); // done 事件携带 conversationId，保持和流式完成事件一致。
            doneData.put("conversationId", conversationId); // 返回会话 ID，前端新会话首轮发送后可拿到真实会话。
            log.info("[ChatMessage] send done event after full answer"); // 标记完整答案模式准备发送 done 事件。
            emitter.send(SseEmitter.event().name("done").data(doneData)); // 通知前端本轮回答结束，前端可关闭加载状态。

            emitter.complete(); // 正常结束 SSE 连接。
        } catch (Exception e) {
            emitter.completeWithError(e); // 任意异常都交给 SSE 错误通道，避免连接悬挂。
        }
    }

    private void validateRequest(ChatRequestDTO dto) {
        if (dto == null || dto.getMsg() == null || dto.getMsg().trim().isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }

    private Conversation resolveConversation(ChatRequestDTO dto, Long userId, LocalDateTime now) {
        if (dto.getConversationId() == null) {
            // 未传会话ID时自动创建会话。
            Conversation conversation = new Conversation();
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

    private List<ChatMessage> queryRecentHistory(Long conversationId, Long userId) {
        // 同时按会话和用户过滤，防止跨用户拼接历史。
        List<ChatMessage> historyMessages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .orderByDesc(ChatMessage::getCreateTime)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + HISTORY_LIMIT)); // 先倒序取最近 N 条，数据库可以利用时间排序快速截断。
        Collections.reverse(historyMessages); // 再 reverse 成真实对话顺序，保证 Prompt 中上下文从旧到新。
        return historyMessages;
    }

    private String buildMultiTurnQuestion(String currentQuestion, List<ChatMessage> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return currentQuestion;
        }

        StringBuilder builder = new StringBuilder(); // 多轮上下文以“用户/助手”角色标记，帮助模型理解追问指代。
        for (ChatMessage message : historyMessages) {
            if (ROLE_USER.equals(message.getRole())) {
                builder.append("用户：");
            } else if (ROLE_ASSISTANT.equals(message.getRole())) {
                builder.append("助手：");
            } else {
                builder.append(message.getRole()).append("：");
            }
            builder.append(message.getContent()).append("\n");
        }
        builder.append("当前用户问题：").append(currentQuestion);
        return builder.toString();
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
            // error 也使用JSON对象，保持前端解析一致。
            Map<String, String> errorData = new HashMap<>();
            errorData.put("message", error.getMessage());
            emitter.send(SseEmitter.event().name("error").data(errorData));
        } catch (IOException ignored) {
            // 连接断开时忽略二次发送失败。
        }
        emitter.completeWithError(error);
    }
}
