package com.agent.toolcalling.core; // Tool Calling公共编排服务包。

/**
 * Tool Calling聊天编排接口。
 *
 * <p>该接口位于Tech-Brain-Tool公共模块，用来抽象“用户消息 -> 大模型判断是否调用工具 -> 执行工具 -> 二次调用模型 -> 最终回答”的通用流程。</p>
 * <p>调用链为：业务模块Controller/Service接收用户请求 -> 调用ToolCallingChatService.chat(message) -> 公共编排器调用DeepSeekClient和ToolRegistry -> 返回最终answer。</p>
 * <p>本接口不依赖Agent、Milvus、数据库或具体业务工具；具体工具由业务模块实现AiTool并注册到Spring容器。</p>
 */
public interface ToolCallingChatService { // 公共Tool Calling聊天编排接口。

    String chat(String message); // 接收用户原始问题并返回最终自然语言回答。
}
