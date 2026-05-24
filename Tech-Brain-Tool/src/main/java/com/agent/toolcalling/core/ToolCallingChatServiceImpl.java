package com.agent.toolcalling.core; // Tool Calling公共编排服务包。

import com.agent.toolcalling.client.DeepSeekClient; // 公共DeepSeek HTTP客户端，负责实际chat/completions调用。
import com.agent.toolcalling.client.DeepSeekStreamCallback; // DeepSeek底层流式回调，chatStream会适配成ToolCallingStreamCallback。
import com.agent.toolcalling.config.DeepSeekProperties; // 读取deepseek.model-name/base-url等配置，继续保持deepseek-v4-pro。
import com.agent.toolcalling.registry.ToolRegistry; // 公共工具注册中心，负责构造tools JSON和按名称查找工具。
import com.agent.toolcalling.spi.AiTool; // 公共工具接口，模型返回tool_call后由这里统一分发执行。
import com.fasterxml.jackson.databind.JsonNode; // 解析DeepSeek响应和tool arguments。
import com.fasterxml.jackson.databind.ObjectMapper; // 构造DeepSeek请求体messages/tools JSON。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造messages数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 构造请求体和message对象。
import lombok.extern.slf4j.Slf4j; // 输出ToolChatStream验收日志。
import org.springframework.stereotype.Service; // 注册为Spring Service，供Agent模块注入。

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Tool Calling聊天公共编排器。
 *
 * <p>该类位于Tech-Brain-Tool公共模块，承接通用Tool Calling流式主流程：构造DeepSeek请求、判断tool_call、通过ToolRegistry执行工具、把tool result回传模型并流式输出最终回答。</p>
 * <p>调用链为：ChatMessageServiceImpl -> ToolCallingChatServiceImpl.chatStream(currentMessage, historyMessages, callback) -> DeepSeekClient第一次调用 -> ToolRegistry.getTool(toolName) -> AiTool.execute(arguments) -> DeepSeekClient第二次流式调用。</p>
 * <p>本类只负责编排协议和工具分发，不依赖AgentService、Milvus、数据库或任何具体业务工具；ragSearch等业务工具仍由Tech-Brain-Agent模块提供。</p>
 */
@Slf4j // 生成日志对象，输出[ToolChatStream]前缀的调试日志。
@Service // 作为公共编排器注册到Spring容器。
public class ToolCallingChatServiceImpl implements ToolCallingChatService { // 默认Tool Calling聊天编排实现。

    private static final String ROLE_USER = "user"; // Tool历史中允许进入模型上下文的用户角色。

    private static final String ROLE_ASSISTANT = "assistant"; // Tool历史中允许进入模型上下文的助手角色。

    private static final int HISTORY_MESSAGE_LIMIT = 6; // Tool Calling最终回答阶段最多注入最近6条有效历史。

    private static final int HISTORY_CONTENT_MAX_LENGTH = 1200; // 单条历史最多保留1200字，避免超长上下文污染模型。

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

    private static final String NO_TOOL_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 普通流式回答专用prompt，明确本轮没有工具结果。
            "\n本轮没有调用任何工具，也没有检索知识库。" +
            "\n你可以结合历史上下文理解用户追问。" +
            "\n如果当前问题是追问，并且历史消息中有相关上下文，你可以结合历史上下文回答。" +
            "\n当用户说“这个”“它”“上面那个”“刚才那个”“这段代码”等表达时，需要根据最近历史判断指代对象。" +
            "\n如果回答基于历史，可以说“结合上文”或“根据刚才的代码”，不要说“根据知识库”。" +
            "\n不能把历史内容伪装成新一轮知识库检索结果。" +
            "\n如果历史和当前问题冲突，以当前问题为准。" +
            "\n不要声称“根据你的知识库”“根据你的笔记”“根据资料”。" +
            "\n普通闲聊、通用知识和普通编程问题按常规能力直接回答。";

    private static final String RAG_SEARCH_TOOL_NAME = "ragSearch"; // 强制知识库路由时固定执行的工具名。

    private static final String FORCED_RAG_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行ragSearch后的回答约束。
            "\n用户明确要求根据个人知识库、笔记或资料回答。" +
            "\n后端已经强制执行 ragSearch 工具。" +
            "\n工具结果优先级最高，你必须严格基于 ragSearch 返回的知识库内容回答。" +
            "\n历史上下文只用于理解指代和追问，不允许用历史替代工具结果。" +
            "\n如果工具结果说明没有检索到相关内容，请明确说明没有找到，不要从历史或常识里编造。";

