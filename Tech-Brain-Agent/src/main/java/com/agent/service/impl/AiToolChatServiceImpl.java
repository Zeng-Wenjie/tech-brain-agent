package com.agent.service.impl; // Agent模块Service实现包。

import com.agent.entity.dto.ToolChatRequest; // /api/ai/tool-chat请求DTO。
import com.agent.entity.dto.ToolChatResponse; // /api/ai/tool-chat响应DTO。
import com.agent.service.AiToolChatService; // Agent模块对Controller暴露的ToolChat业务接口。
import com.agent.toolcalling.core.ToolCallingChatService; // Tech-Brain-Tool中的公共Tool Calling编排器。
import org.springframework.stereotype.Service; // 注册为Spring Service供Controller注入。

/**
 * ToolChat业务入口服务实现。
 *
 * <p>该类位于Tech-Brain-Agent模块，现在只作为/api/ai/tool-chat的业务适配层：接收Controller传入的DTO、取出用户message、调用Tech-Brain-Tool公共编排器、再封装ToolChatResponse。</p>
 * <p>调用链为：AiToolChatController -> AiToolChatServiceImpl -> ToolCallingChatService -> DeepSeekClient/ToolRegistry/RagSearchTool -> 返回最终answer。</p>
 * <p>本类不再负责DeepSeek两次调用、tool_call解析、tools JSON构造或工具执行，避免Agent入口和通用Tool Calling流程继续耦合。</p>
 */
@Service // 保持原有Service Bean，Controller注入方式不变。
public class AiToolChatServiceImpl implements AiToolChatService { // /api/ai/tool-chat的薄业务适配层。

    private final ToolCallingChatService toolCallingChatService; // 公共Tool Calling编排器，承接完整工具调用闭环。

    public AiToolChatServiceImpl(ToolCallingChatService toolCallingChatService) { // 构造器注入公共编排器。
        this.toolCallingChatService = toolCallingChatService; // 保存编排器，toolChat方法只负责转发调用。
    }

    @Override // 实现Agent模块ToolChat服务接口。
    public ToolChatResponse toolChat(ToolChatRequest request) { // 处理POST /api/ai/tool-chat请求。
        String message = request == null ? null : request.getMessage(); // 只从请求体中取message，空值交给公共编排器统一校验。
        String answer = toolCallingChatService.chat(message); // 调用Tech-Brain-Tool公共Tool Calling编排器获取最终回答。
        ToolChatResponse response = new ToolChatResponse(); // 创建响应DTO，保持现有返回结构不变。
        response.setAnswer(answer); // 写入最终answer字段。
        return response; // 返回给Controller，由Spring序列化为JSON。
    }
}
