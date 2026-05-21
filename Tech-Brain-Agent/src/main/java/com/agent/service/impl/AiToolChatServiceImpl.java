package com.agent.service.impl; // Service 实现包声明。

import com.agent.entity.dto.ToolChatRequest; // 引入 Tool Calling 请求 DTO。
import com.agent.entity.dto.ToolChatResponse; // 引入 Tool Calling 响应 DTO。
import com.agent.service.AiToolChatService; // 引入 Tool Calling 服务接口。
import com.agent.toolcalling.client.DeepSeekClient; // 引入Tech-Brain-Tool中的DeepSeek公共HTTP客户端。
import com.agent.toolcalling.config.DeepSeekProperties; // 引入DeepSeek公共配置，读取模型和baseUrl用于日志与请求体。
import com.agent.toolcalling.registry.ToolRegistry; // 引入公共工具注册中心，统一构造tools并分发工具执行。
import com.agent.toolcalling.spi.AiTool; // 引入公共工具接口，按模型返回的tool name执行对应工具。
import com.fasterxml.jackson.databind.JsonNode; // 用于解析模型返回的工具参数 JSON。
import com.fasterxml.jackson.databind.ObjectMapper; // 用于创建本地 Jackson 解析器解析 arguments。
import com.fasterxml.jackson.databind.node.ArrayNode; // 用于手动组装 DeepSeek messages/tools 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 用于手动组装 DeepSeek 请求 JSON 对象。
import lombok.extern.slf4j.Slf4j; // 用于打印验收要求中的关键日志。
import org.springframework.stereotype.Service; // 声明当前类为 Spring Service。

/**
 * Tool Calling测试聊天服务实现。
 *
 * <p>该类属于Tech-Brain-Agent业务模块，负责维持/api/ai/tool-chat现有业务流程：组装DeepSeek请求、解析tool_call、通过ToolRegistry执行工具、再把tool结果发回模型。</p>
 * <p>调用链为：AiToolChatController -> AiToolChatServiceImpl -> DeepSeekClient -> DeepSeek API；当模型返回tool_call时，通过ToolRegistry找到具体AiTool执行。</p>
 * <p>本类不再直接发送DeepSeek HTTP请求，HTTP细节已下沉到Tech-Brain-Tool模块的DeepSeekClient，便于后续复用。</p>
 */
@Slf4j // 生成 log 对象，方便打印 ToolChat 测试日志。
@Service // 注册为 Spring Bean，供 Controller 注入。
public class AiToolChatServiceImpl implements AiToolChatService { // 最小 Tool Calling 闭环实现。

    private static final String SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 第一次调用模型时注入项目助手身份。
            "\n当用户要求“根据知识库”“根据我的笔记”“根据资料”“根据项目文档”回答时，你必须调用 ragSearch 工具。" + // 明确触发工具调用的场景。
            "\n如果用户只是普通闲聊，可以直接回答。" + // 普通闲聊允许模型不调用工具。
            "\n当 ragSearch 返回了知识库内容时，你必须优先根据工具返回内容回答。" + // 强化第二次模型调用对真实 RAG 结果的依赖。
            "\n如果 ragSearch 返回“知识库中没有检索到与该问题相关的内容”，请明确告诉用户知识库中没有找到相关资料，不要编造。" + // 无检索结果时禁止模型补编。
            "\n不要编造知识库内容。"; // 防止模型在没有工具结果时凭空生成知识库内容。

    private final DeepSeekClient deepSeekClient; // 公共DeepSeek HTTP客户端，负责实际发送chat/completions请求。

    private final DeepSeekProperties deepSeekProperties; // DeepSeek配置对象，用于ToolChat日志和请求体model字段。

    private final ToolRegistry toolRegistry; // 公共工具注册中心，负责提供tools定义和按名称查找具体工具。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 使用本地 JSON 解析器，避免 IDE 找不到 ObjectMapper Bean 报红。