    private static final String TOOL_RESULT_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 模型主动tool_call后的最终回答约束。
            "\n本轮已经执行了工具调用。" +
            "\n工具结果优先级高于历史上下文。" +
            "\n历史上下文只用于理解指代和追问，不允许用历史替代工具结果。" +
            "\n如果工具结果说明没有相关内容，请明确说明没有找到，不要编造。";

    private static final String[] FORCE_RAG_KEYWORDS = { // 用户出现这些表达时，后端不再依赖模型判断，直接强制ragSearch。
            "根据我的知识库",
            "根据知识库",
            "我的知识库",
            "根据我的笔记",
            "根据笔记",
            "我的笔记",
            "根据资料",
            "根据项目文档",
            "根据我保存的内容",
            "查我的知识库",
            "从我的文章里找",
            "从知识库里找"
    };

    private static final String[] RAG_QUERY_PREFIXES = { // 清理用户自然语言前缀，让Milvus检索query更聚焦。
            "请根据我的知识库回答：",
            "请根据我的知识库回答:",
            "根据我的知识库回答：",
            "根据我的知识库回答:",
            "请根据我的笔记回答：",
            "请根据我的笔记回答:",
            "根据我的笔记回答：",
            "根据我的笔记回答:",
            "请根据知识库回答：",
            "请根据知识库回答:",
            "根据知识库回答：",
            "根据知识库回答:",
            "请根据我的知识库回答",
            "根据我的知识库回答",
            "请根据我的笔记回答",
            "根据我的笔记回答",
            "请根据知识库回答",
            "根据知识库回答"
    };

    private static final String[] FOLLOW_UP_KEYWORDS = { // 追问识别只用于增强普通回答prompt，不参与强制RAG路由。
            "这个",
            "这段",
            "上面",
            "刚才",
            "刚刚",
            "它",
            "那个",
            "继续",
            "再",
            "改成",
            "换成",
            "优化一下",
            "解释一下",
            "详细说说",
            "举个例子",
            "怎么改",
            "如何改"
    };

    private static final String[] DIRTY_HISTORY_KEYWORDS = { // 明显错误、异常、拒答类历史不进入多轮上下文。
            "系统错误",
            "调用失败",
            "工具未注册",
            "DeepSeek调用失败",
            "DeepSeek 调用失败",
            "OpenAIHttpException",
            "RuntimeException",
            "Exception",
            "error",
            "invalid_request_error",
            "敏感词",
            "高频敏感词",
            "无法为您提供回答",
            "无法提供回答",
            "接口异常",
            "服务异常",
            "请求失败",
            "超时",
            "timeout",
            "网络异常",
            "连接失败"
    };

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

    @Override // 实现公共流式聊天编排入口。
    public void chatStream(String message, ToolCallingStreamCallback callback) { // 先非流式判断tool_call，再流式生成最终回答。
        chatStream(message, Collections.emptyList(), callback); // 兼容旧调用方，旧入口不带历史上下文。
    }

