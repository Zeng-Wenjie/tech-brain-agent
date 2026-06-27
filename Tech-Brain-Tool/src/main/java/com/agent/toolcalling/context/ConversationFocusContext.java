package com.agent.toolcalling.context;

import java.time.LocalDateTime;

/**
 * 会话轻量焦点上下文。
 *
 * <p>仅保留检索/上下文能力所需的两类焦点：RAG 命中文章/笔记焦点、用户上传文件 activeFileFocus。
 * 项目源码文件焦点和最近项目代码目标已经随自建编码工具移除。</p>
 */
public class ConversationFocusContext {

    private String sourceType;
    private Long sourceId;
    private String title;
    private String sourceTool;
    private String query;
    private String fileExt;
    private String fileType;
    private String mimeType;
    private Long fileSize;
    private LocalDateTime updateTime;

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceTool() {
        return sourceTool;
    }

    public void setSourceTool(String sourceTool) {
        this.sourceTool = sourceTool;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getFileExt() {
        return fileExt;
    }

    public void setFileExt(String fileExt) {
        this.fileExt = fileExt;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
