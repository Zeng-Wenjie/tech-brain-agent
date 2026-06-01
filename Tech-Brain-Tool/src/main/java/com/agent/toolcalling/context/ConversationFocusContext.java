package com.agent.toolcalling.context; // Tool Calling会话焦点上下文包。

import java.time.LocalDateTime; // 保存焦点更新时间。

/**
 * 会话最近命中文档焦点上下文。
 *
 * <p>适用场景：RAG工具命中某篇文档后，把top1命中文档信息保存为conversation级焦点，后续“这篇笔记”等指代可读取该上下文。</p>
 * <p>当前调用链为：RagSearchTool命中Milvus top1 -> ConversationFocusService.saveLastHitArticle -> Redis保存本DTO对应的JSON结构。</p>
 * <p>本类位于Tech-Brain-Tool公共模块，只描述通用会话焦点数据，不依赖具体Tool、Milvus或数据库表。</p>
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

    public LocalDateTime getUpdateTime() { // 获取更新时间。
        return updateTime; // 返回updateTime。
    }

    public void setUpdateTime(LocalDateTime updateTime) { // 设置更新时间。
        this.updateTime = updateTime; // 写入updateTime。
    }
}
