package com.agent.service.impl;

import com.agent.entity.ChatMessage;
import com.agent.entity.ChatMessageFile;
import com.agent.entity.Conversation;
import com.agent.entity.Result;
import com.agent.entity.vo.ChatMessageFileVO;
import com.agent.entity.vo.ChatMessageVO;
import com.agent.mapper.ChatMessageMapper;
import com.agent.mapper.ConversationMapper;
import com.agent.service.ChatMessageFileService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversationServiceImpl implements ConversationService {

    @Autowired
    private ConversationMapper conversationMapper; // 会话主表访问入口，所有查询都需要围绕当前用户做隔离。

    @Autowired
    private ChatMessageMapper chatMessageMapper; // 删除会话和查询历史时同步处理 chat_message 明细。

    @Autowired
    private ConversationMemoryService conversationMemoryService; // 删除会话时同步清理 conversation_memory 长期记忆。

    @Autowired
    private ChatMessageFileService chatMessageFileService; // 查询消息附件关联，给会话历史返回 attachments。

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
    public Result<List<ChatMessageVO>> listMessages(Long conversationId) {
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

        return Result.success(toMessageVOList(messages, userId));
    }

    /**
     * 将聊天消息实体转换为前端历史消息 VO，并批量填充附件元信息。
     *
     * <p>调用链：listMessages 查询 chat_message -> listByMessageIds 查询 chat_message_file
     * -> 按 messageId 分组 -> ChatMessageVO.attachments 返回前端。</p>
     */
    private List<ChatMessageVO> toMessageVOList(List<ChatMessage> messages, Long userId) {
        if (messages == null || messages.isEmpty()) { // 没有消息时直接返回空数组。
            return Collections.emptyList(); // 避免前端收到 null。
        }

        List<Long> messageIds = messages.stream()
                .filter(message -> message != null && message.getId() != null) // 只收集有效消息 ID。
                .map(ChatMessage::getId) // 提取 chat_message.id。
                .collect(Collectors.toList()); // 批量查询附件，避免逐条查库。
        List<ChatMessageFile> messageFiles = chatMessageFileService.listByMessageIds(userId, messageIds); // 按当前用户过滤附件。
        Map<Long, List<ChatMessageFile>> fileMap = messageFiles.stream()
                .filter(file -> file != null && file.getMessageId() != null) // 过滤异常附件记录。
                .collect(Collectors.groupingBy(ChatMessageFile::getMessageId)); // 按消息 ID 分组。

        List<ChatMessageVO> result = new ArrayList<>(); // 组装最终返回给前端的消息列表。
        for (ChatMessage message : messages) { // 保持原消息排序。
            if (message == null) { // 防御空元素。
                continue; // 跳过异常消息。
            }
            ChatMessageVO vo = toMessageVO(message); // 复制原有消息字段。
            vo.setAttachments(toFileVOList(fileMap.get(message.getId()))); // 设置附件元信息，无附件时为空数组。
            result.add(vo); // 加入返回列表。
        }
        return result; // 返回包含 attachments 的消息列表。
    }

    /**
     * 转换单条聊天消息，不修改 content，也不附加文件正文。
     */
    private ChatMessageVO toMessageVO(ChatMessage message) {
        ChatMessageVO vo = new ChatMessageVO(); // 创建消息返回对象。
        vo.setId(message.getId()); // 复制消息 ID。
        vo.setConversationId(message.getConversationId()); // 复制会话 ID。
        vo.setUserId(message.getUserId()); // 复制用户 ID。
        vo.setRole(message.getRole()); // 复制消息角色。
        vo.setContent(message.getContent()); // 复制原始消息内容，不拼接附件。
        vo.setCreateTime(message.getCreateTime()); // 复制消息创建时间。
        return vo; // 返回消息 VO。
    }

    /**
     * 转换消息附件元信息，不返回服务器真实路径和存储文件名。
     */
    private List<ChatMessageFileVO> toFileVOList(List<ChatMessageFile> files) {
        if (files == null || files.isEmpty()) { // 当前消息没有附件。
            return Collections.emptyList(); // 让 JSON 返回 []。
        }
        List<ChatMessageFileVO> result = new ArrayList<>(); // 组装附件 VO 列表。
        for (ChatMessageFile file : files) { // 遍历附件关联快照。
            if (file == null) { // 防御空元素。
                continue; // 跳过异常记录。
            }
            ChatMessageFileVO vo = new ChatMessageFileVO(); // 创建附件安全返回对象。
            vo.setFileId(file.getFileId()); // 返回 user_file.id。
            vo.setMessageId(file.getMessageId()); // 返回所属消息 ID。
            vo.setOriginalName(file.getOriginalName()); // 返回原始文件名。
            vo.setFileExt(file.getFileExt()); // 返回扩展名。
            vo.setFileType(file.getFileType()); // 返回文件业务类型。
            vo.setMimeType(file.getMimeType()); // 返回 MIME 类型。
            vo.setFileSize(file.getFileSize()); // 返回文件大小。
            vo.setCreateTime(file.getCreateTime()); // 返回附件关联创建时间。
            result.add(vo); // 加入附件列表。
        }
        return result; // 返回附件元信息列表。
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
