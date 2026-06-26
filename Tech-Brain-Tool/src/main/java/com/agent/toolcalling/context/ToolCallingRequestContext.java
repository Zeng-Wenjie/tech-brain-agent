package com.agent.toolcalling.context;

import com.agent.toolcalling.devlog.DevActionLogRecorder;
import com.agent.toolcalling.log.ToolCallLogRecorder;

import java.util.List;

/**
 * Tool Calling 请求上下文。
 *
 * <p>适用场景：承载单轮聊天请求从 ChatMessageServiceImpl 传入 ToolCallingChatServiceImpl 的上下文信息，
 * 再通过 ToolCallingContextHolder 传递给具体 AiTool。</p>
 * <p>调用链：ChatMessageServiceImpl 创建本对象并写入 traceId、userId、conversationId、currentMessage、
 * attachedFiles、recentAttachedFiles、activeFileFocus、projectFileFocus、recentProjectTarget 和可选的 ToolCallLogRecorder；ToolCallingChatServiceImpl 在 AiTool.execute(arguments) 执行期间把本对象
 * 写入 ToolCallingContextHolder；RagSearchTool、SummarizeArticleTool 和后续文件工具再从 Holder 读取用户、会话、当前消息和附件元信息。</p>
 * <p>边界说明：本类位于 Tech-Brain-Tool 模块，只携带公共上下文字段和日志回调接口，不依赖 Agent Service、
 * Mapper、数据库实现或前端代码。</p>
 */
public class ToolCallingRequestContext { // 单轮 Tool Calling 请求上下文。
    private String traceId; // 同一轮聊天请求内所有工具调用共享的追踪ID。
    private Long userId; // 当前登录用户ID，由后端 UserContext 解析得到。
    private Long conversationId; // 当前会话ID，供工具读取上下文和维护会话焦点。
    private String currentMessage; // 当前用户原始输入，不拼接历史或长期记忆。
    private List<ChatAttachedFileContext> attachedFiles; // 本轮聊天附带的文件元信息，不包含 storagePath 或文件内容。
    private List<ChatAttachedFileContext> recentAttachedFiles; // 当前会话最近出现过的附件元信息，用于“这个文件/继续分析”等指代解析。
    private ChatAttachedFileContext activeFileFocus; // 当前会话最近一次 readFile 成功读取的文件焦点，不包含 storagePath 或文件内容。
    private ConversationFocusContext projectFileFocus; // 当前会话最近一次 readProjectFile 成功读取的项目源码文件焦点，不包含文件内容或服务器绝对路径。
    private ConversationFocusContext recentProjectTarget; // 当前会话最近一次明确搜索或定位到的项目代码目标，不包含文件内容或服务器绝对路径。
    private ToolCallLogRecorder toolCallLogRecorder; // Agent 模块传入的可选工具调用日志回调。
    private DevActionLogRecorder devActionLogRecorder; // Agent 模块传入的可选开发行为日志回调，用于“保存上一条代码分析结果”。
    private boolean toolsEnabled = true; // 本轮是否允许进入工具路由/Tool Calling；普通聊天路由(/chat/plain)会设为 false，直接走无工具流式回答，避免聊天时误触工具调用。默认 true 保持 /chat/message 原有行为。

    public String getTraceId() { // 获取当前聊天请求追踪ID。
        return traceId; // 返回本轮请求共享的 traceId。
    }

    public void setTraceId(String traceId) { // 设置当前聊天请求追踪ID。
        this.traceId = traceId; // 保存 traceId 供工具日志使用。
    }

    public Long getUserId() { // 获取当前登录用户ID。
        return userId; // 返回用户ID。
    }

    public void setUserId(Long userId) { // 设置当前登录用户ID。
        this.userId = userId; // 保存用户ID。
    }

    public Long getConversationId() { // 获取当前会话ID。
        return conversationId; // 返回会话ID。
    }

