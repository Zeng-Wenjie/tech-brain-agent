package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.dto.ChatRequestDTO;
import com.agent.entity.dto.ChatResponseDTO;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.AgentService;
import com.agent.service.ChatMessageService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LENGTH = 20;
    private static final int HISTORY_LIMIT = 8;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private AgentService agentService;

    @Override
    public ChatResponseDTO sendMessage(ChatRequestDTO dto) {
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        Conversation conversation = resolveConversation(dto, userId, now);

        // 调用大模型前读取最近历史，避免长事务包住Gemini调用。
        List<ChatMessage> historyMessages = queryRecentHistory(conversation.getId(), userId);
        String multiTurnQuestion = buildMultiTurnQuestion(dto.getMsg(), historyMessages);

        // 当前用户消息仍然先落库，刷新页面可立即恢复提问。
        saveMessage(conversation.getId(), userId, ROLE_USER, dto.getMsg(), now);

        String answer = agentService.chat(multiTurnQuestion);

        // AI回复继续落库，保持会话完整。
        saveMessage(conversation.getId(), userId, ROLE_ASSISTANT, answer, LocalDateTime.now());
        updateConversationTime(conversation.getId(), userId);

        ChatResponseDTO response = new ChatResponseDTO();
        response.setConversationId(conversation.getId());
        response.setAnswer(answer);
        return response;
    }

    private Conversation resolveConversation(ChatRequestDTO dto, Long userId, LocalDateTime now) {
        if (dto.getConversationId() == null) {
            // 未传会话ID时自动创建会话，标题取用户问题前20个字。
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
}
