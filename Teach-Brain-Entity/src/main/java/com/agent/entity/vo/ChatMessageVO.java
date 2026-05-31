package com.agent.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 聊天消息历史返回对象。
 *
 * <p>适用场景：会话消息列表查询时，返回 chat_message 的原有消息字段，并附带当前消息关联的文件元信息。</p>
 * <p>调用链：ConversationController -> ConversationServiceImpl.listMessages -> ChatMessageVO -> Result 返回前端。</p>
 * <p>边界说明：attachments 只包含文件元信息，不包含 storagePath、storedName 或文件正文，不改变 chat_message.content。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目 VO 风格一致。
@Schema(description = "聊天消息历史返回对象")
public class ChatMessageVO { // 前端会话消息列表使用的安全视图对象。
    @Schema(description = "消息 ID")
    private Long id; // chat_message 主键。

    @Schema(description = "会话 ID")
    private Long conversationId; // 消息所属会话 ID。

    @Schema(description = "用户 ID")
    private Long userId; // 消息所属用户 ID。

    @Schema(description = "消息角色")
    private String role; // user 或 assistant。

    @Schema(description = "消息内容")
    private String content; // 数据库原始消息内容，不拼接附件正文。

    @Schema(description = "消息创建时间")
    private LocalDateTime createTime; // 消息创建时间。

    @Schema(description = "消息附件列表")
    private List<ChatMessageFileVO> attachments = Collections.emptyList(); // 附件元信息列表，无附件时返回空数组。
}
