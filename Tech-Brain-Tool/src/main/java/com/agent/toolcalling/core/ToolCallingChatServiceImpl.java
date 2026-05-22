package com.agent.toolcalling.core; // Tool Calling公共编排服务包。

import com.agent.toolcalling.client.DeepSeekClient; // 公共DeepSeek HTTP客户端，负责实际chat/completions调用。
import com.agent.toolcalling.config.DeepSeekProperties; // 读取deepseek.model-name/base-url等配置，继续保持deepseek-v4-pro。
import com.agent.toolcalling.registry.ToolRegistry; // 公共工具注册中心，负责构造tools JSON和按名称查找工具。
import com.agent.toolcalling.spi.AiTool; // 公共工具接口，模型返回tool_call后由这里统一分发执行。
import com.fasterxml.jackson.databind.JsonNode; // 解析DeepSeek响应和tool arguments。
import com.fasterxml.jackson.databind.ObjectMapper; // 构造DeepSeek请求体messages/tools JSON。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造messages数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造请求体和message对象。
import lombok.extern.slf4j.Slf4j; // 输出ToolChat验收日志。
import org.springframework.stereotype.Service; // 注册为Spring Service，供Agent模块注入。

/**
 * Tool Calling聊天公共编排器。
 *
 * <p>该类位于Tech-Brain-Tool公共模块，承接原AiToolChatServiceImpl中的通用Tool Calling主流程：构造DeepSeek请求、判断tool_call、通过ToolRegistry执行工具、把tool result回传模型并读取最终回答。</p>
 * <p>调用链为：AiToolChatServiceImpl -> ToolCallingChatServiceImpl.chat(message) -> DeepSeekClient第一次调用 -> ToolRegistry.getTool(toolName) -> AiTool.execute(arguments) -> DeepSeekClient第二次调用。</p>
 * <p>本类只负责编排协议和工具分发，不依赖AgentService、Milvus、数据库或任何具体业务工具；ragSearch等业务工具仍由Tech-Brain-Agent模块提供。</p>
 */
@Slf4j // 生成日志对象，输出[ToolChat]前缀的调试日志。
@Service // 作为公共编排器注册到Spring容器。
public class ToolCallingChatServiceImpl implements ToolCallingChatService { // 默认Tool Calling聊天编排实现。

    private static final String SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 边界优化版system prompt，定义助手身份。
            "\n你可以根据用户问题选择合适的工具，但不要滥用工具。" +
            "\n\n当前可用工具：" +
            "\n1. ragSearch：用于检索用户的私人知识库、笔记、文章和项目资料。" +
            "\n\n调用 ragSearch 的规则：" +
            "\n- 当用户明确要求“根据知识库”“根据我的知识库”“根据我的笔记”“根据资料”“根据项目文档”“根据我保存的内容”“查我的知识库”“从我的文章里找”等内容时，必须调用 ragSearch。" +
            "\n- 当用户询问 Tech-Brain 项目中已经保存的资料、笔记内容、文章内容、项目文档内容时，必须调用 ragSearch。" +
            "\n- 当用户只是普通闲聊、打招呼、感谢、询问通用知识、普通编程问题时，不要调用 ragSearch，直接回答。" +
            "\n- 如果用户没有明确要求基于知识库或个人资料回答，不要为了普通问题主动调用 ragSearch。" +
            "\n\n回答规则：" +
            "\n- 如果 ragSearch 返回了知识库内容，你必须优先根据工具返回内容回答。" +
            "\n- 如果 ragSearch 返回“知识库中没有检索到与该问题相关的内容”，你必须明确告诉用户知识库中没有找到相关资料，不要编造。" +
            "\n- 不要编造知识库内容。" +
            "\n- 不要声称看过知识库，除非你确实调用了 ragSearch 并拿到了工具结果。";

    private final DeepSeekClient deepSeekClient; // 负责发送DeepSeek HTTP请求。

    private final ToolRegistry toolRegistry; // 负责提供tools定义并执行工具分发。

    private final DeepSeekProperties deepSeekProperties; // 负责提供model/baseUrl等运行配置。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 公共编排器自带JSON工具，避免依赖业务模块额外声明ObjectMapper Bean。

    public ToolCallingChatServiceImpl(DeepSeekClient deepSeekClient,
                                      ToolRegistry toolRegistry,
                                      DeepSeekProperties deepSeekProperties) { // 构造器注入公共编排所需依赖。
        this.deepSeekClient = deepSeekClient; // 保存DeepSeek客户端。
        this.toolRegistry = toolRegistry; // 保存工具注册中心。
        this.deepSeekProperties = deepSeekProperties; // 保存DeepSeek配置。
    }

