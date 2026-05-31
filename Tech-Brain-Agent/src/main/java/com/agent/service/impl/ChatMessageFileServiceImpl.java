package com.agent.service.impl;

import com.agent.entity.ChatMessageFile;
import com.agent.entity.UserFile;
import com.agent.mapper.ChatMessageFileMapper;
import com.agent.service.ChatMessageFileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天消息附件关联服务实现。
 *
 * <p>适用场景：在用户消息保存成功后，把本轮已校验归属的 user_file 元信息写入 chat_message_file，
 * 并为最近聊天历史加载附件元信息。</p>
 * <p>调用链：ChatMessageServiceImpl -> saveMessageFiles/listByMessageIds -> ChatMessageFileMapper -> chat_message_file 表。</p>
 * <p>边界说明：本实现只保存和查询附件元信息，不读取文件内容，不保存 storagePath，不执行 SQL 脚本，不修改数据库结构。</p>
 */
@Slf4j // 输出 [ChatFile] 前缀日志，不打印文件内容或服务器路径。
@Service // 注册为 Spring Bean，供 ChatMessageServiceImpl 注入。
public class ChatMessageFileServiceImpl extends ServiceImpl<ChatMessageFileMapper, ChatMessageFile> implements ChatMessageFileService { // 聊天附件关联服务实现。

    private static final int NORMAL_STATUS = 1; // 正常附件关联状态。

    private static final int DEFAULT_CONVERSATION_FILE_LIMIT = 20; // 会话附件查询默认最多 20 条。

    @Override // 保存用户消息关联的文件元信息快照。
    public void saveMessageFiles(Long messageId, Long conversationId, Long userId, List<UserFile> files) {
        if (files == null || files.isEmpty()) { // 没有附件时不写关联表。
            return; // 保持普通聊天不新增 chat_message_file。
        }
        if (messageId == null || conversationId == null || userId == null) { // 关联表必须有完整归属信息。
            throw new IllegalArgumentException("聊天附件关联参数不能为空"); // 参数异常说明调用链有问题。
        }

        List<ChatMessageFile> records = new ArrayList<>(); // 构造批量保存记录。
        for (UserFile file : files) { // 遍历已由 ChatMessageServiceImpl 校验过归属的文件。
            if (file == null || file.getId() == null) { // 空文件对象不能写入关联。
                continue; // 跳过异常输入。
            }
            ChatMessageFile record = new ChatMessageFile(); // 创建关联实体。
            record.setMessageId(messageId); // 关联刚保存的用户消息 ID。
            record.setConversationId(conversationId); // 记录会话 ID。
            record.setUserId(userId); // 强制写当前登录用户 ID。
            record.setFileId(file.getId()); // 保存 user_file.id。
            record.setOriginalName(file.getOriginalName()); // 保存文件原始名快照。
            record.setFileExt(file.getFileExt()); // 保存扩展名快照。
            record.setFileType(file.getFileType()); // 保存业务类型快照。
            record.setMimeType(file.getMimeType()); // 保存 MIME 类型快照。
            record.setFileSize(file.getFileSize()); // 保存文件大小快照。
            record.setStatus(NORMAL_STATUS); // 正常关联状态。
            records.add(record); // 加入批量保存列表。
        }
        if (records.isEmpty()) { // 过滤后没有可保存记录。
            return; // 直接返回。
        }

        log.info("[ChatFile] save message files, userId: {}, messageId: {}, count: {}",
                userId, messageId, records.size()); // 只打印用户、消息和数量，不打印 storagePath。
        saveBatch(records); // 使用 MyBatis-Plus 批量保存附件关联。
    }

    @Override // 按消息 ID 批量查询附件关联。
    public List<ChatMessageFile> listByMessageIds(Long userId, List<Long> messageIds) {
        if (userId == null || messageIds == null || messageIds.isEmpty()) { // 缺少用户或消息 ID 时不能查询。
            return Collections.emptyList(); // 返回空列表，避免误查全表。
        }
        Set<Long> distinctMessageIds = new LinkedHashSet<>(); // 去重并保持历史消息顺序。
        for (Long messageId : messageIds) { // 遍历传入消息 ID。
            if (messageId != null) { // 过滤空 ID。
                distinctMessageIds.add(messageId); // 保存有效消息 ID。
            }
        }
        if (distinctMessageIds.isEmpty()) { // 没有有效消息 ID。
            return Collections.emptyList(); // 返回空列表。
        }

        log.info("[ChatFile] load message files, userId: {}, messageCount: {}",
                userId, distinctMessageIds.size()); // 只打印用户和消息数量，不打印文件内容。
        return list(new LambdaQueryWrapper<ChatMessageFile>() // 构造附件关联查询条件。
                .eq(ChatMessageFile::getUserId, userId) // 强制当前用户隔离。
                .in(ChatMessageFile::getMessageId, distinctMessageIds) // 限定最近历史消息 ID。
                .eq(ChatMessageFile::getStatus, NORMAL_STATUS) // 只查正常关联。
                .orderByAsc(ChatMessageFile::getCreateTime)); // 按创建时间正序返回。
    }

    @Override // 查询指定会话最近附件关联。
    public List<ChatMessageFile> listByConversationId(Long userId, Long conversationId, Integer limit) {
        if (userId == null || conversationId == null) { // 缺少用户或会话 ID 时不能查询。
            return Collections.emptyList(); // 返回空列表，避免误查全表。
        }
        int safeLimit = limit == null || limit <= 0 ? DEFAULT_CONVERSATION_FILE_LIMIT : limit; // limit 缺失时使用默认值。
        safeLimit = Math.min(safeLimit, 100); // 防止一次查询过多附件关联。
        return list(new LambdaQueryWrapper<ChatMessageFile>() // 构造会话附件查询条件。
                .eq(ChatMessageFile::getUserId, userId) // 强制当前用户隔离。
                .eq(ChatMessageFile::getConversationId, conversationId) // 限定会话 ID。
                .eq(ChatMessageFile::getStatus, NORMAL_STATUS) // 只查正常关联。
                .orderByDesc(ChatMessageFile::getCreateTime) // 最近附件优先。
                .last("LIMIT " + safeLimit)); // 安全整数 limit。
    }
}
