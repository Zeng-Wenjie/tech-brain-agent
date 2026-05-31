package com.agent.toolcalling.context; // Tool Calling 请求上下文包。

/**
 * 聊天本轮附件文件元信息上下文。
 *
 * <p>适用场景：承载用户在 {@code /chat/message} 本轮消息中附带的文件元信息，让 Tool Calling 编排器和后续工具知道用户选择了哪些文件。</p>
 * <p>调用链：ChatMessageServiceImpl 校验 fileIds 归属 -> 转换为 ChatAttachedFileContext
 * -> ToolCallingRequestContext.attachedFiles -> ToolCallingChatServiceImpl 注入模型上下文或传递给后续工具。</p>
 * <p>边界说明：本对象只包含安全元信息，不包含 storagePath，不包含文件内容，不读取或解析文件，不修改数据库。</p>
 */
public class ChatAttachedFileContext { // 单轮聊天附件文件元信息。

    private Long fileId; // user_file.id。

    private String originalName; // 用户上传时的原始文件名。

    private String fileExt; // 文件扩展名，例如 pdf、java、png。

    private String fileType; // 业务文件类型，例如 IMAGE、DOCUMENT、OTHER。

    private String mimeType; // 文件 MIME 类型。

    private Long fileSize; // 文件大小，单位字节。

    public Long getFileId() { // 获取文件 ID。
        return fileId; // 返回文件 ID。
    }

    public void setFileId(Long fileId) { // 设置文件 ID。
        this.fileId = fileId; // 保存文件 ID。
    }

    public String getOriginalName() { // 获取原始文件名。
        return originalName; // 返回原始文件名。
    }

    public void setOriginalName(String originalName) { // 设置原始文件名。
        this.originalName = originalName; // 保存原始文件名。
    }

    public String getFileExt() { // 获取扩展名。
        return fileExt; // 返回扩展名。
    }

    public void setFileExt(String fileExt) { // 设置扩展名。
        this.fileExt = fileExt; // 保存扩展名。
    }

    public String getFileType() { // 获取业务文件类型。
        return fileType; // 返回业务文件类型。
    }

    public void setFileType(String fileType) { // 设置业务文件类型。
        this.fileType = fileType; // 保存业务文件类型。
    }

    public String getMimeType() { // 获取 MIME 类型。
        return mimeType; // 返回 MIME 类型。
    }

    public void setMimeType(String mimeType) { // 设置 MIME 类型。
        this.mimeType = mimeType; // 保存 MIME 类型。
    }

    public Long getFileSize() { // 获取文件大小。
        return fileSize; // 返回文件大小。
    }

    public void setFileSize(Long fileSize) { // 设置文件大小。
        this.fileSize = fileSize; // 保存文件大小。
    }
}
