package com.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息文件附件关联实体。
 *
 * <p>适用场景：映射数据库中已经手动创建的 chat_message_file 表，保存某条用户聊天消息关联了哪些用户文件。</p>
 * <p>调用链：ChatMessageServiceImpl 保存 user chat_message 后 -> ChatMessageFileService.saveMessageFiles
 * -> ChatMessageFileMapper -> chat_message_file 表；加载最近历史时再按 messageId 查询附件元信息注入模型上下文。</p>
 * <p>边界说明：本实体只保存文件元信息快照，不保存 storagePath、不保存 accessUrl、不保存文件内容，不执行 SQL，不修改数据库结构。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目实体风格一致。
@TableName("chat_message_file") // 映射数据库已存在的 chat_message_file 表。
public class ChatMessageFile { // 聊天消息附件关联持久化对象。

    @TableId(value = "id", type = IdType.AUTO) // 主键使用数据库自增 ID，禁止使用雪花算法。
    private Long id; // chat_message_file.id。

    private Long messageId; // 关联的 chat_message.id。

    private Long conversationId; // 所属会话 ID。

    private Long userId; // 文件和消息所属用户 ID，用于用户隔离。

    private Long fileId; // 关联的 user_file.id。

    private String originalName; // 文件原始名称快照。

    private String fileExt; // 文件扩展名快照。

    private String fileType; // 文件业务类型快照。

    private String mimeType; // 文件 MIME 类型快照。

    private Long fileSize; // 文件大小快照，单位字节。

    private Integer status; // 关联状态，1 表示正常。

    @TableField(fill = FieldFill.INSERT) // 插入时由 MyBatis-Plus 自动填充创建时间。
    private LocalDateTime createTime; // 创建时间。

    @TableField(fill = FieldFill.INSERT_UPDATE) // 插入和更新时由 MyBatis-Plus 自动填充更新时间。
    private LocalDateTime updateTime; // 更新时间。
}
