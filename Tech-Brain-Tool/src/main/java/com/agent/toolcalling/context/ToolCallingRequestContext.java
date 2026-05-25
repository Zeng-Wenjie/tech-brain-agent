package com.agent.toolcalling.context; // Tool Calling通用请求上下文包。

/**
 * Tool Calling单次请求上下文。
 *
 * <p>适用场景：业务入口在调用ToolCallingChatService.chatStream时，把当前登录用户和会话信息放入该对象。</p>
 * <p>调用链为：ChatMessageServiceImpl构造ToolCallingRequestContext -> ToolCallingChatServiceImpl在执行AiTool前写入ToolCallingContextHolder
 * -> RagSearchTool、SummarizeArticleTool等业务工具在当前线程内读取上下文。</p>
 * <p>本类位于Tech-Brain-Tool公共模块，只承载通用上下文字段，不依赖Agent、Notes、数据库或具体业务工具。</p>
 */
public class ToolCallingRequestContext { // 单次Tool Calling请求的通用上下文DTO。

    private Long userId; // 当前登录用户ID，由后端UserContext解析后传入，工具不得信任模型参数中的userId。

    private Long conversationId; // 当前会话ID，用于保存会话级最近命中文档焦点。

    public Long getUserId() { // 获取当前登录用户ID。
        return userId; // 返回用户ID。
    }

    public void setUserId(Long userId) { // 设置当前登录用户ID。
        this.userId = userId; // 写入用户ID。
    }

    public Long getConversationId() { // 获取当前会话ID。
        return conversationId; // 返回会话ID。
    }

    public void setConversationId(Long conversationId) { // 设置当前会话ID。
        this.conversationId = conversationId; // 写入会话ID。
    }
}
