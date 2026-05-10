package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.Result;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.ConversationService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConversationServiceImpl implements ConversationService {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Override
    public Result<Long> createConversation() {
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        // 新建空会话，后续首次提问会写入消息。
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle("新会话");
        conversation.setCreateTime(now);
        conversation.setUpdateTime(now);
        conversationMapper.insert(conversation);

        return Result.success(conversation.getId());
    }

    @Override
    public Result<List<Conversation>> listConversations() {
        Long userId = UserContext.getUserId();

        // 只返回当前用户自己的会话，避免会话列表串号。
        List<Conversation> conversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getUpdateTime));

        return Result.success(conversations);
    }

    @Override
    public Result<List<ChatMessage>> listMessages(Long conversationId) {
        Long userId = UserContext.getUserId();
        if (conversationId == null) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST, "会话ID不能为空");
        }

        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            return Result.error(HttpServletResponse.SC_FORBIDDEN, "无权访问该会话");
        }

        // 读取消息前先校验会话归属，防止通过ID读取别人的聊天记录。
        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getUserId, userId)
                .orderByAsc(ChatMessage::getCreateTime)
                .orderByAsc(ChatMessage::getId));

        return Result.success(messages);
    }
}
