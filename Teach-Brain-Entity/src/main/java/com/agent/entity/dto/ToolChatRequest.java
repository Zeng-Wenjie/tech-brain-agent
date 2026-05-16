package com.agent.entity.dto; // DTO 包声明，沿用项目现有请求对象命名空间。

import lombok.Data;

@Data // 自动生成 message 字段的 getter/setter。
public class ToolChatRequest { // POST /api/ai/tool-chat 的请求体。

    private String message; // 用户原始问题，第一次模型调用会直接使用该内容。
}
