package com.agent.toolcalling.context; // Tool Calling会话焦点上下文包。

import java.time.LocalDateTime; // 保存焦点更新时间。

/**
 * 会话最近命中文档焦点上下文。
 *
 * <p>适用场景：保存当前会话的轻量焦点元信息，支持 RAG 文章焦点、用户上传文件 activeFileFocus、项目源码 projectFileFocus
 * 和最近明确项目目标 recentProjectTarget。</p>
 * <p>当前调用链包括：RagSearchTool 命中文档 -> ConversationFocusService.saveLastHitArticle -> Redis 保存 ARTICLE；
 * ReadFileTool 成功读取用户上传文件 -> ConversationFocusService.saveActiveFileFocus -> Redis 保存 FILE；
 * ReadProjectFileTool 成功读取项目源码文件 -> ConversationFocusService.saveProjectFileFocus -> Redis 保存 PROJECT_FILE；
 * SearchCodeTool 唯一定位项目文件 -> ConversationFocusService.saveRecentProjectTarget -> Redis 保存 PROJECT_TARGET。</p>
 * <p>本类位于 Tech-Brain-Tool 公共模块，只描述通用会话焦点数据，不依赖具体 Tool、Milvus、数据库表或项目文件读取实现。</p>
 */
public class ConversationFocusContext { // 会话最近命中文档焦点DTO。

    private String sourceType; // 来源类型，当前RAG文章命中固定为ARTICLE。

    private Long sourceId; // 来源ID，当前对应articleId。

    private String title; // 来源标题，用于后续总结或弹窗提示。

    private String sourceTool; // 产生该焦点的工具名，当前为ragSearch。

    private String query; // 触发命中的用户检索问题。

    private String fileExt; // 文件焦点扩展名，仅用于 FILE 类型 activeFileFocus。

    private String fileType; // 文件焦点业务类型，仅用于 FILE 类型 activeFileFocus。

    private String mimeType; // 文件焦点 MIME 类型，仅用于 FILE 类型 activeFileFocus。

    private Long fileSize; // 文件焦点大小，仅用于 FILE 类型 activeFileFocus。

    private String path; // 项目文件焦点相对 workspace 路径，仅用于 PROJECT_FILE，不保存服务器绝对路径。

    private String language; // 项目文件焦点语言展示名，仅用于 PROJECT_FILE。
    private String markdownName; // 项目文件焦点 Markdown 代码块语言名，仅用于 PROJECT_FILE。

    private Boolean truncated; // 项目文件最近读取时是否发生截断，仅用于 PROJECT_FILE。

    private String readMode; // 项目文件最近读取模式，SUMMARY 或 FULL，仅用于 PROJECT_FILE。

    private String className; // 最近项目目标类名，仅用于 PROJECT_TARGET 或 Java 项目文件焦点。

    private String targetType; // 最近项目目标类型，例如 CLASS_OR_FILE、TOOL、CONTROLLER，仅用于 PROJECT_TARGET。

    private String confidence; // 最近项目目标置信度，例如 UNIQUE，仅用于 PROJECT_TARGET。

    private LocalDateTime updateTime; // 焦点更新时间。

    public String getSourceType() { // 获取来源类型。
        return sourceType; // 返回sourceType。
    }

    public void setSourceType(String sourceType) { // 设置来源类型。
        this.sourceType = sourceType; // 写入sourceType。
    }

    public Long getSourceId() { // 获取来源ID。
        return sourceId; // 返回sourceId。
    }

    public void setSourceId(Long sourceId) { // 设置来源ID。
        this.sourceId = sourceId; // 写入sourceId。
    }

    public String getTitle() { // 获取标题。
        return title; // 返回标题。
    }

    public void setTitle(String title) { // 设置标题。
        this.title = title; // 写入标题。
    }

    public String getSourceTool() { // 获取来源工具。
        return sourceTool; // 返回sourceTool。
    }

    public void setSourceTool(String sourceTool) { // 设置来源工具。
        this.sourceTool = sourceTool; // 写入sourceTool。
    }

    public String getQuery() { // 获取触发查询。
        return query; // 返回query。
    }

    public void setQuery(String query) { // 设置触发查询。
        this.query = query; // 写入query。
    }

    public String getFileExt() { // 获取文件焦点扩展名。
        return fileExt; // 返回扩展名。
    }

    public void setFileExt(String fileExt) { // 设置文件焦点扩展名。
        this.fileExt = fileExt; // 保存扩展名。
    }

    public String getFileType() { // 获取文件焦点业务类型。
        return fileType; // 返回业务类型。
    }

    public void setFileType(String fileType) { // 设置文件焦点业务类型。
        this.fileType = fileType; // 保存业务类型。
    }

    public String getMimeType() { // 获取文件焦点 MIME 类型。
        return mimeType; // 返回 MIME 类型。
    }

    public void setMimeType(String mimeType) { // 设置文件焦点 MIME 类型。
        this.mimeType = mimeType; // 保存 MIME 类型。
    }

    public Long getFileSize() { // 获取文件焦点大小。
        return fileSize; // 返回文件大小。
    }

    public void setFileSize(Long fileSize) { // 设置文件焦点大小。
        this.fileSize = fileSize; // 保存文件大小。
    }

    public String getPath() { // 获取项目文件相对 workspace 路径。
        return path; // 返回相对路径，不包含服务器绝对路径。
    }

    public void setPath(String path) { // 设置项目文件相对 workspace 路径。
        this.path = path; // 保存相对路径。
    }

    public String getLanguage() { // 获取项目文件语言展示名。
        return language; // 返回语言展示名。
    }

    public void setLanguage(String language) { // 设置项目文件语言展示名。
        this.language = language; // 保存语言展示名。
    }

    public String getMarkdownName() { // 获取项目文件 Markdown 语言名。
        return markdownName; // 返回 Markdown code fence 语言名。
    }

    public void setMarkdownName(String markdownName) { // 设置项目文件 Markdown 语言名。
        this.markdownName = markdownName; // 保存 Markdown code fence 语言名。
    }

    public Boolean getTruncated() { // 获取项目文件最近读取是否截断。
        return truncated; // 返回截断状态。
    }

    public void setTruncated(Boolean truncated) { // 设置项目文件最近读取截断状态。
        this.truncated = truncated; // 保存截断状态。
    }

    public String getReadMode() { // 获取项目文件最近读取模式。
        return readMode; // 返回 SUMMARY 或 FULL。
    }

    public void setReadMode(String readMode) { // 设置项目文件最近读取模式。
        this.readMode = readMode; // 保存读取模式。
    }

    public String getClassName() { // 获取最近项目目标类名。
        return className; // 返回 className。
    }

    public void setClassName(String className) { // 设置最近项目目标类名。
        this.className = className; // 保存 className。
    }

    public String getTargetType() { // 获取最近项目目标类型。
        return targetType; // 返回 targetType。
    }

    public void setTargetType(String targetType) { // 设置最近项目目标类型。
        this.targetType = targetType; // 保存 targetType。
    }

    public String getConfidence() { // 获取最近项目目标置信度。
        return confidence; // 返回 confidence。
    }

    public void setConfidence(String confidence) { // 设置最近项目目标置信度。
        this.confidence = confidence; // 保存 confidence。
    }

    public LocalDateTime getUpdateTime() { // 获取更新时间。
        return updateTime; // 返回updateTime。
    }

    public void setUpdateTime(LocalDateTime updateTime) { // 设置更新时间。
        this.updateTime = updateTime; // 写入updateTime。
    }
}
