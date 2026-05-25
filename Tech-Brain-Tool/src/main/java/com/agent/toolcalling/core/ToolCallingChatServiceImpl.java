package com.agent.toolcalling.core; // Tool Calling公共编排服务包。

import com.agent.toolcalling.client.DeepSeekClient; // 公共DeepSeek HTTP客户端，负责实际chat/completions调用。
import com.agent.toolcalling.client.DeepSeekStreamCallback; // DeepSeek底层流式回调，chatStream会适配成ToolCallingStreamCallback。
import com.agent.toolcalling.config.DeepSeekProperties; // 读取deepseek.model-name/base-url等配置，继续保持deepseek-v4-pro。
import com.agent.toolcalling.context.ToolCallingContextHolder; // 工具执行前后写入并清理ThreadLocal上下文。
import com.agent.toolcalling.context.ToolCallingRequestContext; // 当前Tool Calling请求上下文，包含userId和conversationId。
import com.agent.toolcalling.registry.ToolRegistry; // 公共工具注册中心，负责构造tools JSON和按名称查找工具。
import com.agent.toolcalling.spi.AiTool; // 公共工具接口，模型返回tool_call后由这里统一分发执行。
import com.agent.toolcalling.summary.SummaryTypeConstants; // 通用总结类型常量与归一化规则，保证路由、工具和服务使用同一套summaryType。
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool Calling聊天公共编排器。
 *
 * <p>该类位于Tech-Brain-Tool公共模块，承接通用Tool Calling流式主流程：构造DeepSeek请求、判断tool_call、通过ToolRegistry执行工具、把tool result回传模型并流式输出最终回答。</p>
 * <p>调用链为：ChatMessageServiceImpl -> ToolCallingChatServiceImpl.chatStream(currentMessage, memorySummary, historyMessages, callback) -> DeepSeekClient第一次调用 -> ToolRegistry.getTool(toolName) -> AiTool.execute(arguments) -> DeepSeekClient第二次流式调用。</p>
 * <p>本类只负责编排协议和工具分发，不依赖AgentService、Milvus、数据库或任何具体业务工具；ragSearch等业务工具仍由Tech-Brain-Agent模块提供。</p>
 */
@Slf4j // 生成日志对象，输出[ToolChatStream]前缀的调试日志。
@Service // 作为公共编排器注册到Spring容器。
public class ToolCallingChatServiceImpl implements ToolCallingChatService { // 默认Tool Calling聊天编排实现。

    private static final String ROLE_USER = "user"; // Tool历史中允许进入模型上下文的用户角色。

    private static final String ROLE_ASSISTANT = "assistant"; // Tool历史中允许进入模型上下文的助手角色。

    private static final int HISTORY_MESSAGE_LIMIT = 6; // Tool Calling最终回答阶段最多注入最近6条有效历史。

    private static final int HISTORY_CONTENT_MAX_LENGTH = 1200; // 单条历史最多保留1200字，避免超长上下文污染模型。

    private static final int MEMORY_SUMMARY_MAX_LENGTH = 1500; // 长期记忆摘要最多注入1500字，避免早期上下文过长污染当前问题。

    private static final String CONTEXT_PRIORITY_PROMPT = "\n\n上下文优先级从高到低：" + // 所有最终回答阶段统一声明上下文优先级。
            "\n1. 当前用户输入 currentMessage：定义本轮真正问题和约束。" +
            "\n2. 本轮工具结果 toolResult：如果本轮调用了工具，必须优先作为事实依据。" +
            "\n3. 最近短期历史 historyMessages：只用于理解指代和追问，不能覆盖工具结果。" +
            "\n4. 会话长期记忆 memorySummary：只作为更早对话背景，不能覆盖工具结果。" +
            "\n5. 模型自身知识：只在没有更高优先级上下文时补充。" +
            "\n如果本轮调用了 ragSearch，必须优先基于 ragSearch 工具结果回答。" +
            "\n如果没有调用工具，不能声称“根据你的知识库”“根据你的笔记”“根据资料”。" +
            "\n如果回答基于长期记忆，可以说“结合之前的对话记录”或“结合本会话的长期摘要”，不要说“根据知识库”。";

