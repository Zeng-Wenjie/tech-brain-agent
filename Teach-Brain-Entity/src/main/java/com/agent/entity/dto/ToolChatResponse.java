package com.agent.entity.dto; // DTO 包声明，沿用项目现有响应对象命名空间。

import lombok.Data;

@Data // 自动生成 answer 字段的 getter/setter。
public class ToolChatResponse { // POST /api/ai/tool-chat 的响应体。

    private String answer; // 第二次模型基于工具结果生成的最终自然语言回答。
}