    @Override // 实现带历史上下文的公共流式聊天编排入口。
    public void chatStream(String currentMessage,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingStreamCallback callback) { // 先基于当前输入判断tool_call，再在最终回答阶段注入历史。
        if (callback == null) { // 流式编排必须有上层回调承接token。
            throw new IllegalArgumentException("ToolCallingStreamCallback不能为空"); // 避免后续空指针导致调用方无法收到错误。
        }
        boolean[] streamErrorNotified = {false}; // 记录底层流式调用是否已通知错误，避免外层catch重复通知。
        try { // 统一兜底Tool Calling流式编排中的异常。
            boolean followUpQuestion = isFollowUpQuestion(currentMessage); // 只识别当前输入是否追问，不参与force ragSearch判断。
            log.info("[ToolChatStream] follow-up question: {}", followUpQuestion); // 打印追问识别结果，便于验证多轮指代理解。
            if (followUpQuestion) {
                log.info("[ToolChatStream] follow-up will use history context"); // 追问场景会在最终回答阶段结合historyMessages理解指代。
            }
            String userMessage = validateAndGetMessage(currentMessage); // 校验并规整当前轮用户输入。
            List<ToolChatHistoryMessage> normalizedHistoryMessages = normalizeHistoryMessages(historyMessages); // 规范化历史，但不参与工具路由判断。
            log.info("[ToolChatStream] current user message: {}", userMessage); // 打印当前轮用户消息，确认路由只看当前输入。
            log.info("[ToolChatStream] history enabled: {}", !normalizedHistoryMessages.isEmpty()); // 打印最终回答阶段是否启用历史上下文。
            log.info("[ToolChatStream] history message count: {}", normalizedHistoryMessages.size()); // 打印规范化后的历史数量。
            logHistoryMessages(normalizedHistoryMessages); // 打印历史role和短preview，避免日志爆炸。
            log.info("[ToolChatStream] tool boundary prompt: enabled"); // 标记复用边界优化后的system prompt。
            boolean forceRagSearch = shouldForceRagSearch(userMessage); // 后端先识别强知识库意图，避免模型偶尔不返回tool_call。
            log.info("[ToolChatStream] force ragSearch: {}", forceRagSearch); // 打印流式接口的强制RAG路由结果。
            if (forceRagSearch) { // 用户明确要求基于知识库/笔记/资料回答时，跳过第一次模型tool_call判断。
                streamWithForcedRagSearch(userMessage, normalizedHistoryMessages, callback, streamErrorNotified); // 直接执行ragSearch并进入DeepSeek流式回答。
                return; // 强制RAG路径结束后不再走原有tool_call判断链路。
            }

            ObjectNode firstRequest = buildFirstRequest(userMessage); // 第一次请求仍然只用当前输入，用于判断是否需要调用工具。
            log.info("[ToolChatStream] before first DeepSeek call"); // 标记第一次非流式模型调用开始。
            JsonNode firstResponse = deepSeekClient.chatCompletions(firstRequest); // 第一次非流式调用，获取可能的tool_call。
            log.info("[ToolChatStream] after first DeepSeek call"); // 标记第一次模型调用成功返回。

            JsonNode firstMessage = readFirstMessage(firstResponse); // 读取第一次assistant message。
            JsonNode toolCalls = firstMessage.path("tool_calls"); // 读取工具调用数组。
            boolean hasToolCall = toolCalls.isArray() && !toolCalls.isEmpty(); // 判断是否包含tool_call。
            log.info("[ToolChatStream] has tool call: {}", hasToolCall); // 打印tool_call判断结果。

            if (!hasToolCall) { // 普通聊天不再一次性吐出第一次非流式结果，而是重新发起无tools的流式请求。
                ObjectNode noToolStreamRequest = buildNoToolStreamRequest(userMessage, normalizedHistoryMessages, followUpQuestion); // 第二次请求加入历史，但不设置tools。
                log.info("[ToolChatStream] before no-tool second DeepSeek stream call"); // 标记无工具场景第二次流式请求开始。
                deepSeekClient.streamChatCompletions(noToolStreamRequest, new DeepSeekStreamCallback() { // 让普通聊天也走DeepSeek SSE流式输出。
                    @Override
                    public void onToken(String token) { // DeepSeek返回普通聊天增量token。
                        callback.onToken(token); // 转发给/chat/message的SSE回调。
                    }

                    @Override
                    public void onComplete() { // DeepSeek返回[DONE]或流结束。
                        callback.onComplete(); // 通知上层保存完整assistant消息并发送done事件。
                    }

                    @Override
                    public void onError(Throwable error) { // 无工具流式请求发生异常。
                        streamErrorNotified[0] = true; // 标记底层已通知错误，避免外层catch重复通知。
                        callback.onError(error); // 透传给上层SSE错误处理。
                    }
                });
                log.info("[ToolChatStream] after no-tool second DeepSeek stream call"); // 标记无工具场景第二次流式请求结束。
                return; // 无工具路径结束。
            }

            JsonNode toolCall = toolCalls.get(0); // 当前先处理第一个tool_call。
            String toolCallId = toolCall.path("id").asText(); // 第二次请求tool message必须带回该ID。
            String toolName = toolCall.path("function").path("name").asText(); // 读取模型选择的工具名。
            String toolArguments = toolCall.path("function").path("arguments").asText(); // 读取模型生成的arguments JSON字符串。
            log.info("[ToolChatStream] tool name: {}", toolName); // 打印工具名。
            log.info("[ToolChatStream] tool call id: {}", toolCallId); // 打印工具调用ID。
            log.info("[ToolChatStream] tool arguments: {}", toolArguments); // 打印工具参数。

            JsonNode argumentsNode = parseToolArguments(toolArguments); // 解析arguments，交给具体AiTool执行。
            String toolResult = executeToolForStream(toolName, argumentsNode, callback); // 通过ToolRegistry执行工具并输出流式专用日志。
            if (toolResult == null) { // 工具不存在时executeToolForStream已完成callback通知。
                return; // 未知工具不再进入第二次模型调用。
            }

            ObjectNode secondRequest = buildSecondRequest(userMessage, normalizedHistoryMessages, firstMessage, toolCallId, toolResult); // 第二次请求加入历史和工具结果。
            log.info("[ToolChatStream] before second DeepSeek stream call"); // 标记第二次流式模型调用开始。
            deepSeekClient.streamChatCompletions(secondRequest, new DeepSeekStreamCallback() { // 第二次调用改用DeepSeek流式接口。
                @Override
                public void onToken(String token) { // DeepSeek返回增量token。
                    callback.onToken(token); // 转发给Tool Calling上层业务回调。
                }

                @Override
                public void onComplete() { // DeepSeek返回[DONE]或流结束。
                    callback.onComplete(); // 通知上层流式回答完成。
                }

                @Override
                public void onError(Throwable error) { // DeepSeek流式调用异常。
                    streamErrorNotified[0] = true; // 标记底层已经通知过错误。
                    callback.onError(error); // 透传给上层业务回调。
                }
            });
            log.info("[ToolChatStream] after second DeepSeek stream call"); // 标记第二次流式模型调用结束。
        } catch (Exception e) { // 捕获第一次调用、工具执行、第二次流式调用中的异常。
            log.error("[ToolChatStream] error type: {}", e.getClass().getName()); // 打印异常类型。
            log.error("[ToolChatStream] error message: {}", e.getMessage()); // 打印异常消息。
            if (!streamErrorNotified[0]) { // 如果底层流式回调还没通知错误，外层负责兜底通知。
                callback.onError(e); // 通知上层错误，避免业务层一直等待完成事件。
            }
        }
    }

