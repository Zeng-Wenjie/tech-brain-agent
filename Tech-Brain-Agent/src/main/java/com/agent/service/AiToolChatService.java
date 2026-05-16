package com.agent.service; // Service 接口包声明。

import com.agent.entity.dto.ToolChatRequest; // 引入 Tool Calling 请求 DTO。
import com.agent.entity.dto.ToolChatResponse; // 引入 Tool Calling 响应 DTO。

public interface AiToolChatService { // 定义最小 Tool Calling 测试服务边界。

    ToolChatResponse toolChat(ToolChatRequest request); // 执行一次带 ragSearch 工具的聊天闭环。
}
