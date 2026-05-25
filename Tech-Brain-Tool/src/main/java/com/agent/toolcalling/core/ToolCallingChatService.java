package com.agent.toolcalling.core; // Tool Calling公共编排服务包。

import com.agent.toolcalling.context.ToolCallingRequestContext; // Tool Calling工具执行期可读取的请求上下文。

import java.util.List;

/**
 * Tool Calling聊天编排接口。
 *
 * <p>该接口位于Tech-Brain-Tool公共模块，用来抽象“用户消息 -> 大模型判断是否调用工具 -> 执行工具 -> 二次调用模型 -> 流式输出最终回答”的通用流程。</p>
 * <p>调用链为：ChatMessageServiceImpl -> ToolCallingChatService.chatStream(currentMessage, memorySummary, historyMessages, callback) -> 公共编排器调用DeepSeekClient和ToolRegistry -> SSE逐token返回前端。</p>
 * <p>本接口不依赖Agent、Milvus、数据库或具体业务工具；具体工具由业务模块实现AiTool并注册到Spring容器。</p>
 */
public interface ToolCallingChatService { // 公共Tool Calling聊天编排接口。

    void chatStream(String message, ToolCallingStreamCallback callback); // 接收用户问题并以流式回调输出最终回答。

    void chatStream(String currentMessage,
                    List<ToolChatHistoryMessage> historyMessages,
                    ToolCallingStreamCallback callback); // 接收当前问题和结构化历史，并以流式回调输出最终回答。

    void chatStream(String currentMessage,
                    String memorySummary,
                    List<ToolChatHistoryMessage> historyMessages,
                    ToolCallingStreamCallback callback); // 接收当前问题、长期记忆摘要和结构化历史，并以流式回调输出最终回答。

    void chatStream(String currentMessage,
                    String memorySummary,
                    List<ToolChatHistoryMessage> historyMessages,
                    ToolCallingRequestContext requestContext,
                    ToolCallingStreamCallback callback); // 接收当前问题、长期记忆、历史和请求上下文，并以流式回调输出最终回答。
}