    public void setConversationId(Long conversationId) { // 设置当前会话ID。
        this.conversationId = conversationId; // 保存会话ID。
    }

    public String getCurrentMessage() { // 获取当前用户原始输入。
        return currentMessage; // 返回当前消息。
    }

    public void setCurrentMessage(String currentMessage) { // 设置当前用户原始输入。
        this.currentMessage = currentMessage; // 保存当前消息。
    }

    public List<ChatAttachedFileContext> getAttachedFiles() { // 获取本轮附件文件元信息。
        return attachedFiles; // 返回附件元信息列表。
    }

    public void setAttachedFiles(List<ChatAttachedFileContext> attachedFiles) { // 设置本轮附件文件元信息。
        this.attachedFiles = attachedFiles; // 保存附件元信息列表。
    }

    public List<ChatAttachedFileContext> getRecentAttachedFiles() { // 获取当前会话最近附件文件元信息。
        return recentAttachedFiles; // 返回最近附件元信息列表。
    }

    public void setRecentAttachedFiles(List<ChatAttachedFileContext> recentAttachedFiles) { // 设置当前会话最近附件文件元信息。
        this.recentAttachedFiles = recentAttachedFiles; // 保存最近附件元信息列表，不包含 storagePath 或文件内容。
    }

    public ChatAttachedFileContext getActiveFileFocus() { // 获取当前会话最近成功读取的文件焦点。
        return activeFileFocus; // 返回 activeFileFocus。
    }

    public void setActiveFileFocus(ChatAttachedFileContext activeFileFocus) { // 设置当前会话最近成功读取的文件焦点。
        this.activeFileFocus = activeFileFocus; // 保存文件焦点元信息，不包含 storagePath 或文件内容。
    }

    public ConversationFocusContext getProjectFileFocus() { // 获取当前会话最近成功读取的项目源码文件焦点。
        return projectFileFocus; // 返回 projectFileFocus。
    }

    public void setProjectFileFocus(ConversationFocusContext projectFileFocus) { // 设置当前会话最近成功读取的项目源码文件焦点。
        this.projectFileFocus = projectFileFocus; // 保存项目文件焦点元信息，不包含文件内容或服务器绝对路径。
    }

    public ConversationFocusContext getRecentProjectTarget() { // 获取最近明确项目代码目标。
        return recentProjectTarget; // 返回 recentProjectTarget。
    }

    public void setRecentProjectTarget(ConversationFocusContext recentProjectTarget) { // 设置最近明确项目代码目标。
        this.recentProjectTarget = recentProjectTarget; // 保存最近项目目标元信息，不包含文件内容或服务器绝对路径。
    }

    public ToolCallLogRecorder getToolCallLogRecorder() { // 获取可选工具调用日志回调。
        return toolCallLogRecorder; // 返回上层业务模块传入的日志回调。
    }

    public void setToolCallLogRecorder(ToolCallLogRecorder toolCallLogRecorder) { // 设置可选工具调用日志回调。
        this.toolCallLogRecorder = toolCallLogRecorder; // 保存回调，同时避免 Tool 模块直接依赖 Agent 模块。
    }

    public DevActionLogRecorder getDevActionLogRecorder() { // 获取可选开发行为日志回调。
        return devActionLogRecorder; // 返回上层业务模块传入的开发日志回调。
    }

    public void setDevActionLogRecorder(DevActionLogRecorder devActionLogRecorder) { // 设置可选开发行为日志回调。
        this.devActionLogRecorder = devActionLogRecorder; // 保存回调，同时避免 Tool 模块直接依赖 Agent 模块。
    }

    public boolean isToolsEnabled() { // 本轮是否允许进入工具路由。
        return toolsEnabled; // 返回工具开关，false 表示普通聊天直接走无工具回答。
    }

    public void setToolsEnabled(boolean toolsEnabled) { // 设置本轮是否允许进入工具路由。
        this.toolsEnabled = toolsEnabled; // 普通聊天路由设为 false，避免误触工具调用。
    }
}
