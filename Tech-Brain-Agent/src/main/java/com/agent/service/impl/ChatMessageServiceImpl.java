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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_LENGTH = 20;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private AgentService agentService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResponseDTO sendMessage(ChatRequestDTO dto) {
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        Conversation conversation = resolveConversation(dto, userId, now);

        // 先保存用户消息，保证刷新页面能看到本轮提问。
        saveMessage(conversation.getId(), userId, ROLE_USER, dto.getMsg(), now);

        String answer = agentService.chat(dto.getMsg());

        // 再保存AI回复，角色固定为assistant。
        saveMessage(conversation.getId(), userId, ROLE_ASSISTANT, answer, LocalDateTime.now());

        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversation.getId())
                .eq(Conversation::getUserId, userId)
                .set(Conversation::getUpdateTime, LocalDateTime.now()));

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

    private void saveMessage(Long conversationId, Long userId, String role, String content, LocalDateTime createTime) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(createTime);
        chatMessageMapper.insert(message);
    }

    private String buildTitle(String msg) {
        String title = msg == null ? "新会话" : msg.trim();
        if (title.isEmpty()) {
            return "新会话";
        }
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }
}