    private void streamWithForcedRagSearch(String userMessage,
                                           List<ToolChatHistoryMessage> historyMessages,
                                           ToolCallingStreamCallback callback,
                                           boolean[] streamErrorNotified) { // 流式接口的后端强制ragSearch闭环。
        String query = buildRagQuery(userMessage); // 清理用户问题前缀，让Milvus检索更聚焦。
        log.info("[ToolChatStream] force execute tool: {}", RAG_SEARCH_TOOL_NAME); // 打印强制执行的工具名。
        log.info("[ToolChatStream] force ragSearch query: {}", query); // 打印强制ragSearch的query。
        AiTool ragTool = toolRegistry.getTool(RAG_SEARCH_TOOL_NAME); // 从ToolRegistry获取RagSearchTool。
        if (ragTool == null) { // 没有注册ragSearch时不能继续伪造工具结果。
            callback.onToken("系统错误：ragSearch 工具未注册。"); // 通过SSE把明确错误返回前端。
            callback.onComplete(); // 结束本轮流式输出，避免前端一直等待。
            return; // 工具缺失时不再调用DeepSeek。
        }
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造工具入参JSON。
        arguments.put("query", query); // 写入ragSearch所需query字段。
        String toolResult = ragTool.execute(arguments); // 执行真实RAG检索，RagSearchTool内部会访问Milvus。
        log.info("[ToolChatStream] force ragSearch result: {}", toolResult); // 打印工具结果，便于核对后端确实执行过ragSearch。
        ObjectNode streamRequest = buildForcedRagAnswerRequest(userMessage, historyMessages, toolResult); // 将历史作为辅助上下文，工具结果仍最高优先级。
        log.info("[ToolChatStream] before forced ragSearch DeepSeek stream call"); // 标记强制RAG后的DeepSeek流式调用开始。
        deepSeekClient.streamChatCompletions(streamRequest, new DeepSeekStreamCallback() { // 基于强制RAG结果流式生成最终回答。
            @Override
            public void onToken(String token) { // DeepSeek返回增量token。
                callback.onToken(token); // 转发给/chat/message的SSE回调。
            }

            @Override
            public void onComplete() { // DeepSeek返回[DONE]或流结束。
                callback.onComplete(); // 通知上层保存assistant完整消息并发送done。
            }

            @Override
            public void onError(Throwable error) { // 强制RAG后的流式模型调用异常。
                streamErrorNotified[0] = true; // 标记底层已通知错误，避免外层catch重复通知。
                callback.onError(error); // 透传给上层错误处理。
            }
        });
        log.info("[ToolChatStream] after forced ragSearch DeepSeek stream call"); // 标记强制RAG后的DeepSeek流式调用结束。
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

    private ObjectNode buildNoToolStreamRequest(String userMessage,
                                                List<ToolChatHistoryMessage> historyMessages,
                                                boolean followUpQuestion) { // 构造无工具场景的第二次流式请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，保持deepseek-v4-pro配置一致。
        ArrayNode messages = objectMapper.createArrayNode(); // 只构造普通对话messages。
        messages.add(buildMessage("system", buildNoToolSystemPrompt(followUpQuestion))); // 明确本轮没有工具和知识库结果，追问时强化历史指代理解。
        addHistoryMessages(messages, historyMessages); // 历史只用于普通多轮追问理解。
        messages.add(buildMessage(ROLE_USER, userMessage)); // 当前用户问题放在历史之后，冲突时以当前问题为准。
        request.set("messages", messages); // 不设置tools，避免no-tool场景第二次又触发tool_call。
        return request; // DeepSeekClient会在streamChatCompletions内部补stream=true。
    }

    private String buildNoToolSystemPrompt(boolean followUpQuestion) {
        if (!followUpQuestion) {
            return NO_TOOL_SYSTEM_PROMPT; // 非追问普通聊天使用基础no-tool约束。
        }
        return NO_TOOL_SYSTEM_PROMPT
                + "\n当前问题已判断为追问，请优先结合最近历史理解指代对象，再回答当前问题。"; // 追问时显式提示模型使用历史理解“这个/它”等指代。
    }

    private ObjectNode buildForcedRagAnswerRequest(String userMessage, List<ToolChatHistoryMessage> historyMessages, String toolResult) { // 构造后端强制ragSearch后的回答请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，避免reasoning_content问题。
        ArrayNode messages = objectMapper.createArrayNode(); // 使用普通messages承载强制RAG上下文。
        messages.add(buildMessage("system", FORCED_RAG_SYSTEM_PROMPT)); // 告诉模型后端已强制执行ragSearch，必须基于结果回答。
        addHistoryMessages(messages, historyMessages); // 历史只辅助理解指代，不能替代ragSearch结果。
        messages.add(buildMessage(ROLE_USER, "原始用户问题：" + userMessage)); // 保留用户原问题，帮助模型组织最终回答。
        messages.add(buildMessage(ROLE_USER, "以下是 ragSearch 工具从用户知识库检索到的内容：\n" + toolResult
                + "\n\n请严格基于以上知识库内容回答用户问题。不要编造工具结果中不存在的信息。")); // 直接注入工具结果，避免伪造OpenAI tool message。
        request.set("messages", messages); // 不设置tools，因为工具已经由后端强制执行完成。
        return request; // 返回可用于同步或流式DeepSeek调用的请求体。
    }

    private ObjectNode buildSecondRequest(String userMessage,
                                          List<ToolChatHistoryMessage> historyMessages,
                                          JsonNode firstMessage,
                                          String toolCallId,
                                          String toolResult) { // 构造第二次DeepSeek请求。
        ObjectNode request = buildBaseRequest(); // 第二次请求同样保留model和thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 创建messages数组。
        messages.add(buildMessage("system", TOOL_RESULT_SYSTEM_PROMPT)); // 工具调用后的最终回答强调工具结果优先于历史。
        addHistoryMessages(messages, historyMessages); // 历史作为工具结果之外的辅助上下文。
        messages.add(buildMessage(ROLE_USER, userMessage)); // 保留原始用户问题，保证assistant tool_calls仍紧跟当前问题。
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

    private void addHistoryMessages(ArrayNode messages, List<ToolChatHistoryMessage> historyMessages) {
        for (ToolChatHistoryMessage historyMessage : historyMessages) {
            messages.add(buildMessage(historyMessage.getRole(), historyMessage.getContent())); // 按时间正序追加历史消息。
        }
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

    private JsonNode parseToolArguments(String arguments) { // 解析模型返回的tool arguments。
        try { // arguments是JSON字符串，需要转换为JsonNode交给AiTool读取。
            return objectMapper.readTree(arguments); // 返回解析后的arguments节点。
        } catch (Exception e) { // 模型参数格式异常时不中断编排器本身。
            log.warn("[ToolChatStream] parse tool arguments failed: {}", arguments, e); // 打印异常方便排查模型输出。
            return objectMapper.createObjectNode(); // 返回空对象，由具体工具的必填参数校验给出明确错误。
        }
    }

    private String executeToolForStream(String toolName, JsonNode argumentsNode, ToolCallingStreamCallback callback) { // 流式编排专用工具执行逻辑。
        AiTool tool = toolRegistry.getTool(toolName); // 从注册中心查找工具实现。
        if (tool == null) { // 模型返回了未注册工具。
            String unknownToolMessage = "未知工具：" + toolName; // 构造可返回给用户的未知工具提示。
            callback.onToken(unknownToolMessage); // 按要求将未知工具提示作为输出交给上层。
            callback.onComplete(); // 未知工具路径直接结束本轮流式输出。
            return null; // 返回null表示不要继续第二次模型调用。
        }
        log.info("[ToolChatStream] execute tool: {}", toolName); // 打印流式编排工具执行日志。
        String toolResult = tool.execute(argumentsNode); // 执行具体工具，ragSearch会进入业务模块检索Milvus。
        log.info("[ToolChatStream] tool result: {}", toolResult); // 打印工具执行结果，便于定位第二次请求上下文。
        return toolResult; // 返回工具结果供第二次流式模型调用使用。
    }

    private List<ToolChatHistoryMessage> normalizeHistoryMessages(List<ToolChatHistoryMessage> historyMessages) {
        int rawHistoryCount = historyMessages == null ? 0 : historyMessages.size(); // 原始历史数量只用于日志，不参与路由。
        log.info("[ToolChatStream] raw history message count: {}", rawHistoryCount); // 打印过滤前历史数量，便于排查污染来源。
        if (historyMessages == null || historyMessages.isEmpty()) {
            log.info("[ToolChatStream] normalized history message count: 0"); // 空历史标准化后仍为空。
            return Collections.emptyList(); // 没有历史时返回不可变空列表，不影响主流程。
        }
        List<ToolChatHistoryMessage> normalizedMessages = new ArrayList<>(); // 新建列表，避免修改调用方传入的历史集合。
        for (ToolChatHistoryMessage historyMessage : historyMessages) {
            if (historyMessage == null
                    || !isHistoryRole(historyMessage.getRole())
                    || historyMessage.getContent() == null
                    || historyMessage.getContent().trim().isEmpty()) {
                continue; // 只保留user/assistant且content非空的历史。
            }
            String normalizedContent = historyMessage.getContent().trim(); // 去掉首尾空白，减少无意义上下文。
            if (isDirtyHistoryContent(normalizedContent)) {
                log.info("[ToolChatStream] skip dirty history message, role: {}, content preview: {}",
                        historyMessage.getRole(), previewContent(normalizedContent)); // 脏历史只打印80字预览，避免污染日志。
                continue; // 系统异常、拒答、工具错误类历史不进入模型上下文。
            }
            if (normalizedContent.length() > HISTORY_CONTENT_MAX_LENGTH) {
                log.info("[ToolChatStream] truncate long history message, role: {}, original length: {}",
                        historyMessage.getRole(), normalizedContent.length()); // 超长历史截断并记录原始长度。
                normalizedContent = normalizedContent.substring(0, HISTORY_CONTENT_MAX_LENGTH) + "...[历史内容已截断]";
            }
            normalizedMessages.add(ToolChatHistoryMessage.builder()
                    .role(historyMessage.getRole())
                    .content(normalizedContent)
                    .createTime(historyMessage.getCreateTime())
                    .build()); // 复制为新对象，避免后续处理影响原始入参。
        }
        if (normalizedMessages.stream().allMatch(message -> message.getCreateTime() != null)) {
            normalizedMessages.sort(Comparator.comparing(ToolChatHistoryMessage::getCreateTime)); // createTime完整时按时间正序组织上下文。
        }
        if (normalizedMessages.size() > HISTORY_MESSAGE_LIMIT) {
            normalizedMessages = new ArrayList<>(normalizedMessages.subList(
                    normalizedMessages.size() - HISTORY_MESSAGE_LIMIT,
                    normalizedMessages.size())); // 超过窗口时只保留最近6条，并继续保持当前正序。
        }
        log.info("[ToolChatStream] normalized history message count: {}", normalizedMessages.size()); // 打印最终进入模型上下文的历史数量。
        return normalizedMessages; // 返回过滤、截断、限量后的历史消息。
    }

    private boolean isHistoryRole(String role) {
        return ROLE_USER.equals(role) || ROLE_ASSISTANT.equals(role); // 历史上下文只允许普通用户消息和assistant回复。
    }

    private boolean isFollowUpQuestion(String currentMessage) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return false; // 空问题不是追问，也不能触发任何路由变化。
        }
        for (String keyword : FOLLOW_UP_KEYWORDS) {
            if (currentMessage.contains(keyword)) {
                return true; // 只做轻量contains判断，用于普通回答prompt增强。
            }
        }
        return false; // 未命中追问关键词时按普通当前问题处理。
    }

    private boolean isDirtyHistoryContent(String content) {
        if (content == null || content.isBlank()) {
            return false; // 空内容由normalizeHistoryMessages前置过滤，这里兜底返回非脏。
        }
        String lowerContent = content.toLowerCase(); // 英文error/exception/timeout等按小写判断，兼容大小写。
        for (String keyword : DIRTY_HISTORY_KEYWORDS) {
            if (lowerContent.contains(keyword.toLowerCase())) {
                return true; // 命中异常、拒答、敏感词或工具错误关键词时过滤该历史。
            }
        }
        return false; // 未命中脏历史关键词时允许进入上下文。
    }

    private void logHistoryMessages(List<ToolChatHistoryMessage> historyMessages) {
        for (ToolChatHistoryMessage historyMessage : historyMessages) {
            log.info("[ToolChatStream] history item role: {}, content preview: {}",
                    historyMessage.getRole(), previewContent(historyMessage.getContent())); // 每条历史只打印短preview。
        }
    }

    private String previewContent(String content) {
        if (content == null) {
            return "";
        }
        String normalizedContent = content.replace('\n', ' ').replace('\r', ' '); // 日志预览去掉换行，保持单行可读。
        return normalizedContent.length() <= 80 ? normalizedContent : normalizedContent.substring(0, 80) + "..."; // 历史日志最多打印80字。
    }

    private boolean shouldForceRagSearch(String message) { // 判断用户是否明确要求基于个人知识库/笔记/资料回答。
        if (message == null || message.isBlank()) { // 空消息不能触发强制工具路由。
            return false; // 保持空输入安全返回。
        }
        for (String keyword : FORCE_RAG_KEYWORDS) { // 逐个匹配强制RAG关键词。
            if (message.contains(keyword)) { // contains即可满足当前最小路由规则。
                return true; // 命中后端强制ragSearch。
            }
        }
        return false; // 未命中关键词时继续让模型自行判断是否tool_call。
    }

    private String buildRagQuery(String message) { // 从用户问题中清理“根据知识库回答”等前缀，得到更适合检索的query。
        if (message == null) { // 理论上入口已校验，这里兜底避免空指针。
            return ""; // 空消息返回空query。
        }
        String originalMessage = message.trim(); // 保存清理前文本，清理失败时用于回退。
        String cleanedMessage = originalMessage; // 从原始问题开始逐步清理。
        for (String prefix : RAG_QUERY_PREFIXES) { // 尝试移除常见知识库/笔记问答前缀。
            if (cleanedMessage.startsWith(prefix)) { // 只清理开头前缀，避免误删正文中的关键词。
                cleanedMessage = cleanedMessage.substring(prefix.length()).trim(); // 去掉前缀并清理空白。
                break; // 命中一个前缀即可。
            }
        }
        while (cleanedMessage.startsWith("：") || cleanedMessage.startsWith(":")) { // 兼容无前缀命中后残留的中英文冒号。
            cleanedMessage = cleanedMessage.substring(1).trim(); // 去掉多余冒号。
        }
        return cleanedMessage.isBlank() ? originalMessage : cleanedMessage; // 清理后为空时回退原始问题。
    }

    private String validateAndGetMessage(String message) { // 校验用户消息。
        if (message == null || message.trim().isEmpty()) { // message不能为空。
            throw new IllegalArgumentException("message不能为空"); // 保持原接口的基础校验语义。
        }
        return message.trim(); // 去除首尾空白，减少无效token。
    }

}