    public AiToolChatServiceImpl(DeepSeekClient deepSeekClient, DeepSeekProperties deepSeekProperties, ToolRegistry toolRegistry) { // 构造器注入公共DeepSeek能力和工具注册中心。
        this.deepSeekClient = deepSeekClient; // 保存Tech-Brain-Tool模块提供的DeepSeekClient。
        this.deepSeekProperties = deepSeekProperties; // 保存DeepSeek配置，避免本类重复读取deepseek.*配置。
        this.toolRegistry = toolRegistry; // 保存ToolRegistry，后续统一构建tools并分发tool_call。
    }

    @Override // 实现接口方法。
    public ToolChatResponse toolChat(ToolChatRequest request) { // 执行 POST /api/ai/tool-chat 的完整流程。
        String userMessage = validateAndGetMessage(request); // 先校验请求并取出用户原始问题。
        log.info("[ToolChat] actual model: {}", deepSeekProperties.getModelName()); // 打印运行时实际模型名，确认使用 deepseek-v4-pro。
        log.info("[ToolChat] actual base url: {}", deepSeekProperties.getBaseUrl()); // 打印运行时实际 baseUrl，确认使用 DeepSeek v1 地址。
        log.info("[ToolChat] thinking: disabled"); // 打印 thinking mode 已禁用，避免第二次请求要求 reasoning_content。
        log.info("[ToolChat] user message: {}", userMessage); // 打印验收要求的用户原始问题日志。

        ObjectNode firstRequest = buildFirstRequest(userMessage); // 第一次请求：system + user + tools + thinking disabled。
        log.info("[ToolChat] before first DeepSeek call"); // 标记第一次 DeepSeek 调用即将开始，方便定位失败阶段。
        JsonNode firstResponse = deepSeekClient.chatCompletions(firstRequest); // 通过Tech-Brain-Tool公共客户端调用DeepSeek。
        log.info("[ToolChat] after first DeepSeek call"); // 标记第一次 DeepSeek 调用成功返回。
        JsonNode firstMessage = readFirstMessage(firstResponse); // 取出第一次 assistant message，后续必须保留 tool_calls。
        JsonNode toolCalls = firstMessage.path("tool_calls"); // DeepSeek OpenAI-compatible 响应中的工具调用数组。
        boolean hasToolCall = toolCalls.isArray() && !toolCalls.isEmpty(); // 判断模型是否真的返回了 tool_call。
        log.info("[ToolChat] has tool call: {}", hasToolCall); // 打印验收要求的 tool_call 判断结果。

        if (!hasToolCall) { // 如果模型判断这是普通聊天，直接返回第一次回答。
            return buildResponseAndLog(readContent(firstMessage)); // 不执行任何工具，保持普通闲聊路径最小化。
        }

        JsonNode toolCall = toolCalls.get(0); // 本测试只定义一个工具，因此取第一个工具调用。
        String toolCallId = toolCall.path("id").asText(); // 第二次请求 tool message 必须带回该 tool_call_id。
        String toolName = toolCall.path("function").path("name").asText(); // 读取模型决定调用的工具名。
        String toolArguments = toolCall.path("function").path("arguments").asText(); // 读取模型生成的工具参数 JSON 字符串。
        log.info("[ToolChat] tool name: {}", toolName); // 打印验收要求的工具名日志。
        log.info("[ToolChat] tool call id: {}", toolCallId); // 打印验收要求的工具调用 ID。
        log.info("[ToolChat] tool arguments: {}", toolArguments); // 打印验收要求的工具参数日志。

        JsonNode argumentsNode = parseToolArguments(toolArguments); // 将模型返回的arguments字符串解析成工具可执行的JsonNode。
        String toolResult = executeTool(toolName, argumentsNode); // 通过ToolRegistry分发执行具体工具，不在Service中写死ragSearch逻辑。

        ObjectNode secondRequest = buildSecondRequest(userMessage, firstMessage, toolCallId, toolResult); // 第二次请求保留 assistant tool_calls 并追加 tool result。
        log.info("[ToolChat] before second DeepSeek call"); // 标记第二次 DeepSeek 调用即将开始，方便区分是否是工具结果回传阶段失败。
        JsonNode secondResponse = deepSeekClient.chatCompletions(secondRequest); // 第二次调用仍复用公共DeepSeekClient。
        log.info("[ToolChat] after second DeepSeek call"); // 标记第二次 DeepSeek 调用成功返回。
        JsonNode secondMessage = readFirstMessage(secondResponse); // 取出第二次 assistant 消息。
        return buildResponseAndLog(readContent(secondMessage)); // 返回最终 answer 并打印验收日志。
    }

