package com.agent.service;

import com.agent.entity.ChatMessageFile;
import com.agent.entity.UserFile;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 聊天消息附件关联服务接口。
 *
 * <p>适用场景：保存用户某轮聊天消息关联的文件元信息，并在加载聊天历史时按消息 ID 查询附件元信息。</p>
 * <p>调用链：ChatMessageServiceImpl -> ChatMessageFileService -> ChatMessageFileServiceImpl
 * -> ChatMessageFileMapper -> chat_message_file 表。</p>
 * <p>边界说明：本接口只处理附件关联元数据，不读取文件内容，不解析文件，不返回 storagePath，不修改数据库结构。</p>
 */
public interface ChatMessageFileService extends IService<ChatMessageFile> { // 继承 MyBatis-Plus 通用 Service 能力。

    void saveMessageFiles(Long messageId,
                          Long conversationId,
                          Long userId,
                          List<UserFile> files); // 保存某条用户消息关联的文件元信息快照。

    List<ChatMessageFile> listByMessageIds(Long userId, List<Long> messageIds); // 按当前用户和消息 ID 批量查询正常附件关联。

    List<ChatMessageFile> listByConversationId(Long userId, Long conversationId, Integer limit); // 查询指定会话最近附件关联。
}
