package com.agent.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息附件元信息返回对象。
 *
 * <p>适用场景：会话历史消息查询时，把 chat_message_file 中保存的文件附件快照返回给前端展示。</p>
 * <p>调用链：ConversationController -> ConversationServiceImpl -> ChatMessageFileService.listByMessageIds
 * -> ChatMessageFileVO -> Result 返回前端。</p>
 * <p>边界说明：本 VO 只返回附件展示所需的元信息，不返回 storedName、storagePath 或文件内容。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目 VO 风格一致。
@Schema(description = "聊天消息附件元信息")
public class ChatMessageFileVO { // 前端展示聊天消息附件用的安全视图对象。
    @Schema(description = "用户文件 ID")
    private Long fileId; // user_file.id，用于后续预览或下载接口调用。

    @Schema(description = "附件所属聊天消息 ID")
    private Long messageId; // chat_message.id，便于前端按消息定位附件。

    @Schema(description = "原始文件名")
    private String originalName; // 原始文件名，仅用于展示。

    @Schema(description = "文件扩展名")
    private String fileExt; // 文件扩展名，例如 pdf、png、java。

    @Schema(description = "文件业务类型")
    private String fileType; // 文件业务分类，例如 IMAGE、DOCUMENT、OTHER。

    @Schema(description = "MIME 类型")
    private String mimeType; // 文件 MIME 类型。

    @Schema(description = "文件大小，单位字节")
    private Long fileSize; // 文件大小，单位为字节，前端自行格式化。

    @Schema(description = "附件关联创建时间")
    private LocalDateTime createTime; // 附件关联记录创建时间。
}
