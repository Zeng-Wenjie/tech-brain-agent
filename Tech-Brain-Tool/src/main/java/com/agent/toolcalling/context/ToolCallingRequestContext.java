package com.agent.toolcalling.context;

import com.agent.toolcalling.log.ToolCallLogRecorder;

/**
 * Tool Calling 请求上下文。
 *
 * <p>适用场景：承载单轮聊天请求从 ChatMessageServiceImpl 传入 ToolCallingChatServiceImpl 的上下文信息，
 * 再通过 ToolCallingContextHolder 传递给具体 AiTool。</p>
 * <p>调用链：ChatMessageServiceImpl 创建本对象并写入 traceId、userId、conversationId、currentMessage
 * 和可选的 ToolCallLogRecorder；ToolCallingChatServiceImpl 在 AiTool.execute(arguments) 执行期间把本对象
 * 写入 ToolCallingContextHolder；RagSearchTool 和 SummarizeArticleTool 再从 Holder 读取用户、会话和当前消息信息。</p>
 * <p>边界说明：本类位于 Tech-Brain-Tool 模块，只携带公共上下文字段和日志回调接口，不依赖 Agent Service、
 * Mapper、数据库实现或前端代码。</p>
 */
public class ToolCallingRequestContext { // 单轮 Tool Calling 请求上下文。
    private String traceId; // 同一轮聊天请求内所有工具调用共享的追踪ID。
    private Long userId; // 当前登录用户ID，由后端 UserContext 解析得到。
    private Long conversationId; // 当前会话ID，供工具读取上下文和维护会话焦点。
    private String currentMessage; // 当前用户原始输入，不拼接历史或长期记忆。
    private ToolCallLogRecorder toolCallLogRecorder; // Agent 模块传入的可选工具调用日志回调。

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

    public ToolCallLogRecorder getToolCallLogRecorder() { // 获取可选工具调用日志回调。
        return toolCallLogRecorder; // 返回上层业务模块传入的日志回调。
    }

    public void setToolCallLogRecorder(ToolCallLogRecorder toolCallLogRecorder) { // 设置可选工具调用日志回调。
        this.toolCallLogRecorder = toolCallLogRecorder; // 保存回调，同时避免 Tool 模块直接依赖 Agent 模块。
    }
}