    @Override // 实现公共聊天编排入口。
    public String chat(String message) { // 接收用户消息并返回最终answer。
        String userMessage = validateAndGetMessage(message); // 校验并规整用户输入，避免空消息进入模型。
        log.info("[ToolChat] actual model: {}", deepSeekProperties.getModelName()); // 保留模型日志，确认运行时仍是deepseek-v4-pro。
        log.info("[ToolChat] actual base url: {}", deepSeekProperties.getBaseUrl()); // 保留baseUrl日志，便于排查环境配置。
        log.info("[ToolChat] thinking: disabled"); // 明确当前请求仍禁用thinking mode。
        log.info("[ToolChat] tool boundary prompt: enabled"); // 标记当前使用的是工具调用边界优化后的system prompt。
        log.info("[ToolChat] user message: {}", userMessage); // 打印用户原始问题。

        ObjectNode firstRequest = buildFirstRequest(userMessage); // 第一次请求包含system、user、tools和thinking disabled。
        log.info("[ToolChat] before first DeepSeek call"); // 标记第一次模型调用开始。
        JsonNode firstResponse = deepSeekClient.chatCompletions(firstRequest); // 第一次调用让模型判断是否需要tool_call。
        log.info("[ToolChat] after first DeepSeek call"); // 标记第一次模型调用成功返回。

        JsonNode firstMessage = readFirstMessage(firstResponse); // 读取第一次assistant message。
        JsonNode toolCalls = firstMessage.path("tool_calls"); // 读取工具调用数组。
        boolean hasToolCall = toolCalls.isArray() && !toolCalls.isEmpty(); // 判断是否包含tool_call。
        log.info("[ToolChat] has tool call: {}", hasToolCall); // 打印tool_call判断结果。

        if (!hasToolCall) { // 普通闲聊或模型未选择工具时直接返回第一次回答。
            return buildAnswerAndLog(readContent(firstMessage)); // 读取自然语言回答并打印final answer。
        }

        JsonNode toolCall = toolCalls.get(0); // 当前闭环只处理第一个tool_call，后续多工具可扩展为循环。
        String toolCallId = toolCall.path("id").asText(); // 第二次请求tool message必须带回该ID。
        String toolName = toolCall.path("function").path("name").asText(); // 读取模型选择的工具名。
        String toolArguments = toolCall.path("function").path("arguments").asText(); // 读取模型生成的工具参数JSON字符串。
        log.info("[ToolChat] tool name: {}", toolName); // 打印工具名。
        log.info("[ToolChat] tool call id: {}", toolCallId); // 打印工具调用ID。
        log.info("[ToolChat] tool arguments: {}", toolArguments); // 打印工具参数。

        JsonNode argumentsNode = parseToolArguments(toolArguments); // 解析arguments字符串为JsonNode。
        String toolResult = executeTool(toolName, argumentsNode); // 通过ToolRegistry执行具体AiTool。

        ObjectNode secondRequest = buildSecondRequest(userMessage, firstMessage, toolCallId, toolResult); // 第二次请求追加assistant tool_calls和tool result。
        log.info("[ToolChat] before second DeepSeek call"); // 标记第二次模型调用开始。
        JsonNode secondResponse = deepSeekClient.chatCompletions(secondRequest); // 第二次调用让模型基于工具结果生成最终回答。
        log.info("[ToolChat] after second DeepSeek call"); // 标记第二次模型调用成功返回。
        JsonNode secondMessage = readFirstMessage(secondResponse); // 读取第二次assistant message。
        return buildAnswerAndLog(readContent(secondMessage)); // 返回最终answer并打印日志。
    }

    private ObjectNode buildFirstRequest(String userMessage) { // 构造第一次DeepSeek请求。
        ObjectNode request = buildBaseRequest(); // 基础请求写入model和thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 创建messages数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 写入多工具system prompt。
        messages.add(buildMessage("user", userMessage)); // 写入用户消息。
        request.set("messages", messages); // 设置第一次请求消息。
        request.set("tools", toolRegistry.buildToolsJson()); // tools由注册中心根据所有AiTool统一生成。
        return request; // 返回第一次请求体。
    }

    private ObjectNode buildSecondRequest(String userMessage, JsonNode firstMessage, String toolCallId, String toolResult) { // 构造第二次DeepSeek请求。
        ObjectNode request = buildBaseRequest(); // 第二次请求同样保留model和thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 创建messages数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 保留system prompt，约束最终回答。
        messages.add(buildMessage("user", userMessage)); // 保留原始用户问题。
        messages.add(buildAssistantToolCallMessage(firstMessage)); // 保留第一次assistant message中的tool_calls。
        ObjectNode toolMessage = objectMapper.createObjectNode(); // 创建tool result消息。
        toolMessage.put("role", "tool"); // OpenAI-compatible协议要求工具结果role为tool。
        toolMessage.put("tool_call_id", toolCallId); // 绑定第一次返回的tool_call_id。
        toolMessage.put("content", toolResult); // 写入工具执行结果。
        messages.add(toolMessage); // 将工具结果追加到对话上下文。
        request.set("messages", messages); // 设置第二次请求消息。
        return request; // 返回第二次请求体。
    }

