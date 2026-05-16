package com.agent.service.impl; // Service 实现包声明。

import com.agent.entity.dto.ToolChatRequest; // 引入 Tool Calling 请求 DTO。
import com.agent.entity.dto.ToolChatResponse; // 引入 Tool Calling 响应 DTO。
import com.agent.service.AiToolChatService; // 引入 Tool Calling 服务接口。
import com.fasterxml.jackson.databind.JsonNode; // 用于解析模型返回的工具参数 JSON。
import com.fasterxml.jackson.databind.ObjectMapper; // 用于创建本地 Jackson 解析器解析 arguments。
import com.fasterxml.jackson.databind.node.ArrayNode; // 用于手动组装 DeepSeek messages/tools 数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 用于手动组装 DeepSeek 请求 JSON 对象。
import lombok.extern.slf4j.Slf4j; // 用于打印验收要求中的关键日志。
import org.springframework.beans.factory.annotation.Value; // 引入配置读取注解，用于打印 ToolChat 实际模型配置。
import org.springframework.stereotype.Service; // 声明当前类为 Spring Service。

import java.io.IOException; // 捕获 HTTP 调用和 JSON 解析异常。
import java.net.URI; // 构造 DeepSeek chat completions 请求地址。
import java.net.http.HttpClient; // ToolChat 专用最小 HTTP 客户端，不影响全局模型配置。
import java.net.http.HttpRequest; // 构造 DeepSeek HTTP 请求。
import java.net.http.HttpResponse; // 读取 DeepSeek HTTP 响应。
import java.time.Duration; // 设置 DeepSeek HTTP 请求超时时间。

@Slf4j // 生成 log 对象，方便打印 ToolChat 测试日志。
@Service // 注册为 Spring Bean，供 Controller 注入。
public class AiToolChatServiceImpl implements AiToolChatService { // 最小 Tool Calling 闭环实现。

    private static final String TOOL_NAME = "ragSearch"; // 工具名固定为 ragSearch，和模型工具定义保持一致。

    private static final String SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 第一次调用模型时注入项目助手身份。
            "\n当用户要求“根据知识库”“根据我的笔记”“根据资料”“根据项目文档”回答时，你必须调用 ragSearch 工具。" + // 明确触发工具调用的场景。
            "\n如果用户只是普通闲聊，可以直接回答。" + // 普通闲聊允许模型不调用工具。
            "\n不要编造知识库内容。"; // 防止模型在没有工具结果时凭空生成知识库内容。

    private static final String FAKE_RAG_RESULT = "Tech-Brain 是一个智能知识笔记与 AI Agent 助手系统，支持笔记管理、用户登录、JWT 鉴权、AI 问答和 RAG 检索。"; // 第 1 步只返回固定假 RAG 数据。

    @Value("${deepseek.model-name}") // 从 Spring 实际加载的配置中读取模型名，避免误判运行时模型来源。
    private String actualModel; // /api/ai/tool-chat 当前使用的模型名。

    @Value("${deepseek.base-url}") // 从 Spring 实际加载的配置中读取 DeepSeek baseUrl。
    private String actualBaseUrl; // /api/ai/tool-chat 当前使用的模型服务地址。

    @Value("${deepseek.api-key}") // 复用现有 DeepSeek API Key 配置，不在代码中硬编码密钥。
    private String apiKey; // 仅用于 ToolChat 专用 HTTP 请求的 Authorization 头。

    @Value("${deepseek.timeout-seconds}") // 复用现有超时时间配置，避免请求长时间挂起。
    private Long timeoutSeconds; // DeepSeek HTTP 请求超时时间。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 使用本地 JSON 解析器，避免 IDE 找不到 ObjectMapper Bean 报红。

    private final HttpClient httpClient = HttpClient.newHttpClient(); // 只给 /api/ai/tool-chat 使用的最小 HTTP 客户端。