    private ObjectNode buildFirstRequest(String userMessage) { // 构造第一次 DeepSeek chat/completions 请求。
        ObjectNode request = buildBaseRequest(); // 基础请求包含 model 和 thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 构造 messages 数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 加入系统提示词，要求知识库问题必须调用 ragSearch。
        messages.add(buildMessage("user", userMessage)); // 加入用户原始问题。
        request.set("messages", messages); // 设置第一次请求的消息列表。
        request.set("tools", toolRegistry.buildToolsJson()); // tools统一由ToolRegistry根据已注册AiTool生成，不再手写ragSearch定义。
        return request; // 返回第一次请求体。
    }

    private ObjectNode buildSecondRequest(String userMessage, JsonNode firstMessage, String toolCallId, String toolResult) { // 构造第二次 DeepSeek 请求。
        ObjectNode request = buildBaseRequest(); // 第二次请求同样禁用 thinking mode。
        ArrayNode messages = objectMapper.createArrayNode(); // 构造第二次 messages 数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 保留原 system message。
        messages.add(buildMessage("user", userMessage)); // 保留原 user message。
        messages.add(buildAssistantToolCallMessage(firstMessage)); // 保留第一次 assistant message 中的 tool_calls。
        ObjectNode toolMessage = objectMapper.createObjectNode(); // 构造工具执行结果消息。
        toolMessage.put("role", "tool"); // OpenAI-compatible 协议要求工具结果 role 为 tool。
        toolMessage.put("tool_call_id", toolCallId); // 绑定第一次模型返回的 tool_call_id。
        toolMessage.put("content", toolResult); // 工具执行结果由具体AiTool返回，ragSearch场景下是真实RAG检索结果。
        messages.add(toolMessage); // 将 tool result 追加到上下文。
        request.set("messages", messages); // 设置第二次请求的完整消息列表。
        return request; // 返回第二次请求体。
    }

    private ObjectNode buildBaseRequest() { // 构造 DeepSeek 请求公共字段。
        ObjectNode request = objectMapper.createObjectNode(); // 创建请求体 JSON。
        request.put("model", deepSeekProperties.getModelName()); // 模型固定读取 deepseek.model-name，当前应为 deepseek-v4-pro。
        ObjectNode thinking = objectMapper.createObjectNode(); // 创建 thinking 配置对象。
        thinking.put("type", "disabled"); // 禁用 thinking mode，避免第二次请求必须回传 reasoning_content。
        request.set("thinking", thinking); // 将 thinking disabled 写入请求体。
        return request; // 返回基础请求体。
    }

    private ObjectNode buildMessage(String role, String content) { // 构造普通 system/user 消息。
        ObjectNode message = objectMapper.createObjectNode(); // 创建 message JSON。
        message.put("role", role); // 设置消息角色。
        message.put("content", content); // 设置消息内容。
        return message; // 返回 message。
    }

    private ObjectNode buildAssistantToolCallMessage(JsonNode firstMessage) { // 构造第二次请求需要带回的 assistant tool_calls 消息。
        ObjectNode assistantMessage = objectMapper.createObjectNode(); // 创建 assistant message JSON。
        assistantMessage.put("role", "assistant"); // 保留第一次模型响应的 assistant 角色。
        JsonNode content = firstMessage.get("content"); // 读取第一次 assistant content，tool_call 场景通常为空。
        if (content == null || content.isNull()) { // content 为空时按 OpenAI-compatible 协议显式传 null。
            assistantMessage.putNull("content"); // 避免把空字符串误当成模型正文。
        } else { // content 非空时保留原始内容。
            assistantMessage.set("content", content); // 原样带回第一次 assistant content。
        }
        assistantMessage.set("tool_calls", firstMessage.path("tool_calls")); // 关键：第二次请求必须保留 assistant message 的 tool_calls。
        return assistantMessage; // 返回 assistant tool_calls 消息。
    }