    private ObjectNode buildBaseRequest() { // 构造DeepSeek请求公共字段。
        ObjectNode request = objectMapper.createObjectNode(); // 创建请求体JSON。
        request.put("model", deepSeekProperties.getModelName()); // 模型名继续读取deepseek.model-name。
        ObjectNode thinking = objectMapper.createObjectNode(); // 创建thinking配置对象。
        thinking.put("type", "disabled"); // 保留thinking disabled，避免第二次请求要求reasoning_content。
        request.set("thinking", thinking); // 写入thinking配置。
        return request; // 返回基础请求体。
    }

    private ObjectNode buildMessage(String role, String content) { // 构造普通system/user消息。
        ObjectNode message = objectMapper.createObjectNode(); // 创建message节点。
        message.put("role", role); // 写入消息角色。
        message.put("content", content); // 写入消息内容。
        return message; // 返回message节点。
    }

    private ObjectNode buildAssistantToolCallMessage(JsonNode firstMessage) { // 构造第二次请求需要回传的assistant tool_call消息。
        ObjectNode assistantMessage = objectMapper.createObjectNode(); // 创建assistant消息。
        assistantMessage.put("role", "assistant"); // 角色必须保持assistant。
        JsonNode content = firstMessage.get("content"); // 读取第一次assistant content。
        if (content == null || content.isNull()) { // tool_call场景content通常为null。
            assistantMessage.putNull("content"); // 显式传null，避免协议语义变化。
        } else { // content非空时保留原始内容。
            assistantMessage.set("content", content); // 原样写回content。
        }
        assistantMessage.set("tool_calls", firstMessage.path("tool_calls")); // 关键：第二次请求必须保留第一次assistant的tool_calls。
        return assistantMessage; // 返回assistant tool_call消息。
    }

    private JsonNode readFirstMessage(JsonNode response) { // 从DeepSeek响应中读取第一个assistant message。
        JsonNode choices = response.path("choices"); // OpenAI-compatible响应的候选数组。
        if (!choices.isArray() || choices.isEmpty()) { // 没有choices说明响应格式不符合预期。
            throw new RuntimeException("DeepSeek响应缺少choices"); // 抛出明确错误便于排查。
        }
        JsonNode message = choices.get(0).path("message"); // 读取第一个候选的message。
        if (message.isMissingNode()) { // message缺失说明响应格式异常。
            throw new RuntimeException("DeepSeek响应缺少message"); // 抛出明确错误便于排查。
        }
        return message; // 返回assistant message。
    }

    private String readContent(JsonNode message) { // 读取assistant message中的content。
        JsonNode content = message.path("content"); // 获取content节点。
        return content.isMissingNode() || content.isNull() ? "" : content.asText(); // content为空时返回空字符串。
    }

    private JsonNode parseToolArguments(String arguments) { // 解析模型返回的tool arguments。
        try { // arguments是JSON字符串，需要转换为JsonNode交给AiTool读取。
            return objectMapper.readTree(arguments); // 返回解析后的arguments节点。
        } catch (Exception e) { // 模型参数格式异常时不中断编排器本身。
            log.warn("[ToolChat] parse tool arguments failed: {}", arguments, e); // 打印异常方便排查模型输出。
            return objectMapper.createObjectNode(); // 返回空对象，由具体工具的必填参数校验给出明确错误。
        }
    }

    private String executeTool(String toolName, JsonNode argumentsNode) { // 根据tool name执行具体工具。
        AiTool tool = toolRegistry.getTool(toolName); // 从注册中心查找工具实现。
        if (tool == null) { // 模型返回了未注册工具。
            return "未知工具：" + toolName; // 将未知工具作为tool result回传，保证闭环可继续。
        }
        log.info("[ToolChat] execute tool: {}", toolName); // 打印工具执行日志。
        String toolResult = tool.execute(argumentsNode); // 执行具体工具，ragSearch会进入Agent模块的RagSearchTool。
        log.info("[ToolChat] tool result: {}", toolResult); // 打印工具结果，便于确认二次调用上下文。
        return toolResult; // 返回工具结果。
    }

    private String validateAndGetMessage(String message) { // 校验用户消息。
        if (message == null || message.trim().isEmpty()) { // message不能为空。
            throw new IllegalArgumentException("message不能为空"); // 保持原接口的基础校验语义。
        }
        return message.trim(); // 去除首尾空白，减少无效token。
    }

    private String buildAnswerAndLog(String answer) { // 统一处理最终answer和日志。
        String finalAnswer = answer == null || answer.isBlank() ? "未获取到模型回答。" : answer; // 防御模型返回空文本。
        log.info("[ToolChat] final answer: {}", finalAnswer); // 打印最终回答。
        return finalAnswer; // 返回最终answer。
    }
}