    @Override // 实现接口方法。
    public ToolChatResponse toolChat(ToolChatRequest request) { // 执行 POST /api/ai/tool-chat 的完整流程。
        String userMessage = validateAndGetMessage(request); // 先校验请求并取出用户原始问题。
        log.info("[ToolChat] actual model: {}", actualModel); // 打印运行时实际模型名，确认使用 deepseek-v4-pro。
        log.info("[ToolChat] actual base url: {}", actualBaseUrl); // 打印运行时实际 baseUrl，确认使用 DeepSeek v1 地址。
        log.info("[ToolChat] thinking: disabled"); // 打印 thinking mode 已禁用，避免第二次请求要求 reasoning_content。
        log.info("[ToolChat] user message: {}", userMessage); // 打印验收要求的用户原始问题日志。

        ObjectNode firstRequest = buildFirstRequest(userMessage); // 第一次请求：system + user + tools + thinking disabled。
        JsonNode firstResponse = callDeepSeek(firstRequest); // 手写 HTTP 调用 DeepSeek，绕开 SDK 对 reasoning_content 的封装缺口。
        JsonNode firstMessage = readFirstMessage(firstResponse); // 取出第一次 assistant message，后续必须保留 tool_calls。
        JsonNode toolCalls = firstMessage.path("tool_calls"); // DeepSeek OpenAI-compatible 响应中的工具调用数组。
        boolean hasToolCall = toolCalls.isArray() && !toolCalls.isEmpty(); // 判断模型是否真的返回了 tool_call。
        log.info("[ToolChat] has tool call: {}", hasToolCall); // 打印验收要求的 tool_call 判断结果。

        if (!hasToolCall) { // 如果模型判断这是普通聊天，直接返回第一次回答。
            return buildResponseAndLog(readContent(firstMessage)); // 不执行 fakeRagSearch，保持普通闲聊路径最小化。
        }

        JsonNode toolCall = toolCalls.get(0); // 本测试只定义一个工具，因此取第一个工具调用。
        String toolCallId = toolCall.path("id").asText(); // 第二次请求 tool message 必须带回该 tool_call_id。
        String toolName = toolCall.path("function").path("name").asText(); // 读取模型决定调用的工具名。
        String toolArguments = toolCall.path("function").path("arguments").asText(); // 读取模型生成的工具参数 JSON 字符串。
        log.info("[ToolChat] tool name: {}", toolName); // 打印验收要求的工具名日志。
        log.info("[ToolChat] tool call id: {}", toolCallId); // 打印验收要求的工具调用 ID。
        log.info("[ToolChat] tool arguments: {}", toolArguments); // 打印验收要求的工具参数日志。

        if (!TOOL_NAME.equals(toolName)) { // 防御性校验，避免未来新增工具时误执行。
            throw new IllegalStateException("Unsupported tool call: " + toolName); // 非 ragSearch 工具不在第 1 步范围内。
        }

        String query = parseQuery(toolArguments, userMessage); // 从 arguments JSON 中解析 query 参数。
        String ragResult = fakeRagSearch(query); // 调用本地假 RAG 方法，暂不接真实向量检索。

        ObjectNode secondRequest = buildSecondRequest(userMessage, firstMessage, toolCallId, ragResult); // 第二次请求保留 assistant tool_calls 并追加 tool result。
        JsonNode secondResponse = callDeepSeek(secondRequest); // 第二次调用模型，让模型基于工具结果组织自然语言回答。
        JsonNode secondMessage = readFirstMessage(secondResponse); // 取出第二次 assistant 消息。
        return buildResponseAndLog(readContent(secondMessage)); // 返回最终 answer 并打印验收日志。
    }

    private ObjectNode buildFirstRequest(String userMessage) { // 构造第一次 DeepSeek chat/completions 请求。
        ObjectNode request = buildBaseRequest(); // 基础请求包含 model 和 thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 构造 messages 数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 加入系统提示词，要求知识库问题必须调用 ragSearch。
        messages.add(buildMessage("user", userMessage)); // 加入用户原始问题。
        request.set("messages", messages); // 设置第一次请求的消息列表。
        ArrayNode tools = objectMapper.createArrayNode(); // 构造 tools 数组。
        tools.add(buildRagSearchTool()); // 加入 ragSearch 工具定义。
        request.set("tools", tools); // 只传 tools，不设置 tool_choice，让模型自主选择工具。
        return request; // 返回第一次请求体。
    }

    private ObjectNode buildSecondRequest(String userMessage, JsonNode firstMessage, String toolCallId, String ragResult) { // 构造第二次 DeepSeek 请求。
        ObjectNode request = buildBaseRequest(); // 第二次请求同样禁用 thinking mode。
        ArrayNode messages = objectMapper.createArrayNode(); // 构造第二次 messages 数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 保留原 system message。
        messages.add(buildMessage("user", userMessage)); // 保留原 user message。
        messages.add(buildAssistantToolCallMessage(firstMessage)); // 保留第一次 assistant message 中的 tool_calls。
        ObjectNode toolMessage = objectMapper.createObjectNode(); // 构造工具执行结果消息。
        toolMessage.put("role", "tool"); // OpenAI-compatible 协议要求工具结果 role 为 tool。
        toolMessage.put("tool_call_id", toolCallId); // 绑定第一次模型返回的 tool_call_id。
        toolMessage.put("content", ragResult); // 工具执行结果就是 fakeRagSearch 的返回文本。
        messages.add(toolMessage); // 将 tool result 追加到上下文。
        request.set("messages", messages); // 设置第二次请求的完整消息列表。
        return request; // 返回第二次请求体。
    }

