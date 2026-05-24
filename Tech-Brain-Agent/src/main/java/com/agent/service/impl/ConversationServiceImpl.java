package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.Conversation;
import com.agent.entity.Result;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.ConversationMemoryService;
import com.agent.service.ConversationService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class ConversationServiceImpl implements ConversationService {

    @Autowired
    private ConversationMapper conversationMapper; // 会话主表访问入口，所有查询都需要围绕当前用户做隔离。

    @Autowired
    private ChatMessageMapper chatMessageMapper; // 删除会话和查询历史时同步处理 chat_message 明细。

    @Autowired
    private ConversationMemoryService conversationMemoryService; // 删除会话时同步清理 conversation_memory 长期记忆。

    @Override
    public Result<Long> createConversation() {
        Long userId = UserContext.getUserId(); // 会话归属始终来自登录上下文，不信任前端传用户 ID。
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
        Long userId = UserContext.getUserId(); // 列表接口只查当前用户，防止越权看到其他人的会话标题。

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

        Conversation conversation = conversationMapper.selectById(conversationId); // 先查会话再查消息，权限边界放在会话归属上。
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

    @Override
    @Transactional(rollbackFor = Exception.class) // 删除会话、消息和长期记忆必须在同一事务中完成，避免只删一半产生孤儿数据。
    public Result<String> deleteConversation(Long conversationId) {
        Long userId = UserContext.getUserId(); // 删除操作同样以登录用户为准，防止越权删除。
        if (conversationId == null) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST, "会话ID不能为空");
        }

        // 删除前先确认会话存在。
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return Result.error(HttpServletResponse.SC_NOT_FOUND, "会话不存在");
        }
        if (!userId.equals(conversation.getUserId())) {
            return Result.error(HttpServletResponse.SC_FORBIDDEN, "无权删除该会话");
        }

        log.info("[Conversation] delete conversation, conversationId: {}, userId: {}", conversationId, userId); // 删除会话主流程日志，便于确认 memory 同步清理。

        // 先删当前用户在该会话下的消息，避免误删其他用户数据。
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getUserId, userId));

        // 再删当前用户在该会话下的长期记忆，记录不存在时不报错。
        conversationMemoryService.deleteByConversationAndUser(conversationId, userId);

        // 最后删当前用户自己的会话，事务保证多表删除同时成功或回滚。
        conversationMapper.delete(new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId));

        return Result.success("会话删除成功");
    }
}
