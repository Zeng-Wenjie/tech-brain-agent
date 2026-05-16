package com.agent.controller; // 控制器包声明。

import com.agent.entity.dto.ToolChatRequest; // 引入 Tool Calling 测试请求 DTO。
import com.agent.entity.dto.ToolChatResponse; // 引入 Tool Calling 测试响应 DTO。
import com.agent.service.AiToolChatService; // 引入 Tool Calling 测试服务。
import io.swagger.v3.oas.annotations.Operation; // 引入接口说明注解。
import io.swagger.v3.oas.annotations.tags.Tag; // 引入 Swagger 分组注解。
import org.springframework.beans.factory.annotation.Autowired; // 引入 Spring 依赖注入注解。
import org.springframework.web.bind.annotation.PostMapping; // 引入 POST 路由注解。
import org.springframework.web.bind.annotation.RequestBody; // 引入请求体绑定注解。
import org.springframework.web.bind.annotation.RequestMapping; // 引入统一路径前缀注解。
import org.springframework.web.bind.annotation.RestController; // 引入 REST 控制器注解。

@RestController // 声明当前类是 REST 接口入口。
@RequestMapping("/api/ai") // 给本轮 AI 测试接口统一加 /api/ai 前缀。
@Tag(name = "AI Tool Calling 测试接口") // 在 Swagger 中单独展示 Tool Calling 测试接口。
public class AiToolChatController { // 最小 Tool Calling 闭环测试控制器。

    @Autowired // 注入 Tool Calling 测试服务。
    private AiToolChatService aiToolChatService; // Controller 只接收请求和返回结果，不实现模型调用细节。

    @Operation(summary = "Tool Calling 假 RAG 测试聊天") // 标记接口用途，便于接口文档识别。
    @PostMapping("/tool-chat") // 对外暴露 POST /api/ai/tool-chat。
    public ToolChatResponse toolChat(@RequestBody ToolChatRequest request) { // 接收用户 message 并返回最终 answer。
        return aiToolChatService.toolChat(request); // 具体两次模型调用和 fakeRagSearch 执行交给 Service。
    }
}