    private static final String SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 边界优化版system prompt，定义助手身份。
            "\n你可以根据用户问题选择合适的工具，但不要滥用工具。" +
            "\n\n当前可用工具：" +
            "\n1. ragSearch：用于检索用户的私人知识库、笔记、文章和项目资料。" +
            "\n2. summarizeArticle：用于按 articleId 总结用户指定的一篇文章或笔记。" +
            "\n\n调用 ragSearch 的规则：" +
            "\n- 当用户明确要求“根据知识库”“根据我的知识库”“根据我的笔记”“根据资料”“根据项目文档”“根据我保存的内容”“查我的知识库”“从我的文章里找”等内容时，必须调用 ragSearch。" +
            "\n- 当用户询问 Tech-Brain 项目中已经保存的资料、笔记内容、文章内容、项目文档内容时，必须调用 ragSearch。" +
            "\n- 当用户只是普通闲聊、打招呼、感谢、询问通用知识、普通编程问题时，不要调用 ragSearch，直接回答。" +
            "\n- 如果用户没有明确要求基于知识库或个人资料回答，不要为了普通问题主动调用 ragSearch。" +
            "\n\n调用 summarizeArticle 的规则：" +
            "\n- 当用户要求总结指定编号、指定ID、某一篇笔记或某一篇文章时，必须调用 summarizeArticle。" +
            "\n- 用户说“帮我总结第15篇笔记”“总结文章15”“把第15篇笔记整理成要点”时，articleId 应提取为 15。" +
            "\n- 用户说“帮我总结这篇笔记”“总结刚才那篇”“把上面那篇整理成要点”“把刚刚查到的那篇总结成面试话术”时，也必须调用 summarizeArticle；如果当前输入没有明确ID，可以不传 articleId，后端会尝试从最近一次 ragSearch 命中文档中补全。" +
            "\n- 用户要求“要点”“整理成要点”“核心要点”时，summaryType 使用 points。" +
            "\n- 用户要求“面试话术”“面试表达”“面试怎么说”时，summaryType 使用 interview。" +
            "\n- 其它指定文章总结场景 summaryType 使用 normal。" +
            "\n- summarizeArticle 只允许传 articleId 和 summaryType，不要传 userId。" +
            "\n\n回答规则：" +
            "\n- 如果 ragSearch 返回了知识库内容，你必须优先根据工具返回内容回答。" +
            "\n- 如果 ragSearch 返回“知识库中没有检索到与该问题相关的内容”，你必须明确告诉用户知识库中没有找到相关资料，不要编造。" +
            "\n- 如果 summarizeArticle 返回 article_summary 类型工具结果，最终聊天窗口只输出 chatMessage，不要输出 summary 全文。" +
            "\n- 不要编造知识库内容。" +
            "\n- 不要声称看过知识库，除非你确实调用了 ragSearch 并拿到了工具结果。";

    private static final String NO_TOOL_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 普通流式回答专用prompt，明确本轮没有工具结果。
            "\n本轮没有调用任何工具，也没有检索知识库。" +
            "\n你可以结合历史上下文理解用户追问。" +
            "\n如果当前问题是追问，并且历史消息中有相关上下文，你可以结合历史上下文回答。" +
            "\n当用户说“这个”“它”“上面那个”“刚才那个”“这段代码”等表达时，需要根据最近历史判断指代对象。" +
            "\n如果回答基于历史，可以说“结合上文”或“根据刚才的代码”，不要说“根据知识库”。" +
            "\n不能把历史内容伪装成新一轮知识库检索结果。" +
            "\n本轮 has tool call=false 时，不能冒充知识库、数据库、笔记或检索结果。" +
            "\n禁止使用“根据你的知识库”“根据数据库”“根据你的笔记”“根据检索结果”等表述。" +
            "\n如果需要说明信息来源，只能说“我没有检索知识库，本回答按普通聊天能力给出”。" +
            "\n如果历史和当前问题冲突，以当前问题为准。" +
            "\n可以结合短期历史和长期记忆理解用户追问，但当前问题优先级最高。" +
            "\n不要声称“根据你的知识库”“根据你的笔记”“根据资料”。" +
            "\n普通闲聊、通用知识和普通编程问题按常规能力直接回答。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String RAG_SEARCH_TOOL_NAME = "ragSearch"; // 强制知识库路由时固定执行的工具名。

    private static final String SUMMARIZE_ARTICLE_TOOL_NAME = "summarizeArticle"; // 强制总结路由时固定执行的工具名。

    private static final String ARTICLE_SUMMARY_RESULT_TYPE = "article_summary"; // 文章总结工具结果类型，命中后聊天只输出chatMessage。

    private static final String SUMMARY_RESULT_EVENT_NAME = "summary_result"; // 文章总结完整结果通过该SSE业务事件交给前端弹窗。

