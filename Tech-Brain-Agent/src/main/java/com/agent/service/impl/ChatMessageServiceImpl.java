package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.AgentService;
import com.agent.service.ChatMessageService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LENGTH = 20;
    private static final int HISTORY_LIMIT = 8;
    private static final long SSE_TIMEOUT = 120000L;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private AgentService agentService;

    @Autowired
    private StreamingChatLanguageModel streamingChatLanguageModel;

    @Override
    public SseEmitter sendMessage(ChatRequestDTO dto) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        Long userId = UserContext.getUserId();

        CompletableFuture.runAsync(() -> {
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
        validateRequest(dto);

        LocalDateTime now = LocalDateTime.now();
        Conversation conversation = resolveConversation(dto, userId, now);

        // 读取最近历史，构造多轮问题。
        List<ChatMessage> historyMessages = queryRecentHistory(conversation.getId(), userId);
        String multiTurnQuestion = buildMultiTurnQuestion(dto.getMsg(), historyMessages);

        // 用户消息先落库，AI回复生成完成后再落库。
        saveMessage(conversation.getId(), userId, ROLE_USER, dto.getMsg(), now);

        String finalPrompt = agentService.buildFinalPrompt(multiTurnQuestion);
        streamAnswer(finalPrompt, conversation.getId(), userId, emitter);
    }

    private void streamAnswer(String finalPrompt, Long conversationId, Long userId, SseEmitter emitter) {
        StringBuilder fullAnswer = new StringBuilder();

        streamingChatLanguageModel.generate(finalPrompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                try {
                    fullAnswer.append(token);

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

                    Map<String, Object> doneData = new HashMap<>();
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

        Conversation conversation = conversationMapper.selectById(dto.getConversationId());
        if (conversation == null || !userId.equals(conversation.getUserId())) {
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
                .last("LIMIT " + HISTORY_LIMIT));
        Collections.reverse(historyMessages);
        return historyMessages;
    }

    private String buildMultiTurnQuestion(String currentQuestion, List<ChatMessage> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return currentQuestion;
        }

        StringBuilder builder = new StringBuilder();
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
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(createTime);
        chatMessageMapper.insert(message);
    }

    private void updateConversationTime(Long conversationId, Long userId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
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
        try {
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