    private ObjectNode buildBaseRequest() { // 构造 DeepSeek 请求公共字段。
        ObjectNode request = objectMapper.createObjectNode(); // 创建请求体 JSON。
        request.put("model", actualModel); // 模型固定读取 deepseek.model-name，当前应为 deepseek-v4-pro。
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

    private ObjectNode buildRagSearchTool() { // 构造 DeepSeek OpenAI-compatible tools 中的 ragSearch 定义。
        ObjectNode tool = objectMapper.createObjectNode(); // 创建工具对象。
        tool.put("type", "function"); // OpenAI-compatible 工具类型固定为 function。
        ObjectNode function = objectMapper.createObjectNode(); // 创建 function 定义。
        function.put("name", TOOL_NAME); // 工具名必须是 ragSearch。
        function.put("description", "Search the user's private knowledge base and return relevant notes."); // 工具描述保持用户要求。
        ObjectNode parameters = objectMapper.createObjectNode(); // 创建参数 JSON Schema。
        parameters.put("type", "object"); // 参数 schema 顶层类型为 object。
        ObjectNode properties = objectMapper.createObjectNode(); // 创建 properties。
        ObjectNode query = objectMapper.createObjectNode(); // 创建 query 参数定义。
        query.put("type", "string"); // query 参数类型为 string。
        query.put("description", "The search query extracted from the user's question."); // query 参数描述保持用户要求。
        properties.set("query", query); // 将 query 写入 properties。
        parameters.set("properties", properties); // 将 properties 写入参数 schema。
        ArrayNode required = objectMapper.createArrayNode(); // 创建 required 数组。
        required.add("query"); // query 是必填参数。
        parameters.set("required", required); // 将 required 写入参数 schema。
        function.set("parameters", parameters); // 将参数 schema 绑定到 function。
        tool.set("function", function); // 将 function 绑定到 tool。
        return tool; // 返回 ragSearch 工具定义。
    }

    private JsonNode callDeepSeek(ObjectNode requestBody) { // ToolChat 专用 DeepSeek HTTP 调用。
        try { // 捕获网络、序列化和解析异常。
            String requestJson = objectMapper.writeValueAsString(requestBody); // 将请求体序列化为 JSON 字符串。
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(buildChatCompletionsUrl())) // 构造 POST /chat/completions 请求。
                    .timeout(Duration.ofSeconds(timeoutSeconds)) // 使用配置中的超时时间。
                    .header("Authorization", "Bearer " + apiKey) // 使用 DEEPSEEK_API_KEY，不打印密钥。
                    .header("Content-Type", "application/json") // DeepSeek API 请求体是 JSON。
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson)) // 写入 JSON 请求体。
                    .build(); // 生成 HTTP 请求。
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()); // 同步调用 DeepSeek。
            if (response.statusCode() < 200 || response.statusCode() >= 300) { // 非 2xx 视为 DeepSeek 调用失败。
                log.error("[ToolChat] DeepSeek error response: {}", response.body()); // 打印完整错误响应，便于确认 thinking disabled 是否生效。
                throw new RuntimeException("DeepSeek调用失败: HTTP " + response.statusCode()); // 抛出异常交给全局异常处理。
            }
            return objectMapper.readTree(response.body()); // 将 DeepSeek 成功响应解析为 JSON。
        } catch (InterruptedException e) { // 当前线程被中断时需要恢复中断标记。
            Thread.currentThread().interrupt(); // 恢复中断状态。
            throw new RuntimeException("DeepSeek调用被中断", e); // 包装为运行时异常。
        } catch (IOException e) { // 网络 IO 或 JSON 解析失败。
            throw new RuntimeException("DeepSeek调用失败", e); // 包装为运行时异常。
        }
    }

    private String buildChatCompletionsUrl() { // 拼接 DeepSeek chat completions 地址。
        String baseUrl = actualBaseUrl.endsWith("/") ? actualBaseUrl.substring(0, actualBaseUrl.length() - 1) : actualBaseUrl; // 去掉末尾斜杠避免双斜杠。
        return baseUrl + "/chat/completions"; // 返回 https://api.deepseek.com/v1/chat/completions。
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

    private String fakeRagSearch(String query) { // 本地假 RAG 工具方法，后续真实 RAG 会替换这里。
        log.info("[ToolChat] fake rag query: {}", query); // 打印验收要求的 fakeRagSearch 入参。
        log.info("[ToolChat] fake rag result: {}", FAKE_RAG_RESULT); // 打印验收要求的 fakeRagSearch 返回结果。
        return FAKE_RAG_RESULT; // 第 1 步固定返回假知识库内容，不连接 Milvus、Redis 或数据库。
    }

    private String parseQuery(String arguments, String fallbackMessage) { // 从工具参数 JSON 中解析 query。
        try { // 模型返回的 arguments 是 JSON 字符串，需要容错解析。
            JsonNode argumentsNode = objectMapper.readTree(arguments); // 将 arguments 字符串解析为 JSON 节点。
            JsonNode queryNode = argumentsNode.path("query"); // 读取 query 字段。
            String query = queryNode.asText(); // 将 query 节点转换为字符串。
            return query == null || query.isBlank() ? fallbackMessage : query; // query 缺失时退回用户原始问题。
        } catch (Exception e) { // arguments 异常时不中断测试闭环。
            log.warn("[ToolChat] parse tool arguments failed, use fallback message: {}", arguments, e); // 打印解析失败原因，方便排查模型返回格式。
            return fallbackMessage; // 解析失败时用原始问题作为 fakeRagSearch 查询词。
        }
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