    private static final String FORCED_RAG_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行ragSearch后的回答约束。
            "\n用户明确要求根据个人知识库、笔记或资料回答。" +
            "\n后端已经强制执行 ragSearch 工具。" +
            "\n工具结果优先级最高，你必须严格基于 ragSearch 返回的知识库内容回答。" +
            "\n历史上下文只用于理解指代和追问，不允许用历史替代工具结果。" +
            "\n长期记忆只用于辅助理解，不允许替代工具结果。" +
            "\n如果工具结果说明没有检索到相关内容，请明确说明没有找到，不要从历史、长期记忆或常识里编造。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String TOOL_RESULT_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 模型主动tool_call后的最终回答约束。
            "\n本轮已经执行了工具调用。" +
            "\n工具结果优先级高于历史上下文。" +
            "\n历史上下文只用于理解指代和追问，不允许用历史替代工具结果。" +
            "\n长期记忆只用于辅助理解，不允许替代工具结果。" +
            "\n如果工具结果说明没有相关内容，请明确说明没有找到，不要从历史或长期记忆里编造。" +
            "\n如果工具结果是 article_summary 类型：聊天窗口只输出 chatMessage，不要输出 summary 全文；如果 success=false，只输出 chatMessage 中的错误提示；summary 完整内容后续由前端弹窗展示。" +
            CONTEXT_PRIORITY_PROMPT;

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

    private static final String[] FORCE_SUMMARY_KEYWORDS = { // 用户出现这些表达时说明当前输入有总结类意图。
            "总结",
            "AI总结",
            "ai总结",
            "摘要",
            "概括",
            "提炼",
            "整理",
            "要点",
            "重点",
            "梳理",
            "列点",
            "分点",
            "条目",
            "整理成要点",
            "提炼要点",
            "核心要点",
            "面试话术",
            "面试表达",
            "项目话术",
            "背诵版",
            "面试怎么说",
            "怎么向面试官说"
    };

    private static final String[] FORCE_SUMMARY_TARGET_KEYWORDS = { // 总结意图必须同时指向文章/笔记/上下文目标，避免普通聊天误判。
            "笔记",
            "文章",
            "资料",
            "内容",
            "文档",
            "代码",
            "第",
            "篇",
            "id=",
            "articleId="
    };

    private static final String[] FORCE_SUMMARY_CONTEXT_REFERENCE_KEYWORDS = { // 总结意图命中这些上下文指代时，允许由focus history动态匹配具体文章。
            "这篇",
            "那篇",
            "当前",
            "刚才",
            "刚刚",
            "上面",
            "之前",
            "前面",
            "上一条",
            "刚查到",
            "刚检索到",
            "刚才查到",
            "刚才那篇",
            "之前那篇"
    };

    private static final String[] SUMMARY_POINTS_KEYWORDS = { // 要点类总结关键词。
            "要点",
            "核心要点",
            "重点",
            "提炼",
            "梳理",
            "整理成要点",
            "列点",
            "分点",
            "条目",
            "bullet"
    };

    private static final String[] SUMMARY_INTERVIEW_KEYWORDS = { // 面试话术类总结关键词。
            "面试",
            "面试话术",
            "面试表达",
            "面试官",
            "项目话术",
            "背诵版",
            "面试怎么说",
            "怎么向面试官说"
    };

    private static final Pattern[] ARTICLE_ID_PATTERNS = { // 从当前输入中提取显式文章ID的轻量规则。
            Pattern.compile("第\\s*(\\d+)\\s*篇"),
            Pattern.compile("(\\d+)\\s*篇"),
            Pattern.compile("(?i)articleId\\s*[=:：]\\s*(\\d+)"),
            Pattern.compile("(?i)id\\s*[=:：]\\s*(\\d+)"),
            Pattern.compile("(?:文章|笔记)\\s*(\\d+)")
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
        chatStream(message, "", Collections.emptyList(), null, callback); // 兼容旧调用方，旧入口不带长期记忆、历史和请求上下文。
    }

    @Override // 实现带历史上下文的公共流式聊天编排入口。
    public void chatStream(String currentMessage,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingStreamCallback callback) { // 先基于当前输入判断tool_call，再在最终回答阶段注入历史。
        chatStream(currentMessage, "", historyMessages, null, callback); // 兼容4.3入口，长期记忆和请求上下文为空。
    }

    @Override // 实现带长期记忆和短期历史上下文的公共流式聊天编排入口。
    public void chatStream(String currentMessage,
                           String memorySummary,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingStreamCallback callback) { // 当前输入只用于路由，长期记忆和历史只进入最终回答阶段。
        chatStream(currentMessage, memorySummary, historyMessages, null, callback); // 兼容旧入口，不向工具传递请求上下文。
    }