    private JsonNode readFirstMessage(JsonNode response) { // 从 DeepSeek 响应中读取第一个 assistant message。
        JsonNode choices = response.path("choices"); // OpenAI-compatible 响应的候选答案数组。
        if (!choices.isArray() || choices.isEmpty()) { // 没有 choices 说明响应格式异常。
            throw new RuntimeException("DeepSeek响应缺少choices"); // 直接暴露异常，便于测试阶段定位。
        }
        JsonNode message = choices.get(0).path("message"); // 读取第一个候选的 message。
        if (message.isMissingNode()) { // message 缺失说明响应格式异常。
            throw new RuntimeException("DeepSeek响应缺少message"); // 直接暴露异常，便于测试阶段定位。
        }
        return message; // 返回 assistant message。
    }

    private String readContent(JsonNode message) { // 安全读取 assistant message 的 content。
        JsonNode content = message.path("content"); // 读取 content 字段。
        return content.isMissingNode() || content.isNull() ? "" : content.asText(); // content 为空时返回空字符串。
    }

    private JsonNode parseToolArguments(String arguments) { // 将工具参数JSON字符串解析为JsonNode，交给具体AiTool处理字段语义。
        try { // 模型返回的 arguments 是 JSON 字符串，需要容错解析。
            return objectMapper.readTree(arguments); // 将 arguments 字符串解析为具体工具可读取的JSON节点。
        } catch (Exception e) { // arguments 异常时不中断测试闭环。
            log.warn("[ToolChat] parse tool arguments failed: {}", arguments, e); // 打印解析失败原因，方便排查模型返回格式。
            return objectMapper.createObjectNode(); // 返回空对象，由具体工具按必填参数规则给出明确错误。
        }
    }

    private String executeTool(String toolName, JsonNode argumentsNode) { // 根据模型返回的工具名执行具体AiTool。
        AiTool tool = toolRegistry.getTool(toolName); // 从注册中心查找工具，避免Service中硬编码ragSearch分支。
        if (tool == null) { // 未注册工具可能来自模型误判或后续工具配置不一致。
            return "未知工具：" + toolName; // 将未知工具信息作为tool result回传模型，而不是直接破坏闭环。
        }
        log.info("[ToolChat] execute tool: {}", toolName); // 打印验收要求的工具执行日志。
        String toolResult = tool.execute(argumentsNode); // 执行具体工具，ragSearch会进入RagSearchTool并调用Milvus RAG。
        log.info("[ToolChat] tool result: {}", toolResult); // 打印工具最终返回值，便于确认第二次模型调用上下文。
        return toolResult; // 返回给第二次DeepSeek请求的tool message。
    }

    private String validateAndGetMessage(ToolChatRequest request) { // 校验请求体并返回 message。
        if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) { // message 不能为空。
            throw new IllegalArgumentException("message不能为空"); // 让全局异常处理器返回错误响应。
        }
        return request.getMessage().trim(); // 去掉首尾空白，避免无意义 token 进入模型。
    }

    private ToolChatResponse buildResponseAndLog(String answer) { // 统一构造响应并打印最终回答。
        String finalAnswer = answer == null || answer.isBlank() ? "未获取到模型回答。" : answer; // 防御模型返回空文本。
        log.info("[ToolChat] final answer: {}", finalAnswer); // 打印验收要求的最终 AI 回答。
        ToolChatResponse response = new ToolChatResponse(); // 创建响应 DTO。
        response.setAnswer(finalAnswer); // 设置最终 answer 字段。
        return response; // 返回给 Controller，由 Spring 序列化为 JSON。
    }

}