    @Override // 实现带请求上下文的公共流式聊天编排入口。
    public void chatStream(String currentMessage,
                           String memorySummary,
                           List<ToolChatHistoryMessage> historyMessages,
                           ToolCallingRequestContext requestContext,
                           ToolCallingStreamCallback callback) { // requestContext只在工具执行阶段放入ThreadLocal，不参与RAG路由和query构造。
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
            if (requestContext != null && (requestContext.getCurrentMessage() == null || requestContext.getCurrentMessage().isBlank())) { // 旧调用方未写入当前消息时兜底补齐。
                requestContext.setCurrentMessage(userMessage); // currentMessage只给工具解析focus，不拼接历史或长期记忆。
            }
            String normalizedMemorySummary = normalizeMemorySummary(memorySummary); // 标准化长期记忆，但不参与工具路由判断。
            List<ToolChatHistoryMessage> normalizedHistoryMessages = normalizeHistoryMessages(historyMessages); // 规范化历史，但不参与工具路由判断。
            log.info("[ToolChatStream] current user message: {}", userMessage); // 打印当前轮用户消息，确认路由只看当前输入。
            logMemorySummary(normalizedMemorySummary); // 打印长期记忆启用状态、长度和短preview。
            log.info("[ToolChatStream] history enabled: {}", !normalizedHistoryMessages.isEmpty()); // 打印最终回答阶段是否启用历史上下文。
            log.info("[ToolChatStream] history message count: {}", normalizedHistoryMessages.size()); // 打印规范化后的历史数量。
            logHistoryMessages(normalizedHistoryMessages); // 打印历史role和短preview，避免日志爆炸。
            log.info("[ToolChatStream] tool boundary prompt: enabled"); // 标记复用边界优化后的system prompt。
            boolean forceSummarizeArticle = shouldForceSummarizeArticle(userMessage); // 后端先识别总结意图，避免模型偷懒不返回tool_call。
            log.info("[ToolChatStream] force summarizeArticle: {}", forceSummarizeArticle); // 打印强制总结路由判断结果。
            boolean forceRagSearch = !forceSummarizeArticle && shouldForceRagSearch(userMessage); // 只有没有总结意图时才强制RAG，避免“根据知识库总结第15篇”误进RAG。
            log.info("[ToolChatStream] force ragSearch: {}", forceRagSearch); // 打印流式接口的强制RAG路由结果。
            if (forceRagSearch) { // 用户明确要求基于知识库/笔记/资料回答时，跳过第一次模型tool_call判断。
                streamWithForcedRagSearch(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 直接执行ragSearch并进入DeepSeek流式回答。
                return; // 强制RAG路径结束后不再走原有tool_call判断链路。
            }
            if (forceSummarizeArticle) { // 用户明确要求总结文章/笔记时，跳过第一次模型tool_call判断。
                streamWithForcedSummarizeArticle(userMessage, requestContext, callback); // 直接执行summarizeArticle并只输出chatMessage。
                return; // 强制总结路径结束后不再走模型tool_call判断链路。
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
                ObjectNode noToolStreamRequest = buildNoToolStreamRequest(userMessage, normalizedMemorySummary, normalizedHistoryMessages, followUpQuestion); // 第二次请求加入长期记忆和历史，但不设置tools。
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
            String toolResult = executeToolForStream(toolName, argumentsNode, requestContext, callback); // 通过ToolRegistry执行工具并输出流式专用日志。
            if (toolResult == null) { // 工具不存在时executeToolForStream已完成callback通知。
                return; // 未知工具不再进入第二次模型调用。
            }
            if (isArticleSummaryResult(toolResult)) { // 成功的文章总结结果需要额外通知前端打开总结弹窗。
                log.info("[ToolChatStream] detected article summary result"); // 只记录检测到文章总结结果，不打印完整summary。
                log.info("[ToolChatStream] emit tool event: {}", SUMMARY_RESULT_EVENT_NAME); // 标记准备发送summary_result业务事件。
                callback.onToolEvent(SUMMARY_RESULT_EVENT_NAME, toolResult); // 透传完整工具JSON给上层SSE，聊天token仍只输出chatMessage。
            }
            String articleSummaryChatMessage = readArticleSummaryChatMessage(toolResult); // article_summary 工具结果只允许聊天输出短提示。
            if (articleSummaryChatMessage != null) { // 命中文章总结工具结果时不再进入第二次模型，避免 summary 全文进聊天气泡。
                log.info("[ToolChatStream] article summary chatMessage only"); // 标记文章总结结果已被直接处理。
                callback.onToken(articleSummaryChatMessage); // 聊天气泡只输出 chatMessage。
                callback.onComplete(); // 结束本轮流式输出。
                return; // 不再调用第二次 DeepSeek，避免模型复述完整 summary。
            }

            ObjectNode secondRequest = buildSecondRequest(userMessage, normalizedMemorySummary, normalizedHistoryMessages, firstMessage, toolCallId, toolResult); // 第二次请求加入长期记忆、历史和工具结果。
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
                                           String memorySummary,
                                           List<ToolChatHistoryMessage> historyMessages,
                                           ToolCallingRequestContext requestContext,
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
        String toolResult = executeToolWithContext(ragTool, arguments, requestContext); // 执行真实RAG检索，工具执行期间可读取上下文。
        log.info("[ToolChatStream] force ragSearch result: {}", toolResult); // 打印工具结果，便于核对后端确实执行过ragSearch。
        ObjectNode streamRequest = buildForcedRagAnswerRequest(userMessage, memorySummary, historyMessages, toolResult); // 将长期记忆和历史作为辅助上下文，工具结果仍最高优先级。
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

    private void streamWithForcedSummarizeArticle(String userMessage,
                                                  ToolCallingRequestContext requestContext,
                                                  ToolCallingStreamCallback callback) { // 流式接口的后端强制summarizeArticle闭环。
        log.info("[ToolChatStream] force execute tool: {}", SUMMARIZE_ARTICLE_TOOL_NAME); // 打印强制执行的工具名。
        AiTool summarizeTool = toolRegistry.getTool(SUMMARIZE_ARTICLE_TOOL_NAME); // 从ToolRegistry获取SummarizeArticleTool。
        if (summarizeTool == null) { // summarizeArticle未注册时不能继续伪造结果。
            callback.onToken("系统错误：summarizeArticle 工具未注册。"); // 通过SSE把明确错误返回前端。
            callback.onComplete(); // 结束本轮流式输出，避免前端一直等待。
            return; // 工具缺失时不再执行后续流程。
        }

        Long articleId = resolveArticleIdFromMessage(userMessage); // 从当前输入中提取显式文章ID，可能为空。
        String summaryType = resolveSummaryType(userMessage); // 从当前输入中解析总结类型。
        log.info("[ToolChatStream] force summarize articleId: {}", articleId); // 打印强制总结解析出的articleId。
        log.info("[ToolChatStream] resolved summaryType: {}", summaryType); // 打印统一解析后的summaryType，便于和工具、服务日志对齐。
        log.info("[ToolChatStream] force summarize summaryType: {}", summaryType); // 打印强制总结解析出的summaryType。

        ObjectNode arguments = objectMapper.createObjectNode(); // 构造summarizeArticle工具入参。
        if (articleId != null) { // 有显式ID时传给工具。
            arguments.put("articleId", articleId); // 写入articleId。
        }
        arguments.put("summaryType", summaryType); // 写入summaryType，缺省为normal。

        String toolResult = executeToolWithContext(summarizeTool, arguments, requestContext); // 执行总结工具，工具内部可从focus补articleId。
        logToolResult(toolResult); // 打印工具结果，article_summary场景隐藏完整summary。
        JsonNode articleSummaryNode = readArticleSummaryNode(toolResult); // 解析article_summary结果。
        boolean success = articleSummaryNode != null && articleSummaryNode.path("success").asBoolean(false); // 读取工具执行成功状态。
        log.info("[ToolChatStream] force summarize result success: {}", success); // 打印强制总结结果状态。
        if (isArticleSummaryResult(toolResult)) { // 成功且summary非空时发送summary_result业务事件。
            log.info("[ToolChatStream] force summarize emit summary_result"); // 标记发送summary_result。
            callback.onToolEvent(SUMMARY_RESULT_EVENT_NAME, toolResult); // 完整summary只通过SSE事件交给前端弹窗。
        }

        String chatMessage = readChatMessageFromToolResult(toolResult); // 只读取聊天短提示。
        log.info("[ToolChatStream] force summarize chatMessage only"); // 标记聊天窗口只输出chatMessage。
        callback.onToken(chatMessage); // 聊天气泡只输出短提示，不输出完整summary。
        callback.onComplete(); // 结束本轮流式输出。
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
                                                String memorySummary,
                                                List<ToolChatHistoryMessage> historyMessages,
                                                boolean followUpQuestion) { // 构造无工具场景的第二次流式请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，保持deepseek-v4-pro配置一致。
        ArrayNode messages = objectMapper.createArrayNode(); // 只构造普通对话messages。
        messages.add(buildMessage("system", buildNoToolSystemPrompt(followUpQuestion))); // 明确本轮没有工具和知识库结果，追问时强化历史指代理解。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只作为更早对话背景，不代表本轮检索结果。
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

    private ObjectNode buildForcedRagAnswerRequest(String userMessage,
                                                   String memorySummary,
                                                   List<ToolChatHistoryMessage> historyMessages,
                                                   String toolResult) { // 构造后端强制ragSearch后的回答请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，避免reasoning_content问题。
        ArrayNode messages = objectMapper.createArrayNode(); // 使用普通messages承载强制RAG上下文。
        messages.add(buildMessage("system", FORCED_RAG_SYSTEM_PROMPT)); // 告诉模型后端已强制执行ragSearch，必须基于结果回答。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只辅助理解，不能替代ragSearch结果。
        addHistoryMessages(messages, historyMessages); // 历史只辅助理解指代，不能替代ragSearch结果。
        messages.add(buildMessage(ROLE_USER, "原始用户问题：" + userMessage)); // 保留用户原问题，帮助模型组织最终回答。
        messages.add(buildMessage(ROLE_USER, "以下是 ragSearch 工具从用户知识库检索到的内容：\n" + toolResult
                + "\n\n请严格基于以上知识库内容回答用户问题。不要编造工具结果中不存在的信息。")); // 直接注入工具结果，避免伪造OpenAI tool message。
        request.set("messages", messages); // 不设置tools，因为工具已经由后端强制执行完成。
        return request; // 返回可用于同步或流式DeepSeek调用的请求体。
    }

    private ObjectNode buildSecondRequest(String userMessage,
                                          String memorySummary,
                                          List<ToolChatHistoryMessage> historyMessages,
                                          JsonNode firstMessage,
                                          String toolCallId,
                                          String toolResult) { // 构造第二次DeepSeek请求。
        ObjectNode request = buildBaseRequest(); // 第二次请求同样保留model和thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 创建messages数组。
        messages.add(buildMessage("system", TOOL_RESULT_SYSTEM_PROMPT)); // 工具调用后的最终回答强调工具结果优先于历史。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆作为工具结果之外的更早背景。
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

    private void addMemorySummaryMessage(ArrayNode messages, String memorySummary) {
        if (memorySummary == null || memorySummary.isBlank()) {
            return; // 没有长期记忆时不追加上下文消息，避免空内容干扰模型。
        }
        messages.add(buildMessage("system",
                "以下是本会话的长期记忆摘要，仅用于理解上下文和追问，不代表本轮检索结果：\n"
                        + memorySummary)); // 作为system背景注入最终回答阶段，不能触发工具路由。
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

    private String executeToolForStream(String toolName,
                                        JsonNode argumentsNode,
                                        ToolCallingRequestContext requestContext,
                                        ToolCallingStreamCallback callback) { // 流式编排专用工具执行逻辑。
        AiTool tool = toolRegistry.getTool(toolName); // 从注册中心查找工具实现。
        if (tool == null) { // 模型返回了未注册工具。
            String unknownToolMessage = "未知工具：" + toolName; // 构造可返回给用户的未知工具提示。
            callback.onToken(unknownToolMessage); // 按要求将未知工具提示作为输出交给上层。
            callback.onComplete(); // 未知工具路径直接结束本轮流式输出。
            return null; // 返回null表示不要继续第二次模型调用。
        }
        log.info("[ToolChatStream] execute tool: {}", toolName); // 打印流式编排工具执行日志。
        String toolResult = executeToolWithContext(tool, argumentsNode, requestContext); // 执行具体工具，工具内部可读取userId/conversationId上下文。
        logToolResult(toolResult); // 打印工具结果，article_summary 场景会隐藏完整 summary。
        return toolResult; // 返回工具结果供第二次流式模型调用使用。
    }

    private String executeToolWithContext(AiTool tool,
                                          JsonNode argumentsNode,
                                          ToolCallingRequestContext requestContext) { // 只在具体AiTool执行阶段设置ThreadLocal上下文。
        ToolCallingContextHolder.set(requestContext); // 写入当前请求上下文，旧调用方传null时会自动清理。
        try {
            return tool.execute(argumentsNode); // 执行业务工具，RagSearchTool等可通过Holder读取上下文。
        } finally {
            ToolCallingContextHolder.clear(); // 必须清理ThreadLocal，避免线程复用污染后续请求。
        }
    }

    private String readArticleSummaryChatMessage(String toolResult) {
        JsonNode articleSummaryNode = readArticleSummaryNode(toolResult); // 尝试解析 article_summary 工具结果。
        if (articleSummaryNode == null) { // 非文章总结工具结果不处理。
            return null; // 返回 null 表示继续原有第二次模型调用。
        }
        return readChatMessageFromToolResult(toolResult); // article_summary只允许输出chatMessage或兜底短提示。
    }

    private String readChatMessageFromToolResult(String toolResult) {
        JsonNode articleSummaryNode = readArticleSummaryNode(toolResult); // 尝试解析article_summary工具结果。
        if (articleSummaryNode == null) { // 非法工具结果无法读取chatMessage。
            return "总结失败，请稍后重试。"; // 返回安全兜底错误提示。
        }
        String chatMessage = articleSummaryNode.path("chatMessage").asText(""); // 读取聊天短提示。
        if (!chatMessage.isBlank()) { // 工具有明确短提示时优先使用。
            return chatMessage; // 返回工具给出的chatMessage。
        }
        boolean success = articleSummaryNode.path("success").asBoolean(false); // 读取成功状态。
        return success ? "总结完成。" : "总结失败，请稍后重试。"; // 缺少chatMessage时按成功/失败兜底。
    }

    private boolean isArticleSummaryResult(String toolResult) {
        JsonNode articleSummaryNode = readArticleSummaryNode(toolResult); // 按JSON解析并确认type=article_summary。
        if (articleSummaryNode == null) { // 空结果、非JSON结果或非文章总结结果都不触发summary_result。
            return false; // 仅成功文章总结才需要发送业务事件。
        }
        boolean success = articleSummaryNode.path("success").asBoolean(false); // 读取工具执行成功状态。
        String summary = articleSummaryNode.path("summary").asText(""); // 读取完整总结正文，只用于判断是否可发送事件。
        return success && !summary.isBlank(); // 只有成功且summary非空才通知前端弹窗。
    }

    private JsonNode readArticleSummaryNode(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) { // 空工具结果不是 article_summary。
            return null; // 返回 null 表示未命中。
        }
        try { // 尝试按 JSON 解析工具结果。
            JsonNode node = objectMapper.readTree(toolResult); // 解析工具返回 JSON。
            if (ARTICLE_SUMMARY_RESULT_TYPE.equals(node.path("type").asText())) { // 判断工具结果类型。
                return node; // 返回 article_summary 节点。
            }
            return null; // 其它工具结果继续原链路。
        } catch (Exception e) { // ragSearch 等普通文本工具结果不是 JSON。
            return null; // 解析失败时按非 article_summary 处理。
        }
    }

    private void logToolResult(String toolResult) {
        JsonNode articleSummaryNode = readArticleSummaryNode(toolResult); // 判断是否文章总结工具结果。
        if (articleSummaryNode != null) { // 文章总结结果包含完整 summary，日志必须隐藏全文。
            log.info("[ToolChatStream] tool result type: {}", ARTICLE_SUMMARY_RESULT_TYPE); // 只打印结果类型。
            log.info("[ToolChatStream] tool result success: {}", articleSummaryNode.path("success").asBoolean(false)); // 打印成功状态。
            log.info("[ToolChatStream] tool result chatMessage: {}", articleSummaryNode.path("chatMessage").asText("")); // 打印聊天短提示。
            log.info("[ToolChatStream] tool result summary preview: {}", previewContent(articleSummaryNode.path("summary").asText(""))); // 只打印 summary 预览。
            return; // 不打印完整 JSON。
        }
        log.info("[ToolChatStream] tool result: {}", toolResult); // 非 article_summary 保持原有工具结果日志。
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

    private String normalizeMemorySummary(String memorySummary) {
        if (memorySummary == null) {
            return ""; // null 长期记忆统一视为空。
        }
        String normalizedSummary = memorySummary.trim(); // 去掉首尾空白，减少无效上下文。
        if (normalizedSummary.isEmpty()) {
            return ""; // 空 summary 不进入最终回答上下文。
        }
        if (isDirtyHistoryContent(normalizedSummary)) {
            log.info("[ToolChatStream] skip dirty memory summary, content preview: {}",
                    previewContent(normalizedSummary)); // 长期记忆中出现异常/拒答类内容时整段跳过。
            return ""; // 脏长期记忆不进入模型上下文。
        }
        if (normalizedSummary.length() > MEMORY_SUMMARY_MAX_LENGTH) {
            log.info("[ToolChatStream] truncate long memory summary, original length: {}",
                    normalizedSummary.length()); // 超长长期记忆截断并记录原始长度。
            return normalizedSummary.substring(0, MEMORY_SUMMARY_MAX_LENGTH) + "...[长期记忆已截断]"; // 限制注入模型的长期摘要长度。
        }
        return normalizedSummary; // 返回标准化后的长期记忆摘要。
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

    private void logMemorySummary(String memorySummary) {
        boolean enabled = memorySummary != null && !memorySummary.isBlank(); // 长期记忆非空才算启用。
        log.info("[ToolChatStream] memory summary enabled: {}", enabled); // 打印长期记忆是否进入最终回答上下文。
        log.info("[ToolChatStream] memory summary length: {}", enabled ? memorySummary.length() : 0); // 打印标准化后的长期记忆长度。
        log.info("[ToolChatStream] memory summary preview: {}", previewContent(memorySummary)); // 只打印80字以内预览，避免日志过长。
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

    private boolean shouldForceSummarizeArticle(String currentMessage) { // 判断用户是否明确要求总结某篇文章/笔记。
        if (currentMessage == null || currentMessage.isBlank()) { // 空消息不能触发强制总结。
            return false; // 保持空输入安全返回。
        }
        boolean hasSummaryAction = containsAny(currentMessage, FORCE_SUMMARY_KEYWORDS); // 只基于当前输入判断是否有总结、提炼、要点等动作。
        boolean hasExplicitTarget = containsAny(currentMessage, FORCE_SUMMARY_TARGET_KEYWORDS); // 判断是否明确指向笔记、文章、代码、篇号或articleId。
        boolean hasContextReference = containsAny(currentMessage, FORCE_SUMMARY_CONTEXT_REFERENCE_KEYWORDS); // 判断是否指向刚才、之前、上面等会话focus目标。
        boolean hasArticleId = resolveArticleIdFromMessage(currentMessage) != null; // 显式ID本身也说明目标清晰。
        log.info("[ToolChatStream] summary action detected: {}", hasSummaryAction); // 打印总结动作识别结果。
        log.info("[ToolChatStream] summary explicit target detected: {}", hasExplicitTarget || hasArticleId); // 打印显式目标识别结果。
        log.info("[ToolChatStream] summary context reference detected: {}", hasContextReference); // 打印上下文指代识别结果。
        if (!hasSummaryAction) { // 没有总结、摘要、提炼、要点、面试话术等动作。
            return false; // 不强制summarizeArticle，避免普通技术问题误判。
        }
        return hasExplicitTarget || hasContextReference || hasArticleId; // 有总结动作且有目标指向时才强制总结工具。
    }

    private String resolveSummaryType(String currentMessage) { // 从当前输入解析总结类型。
        if (currentMessage == null || currentMessage.isBlank()) { // 空消息兜底普通摘要。
            return SummaryTypeConstants.NORMAL; // 默认normal。
        }
        if (containsAny(currentMessage, SUMMARY_INTERVIEW_KEYWORDS)) { // 面试相关请求优先识别为interview。
            return SummaryTypeConstants.INTERVIEW; // 返回面试话术总结。
        }
        if (containsAny(currentMessage, SUMMARY_POINTS_KEYWORDS)) { // 要点、提炼、梳理、列点等请求识别为points。
            return SummaryTypeConstants.POINTS; // 返回要点总结。
        }
        return SummaryTypeConstants.NORMAL; // 默认普通摘要。
    }

    private Long resolveArticleIdFromMessage(String currentMessage) { // 从当前输入中提取显式文章ID。
        if (currentMessage == null || currentMessage.isBlank()) { // 空消息无法提取ID。
            return null; // 返回null交给SummarizeArticleTool从focus补全。
        }
        for (Pattern pattern : ARTICLE_ID_PATTERNS) { // 逐个尝试轻量规则。
            Matcher matcher = pattern.matcher(currentMessage); // 匹配当前输入。
            if (!matcher.find()) { // 未命中当前规则。
                continue; // 尝试下一条。
            }
            try {
                return Long.parseLong(matcher.group(1)); // 返回第一个捕获到的数字ID。
            } catch (NumberFormatException e) {
                return null; // 理论上正则只捕获数字，兜底返回null。
            }
        }
        return null; // 没有显式ID时交给focus补全。
    }

    private boolean containsAny(String content, String[] keywords) { // 判断文本是否包含任意关键词。
        if (content == null || keywords == null || keywords.length == 0) { // 空输入或空关键词不匹配。
            return false; // 返回false。
        }
        String normalizedContent = content.toLowerCase(); // 统一小写后匹配，兼容 bullet 等英文关键词。
        for (String keyword : keywords) { // 遍历关键词。
            if (keyword != null && !keyword.isBlank() && normalizedContent.contains(keyword.toLowerCase())) { // 命中关键词。
                return true; // 返回true。
            }
        }
        return false; // 未命中任何关键词。
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
