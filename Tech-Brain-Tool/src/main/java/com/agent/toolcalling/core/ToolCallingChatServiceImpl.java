package com.agent.toolcalling.core; // Tool Calling公共编排服务包。

import com.agent.toolcalling.client.DeepSeekClient; // 公共DeepSeek HTTP客户端，负责实际chat/completions调用。
import com.agent.toolcalling.client.DeepSeekStreamCallback; // DeepSeek底层流式回调，chatStream会适配成ToolCallingStreamCallback。
import com.agent.toolcalling.config.DeepSeekProperties; // 读取deepseek.model-name/base-url等配置，继续保持deepseek-v4-pro。
import com.agent.toolcalling.context.ChatAttachedFileContext; // 本轮聊天附带的用户文件安全元信息。
import com.agent.toolcalling.context.ConversationFocusContext; // 通用会话焦点上下文，读取projectFileFocus元信息。
import com.agent.toolcalling.context.ConversationFocusService; // 通用会话焦点服务，readProjectFile成功后保存projectFileFocus。
import com.agent.toolcalling.context.ToolCallingContextHolder; // 工具执行前后写入并清理ThreadLocal上下文。
import com.agent.toolcalling.context.ToolCallingRequestContext; // 当前Tool Calling请求上下文，包含userId和conversationId。
import com.agent.toolcalling.project.ProjectCodeTargetResolution; // 项目代码统一目标解析结果。
import com.agent.toolcalling.project.ProjectCodeTargetResolver; // 项目代码统一目标解析器，统一“明确目标 > 全项目扫描 > focus”优先级。
import com.agent.toolcalling.log.ToolCallLogRecorder; // 跨模块工具调用日志回调，不让Tool模块反向依赖Agent服务。
import com.agent.toolcalling.project.analysis.CodeAnalysisType; // P5.5.5 analyzeCode 统一分析类型。
import com.agent.toolcalling.project.language.CodeLanguageRegistry; // P4项目源码语言识别注册表，复用markdownName规则。
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
import java.util.Locale;
import java.util.Set;
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
            "\n3. readFile：用于按 fileId 读取当前用户已上传的文本/代码类文件内容。" +
            "\n4. listProjectTree：用于读取项目 workspace 内指定目录的目录树结构，只返回目录和文件元信息，不读取文件内容。" +
            "\n5. searchCode：用于在项目 workspace 内搜索类名、方法名、注解、接口路径和关键词，只返回相对路径、行号和代码片段。" +
            "\n6. analyzeCode：统一项目代码分析工具，通过 analysisType 支持 STRUCTURE、CALL_CHAIN、CONTROLLER_SERVICE、TOOL_SERVICE、SSE_EVENT_CHAIN、EXPLANATION，不返回完整源码，不修改项目文件。" +
            "\n7. readProjectFile：用于读取项目 workspace 内指定代码/文本文件内容，必须传相对 workspace 的文件路径，禁止读取敏感文件和 workspace 外路径。" +
            "\n\n调用 ragSearch 的规则：" +
            "\n- 当用户明确要求“根据知识库”“根据我的知识库”“根据我的笔记”“根据资料”“根据项目文档”“根据我保存的内容”“查我的知识库”“从我的文章里找”等内容时，必须调用 ragSearch。" +
            "\n- 当用户询问 Tech-Brain 项目中已经保存的资料、笔记内容、文章内容、项目文档内容时，必须调用 ragSearch。" +
            "\n- 当用户只是普通闲聊、打招呼、感谢、询问通用知识、普通编程问题时，不要调用 ragSearch，直接回答。" +
            "\n- 如果用户没有明确要求基于知识库或个人资料回答，不要为了普通问题主动调用 ragSearch。" +
            "\n- ragSearch 不用于查询当前会话附件、刚上传的文件、这些文件、最近上传的文件或文件库里的文件。" +
            "\n- 当用户询问刚才发的文件、这些附件、当前文件时，应优先查看 attachedFiles 附件元信息，不要把附件问题改写成知识库检索。" +
            "\n- 如果用户要求读取附件正文，应调用 readFile 工具；readFile 仅支持文本/代码类文件，不支持 PDF、Word、图片解析。" +
            "\n- 未读取文件正文前，不得假装已经分析或总结了附件内容。" +
            "\n- 如果用户只问“我上传了哪些文件”“这轮有哪些附件”，只需基于 attachedFiles 元信息回答，不必调用 readFile。" +
            "\n- 如果用户要求“分析 main.py”“看看这个 sql”“总结这个 md 文件”“读取 fileId=4”，应调用 readFile，而不是 ragSearch。" +
            "\n- 当用户明确要求读取、分析、总结当前上传的文本/代码文件时，必须调用 readFile；不要回答“我不能读取文件内容”，除非 readFile 返回失败。" +
            "\n- 如果 readFile 返回 success=true，最终回答必须基于 readFile.content；如果 truncated=true，需要说明内容已截断。" +
            "\n- 未调用 readFile 之前，不得假装分析了文件正文，也不要用 ragSearch 读取当前附件。" +
            "\n\n调用 listProjectTree 的规则：" +
            "\n- 当用户要求查看项目目录结构、文件树、项目结构、模块结构、某个目录下有哪些文件时，应调用 listProjectTree。" +
            "\n- listProjectTree 只返回目录结构和代码/文本文件元信息，不读取文件内容。" +
            "\n- 如果用户要搜索代码位置，应调用 searchCode；如果用户要分析项目源码结构、调用链、Controller→Service、Tool→Service、SSE 链路或生成代码说明，应调用 analyzeCode；如果用户要读取指定项目代码文件，应调用 readProjectFile。" +
            "\n- 不要用 ragSearch 查询项目 workspace 的文件结构；ragSearch 只用于知识库、笔记、文章和项目资料检索。" +
            "\n- 未读取文件内容前，不得声称已经看过具体代码正文。" +
            "\n\n调用 searchCode 的规则：" +
            "\n- 当用户问某个类、方法、接口、注解、接口路径或关键词在哪时，应调用 searchCode。" +
            "\n- 当用户问“AgentController 在哪里”“saveNote 方法在哪”“ragSearch 相关代码在哪”“@PostMapping 在哪些文件里”时，应调用 searchCode。" +
            "\n- searchCode 只定位代码位置和片段，不负责完整读取文件，不生成 patch，不修改项目代码。" +
            "\n- 不要用 ragSearch 查询项目 workspace 代码位置；ragSearch 只用于知识库、笔记、文章和项目资料检索。" +
            "\n\n调用 analyzeCode 的规则：" +
            "\n- analyzeCode 是唯一对外项目代码分析 Tool；不要调用 analyzeCallChain、analyzeControllerServiceChain、analyzeToolServiceChain 或 analyzeSseEventChain，这些能力已收敛为 analyzeCode 内部 Analyzer。" +
            "\n- 当用户要求代码结构、方法/字段/注解/接口/Tool 信息时，应调用 analyzeCode，analysisType=STRUCTURE，可传 path 或 className；只有 path/className 时也默认 STRUCTURE。" +
            "\n- 当用户要求普通调用链、调用关系、方法调用了什么、依赖哪些对象时，应调用 analyzeCode，analysisType=CALL_CHAIN，可传 path/className/methodName。" +
            "\n- 当用户要求 Controller→Service、接口背后调用哪些 Service、/api 接口链路时，应调用 analyzeCode，analysisType=CONTROLLER_SERVICE；API 路径必须放 endpoint，不能放 path。" +
            "\n- 当用户要求 Tool→Service、工具背后调用哪些 Service/Guard/Registry/Mapper/Repository、execute 链路时，应调用 analyzeCode，analysisType=TOOL_SERVICE，可传 toolName、className 或 path。" +
            "\n- 当用户要求前后端 SSE、流式响应、事件链路、事件发送/接收、前端哪里调用/接收接口时，应调用 analyzeCode，analysisType=SSE_EVENT_CHAIN，可传 endpoint、eventName、frontendKeyword、methodName 或 path。" +
            "\n- 当用户要求说明、解释、讲一下、代码说明、面试话术、这个类/方法/接口/Tool 怎么工作时，应调用 analyzeCode，analysisType=EXPLANATION；可传 explanationType、detailLevel、audience。" +
            "\n- 如果没有明确目标且当前会话有 recentProjectTarget，应优先用于“这个类/这个工具/这个文件/它”等追问；没有 recentProjectTarget 时再使用 projectFileFocus；如果有 endpoint/className/toolName/eventName/frontendKeyword，必须以明确目标为准，不被上下文焦点锁死。" +
            "\n- analyzeCode 只返回结构化静态分析结果，不返回完整源码，不做完整精准调用图，不做风险分析、测试步骤、开发日志保存或 patch，不修改前端或后端业务代码。" +
            "\n\n调用 readProjectFile 的规则：" +
            "\n- 当用户要求读取、打开、查看项目 workspace 内某个代码文件或文本文件的完整内容时，应调用 readProjectFile。" +
            "\n- 当用户说“读取这个路径”“打开 SearchCodeTool.java”“看完整 ToolCallingChatServiceImpl.java”“读取 Tech-Brain-Agent/src/main/java/... 文件”时，应调用 readProjectFile。" +
            "\n- readProjectFile 用于读取项目源码文件；analyzeCode 用于分析代码结构；readFile 用于读取用户上传文件；searchCode 用于定位文件和代码片段；listProjectTree 用于查看目录树。" +
            "\n- readProjectFile 只能接收相对 workspace 的路径，不得读取绝对路径、路径穿越、敏感配置、密钥、证书、二进制文件或压缩包。" +
            "\n- 未调用 readProjectFile 前，不要假装已经看过完整项目源码文件。" +
            "\n\n调用 summarizeArticle 的规则：" +
            "\n- 当用户要求总结指定编号、指定ID、某一篇笔记或某一篇文章时，必须调用 summarizeArticle。" +
            "\n- 用户说“帮我总结第15篇笔记”“总结文章15”“把第15篇笔记整理成要点”时，articleId 应提取为 15。" +
            "\n- 用户说“帮我总结这篇笔记”“总结刚才那篇”“把上面那篇整理成要点”“把刚刚查到的那篇总结成面试话术”“就是这篇笔记”时，也必须调用 summarizeArticle；如果当前输入没有明确ID，可以不传 articleId，后端会尝试从最近一次 ragSearch 命中文档中补全。" +
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
            "\n本轮没有调用任何工具，也没有执行知识库检索工具，因此不存在知识库检索结果可判断。" +
            "\n你可以结合历史上下文理解用户追问。" +
            "\n如果当前问题是追问，并且历史消息中有相关上下文，你可以结合历史上下文回答。" +
            "\n当用户说“这个”“它”“上面那个”“刚才那个”“这段代码”等表达时，需要根据最近历史判断指代对象。" +
            "\n如果回答基于历史，可以说“结合上文”或“根据刚才的代码”，不要说“根据知识库”。" +
            "\n不能把历史内容伪装成新一轮知识库检索结果。" +
            "\n本轮 has tool call=false 时，不能冒充知识库、数据库、笔记或检索结果。" +
            "\n禁止使用“根据你的知识库”“根据数据库”“根据你的笔记”“根据检索结果”等表述。" +
            "\n禁止使用“知识库没有”“没有检索到你的知识库”“没有检索到你的笔记”“你的笔记里没有”“根据数据库没有找到”等检索结论式表述。" +
            "\n普通回答里不要主动说明知识库状态；如果必须说明，只能说“本轮未检索知识库”。" +
            "\n如果历史和当前问题冲突，以当前问题为准。" +
            "\n可以结合短期历史和长期记忆理解用户追问，但当前问题优先级最高。" +
            "\n不要声称“根据你的知识库”“根据你的笔记”“根据资料”。" +
            "\n普通闲聊、通用知识和普通编程问题按常规能力直接回答。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String RAG_SEARCH_TOOL_NAME = "ragSearch"; // 强制知识库路由时固定执行的工具名。

    private static final String SUMMARIZE_ARTICLE_TOOL_NAME = "summarizeArticle"; // 强制总结路由时固定执行的工具名。

    private static final String READ_FILE_TOOL_NAME = "readFile"; // 文件读取工具名。

    private static final String LIST_PROJECT_TREE_TOOL_NAME = "listProjectTree"; // 项目目录树工具名。

    private static final String SEARCH_CODE_TOOL_NAME = "searchCode"; // 项目代码搜索工具名。

    private static final String ANALYZE_CODE_TOOL_NAME = "analyzeCode"; // 项目代码结构分析工具名。

    private static final String READ_PROJECT_FILE_TOOL_NAME = "readProjectFile"; // 项目代码文件读取工具名。

    private static final String ARTICLE_SUMMARY_RESULT_TYPE = "article_summary"; // 文章总结工具结果类型，命中后聊天只输出chatMessage。

    private static final String SUMMARY_RESULT_EVENT_NAME = "summary_result"; // 文章总结完整结果通过该SSE业务事件交给前端弹窗。

    private static final String CALL_SOURCE_FORCE_ROUTE = "FORCE_ROUTE"; // 后端强制路由工具调用来源。

    private static final String CALL_SOURCE_MODEL_TOOL_CALL = "MODEL_TOOL_CALL"; // 模型主动 tool_call 工具调用来源。

    private static final String ROUTE_REASON_FORCE_RAG_SEARCH = "force ragSearch"; // 强制执行 ragSearch 的路由原因。

    private static final String ROUTE_REASON_FORCE_SUMMARIZE_ARTICLE = "force summarizeArticle"; // 强制执行 summarizeArticle 的路由原因。

    private static final String ROUTE_REASON_FORCE_READ_FILE = "force readFile"; // 强制执行 readFile 的路由原因。

    private static final String ROUTE_REASON_FORCE_LIST_PROJECT_TREE = "force listProjectTree"; // 强制执行 listProjectTree 的路由原因。

    private static final String ROUTE_REASON_FORCE_SEARCH_CODE = "force searchCode"; // 强制执行 searchCode 的路由原因。

    private static final String ROUTE_REASON_FORCE_ANALYZE_CODE = "force analyzeCode"; // 强制执行 analyzeCode 的路由原因。

    private static final String ROUTE_REASON_FORCE_ANALYZE_CODE_BY_RECENT_TARGET = "force analyzeCode by recentProjectTarget"; // 通过最近明确项目目标强制执行 analyzeCode 的路由原因。

    private static final String ROUTE_REASON_FORCE_ANALYZE_CODE_BY_FOCUS = "force analyzeCode by projectFileFocus"; // 通过项目文件焦点强制执行 analyzeCode 的路由原因。

    private static final String ROUTE_REASON_FORCE_ANALYZE_CODE_EXPLANATION_BY_ENDPOINT = "force analyzeCode explanation by endpoint"; // 通过接口路径触发代码说明的路由原因。

    private static final String ROUTE_REASON_FORCE_ANALYZE_CODE_EXPLANATION_BY_RECENT_TARGET = "force analyzeCode explanation by recentProjectTarget"; // 通过最近明确项目目标触发代码说明的路由原因。

    private static final String ROUTE_REASON_FORCE_ANALYZE_CODE_EXPLANATION_BY_FOCUS = "force analyzeCode explanation by projectFileFocus"; // 通过项目文件焦点触发代码说明的路由原因。

    private static final String ROUTE_REASON_FORCE_READ_PROJECT_FILE = "force readProjectFile"; // 强制执行 readProjectFile 的路由原因。

    private static final String ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_NAME = "force readProjectFile by file name or class name"; // 通过类名/文件名唯一定位项目文件时的路由原因。

    private static final String ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_RECENT_TARGET = "force readProjectFile by recentProjectTarget"; // 通过最近明确项目目标触发 readProjectFile 的路由原因。

    private static final String ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_FOCUS = "force readProjectFile by projectFileFocus"; // 通过当前会话项目源码文件焦点触发 readProjectFile 的路由原因。

    private static final String ROUTE_REASON_MODEL_TOOL_CALL = "model tool_call"; // 模型主动调用工具的路由原因。

    private static final String TOOL_TYPE_RAG = "RAG"; // ragSearch 对应的日志工具类型。

    private static final String TOOL_TYPE_SUMMARY = "SUMMARY"; // summarizeArticle 对应的日志工具类型。

    private static final String TOOL_TYPE_FILE = "FILE"; // readFile 对应的日志工具类型。

    private static final String TOOL_TYPE_PROJECT = "PROJECT"; // listProjectTree 对应的日志工具类型。

    private static final String TOOL_TYPE_UNKNOWN = "UNKNOWN"; // 未知工具对应的日志工具类型。

    private static final int FORCE_READ_FILE_MAX_CHARS = 8000; // 强制读取文件时默认最多读取8000字符，避免工具日志和模型上下文过大。

    private static final int FORCE_LIST_PROJECT_TREE_MAX_DEPTH = 4; // 强制读取项目目录树时默认最大深度。

    private static final int FORCE_LIST_PROJECT_TREE_MAX_NODES = 300; // 强制读取项目目录树时默认节点上限。

    private static final int FORCE_SEARCH_CODE_MAX_RESULTS = 20; // 强制搜索代码时默认最大结果数。

    private static final int FORCE_SEARCH_CODE_CONTEXT_LINES = 3; // 强制搜索代码时默认上下文行数。
    private static final int FORCE_ANALYZE_CODE_MAX_ITEMS = 100; // 强制分析代码结构时默认最大结构项数。
    private static final int FORCE_ANALYZE_CALL_CHAIN_MAX_ITEMS = 100; // 强制分析调用链时默认最大调用项数。
    private static final int FORCE_ANALYZE_CALL_CHAIN_MAX_DEPTH = 1; // 强制分析调用链时默认深度，第一版只取 1 层。
    private static final int FORCE_CONTROLLER_SERVICE_CHAIN_MAX_ITEMS = 100; // 强制分析 Controller→Service 链路时默认最大项数。
    private static final int FORCE_TOOL_SERVICE_CHAIN_MAX_ITEMS = 100; // 强制分析 Tool→业务组件链路时默认最大项数。
    private static final int FORCE_TOOL_SERVICE_CHAIN_MAX_DEPTH = 1; // 强制分析 Tool→业务组件链路时默认深度。
    private static final int FORCE_SSE_EVENT_CHAIN_MAX_ITEMS = 100; // 强制分析前后端 SSE 链路时默认最大项数。
    private static final int FORCE_SSE_EVENT_CHAIN_MAX_DEPTH = 1; // 强制分析前后端 SSE 链路时默认深度。
    private static final int FORCE_READ_PROJECT_FILE_MAX_CHARS = 50000; // 强制读取项目文件时默认最多读取50000字符。
    private static final String READ_PROJECT_FILE_MODE_SUMMARY = "SUMMARY"; // 项目文件读取后的分析模式。
    private static final String READ_PROJECT_FILE_MODE_FULL = "FULL"; // 项目文件读取后的完整代码输出模式。
    private static final int PLAIN_TEXT_STREAM_CHUNK_SIZE = 120; // 后端确定性文本流式输出时每个SSE分块的字符数。
    private static final long PLAIN_TEXT_STREAM_DELAY_MS = 8L; // 后端确定性文本流式输出时每块之间的轻微间隔。
    private static final int TOOL_DSL_ROLLING_BUFFER_LIMIT = 200; // 流式检测内部工具协议时最多保留最近200个字符。
    private static final String[] TOOL_DSL_LEAK_MARKERS = { // 任意命中都视为最终回答泄漏了内部工具调用格式。
            "<||dsml||",
            "tool_calls",
            "invoke name=",
            "parameter name=",
            "</invoke>",
            "<tool_call>",
            "function_call"
    };
    private static final String TOOL_FINAL_ANSWER_STRICT_SYSTEM_PROMPT = "你现在处于工具结果总结阶段，已经拿到了工具执行结果。" // 工具结果总结阶段禁止模型继续暴露工具协议。
            + "\n禁止再调用任何工具，禁止输出任何内部工具调用格式。"
            + "\n禁止输出：<||DSML||tool_calls>、<||DSML||invoke>、invoke name=、parameter name=、tool_calls、function_call、<tool_call>。"
            + "\n你必须只基于当前工具结果生成用户可读回答。"
            + "\n如果工具结果中 resultCount > 0，必须总结命中文件、行号和关键片段；如果 resultCount = 0，才说明未找到。"
            + "\n不要被历史对话中的其它搜索目标干扰，当前用户问题、当前工具参数和当前工具结果优先级最高。"; // 统一收束工具结果最终回答。
    private static final String SEARCH_CODE_FINAL_ANSWER_SYSTEM_PROMPT = "当前工具是 searchCode。" // searchCode 专项最终回答规则。
            + "\n当前搜索 query 以 arguments_json.query 为准。"
            + "\n如果 result_json.resultCount > 0，不能说没有找到，必须列出前 5 到 10 个最相关结果。"
            + "\n每条结果应包含文件路径、行号、matchType 和 matchedLine 的简短摘要。"
            + "\n如果 stoppedEarly=true，需要说明结果已截断。"
            + "\n如果用户询问项目有哪些工具、某某是不是工具、项目中是否存在某个工具，必须优先识别真实 AI Tool 实现类。"
            + "\n真实 AI Tool 的判断依据包括：类名以 Tool 结尾并继承 AbstractAiTool、实现 AiTool、包含 TOOL_NAME 常量、name() 返回工具名、@Component 且位于 agent/tool 业务包、由 ToolRegistry 自动收集 AiTool Bean 注册。"
            + "\nToolCallLog、ToolCallLogVO、ToolCallLogController、ToolCallLogMapper、ToolCallLogService 只是工具调用日志相关代码，不是 AI Tool 本体。"
            + "\n如果搜索到 ListProjectTreeTool.java，应明确说明 listProjectTree 是 AI Tool；如果搜索到 SearchCodeTool.java，应明确说明 searchCode 是 AI Tool。"
            + "\n不要输出完整 result_json，不要输出 DSML，不要声称已读取完整文件。"; // 避免 searchCode 结果被历史上下文污染。

    private static final String ANALYZE_CODE_FINAL_ANSWER_SYSTEM_PROMPT = "当前工具是 analyzeCode。" // analyzeCode 专项最终回答规则。
            + "\nanalyzeCode 是唯一对外项目代码分析 Tool；必须读取 result_json.analysisType 和 result_json.result.type，再按对应内部分析结果回答。"
            + "\n如果 success=false，只说明工具返回的失败原因；如果返回 candidates、candidateEndpoints 或 candidateEvents，只列出这些真实候选，不要编造文件、接口、Tool 或事件。"
            + "\nSTRUCTURE：基于 result.classInfo、fields、methods、springEndpoints、aiToolInfo 和 basicSymbols 回答文件概览、类信息、方法/字段/接口/Tool 信息。"
            + "\nCALL_CHAIN：基于 result.dependencies、internalCalls、externalCalls、utilityCalls、unresolvedCalls 和 candidateTargets 回答入口类/方法、内部调用、外部调用、依赖对象和候选目标文件。"
            + "\nCONTROLLER_SERVICE：基于 result.controller、endpoints、dependencies、serviceCalls、serviceMethodCalls 和候选文件回答 Controller 概览、endpoint、Controller 方法、Service 调用和 ServiceImpl 候选；如果 arguments_json 只有 endpoint 没有 path，应说明这是先按接口路径在全项目 Controller 中匹配再分析，不要把 endpoint 说成源码路径。"
            + "\nTOOL_SERVICE：基于 result.aiToolInfo、dependencies、internalCalls、serviceCalls、guardCalls、registryCalls、mapperCalls、repositoryCalls、toolCalls、utilityCalls、unresolvedCalls、serviceMethodCalls 和候选文件回答 Tool 概览、toolName、execute、依赖组件和业务组件调用。"
            + "\nSSE_EVENT_CHAIN：基于 result.frontendCalls、frontendEventHandlers、backendSseEndpoints、serviceCalls、backendEventSenders 和 toolCalls 回答前端发起点、后端 SSE Controller、Controller→Service、后端事件发送和前端事件接收；前端为空时如实说明未扫描到前端 SSE 代码。"
            + "\nEXPLANATION：基于 result.summary、responsibilities、mainFlow、keyMethods、dependencies、callChainExplanation、sseFlowExplanation、importantDetails 和 sourceEvidence 输出代码说明；audience=INTERVIEW 时用面试话术风格，但不得夸大或编造。"
            + "\n候选目标文件只是轻量静态文本推断，必须说明不保证完全精准；如果 result_json.truncated=true 或 result.truncated=true，需要说明已按 maxItems 截断。"
            + "\n不要输出完整源码，不要输出完整 result_json，不要生成风险分析、测试步骤、修改方案或 patch，不要声称修改了文件。";

    private static final String FORCED_RAG_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行ragSearch后的回答约束。
            "\n用户明确要求根据个人知识库、笔记或资料回答。" +
            "\n后端已经强制执行 ragSearch 工具。" +
            "\n工具结果优先级最高，你必须严格基于 ragSearch 返回的知识库内容回答。" +
            "\n历史上下文只用于理解指代和追问，不允许用历史替代工具结果。" +
            "\n长期记忆只用于辅助理解，不允许替代工具结果。" +
            "\n如果工具结果说明没有检索到相关内容，只能说“本次知识库检索未找到直接相关内容”，不要说“你的知识库绝对没有”，也不要从历史、长期记忆或常识里编造知识库结果。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String TOOL_RESULT_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 模型主动tool_call后的最终回答约束。
            "\n本轮已经执行了工具调用。" +
            "\n工具结果优先级高于历史上下文。" +
            "\n历史上下文只用于理解指代和追问，不允许用历史替代工具结果。" +
            "\n长期记忆只用于辅助理解，不允许替代工具结果。" +
            "\n如果 ragSearch 工具结果说明没有相关内容，只能说“本次知识库检索未找到直接相关内容”，不要说“你的知识库绝对没有”。" +
            "\n如果工具结果是 article_summary 类型：聊天窗口只输出 chatMessage，不要输出 summary 全文；如果 success=false，只输出 chatMessage 中的错误提示；summary 完整内容后续由前端弹窗展示。" +
            "\n如果工具结果是 project_tree 类型：只能基于目录树元信息说明项目结构、模块和文件分布；不要声称已经读取了文件内容。" +
            "\n如果工具结果是 code_search 类型：只能基于返回的相对路径、行号、命中行和 snippet 说明代码位置；不要声称已经读取完整文件，也不要生成 patch。" +
            "\n如果工具结果是 code_analysis 类型：必须先看 analysisType 和 result.type；STRUCTURE 基于 classInfo/fields/methods/springEndpoints/aiToolInfo/basicSymbols，CALL_CHAIN 基于 dependencies/internalCalls/externalCalls/candidateTargets，CONTROLLER_SERVICE 基于 controller/endpoints/serviceCalls/serviceMethodCalls，TOOL_SERVICE 基于 aiToolInfo/dependencies/serviceCalls/guardCalls/registryCalls/mapperCalls/repositoryCalls，SSE_EVENT_CHAIN 基于 frontendCalls/frontendEventHandlers/backendSseEndpoints/serviceCalls/backendEventSenders/toolCalls，EXPLANATION 基于 summary/responsibilities/mainFlow/keyMethods/dependencies/callChainExplanation/sseFlowExplanation/importantDetails/sourceEvidence；不要输出完整源码，不要生成风险分析、测试步骤或 patch。" +
            "\n如果工具结果是 call_chain_analysis 类型：只能基于返回的 dependencies、internalCalls、externalCalls、utilityCalls、unresolvedCalls 和 candidateTargets 说明调用关系；候选目标文件不保证完全精准；不要输出完整源码，不要生成风险分析、测试步骤或 patch。" +
            "\n如果工具结果是 controller_service_chain_analysis 类型：只能基于返回的 controller、endpoints、dependencies、serviceCalls、serviceMethodCalls 和候选文件说明 Controller→Service 链路；候选 ServiceImpl 不保证完全精准；不要输出完整源码，不要生成风险分析、测试步骤或 patch。" +
            "\n如果工具结果是 tool_service_chain_analysis 类型：只能基于返回的 aiToolInfo、dependencies、internalCalls、serviceCalls、guardCalls、registryCalls、mapperCalls、repositoryCalls、toolCalls、utilityCalls、unresolvedCalls、serviceMethodCalls 和候选文件说明 Tool→业务组件链路；候选目标文件不保证完全精准；不要输出完整源码，不要生成风险分析、测试步骤或 patch。" +
            "\n如果工具结果是 sse_event_chain_analysis 类型：只能基于返回的 frontendCalls、frontendEventHandlers、backendSseEndpoints、serviceCalls、backendEventSenders、toolCalls 说明前后端 SSE 事件链路；前端为空时如实说明未扫描到前端 SSE 代码；候选文件不保证完全精准；不要输出完整源码，不要生成风险分析、测试步骤或 patch。" +
            "\n如果工具结果是 project_file_read 类型：只能基于返回的 path、language、truncated 和 content 说明项目源码文件内容；不要声称已经修改文件，不要生成 patch，不要应用 patch。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String FORCED_READ_FILE_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行readFile后的回答约束。
            "\n用户正在要求读取、查看、分析或总结当前会话附件中的文件。" +
            "\n后端已经强制执行 readFile 工具。" +
            "\nreadFile 工具结果优先级最高，必须基于工具返回的 JSON 回答。" +
            "\n如果 readFile 返回 success=true，必须基于 content 字段分析，不要编造 content 之外的内容。" +
            "\n如果 readFile 返回 truncated=true，需要说明当前只读取了前面一部分内容。" +
            "\n如果 readFile 返回 success=false，只能如实说明工具返回的失败原因，不要假装读取过文件。" +
            "\n不要调用或暗示使用 ragSearch 读取当前附件；ragSearch 只用于知识库、笔记、文章和项目资料检索。" +
            "\n不要解析 PDF、Word 或图片内容；readFile 不支持时应如实说明暂不支持直接读取。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String FORCED_PROJECT_TREE_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行listProjectTree后的回答约束。
            "\n用户正在要求查看项目目录结构、文件树、模块结构或某个目录下有哪些文件。" +
            "\n后端已经强制执行 listProjectTree 工具。" +
            "\nlistProjectTree 工具结果优先级最高，必须基于返回的 project_tree JSON 回答。" +
            "\n该工具只返回目录和文件元信息，不包含文件内容；不要声称已经阅读或分析了具体代码正文。" +
            "\n不要使用 ragSearch 来查询项目 workspace 文件结构。" +
            "\n如果目录树被 truncated=true 截断，需要提示用户可缩小 path、maxDepth 或 maxNodes 后继续查看。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String FORCED_SEARCH_CODE_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行searchCode后的回答约束。
            "\n用户正在要求定位项目 workspace 中的类、方法、注解、接口路径或关键词。" +
            "\n后端已经强制执行 searchCode 工具。" +
            "\nsearchCode 工具结果优先级最高，必须基于返回的 code_search JSON 回答。" +
            "\n该工具只返回相对路径、行号、命中行和少量上下文片段，不代表已经读取完整文件。" +
            "\n不要使用 ragSearch 查询项目 workspace 代码位置。" +
            "\n不要生成 patch，不要修改项目代码，不要声称已经完整分析了整个文件。" +
            "\n如果 resultCount=0，只说明本次安全搜索范围内未找到匹配结果。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String FORCED_READ_PROJECT_FILE_SYSTEM_PROMPT = "你是 Tech-Brain 项目的 AI 助手。" + // 后端强制执行readProjectFile后的回答约束。
            "\n用户正在要求读取、打开或查看项目 workspace 内的代码/文本文件。" +
            "\n后端已经强制执行 readProjectFile 工具。" +
            "\nreadProjectFile 工具结果优先级最高，必须基于返回的 project_file_read JSON 回答。" +
            "\n如果 success=true，必须基于 content 字段说明文件内容；如果 truncated=true，需要说明当前只读取了前面一部分内容。" +
            "\n如果 success=false，只能如实说明工具返回的失败原因，不要假装看过文件内容。" +
            "\n不要生成 patch，不要应用 patch，不要声称已经修改项目文件。" +
            "\n不要使用 ragSearch 读取项目 workspace 文件；ragSearch 只用于知识库、笔记、文章和项目资料检索。" +
            "\n不要输出服务器绝对路径，只能引用工具结果中的相对 path。" +
            CONTEXT_PRIORITY_PROMPT;

    private static final String RAG_EMPTY_RESULT_TEXT = "知识库中没有检索到与该问题相关的内容"; // RagSearchTool当前无命中固定返回片段。

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

    private static final String[] FILE_ATTACHMENT_INTENT_KEYWORDS = { // 用户正在询问当前会话附件或刚上传文件时的轻量识别词。
            "文件",
            "附件",
            "上传",
            "刚发",
            "刚才发",
            "我发的",
            "我给你的",
            "给了",
            "这些文件",
            "这几个文件",
            "当前文件",
            "最近上传",
            "上传的文件",
            "刚上传",
            "fileid",
            "图片",
            "照片",
            "截图"
    };

    private static final String[] FILE_ATTACHMENT_EXT_KEYWORDS = { // 文件名或扩展名命中时也视为附件意图。
            ".py",
            ".java",
            ".vue",
            ".sql",
            ".js",
            ".ts",
            ".json",
            ".xml",
            ".html",
            ".css",
            ".md",
            ".txt",
            ".yml",
            ".yaml",
            ".properties",
            ".pdf",
            ".doc",
            ".docx",
            ".png",
            ".jpg",
            ".jpeg",
            ".webp"
    };

    private static final String[] EXPLICIT_RAG_INTENT_KEYWORDS = { // 明确要求查知识库/笔记/文章时，不因出现“文件”而拦截RAG。
            "根据我的知识库",
            "根据知识库",
            "从知识库回答",
            "查知识库",
            "检索知识库",
            "知识库里",
            "我的知识库",
            "根据我的笔记",
            "根据笔记",
            "根据笔记回答",
            "查我的笔记",
            "从我的文章里找",
            "从我的笔记里找",
            "从知识库里找",
            "根据资料",
            "根据项目文档",
            "根据项目资料",
            "根据我保存的内容",
            "rag"
    };

    private static final String[] PROJECT_TREE_INTENT_KEYWORDS = { // 用户查看项目目录树、项目结构或模块结构时的强制路由关键词。
            "项目目录",
            "目录结构",
            "文件树",
            "项目结构",
            "项目树",
            "目录树",
            "模块结构",
            "有哪些模块",
            "有哪些文件",
            "列一下目录",
            "列出目录",
            "列一下项目",
            "列出项目",
            "看看项目",
            "查看项目",
            "src/main/java",
            "list tree"
    };

    private static final String[] PROJECT_TREE_EXCLUDED_KEYWORDS = { // 排除用户文件库和聊天附件场景，避免误触发项目目录树。
            "附件",
            "上传",
            "文件库",
            "fileId"
    };

    private static final String[] SEARCH_CODE_INTENT_KEYWORDS = { // 用户要求搜索代码位置时的强制路由关键词。
            "在哪里",
            "在哪",
            "哪个文件",
            "哪个类",
            "哪个方法",
            "搜索",
            "查找",
            "找一下",
            "相关代码",
            "代码在哪",
            "方法在哪",
            "类在哪",
            "接口在哪",
            "controller 在哪",
            "service 在哪",
            "@postmapping",
            "@getmapping",
            "@requestmapping"
    };

    private static final String[] SEARCH_CODE_QUERY_STOP_WORDS = { // 从自然语言中提取 query 时移除的描述词。
            "在哪里",
            "在哪",
            "在哪些文件里",
            "在哪些文件",
            "哪个文件",
            "哪个类",
            "哪个方法",
            "搜索",
            "查找",
            "找一下",
            "相关代码",
            "代码在哪",
            "方法在哪",
            "类在哪",
            "接口在哪",
            "方法",
            "类",
            "接口",
            "代码",
            "文件",
            "里",
            "？",
            "?",
            "：",
            ":"
    };

    private static final String[] PROJECT_TOOL_INVENTORY_KEYWORDS = { // 用户询问项目 AI Tool 清单或某工具是否存在时的识别词。
            "项目里有多少工具",
            "项目有多少工具",
            "项目有哪些工具",
            "有哪些工具",
            "有哪些 tool",
            "有哪些 ai tool",
            "有哪些可调用工具",
            "当前注册了哪些工具",
            "注册了哪些工具",
            "工具列表",
            "工具清单",
            "是不是工具",
            "是工具吗",
            "这个工具吗",
            "这个 tool 吗",
            "有没有",
            "项目中有",
            "项目里有"
    };

    private static final String[] PROJECT_TOOL_KNOWN_NAMES = { // 当前项目已知 AI Tool 名称，用于工具追问和存在性判断。
            "listProjectTree",
            "searchCode",
            "analyzeCode",
            "readProjectFile",
            "readFile",
            "ragSearch",
            "summarizeArticle"
    };

    private static final String[] PROJECT_MODULE_PATH_KEYWORDS = { // 强制 searchCode 时可识别的项目模块范围。
            "Tech-Brain-Agent",
            "Tech-Brain-Entity",
            "Teach-Brain-Entity",
            "Tech-Brain-Notes",
            "Tech-Brain-Tool",
            "Tech-Brain-Common",
            "Tech-Brain-AOP",
            "Tech-Bain-Login"
    };

    private static final String[] READ_PROJECT_FILE_INTENT_KEYWORDS = { // 用户要求读取项目 workspace 源码文件时的强制路由关键词。
            "读取",
            "打开",
            "查看",
            "看一下",
            "查看完整代码",
            "完整文件",
            "看完整",
            "展开这个文件",
            "读一下这个文件",
            "打开这个路径",
            "读取这个路径",
            "看一下这个类完整代码",
            "看一下这个文件完整代码",
            "完整代码",
            "全部代码",
            "完整内容",
            "给我代码",
            "给我完整代码",
            "输出完整代码",
            "读取完整代码",
            "展开文件",
            "查看完整源码",
            "读取源码"
    };

    private static final String[] READ_PROJECT_FILE_ACTION_KEYWORDS = { // 用户表达获取、展示或打开项目源码内容时的动作词。
            "给我",
            "发我",
            "输出",
            "读取",
            "打开",
            "查看",
            "看一下",
            "看下",
            "展示",
            "贴一下",
            "提供",
            "拿出来"
    };

    private static final String[] READ_PROJECT_FILE_CONTENT_KEYWORDS = { // 用户表达要代码、源码、文件或内容时的内容词。
            "代码",
            "源码",
            "源代码",
            "文件",
            "内容",
            "类代码",
            "完整代码",
            "全部代码",
            "完整源码",
            "完整文件"
    };

    private static final String[] READ_PROJECT_FILE_ANALYZE_KEYWORDS = { // 用户要求分析项目文件内容时的动作词，命中后走 SUMMARY 而不是 FULL。
            "分析",
            "解释",
            "总结",
            "梳理",
            "说明",
            "这个类做什么",
            "这个工具实现逻辑"
    };

    private static final String[] PROJECT_CODE_SEARCH_ONLY_KEYWORDS = { // 纯定位、搜索类问题必须留给 searchCode。
            "在哪里",
            "在哪",
            "哪个文件",
            "哪个类",
            "哪个方法",
            "搜索",
            "查找",
            "找一下",
            "相关代码",
            "代码在哪",
            "方法在哪",
            "类在哪",
            "接口在哪",
            "定位"
    };

    private static final String[] READ_PROJECT_FILE_FULL_KEYWORDS = { // 用户明确要求完整代码时的判断词。
            "完整代码",
            "全部代码",
            "完整文件",
            "完整内容",
            "给我代码",
            "给我完整代码",
            "输出完整代码",
            "读取完整代码",
            "查看完整代码",
            "完整源码",
            "全部源码"
    };

    private static final String[] ANALYZE_CODE_INTENT_KEYWORDS = { // 用户要求分析单个源码文件结构时的强制路由关键词。
            "分析",
            "分析这个类",
            "分析这个文件",
            "分析这个工具",
            "分析代码结构",
            "代码结构",
            "类结构",
            "文件结构",
            "有哪些方法",
            "方法列表",
            "字段列表",
            "有哪些字段",
            "有哪些注解",
            "接口列表",
            "有哪些接口",
            "controller 有哪些接口",
            "这个 controller 有哪些接口",
            "这个 tool 的工具名是什么",
            "toolname 是什么",
            "工具名是什么",
            "这个工具有哪些方法",
            "这个类有哪些方法",
            "这个文件有哪些方法",
            "analyze",
            "structure",
            "method list",
            "methods",
            "fields",
            "annotations",
            "endpoints",
            "toolname"
    };

    private static final String[] ANALYZE_CALL_CHAIN_INTENT_KEYWORDS = { // 用户要求分析调用链/调用关系/依赖对象时的强制路由关键词。
            "调用链",
            "调用关系",
            "调用了哪些",
            "依赖了哪些",
            "这个方法调用了什么",
            "这个类调用了什么",
            "这个类依赖哪些",
            "这个文件依赖哪些",
            "方法链路",
            "代码链路",
            "内部调用",
            "外部调用",
            "service 调用",
            "mapper 调用",
            "repository 调用",
            "调用了哪些方法",
            "调用了哪些对象",
            "调用了哪些 service",
            "依赖哪些 service",
            "调用关系是什么",
            "工具调用链",
            "call chain",
            "callchain"
    };

    private static final String[] TOOL_SERVICE_CHAIN_INTENT_KEYWORDS = { // 用户明确要求分析 AI Tool 到业务组件调用链时的强制路由短语。
            "tool 到 service",
            "tool到service",
            "工具调用链",
            "工具链路",
            "tool 调用了哪些 service",
            "tool调用了哪些service",
            "这个 tool 调用了哪些 service",
            "这个工具调用了哪些 service",
            "这个工具调用了哪些业务类",
            "这个工具依赖哪些组件",
            "这个 tool 背后链路",
            "ai tool 调用链",
            "tool execute 调用链",
            "工具 execute 调用链",
            "execute 调用链",
            "execute 调用了什么",
            "执行链路",
            "背后调用了什么",
            "背后调用了哪些组件",
            "背后链路",
            "调用了哪些业务类",
            "依赖哪些安全组件",
            "依赖哪些组件",
            "最终有没有调用数据库"
    };

    private static final String[] TOOL_SERVICE_CHAIN_REFERENCE_KEYWORDS = { // Tool 专项追问中的工具指代词，用来识别“这个工具/它/execute”等 follow-up。
            "工具",
            "tool",
            "ai tool",
            "这个工具",
            "当前工具",
            "这个 tool",
            "它",
            "execute",
            "工具链路",
            "工具调用链"
    };

    private static final String[] TOOL_SERVICE_CHAIN_COMPONENT_INTENT_KEYWORDS = { // Tool 专项追问中的 Service、组件和业务调用语义。
            "调用了哪些 service",
            "调用哪些 service",
            "调用了什么 service",
            "依赖哪些 service",
            "依赖哪些组件",
            "调用了哪些组件",
            "调用哪些组件",
            "背后调用了什么",
            "背后调用了哪些",
            "背后链路",
            "到 service",
            "业务 service",
            "业务类",
            "guard",
            "registry",
            "mapper",
            "repository",
            "安全组件",
            "组件",
            "数据库",
            "execute 链路",
            "execute 调用链",
            "执行链路"
    };

    private static final String[] TOOL_SERVICE_CHAIN_ORDINARY_CALL_CHAIN_KEYWORDS = { // 用户明确说“普通调用链”时保留给 analyzeCode(CALL_CHAIN)，避免 Tool 专项误抢。
            "普通调用链",
            "普通调用关系",
            "一般调用链"
    };

    private static final String[] CODE_EXPLANATION_INTENT_KEYWORDS = { // P5.6 代码说明/解释/面试话术统一路由到 analyzeCode(EXPLANATION)。
            "说明",
            "解释",
            "讲一下",
            "介绍一下",
            "它是干嘛的",
            "这个类是干嘛的",
            "这个文件是干嘛的",
            "这个方法做了什么",
            "这个接口流程",
            "这个 tool 怎么工作",
            "这个工具怎么工作",
            "这个模块怎么讲",
            "代码说明",
            "流程说明",
            "生成代码说明",
            "面试怎么讲",
            "面试怎么说",
            "面试话术",
            "给我一段说明",
            "这个链路怎么讲",
            "帮我解释",
            "帮我说明",
            "怎么工作的",
            "怎么工作",
            "怎么讲"
    };

    private static final String[] CODE_EXPLANATION_INTERVIEW_KEYWORDS = { // 面试风格说明关键词。
            "面试",
            "话术",
            "怎么讲",
            "怎么说",
            "项目经历",
            "简历"
    };

    private static final String[] CODE_EXPLANATION_BEGINNER_KEYWORDS = { // 新手/通俗风格说明关键词。
            "通俗",
            "小白",
            "新手",
            "初学",
            "简单讲",
            "白话"
    };

    private static final String[] CODE_EXPLANATION_BRIEF_KEYWORDS = { // 简要说明关键词。
            "简要",
            "简单",
            "一句话",
            "概括",
            "摘要"
    };

    private static final String[] CODE_EXPLANATION_DETAILED_KEYWORDS = { // 详细说明关键词。
            "详细",
            "深入",
            "完整",
            "展开",
            "细一点"
    };

    private static final String[] SSE_EVENT_CHAIN_INTENT_KEYWORDS = { // 用户要求分析前后端 SSE 事件链路时的强制路由命中词。
            "sse",
            "流式",
            "流式响应",
            "流式输出",
            "事件流",
            "事件链路",
            "前后端事件",
            "前后端 sse",
            "sse 链路",
            "前端到后端",
            "后端到前端",
            "eventsource",
            "onmessage",
            "addeventlistener",
            "readablestream",
            "reader.read",
            "textdecoder",
            "summary_result",
            "tool_call",
            "tool_result",
            "done 事件",
            "error 事件",
            "message 事件",
            "怎么流式返回",
            "前端哪里接收",
            "前端哪里调用",
            "后端哪里发送",
            "前端在哪里处理",
            "前端在哪里接收",
            "事件前端",
            "流式链路",
            "事件链"
    };

    private static final String[] SSE_STRONG_SIGNAL_KEYWORDS = { // 强 SSE 信号：出现这些词即视为前后端流式链路问题。
            "sse",
            "流式",
            "事件流",
            "事件链路",
            "事件链",
            "前后端",
            "前端到后端",
            "后端到前端",
            "流式链路"
    };

    private static final String[] SSE_FRONTEND_KEYWORDS = { // 前端 SSE 关键词，命中后作为 frontendKeyword 传入工具。
            "EventSource",
            "fetch",
            "onmessage",
            "addEventListener",
            "TextDecoder",
            "ReadableStream",
            "reader.read",
            "getReader"
    };

    private static final String[] SSE_EVENT_NAME_KEYWORDS = { // 已知 SSE 事件名，命中后作为 eventName 传入工具。
            "summary_result",
            "tool_call",
            "tool_result",
            "done",
            "error",
            "message"
    };

    private static final Pattern SSE_EVENT_WITH_WORD_PATTERN = Pattern.compile(
            "(summary_result|tool_call|tool_result|done|error|message)\\s*事件"); // 识别“done 事件 / summary_result 事件”形态的事件名。

    private static final Pattern CALL_CHAIN_METHOD_NAME_PATTERN = Pattern.compile(
            "(?:分析\\s*)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*方法(?:调用链|调用关系|调用了哪些|调用了什么|依赖|链路|内部调用|外部调用)?"); // 从“分析 execute 方法调用链”中提取方法名。

    private static final Pattern CALL_CHAIN_LOWER_METHOD_PATTERN = Pattern.compile(
            "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:方法)?\\s*(?:调用了什么|调用了哪些|调用链|调用关系|内部调用|外部调用|依赖哪些|依赖了哪些)"); // 识别 streamPlainText 调用了什么 这类小写开头方法名。

    private static final Set<String> CALL_CHAIN_METHOD_STOP_WORDS = Set.of( // 调用链方法名提取需要排除的停用词。
            "call", "chain", "callchain", "service", "mapper", "repository", "tool",
            "this", "new", "return", "method"); // 这些词不是真正的入口方法名。

    private static final String[] CONTROLLER_SERVICE_CHAIN_INTENT_KEYWORDS = { // 用户要求分析 Controller→Service 接口链路时的强制路由关键词。
            "controller 到 service",
            "controller到service",
            "到 service 的调用链",
            "到service的调用链",
            "接口调用链",
            "接口链路",
            "接口后面调用",
            "接口后面调用了哪些",
            "接口调用了哪些 service",
            "接口调用了哪些service",
            "接口调用了哪些",
            "controller 调用了哪些 service",
            "controller调用了哪些service",
            "controller 调用了哪些",
            "这个接口调用了哪些",
            "接口背后",
            "接口背后的链路",
            "后端接口链路",
            "接口路径",
            "接口的链路",
            "controller 接口链路",
            "controller 链路"
    };

    // 接口路径提取已统一收敛到 ProjectCodeTargetResolver.extractEndpoint，这里不再重复维护 endpoint 正则。

    private static final Pattern CONTROLLER_METHOD_NAME_PATTERN = Pattern.compile(
            "([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:接口|方法)\\s*(?:链路|调用链|调用了哪些|调用了什么|后面调用|背后)?"); // 从“chat 接口链路”中提取 Controller 方法名。

    private static final String[] PROJECT_FILE_FOLLOW_UP_KEYWORDS = { // 用户围绕最近项目源码文件继续追问时的指代词。
            "给我代码",
            "给我完整代码",
            "完整代码",
            "全部代码",
            "输出代码",
            "源码",
            "完整源码",
            "继续",
            "继续分析",
            "继续看",
            "继续优化",
            "优化一下",
            "重构一下",
            "改一下",
            "这个文件",
            "这个类",
            "这个工具",
            "这个代码",
            "这段代码",
            "刚才那个文件",
            "刚才那个类",
            "刚才那个工具",
            "刚才的代码",
            "它",
            "这里",
            "这个方法",
            "这个接口",
            "有什么问题",
            "哪里可以优化",
            "还有什么问题",
            "讲一下这个类",
            "分析这个工具"
    };

    private static final String[] UPLOADED_FILE_EXPLICIT_KEYWORDS = { // 明确指向用户上传文件或聊天附件时，projectFileFocus 必须避让。
            "上传",
            "附件",
            "上传的文件",
            "刚上传",
            "刚发的文件",
            "我发的文件",
            "我给你的文件",
            "fileid",
            "fileId"
    };

    private static final String[] PROJECT_ROOT_READABLE_FILES = { // workspace 根目录允许通过文件名直接读取的项目说明和构建文件。
            "pom.xml",
            "README.md",
            "README.zh-CN.md",
            "README.en-US.md",
            "HELP.md",
            "docker-compose.yml",
            ".env.example"
    };

    private static final Pattern CODE_IDENTIFIER_PATTERN = Pattern.compile("(@[A-Za-z][A-Za-z0-9_]*|/[A-Za-z0-9_./{}-]+|[A-Za-z_$][A-Za-z0-9_$]*(?:\\(\\))?)"); // 从用户消息中提取类名、方法名、注解或接口路径。

    private static final Pattern PROJECT_WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("(?i)([A-Za-z]:[/\\\\][^\\s，。；;]+)"); // 识别Windows绝对路径，后续交给工具安全拒绝。

    private static final Pattern PROJECT_UNIX_ABSOLUTE_PATH_PATTERN = Pattern.compile("(?i)(?:^|\\s)(/[^\\s，。；;]+)"); // 识别Unix绝对路径，后续交给工具安全拒绝。

    private static final Pattern PROJECT_RELATIVE_FILE_PATH_PATTERN = Pattern.compile("(?i)([A-Za-z0-9_.@()$-]+(?:[/\\\\][A-Za-z0-9_.@()$-]+)+\\.[A-Za-z0-9.]+)"); // 识别带扩展名的 workspace 相对文件路径。

    private static final Pattern PROJECT_RELATIVE_PATH_TOKEN_PATTERN = Pattern.compile("(?i)([A-Za-z0-9_.@()$-]+(?:[/\\\\][A-Za-z0-9_.@()$-]+)+)"); // 识别 .git/config 这类无扩展名相对路径，让工具统一拒绝敏感路径。

    private static final Pattern PROJECT_FILE_NAME_PATTERN = Pattern.compile("(?i)([A-Za-z0-9_.@()$-]+\\.[A-Za-z0-9.]+)"); // 识别 SearchCodeTool.java、pom.xml、.env.example、app.jar 这类文件名。

    private static final Pattern PROJECT_SENSITIVE_FILENAME_PATTERN = Pattern.compile("(?i)(\\.env(?:\\.local|\\.production)?|id_rsa(?:\\.pub)?|known_hosts|authorized_keys)"); // 识别敏感文件名并交给工具返回拒绝原因。

    private static final Pattern[] PROJECT_TREE_PATH_PATTERNS = { // 从自然语言中轻量提取 workspace 相对目录路径。
            Pattern.compile("(?i)path\\s*[=:：]\\s*([A-Za-z0-9_./\\\\-]+)"),
            Pattern.compile("(?:列一下|列出|查看|看看|看一下)\\s*([A-Za-z0-9_./\\\\-]+)\\s*(?:项目|目录|目录树|文件树|项目结构)"),
            Pattern.compile("([A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+)"),
            Pattern.compile("([A-Za-z0-9_.-]+)\\s*项目(?:的)?(?:目录树|目录结构|文件树|结构|有哪些文件|有哪些模块)")
    };

    private static final String[] READ_FILE_ACTION_KEYWORDS = { // 用户要求读取或分析附件正文时的动作词。
            "读取文件",
            "读这个文件",
            "读取这个文件",
            "读一下这个文件",
            "读取这个",
            "打开这个文件",
            "查看这个文件",
            "看这个文件",
            "看看这个文件",
            "看下这个文件",
            "分析这个文件",
            "分析这些文件",
            "总结这个文件",
            "总结这些文件",
            "检查这个文件",
            "解释这个文件",
            "读取附件",
            "分析附件",
            "总结附件",
            "查看附件",
            "看看附件",
            "读附件",
            "读取内容",
            "分析内容",
            "总结内容",
            "文件内容",
            "分析代码",
            "看看代码",
            "看下代码",
            "检查代码",
            "解释代码",
            "总结代码",
            "读取代码",
            "读代码"
    };

    private static final String[] FILE_LIST_QUERY_KEYWORDS = { // 只询问附件列表时不应触发readFile。
            "上传了哪些文件",
            "有哪些文件",
            "哪些文件",
            "几个文件",
            "给了几个文件",
            "文件列表",
            "附件列表",
            "上传列表",
            "最近上传的文件有哪些",
            "我上传的文件有哪些"
    };

    private static final String[] READ_FILE_SUPPORTED_EXT_KEYWORDS = { // 文本/代码文件名后缀命中时也可触发readFile意图。
            ".txt",
            ".md",
            ".java",
            ".vue",
            ".sql",
            ".py",
            ".js",
            ".ts",
            ".json",
            ".xml",
            ".html",
            ".css",
            ".yml",
            ".yaml",
            ".properties"
    };

    private static final String[] FILE_REFERENCE_FOLLOW_UP_KEYWORDS = { // 用户用模糊指代继续追问当前文件焦点时的识别词。
            "这个文件",
            "这个代码",
            "这段代码",
            "这份代码",
            "这个类",
            "这个接口",
            "这个脚本",
            "它",
            "刚才那个",
            "刚发的",
            "刚上传的",
            "继续",
            "继续分析",
            "继续优化",
            "继续看",
            "继续检查",
            "现在呢",
            "那现在呢",
            "基于刚才",
            "按刚才那个",
            "读一下",
            "读取它",
            "分析一下",
            "看一下",
            "检查一下",
            "优化一下",
            "重构一下",
            "改一下",
            "再改",
            "再发表",
            "还有什么问题",
            "解释一下",
            "总结一下",
            "这个呢"
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

    private static final Pattern[] FILE_ID_PATTERNS = { // 从用户输入中提取显式fileId，优先用于多附件目标选择。
            Pattern.compile("(?i)fileId\\s*[=:：]?\\s*(\\d+)"),
            Pattern.compile("文件\\s*ID\\s*[=:：]?\\s*(\\d+)"),
            Pattern.compile("文件\\s*id\\s*[=:：]?\\s*(\\d+)")
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

    private enum RagSearchStatus { // 本轮知识库检索状态，只由是否实际执行ragSearch及其结果决定。
        NOT_SEARCHED, // 本轮没有调用ragSearch，禁止输出知识库检索结论。
        SEARCHED_FOUND, // 本轮调用ragSearch且工具结果有内容，可以说根据知识库。
        SEARCHED_EMPTY // 本轮调用ragSearch但工具结果为空，只能说本次检索未找到直接相关内容。
    }

    private final DeepSeekClient deepSeekClient; // 负责发送DeepSeek HTTP请求。

    private final ToolRegistry toolRegistry; // 负责提供tools定义并执行工具分发。

    private final DeepSeekProperties deepSeekProperties; // 负责提供model/baseUrl等运行配置。

    private final ConversationFocusService conversationFocusService; // 保存 readProjectFile 成功后的项目源码文件焦点。
    private final ProjectCodeTargetResolver projectCodeTargetResolver; // 项目代码统一目标解析器，所有项目代码工具路由共享同一套目标解析策略。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 公共编排器自带JSON工具，避免依赖业务模块额外声明ObjectMapper Bean。

    public ToolCallingChatServiceImpl(DeepSeekClient deepSeekClient,
                                      ToolRegistry toolRegistry,
                                      DeepSeekProperties deepSeekProperties,
                                      ConversationFocusService conversationFocusService,
                                      ProjectCodeTargetResolver projectCodeTargetResolver) { // 构造器注入公共编排所需依赖。
        this.deepSeekClient = deepSeekClient; // 保存DeepSeek客户端。
        this.toolRegistry = toolRegistry; // 保存工具注册中心。
        this.deepSeekProperties = deepSeekProperties; // 保存DeepSeek配置。
        this.conversationFocusService = conversationFocusService; // 保存会话焦点服务，用于项目文件焦点读写。
        this.projectCodeTargetResolver = projectCodeTargetResolver; // 保存统一目标解析器，路由阶段判断明确目标与 focus 优先级。
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
            log.debug("[ToolChatStream] follow-up question: {}", followUpQuestion); // 追问识别属于细节排查日志。
            if (followUpQuestion) {
                log.debug("[ToolChatStream] follow-up will use history context"); // 追问场景会在最终回答阶段结合historyMessages理解指代。
            }
            String userMessage = validateAndGetMessage(currentMessage); // 校验并规整当前轮用户输入。
            if (requestContext != null && (requestContext.getCurrentMessage() == null || requestContext.getCurrentMessage().isBlank())) { // 旧调用方未写入当前消息时兜底补齐。
                requestContext.setCurrentMessage(userMessage); // currentMessage只给工具解析focus，不拼接历史或长期记忆。
            }
            String normalizedMemorySummary = normalizeMemorySummary(memorySummary); // 标准化长期记忆，但不参与工具路由判断。
            List<ToolChatHistoryMessage> normalizedHistoryMessages = normalizeHistoryMessages(historyMessages); // 规范化历史，但不参与工具路由判断。
            log.debug("[ToolChatStream] current user message: {}", previewContent(userMessage)); // 用户原文只打印短preview，避免INFO泄露。
            logMemorySummary(normalizedMemorySummary); // 打印长期记忆启用状态、长度和短preview。
            log.debug("[ToolChatStream] history enabled: {}", !normalizedHistoryMessages.isEmpty()); // 历史启用状态降级为DEBUG。
            log.debug("[ToolChatStream] history message count: {}", normalizedHistoryMessages.size()); // 历史数量降级为DEBUG。
            logHistoryMessages(normalizedHistoryMessages); // 打印历史role和短preview，避免日志爆炸。
            log.debug("[ToolChatStream] tool boundary prompt: enabled"); // prompt边界状态属于低频排查信息。
            boolean hasAttachedFiles = hasAttachedFiles(requestContext); // 本轮是否携带用户文件附件元信息。
            log.debug("[ToolChatStream] attached files enabled: {}", hasAttachedFiles); // 附件状态只在DEBUG打印。
            boolean attachmentContext = hasAttachmentContext(requestContext, normalizedHistoryMessages); // 本轮或历史是否有附件元信息上下文。
            boolean fileAttachmentIntent = isFileAttachmentIntent(userMessage); // 用户是否在问当前附件/上传文件。
            boolean fileReferenceFollowUp = isFileReferenceFollowUp(userMessage); // 用户是否在用“这个文件/现在呢/继续分析”等模糊指代。
            List<ChatAttachedFileContext> resolvedFileFocus = resolveFileFocus(userMessage, requestContext); // 解析本轮或会话最近附件焦点。
            boolean explicitRagIntent = isExplicitRagIntent(userMessage); // 用户是否明确要求查知识库/笔记/文章。
            boolean blockRagForAttachmentIntent = shouldBlockRagForAttachmentIntent(userMessage, requestContext, normalizedHistoryMessages); // 附件问题阻断RAG。
            log.info("[ToolChatStream] file attachment intent: {}, explicitRag: {}, hasAttachment: {}",
                    fileAttachmentIntent, explicitRagIntent, attachmentContext); // 只记录布尔路由状态，不打印附件路径。
            log.info("[ToolChatStream] file reference follow-up: {}", fileReferenceFollowUp); // 打印文件指代追问判断结果。
            log.info("[ToolChatStream] resolved file focus count: {}", resolvedFileFocus.size()); // 打印解析出的文件焦点数量。
            logResolvedFileFocus(resolvedFileFocus); // 打印文件焦点ID和文件名，不打印路径。
            boolean projectToolInventoryIntent = isProjectToolInventoryIntent(userMessage); // 判断是否询问项目AI Tool清单或某工具是否存在。
            log.info("[ToolChatStream] project tool inventory intent: {}", projectToolInventoryIntent); // 打印项目工具识别意图。
            boolean forceSummarizeArticle = !hasAttachedFiles && shouldForceSummarizeArticle(userMessage); // 携带用户文件时不强制走文章总结工具，避免把文件误当文章。
            log.info("[ToolChatStream] force summarizeArticle: {}", forceSummarizeArticle); // 打印强制总结路由判断结果。
            ProjectCodeTargetResolution projectCodeTarget = projectCodeTargetResolver.resolve(userMessage,
                    resolveReadProjectFilePathFromMessage(userMessage),
                    hasRecentProjectTarget(requestContext),
                    hasProjectFileFocus(requestContext),
                    isControllerFocus(requestContext)); // 统一目标解析：明确目标 > 全项目扫描 > recentProjectTarget > projectFileFocus。
            log.info("[ToolChatStream] project code target: {}", projectCodeTarget); // 打印统一目标解析结论，便于排查 focus 是否锁死明确目标。
            boolean forceReadProjectFile = !forceSummarizeArticle && shouldForceReadProjectFile(userMessage, requestContext); // 明确项目源码文件读取请求优先于searchCode。
            log.info("[ToolChatStream] force readProjectFile: {}", forceReadProjectFile); // 打印强制项目文件读取路由判断结果。
            boolean forceCodeExplanation = !forceSummarizeArticle && !forceReadProjectFile && shouldForceCodeExplanation(userMessage, requestContext); // 说明/解释/面试话术优先于普通分析，但 readProjectFile 仍最高。
            log.info("[ToolChatStream] force analyzeCode EXPLANATION: {}", forceCodeExplanation); // 打印强制说明生成路由判断结果。
            boolean forceSseEventChain = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && shouldForceAnalyzeSseEventChain(userMessage, requestContext); // 前后端 SSE 事件链路优先于 Controller→Service 等链路。
            log.info("[ToolChatStream] force analyzeCode SSE_EVENT_CHAIN: {}", forceSseEventChain); // 打印强制 SSE 事件链路路由判断结果。
            boolean forceControllerServiceChain = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && shouldForceAnalyzeControllerServiceChain(userMessage, requestContext); // Controller→Service 接口链路优先于普通调用链。
            log.info("[ToolChatStream] force analyzeCode CONTROLLER_SERVICE: {}", forceControllerServiceChain); // 打印强制 Controller→Service 链路路由判断结果。
            boolean forceToolServiceChain = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && shouldForceAnalyzeToolServiceChain(userMessage, requestContext); // Tool→业务组件链路优先于普通调用链。
            log.info("[ToolChatStream] force analyzeCode TOOL_SERVICE: {}", forceToolServiceChain); // 打印强制 Tool→业务组件链路路由判断结果。
            boolean forceAnalyzeCallChain = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && shouldForceAnalyzeCallChain(userMessage, requestContext); // 调用链/调用关系/依赖分析优先于单文件结构分析。
            log.info("[ToolChatStream] force analyzeCode CALL_CHAIN: {}", forceAnalyzeCallChain); // 打印强制调用链分析路由判断结果。
            boolean forceAnalyzeCode = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && !forceAnalyzeCallChain && shouldForceAnalyzeCode(userMessage, requestContext); // 结构/方法/字段/接口分析优先于项目文件焦点读取。
            log.info("[ToolChatStream] force analyzeCode: {}", forceAnalyzeCode); // 打印强制代码结构分析路由判断结果。
            boolean forceProjectFileFocus = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && !forceAnalyzeCallChain && !forceAnalyzeCode && shouldUseProjectFileFocus(userMessage, requestContext); // 项目源码文件追问优先使用最近 readProjectFile 焦点。
            log.info("[ToolChatStream] project file follow-up intent: {}", isProjectFileFollowUpIntent(userMessage)); // 打印项目文件追问判断结果。
            log.info("[ToolChatStream] force projectFileFocus: {}", forceProjectFileFocus); // 打印项目源码文件焦点路由判断结果。
            boolean promptProjectFileFocusMissing = !forceSummarizeArticle
                    && !forceReadProjectFile
                    && !forceCodeExplanation
                    && !forceSseEventChain
                    && !forceControllerServiceChain
                    && !forceToolServiceChain
                    && !forceAnalyzeCallChain
                    && !forceAnalyzeCode
                    && !forceProjectFileFocus
                    && shouldPromptProjectFileFocusMissing(userMessage, requestContext); // 没有项目文件焦点时不乱猜文件。
            boolean forceReadFile = !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && !forceAnalyzeCallChain && !forceAnalyzeCode && !forceProjectFileFocus && !promptProjectFileFocusMissing && shouldForceReadFile(userMessage, requestContext); // 明确要求读取/分析当前附件时由后端强制readFile，避免模型漏调工具。
            log.info("[ToolChatStream] force readFile: {}", forceReadFile); // 打印强制readFile路由判断结果。
            boolean forceListProjectTree = !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && !forceAnalyzeCallChain && !forceAnalyzeCode && !forceProjectFileFocus && !promptProjectFileFocusMissing && !forceReadFile && shouldForceListProjectTree(userMessage); // 用户要求查看项目目录结构时强制执行listProjectTree。
            log.info("[ToolChatStream] force listProjectTree: {}", forceListProjectTree); // 打印强制目录树路由判断结果。
            boolean forceSearchCode = !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && !forceAnalyzeCallChain && !forceAnalyzeCode && !forceProjectFileFocus && !promptProjectFileFocusMissing && !forceReadFile && !forceListProjectTree && shouldForceSearchCode(userMessage); // 用户要求定位类、方法、注解或关键词位置时强制执行searchCode。
            log.info("[ToolChatStream] force searchCode: {}", forceSearchCode); // 打印强制代码搜索路由判断结果。
            boolean forceRagSearch = !forceSummarizeArticle && !forceReadProjectFile && !forceCodeExplanation && !forceSseEventChain && !forceControllerServiceChain && !forceToolServiceChain && !forceAnalyzeCallChain && !forceAnalyzeCode && !forceProjectFileFocus && !promptProjectFileFocusMissing && !forceReadFile && !forceListProjectTree && !forceSearchCode && shouldForceRagSearch(userMessage); // 只有没有更明确工具意图时才强制RAG。
            if (forceRagSearch && blockRagForAttachmentIntent) { // 附件问题即使命中RAG关键词也不能误入向量库，除非用户明确要求知识库。
                forceRagSearch = false; // 阻断强制RAG。
                log.info("[ToolChatStream] block ragSearch for attachment intent, conversationId: {}, userId: {}",
                        requestContext == null ? null : requestContext.getConversationId(),
                        requestContext == null ? null : requestContext.getUserId()); // 只打印归属ID，不打印附件内容。
            }
            log.info("[ToolChatStream] force ragSearch: {}", forceRagSearch); // 打印流式接口的强制RAG路由结果。
            String answerMode; // 记录本轮回答模式，不改变路由逻辑。
            if (forceSummarizeArticle) { // 总结文章强制路由。
                answerMode = "force-summarizeArticle"; // 标记总结模式。
            } else if (forceReadProjectFile) { // 明确项目文件读取。
                answerMode = "force-readProjectFile"; // 标记项目文件读取模式。
            } else if (forceCodeExplanation) { // P5.6 代码说明生成。
                answerMode = "force-analyzeCode-EXPLANATION"; // 标记代码说明模式。
            } else if (forceSseEventChain) { // 前后端 SSE 事件链路分析。
                answerMode = "force-analyzeCode-SSE_EVENT_CHAIN"; // 标记 SSE 事件链路分析模式。
            } else if (forceControllerServiceChain) { // Controller→Service 接口链路分析。
                answerMode = "force-analyzeCode-CONTROLLER_SERVICE"; // 标记 Controller→Service 链路分析模式。
            } else if (forceToolServiceChain) { // AI Tool→业务组件链路分析。
                answerMode = "force-analyzeCode-TOOL_SERVICE"; // 标记 Tool→业务组件链路分析模式。
            } else if (forceAnalyzeCallChain) { // 项目文件调用链分析。
                answerMode = "force-analyzeCode-CALL_CHAIN"; // 标记调用链分析模式。
            } else if (forceAnalyzeCode) { // 项目文件结构分析。
                answerMode = "force-analyzeCode"; // 标记代码结构分析模式。
            } else if (forceProjectFileFocus) { // 项目文件焦点追问。
                answerMode = "force-projectFileFocus"; // 标记项目文件焦点模式。
            } else if (promptProjectFileFocusMissing) { // 缺少项目文件焦点。
                answerMode = "projectFileFocus-missing"; // 标记缺焦点提示模式。
            } else if (forceReadFile) { // 上传文件读取。
                answerMode = "force-readFile"; // 标记上传文件读取模式。
            } else if (forceListProjectTree) { // 项目目录树。
                answerMode = "force-listProjectTree"; // 标记目录树模式。
            } else if (forceSearchCode) { // 项目代码搜索。
                answerMode = "force-searchCode"; // 标记代码搜索模式。
            } else if (forceRagSearch) { // 知识库检索。
                answerMode = "force-ragSearch"; // 标记RAG模式。
            } else {
                answerMode = "model-routing"; // 默认交给模型tool_call判断。
            }
            log.info("[ToolChatStream] answer mode: {}", answerMode); // 保留关键链路日志，便于面试展示和线上排查。
            if (forceSummarizeArticle) { // 用户明确要求总结文章/笔记时，跳过第一次模型tool_call判断。
                streamWithForcedSummarizeArticle(userMessage, requestContext, callback); // 直接执行summarizeArticle并只输出chatMessage。
                return; // 强制总结路径结束后不再走其它工具链路。
            }
            if (forceReadProjectFile) { // 用户明确要求读取项目源码文件时，跳过searchCode和模型tool_call判断。
                streamWithForcedReadProjectFile(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 直接执行readProjectFile并进入流式回答。
                return; // 强制readProjectFile路径结束后不再走searchCode、目录树、RAG、readFile或模型tool_call链路。
            }
            if (forceCodeExplanation) { // 用户明确要求说明/解释项目代码时，统一走 analyzeCode(EXPLANATION)。
                streamWithForcedAnalyzeCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified, CodeAnalysisType.EXPLANATION); // 统一执行 analyzeCode，并通过 CodeExplanationGenerator 生成说明。
                return; // 强制代码说明路径结束后不再走其它分析、目录树、搜索、RAG、readFile或模型tool_call链路。
            }
            if (forceSseEventChain) { // 用户明确要求分析前后端 SSE 事件链路时，跳过 Controller→Service 等链路和模型tool_call判断。
                streamWithForcedAnalyzeCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified, CodeAnalysisType.SSE_EVENT_CHAIN); // 统一执行 analyzeCode，并通过 analysisType 分发到 SSE 内部分析器。
                return; // 强制 SSE 事件链路路径结束后不再走其它链路、目录树、搜索、RAG、readFile或模型tool_call链路。
            }
            if (forceControllerServiceChain) { // 用户明确要求分析 Controller→Service 接口链路时，跳过普通调用链和模型tool_call判断。
                streamWithForcedAnalyzeCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified, CodeAnalysisType.CONTROLLER_SERVICE); // 统一执行 analyzeCode，并通过 analysisType 分发到 Controller→Service 内部分析器。
                return; // 强制 Controller→Service 链路路径结束后不再走调用链、结构分析、目录树、搜索、RAG、readFile或模型tool_call链路。
            }
            if (forceToolServiceChain) { // 用户明确要求分析 AI Tool→业务组件链路时，跳过普通调用链和模型tool_call判断。
                streamWithForcedAnalyzeCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified, CodeAnalysisType.TOOL_SERVICE); // 统一执行 analyzeCode，并通过 analysisType 分发到 Tool→Service 内部分析器。
                return; // 强制 Tool→业务组件链路路径结束后不再走普通调用链、结构分析、目录树、搜索、RAG、readFile或模型tool_call链路。
            }
            if (forceAnalyzeCallChain) { // 用户明确要求分析项目源码调用链时，跳过analyzeCode结构分析和模型tool_call判断。
                streamWithForcedAnalyzeCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified, CodeAnalysisType.CALL_CHAIN); // 统一执行 analyzeCode，并通过 analysisType 分发到普通调用链内部分析器。
                return; // 强制 analyzeCode(CALL_CHAIN) 路径结束后不再走结构分析、目录树、搜索、RAG、readFile或模型tool_call链路。
            }
            if (forceAnalyzeCode) { // 用户明确要求分析项目源码结构时，跳过readProjectFile全文输出和模型tool_call判断。
                streamWithForcedAnalyzeCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified, CodeAnalysisType.STRUCTURE); // 统一执行 analyzeCode，并通过 analysisType 分发到结构分析器。
                return; // 强制analyzeCode路径结束后不再走目录树、搜索、RAG、readFile或模型tool_call链路。
            }
            if (forceProjectFileFocus) { // 用户围绕最近项目源码文件追问时，使用 projectFileFocus 读取该文件。
                streamWithProjectFileFocus(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 通过 projectFileFocus 执行 readProjectFile。
                return; // 项目文件焦点路径结束后不再走其它工具链路。
            }
            if (promptProjectFileFocusMissing) { // 用户使用“给我代码/继续分析”等指代但当前没有项目文件焦点。
                callback.onToken(buildProjectFileFocusMissingPrompt()); // 明确要求用户指定类名、路径或先搜索。
                callback.onComplete(); // 结束本轮SSE。
                return; // 不调用searchCode或ragSearch乱猜文件。
            }
            if (forceReadFile) { // 用户明确要求读取、分析或总结本轮附件文本/代码文件时，跳过模型tool_call判断。
                streamWithForcedReadFile(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 直接执行readFile并进入DeepSeek流式回答。
                return; // 强制readFile路径结束后不再走RAG、总结或模型tool_call链路。
            }
            if (forceListProjectTree) { // 用户明确要求项目目录树时，跳过模型tool_call判断。
                streamWithForcedListProjectTree(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 直接执行listProjectTree并进入DeepSeek流式回答。
                return; // 强制listProjectTree路径结束后不再走RAG、readFile、总结或模型tool_call链路。
            }
            if (forceSearchCode) { // 用户明确要求搜索代码位置时，跳过模型tool_call判断。
                streamWithForcedSearchCode(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 直接执行searchCode并进入DeepSeek流式回答。
                return; // 强制searchCode路径结束后不再走目录树、RAG、readFile、总结或模型tool_call链路。
            }
            if (forceRagSearch) { // 用户明确要求基于知识库/笔记/资料回答时，跳过第一次模型tool_call判断。
                streamWithForcedRagSearch(userMessage, normalizedMemorySummary, normalizedHistoryMessages, requestContext, callback, streamErrorNotified); // 直接执行ragSearch并进入DeepSeek流式回答。
                return; // 强制RAG路径结束后不再走原有tool_call判断链路。
            }

            ObjectNode firstRequest = buildFirstRequest(userMessage, requestContext); // 第一次请求加入附件元信息约束，但不读取文件内容。
            log.debug("[ToolChatStream] before first DeepSeek call"); // DeepSeek调用细节降级为DEBUG。
            JsonNode firstResponse = deepSeekClient.chatCompletions(firstRequest); // 第一次非流式调用，获取可能的tool_call。
            log.debug("[ToolChatStream] after first DeepSeek call"); // DeepSeek调用细节降级为DEBUG。

            JsonNode firstMessage = readFirstMessage(firstResponse); // 读取第一次assistant message。
            JsonNode toolCalls = firstMessage.path("tool_calls"); // 读取工具调用数组。
            boolean hasToolCall = toolCalls.isArray() && !toolCalls.isEmpty(); // 判断是否包含tool_call。
            log.info("[ToolChatStream] has tool call: {}", hasToolCall); // 打印tool_call判断结果。

            if (!hasToolCall) { // 普通聊天不再一次性吐出第一次非流式结果，而是重新发起无tools的流式请求。
                log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 没有tool_call时明确本轮未检索知识库。
                log.info("[ToolChatStream] no-tool answer, knowledge base claim disabled"); // 标记no-tool回答禁止冒充知识库检索。
                streamNoToolAnswer(userMessage, normalizedMemorySummary, normalizedHistoryMessages,
                        followUpQuestion, requestContext, false, callback, streamErrorNotified); // 第二次请求加入长期记忆、历史和附件元信息，但不设置tools。
                return; // 无工具路径结束。
            }

            JsonNode toolCall = toolCalls.get(0); // 当前先处理第一个tool_call。
            String toolCallId = toolCall.path("id").asText(); // 第二次请求tool message必须带回该ID。
            String toolName = toolCall.path("function").path("name").asText(); // 读取模型选择的工具名。
            String toolArguments = toolCall.path("function").path("arguments").asText(); // 读取模型生成的arguments JSON字符串。
            log.info("[ToolChatStream] tool name: {}", toolName); // 打印工具名。
            log.debug("[ToolChatStream] tool call id: {}", toolCallId); // 工具调用ID属于排查信息。
            log.debug("[ToolChatStream] tool arguments: {}", previewContent(toolArguments)); // arguments可能较长，只在DEBUG打印短preview。
            if (RAG_SEARCH_TOOL_NAME.equals(toolName) && blockRagForAttachmentIntent) { // 模型误选ragSearch且当前问题明确指向附件。
                log.info("[ToolChatStream] block ragSearch for attachment intent, toolName: {}", toolName); // 记录被拦截的工具名。
                log.info("[ToolChatStream] skip ragSearch, answer with attachment context"); // 不执行工具，因此不会产生tool_call_log。
                streamNoToolAnswer(userMessage, normalizedMemorySummary, normalizedHistoryMessages,
                        followUpQuestion, requestContext, true, callback, streamErrorNotified); // 走无工具流式回答，基于附件元信息响应。
                return; // 阻断ragSearch后结束model tool_call路径。
            }

            JsonNode argumentsNode = parseToolArguments(toolArguments); // 解析arguments，交给具体AiTool执行。
            String toolResult = executeToolForStream(toolName, argumentsNode, requestContext, callback); // 通过ToolRegistry执行工具并输出流式专用日志。
            if (toolResult == null) { // 工具不存在时executeToolForStream已完成callback通知。
                return; // 未知工具不再进入第二次模型调用。
            }
            if (READ_PROJECT_FILE_TOOL_NAME.equals(toolName)) { // 模型主动调用 readProjectFile 成功后也要更新项目文件焦点。
                saveProjectFileFocusFromToolResult(toolResult, requestContext, resolveReadProjectFileModeFromArguments(argumentsNode)); // 保存相对路径和元信息，不保存文件内容。
            }
            if (SEARCH_CODE_TOOL_NAME.equals(toolName)) { // 模型主动调用 searchCode 且唯一命中后也要更新最近项目目标。
                saveRecentProjectTargetFromSearchCodeResult(userMessage, argumentsNode, toolResult, requestContext); // 保存相对路径和元信息，不保存文件内容。
            }
            if (ANALYZE_CODE_TOOL_NAME.equals(toolName)) { // 模型主动调用 analyzeCode 成功后也要更新项目文件焦点。
                saveProjectFileFocusFromCodeAnalysisResult(toolResult, requestContext); // 保存相对路径和元信息，不保存文件内容。
            }
            RagSearchStatus ragSearchStatus = resolveRagSearchStatus(toolName, toolResult); // 根据实际执行工具和结果更新本轮RAG状态。
            log.info("[ToolChatStream] rag search status: {}", ragSearchStatus); // 输出本轮RAG状态，避免no-tool冒充检索结果。
            if (isArticleSummaryResult(toolResult)) { // 成功的文章总结结果需要额外通知前端打开总结弹窗。
                log.info("[ToolChatStream] detected article summary result"); // 只记录检测到文章总结结果，不打印完整summary。
                log.info("[ToolChatStream] emit tool event: {}", SUMMARY_RESULT_EVENT_NAME); // 标记准备发送summary_result业务事件。
                callback.onToolEvent(SUMMARY_RESULT_EVENT_NAME, toolResult); // 透传完整工具JSON给上层SSE，聊天token仍只输出chatMessage。
            }
            String articleSummaryChatMessage = readArticleSummaryChatMessage(toolResult); // article_summary 工具结果只允许聊天输出短提示。
            if (articleSummaryChatMessage != null) { // 命中文章总结工具结果时不再进入第二次模型，避免 summary 全文进聊天气泡。
                log.info("[ToolChatStream] final chatMessage only"); // 标记文章总结结果已被直接处理。
                callback.onToken(articleSummaryChatMessage); // 聊天气泡只输出 chatMessage。
                callback.onComplete(); // 结束本轮流式输出。
                return; // 不再调用第二次 DeepSeek，避免模型复述完整 summary。
            }

            ObjectNode secondRequest = buildSecondRequest(userMessage, normalizedMemorySummary, normalizedHistoryMessages, firstMessage, toolCallId, toolResult, ragSearchStatus, requestContext); // 第二次请求加入长期记忆、历史、工具结果、RAG状态和附件元信息。
            streamSafeToolFinalAnswer(toolName, userMessage, toolArguments, toolResult,
                    secondRequest, callback, streamErrorNotified); // 工具结果最终回答统一走DSML泄漏检测和安全兜底。
        } catch (Exception e) { // 捕获第一次调用、工具执行、第二次流式调用中的异常。
            log.error("[ToolChatStream] error type: {}", e.getClass().getName()); // 打印异常类型。
            log.error("[ToolChatStream] error message: {}", e.getMessage()); // 打印异常消息。
            log.error("[ToolChatStream] request traceId: {}", requestContext == null ? null : requestContext.getTraceId()); // 模型调用失败时保留traceId便于排查。
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
        log.debug("[ToolChatStream] force ragSearch query: {}", previewContent(query)); // query只在DEBUG打印短preview。
        AiTool ragTool = toolRegistry.getTool(RAG_SEARCH_TOOL_NAME); // 从ToolRegistry获取RagSearchTool。
        if (ragTool == null) { // 没有注册ragSearch时不能继续伪造工具结果。
            callback.onToken("系统错误：ragSearch 工具未注册。"); // 通过SSE把明确错误返回前端。
            callback.onComplete(); // 结束本轮流式输出，避免前端一直等待。
            return; // 工具缺失时不再调用DeepSeek。
        }
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造工具入参JSON。
        arguments.put("query", query); // 写入ragSearch所需query字段。
        String toolResult = executeToolWithLog(ragTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_RAG_SEARCH); // 执行真实RAG检索并记录工具调用日志。
        RagSearchStatus ragSearchStatus = resolveRagSearchStatus(RAG_SEARCH_TOOL_NAME, toolResult); // 强制RAG执行后解析命中状态。
        log.info("[ToolChatStream] rag search status: {}", ragSearchStatus); // 输出强制RAG状态。
        log.debug("[ToolChatStream] force ragSearch result preview: {}", previewContent(toolResult)); // RAG结果可能较长，不在INFO打印。
        ObjectNode streamRequest = buildForcedRagAnswerRequest(userMessage, memorySummary, historyMessages, toolResult, ragSearchStatus, requestContext); // 将长期记忆、历史、附件元信息和RAG状态作为辅助上下文，工具结果仍最高优先级。
        streamSafeToolFinalAnswer(RAG_SEARCH_TOOL_NAME, userMessage, serializeArguments(arguments), toolResult,
                streamRequest, callback, streamErrorNotified); // 工具结果最终回答统一走DSML泄漏检测和安全兜底。
    }

    private void streamWithForcedSearchCode(String userMessage,
                                            String memorySummary,
                                            List<ToolChatHistoryMessage> historyMessages,
                                            ToolCallingRequestContext requestContext,
                                            ToolCallingStreamCallback callback,
                                            boolean[] streamErrorNotified) { // 流式接口的后端强制searchCode闭环。
        log.info("[ToolChatStream] force execute tool: {}", SEARCH_CODE_TOOL_NAME); // 打印强制执行的工具名。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 代码搜索分支没有执行ragSearch。
        AiTool searchCodeTool = toolRegistry.getTool(SEARCH_CODE_TOOL_NAME); // 从ToolRegistry获取SearchCodeTool。
        if (searchCodeTool == null) { // searchCode未注册时不能伪造搜索结果。
            callback.onToken("系统错误：searchCode 工具未注册。"); // 通过SSE返回明确错误。
            callback.onComplete(); // 结束本轮SSE。
            return; // 工具缺失时不再继续。
        }

        String query = resolveSearchCodeQueryFromMessage(userMessage); // 从用户输入中提取搜索关键词。
        String searchType = resolveSearchCodeTypeFromMessage(userMessage, query); // 根据用户表达和query推断搜索类型。
        String searchPath = resolveSearchCodePathFromMessage(userMessage); // 从用户输入中轻量提取搜索范围。
        if (isProjectToolInventoryIntent(userMessage)) { // 工具清单或工具存在性问题需要优先搜索真实Tool实现。
            log.info("[ToolChatStream] force searchCode for tool inventory, query: {}", query); // 只打印query，不打印完整prompt。
        }
        log.info("[ToolChatStream] force searchCode query: {}, searchType: {}, path: {}",
                previewContent(query), searchType, searchPath == null || searchPath.isBlank() ? "." : searchPath); // 只打印短query和相对路径。
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造searchCode工具入参。
        arguments.put("query", query); // query 必填，工具内部会再次校验。
        arguments.put("searchType", searchType); // 写入搜索类型。
        if (searchPath != null && !searchPath.isBlank()) { // 有明确目录时传path。
            arguments.put("path", searchPath); // path必须是相对workspace路径，工具内部会再次校验。
        }
        arguments.put("maxResults", FORCE_SEARCH_CODE_MAX_RESULTS); // 强制路由使用默认结果上限。
        arguments.put("contextLines", FORCE_SEARCH_CODE_CONTEXT_LINES); // 强制路由使用默认上下文行数。
        arguments.put("caseSensitive", false); // 默认不区分大小写，减少漏搜。
        String toolResult = executeToolWithLog(searchCodeTool, arguments, requestContext,
                CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_SEARCH_CODE); // 执行searchCode并记录tool_call_log。
        logToolResult(toolResult); // 工具结果只打preview，不打印完整搜索结果。
        saveRecentProjectTargetFromSearchCodeResult(userMessage, arguments, toolResult, requestContext); // 唯一命中真实文件时刷新 recentProjectTarget。

        String argumentsJson = serializeArguments(arguments); // 复用日志入参序列化，保证最终回答阶段明确当前query。
        ObjectNode streamRequest = buildForcedSearchCodeAnswerRequest(userMessage, argumentsJson, toolResult); // searchCode最终回答只保留本轮工具结果，避免历史query污染。
        streamSafeToolFinalAnswer(SEARCH_CODE_TOOL_NAME, userMessage, argumentsJson, toolResult,
                streamRequest, callback, streamErrorNotified); // 统一检测并拦截DSML/tool_calls泄漏。
    }

    private void streamWithForcedAnalyzeCode(String userMessage,
                                             String memorySummary,
                                             List<ToolChatHistoryMessage> historyMessages,
                                             ToolCallingRequestContext requestContext,
                                             ToolCallingStreamCallback callback,
                                             boolean[] streamErrorNotified,
                                             CodeAnalysisType analysisType) { // 流式接口的后端强制 analyzeCode 统一分析闭环。
        log.info("[ToolChatStream] force execute tool: {}", ANALYZE_CODE_TOOL_NAME); // 打印强制执行的工具名。
        log.info("[ToolChatStream] force analyzeCode analysisType: {}", analysisType); // P5.5.5 后所有代码分析都通过 analysisType 分发。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 代码分析分支没有执行ragSearch。
        AiTool analyzeCodeTool = toolRegistry.getTool(ANALYZE_CODE_TOOL_NAME); // 从ToolRegistry获取AnalyzeCodeTool。
        if (analyzeCodeTool == null) { // analyzeCode未注册时不能伪造分析结果。
            callback.onToken("系统错误：analyzeCode 工具未注册。"); // 通过SSE返回明确错误。
            callback.onComplete(); // 结束本轮SSE。
            return; // 工具缺失时不再继续。
        }

        ObjectNode arguments = buildForcedAnalyzeCodeArguments(userMessage, requestContext, analysisType); // 按分析类型构造统一 analyzeCode 入参。
        if (arguments == null) { // 没有可执行目标时不乱猜。
            callback.onToken(buildAnalyzeCodeTargetMissingPrompt(analysisType, requestContext)); // 根据分析类型提示用户补充目标。
            callback.onComplete(); // 结束本轮SSE。
            return; // 不执行工具。
        }

        log.info("[ToolChatStream] force analyzeCode arguments: {}", previewContent(serializeArguments(arguments))); // 只打印短参数预览。
        String routeReason = buildAnalyzeCodeRouteReason(analysisType, arguments); // 统一 route_reason，旧专项不再写旧 toolName。
        String toolResult = executeToolWithLog(analyzeCodeTool, arguments, requestContext,
                CALL_SOURCE_FORCE_ROUTE, routeReason); // 执行analyzeCode并记录tool_call_log。
        logToolResult(toolResult); // 工具结果只打preview，不打印完整分析JSON。
        saveProjectFileFocusFromCodeAnalysisResult(toolResult, requestContext); // 成功分析后刷新projectFileFocus元信息。

        String argumentsJson = serializeArguments(arguments); // 复用日志入参序列化，保证最终回答阶段明确当前path。
        ObjectNode streamRequest = buildForcedAnalyzeCodeAnswerRequest(userMessage, argumentsJson, toolResult); // analyzeCode最终回答只保留本轮工具结果。
        streamSafeToolFinalAnswer(ANALYZE_CODE_TOOL_NAME, userMessage, argumentsJson, toolResult,
                streamRequest, callback, streamErrorNotified); // 统一检测并拦截DSML/tool_calls泄漏。
    }

    private ObjectNode buildForcedAnalyzeCodeArguments(String userMessage,
                                                       ToolCallingRequestContext requestContext,
                                                       CodeAnalysisType analysisType) { // 根据分析类型构造统一 analyzeCode 参数。
        CodeAnalysisType safeType = analysisType == null ? CodeAnalysisType.AUTO : analysisType; // 空类型按 AUTO 兜底。
        ObjectNode arguments = objectMapper.createObjectNode(); // 创建统一参数。
        arguments.put("analysisType", safeType.name()); // tool_call_log 必须记录 analysisType。
        arguments.put("includeSnippet", safeType != CodeAnalysisType.EXPLANATION); // 说明生成默认不贴片段，其它分析默认返回少量片段。
        arguments.put("maxItems", resolveAnalyzeCodeMaxItems(safeType)); // 按类型复用原默认上限。
        if (safeType != CodeAnalysisType.EXPLANATION) { // 说明生成不写 maxDepth，避免 tool_call_log 把说明请求看成链路分析。
            arguments.put("maxDepth", resolveAnalyzeCodeMaxDepth(userMessage, safeType)); // 按类型复用原深度判断。
        }
        if (safeType == CodeAnalysisType.EXPLANATION) { // P5.6 代码说明。
            return fillExplanationAnalyzeCodeArguments(userMessage, requestContext, arguments); // 填充目标、explanationType、detailLevel、audience。
        }
        if (safeType == CodeAnalysisType.SSE_EVENT_CHAIN) { // SSE 前后端事件链路。
            return fillSseAnalyzeCodeArguments(userMessage, requestContext, arguments); // 填充 endpoint/eventName/frontendKeyword/path。
        }
        if (safeType == CodeAnalysisType.CONTROLLER_SERVICE) { // Controller→Service。
            return fillControllerAnalyzeCodeArguments(userMessage, requestContext, arguments); // 填充 endpoint/path/methodName。
        }
        if (safeType == CodeAnalysisType.TOOL_SERVICE) { // Tool→Service。
            return fillToolAnalyzeCodeArguments(userMessage, requestContext, arguments); // 填充 path/toolName/className/methodName。
        }
        if (safeType == CodeAnalysisType.CALL_CHAIN) { // 普通调用链。
            return fillCallChainAnalyzeCodeArguments(userMessage, requestContext, arguments); // 填充 path/methodName。
        }
        return fillStructureAnalyzeCodeArguments(userMessage, requestContext, arguments); // 默认结构分析。
    }

    private ObjectNode fillExplanationAnalyzeCodeArguments(String userMessage,
                                                           ToolCallingRequestContext requestContext,
                                                           ObjectNode arguments) { // 构造 EXPLANATION 参数。
        String explanationType = resolveExplanationTypeFromMessage(userMessage, requestContext); // 根据真实目标和意图判断说明类型。
        arguments.put("explanationType", explanationType); // 写入说明类型。
        arguments.put("detailLevel", resolveExplanationDetailLevel(userMessage)); // 写入说明详细程度。
        arguments.put("audience", resolveExplanationAudience(userMessage)); // 写入说明受众。

        String eventName = extractSseEventName(userMessage); // 优先提取真实 SSE 事件名。
        String frontendKeyword = extractSseFrontendKeyword(userMessage); // 提取前端关键词。
        boolean sseExplanation = "SSE_CHAIN".equals(explanationType); // 是否说明 SSE 链路。
        if (sseExplanation) { // SSE 说明优先保留 eventName/frontendKeyword/endpoint。
            String endpoint = projectCodeTargetResolver.extractEndpoint(userMessage); // SSE 也可能按接口说明。
            if (endpoint != null && !endpoint.isBlank()) { // 有接口路径。
                arguments.put("endpoint", endpoint); // endpoint 不放 path。
                arguments.put("targetSource", "endpoint"); // 标记来源。
            }
            if (eventName != null && !eventName.isBlank()) { // 有事件名。
                arguments.put("eventName", eventName); // 写入事件名。
                arguments.put("targetSource", "eventName"); // 标记来源。
            }
            if (frontendKeyword != null && !frontendKeyword.isBlank()) { // 有前端关键词。
                arguments.put("frontendKeyword", frontendKeyword); // 写入前端关键词。
                arguments.put("targetSource", "frontendKeyword"); // 标记来源。
            }
            if (!arguments.hasNonNull("endpoint") && !arguments.hasNonNull("eventName") && !arguments.hasNonNull("frontendKeyword")) { // 没有显式 SSE 目标。
                fillFocusPathForExplanation(requestContext, arguments, true); // 允许 recentProjectTarget/projectFileFocus 中的 SSE 文件。
            }
            return hasAnalyzeCodeTarget(arguments) ? arguments : null; // 无真实目标时交给上层提示。
        }

        String endpoint = extractControllerEndpoint(userMessage); // 提取 Controller endpoint。
        if ("CONTROLLER_ENDPOINT".equals(explanationType) && endpoint != null && !endpoint.isBlank()) { // 接口说明。
            arguments.put("endpoint", endpoint); // API 路径必须放 endpoint。
            arguments.put("targetSource", "endpoint"); // 标记来源。
            return arguments; // endpoint-only 由内部 Controller 分析器扫描。
        }

        if ("TOOL_CHAIN".equals(explanationType)) { // AI Tool 说明。
            AnalyzeToolTarget toolTarget = resolveToolTargetFromMessage(userMessage, requestContext); // 复用 Tool 真实目标解析。
            if (toolTarget == null || !toolTarget.hasAnyTarget()) { // 未找到 Tool 目标。
                return null; // 不脑补 Tool 类。
            }
            if (toolTarget.path() != null && !toolTarget.path().isBlank()) { // 有 Tool 路径。
                arguments.put("path", toolTarget.path()); // 写入 path。
            }
            if (toolTarget.toolName() != null && !toolTarget.toolName().isBlank()) { // 有 toolName。
                arguments.put("toolName", toolTarget.toolName()); // 写入 toolName。
            }
            if (toolTarget.className() != null && !toolTarget.className().isBlank()) { // 有类名。
                arguments.put("className", toolTarget.className()); // 写入 className。
            }
            arguments.put("methodName", extractToolServiceEntryMethodName(userMessage)); // Tool 说明默认 execute。
            markTargetSource(arguments, toolTarget.source()); // 写入 recentProjectTarget/projectFileFocus 来源。
            return arguments; // 返回 Tool 说明参数。
        }

        AnalyzeCodeTarget target = resolveAnalyzeCodeTargetFromMessage(userMessage, requestContext); // 普通类/方法/调用链说明目标。
        if (target == null || target.path() == null || target.path().isBlank()) { // 没有真实目标。
            return null; // 不乱猜。
        }
        arguments.put("path", target.path()); // 写入目标文件或类名。
        String methodName = extractCallChainMethodName(userMessage); // 提取可选方法名。
        if (methodName != null && !methodName.isBlank()) { // 有方法名。
            arguments.put("methodName", methodName); // 写入方法名。
            if ("AUTO".equals(explanationType) || "CLASS".equals(explanationType)) { // 明确方法名时说明方法。
                arguments.put("explanationType", "METHOD"); // 更新为方法说明。
            }
        }
        if ("CALL_CHAIN".equals(explanationType)) { // 调用链说明。
            arguments.put("maxDepth", resolveAnalyzeCodeMaxDepth(userMessage, CodeAnalysisType.CALL_CHAIN)); // 复用调用链深度。
        }
        markTargetSource(arguments, target.source()); // 写入 recentProjectTarget/projectFileFocus 来源。
        return arguments; // 返回普通说明参数。
    }

    private ObjectNode fillStructureAnalyzeCodeArguments(String userMessage,
                                                        ToolCallingRequestContext requestContext,
                                                        ObjectNode arguments) { // 构造 STRUCTURE 参数。
        AnalyzeCodeTarget target = resolveAnalyzeCodeTargetFromMessage(userMessage, requestContext); // 解析明确文件、类名或 projectFileFocus。
        if (target == null || target.path() == null || target.path().isBlank()) { // 无目标。
            return null; // 交给上层提示。
        }
        arguments.put("path", target.path()); // 结构分析传 path。
        if (target.byRecentProjectTarget()) { // 标记来自最近明确项目目标。
            arguments.put("targetSource", "recentProjectTarget"); // route_reason 使用。
        } else if (target.byFocus()) { // 标记来自 focus。
            arguments.put("targetSource", "projectFileFocus"); // route_reason 使用。
        }
        return arguments; // 返回参数。
    }

    private ObjectNode fillCallChainAnalyzeCodeArguments(String userMessage,
                                                        ToolCallingRequestContext requestContext,
                                                        ObjectNode arguments) { // 构造 CALL_CHAIN 参数。
        AnalyzeCodeTarget target = resolveAnalyzeCodeTargetFromMessage(userMessage, requestContext); // 复用结构分析目标解析。
        if (target == null || target.path() == null || target.path().isBlank()) { // 无目标。
            return null; // 交给上层提示。
        }
        arguments.put("path", target.path()); // 调用链分析传 path。
        String methodName = extractCallChainMethodName(userMessage); // 提取可选入口方法。
        if (methodName != null && !methodName.isBlank()) { // 指定入口。
            arguments.put("methodName", methodName); // 写入入口方法。
        }
        if (target.byFocus()) { // 标记来自 focus。
            arguments.put("targetSource", "projectFileFocus"); // route_reason 使用。
        } else if (target.byRecentProjectTarget()) { // 标记来自最近明确项目目标。
            arguments.put("targetSource", "recentProjectTarget"); // route_reason 使用。
        }
        return arguments; // 返回参数。
    }

    private ObjectNode fillControllerAnalyzeCodeArguments(String userMessage,
                                                         ToolCallingRequestContext requestContext,
                                                         ObjectNode arguments) { // 构造 CONTROLLER_SERVICE 参数。
        String endpoint = extractControllerEndpoint(userMessage); // endpoint 明确时全项目扫描。
        boolean endpointQuery = endpoint != null && !endpoint.isBlank(); // 是否 endpoint-only。
        AnalyzeCodeTarget target = endpointQuery ? null : resolveControllerTargetFromMessage(userMessage, requestContext); // 无 endpoint 才允许 Controller 文件目标。
        if (!endpointQuery && (target == null || target.path() == null || target.path().isBlank())) { // 无 endpoint 也无 Controller。
            return null; // 交给上层提示。
        }
        if (target != null && target.path() != null && !target.path().isBlank()) { // 有 Controller 文件。
            arguments.put("path", target.path()); // 写入 path。
            if (target.byRecentProjectTarget()) { // 来自 recentProjectTarget。
                arguments.put("targetSource", "recentProjectTarget"); // route_reason 使用。
            } else if (target.byFocus()) { // 来自 focus。
                arguments.put("targetSource", "projectFileFocus"); // route_reason 使用。
            }
        }
        if (endpoint != null && !endpoint.isBlank()) { // endpoint 查询。
            arguments.put("endpoint", endpoint); // 写入 endpoint，绝不放入 path。
            arguments.put("targetSource", "endpoint"); // route_reason 使用。
        }
        String methodName = extractControllerMethodName(userMessage); // 可选 Controller 方法。
        if (!endpointQuery && methodName != null && !methodName.isBlank()) { // endpoint 查询不被方法名约束。
            arguments.put("methodName", methodName); // 写入方法名。
        }
        return arguments; // 返回参数。
    }

    private ObjectNode fillToolAnalyzeCodeArguments(String userMessage,
                                                   ToolCallingRequestContext requestContext,
                                                   ObjectNode arguments) { // 构造 TOOL_SERVICE 参数。
        AnalyzeToolTarget target = resolveToolTargetFromMessage(userMessage, requestContext); // 解析 path/toolName/className 或 AI Tool 焦点。
        if (target == null || !target.hasAnyTarget()) { // 无 Tool 目标。
            return null; // 交给上层提示。
        }
        if (target.path() != null && !target.path().isBlank()) { // 有 Tool 文件路径。
            arguments.put("path", target.path()); // 写入 path。
        }
        if (target.toolName() != null && !target.toolName().isBlank()) { // 有 toolName。
            arguments.put("toolName", target.toolName()); // 写入 toolName。
        }
        if (target.className() != null && !target.className().isBlank()) { // 有类名。
            arguments.put("className", target.className()); // 写入 className。
        }
        arguments.put("methodName", extractToolServiceEntryMethodName(userMessage)); // 默认 execute。
        if (target.byRecentProjectTarget()) { // 来自 recentProjectTarget。
            arguments.put("targetSource", "recentProjectTarget"); // route_reason 使用。
        } else if (target.byFocus()) { // 来自 focus。
            arguments.put("targetSource", "projectFileFocus"); // route_reason 使用。
        }
        return arguments; // 返回参数。
    }

    private ObjectNode fillSseAnalyzeCodeArguments(String userMessage,
                                                  ToolCallingRequestContext requestContext,
                                                  ObjectNode arguments) { // 构造 SSE_EVENT_CHAIN 参数。
        String endpoint = projectCodeTargetResolver.extractEndpoint(userMessage); // 提取接口路径。
        String eventName = extractSseEventName(userMessage); // 提取事件名。
        String frontendKeyword = extractSseFrontendKeyword(userMessage); // 提取前端关键词。
        String methodName = extractCallChainMethodName(userMessage); // 提取后端方法名。
        boolean explicitTarget = endpoint != null || eventName != null || frontendKeyword != null; // 是否明确 SSE 目标。
        ConversationFocusContext recentTarget = resolveRecentProjectTarget(requestContext); // SSE 指代追问也优先使用最近明确项目目标。
        boolean useRecentTarget = !explicitTarget && recentTarget != null && isSseProjectPath(recentTarget.getPath()); // 最近目标是否可作为 SSE 文件。
        String focusPath = useRecentTarget
                ? recentTarget.getPath()
                : ((!explicitTarget && recentTarget == null && isSseFocus(requestContext)) ? requestContext.getProjectFileFocus().getPath() : null); // 有 recentProjectTarget 时不回退旧 SSE 焦点。
        if (!explicitTarget && (focusPath == null || focusPath.isBlank())) { // 无目标。
            return null; // 交给上层提示。
        }
        if (focusPath != null && !focusPath.isBlank()) { // 有焦点路径。
            arguments.put("path", focusPath); // 写入 path。
            arguments.put("targetSource", useRecentTarget ? "recentProjectTarget" : "projectFileFocus"); // route_reason 使用。
        }
        if (endpoint != null && !endpoint.isBlank()) { // 有 endpoint。
            arguments.put("endpoint", endpoint); // 写入 endpoint。
            arguments.put("targetSource", "endpoint"); // route_reason 使用。
        }
        if (eventName != null && !eventName.isBlank()) { // 有事件名。
            arguments.put("eventName", eventName); // 写入 eventName。
        }
        if (frontendKeyword != null && !frontendKeyword.isBlank()) { // 有前端关键词。
            arguments.put("frontendKeyword", frontendKeyword); // 写入 frontendKeyword。
        }
        if (methodName != null && !methodName.isBlank()) { // 有方法名。
            arguments.put("methodName", methodName); // 写入 methodName。
        }
        return arguments; // 返回参数。
    }

    private int resolveAnalyzeCodeMaxItems(CodeAnalysisType analysisType) { // 按分析类型复用旧默认 maxItems。
        if (analysisType == CodeAnalysisType.EXPLANATION) { // 代码说明。
            return FORCE_ANALYZE_CODE_MAX_ITEMS; // 说明条目上限保持 100。
        }
        if (analysisType == CodeAnalysisType.SSE_EVENT_CHAIN) { // SSE 链路。
            return FORCE_SSE_EVENT_CHAIN_MAX_ITEMS; // 原 SSE 上限。
        }
        if (analysisType == CodeAnalysisType.TOOL_SERVICE) { // Tool→Service。
            return FORCE_TOOL_SERVICE_CHAIN_MAX_ITEMS; // 原 Tool 链路上限。
        }
        if (analysisType == CodeAnalysisType.CONTROLLER_SERVICE) { // Controller→Service。
            return FORCE_CONTROLLER_SERVICE_CHAIN_MAX_ITEMS; // 原 Controller 链路上限。
        }
        if (analysisType == CodeAnalysisType.CALL_CHAIN) { // 普通调用链。
            return FORCE_ANALYZE_CALL_CHAIN_MAX_ITEMS; // 原调用链上限。
        }
        return FORCE_ANALYZE_CODE_MAX_ITEMS; // 结构分析上限。
    }

    private int resolveAnalyzeCodeMaxDepth(String userMessage, CodeAnalysisType analysisType) { // 按分析类型复用旧深度判断。
        if (analysisType == CodeAnalysisType.EXPLANATION) { // 代码说明。
            return containsAny(userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT), CODE_EXPLANATION_DETAILED_KEYWORDS) ? 2 : 1; // 详细说明时允许底层 Analyzer 展开一层。
        }
        if (analysisType == CodeAnalysisType.SSE_EVENT_CHAIN) { // SSE 链路。
            return isSseDepthIntent(userMessage) ? 2 : FORCE_SSE_EVENT_CHAIN_MAX_DEPTH; // 旧 SSE 深度。
        }
        if (analysisType == CodeAnalysisType.TOOL_SERVICE) { // Tool→Service。
            return isToolServiceDepthIntent(userMessage) ? 2 : FORCE_TOOL_SERVICE_CHAIN_MAX_DEPTH; // 旧 Tool 深度。
        }
        if (analysisType == CodeAnalysisType.CONTROLLER_SERVICE) { // Controller→Service。
            return isServiceImplDepthIntent(userMessage) ? 2 : 1; // 旧 Controller 深度。
        }
        if (analysisType == CodeAnalysisType.CALL_CHAIN) { // 普通调用链。
            return FORCE_ANALYZE_CALL_CHAIN_MAX_DEPTH; // 旧调用链深度。
        }
        return 1; // 结构分析不展开深度。
    }

    private String buildAnalyzeCodeRouteReason(CodeAnalysisType analysisType, ObjectNode arguments) { // 构造统一 analyzeCode 路由原因。
        String source = arguments == null ? "" : arguments.path("targetSource").asText(""); // 读取目标来源标记。
        if (analysisType == CodeAnalysisType.EXPLANATION && "endpoint".equals(source)) { // endpoint 说明场景需要精确日志。
            return ROUTE_REASON_FORCE_ANALYZE_CODE_EXPLANATION_BY_ENDPOINT; // 返回 endpoint 说明路由原因。
        }
        if (analysisType == CodeAnalysisType.EXPLANATION && "recentProjectTarget".equals(source)) { // recentProjectTarget 说明场景。
            return ROUTE_REASON_FORCE_ANALYZE_CODE_EXPLANATION_BY_RECENT_TARGET; // 返回最近目标说明路由原因。
        }
        if (analysisType == CodeAnalysisType.EXPLANATION && "projectFileFocus".equals(source)) { // projectFileFocus 说明场景。
            return ROUTE_REASON_FORCE_ANALYZE_CODE_EXPLANATION_BY_FOCUS; // 返回焦点说明路由原因。
        }
        if ("recentProjectTarget".equals(source)) { // 来自最近明确项目目标。
            return ROUTE_REASON_FORCE_ANALYZE_CODE_BY_RECENT_TARGET; // 验收要求 route_reason 精确标记 recentProjectTarget。
        }
        if ("projectFileFocus".equals(source)) { // 来自 focus。
            return ROUTE_REASON_FORCE_ANALYZE_CODE_BY_FOCUS + " " + analysisType.name(); // 统一记录 analyzeCode by focus。
        }
        return ROUTE_REASON_FORCE_ANALYZE_CODE + " " + analysisType.name(); // 显式目标或 endpoint 统一记录 analyzeCode。
    }

    private String buildAnalyzeCodeTargetMissingPrompt(CodeAnalysisType analysisType,
                                                       ToolCallingRequestContext requestContext) { // analyzeCode 缺目标提示。
        if (analysisType == CodeAnalysisType.EXPLANATION) { // 说明生成。
            return "请指定要说明的类名、文件、接口、AI Tool 或 SSE 事件，例如：说明 SearchCodeTool 是怎么工作的、说明 /chat/message 这个接口流程、summary_result 事件链路怎么讲。"; // EXPLANATION 提示。
        }
        if (analysisType == CodeAnalysisType.TOOL_SERVICE) { // Tool 专项。
            return buildToolServiceChainTargetMissingPrompt(requestContext); // 复用 Tool 缺目标提示。
        }
        if (analysisType == CodeAnalysisType.SSE_EVENT_CHAIN) { // SSE 专项。
            return "请提供 SSE 接口路径、事件名、前端关键词或相关文件路径，例如：分析 /chat/message 的 SSE 链路、summary_result 事件前端在哪里接收。"; // SSE 提示。
        }
        if (analysisType == CodeAnalysisType.CONTROLLER_SERVICE) { // Controller 专项。
            return "请提供 Controller 文件路径、Controller 类名或接口路径，例如 /chat/message。"; // Controller 提示。
        }
        return buildProjectFileFocusMissingPrompt(); // 结构/普通调用链沿用项目文件缺焦点提示。
    }

    private void streamWithForcedReadProjectFile(String userMessage,
                                                 String memorySummary,
                                                 List<ToolChatHistoryMessage> historyMessages,
                                                 ToolCallingRequestContext requestContext,
                                                 ToolCallingStreamCallback callback,
                                                 boolean[] streamErrorNotified) { // 流式接口的后端强制readProjectFile闭环。
        log.info("[ToolChatStream] force execute tool: {}", READ_PROJECT_FILE_TOOL_NAME); // 打印强制执行的工具名。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 项目文件读取分支没有执行ragSearch。
        AiTool readProjectFileTool = toolRegistry.getTool(READ_PROJECT_FILE_TOOL_NAME); // 从ToolRegistry获取ReadProjectFileTool。
        if (readProjectFileTool == null) { // readProjectFile未注册时不能伪造读取结果。
            callback.onToken("系统错误：readProjectFile 工具未注册。"); // 通过SSE返回明确错误。
            callback.onComplete(); // 结束本轮SSE。
            return; // 工具缺失时不再继续。
        }

        ReadProjectFileTarget readTarget = resolveReadProjectFileTarget(userMessage, requestContext); // 显式目标优先，其次 recentProjectTarget，再其次 projectFileFocus。
        String projectFilePath = readTarget == null ? "" : readTarget.path(); // 读取目标 path。
        if (projectFilePath == null || projectFilePath.isBlank()) { // 没有明确路径或上下文目标时不盲目搜索或读取。
            callback.onToken("请提供要读取的项目文件相对路径，或先使用 searchCode 定位文件位置。"); // 直接提示用户补路径。
            callback.onComplete(); // 结束本轮SSE。
            return; // 不执行工具。
        }

        String readMode = resolveReadProjectFileMode(userMessage); // 根据“完整代码/全部代码”等表达确定读取模式。
        log.info("[ToolChatStream] force readProjectFile path: {}, readMode: {}", projectFilePath, readMode); // 只打印相对路径和模式。
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造readProjectFile工具入参。
        arguments.put("path", projectFilePath); // path必须是相对workspace路径，工具内部会再次校验。
        arguments.put("maxChars", FORCE_READ_PROJECT_FILE_MAX_CHARS); // 强制路由使用较高读取上限，普通Java类尽量完整返回。
        arguments.put("readMode", readMode); // FULL模式进入tool_call_log，便于排查完整代码输出。
        String routeReason = resolveReadProjectFileRouteReason(userMessage, readTarget); // 明确路径、类名/文件名、recentProjectTarget 或 projectFileFocus 分别记录路由原因。
        String toolResult = executeToolWithLog(readProjectFileTool, arguments, requestContext,
                CALL_SOURCE_FORCE_ROUTE, routeReason); // 执行readProjectFile并记录tool_call_log。
        logToolResult(toolResult); // 工具结果只打preview，不打印完整代码内容。
        saveProjectFileFocusFromToolResult(toolResult, requestContext, readMode); // 成功读取后保存 projectFileFocus，后续“给我代码/继续分析”可复用。

        if (READ_PROJECT_FILE_MODE_FULL.equals(readMode)) { // 用户明确要求完整代码时不交给模型自由省略。
            String finalAnswer = buildReadProjectFileFullAnswer(toolResult); // 后端确定性构造完整代码块或安全失败文案。
            streamPlainText(finalAnswer, callback); // 分块输出，保留SSE流式体验。
            return; // FULL模式已完成回答，不再调用DeepSeek总结。
        }

        ObjectNode streamRequest = buildForcedReadProjectFileAnswerRequest(userMessage, memorySummary, historyMessages,
                toolResult, requestContext); // 将项目文件读取结果注入最终回答。
        streamSafeToolFinalAnswer(READ_PROJECT_FILE_TOOL_NAME, userMessage, serializeArguments(arguments), toolResult,
                streamRequest, callback, streamErrorNotified); // 非FULL模式仍由模型基于文件内容流式分析。
    }

    private void streamWithProjectFileFocus(String userMessage,
                                            String memorySummary,
                                            List<ToolChatHistoryMessage> historyMessages,
                                            ToolCallingRequestContext requestContext,
                                            ToolCallingStreamCallback callback,
                                            boolean[] streamErrorNotified) { // 基于 projectFileFocus 强制读取最近项目源码文件。
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 从请求上下文读取项目源码文件焦点。
        if (focus == null || focus.getPath() == null || focus.getPath().isBlank()) { // 理论上调用前已判断，兜底防空。
            log.info("[ToolChatStream] projectFileFocus invalid, reason: empty focus"); // 记录焦点失效原因。
            callback.onToken(buildProjectFileFocusMissingPrompt()); // 提示用户指定文件。
            callback.onComplete(); // 结束SSE。
            return; // 不继续执行工具。
        }

        AiTool readProjectFileTool = toolRegistry.getTool(READ_PROJECT_FILE_TOOL_NAME); // 从ToolRegistry获取ReadProjectFileTool。
        if (readProjectFileTool == null) { // readProjectFile未注册时不能继续。
            callback.onToken("系统错误：readProjectFile 工具未注册。"); // 通过SSE返回明确错误。
            callback.onComplete(); // 结束本轮SSE。
            return; // 工具缺失时不再继续。
        }

        String readMode = resolveProjectFileFocusReadMode(userMessage); // 根据追问语义决定 FULL 或 SUMMARY。
        log.info("[ToolChatStream] use projectFileFocus: {}, readMode: {}", focus.getPath(), readMode); // 只打印相对路径和读取模式。
        log.info("[ToolChatStream] force execute tool: {}", READ_PROJECT_FILE_TOOL_NAME); // 标记后端强制执行 readProjectFile。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 项目文件焦点分支不执行RAG。

        ObjectNode arguments = objectMapper.createObjectNode(); // 构造readProjectFile工具入参。
        arguments.put("path", focus.getPath()); // path 来自 Redis 中保存的 workspace 相对路径。
        arguments.put("maxChars", FORCE_READ_PROJECT_FILE_MAX_CHARS); // 焦点读取同样使用较高上限。
        arguments.put("readMode", readMode); // FULL/SUMMARY 进入 tool_call_log。
        String toolResult = executeToolWithLog(readProjectFileTool, arguments, requestContext,
                CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_FOCUS); // 执行工具并记录 projectFileFocus 路由原因。
        logToolResult(toolResult); // 工具结果只打preview，不打印完整代码内容。
        saveProjectFileFocusFromToolResult(toolResult, requestContext, readMode); // 成功时刷新 projectFileFocus 更新时间和模式。

        if (READ_PROJECT_FILE_MODE_FULL.equals(readMode)) { // “给我代码/完整代码”使用确定性代码块输出。
            String finalAnswer = buildReadProjectFileFullAnswer(toolResult); // 构造完整代码块或失败文案。
            streamPlainText(finalAnswer, callback); // 分块SSE输出，不让模型省略代码。
            return; // FULL模式不再调用DeepSeek总结。
        }

        ObjectNode streamRequest = buildForcedReadProjectFileAnswerRequest(userMessage, memorySummary, historyMessages,
                toolResult, requestContext); // 将项目文件内容注入最终回答。
        streamSafeToolFinalAnswer(READ_PROJECT_FILE_TOOL_NAME, userMessage, serializeArguments(arguments), toolResult,
                streamRequest, callback, streamErrorNotified); // SUMMARY模式由模型基于文件内容流式分析。
    }

    private void streamWithForcedListProjectTree(String userMessage,
                                                 String memorySummary,
                                                 List<ToolChatHistoryMessage> historyMessages,
                                                 ToolCallingRequestContext requestContext,
                                                 ToolCallingStreamCallback callback,
                                                 boolean[] streamErrorNotified) { // 流式接口的后端强制listProjectTree闭环。
        log.info("[ToolChatStream] force execute tool: {}", LIST_PROJECT_TREE_TOOL_NAME); // 打印强制执行的工具名。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 目录树分支没有执行ragSearch。
        AiTool projectTreeTool = toolRegistry.getTool(LIST_PROJECT_TREE_TOOL_NAME); // 从ToolRegistry获取ListProjectTreeTool。
        if (projectTreeTool == null) { // listProjectTree未注册时不能伪造目录树结果。
            callback.onToken("系统错误：listProjectTree 工具未注册。"); // 通过SSE返回明确错误。
            callback.onComplete(); // 结束本轮SSE。
            return; // 工具缺失时不再继续。
        }

        String projectPath = resolveProjectTreePathFromMessage(userMessage); // 从用户输入中轻量提取workspace相对目录。
        log.info("[ToolChatStream] force listProjectTree path: {}", projectPath == null || projectPath.isBlank() ? "." : projectPath); // 只打印相对路径。
        ObjectNode arguments = objectMapper.createObjectNode(); // 构造listProjectTree工具入参。
        if (projectPath != null && !projectPath.isBlank()) { // 有明确目录时传path。
            arguments.put("path", projectPath); // path必须是相对workspace路径，工具内部会再次校验。
        }
        arguments.put("maxDepth", FORCE_LIST_PROJECT_TREE_MAX_DEPTH); // 强制路由使用默认目录深度。
        arguments.put("maxNodes", FORCE_LIST_PROJECT_TREE_MAX_NODES); // 强制路由使用默认节点上限。
        String toolResult = executeToolWithLog(projectTreeTool, arguments, requestContext,
                CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_LIST_PROJECT_TREE); // 执行listProjectTree并记录tool_call_log。
        logToolResult(toolResult); // 工具结果只打preview，不打印完整目录树。

        ObjectNode streamRequest = buildForcedProjectTreeAnswerRequest(userMessage, memorySummary, historyMessages,
                toolResult, requestContext); // 将目录树结果注入最终回答。
        streamSafeToolFinalAnswer(LIST_PROJECT_TREE_TOOL_NAME, userMessage, serializeArguments(arguments), toolResult,
                streamRequest, callback, streamErrorNotified); // 工具结果最终回答统一走DSML泄漏检测和安全兜底。
    }

    private void streamWithForcedReadFile(String userMessage,
                                          String memorySummary,
                                          List<ToolChatHistoryMessage> historyMessages,
                                          ToolCallingRequestContext requestContext,
                                          ToolCallingStreamCallback callback,
                                          boolean[] streamErrorNotified) { // 流式接口的后端强制readFile闭环。
        ForceReadFileTarget target = resolveForceReadFileTarget(userMessage, requestContext); // 根据当前附件和用户输入确定要读取的文件。
        if (target == null || target.isAmbiguous()) { // 多附件且未指定文件名时不乱读。
            log.info("[ToolChatStream] force readFile skipped, reason: multiple files ambiguous"); // 记录跳过原因。
            log.info("[ToolChatStream] multiple file focus ambiguous, ask user to specify"); // 明确要求用户指定文件。
            log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 明确没有走RAG。
            callback.onToken(buildReadFileAmbiguousPrompt(target == null ? Collections.emptyList() : target.focusFiles())); // 直接提示用户指定文件，不再调用模型或RAG。
            callback.onComplete(); // 结束本轮SSE。
            return; // 多附件模糊场景结束。
        }

        ChatAttachedFileContext file = target.file(); // 已明确的目标附件。
        AiTool readFileTool = toolRegistry.getTool(READ_FILE_TOOL_NAME); // 从ToolRegistry获取ReadFileTool。
        if (readFileTool == null) { // readFile未注册时不能伪造读取结果。
            callback.onToken("系统错误：readFile 工具未注册。"); // 通过SSE返回明确错误。
            callback.onComplete(); // 结束本轮SSE。
            return; // 工具缺失时不再继续。
        }

        log.info("[ToolChatStream] force readFile fileId: {}, fileName: {}",
                file == null ? null : file.getFileId(),
                file == null ? null : safeText(file.getOriginalName())); // 只打印文件ID和原始名，不打印路径。
        log.info("[ToolChatStream] execute readFile by force route"); // 明确本轮由后端强制执行readFile。
        log.info("[ToolChatStream] force readFile by file focus"); // 标记本轮由文件焦点触发强制读取。
        log.info("[ToolChatStream] force execute tool: {}", READ_FILE_TOOL_NAME); // 和其它强制工具保持一致的执行日志。

        ObjectNode arguments = objectMapper.createObjectNode(); // 构造readFile工具入参。
        arguments.put("fileId", file.getFileId()); // 只传fileId，不允许从模型参数传userId。
        arguments.put("maxChars", FORCE_READ_FILE_MAX_CHARS); // 强制路由使用默认读取长度，避免日志和上下文过大。
        String toolResult = executeToolWithLog(readFileTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_READ_FILE); // 执行readFile并记录tool_call_log。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // readFile分支没有执行ragSearch。
        logToolResult(toolResult); // 工具结果只打preview，不打印完整文件内容。

        ObjectNode streamRequest = buildForcedReadFileAnswerRequest(userMessage, memorySummary, historyMessages,
                toolResult, requestContext); // 将readFile结果注入最终回答。
        streamSafeToolFinalAnswer(READ_FILE_TOOL_NAME, userMessage, serializeArguments(arguments), toolResult,
                streamRequest, callback, streamErrorNotified); // 工具结果最终回答统一走DSML泄漏检测和安全兜底。
    }

    private void streamWithForcedSummarizeArticle(String userMessage,
                                                  ToolCallingRequestContext requestContext,
                                                  ToolCallingStreamCallback callback) { // 流式接口的后端强制summarizeArticle闭环。
        log.info("[ToolChatStream] force execute tool: {}", SUMMARIZE_ARTICLE_TOOL_NAME); // 打印强制执行的工具名。
        log.info("[ToolChatStream] rag search status: {}", RagSearchStatus.NOT_SEARCHED); // 强制总结分支没有执行ragSearch。
        AiTool summarizeTool = toolRegistry.getTool(SUMMARIZE_ARTICLE_TOOL_NAME); // 从ToolRegistry获取SummarizeArticleTool。
        if (summarizeTool == null) { // summarizeArticle未注册时不能继续伪造结果。
            callback.onToken("系统错误：summarizeArticle 工具未注册。"); // 通过SSE把明确错误返回前端。
            callback.onComplete(); // 结束本轮流式输出，避免前端一直等待。
            return; // 工具缺失时不再执行后续流程。
        }

        Long articleId = resolveArticleIdFromMessage(userMessage); // 从当前输入中提取显式文章ID，可能为空。
        String summaryType = resolveSummaryType(userMessage); // 从当前输入中解析总结类型。
        log.info("[ToolChatStream] force summarize articleId: {}", articleId); // 打印强制总结解析出的articleId。
        log.debug("[ToolChatStream] resolved summaryType: {}", summaryType); // 与下一条summaryType日志重复，降级为DEBUG。
        log.info("[ToolChatStream] force summarize summaryType: {}", summaryType); // 打印强制总结解析出的summaryType。

        ObjectNode arguments = objectMapper.createObjectNode(); // 构造summarizeArticle工具入参。
        if (articleId != null) { // 有显式ID时传给工具。
            arguments.put("articleId", articleId); // 写入articleId。
        }
        arguments.put("summaryType", summaryType); // 写入summaryType，缺省为normal。

        String toolResult = executeToolWithLog(summarizeTool, arguments, requestContext, CALL_SOURCE_FORCE_ROUTE, ROUTE_REASON_FORCE_SUMMARIZE_ARTICLE); // 执行总结工具并记录工具调用日志。
        logToolResult(toolResult); // 打印工具结果，article_summary场景隐藏完整summary。
        JsonNode articleSummaryNode = readArticleSummaryNode(toolResult); // 解析article_summary结果。
        boolean success = articleSummaryNode != null && articleSummaryNode.path("success").asBoolean(false); // 读取工具执行成功状态。
        log.info("[ToolChatStream] force summarize result success: {}", success); // 打印强制总结结果状态。
        if (isArticleSummaryResult(toolResult)) { // 成功且summary非空时发送summary_result业务事件。
            log.info("[ToolChatStream] force summarize emit summary_result"); // 标记发送summary_result。
            callback.onToolEvent(SUMMARY_RESULT_EVENT_NAME, toolResult); // 完整summary只通过SSE事件交给前端弹窗。
        }

        String chatMessage = readChatMessageFromToolResult(toolResult); // 只读取聊天短提示。
        log.info("[ToolChatStream] final chatMessage only"); // 标记聊天窗口只输出chatMessage。
        callback.onToken(chatMessage); // 聊天气泡只输出短提示，不输出完整summary。
        callback.onComplete(); // 结束本轮流式输出。
    }

    private ObjectNode buildFirstRequest(String userMessage, ToolCallingRequestContext requestContext) { // 构造第一次DeepSeek请求。
        ObjectNode request = buildBaseRequest(); // 基础请求写入model和thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 创建messages数组。
        messages.add(buildMessage("system", SYSTEM_PROMPT)); // 写入多工具system prompt。
        addAttachedFilesMessage(messages, requestContext); // 注入本轮附件元信息和未读取文件内容的边界说明。
        messages.add(buildMessage("user", userMessage)); // 写入用户消息。
        request.set("messages", messages); // 设置第一次请求消息。
        request.set("tools", toolRegistry.buildToolsJson()); // tools由注册中心根据所有AiTool统一生成。
        return request; // 返回第一次请求体。
    }

    private void streamNoToolAnswer(String userMessage,
                                    String memorySummary,
                                    List<ToolChatHistoryMessage> historyMessages,
                                    boolean followUpQuestion,
                                    ToolCallingRequestContext requestContext,
                                    boolean attachmentRagBlocked,
                                    ToolCallingStreamCallback callback,
                                    boolean[] streamErrorNotified) { // 无工具流式回答，附件RAG拦截场景也复用这里。
        ObjectNode noToolStreamRequest = buildNoToolStreamRequest(userMessage, memorySummary, historyMessages,
                followUpQuestion, requestContext, attachmentRagBlocked); // 构造不带tools的DeepSeek流式请求。
        log.debug("[ToolChatStream] before no-tool second DeepSeek stream call"); // 普通流式调用细节降级为DEBUG。
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
                if (streamErrorNotified != null && streamErrorNotified.length > 0) { // 兼容旧调用方没有传标记数组的情况。
                    streamErrorNotified[0] = true; // 标记底层已通知错误，避免外层catch重复通知。
                }
                callback.onError(error); // 透传给上层SSE错误处理。
            }
        });
        log.debug("[ToolChatStream] after no-tool second DeepSeek stream call"); // 普通流式调用细节降级为DEBUG。
    }

    private ObjectNode buildNoToolStreamRequest(String userMessage,
                                                String memorySummary,
                                                List<ToolChatHistoryMessage> historyMessages,
                                                boolean followUpQuestion,
                                                ToolCallingRequestContext requestContext,
                                                boolean attachmentRagBlocked) { // 构造无工具场景的第二次流式请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，保持deepseek-v4-pro配置一致。
        ArrayNode messages = objectMapper.createArrayNode(); // 只构造普通对话messages。
        messages.add(buildMessage("system", buildNoToolSystemPrompt(followUpQuestion))); // 明确本轮没有工具和知识库结果，追问时强化历史指代理解。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只作为更早对话背景，不代表本轮检索结果。
        addHistoryMessages(messages, historyMessages); // 历史只用于普通多轮追问理解。
        addAttachedFilesMessage(messages, requestContext); // 附件只作为元信息上下文，不代表已读取文件内容。
        if (attachmentRagBlocked) { // 模型误选ragSearch但被附件意图拦截。
            messages.add(buildMessage("system", buildAttachmentRagBlockedPrompt())); // 明确当前问题应基于附件元信息回答，不走知识库。
        }
        messages.add(buildMessage("system", buildRagSearchStatusPrompt(RagSearchStatus.NOT_SEARCHED))); // 显式声明本轮未检索，禁止模型输出知识库未命中结论。
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

    private String buildRagSearchStatusPrompt(RagSearchStatus ragSearchStatus) {
        RagSearchStatus status = ragSearchStatus == null ? RagSearchStatus.NOT_SEARCHED : ragSearchStatus; // 空状态按未检索处理，避免模型误判。
        if (status == RagSearchStatus.SEARCHED_FOUND) { // 本轮确实执行ragSearch且有命中。
            return "本轮知识库检索状态：SEARCHED_FOUND。已经执行 ragSearch 且命中相关内容，可以基于工具结果说“根据你的知识库”或“根据检索到的笔记”。"; // 允许知识库来源表述。
        }
        if (status == RagSearchStatus.SEARCHED_EMPTY) { // 本轮确实执行ragSearch但无命中。
            return "本轮知识库检索状态：SEARCHED_EMPTY。已经执行 ragSearch，但本次检索未找到直接相关内容；只能说“本次知识库检索未找到直接相关内容”，禁止说“你的知识库绝对没有”或“你的笔记里没有”。"; // 限制未命中措辞。
        }
        return "本轮知识库检索状态：NOT_SEARCHED。没有执行 ragSearch；禁止说“知识库没有”“没有检索到你的知识库”“你的笔记里没有”“根据数据库没有找到”“根据你的知识库”。请按通用知识或当前对话上下文回答。"; // no-tool和非RAG工具分支禁止冒充检索。
    }

    private String buildAttachmentRagBlockedPrompt() {
        return "当前用户问题指向会话附件或刚上传的文件，不是知识库检索请求。" + // 附件RAG拦截后的额外边界。
                "\n你只能基于 attachedFiles 和历史附件元信息回答。" +
                "\n不要声称已经读取文件正文，不要输出向量库或知识库内容。" +
                "\n如果用户要求读取、总结或分析文本/代码文件正文，应调用 readFile 工具；PDF、Word、图片暂不支持直接读取。"; // 不读取二进制文件内容。
    }

    private ObjectNode buildForcedRagAnswerRequest(String userMessage,
                                                   String memorySummary,
                                                   List<ToolChatHistoryMessage> historyMessages,
                                                   String toolResult,
                                                   RagSearchStatus ragSearchStatus,
                                                   ToolCallingRequestContext requestContext) { // 构造后端强制ragSearch后的回答请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，避免reasoning_content问题。
        ArrayNode messages = objectMapper.createArrayNode(); // 使用普通messages承载强制RAG上下文。
        messages.add(buildMessage("system", FORCED_RAG_SYSTEM_PROMPT)); // 告诉模型后端已强制执行ragSearch，必须基于结果回答。
        messages.add(buildMessage("system", buildRagSearchStatusPrompt(ragSearchStatus))); // 明确RAG命中状态，约束最终措辞。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只辅助理解，不能替代ragSearch结果。
        addHistoryMessages(messages, historyMessages); // 历史只辅助理解指代，不能替代ragSearch结果。
        addAttachedFilesMessage(messages, requestContext); // 附件元信息只能辅助识别用户本轮选择的文件。
        messages.add(buildMessage(ROLE_USER, "原始用户问题：" + userMessage)); // 保留用户原问题，帮助模型组织最终回答。
        messages.add(buildMessage(ROLE_USER, "以下是 ragSearch 工具从用户知识库检索到的内容：\n" + toolResult
                + "\n\n请严格基于以上知识库内容回答用户问题。不要编造工具结果中不存在的信息。")); // 直接注入工具结果，避免伪造OpenAI tool message。
        request.set("messages", messages); // 不设置tools，因为工具已经由后端强制执行完成。
        return request; // 返回可用于同步或流式DeepSeek调用的请求体。
    }

    private ObjectNode buildForcedReadFileAnswerRequest(String userMessage,
                                                        String memorySummary,
                                                        List<ToolChatHistoryMessage> historyMessages,
                                                        String toolResult,
                                                        ToolCallingRequestContext requestContext) { // 构造后端强制readFile后的回答请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，避免reasoning_content问题。
        ArrayNode messages = objectMapper.createArrayNode(); // 使用普通messages承载readFile结果上下文。
        messages.add(buildMessage("system", FORCED_READ_FILE_SYSTEM_PROMPT)); // 告诉模型后端已强制执行readFile，必须基于结果回答。
        messages.add(buildMessage("system", buildRagSearchStatusPrompt(RagSearchStatus.NOT_SEARCHED))); // 明确没有执行RAG，避免冒充知识库。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只辅助理解，不能替代readFile结果。
        addHistoryMessages(messages, historyMessages); // 历史只辅助理解指代，不能替代readFile结果。
        addAttachedFilesMessage(messages, requestContext); // 附件元信息用于说明本轮选择，不替代工具结果。
        messages.add(buildMessage(ROLE_USER, "原始用户问题：" + userMessage)); // 保留用户原问题，帮助模型组织最终回答。
        messages.add(buildMessage(ROLE_USER, "以下是 readFile 工具返回结果 JSON：\n" + toolResult
                + "\n\n请严格基于以上 readFile 结果回答用户问题。success=false 时只说明失败原因；success=true 时基于 content 分析。")); // 直接注入工具结果，避免伪造OpenAI tool message。
        request.set("messages", messages); // 不设置tools，因为readFile已经由后端强制执行完成。
        return request; // 返回可用于流式DeepSeek调用的请求体。
    }

    private ObjectNode buildForcedReadProjectFileAnswerRequest(String userMessage,
                                                               String memorySummary,
                                                               List<ToolChatHistoryMessage> historyMessages,
                                                               String toolResult,
                                                               ToolCallingRequestContext requestContext) { // 构造后端强制readProjectFile后的回答请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，避免reasoning_content问题。
        ArrayNode messages = objectMapper.createArrayNode(); // 使用普通messages承载readProjectFile结果上下文。
        messages.add(buildMessage("system", FORCED_READ_PROJECT_FILE_SYSTEM_PROMPT)); // 告诉模型后端已强制执行readProjectFile，必须基于结果回答。
        messages.add(buildMessage("system", buildRagSearchStatusPrompt(RagSearchStatus.NOT_SEARCHED))); // 明确没有执行RAG，避免冒充知识库。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只辅助理解，不能替代项目文件读取结果。
        addHistoryMessages(messages, historyMessages); // 历史只辅助理解指代，不能替代项目文件读取结果。
        addAttachedFilesMessage(messages, requestContext); // 用户上传文件附件元信息不覆盖项目源码文件读取结果。
        messages.add(buildMessage(ROLE_USER, "原始用户问题：" + userMessage)); // 保留用户原问题，帮助模型组织最终回答。
        messages.add(buildMessage(ROLE_USER, "以下是 readProjectFile 工具返回结果 JSON：\n" + toolResult
                + "\n\n请严格基于以上 readProjectFile 结果回答用户问题。success=false 时只说明失败原因；success=true 时基于 content 分析项目源码文件。不要生成 patch，不要声称已经修改项目文件。")); // 直接注入工具结果，避免伪造OpenAI tool message。
        request.set("messages", messages); // 不设置tools，因为readProjectFile已经由后端强制执行完成。
        return request; // 返回可用于流式DeepSeek调用的请求体。
    }

    private ObjectNode buildForcedProjectTreeAnswerRequest(String userMessage,
                                                           String memorySummary,
                                                           List<ToolChatHistoryMessage> historyMessages,
                                                           String toolResult,
                                                           ToolCallingRequestContext requestContext) { // 构造后端强制listProjectTree后的回答请求。
        ObjectNode request = buildBaseRequest(); // 复用model和thinking disabled，避免reasoning_content问题。
        ArrayNode messages = objectMapper.createArrayNode(); // 使用普通messages承载目录树结果上下文。
        messages.add(buildMessage("system", FORCED_PROJECT_TREE_SYSTEM_PROMPT)); // 告诉模型后端已强制执行listProjectTree。
        messages.add(buildMessage("system", buildRagSearchStatusPrompt(RagSearchStatus.NOT_SEARCHED))); // 明确没有执行RAG，避免冒充知识库。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆只辅助理解，不能替代目录树工具结果。
        addHistoryMessages(messages, historyMessages); // 历史只辅助理解指代，不能替代目录树工具结果。
        addAttachedFilesMessage(messages, requestContext); // 附件元信息不覆盖项目目录树结果。
        messages.add(buildMessage(ROLE_USER, "原始用户问题：" + userMessage)); // 保留用户原问题，帮助模型组织最终回答。
        messages.add(buildMessage(ROLE_USER, "以下是 listProjectTree 工具返回结果 JSON：\n" + toolResult
                + "\n\n请严格基于以上目录树元信息回答用户问题。不要声称已经读取文件内容；success=false 时只说明失败原因。")); // 直接注入工具结果，避免伪造OpenAI tool message。
        request.set("messages", messages); // 不设置tools，因为listProjectTree已经由后端强制执行完成。
        return request; // 返回可用于流式DeepSeek调用的请求体。
    }

    private ObjectNode buildForcedSearchCodeAnswerRequest(String userMessage,
                                                          String argumentsJson,
                                                          String toolResult) { // 构造后端强制searchCode后的安全回答请求。
        return buildToolFinalAnswerRequest(userMessage, SEARCH_CODE_TOOL_NAME, argumentsJson, toolResult); // searchCode最终回答不注入历史，避免历史query污染。
    }

    private ObjectNode buildForcedAnalyzeCodeAnswerRequest(String userMessage,
                                                           String argumentsJson,
                                                           String toolResult) { // 构造后端强制analyzeCode后的安全回答请求。
        return buildToolFinalAnswerRequest(userMessage, ANALYZE_CODE_TOOL_NAME, argumentsJson, toolResult); // analyzeCode最终回答不注入历史，避免历史文件污染。
    }

    private String buildReadProjectFileFullAnswer(String toolResult) { // FULL模式下由后端确定性构造完整代码输出，避免模型省略。
        try {
            JsonNode resultNode = objectMapper.readTree(emptyJsonObjectIfBlank(toolResult)); // 解析readProjectFile返回JSON。
            if (!resultNode.path("success").asBoolean(false)) { // 工具读取失败。
                return "项目文件读取失败：" + safeLine(resultNode.path("message").asText("项目文件读取失败。")); // 只输出友好错误，不暴露绝对路径。
            }
            String path = resultNode.path("path").asText(""); // 读取相对workspace路径。
            String fileName = resultNode.path("fileName").asText(path); // 读取文件名。
            String extension = resultNode.path("extension").asText(""); // 读取扩展名。
            String markdownName = resultNode.path("markdownName").asText(""); // 优先使用 readProjectFile 识别出的 Markdown 语言名。
            String content = resultNode.path("content").asText(""); // 读取工具已安全截断的文件内容。
            boolean truncated = resultNode.path("truncated").asBoolean(false); // 判断是否截断。
            int returnedChars = resultNode.path("returnedChars").asInt(content.length()); // 读取返回字符数。
            String fence = content.contains("```") ? "````" : "```"; // 文件内容包含三反引号时使用四反引号包裹。
            StringBuilder answer = new StringBuilder(); // 构造最终回答。
            answer.append("以下是文件 `")
                    .append(safeInline(path.isBlank() ? fileName : path))
                    .append("` 的")
                    .append(truncated ? "已截断内容" : "完整内容")
                    .append("：\n\n"); // 说明当前输出范围。
            if (truncated) { // 工具结果已截断。
                answer.append("注意：文件内容较长，以下只返回前 ")
                        .append(returnedChars)
                        .append(" 个字符，内容已截断。后续可以提高限制或做分段读取。\n\n"); // 明确不是完整文件。
            }
            answer.append(fence)
                    .append(resolveMarkdownLanguage(extension, markdownName))
                    .append("\n")
                    .append(content)
                    .append("\n")
                    .append(fence); // 输出代码块，不让模型二次省略。
            return answer.toString(); // 返回最终安全回答。
        } catch (Exception e) {
            log.warn("[ToolChatStream] build readProjectFile full answer failed, error: {}", e.getMessage()); // 不打印完整工具结果。
            return "项目文件读取工具已经执行，但构造完整代码回答失败，请稍后重试。"; // 友好兜底。
        }
    }

    private void saveProjectFileFocusFromToolResult(String toolResult,
                                                    ToolCallingRequestContext requestContext,
                                                    String readMode) { // 从 readProjectFile 工具结果中提取安全元信息并保存 projectFileFocus。
        if (conversationFocusService == null || requestContext == null) { // 旧入口没有上下文或焦点服务时跳过。
            return; // 不影响工具主流程。
        }
        try {
            JsonNode resultNode = objectMapper.readTree(emptyJsonObjectIfBlank(toolResult)); // 解析 readProjectFile 返回 JSON。
            if (!resultNode.path("success").asBoolean(false)
                    || !"project_file_read".equals(resultNode.path("type").asText(""))) { // 只保存成功的项目文件读取结果。
                return; // 失败结果不切换焦点。
            }
            String path = resultNode.path("path").asText(""); // 读取 workspace 相对路径。
            if (path == null || path.isBlank()) { // 没有相对路径时不能保存焦点。
                return; // 跳过保存。
            }
            String fileName = resultNode.path("fileName").asText(""); // 读取文件名。
            String extension = resultNode.path("extension").asText(""); // 读取扩展名。
            String language = resultNode.path("language").asText(""); // 读取语言展示名。
            String markdownName = resultNode.path("markdownName").asText(""); // 读取 Markdown code fence 语言名。
            Long fileSize = resultNode.path("fileSize").isNumber() ? resultNode.path("fileSize").asLong() : null; // 读取文件大小。
            Boolean truncated = resultNode.path("truncated").isBoolean() ? resultNode.path("truncated").asBoolean() : null; // 读取截断状态。
            String resultReadMode = resultNode.path("readMode").asText(""); // 工具结果中的读取模式。
            String effectiveReadMode = resultReadMode == null || resultReadMode.isBlank() ? readMode : resultReadMode; // 优先使用工具返回的模式。
            conversationFocusService.saveProjectFileFocus(requestContext.getUserId(),
                    requestContext.getConversationId(),
                    path,
                    fileName,
                    extension,
                    language,
                    markdownName,
                    fileSize,
                    truncated,
                    effectiveReadMode,
                    READ_PROJECT_FILE_TOOL_NAME); // 写入 Redis projectFileFocus，不保存文件内容。
            requestContext.setProjectFileFocus(conversationFocusService.getProjectFileFocus(requestContext.getUserId(),
                    requestContext.getConversationId())); // 同一轮内刷新上下文焦点，后续日志和调用可读到最新值。
            requestContext.setRecentProjectTarget(conversationFocusService.getRecentProjectTarget(requestContext.getUserId(),
                    requestContext.getConversationId())); // 同一轮内刷新最近明确项目目标，后续“它/这个类”优先使用。
        } catch (Exception e) {
            log.warn("[ProjectFileFocus] save from tool result failed, userId: {}, conversationId: {}, error: {}",
                    requestContext.getUserId(), requestContext.getConversationId(), e.getMessage(), e); // 只记录归属ID和错误，不打印文件内容。
        }
    }

    private void saveRecentProjectTargetFromSearchCodeResult(String userMessage,
                                                             JsonNode argumentsNode,
                                                             String toolResult,
                                                             ToolCallingRequestContext requestContext) { // 从 searchCode 唯一真实文件命中中保存 recentProjectTarget。
        if (conversationFocusService == null || requestContext == null) { // 旧入口没有上下文或焦点服务时跳过。
            return; // 不影响 searchCode 主流程。
        }
        try {
            JsonNode resultNode = objectMapper.readTree(emptyJsonObjectIfBlank(toolResult)); // 解析 searchCode 返回 JSON。
            if (!resultNode.path("success").asBoolean(false)
                    || !"code_search".equals(resultNode.path("type").asText(""))) { // 只处理成功的项目代码搜索结果。
                return; // 失败结果不更新最近目标。
            }
            RecentProjectTargetCandidate candidate = resolveUniqueRecentProjectTargetCandidate(userMessage, argumentsNode, resultNode); // 从搜索结果中挑出唯一文件。
            if (candidate == null || candidate.path() == null || candidate.path().isBlank()) { // 多候选或无文件时不更新。
                return; // 避免“它”指错旧搜索结果。
            }
            conversationFocusService.saveRecentProjectTarget(requestContext.getUserId(),
                    requestContext.getConversationId(),
                    candidate.path(),
                    candidate.fileName(),
                    deriveClassNameFromProjectPath(candidate.path()),
                    candidate.targetType(),
                    SEARCH_CODE_TOOL_NAME,
                    "UNIQUE"); // searchCode 唯一命中写入 recentProjectTarget，不保存文件内容。
            requestContext.setRecentProjectTarget(conversationFocusService.getRecentProjectTarget(requestContext.getUserId(),
                    requestContext.getConversationId())); // 同一轮内刷新最近项目目标。
        } catch (Exception e) {
            log.warn("[RecentProjectTarget] save from searchCode result failed, userId: {}, conversationId: {}, error: {}",
                    requestContext.getUserId(), requestContext.getConversationId(), e.getMessage(), e); // 只记录归属ID和错误，不打印搜索结果全文。
        }
    }

    private RecentProjectTargetCandidate resolveUniqueRecentProjectTargetCandidate(String userMessage,
                                                                                  JsonNode argumentsNode,
                                                                                  JsonNode resultNode) { // 判断 searchCode 是否唯一命中一个真实项目文件。
        JsonNode results = resultNode == null ? null : resultNode.path("results"); // 读取结果数组。
        if (results == null || !results.isArray() || results.isEmpty()) { // 没有结果。
            return null; // 不更新 recentProjectTarget。
        }
        List<RecentProjectTargetCandidate> candidates = new ArrayList<>(); // 保存去重后的文件候选。
        Set<String> seenPaths = new java.util.LinkedHashSet<>(); // 按路径去重并保持搜索顺序。
        for (JsonNode item : results) { // 遍历 searchCode 命中项。
            String path = item == null ? "" : item.path("filePath").asText(""); // 读取 workspace 相对路径。
            if (!isSafeRecentProjectTargetPath(path) || !seenPaths.add(path)) { // 非项目相对路径或重复路径跳过。
                continue; // 继续下一个结果。
            }
            String fileName = item.path("fileName").asText(fileNameFromProjectPath(path)); // 读取文件名，缺失时从路径推导。
            candidates.add(new RecentProjectTargetCandidate(path, fileName, resolveRecentProjectTargetType(path))); // 保存候选。
        }
        if (candidates.size() == 1) { // 所有命中只落在一个真实文件。
            return candidates.get(0); // 唯一命中可作为最近目标。
        }
        String query = argumentsNode == null ? "" : argumentsNode.path("query").asText(""); // 读取 searchCode query。
        List<RecentProjectTargetCandidate> strongMatches = candidates.stream()
                .filter(candidate -> isStrongSearchTargetMatch(userMessage, query, candidate.fileName(), candidate.path())) // 多结果时只允许文件名强匹配唯一目标。
                .toList(); // 收集强匹配。
        return strongMatches.size() == 1 ? strongMatches.get(0) : null; // 多个强匹配或没有强匹配都不更新。
    }

    private boolean isStrongSearchTargetMatch(String userMessage,
                                              String query,
                                              String fileName,
                                              String path) { // 判断多结果中某个文件是否与用户明确目标强相关。
        String safeFileName = fileName == null ? "" : fileName; // 文件名兜底。
        String safePath = path == null ? "" : path; // 路径兜底。
        String safeQuery = query == null ? "" : query.trim(); // query 兜底。
        if (safeQuery.isBlank() || safeFileName.isBlank()) { // 没有可比对信息。
            return false; // 不是强匹配。
        }
        String fileBaseName = safeFileName.toLowerCase(Locale.ROOT).endsWith(".java")
                ? safeFileName.substring(0, safeFileName.length() - ".java".length())
                : safeFileName; // Java 文件去扩展名后也可匹配类名。
        boolean queryMatchesFile = safeQuery.equalsIgnoreCase(safeFileName)
                || safeQuery.equalsIgnoreCase(fileBaseName)
                || safeQuery.equalsIgnoreCase(safePath); // query 精确等于文件名、类名或相对路径。
        if (queryMatchesFile) { // query 本身就是强目标。
            return true; // 强匹配。
        }
        String safeMessage = userMessage == null ? "" : userMessage; // 用户消息兜底。
        return safeMessage.contains(safeFileName) || safeMessage.contains(fileBaseName); // 用户原文明确包含文件名或类名。
    }

    private void saveProjectFileFocusFromCodeAnalysisResult(String toolResult,
                                                            ToolCallingRequestContext requestContext) { // 从 analyzeCode 结果中提取安全元信息并保存 projectFileFocus。
        if (conversationFocusService == null || requestContext == null) { // 旧入口没有上下文或焦点服务时跳过。
            return; // 不影响工具主流程。
        }
        try {
            JsonNode resultNode = objectMapper.readTree(emptyJsonObjectIfBlank(toolResult)); // 解析 analyzeCode 返回 JSON。
            if (!resultNode.path("success").asBoolean(false)
                    || !"code_analysis".equals(resultNode.path("type").asText(""))) { // 只保存成功的代码结构分析结果。
                return; // 失败结果不切换焦点。
            }
            String path = resultNode.path("path").asText(""); // 读取 workspace 相对路径。
            if (path == null || path.isBlank()) { // 没有相对路径时不能保存焦点。
                return; // 跳过保存。
            }
            String fileName = resultNode.path("fileName").asText(""); // 读取文件名。
            String extension = resultNode.path("extension").asText(""); // 读取扩展名。
            String language = resultNode.path("language").asText(""); // 读取语言展示名。
            String markdownName = CodeLanguageRegistry.resolveByExtension(extension).getMarkdownName(); // 根据扩展名补齐 Markdown 语言名。
            Long fileSize = resultNode.path("fileSize").isNumber() ? resultNode.path("fileSize").asLong() : null; // 读取文件大小。
            Boolean truncated = resultNode.path("truncated").isBoolean() ? resultNode.path("truncated").asBoolean() : null; // 读取截断状态。
            conversationFocusService.saveProjectFileFocus(requestContext.getUserId(),
                    requestContext.getConversationId(),
                    path,
                    fileName,
                    extension,
                    language,
                    markdownName,
                    fileSize,
                    truncated,
                    "ANALYZE",
                    ANALYZE_CODE_TOOL_NAME); // 写入 Redis projectFileFocus，不保存文件内容。
            requestContext.setProjectFileFocus(conversationFocusService.getProjectFileFocus(requestContext.getUserId(),
                    requestContext.getConversationId())); // 同一轮内刷新上下文焦点。
            requestContext.setRecentProjectTarget(conversationFocusService.getRecentProjectTarget(requestContext.getUserId(),
                    requestContext.getConversationId())); // analyzeCode 成功后同步刷新最近项目目标。
        } catch (Exception e) {
            log.warn("[ProjectFileFocus] save from analyzeCode result failed, userId: {}, conversationId: {}, error: {}",
                    requestContext.getUserId(), requestContext.getConversationId(), e.getMessage(), e); // 只记录归属ID和错误。
        }
    }

    private String resolveReadProjectFileModeFromArguments(JsonNode argumentsNode) { // 从模型 tool_call 参数中读取 readProjectFile 模式。
        String readMode = argumentsNode == null ? "" : argumentsNode.path("readMode").asText(""); // 读取 readMode。
        return READ_PROJECT_FILE_MODE_FULL.equalsIgnoreCase(readMode)
                ? READ_PROJECT_FILE_MODE_FULL
                : READ_PROJECT_FILE_MODE_SUMMARY; // 非 FULL 一律按 SUMMARY。
    }

    private void streamPlainText(String text,
                                 ToolCallingStreamCallback callback) { // 将后端确定性文本按小块输出，保持SSE可见流式体验。
        streamPlainText(text, callback, PLAIN_TEXT_STREAM_CHUNK_SIZE, PLAIN_TEXT_STREAM_DELAY_MS); // 使用默认分块大小和轻微间隔。
    }

    private void streamPlainText(String text,
                                 ToolCallingStreamCallback callback,
                                 int chunkSize,
                                 long delayMs) { // FULL代码块等非模型文本的通用分块输出方法。
        String safeText = text == null || text.isBlank() ? "工具已执行完成，但没有可输出内容。" : text; // 空文本兜底。
        int safeChunkSize = chunkSize <= 0 ? PLAIN_TEXT_STREAM_CHUNK_SIZE : chunkSize; // 非法chunkSize使用默认值。
        long safeDelayMs = Math.max(0L, delayMs); // 负数间隔按0处理。
        try {
            for (int start = 0; start < safeText.length(); start += safeChunkSize) { // 按固定字符数切分完整回答。
                int end = Math.min(start + safeChunkSize, safeText.length()); // 计算当前块结束位置。
                callback.onToken(safeText.substring(start, end)); // 通过SSE发送当前分块，前端会逐块追加。
                sleepPlainTextStreamDelay(safeDelayMs); // 轻微间隔，避免后端瞬间写完导致前端看起来像一次性输出。
            }
            callback.onComplete(); // 通知上层保存assistant消息并回填tool_call_log.final_answer。
        } catch (Exception e) {
            log.error("[ToolChatStream] plain text stream failed, error: {}", e.getMessage()); // 不打印完整代码内容。
            callback.onError(e); // 交给上层统一SSE错误兜底，避免连接卡死。
        }
    }

    private void sleepPlainTextStreamDelay(long delayMs) { // 后端确定性文本流式输出的轻微节流。
        if (delayMs <= 0) { // 不需要间隔。
            return; // 直接返回。
        }
        try {
            Thread.sleep(delayMs); // 短暂暂停，让SSE事件更容易被前端逐块渲染。
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标记。
            log.warn("[ToolChatStream] plain text stream delay interrupted"); // 只记录状态，不打印内容。
        }
    }

    private String resolveMarkdownLanguage(String extension, String markdownName) { // 根据工具结果或注册表生成Markdown代码块语言标记。
        if (markdownName != null && !markdownName.isBlank()) { // readProjectFile 已经识别出语言名。
            return markdownName; // 优先复用工具返回值。
        }
        String resolvedMarkdownName = CodeLanguageRegistry.resolveByExtension(extension).getMarkdownName(); // 回退到统一语言注册表。
        return "text".equals(resolvedMarkdownName) ? "" : resolvedMarkdownName; // 未知文本保留空 fence，兼容旧输出。
    }

    private void streamSafeToolFinalAnswer(String toolName,
                                           String userMessage,
                                           String argumentsJson,
                                           String toolResult,
                                           ObjectNode firstAnswerRequest,
                                           ToolCallingStreamCallback callback,
                                           boolean[] streamErrorNotified) { // 工具结果最终回答统一安全出口。
        try {
            ObjectNode answerRequest = SEARCH_CODE_TOOL_NAME.equals(toolName)
                    ? buildToolFinalAnswerRequest(userMessage, toolName, argumentsJson, toolResult)
                    : addStrictFinalAnswerPrompt(firstAnswerRequest); // searchCode不用历史，其它工具在原请求上追加严格约束。
            streamFinalAnswerWithToolResult(toolName, userMessage, argumentsJson, toolResult,
                    answerRequest, callback, streamErrorNotified); // 工具结果最终回答恢复DeepSeek SSE流式输出。
        } catch (Exception e) {
            if (streamErrorNotified != null && streamErrorNotified.length > 0 && streamErrorNotified[0]) { // 内部流式回调已经处理过错误或兜底。
                return; // 避免重复onError导致SSE状态混乱。
            }
            if (streamErrorNotified != null && streamErrorNotified.length > 0) { // 兼容外层错误通知标记。
                streamErrorNotified[0] = true; // 避免外层catch重复通知。
            }
            log.error("[ToolChatStream] safe final answer failed, toolName: {}, error: {}", toolName, e.getMessage()); // 不打印工具结果全文。
            callback.onError(e); // 兜底透传给上层SSE错误处理。
        }
    }

    private void streamFinalAnswerWithToolResult(String toolName,
                                                 String userMessage,
                                                 String argumentsJson,
                                                 String toolResult,
                                                 ObjectNode answerRequest,
                                                 ToolCallingStreamCallback callback,
                                                 boolean[] streamErrorNotified) { // 基于工具结果生成最终回答，并保持SSE流式输出。
        log.info("[ToolChatStream] stream final answer with tool result, toolName: {}", toolName); // 记录工具结果流式回答阶段。
        StringBuilder finalAnswerBuilder = new StringBuilder(); // 只累计已经发送给前端的安全文本，用于上层回填final_answer。
        StringBuilder pendingBuffer = new StringBuilder(); // 暂存可能包含DSML跨token前缀的短文本，确认安全后再发送。
        boolean[] leakDetected = {false}; // 标记本次流式回答是否已检测到内部工具协议泄漏。
        deepSeekClient.streamChatCompletions(answerRequest, new DeepSeekStreamCallback() { // 工具结果回答阶段必须使用DeepSeek SSE流。
            @Override
            public void onToken(String token) { // 收到工具结果最终回答增量token。
                if (leakDetected[0]) { // 泄漏后忽略模型后续输出。
                    return; // 不再转发任何模型token。
                }
                pendingBuffer.append(token); // 先进入安全检测缓冲区。
                if (containsToolDslLeak(buildRecentFinalAnswerText(finalAnswerBuilder, pendingBuffer))) { // 检测跨token DSML/tool_calls泄漏。
                    leakDetected[0] = true; // 标记泄漏，后续token全部忽略。
                    pendingBuffer.setLength(0); // 清空尚未发送的可疑缓冲，避免泄漏到前端。
                    log.warn("[ToolChatStream] final answer DSML leak detected during stream, toolName: {}", toolName); // 记录泄漏兜底。
                    String fallbackAnswer = buildToolDslLeakFallbackAnswer(toolName, userMessage, argumentsJson, toolResult); // 构造安全兜底回答。
                    callback.onToken(fallbackAnswer); // 异常场景允许一次性输出安全兜底。
                    finalAnswerBuilder.append(fallbackAnswer); // final_answer只保存安全文本。
                    return; // 本token处理结束。
                }
                flushSafeFinalAnswerBuffer(callback, finalAnswerBuilder, pendingBuffer, false); // 将已确认不可能组成DSML前缀的内容流式发出。
            }

            @Override
            public void onComplete() { // DeepSeek最终回答流结束。
                if (!leakDetected[0]) { // 未泄漏时 flush 剩余安全内容。
                    flushSafeFinalAnswerBuffer(callback, finalAnswerBuilder, pendingBuffer, true); // 完成时发送全部剩余文本。
                    if (finalAnswerBuilder.toString().trim().isEmpty()) { // 模型返回空内容时兜底。
                        String fallbackAnswer = buildGenericToolFallbackAnswer(toolName); // 使用短安全提示。
                        callback.onToken(fallbackAnswer); // 发送兜底。
                        finalAnswerBuilder.append(fallbackAnswer); // 纳入最终保存文本。
                    }
                }
                log.info("[ToolChatStream] final answer stream complete, toolName: {}, length: {}",
                        toolName, finalAnswerBuilder.length()); // 不打印完整finalAnswer。
                callback.onComplete(); // 通知上层保存assistant消息并回填tool_call_log.final_answer。
            }

            @Override
            public void onError(Throwable error) { // 工具结果最终回答流式调用失败。
                if (streamErrorNotified != null && streamErrorNotified.length > 0) { // 标记已经在内部处理错误。
                    streamErrorNotified[0] = true; // 避免外层重复onError。
                }
                log.error("[ToolChatStream] final answer stream failed, toolName: {}, error: {}",
                        toolName, error == null ? null : error.getMessage()); // 不打印完整工具结果。
                pendingBuffer.setLength(0); // 清空未发送缓冲。
                String fallbackAnswer = buildToolStreamErrorFallbackAnswer(toolName, userMessage, argumentsJson, toolResult); // 构造友好错误兜底。
                callback.onToken(fallbackAnswer); // 保证SSE不会卡死，前端能看到结果。
                finalAnswerBuilder.append(fallbackAnswer); // 让上层保存安全final_answer。
                callback.onComplete(); // 结束本轮SSE。
            }
        });
    }

    private void flushSafeFinalAnswerBuffer(ToolCallingStreamCallback callback,
                                            StringBuilder finalAnswerBuilder,
                                            StringBuilder pendingBuffer,
                                            boolean forceFlush) { // 将已确认安全的最终回答片段发送给前端。
        if (pendingBuffer == null || pendingBuffer.isEmpty()) { // 没有待发送内容。
            return; // 直接返回。
        }
        int keepLength = forceFlush ? 0 : resolveToolDslPrefixHoldLength(pendingBuffer.toString()); // 未完成时保留可能组成DSML标记的尾部前缀。
        int flushLength = pendingBuffer.length() - keepLength; // 可安全发送的长度。
        if (flushLength <= 0) { // 全部内容仍可能是内部协议前缀。
            return; // 等待下一个token再判断。
        }
        String safeToken = pendingBuffer.substring(0, flushLength); // 取出安全片段。
        callback.onToken(safeToken); // 继续保持SSE增量输出。
        finalAnswerBuilder.append(safeToken); // 只累计已经发送给前端的安全文本。
        pendingBuffer.delete(0, flushLength); // 删除已发送内容，保留短尾部做跨token检测。
    }

    private int resolveToolDslPrefixHoldLength(String text) { // 判断缓冲尾部是否可能是DSML标记的开头。
        if (text == null || text.isEmpty()) { // 空文本没有前缀风险。
            return 0; // 不需要保留。
        }
        String normalizedText = text.toLowerCase(Locale.ROOT); // DSML检测大小写不敏感。
        int holdLength = 0; // 默认不保留。
        for (String marker : TOOL_DSL_LEAK_MARKERS) { // 遍历所有内部工具协议标记。
            int maxLength = Math.min(marker.length() - 1, normalizedText.length()); // 只检查完整标记之前的前缀。
            for (int length = 1; length <= maxLength; length++) { // 从1字符前缀开始检查。
                String suffix = normalizedText.substring(normalizedText.length() - length); // 取当前缓冲尾部。
                if (marker.startsWith(suffix)) { // 尾部可能和下一token组成泄漏标记。
                    holdLength = Math.max(holdLength, length); // 保留最长可疑前缀。
                }
            }
        }
        return holdLength; // 返回需要暂存不发送的尾部长度。
    }

    private String buildRecentFinalAnswerText(StringBuilder finalAnswerBuilder,
                                              StringBuilder pendingBuffer) { // 构造最近窗口文本用于跨token泄漏检测。
        String emittedText = finalAnswerBuilder == null ? "" : finalAnswerBuilder.toString(); // 已发送文本。
        int startIndex = Math.max(0, emittedText.length() - TOOL_DSL_ROLLING_BUFFER_LIMIT); // 只取最近200字符。
        String recentEmittedText = emittedText.substring(startIndex); // 已发送内容的最近窗口。
        String pendingText = pendingBuffer == null ? "" : pendingBuffer.toString(); // 未发送缓冲。
        return recentEmittedText + pendingText; // 合并后检测跨边界泄漏。
    }

    private String buildToolDslLeakFallbackAnswer(String toolName,
                                                  String userMessage,
                                                  String argumentsJson,
                                                  String toolResult) { // DSML泄漏时生成不会暴露内部格式的安全回答。
        if (SEARCH_CODE_TOOL_NAME.equals(toolName)) { // searchCode可以解析结构化结果生成稳定摘要。
            log.warn("[ToolChatStream] use deterministic fallback answer, toolName: searchCode"); // 记录searchCode兜底。
            return "工具已执行成功，但模型生成回答时出现内部格式异常。我根据工具结果重新整理如下：\n\n"
                    + buildSearchCodeFallbackAnswer(userMessage, argumentsJson, toolResult); // 使用代码搜索确定性摘要。
        }
        return "工具 `" + safeInline(toolName) + "` 已经执行完成，但模型生成回答时出现内部格式异常。请稍后重试，或让我重新整理本次工具结果。"; // 其它工具短兜底。
    }

    private String buildToolStreamErrorFallbackAnswer(String toolName,
                                                      String userMessage,
                                                      String argumentsJson,
                                                      String toolResult) { // 模型流式生成失败时返回友好兜底。
        if (SEARCH_CODE_TOOL_NAME.equals(toolName)) { // searchCode即使模型失败也能从result_json整理结果。
            log.warn("[ToolChatStream] use deterministic fallback answer, toolName: searchCode"); // 记录searchCode兜底。
            return buildSearchCodeFallbackAnswer(userMessage, argumentsJson, toolResult); // 返回确定性摘要。
        }
        return "工具 `" + safeInline(toolName) + "` 已经执行完成，但模型生成最终回答失败，请稍后重试。"; // 不暴露DeepSeek内部异常。
    }

    private ObjectNode addStrictFinalAnswerPrompt(ObjectNode request) { // 给已有工具结果回答请求追加禁止DSML的系统提示。
        ObjectNode safeRequest = request == null ? buildBaseRequest() : request.deepCopy(); // 避免修改调用方原始请求。
        JsonNode messagesNode = safeRequest.path("messages"); // 读取已有messages。
        ArrayNode safeMessages = objectMapper.createArrayNode(); // 重建messages，保证严格提示排在最前面。
        safeMessages.add(buildMessage("system", TOOL_FINAL_ANSWER_STRICT_SYSTEM_PROMPT)); // 最前置工具结果阶段硬约束。
        if (messagesNode.isArray()) { // 保留原有工具结果上下文。
            for (JsonNode messageNode : messagesNode) { // 逐条复制原messages。
                safeMessages.add(messageNode); // 追加原消息。
            }
        }
        safeRequest.set("messages", safeMessages); // 覆盖为带强约束的messages。
        safeRequest.remove("tools"); // 最终回答阶段禁用工具列表。
        return safeRequest; // 返回安全请求。
    }

    private ObjectNode buildToolFinalAnswerRequest(String userMessage,
                                                   String toolName,
                                                   String argumentsJson,
                                                   String toolResult) { // 构造最小上下文的工具结果最终回答请求。
        ObjectNode request = buildBaseRequest(); // 复用模型和thinking配置。
        ArrayNode messages = objectMapper.createArrayNode(); // 只放当前问题、当前工具参数和当前工具结果。
        messages.add(buildMessage("system", TOOL_FINAL_ANSWER_STRICT_SYSTEM_PROMPT)); // 工具结果阶段禁止继续tool_call。
        if (SEARCH_CODE_TOOL_NAME.equals(toolName)) { // searchCode追加专项规则。
            messages.add(buildMessage("system", SEARCH_CODE_FINAL_ANSWER_SYSTEM_PROMPT)); // 强制围绕当前query总结命中结果。
        }
        if (ANALYZE_CODE_TOOL_NAME.equals(toolName)) { // analyzeCode追加专项规则。
            messages.add(buildMessage("system", ANALYZE_CODE_FINAL_ANSWER_SYSTEM_PROMPT)); // 强制按照 analysisType 总结统一代码分析结果。
        }
        messages.add(buildMessage(ROLE_USER, "当前用户问题：\n" + safePromptText(userMessage))); // 当前问题优先。
        messages.add(buildMessage(ROLE_USER, "当前工具名称：\n" + safePromptText(toolName))); // 当前工具名。
        messages.add(buildMessage(ROLE_USER, "当前工具参数 arguments_json：\n" + safePromptText(argumentsJson))); // 当前工具参数。
        messages.add(buildMessage(ROLE_USER, "当前工具结果 result_json：\n" + safePromptText(toolResult))); // 当前工具结果。
        request.set("messages", messages); // 不设置tools，防止最终回答阶段再次调用工具。
        return request; // 返回最终回答请求。
    }

    private boolean containsToolDslLeak(String text) { // 检测最终回答是否泄漏内部工具调用DSL。
        if (text == null || text.isBlank()) { // 空文本不算DSL泄漏。
            return false; // 返回false。
        }
        String normalizedText = text.toLowerCase(Locale.ROOT); // 大小写不敏感检测。
        for (String marker : TOOL_DSL_LEAK_MARKERS) { // 统一使用内部协议标记列表。
            if (normalizedText.contains(marker)) { // 命中任一内部工具调用格式。
                return true; // 视为泄漏。
            }
        }
        return false; // 未命中内部工具调用格式。
    }

    private String buildSearchCodeFallbackAnswer(String userMessage,
                                                 String argumentsJson,
                                                 String resultJson) { // searchCode模型回答不稳定时的确定性后端摘要。
        try {
            JsonNode argumentsNode = objectMapper.readTree(emptyJsonObjectIfBlank(argumentsJson)); // 解析工具参数。
            JsonNode resultNode = objectMapper.readTree(emptyJsonObjectIfBlank(resultJson)); // 解析工具结果。
            String query = firstNonBlank(argumentsNode.path("query").asText(null), resultNode.path("query").asText(null), userMessage); // query以arguments优先。
            boolean success = resultNode.path("success").asBoolean(false); // 读取工具成功状态。
            if (!success) { // 工具失败时只返回错误原因。
                String message = firstNonBlank(resultNode.path("message").asText(null), "代码搜索执行失败，请稍后重试。"); // 读取失败文案。
                return "代码搜索工具执行失败：" + message; // 不输出内部JSON。
            }
            int resultCount = resultNode.path("resultCount").asInt(resultNode.path("results").size()); // 读取命中数量。
            if (isProjectToolInventoryIntent(userMessage)) { // 工具清单/工具存在性场景使用专用确定性摘要。
                return buildProjectToolInventoryFallbackAnswer(userMessage, resultNode, resultCount); // 避免模型把ToolCallLog误当工具本体。
            }
            if (resultCount <= 0) { // 无命中。
                return "未在项目代码中找到与 `" + safeInline(query) + "` 相关的匹配结果。"; // 友好无结果提示。
            }
            StringBuilder answer = new StringBuilder(); // 构造摘要。
            answer.append("我在项目中找到了 `").append(safeInline(query)).append("` 相关代码，共 ")
                    .append(resultCount).append(" 处匹配。下面列出前几处："); // 概览命中数量。
            JsonNode resultsNode = resultNode.path("results"); // 读取结果数组。
            int limit = resultsNode.isArray() ? Math.min(resultsNode.size(), 8) : 0; // 最多列8条，避免刷屏。
            for (int i = 0; i < limit; i++) { // 输出前几条结果。
                JsonNode item = resultsNode.get(i); // 当前结果。
                answer.append("\n\n").append(i + 1).append(". ")
                        .append(safeInline(item.path("filePath").asText("-")))
                        .append(":")
                        .append(item.path("lineNumber").asInt(0))
                        .append("\n   匹配类型：")
                        .append(safeInline(item.path("matchType").asText("-")))
                        .append("\n   命中：")
                        .append(safeLine(item.path("matchedLine").asText("-"))); // 只输出命中行，不输出完整文件。
            }
            if (resultNode.path("stoppedEarly").asBoolean(false)) { // 扫描或结果被截断。
                answer.append("\n\n结果已截断，可以缩小 path 或提高 maxResults 后继续搜索。"); // 截断提示。
            }
            answer.append("\n\n如果你要继续看某个文件的完整内容，可以让我读取对应文件。"); // 下一步提示。
            return answer.toString(); // 返回后端兜底摘要。
        } catch (Exception e) {
            log.warn("[ToolChatStream] build searchCode fallback failed, error: {}", e.getMessage()); // 不打印完整result_json。
            return "代码搜索工具已经执行成功，但模型生成回答时出现内部工具格式泄漏。请让我重新整理本次搜索结果。"; // 最终安全兜底。
        }
    }

    private String buildProjectToolInventoryFallbackAnswer(String userMessage,
                                                           JsonNode resultNode,
                                                           int resultCount) { // 根据searchCode结果确定性回答项目AI Tool清单或存在性问题。
        String targetToolName = resolveKnownProjectToolNameFromMessage(userMessage); // 具体工具存在性问题优先识别规范工具名。
        if (userMessage != null && userMessage.toLowerCase(Locale.ROOT).contains("toolcalllog")) { // ToolCallLog 是日志类，不是AI Tool。
            return buildToolCallLogNotToolAnswer(resultNode); // 返回日志类和工具本体的区分说明。
        }
        List<JsonNode> aiToolResults = collectAiToolImplementationResults(resultNode.path("results")); // 只保留真实AI Tool实现类。
        if (targetToolName != null) { // 用户问某个具体工具是否存在。
            JsonNode matchedTool = findAiToolResultByToolName(aiToolResults, targetToolName); // 在真实Tool实现中匹配工具名。
            if (matchedTool != null) { // 找到对应Tool实现。
                return buildSingleToolExistsAnswer(targetToolName, matchedTool); // 明确回答存在。
            }
            return "未在项目代码中找到 `" + safeInline(targetToolName) + "` 对应的 AI Tool 实现类。"
                    + "\n\n注意：ToolCallLog 相关类只代表工具调用日志，不等于 AI Tool 本体。"; // 搜不到才说明未找到。
        }
        if (aiToolResults.isEmpty()) { // 工具清单场景没有找到真实Tool实现。
            return resultCount <= 0
                    ? "本次搜索没有找到 AI Tool 实现类。建议继续搜索 `TOOL_NAME` 或 `implements AiTool`。"
                    : "本次搜索结果里没有识别到真实 AI Tool 实现类；ToolCallLog 相关类只是工具调用日志模块，不是工具本体。"; // 区分无结果和无真实Tool。
        }
        StringBuilder answer = new StringBuilder("我在项目中识别到以下 AI Tool 实现类："); // 构造工具清单回答。
        List<String> emittedToolNames = new ArrayList<>(); // 避免同一工具因多条命中重复输出。
        int index = 1; // 列表编号。
        for (JsonNode item : aiToolResults) { // 遍历真实Tool结果。
            String toolName = inferToolNameFromToolFileName(item.path("fileName").asText("")); // 根据实现类文件名推断toolName。
            if (toolName.isBlank() || emittedToolNames.contains(toolName)) { // 无法推断或已经输出过。
                continue; // 跳过重复项。
            }
            emittedToolNames.add(toolName); // 标记已输出。
            answer.append("\n\n").append(index++).append(". ").append(safeInline(item.path("fileName").asText("-")))
                    .append("\n   - toolName: ").append(safeInline(toolName))
                    .append("\n   - 路径: ").append(safeInline(item.path("filePath").asText("-")))
                    .append("\n   - 注册方式: @Component 注册为 Spring Bean，由 ToolRegistry 自动收集 AiTool Bean")
                    .append("\n   - 判断依据: 业务 Tool 实现类，继承 AbstractAiTool / 暴露 TOOL_NAME / 实现 name()"); // 输出工具本体判定依据。
        }
        answer.append("\n\n注意：ToolCallLog、ToolCallLogVO、ToolCallLogController、ToolCallLogMapper、ToolCallLogService 是工具调用日志模块，不是 AI Tool 本体。"); // 明确排除日志类。
        return answer.toString(); // 返回工具清单确定性回答。
    }

    private String buildToolCallLogNotToolAnswer(JsonNode resultNode) { // 回答ToolCallLog不是AI Tool本体。
        String path = "-"; // 默认路径占位。
        JsonNode resultsNode = resultNode == null ? null : resultNode.path("results"); // 读取搜索结果。
        if (resultsNode != null && resultsNode.isArray() && !resultsNode.isEmpty()) { // 有搜索结果时取第一条路径。
            path = resultsNode.get(0).path("filePath").asText("-"); // 只返回相对workspace路径。
        }
        return "ToolCallLog 不是 AI Tool 本体，它是工具调用日志持久化/查询相关代码。"
                + "\n\n- 相关路径: " + safeInline(path)
                + "\n- 作用: 记录 tool_name、arguments_json、result_json、final_answer 等工具调用日志"
                + "\n- 区分: 真实 AI Tool 通常是 `*Tool.java`，继承 `AbstractAiTool` 或实现 `AiTool`，并通过 `@Component` 被 `ToolRegistry` 自动注册。"; // 给出区分规则。
    }

    private List<JsonNode> collectAiToolImplementationResults(JsonNode resultsNode) { // 从searchCode结果中筛选真实AI Tool实现类。
        if (resultsNode == null || !resultsNode.isArray()) { // 缺少结果数组。
            return Collections.emptyList(); // 返回空列表。
        }
        List<JsonNode> aiToolResults = new ArrayList<>(); // 保存真实Tool实现命中。
        List<String> seenPaths = new ArrayList<>(); // 按文件路径去重。
        for (JsonNode item : resultsNode) { // 遍历搜索结果。
            if (!isAiToolImplementationResult(item)) { // 过滤ToolCallLog和非工具类。
                continue; // 跳过非工具本体。
            }
            String filePath = item.path("filePath").asText(""); // 读取相对路径。
            if (seenPaths.contains(filePath)) { // 同一个文件可能多行命中。
                continue; // 去重。
            }
            seenPaths.add(filePath); // 记录路径。
            aiToolResults.add(item); // 保存真实Tool。
        }
        return aiToolResults; // 返回真实Tool实现列表。
    }

    private boolean isAiToolImplementationResult(JsonNode item) { // 判断单条搜索结果是否指向真实AI Tool实现类。
        if (item == null || item.isMissingNode() || item.isNull()) { // 无效节点。
            return false; // 返回false。
        }
        String fileName = item.path("fileName").asText(""); // 读取文件名。
        String filePath = item.path("filePath").asText("").replace('\\', '/'); // 读取相对路径。
        String evidence = (item.path("matchedLine").asText("") + "\n" + item.path("snippet").asText("")).toLowerCase(Locale.ROOT); // 用命中行和snippet判断特征。
        if (fileName.startsWith("ToolCallLog") || filePath.toLowerCase(Locale.ROOT).contains("toolcalllog")) { // 日志类不是工具本体。
            return false; // 排除ToolCallLog相关类。
        }
        if (!fileName.endsWith("Tool.java")) { // 真实业务工具当前统一以Tool.java结尾。
            return false; // 非工具实现类。
        }
        return filePath.contains("/tool/")
                || evidence.contains("extends abstractaitool")
                || evidence.contains("implements aitool")
                || evidence.contains("tool_name")
                || evidence.contains("@component")
                || resolveKnownProjectToolNameFromFileName(fileName) != null; // 满足任一工具本体特征即可。
    }

    private JsonNode findAiToolResultByToolName(List<JsonNode> aiToolResults,
                                                String targetToolName) { // 在真实Tool实现中按toolName查找。
        if (aiToolResults == null || aiToolResults.isEmpty() || targetToolName == null) { // 缺少候选或目标。
            return null; // 返回未找到。
        }
        for (JsonNode item : aiToolResults) { // 遍历真实Tool实现。
            String inferredToolName = inferToolNameFromToolFileName(item.path("fileName").asText("")); // 从文件名推断工具名。
            if (targetToolName.equals(inferredToolName)) { // 命中规范工具名。
                return item; // 返回对应结果。
            }
        }
        return null; // 未找到对应工具。
    }

    private String buildSingleToolExistsAnswer(String toolName,
                                               JsonNode matchedTool) { // 构造单个工具存在性回答。
        return "`" + safeInline(toolName) + "` 是项目中的 AI Tool。"
                + "\n\n- 实现类: " + safeInline(matchedTool.path("filePath").asText("-"))
                + "\n- 工具名: " + safeInline(toolName)
                + "\n- 注册方式: 实现类使用 `@Component` 注册为 Spring Bean，`ToolRegistry` 构造时自动收集所有 `AiTool` Bean 并按 `name()` 注册"
                + "\n- 判断依据: 文件名为 `" + safeInline(matchedTool.path("fileName").asText("-")) + "`，属于业务 Tool 实现类，包含 `TOOL_NAME` / `name()` / `AbstractAiTool` 相关特征。"; // 明确回答存在。
    }

    private String inferToolNameFromToolFileName(String fileName) { // 根据Tool实现类文件名推断toolName。
        String knownToolName = resolveKnownProjectToolNameFromFileName(fileName); // 已知工具名优先。
        if (knownToolName != null) { // 命中已知工具。
            return knownToolName; // 返回规范名称。
        }
        if (fileName == null || !fileName.endsWith("Tool.java")) { // 非Tool实现类。
            return ""; // 返回空。
        }
        String className = fileName.substring(0, fileName.length() - ".java".length()); // 去掉.java。
        String baseName = className.endsWith("Tool") ? className.substring(0, className.length() - "Tool".length()) : className; // 去掉Tool后缀。
        return baseName.isBlank() ? "" : Character.toLowerCase(baseName.charAt(0)) + baseName.substring(1); // 简单转为camelCase工具名。
    }

    private String resolveKnownProjectToolNameFromFileName(String fileName) { // 从已知Tool文件名映射规范toolName。
        if ("ListProjectTreeTool.java".equals(fileName)) { // 项目目录树工具。
            return LIST_PROJECT_TREE_TOOL_NAME; // 返回listProjectTree。
        }
        if ("SearchCodeTool.java".equals(fileName)) { // 项目代码搜索工具。
            return SEARCH_CODE_TOOL_NAME; // 返回searchCode。
        }
        if ("AnalyzeCodeTool.java".equals(fileName)) { // 项目代码结构分析工具。
            return ANALYZE_CODE_TOOL_NAME; // 返回analyzeCode。
        }
        if ("ReadProjectFileTool.java".equals(fileName)) { // 项目源码文件读取工具。
            return READ_PROJECT_FILE_TOOL_NAME; // 返回readProjectFile。
        }
        if ("ReadFileTool.java".equals(fileName)) { // 用户文件读取工具。
            return READ_FILE_TOOL_NAME; // 返回readFile。
        }
        if ("RagSearchTool.java".equals(fileName)) { // 知识库检索工具。
            return RAG_SEARCH_TOOL_NAME; // 返回ragSearch。
        }
        if ("SummarizeArticleTool.java".equals(fileName)) { // 文章总结工具。
            return SUMMARIZE_ARTICLE_TOOL_NAME; // 返回summarizeArticle。
        }
        return null; // 未知文件名。
    }

    private String buildGenericToolFallbackAnswer(String toolName) { // 非searchCode工具的安全兜底回答。
        return "工具 `" + safeInline(toolName) + "` 已经执行完成，但模型生成最终回答时出现内部工具格式泄漏。请稍后重试。"; // 不输出泄漏文本。
    }

    private String emptyJsonObjectIfBlank(String json) { // 空JSON兜底。
        return json == null || json.trim().isEmpty() ? "{}" : json; // 返回可解析对象。
    }

    private String firstNonBlank(String... values) { // 读取第一个非空文本。
        if (values == null) { // 入参为空。
            return "-"; // 兜底。
        }
        for (String value : values) { // 遍历候选值。
            if (value != null && !value.trim().isEmpty()) { // 命中非空。
                return value.trim(); // 返回清理后的文本。
            }
        }
        return "-"; // 全部为空时兜底。
    }

    private String safePromptText(String text) { // 写入模型prompt前的空值兜底。
        return text == null || text.isBlank() ? "-" : text; // 不截断工具结果，保留当前工具事实。
    }

    private String safeInline(String text) { // markdown行内展示安全处理。
        return safeLine(text).replace("`", "'"); // 避免破坏反引号格式。
    }

    private String safeLine(String text) { // 单行展示安全处理。
        if (text == null || text.isBlank()) { // 空文本兜底。
            return "-"; // 返回占位。
        }
        String normalizedText = text.replace('\n', ' ').replace('\r', ' ').trim(); // 移除换行。
        return normalizedText.length() <= 300 ? normalizedText : normalizedText.substring(0, 300) + "..."; // 限制单行长度。
    }

    private ObjectNode buildSecondRequest(String userMessage,
                                          String memorySummary,
                                          List<ToolChatHistoryMessage> historyMessages,
                                          JsonNode firstMessage,
                                          String toolCallId,
                                          String toolResult,
                                          RagSearchStatus ragSearchStatus,
                                          ToolCallingRequestContext requestContext) { // 构造第二次DeepSeek请求。
        ObjectNode request = buildBaseRequest(); // 第二次请求同样保留model和thinking disabled。
        ArrayNode messages = objectMapper.createArrayNode(); // 创建messages数组。
        messages.add(buildMessage("system", TOOL_RESULT_SYSTEM_PROMPT)); // 工具调用后的最终回答强调工具结果优先于历史。
        messages.add(buildMessage("system", buildRagSearchStatusPrompt(ragSearchStatus))); // 显式声明本轮RAG状态，避免工具外分支冒充知识库。
        addMemorySummaryMessage(messages, memorySummary); // 长期记忆作为工具结果之外的更早背景。
        addHistoryMessages(messages, historyMessages); // 历史作为工具结果之外的辅助上下文。
        addAttachedFilesMessage(messages, requestContext); // 附件元信息不覆盖工具结果，也不代表已读取文件内容。
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

    private void addAttachedFilesMessage(ArrayNode messages, ToolCallingRequestContext requestContext) {
        String attachedFilesPrompt = buildAttachedFilesPrompt(requestContext); // 构造本轮附件元信息提示。
        if (attachedFilesPrompt == null || attachedFilesPrompt.isBlank()) { // 没有附件时不追加上下文。
            return; // 保持普通聊天 prompt 不变。
        }
        messages.add(buildMessage("system", attachedFilesPrompt)); // 作为 system 边界说明注入模型上下文。
    }

    private String buildAttachedFilesPrompt(ToolCallingRequestContext requestContext) {
        List<ChatAttachedFileContext> attachedFiles = currentAttachedFiles(requestContext); // 读取本轮附件列表。
        List<ChatAttachedFileContext> recentFiles = recentAttachedFiles(requestContext); // 读取会话最近附件列表。
        ChatAttachedFileContext activeFileFocus = activeFileFocus(requestContext); // 读取最近成功readFile文件焦点。
        if (attachedFiles.isEmpty() && recentFiles.isEmpty() && activeFileFocus == null) { // 没有任何附件焦点时不生成提示。
            return null; // 返回空提示。
        }
        StringBuilder prompt = new StringBuilder(); // 构造附件元信息提示。
        if (!attachedFiles.isEmpty()) { // 本轮附件优先展示。
            prompt.append("用户本轮附带了以下文件（仅文件元信息，后端尚未读取文件内容）："); // 明确只是元信息。
            appendFileMetadataLines(prompt, attachedFiles); // 输出本轮附件安全元信息。
        }
        List<ChatAttachedFileContext> activeOnlyFiles = removeFilesById(
                activeFileFocus == null ? Collections.emptyList() : List.of(activeFileFocus),
                attachedFiles); // activeFileFocus里去掉本轮已展示文件。
        if (!activeOnlyFiles.isEmpty()) { // 当前会话有最近成功读取文件焦点。
            if (!prompt.isEmpty()) { // 本轮附件已输出时换段展示active焦点。
                prompt.append("\n\n");
            }
            prompt.append("当前会话最近成功读取的文件焦点，可优先用于“这段代码/继续优化/现在呢”等指代："); // activeFileFocus说明。
            appendFileMetadataLines(prompt, activeOnlyFiles); // 输出activeFileFocus安全元信息。
        }
        List<ChatAttachedFileContext> recentOnlyFiles = removeFilesById(
                recentFiles, mergeFilesById(attachedFiles, activeOnlyFiles)); // 最近附件里去掉本轮和active已展示文件。
        if (!recentOnlyFiles.isEmpty()) { // 当前会话有历史附件焦点。
            if (!prompt.isEmpty()) { // 本轮附件已输出时换段展示历史焦点。
                prompt.append("\n\n");
            }
            prompt.append("当前会话最近还出现过以下附件，可用于理解“这个文件/刚才那个/继续分析”等指代："); // 历史附件焦点说明。
            appendFileMetadataLines(prompt, recentOnlyFiles); // 输出最近附件安全元信息。
        }
        prompt.append("\n注意：以上只是文件元信息，不代表你已经读取了文件内容。") // 防止模型冒充已读文件。
                .append("\n如果用户要求读取、查看、总结或分析文本/代码类附件内容，应调用 readFile 工具并传入对应 fileId。") // 文本/代码文件读取走readFile。
                .append("\nreadFile 不支持 PDF、Word、图片解析；遇到不支持类型时应如实说明暂不支持直接读取。") // 给模型安全答复策略。
                .append("\n不要编造文件内容，不要声称已经打开或阅读过附件。"); // 明确禁止幻觉。
        return prompt.toString(); // 返回完整附件提示。
    }

    private void appendFileMetadataLines(StringBuilder prompt, List<ChatAttachedFileContext> files) {
        for (int i = 0; i < files.size(); i++) { // 按传入顺序输出附件元信息。
            ChatAttachedFileContext file = files.get(i); // 当前附件元信息。
            prompt.append("\n")
                    .append(i + 1)
                    .append(". fileId=")
                    .append(file == null ? null : file.getFileId())
                    .append(", 文件名=")
                    .append(safeText(file == null ? null : file.getOriginalName()))
                    .append(", 类型=")
                    .append(safeText(file == null ? null : file.getFileExt()))
                    .append(", 文件分类=")
                    .append(safeText(file == null ? null : file.getFileType()))
                    .append(", MIME=")
                    .append(safeText(file == null ? null : file.getMimeType()))
                    .append(", 大小=")
                    .append(formatFileSize(file == null ? null : file.getFileSize())); // 只输出安全展示字段。
        }
    }

    private List<ChatAttachedFileContext> removeFilesById(List<ChatAttachedFileContext> source,
                                                          List<ChatAttachedFileContext> excludedFiles) {
        if (source == null || source.isEmpty()) { // 源列表为空。
            return Collections.emptyList(); // 返回空列表。
        }
        List<Long> excludedFileIds = new ArrayList<>(); // 记录需要排除的 fileId。
        if (excludedFiles != null) { // 有排除列表时收集ID。
            for (ChatAttachedFileContext file : excludedFiles) { // 遍历本轮附件。
                if (file != null && file.getFileId() != null) { // 只记录有效ID。
                    excludedFileIds.add(file.getFileId()); // 保存 fileId。
                }
            }
        }
        List<ChatAttachedFileContext> result = new ArrayList<>(); // 构造去重结果。
        for (ChatAttachedFileContext file : source) { // 遍历最近附件。
            if (file == null || file.getFileId() == null) { // 无效附件跳过。
                continue; // 跳过空文件。
            }
            if (excludedFileIds.contains(file.getFileId())) { // 已在本轮附件展示。
                continue; // 不重复输出。
            }
            result.add(file); // 保留历史附件焦点。
        }
        return result; // 返回去掉本轮附件后的最近附件。
    }

    private boolean hasAttachedFiles(ToolCallingRequestContext requestContext) {
        return !currentAttachedFiles(requestContext).isEmpty(); // 判断本轮是否携带附件元信息。
    }

    private boolean hasRecentAttachedFiles(ToolCallingRequestContext requestContext) {
        return !recentAttachedFiles(requestContext).isEmpty(); // 判断当前会话是否有最近附件焦点。
    }

    private boolean hasActiveFileFocus(ToolCallingRequestContext requestContext) {
        return activeFileFocus(requestContext) != null; // 判断当前会话是否有最近成功读取文件焦点。
    }

    private List<ChatAttachedFileContext> currentAttachedFiles(ToolCallingRequestContext requestContext) {
        if (requestContext == null || requestContext.getAttachedFiles() == null || requestContext.getAttachedFiles().isEmpty()) { // 没有本轮附件。
            return Collections.emptyList(); // 返回空列表。
        }
        return requestContext.getAttachedFiles(); // 返回本轮附件。
    }

    private List<ChatAttachedFileContext> recentAttachedFiles(ToolCallingRequestContext requestContext) {
        if (requestContext == null || requestContext.getRecentAttachedFiles() == null || requestContext.getRecentAttachedFiles().isEmpty()) { // 没有最近附件。
            return Collections.emptyList(); // 返回空列表。
        }
        return requestContext.getRecentAttachedFiles(); // 返回会话最近附件。
    }

    private ChatAttachedFileContext activeFileFocus(ToolCallingRequestContext requestContext) {
        if (requestContext == null || requestContext.getActiveFileFocus() == null) { // 没有activeFileFocus。
            return null; // 返回null。
        }
        ChatAttachedFileContext focus = requestContext.getActiveFileFocus(); // 读取最近成功readFile焦点。
        return focus.getFileId() == null ? null : focus; // 缺少fileId时视为无效焦点。
    }

    private boolean isReadFileIntent(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) { // 空消息不触发readFile强制路由。
            return false; // 返回false。
        }
        String normalizedMessage = userMessage.trim().toLowerCase(Locale.ROOT); // 统一小写，兼容fileId和扩展名。
        if (normalizedMessage.contains("用户本轮上传了文件但没有输入文字")) { // 只发文件时的隐藏上下文不是用户真实读取请求。
            return false; // 只发文件不强制读取，仍让模型基于附件元信息回应。
        }
        boolean hasReadAction = containsAny(normalizedMessage, READ_FILE_ACTION_KEYWORDS); // 判断是否有读取/分析/总结文件正文动作。
        if (containsAny(normalizedMessage, FILE_LIST_QUERY_KEYWORDS) && !hasReadAction) { // 只问文件列表或附件列表时不读取正文。
            return false; // 返回false。
        }
        if (hasReadAction) { // 明确要求读取或分析文件内容。
            return true; // 触发readFile强制路由。
        }
        if (resolveFileIdFromMessage(userMessage) != null) { // 用户显式提到fileId，多数情况下是在要求读取具体文件。
            return true; // 触发readFile强制路由。
        }
        return containsAny(normalizedMessage, READ_FILE_SUPPORTED_EXT_KEYWORDS); // 命中文本/代码文件名后缀时也触发readFile强制路由。
    }

    private boolean isFileReferenceFollowUp(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) { // 空消息不构成文件指代追问。
            return false; // 返回false。
        }
        String normalizedMessage = userMessage.trim().toLowerCase(Locale.ROOT); // 统一小写匹配。
        return containsAny(normalizedMessage, FILE_REFERENCE_FOLLOW_UP_KEYWORDS); // 命中“这个文件/继续/现在呢”等指代词。
    }

    private boolean shouldForceReadFile(String userMessage,
                                        ToolCallingRequestContext requestContext) {
        List<ChatAttachedFileContext> fileFocus = resolveFileFocus(userMessage, requestContext); // 解析本轮或历史附件焦点。
        return (isReadFileIntent(userMessage) || isFileReferenceFollowUp(userMessage))
                && !fileFocus.isEmpty()
                && !isExplicitRagIntent(userMessage); // 读取/指代意图 + 文件焦点 + 非显式知识库 => 强制readFile。
    }

    private ForceReadFileTarget resolveForceReadFileTarget(String userMessage,
                                                           ToolCallingRequestContext requestContext) {
        List<ChatAttachedFileContext> fileFocus = resolveFileFocus(userMessage, requestContext); // 根据本轮附件和最近附件解析焦点。
        if (fileFocus.size() == 1) { // 只有一个明确焦点。
            return ForceReadFileTarget.of(fileFocus.get(0)); // 直接读取该文件。
        }
        return ForceReadFileTarget.ambiguous(fileFocus); // 多焦点或无焦点都视为不应直接读取。
    }

    private List<ChatAttachedFileContext> resolveFileFocus(String userMessage,
                                                           ToolCallingRequestContext requestContext) {
        List<ChatAttachedFileContext> currentFiles = dedupeFilesById(currentAttachedFiles(requestContext)); // 本轮附件优先。
        ChatAttachedFileContext activeFileFocus = activeFileFocus(requestContext); // 最近成功readFile文件焦点优先于历史附件。
        List<ChatAttachedFileContext> activeFiles = activeFileFocus == null
                ? Collections.emptyList()
                : List.of(activeFileFocus); // activeFileFocus最多一个。
        List<ChatAttachedFileContext> recentFiles = dedupeFilesById(recentAttachedFiles(requestContext)); // 会话最近附件作为焦点记忆。
        List<ChatAttachedFileContext> allFiles = mergeFilesById(currentFiles, mergeFilesById(activeFiles, recentFiles)); // 显式匹配在本轮、active和历史里查找。
        if (allFiles.isEmpty()) { // 没有附件上下文。
            return Collections.emptyList(); // 返回空焦点。
        }
        ChatAttachedFileContext fileById = resolveAttachedFileById(userMessage, allFiles); // 显式 fileId 优先匹配。
        if (fileById != null) { // 用户明确指向某个 fileId。
            return List.of(fileById); // 返回单文件焦点。
        }
        ChatAttachedFileContext fileByName = resolveAttachedFileByName(userMessage, allFiles); // 再按文件名匹配。
        if (fileByName != null) { // 用户明确说出文件名或唯一扩展名。
            return List.of(fileByName); // 返回单文件焦点。
        }
        if (currentFiles.size() == 1) { // 本轮只有一个附件时直接作为焦点。
            return currentFiles; // 返回本轮唯一附件。
        }
        if (!currentFiles.isEmpty()) { // 本轮多个附件时优先让上层提示用户指定，不回退到旧active焦点。
            return currentFiles; // 返回本轮多个附件。
        }
        if (!activeFiles.isEmpty()) { // 本轮没有附件时，模糊指代优先使用最近成功读取的文件焦点。
            return activeFiles; // 返回activeFileFocus，避免多附件历史反复要求指定。
        }
        return recentFiles; // 本轮没有附件且没有active焦点时，使用最近附件焦点，可能多个。
    }

    private List<ChatAttachedFileContext> mergeFilesById(List<ChatAttachedFileContext> primaryFiles,
                                                         List<ChatAttachedFileContext> secondaryFiles) {
        List<ChatAttachedFileContext> mergedFiles = new ArrayList<>(); // 合并后的文件焦点。
        List<Long> seenFileIds = new ArrayList<>(); // 记录已出现 fileId，保持顺序且避免重复。
        appendDistinctFiles(mergedFiles, seenFileIds, primaryFiles); // 先加入本轮附件。
        appendDistinctFiles(mergedFiles, seenFileIds, secondaryFiles); // 再加入历史最近附件。
        return mergedFiles; // 返回合并结果。
    }

    private List<ChatAttachedFileContext> dedupeFilesById(List<ChatAttachedFileContext> files) {
        List<ChatAttachedFileContext> dedupedFiles = new ArrayList<>(); // 去重后的文件列表。
        List<Long> seenFileIds = new ArrayList<>(); // 记录已出现 fileId。
        appendDistinctFiles(dedupedFiles, seenFileIds, files); // 执行去重。
        return dedupedFiles; // 返回去重结果。
    }

    private void appendDistinctFiles(List<ChatAttachedFileContext> target,
                                     List<Long> seenFileIds,
                                     List<ChatAttachedFileContext> source) {
        if (source == null || source.isEmpty()) { // 源列表为空时不处理。
            return; // 直接返回。
        }
        for (ChatAttachedFileContext file : source) { // 遍历候选文件。
            if (file == null || file.getFileId() == null) { // 无效附件跳过。
                continue; // 跳过空记录。
            }
            if (seenFileIds.contains(file.getFileId())) { // 已出现过该 fileId。
                continue; // 保留第一次出现的上下文。
            }
            seenFileIds.add(file.getFileId()); // 标记已加入。
            target.add(file); // 追加到目标列表。
        }
    }

    private void logResolvedFileFocus(List<ChatAttachedFileContext> fileFocus) {
        if (fileFocus == null || fileFocus.isEmpty()) { // 没有文件焦点。
            return; // 不输出额外日志。
        }
        int logCount = Math.min(fileFocus.size(), 3); // 最多打印前三个文件焦点，避免日志过长。
        for (int i = 0; i < logCount; i++) { // 遍历少量焦点。
            ChatAttachedFileContext file = fileFocus.get(i); // 当前文件焦点。
            log.info("[ToolChatStream] resolved file focus: fileId={}, fileName={}",
                    file == null ? null : file.getFileId(),
                    file == null ? null : safeText(file.getOriginalName())); // 只打印ID和原始名，不打印路径或内容。
        }
    }

    private ChatAttachedFileContext resolveAttachedFileById(String userMessage,
                                                            List<ChatAttachedFileContext> attachedFiles) {
        Long fileId = resolveFileIdFromMessage(userMessage); // 从输入中提取显式fileId。
        if (fileId == null) { // 没有显式fileId。
            return null; // 交给文件名或单附件规则。
        }
        for (ChatAttachedFileContext file : attachedFiles) { // 只在本轮attachedFiles内匹配，避免跨用户或跨上下文读取。
            if (file != null && fileId.equals(file.getFileId())) { // 命中本轮附件。
                return file; // 返回匹配文件。
            }
        }
        return null; // 显式fileId不属于本轮附件时不读取。
    }

    private ChatAttachedFileContext resolveAttachedFileByName(String userMessage,
                                                              List<ChatAttachedFileContext> attachedFiles) {
        String normalizedMessage = normalizeForFileMatch(userMessage); // 标准化用户输入。
        if (normalizedMessage.isEmpty()) { // 空输入无法匹配文件名。
            return null; // 返回空。
        }
        for (ChatAttachedFileContext file : attachedFiles) { // 优先匹配完整原始文件名。
            String normalizedFileName = normalizeForFileMatch(file == null ? null : file.getOriginalName()); // 标准化原始文件名。
            if (!normalizedFileName.isEmpty() && normalizedMessage.contains(normalizedFileName)) { // 用户明确说出了文件名。
                return file; // 返回匹配文件。
            }
        }
        return resolveUniqueFileByExtension(normalizedMessage, attachedFiles); // 用户只说扩展名时，只有唯一同扩展名附件才匹配。
    }

    private ChatAttachedFileContext resolveUniqueFileByExtension(String normalizedMessage,
                                                                 List<ChatAttachedFileContext> attachedFiles) {
        ChatAttachedFileContext matchedFile = null; // 保存唯一命中的文件。
        for (ChatAttachedFileContext file : attachedFiles) { // 遍历本轮附件。
            String fileExt = safeText(file == null ? null : file.getFileExt()).toLowerCase(Locale.ROOT); // 读取扩展名。
            if (fileExt.isBlank()) { // 扩展名缺失无法按后缀匹配。
                continue; // 跳过。
            }
            boolean extMatched = normalizedMessage.contains("." + fileExt)
                    || normalizedMessage.contains(" " + fileExt + " ")
                    || normalizedMessage.endsWith(" " + fileExt); // 支持“.sql”和“ sql”等轻量表达。
            if (!extMatched) { // 当前扩展名未命中。
                continue; // 尝试下一个文件。
            }
            if (matchedFile != null) { // 多个同扩展名附件都会命中。
                return null; // 不唯一时不乱读。
            }
            matchedFile = file; // 暂存唯一候选。
        }
        return matchedFile; // 唯一命中则返回，否则为null。
    }

    private Long resolveFileIdFromMessage(String currentMessage) {
        if (currentMessage == null || currentMessage.isBlank()) { // 空输入无法提取fileId。
            return null; // 返回null。
        }
        for (Pattern pattern : FILE_ID_PATTERNS) { // 逐个尝试fileId规则。
            Matcher matcher = pattern.matcher(currentMessage); // 匹配当前输入。
            if (!matcher.find()) { // 未命中当前规则。
                continue; // 尝试下一条。
            }
            try {
                return Long.parseLong(matcher.group(1)); // 返回第一个捕获到的文件ID。
            } catch (NumberFormatException e) {
                return null; // 理论上正则只捕获数字，兜底返回null。
            }
        }
        return null; // 没有显式fileId。
    }

    private String buildReadFileAmbiguousPrompt(List<ChatAttachedFileContext> focusFiles) {
        StringBuilder builder = new StringBuilder("我看到你这轮关联了多个文件，请指定要读取哪一个："); // 多附件模糊提示。
        List<ChatAttachedFileContext> safeFocusFiles = focusFiles == null ? Collections.emptyList() : focusFiles; // 获取已解析出的焦点文件。
        for (int i = 0; i < safeFocusFiles.size(); i++) { // 逐个列出安全元信息。
            ChatAttachedFileContext file = safeFocusFiles.get(i); // 当前附件。
            builder.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(safeText(file == null ? null : file.getOriginalName()))
                    .append(" (fileId=")
                    .append(file == null ? null : file.getFileId())
                    .append(", ")
                    .append(safeText(file == null ? null : file.getFileExt()))
                    .append(", ")
                    .append(formatFileSize(file == null ? null : file.getFileSize()))
                    .append(")"); // 不返回storagePath或storedName。
        }
        builder.append("\n\n目前 readFile 支持读取 txt、md、java、vue、sql、py、js、ts、json、xml、html、css、yml、yaml、properties 等文本/代码文件。"); // 告知可读取类型。
        return builder.toString(); // 返回可直接作为SSE token输出的提示。
    }

    private String normalizeForFileMatch(String text) {
        if (text == null || text.isBlank()) { // 空文本无法匹配。
            return ""; // 返回空字符串。
        }
        return text.trim()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT); // 统一路径分隔符和大小写，便于文件名匹配。
    }

    private boolean isFileAttachmentIntent(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) { // 空消息不是附件意图。
            return false; // 返回false。
        }
        String normalizedMessage = userMessage.trim().toLowerCase(Locale.ROOT); // 统一小写，兼容fileId和扩展名。
        if (containsAny(normalizedMessage, FILE_ATTACHMENT_INTENT_KEYWORDS)) { // 命中附件/上传/刚发等中文或英文关键词。
            return true; // 视为附件意图。
        }
        return containsAny(normalizedMessage, FILE_ATTACHMENT_EXT_KEYWORDS); // 命中文件扩展名时也视为附件意图。
    }

    private boolean isExplicitRagIntent(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) { // 空消息不是显式RAG意图。
            return false; // 返回false。
        }
        String normalizedMessage = userMessage.trim().toLowerCase(Locale.ROOT); // 统一小写，兼容RAG大小写。
        return containsAny(normalizedMessage, EXPLICIT_RAG_INTENT_KEYWORDS); // 明确知识库/笔记/文章/资料请求才允许RAG优先。
    }

    private boolean hasAttachmentContext(ToolCallingRequestContext requestContext) {
        return hasAttachedFiles(requestContext) || hasRecentAttachedFiles(requestContext) || hasActiveFileFocus(requestContext); // 当前上下文包含本轮附件、最近附件或activeFileFocus。
    }

    private boolean hasAttachmentContext(ToolCallingRequestContext requestContext,
                                         List<ToolChatHistoryMessage> historyMessages) {
        if (hasAttachmentContext(requestContext)) { // 本轮携带附件。
            return true; // 直接认为有附件上下文。
        }
        if (historyMessages == null || historyMessages.isEmpty()) { // 没有历史上下文。
            return false; // 没有附件上下文。
        }
        for (ToolChatHistoryMessage historyMessage : historyMessages) { // 检查历史消息是否已经注入附件元信息。
            if (historyMessage == null || historyMessage.getContent() == null) { // 空历史跳过。
                continue; // 继续下一条。
            }
            String content = historyMessage.getContent(); // 历史内容只来自后端构造，不包含文件正文。
            if (content.contains("[本条消息附件]")
                    || content.contains("用户曾发送文件附件")
                    || (content.contains("fileId=") && content.contains("文件名="))) { // 命中历史附件元信息标记。
                return true; // 历史中有附件上下文。
            }
        }
        return false; // 未发现附件上下文。
    }

    private boolean shouldBlockRagForAttachmentIntent(String userMessage,
                                                      ToolCallingRequestContext requestContext) {
        return (isFileAttachmentIntent(userMessage) || isFileReferenceFollowUp(userMessage))
                && hasAttachmentContext(requestContext)
                && !isExplicitRagIntent(userMessage); // 附件意图 + 当前附件上下文 + 非显式RAG => 阻断RAG。
    }

    private boolean shouldBlockRagForAttachmentIntent(String userMessage,
                                                      ToolCallingRequestContext requestContext,
                                                      List<ToolChatHistoryMessage> historyMessages) {
        boolean hasFileFocus = !resolveFileFocus(userMessage, requestContext).isEmpty(); // 是否能解析出明确或候选文件焦点。
        return ((isFileAttachmentIntent(userMessage) && hasAttachmentContext(requestContext, historyMessages))
                || (isFileReferenceFollowUp(userMessage) && hasFileFocus))
                && !isExplicitRagIntent(userMessage); // 附件意图 + 本轮/历史附件上下文 + 非显式RAG => 阻断RAG。
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) { // 元信息缺失时使用占位。
            return "-"; // 避免 prompt 出现 null。
        }
        String normalizedValue = value.replace('\n', ' ').replace('\r', ' ').trim(); // 去掉换行，避免文件名污染 prompt 结构。
        return normalizedValue.length() <= 120 ? normalizedValue : normalizedValue.substring(0, 120) + "..."; // 限制单个字段长度。
    }

    private String formatFileSize(Long fileSize) {
        if (fileSize == null || fileSize < 0) { // 文件大小缺失或异常时兜底。
            return "未知"; // 返回友好占位。
        }
        if (fileSize < 1024) { // 小于 1KB 时按字节展示。
            return fileSize + "B"; // 返回字节单位。
        }
        if (fileSize < 1024 * 1024) { // 小于 1MB 时按 KB 展示。
            return (fileSize / 1024) + "KB"; // 返回 KB 单位。
        }
        return (fileSize / (1024 * 1024)) + "MB"; // 大文件按 MB 展示。
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
            log.warn("[ToolChatStream] parse tool arguments failed, preview: {}", previewContent(arguments), e); // 只打印短preview，避免长参数进日志。
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
        String toolResult = executeToolWithLog(tool, argumentsNode, requestContext, CALL_SOURCE_MODEL_TOOL_CALL, ROUTE_REASON_MODEL_TOOL_CALL); // 执行具体工具并记录模型tool_call日志。
        logToolResult(toolResult); // 打印工具结果，article_summary 场景会隐藏完整 summary。
        return toolResult; // 返回工具结果供第二次流式模型调用使用。
    }

    private String executeToolWithLog(AiTool tool,
                                      JsonNode argumentsNode,
                                      ToolCallingRequestContext requestContext,
                                      String callSource,
                                      String routeReason) { // 在真实工具执行前后记录 tool_call_log，日志异常不能影响工具执行。
        String toolName = tool == null ? "" : tool.name(); // 读取工具名，空值由日志服务继续兜底。
        String toolType = resolveToolType(toolName); // 按工具名映射日志工具类型。
        String argumentsJson = serializeArguments(argumentsNode); // 序列化工具入参快照，不打印完整内容。
        Long logId = createRunningToolLog(requestContext, toolName, toolType, callSource, routeReason, argumentsJson); // 工具执行前先创建运行中日志。
        long startTime = System.currentTimeMillis(); // 记录工具执行开始时间。
        try {
            String toolResult = executeToolWithContext(tool, argumentsNode, requestContext); // 保持原有ThreadLocal上下文执行逻辑。
            long durationMs = Math.max(1L, System.currentTimeMillis() - startTime); // 计算耗时，保证成功工具记录大于0。
            markToolLogSuccess(requestContext, logId, toolName, toolResult, durationMs); // 成功时写入result_json和duration_ms。
            return toolResult; // 返回真实工具结果给原主链路。
        } catch (Exception e) {
            long durationMs = Math.max(1L, System.currentTimeMillis() - startTime); // 异常时同样记录耗时。
            markToolLogFailed(requestContext, logId, toolName, e.getMessage(), durationMs); // 失败时写入error_message和duration_ms。
            throw e; // 保持原有异常传播语义，不吞掉工具异常。
        }
    }

    private Long createRunningToolLog(ToolCallingRequestContext requestContext,
                                      String toolName,
                                      String toolType,
                                      String callSource,
                                      String routeReason,
                                      String argumentsJson) { // 创建运行中工具日志，失败时只记录warn并返回null。
        ToolCallLogRecorder recorder = resolveToolCallLogRecorder(requestContext); // 从请求上下文读取跨模块日志回调。
        if (recorder == null) { // 旧入口或普通测试未传回调时跳过日志。
            return null; // 不影响工具执行。
        }
        String traceId = requestContext == null ? null : requestContext.getTraceId(); // 读取本轮聊天traceId。
        try {
            return recorder.createRunningLog(traceId,
                    requestContext == null ? null : requestContext.getConversationId(),
                    requestContext == null ? null : requestContext.getUserId(),
                    requestContext == null ? null : requestContext.getCurrentMessage(),
                    toolName,
                    toolType,
                    callSource,
                    routeReason,
                    argumentsJson); // 委托Agent模块日志服务创建tool_call_log记录。
        } catch (Exception e) {
            log.warn("[ToolCallLog] save log failed, traceId: {}, toolName: {}", traceId, toolName, e); // 不打印完整argumentsJson。
            return null; // 日志失败不能阻断工具执行。
        }
    }

    private void markToolLogSuccess(ToolCallingRequestContext requestContext,
                                    Long logId,
                                    String toolName,
                                    String toolResult,
                                    Long durationMs) { // 标记工具执行成功，日志失败不影响主流程。
        ToolCallLogRecorder recorder = resolveToolCallLogRecorder(requestContext); // 获取日志回调。
        if (recorder == null || logId == null) { // 没有日志ID时说明创建阶段跳过或失败。
            return; // 直接跳过成功更新。
        }
        String traceId = requestContext == null ? null : requestContext.getTraceId(); // 读取traceId用于warn日志。
        try {
            recorder.markSuccess(logId, toolResult, durationMs); // 写入result_json和duration_ms。
        } catch (Exception e) {
            log.warn("[ToolCallLog] save log failed, traceId: {}, toolName: {}", traceId, toolName, e); // 不打印完整resultJson。
        }
    }

    private void markToolLogFailed(ToolCallingRequestContext requestContext,
                                   Long logId,
                                   String toolName,
                                   String errorMessage,
                                   Long durationMs) { // 标记工具执行失败，失败后仍按原异常处理继续传播。
        ToolCallLogRecorder recorder = resolveToolCallLogRecorder(requestContext); // 获取日志回调。
        if (recorder == null || logId == null) { // 没有日志ID时说明创建阶段跳过或失败。
            return; // 直接跳过失败更新。
        }
        String traceId = requestContext == null ? null : requestContext.getTraceId(); // 读取traceId用于warn日志。
        try {
            recorder.markFailed(logId, errorMessage, durationMs); // 写入error_message和duration_ms。
        } catch (Exception e) {
            log.warn("[ToolCallLog] save log failed, traceId: {}, toolName: {}", traceId, toolName, e); // 不打印完整错误上下文。
        }
    }

    private ToolCallLogRecorder resolveToolCallLogRecorder(ToolCallingRequestContext requestContext) { // 统一读取日志回调。
        return requestContext == null ? null : requestContext.getToolCallLogRecorder(); // requestContext为空时不记录日志。
    }

    private String serializeArguments(JsonNode argumentsNode) { // 将工具参数序列化为JSON快照。
        try {
            JsonNode safeArgumentsNode = argumentsNode == null ? objectMapper.createObjectNode() : argumentsNode; // null参数按空JSON对象记录。
            return objectMapper.writeValueAsString(safeArgumentsNode); // 使用Jackson生成合法JSON字符串。
        } catch (Exception e) {
            log.warn("[ToolCallLog] serialize arguments failed"); // 不打印完整arguments内容。
            return "{}"; // 序列化失败时用空JSON兜底。
        }
    }

    private String resolveToolType(String toolName) { // 按工具名映射日志工具类型。
        if (RAG_SEARCH_TOOL_NAME.equals(toolName)) { // ragSearch日志类型。
            return TOOL_TYPE_RAG; // 返回RAG。
        }
        if (SUMMARIZE_ARTICLE_TOOL_NAME.equals(toolName)) { // summarizeArticle日志类型。
            return TOOL_TYPE_SUMMARY; // 返回SUMMARY。
        }
        if (READ_FILE_TOOL_NAME.equals(toolName)) { // readFile日志类型。
            return TOOL_TYPE_FILE; // 返回FILE。
        }
        if (LIST_PROJECT_TREE_TOOL_NAME.equals(toolName)) { // listProjectTree日志类型。
            return TOOL_TYPE_PROJECT; // 返回PROJECT。
        }
        if (SEARCH_CODE_TOOL_NAME.equals(toolName)) { // searchCode日志类型。
            return TOOL_TYPE_PROJECT; // 返回PROJECT。
        }
        if (ANALYZE_CODE_TOOL_NAME.equals(toolName)) { // analyzeCode日志类型。
            return TOOL_TYPE_PROJECT; // 返回PROJECT。
        }
        if (READ_PROJECT_FILE_TOOL_NAME.equals(toolName)) { // readProjectFile日志类型。
            return TOOL_TYPE_PROJECT; // 返回PROJECT。
        }
        return TOOL_TYPE_UNKNOWN; // 其它工具统一标记UNKNOWN。
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

    private RagSearchStatus resolveRagSearchStatus(String toolName, String toolResult) {
        if (!RAG_SEARCH_TOOL_NAME.equals(toolName)) { // 只有实际执行ragSearch才允许进入SEARCHED状态。
            return RagSearchStatus.NOT_SEARCHED; // summarizeArticle或未来非RAG工具都不能声称知识库检索过。
        }
        if (toolResult == null || toolResult.isBlank()) { // ragSearch执行但结果为空，按未命中处理。
            return RagSearchStatus.SEARCHED_EMPTY; // 允许说本次检索未找到直接相关内容。
        }
        if (toolResult.contains(RAG_EMPTY_RESULT_TEXT)) { // 兼容RagSearchTool当前固定无命中文案。
            return RagSearchStatus.SEARCHED_EMPTY; // 工具明确无命中。
        }
        return RagSearchStatus.SEARCHED_FOUND; // ragSearch执行且返回非空知识片段。
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
            log.debug("[ToolChatStream] tool result type: {}", ARTICLE_SUMMARY_RESULT_TYPE); // 结果细节降级为DEBUG。
            log.debug("[ToolChatStream] tool result success: {}", articleSummaryNode.path("success").asBoolean(false)); // 成功状态细节降级为DEBUG。
            log.debug("[ToolChatStream] tool result chatMessage: {}", articleSummaryNode.path("chatMessage").asText("")); // 聊天短提示降级为DEBUG。
            log.debug("[ToolChatStream] tool result summary preview: {}", previewContent(articleSummaryNode.path("summary").asText(""))); // summary预览不能在INFO打印。
            return; // 不打印完整 JSON。
        }
        log.debug("[ToolChatStream] tool result preview: {}", previewContent(toolResult)); // 非summary工具结果也可能较长，降级为DEBUG。
    }

    private List<ToolChatHistoryMessage> normalizeHistoryMessages(List<ToolChatHistoryMessage> historyMessages) {
        int rawHistoryCount = historyMessages == null ? 0 : historyMessages.size(); // 原始历史数量只用于日志，不参与路由。
        log.debug("[ToolChatStream] raw history message count: {}", rawHistoryCount); // 历史数量属于DEBUG排查信息。
        if (historyMessages == null || historyMessages.isEmpty()) {
            log.debug("[ToolChatStream] normalized history message count: 0"); // 空历史标准化后仍为空。
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
                log.debug("[ToolChatStream] skip dirty history message, role: {}, content preview: {}",
                        historyMessage.getRole(), previewContent(normalizedContent)); // 脏历史只打印80字预览，避免污染日志。
                continue; // 系统异常、拒答、工具错误类历史不进入模型上下文。
            }
            if (normalizedContent.length() > HISTORY_CONTENT_MAX_LENGTH) {
                log.debug("[ToolChatStream] truncate long history message, role: {}, original length: {}",
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
        log.debug("[ToolChatStream] normalized history message count: {}", normalizedMessages.size()); // 历史数量属于DEBUG排查信息。
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
            log.debug("[ToolChatStream] skip dirty memory summary, content preview: {}",
                    previewContent(normalizedSummary)); // 长期记忆中出现异常/拒答类内容时整段跳过。
            return ""; // 脏长期记忆不进入模型上下文。
        }
        if (normalizedSummary.length() > MEMORY_SUMMARY_MAX_LENGTH) {
            log.debug("[ToolChatStream] truncate long memory summary, original length: {}",
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
            log.debug("[ToolChatStream] history item role: {}, content preview: {}",
                    historyMessage.getRole(), previewContent(historyMessage.getContent())); // 每条历史只打印短preview。
        }
    }

    private void logMemorySummary(String memorySummary) {
        boolean enabled = memorySummary != null && !memorySummary.isBlank(); // 长期记忆非空才算启用。
        log.debug("[ToolChatStream] memory summary enabled: {}", enabled); // 长期记忆状态降级为DEBUG。
        log.debug("[ToolChatStream] memory summary length: {}", enabled ? memorySummary.length() : 0); // 长期记忆长度降级为DEBUG。
        log.debug("[ToolChatStream] memory summary preview: {}", previewContent(memorySummary)); // memory preview不能在INFO打印。
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

    private boolean isProjectToolInventoryIntent(String userMessage) { // 判断是否询问项目AI Tool清单或某个工具是否存在。
        if (userMessage == null || userMessage.isBlank()) { // 空消息不构成工具清单意图。
            return false; // 返回false。
        }
        String normalizedMessage = userMessage.trim().toLowerCase(Locale.ROOT); // 统一小写匹配中英文工具名。
        boolean hasKnownToolName = resolveKnownProjectToolNameFromMessage(userMessage) != null; // 是否提到了已知AI Tool名。
        boolean hasToolLogName = normalizedMessage.contains("toolcalllog"); // 是否提到工具调用日志类。
        boolean hasToolWord = normalizedMessage.contains("工具") || normalizedMessage.contains("tool"); // 是否明确说工具。
        boolean hasInventoryKeyword = containsAny(normalizedMessage, PROJECT_TOOL_INVENTORY_KEYWORDS); // 是否命中工具清单/存在性表达。
        if (hasInventoryKeyword && (hasToolWord || hasKnownToolName || hasToolLogName)) { // 清单或存在性表达必须指向工具语义。
            return true; // 触发项目工具识别。
        }
        if (hasKnownToolName
                && (hasToolWord
                || normalizedMessage.contains("呢")
                || normalizedMessage.contains("有没有")
                || normalizedMessage.contains("项目中有")
                || normalizedMessage.contains("项目里有")
                || normalizedMessage.contains("是"))) { // 支持“那 searchCode 呢”这类追问。
            log.info("[ToolChatStream] project tool follow-up query: {}", resolveKnownProjectToolNameFromMessage(userMessage)); // 记录追问工具名。
            return true; // 触发项目工具识别。
        }
        return hasToolLogName && hasToolWord; // ToolCallLog 是工具吗这类问题也进入搜索，以便区分日志类和工具本体。
    }

    private String resolveProjectToolInventoryQueryFromMessage(String userMessage) { // 构造工具清单场景的searchCode query。
        String knownToolName = resolveKnownProjectToolNameFromMessage(userMessage); // 具体工具名优先。
        if (knownToolName != null) { // 用户询问某个具体工具。
            return knownToolName; // 直接搜索该工具名。
        }
        if (userMessage != null && userMessage.toLowerCase(Locale.ROOT).contains("toolcalllog")) { // 用户询问ToolCallLog是否是工具。
            return "ToolCallLog"; // 搜索日志类以便回答其不是AI Tool本体。
        }
        return "extends AbstractAiTool"; // 工具清单默认搜索真实AI Tool实现类的共同继承特征。
    }

    private String resolveKnownProjectToolNameFromMessage(String userMessage) { // 从用户输入中识别已知AI Tool工具名。
        if (userMessage == null || userMessage.isBlank()) { // 空消息没有工具名。
            return null; // 返回null。
        }
        String normalizedMessage = userMessage.toLowerCase(Locale.ROOT); // 忽略大小写匹配。
        for (String toolName : PROJECT_TOOL_KNOWN_NAMES) { // 遍历当前已知工具名。
            if (normalizedMessage.contains(toolName.toLowerCase(Locale.ROOT))) { // 命中工具名。
                return toolName; // 返回规范工具名。
            }
        }
        return null; // 未命中已知工具名。
    }

    private boolean shouldForceReadProjectFile(String message,
                                               ToolCallingRequestContext requestContext) { // 判断用户是否要求读取项目workspace源码文件。
        if (message == null || message.isBlank()) { // 空消息不能触发项目文件读取。
            return false; // 返回false。
        }
        if (isExplicitRagIntent(message)) { // 用户明确要求知识库时不抢占RAG。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写判断动作词。
        if (isCodeExplanationIntent(normalizedMessage)) { // “说明/解释/流程/面试话术”属于 analyzeCode(EXPLANATION)，不能被 readProjectFile 抢走。
            return false; // 返回false。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null) { // /chat/message、/api/xxx 是接口路径，不是源码文件路径。
            return false; // endpoint 交给 analyzeCode 的 endpoint 参数处理。
        }
        String projectFilePath = resolveReadProjectFilePathFromMessage(message); // 提取项目文件路径或文件名。
        if (projectFilePath == null || projectFilePath.isBlank()) { // 没有显式路径或文件名时尝试指代读取。
            ReadProjectFileTarget contextTarget = resolveReadProjectFileContextTarget(message, requestContext); // recentProjectTarget 优先，projectFileFocus 兜底。
            if (contextTarget == null || contextTarget.path() == null || contextTarget.path().isBlank()) { // 没有可读上下文目标。
                return false; // 返回false。
            }
            projectFilePath = contextTarget.path(); // 后续沿用统一读取判断。
        }
        if (isProjectCodeSearchOnlyIntent(normalizedMessage)) { // “在哪/相关代码/搜索”这类定位问题必须走searchCode。
            return false; // 避免“ListProjectTreeTool 在哪”误读源码文件。
        }
        boolean hasReadIntent = isReadProjectFileIntent(message, projectFilePath); // 判断是否有读取/打开/给我代码等源码读取意图。
        if (!hasReadIntent
                && isAnalyzeCodeIntent(normalizedMessage)
                && !containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)) { // 结构/字段/方法分析交给 analyzeCode，明确读取动作不被类名里的 Analyze/Structure 抢走。
            return false; // 避免 readProjectFile 抢占结构分析请求。
        }
        if (isControllerServiceChainIntent(normalizedMessage) && extractControllerEndpoint(message) != null) { // Controller→Service endpoint-only 请求不能把 /api/xxx 当作项目文件路径。
            return false; // 交给 analyzeCode(CONTROLLER_SERVICE) 的 endpoint 参数处理。
        }
        if (isToolServiceChainIntent(normalizedMessage)) { // Tool→业务组件链路请求不能被 readProjectFile 抢占。
            return false; // 交给 analyzeCode(TOOL_SERVICE) 处理。
        }
        if (!hasReadIntent && !containsProjectFilePath(message)) { // 没有读取意图且不是明确路径时不触发。
            return false; // 避免普通类名或工具名问题误读文件。
        }
        if (hasAttachmentContext(requestContext)
                && !containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                && isFilenameOnlyProjectPath(projectFilePath)
                && !isProjectRootReadableFile(projectFilePath)
                && resolveKnownProjectFilePath(projectFilePath) == null) { // 附件上下文下的普通文件名优先交给readFile，避免读取项目同名文件。
            return false; // 返回false。
        }
        return true; // 有读取动作和明确项目路径/文件名时强制readProjectFile。
    }

    private boolean shouldForceAnalyzeCode(String message,
                                           ToolCallingRequestContext requestContext) { // 判断用户是否要求分析项目源码文件结构。
        if (message == null || message.isBlank()) { // 空消息不能触发代码结构分析。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写兼容英文。
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || isProjectCodeSearchOnlyIntent(normalizedMessage)
                || containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || shouldForceListProjectTree(message)) { // 知识库、上传文件、定位、完整源码、目录树都有自己的路由。
            return false; // 返回false。
        }
        String readProjectFilePath = resolveReadProjectFilePathFromMessage(message); // 读取意图优先，避免 AnalyzeCodeTool.java 这类文件名中的 Analyze 被当成分析动作。
        if (isReadProjectFileIntent(message, readProjectFilePath)
                || resolveReadProjectFileContextTarget(message, requestContext) != null) { // 明确读取源码或读取“它/这个文件”时 analyzeCode 必须避让。
            return false; // 返回false。
        }
        if (!isAnalyzeCodeIntent(normalizedMessage)) { // 没有结构分析意图。
            return false; // 返回false。
        }
        AnalyzeCodeTarget target = resolveAnalyzeCodeTargetFromMessage(message, requestContext); // 解析明确路径、类名/文件名或当前项目文件焦点。
        return target != null && target.path() != null && !target.path().isBlank(); // 必须有可执行目标才强制 analyzeCode。
    }

    private boolean shouldForceCodeExplanation(String message,
                                               ToolCallingRequestContext requestContext) { // 判断用户是否要求生成代码说明。
        if (message == null || message.isBlank()) { // 空消息不能触发说明。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写。
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || isProjectCodeSearchOnlyIntent(normalizedMessage)) { // 知识库、附件或纯定位请求不走说明。
            return false; // 返回false。
        }
        if (!isCodeExplanationIntent(normalizedMessage)) { // 必须有“说明/解释/面试话术”等意图。
            return false; // 返回false。
        }
        if (shouldForceReadProjectFile(message, requestContext)) { // 读取源码优先级更高。
            return false; // 返回false。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null
                || extractSseEventName(message) != null
                || extractSseFrontendKeyword(message) != null
                || hasExplicitToolTarget(message)) { // endpoint / event / frontend / Tool 显式目标。
            return true; // 直接走说明。
        }
        AnalyzeCodeTarget target = resolveAnalyzeCodeTargetFromMessage(message, requestContext); // 解析类名/路径/recentProjectTarget/projectFileFocus。
        return target != null && target.path() != null && !target.path().isBlank(); // 有真实目标才强制说明。
    }

    private boolean isCodeExplanationIntent(String normalizedMessage) { // 判断说明意图关键词。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息。
            return false; // 无意图。
        }
        return containsAny(normalizedMessage, CODE_EXPLANATION_INTENT_KEYWORDS); // 命中说明/解释/面试话术。
    }

    private boolean isAnalyzeCodeIntent(String normalizedMessage) { // 判断是否是代码结构、方法、字段、接口或AI Tool信息分析意图。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有分析意图。
            return false; // 返回false。
        }
        return containsAny(normalizedMessage, ANALYZE_CODE_INTENT_KEYWORDS); // 命中结构分析关键词。
    }

    private String resolveExplanationTypeFromMessage(String message,
                                                     ToolCallingRequestContext requestContext) { // 根据用户消息和目标推断 explanationType。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 统一小写。
        if (containsAny(normalizedMessage, SSE_EVENT_CHAIN_INTENT_KEYWORDS)
                || extractSseEventName(message) != null
                || extractSseFrontendKeyword(message) != null) { // SSE/流式/事件链路。
            return "SSE_CHAIN"; // SSE 链路说明。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null) { // 明确接口路径。
            return "CONTROLLER_ENDPOINT"; // Controller 接口说明。
        }
        boolean hasToolFocus = hasRecentProjectTarget(requestContext)
                ? isRecentProjectTargetAiTool(requestContext)
                : isProjectFileFocusAiTool(requestContext); // recentProjectTarget 优先判断是否是 AI Tool。
        boolean hasToolExplanationSignal = hasExplicitToolTarget(message)
                || hasExplicitToolWord(normalizedMessage)
                || normalizedMessage.contains("execute"); // 只有明确 Tool/工具/execute 信号才进入 Tool 说明。
        if (hasToolExplanationSignal && (hasExplicitToolTarget(message) || hasToolFocus || hasExplicitToolWord(normalizedMessage))) { // 避免“说明它”在非 Tool 焦点下误入 Tool 专项。
            return "TOOL_CHAIN"; // Tool 链路说明。
        }
        if (containsAny(normalizedMessage, ANALYZE_CALL_CHAIN_INTENT_KEYWORDS)) { // 调用链/链路怎么讲。
            return "CALL_CHAIN"; // 调用链说明。
        }
        if (extractCallChainMethodName(message) != null) { // 指定方法名。
            return "METHOD"; // 方法说明。
        }
        return "AUTO"; // 普通“说明它/说明这个类”保留 AUTO，由内部生成器按真实 path 判断 CLASS 或 TOOL_CHAIN。
    }

    private String resolveExplanationDetailLevel(String message) { // 解析说明详细程度。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 统一小写。
        if (containsAny(normalizedMessage, CODE_EXPLANATION_DETAILED_KEYWORDS)) { // 详细关键词。
            return "DETAILED"; // 详细。
        }
        if (containsAny(normalizedMessage, CODE_EXPLANATION_BRIEF_KEYWORDS)) { // 简要关键词。
            return "BRIEF"; // 简要。
        }
        return "NORMAL"; // 默认标准。
    }

    private String resolveExplanationAudience(String message) { // 解析说明受众。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 统一小写。
        if (containsAny(normalizedMessage, CODE_EXPLANATION_INTERVIEW_KEYWORDS)) { // 面试关键词。
            return "INTERVIEW"; // 面试风格。
        }
        if (containsAny(normalizedMessage, CODE_EXPLANATION_BEGINNER_KEYWORDS)) { // 新手关键词。
            return "BEGINNER"; // 通俗风格。
        }
        return "DEVELOPER"; // 默认开发者说明。
    }

    private void fillFocusPathForExplanation(ToolCallingRequestContext requestContext,
                                             ObjectNode arguments,
                                             boolean requireSsePath) { // 给说明型追问补 recentProjectTarget/projectFileFocus。
        ConversationFocusContext recentTarget = resolveRecentProjectTarget(requestContext); // recentProjectTarget 优先。
        if (recentTarget != null && recentTarget.getPath() != null && !recentTarget.getPath().isBlank()
                && (!requireSsePath || isSseProjectPath(recentTarget.getPath()))) { // recentProjectTarget 可用。
            arguments.put("path", recentTarget.getPath()); // 写入相对路径。
            arguments.put("targetSource", "recentProjectTarget"); // 标记来源。
            return; // 不再回退旧 focus。
        }
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取 projectFileFocus。
        if (focus != null && focus.getPath() != null && !focus.getPath().isBlank()
                && (!requireSsePath || isSseProjectPath(focus.getPath()))) { // focus 可用。
            arguments.put("path", focus.getPath()); // 写入相对路径。
            arguments.put("targetSource", "projectFileFocus"); // 标记来源。
        }
    }

    private void markTargetSource(ObjectNode arguments, String source) { // 写入目标来源。
        if (arguments == null || source == null || source.isBlank()) { // 无来源。
            return; // 不写。
        }
        if ("recentProjectTarget".equals(source) || "projectFileFocus".equals(source) || "endpoint".equals(source)) { // 只记录有路由意义的来源。
            arguments.put("targetSource", source); // 写入 route_reason 参考字段。
        }
    }

    private boolean hasAnalyzeCodeTarget(ObjectNode arguments) { // 判断 analyzeCode 是否有任一真实目标参数。
        return arguments != null
                && (arguments.hasNonNull("path")
                || arguments.hasNonNull("className")
                || arguments.hasNonNull("methodName")
                || arguments.hasNonNull("endpoint")
                || arguments.hasNonNull("toolName")
                || arguments.hasNonNull("eventName")
                || arguments.hasNonNull("frontendKeyword")); // 任一目标即可。
    }

    private AnalyzeCodeTarget resolveAnalyzeCodeTargetFromMessage(String message,
                                                                  ToolCallingRequestContext requestContext) { // 解析 analyzeCode 目标文件。
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 复用项目文件路径、文件名、类名唯一定位能力。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()) { // 用户显式给了路径/文件名/类名。
            return new AnalyzeCodeTarget(explicitProjectPath, "explicit"); // 显式目标优先。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null
                || projectCodeTargetResolver.extractMethodName(message) != null) { // 用户给了 endpoint 或方法名属于明确目标。
            return null; // 明确目标必须走全项目扫描/searchCode，不退回 projectFileFocus。
        }
        ConversationFocusContext recentTarget = resolveRecentProjectTarget(requestContext); // 指代追问优先使用最近明确项目目标。
        if (recentTarget != null && recentTarget.getPath() != null && !recentTarget.getPath().isBlank()) { // 有最近明确项目目标。
            return new AnalyzeCodeTarget(recentTarget.getPath(), "recentProjectTarget"); // searchCode 唯一定位目标优先于旧读取焦点。
        }
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取项目源码文件焦点。
        if (focus != null && focus.getPath() != null && !focus.getPath().isBlank()) { // 有最近项目源码文件焦点。
            return new AnalyzeCodeTarget(focus.getPath(), "projectFileFocus"); // 仅无最近目标时回退项目文件焦点。
        }
        return null; // 无目标时不猜测。
    }

    private AnalyzeCodeTarget resolveControllerTargetFromMessage(String message,
                                                                 ToolCallingRequestContext requestContext) { // 解析 Controller→Service 分析的 Controller 文件目标。
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 先复用项目文件路径/类名解析，但会过滤掉 /api endpoint。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()
                && looksLikeControllerFileTarget(explicitProjectPath)) { // 只有 Controller 文件/类名才作为 path。
            return new AnalyzeCodeTarget(explicitProjectPath, "explicit"); // 显式 Controller 目标优先。
        }
        ConversationFocusContext recentTarget = resolveRecentProjectTarget(requestContext); // 指代追问优先使用最近明确项目目标。
        if (recentTarget != null && recentTarget.getPath() != null && !recentTarget.getPath().isBlank()) { // 有最近明确项目目标时不再回退旧 projectFileFocus。
            return looksLikeControllerFileTarget(recentTarget.getPath())
                    ? new AnalyzeCodeTarget(recentTarget.getPath(), "recentProjectTarget")
                    : null; // 最近目标不是 Controller 时提示用户重新指定。
        }
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取项目源码文件焦点。
        if (focus != null && focus.getPath() != null && !focus.getPath().isBlank()
                && looksLikeControllerFileTarget(focus.getPath())) { // 只允许 Controller 焦点参与 Controller→Service 链路。
            return new AnalyzeCodeTarget(focus.getPath(), "projectFileFocus"); // 返回焦点 Controller。
        }
        return null; // 没有 Controller 目标时交给 endpoint-only 或提示。
    }

    private boolean looksLikeControllerFileTarget(String path) { // 判断候选 path 是否明确指向 Controller 文件或 Controller 类名。
        if (path == null || path.isBlank()) { // 空路径无效。
            return false; // 返回false。
        }
        String normalizedPath = path.replace('\\', '/').toLowerCase(Locale.ROOT); // 统一分隔符和大小写。
        return normalizedPath.endsWith("controller.java")
                || normalizedPath.contains("/controller/")
                || normalizedPath.endsWith("controller"); // 允许 AgentController 这类类名被补成目标。
    }

    private boolean shouldForceAnalyzeToolServiceChain(String message,
                                                       ToolCallingRequestContext requestContext) { // 判断用户是否要求分析 AI Tool→业务组件链路。
        if (message == null || message.isBlank()) { // 空消息不能触发。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写兼容中英文。
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || isProjectCodeSearchOnlyIntent(normalizedMessage)
                || shouldForceListProjectTree(message)) { // 知识库、上传文件、完整源码、定位、目录树都有自己的路由。
            return false; // 返回false。
        }
        if (containsAny(normalizedMessage, TOOL_SERVICE_CHAIN_ORDINARY_CALL_CHAIN_KEYWORDS)) { // 用户明确说普通调用链时交给 analyzeCode(CALL_CHAIN)。
            return false; // 返回false。
        }
        boolean hasExplicitToolTarget = hasExplicitToolTarget(message); // 是否给了 Tool 类名、路径或已知 toolName。
        boolean hasToolFocus = hasRecentProjectTarget(requestContext)
                ? isRecentProjectTargetAiTool(requestContext)
                : isProjectFileFocusAiTool(requestContext); // 当前 recentProjectTarget 优先，缺失时才看 projectFileFocus。
        boolean hasExplicitToolWord = hasExplicitToolWord(normalizedMessage); // 是否明确出现 Tool/工具，而不是只有“它”这种泛指。
        if (isToolServiceChainIntent(normalizedMessage) && (hasExplicitToolTarget || hasToolFocus || hasExplicitToolWord)) { // Tool follow-up 意图 + Tool 目标信号。
            return true; // 触发 analyzeCode(TOOL_SERVICE)。
        }
        String knownToolName = resolveKnownProjectToolNameFromMessage(message); // 识别 searchCode/readProjectFile 等 toolName。
        return knownToolName != null
                && (normalizedMessage.contains("背后")
                || normalizedMessage.contains("调用")
                || normalizedMessage.contains("依赖")
                || normalizedMessage.contains("链路")
                || normalizedMessage.contains("组件")
                || normalizedMessage.contains("业务类")
                || normalizedMessage.contains("数据库")); // 已知 toolName + 链路/依赖语义也触发。
    }

    private boolean isToolServiceChainIntent(String normalizedMessage) { // 判断是否是 Tool→业务组件链路意图。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有意图。
            return false; // 返回false。
        }
        if (containsAny(normalizedMessage, TOOL_SERVICE_CHAIN_INTENT_KEYWORDS)) { // 明确短语优先命中，例如“工具调用链”“Tool 到 Service”。
            return true; // 返回true。
        }
        return containsAny(normalizedMessage, TOOL_SERVICE_CHAIN_REFERENCE_KEYWORDS)
                && containsAny(normalizedMessage, TOOL_SERVICE_CHAIN_COMPONENT_INTENT_KEYWORDS); // Tool 指代词 + Service/组件语义同时命中才属于专项链路。
    }

    private boolean hasExplicitToolWord(String normalizedMessage) { // 判断用户是否明确说了 Tool/工具，避免“它”在非 Tool 焦点下误触发。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有显式 Tool 词。
            return false; // 返回false。
        }
        return normalizedMessage.contains("tool") || normalizedMessage.contains("工具"); // 只有出现 Tool/工具才视为显式工具指代。
    }

    private boolean hasExplicitToolTarget(String message) { // 判断消息是否包含明确 AI Tool 目标。
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 复用项目文件/类名解析。
        if (explicitProjectPath != null && looksLikeToolFileTarget(explicitProjectPath)) { // 明确 Tool 文件或 Tool 类。
            return true; // 有明确 Tool 目标。
        }
        if (projectCodeTargetResolver.extractToolClassName(message) != null) { // 命中 XxxTool。
            return true; // 有明确 Tool 类名。
        }
        return projectCodeTargetResolver.extractToolName(message, List.of(PROJECT_TOOL_KNOWN_NAMES)) != null; // 命中已知 toolName。
    }

    private AnalyzeToolTarget resolveToolTargetFromMessage(String message,
                                                           ToolCallingRequestContext requestContext) { // 解析 analyzeCode(TOOL_SERVICE) 的 path/toolName/className 目标。
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 优先解析显式项目路径、文件名或类名。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()
                && looksLikeToolFileTarget(explicitProjectPath)) { // 只有 Tool 文件/类名才作为 path。
            return new AnalyzeToolTarget(explicitProjectPath, null, deriveClassNameFromProjectPath(explicitProjectPath), "explicit"); // 显式 Tool 文件优先。
        }
        String toolName = projectCodeTargetResolver.extractToolName(message, List.of(PROJECT_TOOL_KNOWN_NAMES)); // 识别 searchCode/readProjectFile 等工具名。
        String className = projectCodeTargetResolver.extractToolClassName(message); // 识别 SearchCodeTool 等 Tool 类名。
        if ((toolName != null && !toolName.isBlank()) || (className != null && !className.isBlank())) { // 有明确 toolName 或 className。
            return new AnalyzeToolTarget(null, toolName, className, "explicit"); // 交给工具全 workspace 定位真实 Tool 文件。
        }
        ConversationFocusContext recentTarget = resolveRecentProjectTarget(requestContext); // 指代追问优先使用最近明确项目目标。
        if (recentTarget != null) { // 有最近明确项目目标时不再回退旧 projectFileFocus。
            if (!looksLikeToolFileTarget(recentTarget.getPath())) { // 最近目标不是 Tool 文件。
                return null; // 交给上层提示用户指定 Tool。
            }
            String recentFileName = fileNameFromProjectPath(recentTarget.getPath()); // 从相对路径取文件名。
            String recentToolName = resolveKnownProjectToolNameFromFileName(recentFileName); // 尝试补齐 toolName。
            return new AnalyzeToolTarget(recentTarget.getPath(), recentToolName,
                    deriveClassNameFromProjectPath(recentTarget.getPath()), "recentProjectTarget"); // 返回最近 Tool 目标。
        }
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取项目文件焦点。
        if (isProjectFileFocusAiTool(requestContext)) { // 只允许 AI Tool 文件焦点参与 Tool 专项链路。
            String focusFileName = fileNameFromProjectPath(focus.getPath()); // projectFileFocus 只保存相对路径，文件名从路径末段推导。
            String focusToolName = resolveKnownProjectToolNameFromFileName(focusFileName); // 尝试补齐 toolName。
            return new AnalyzeToolTarget(focus.getPath(), focusToolName, deriveClassNameFromProjectPath(focus.getPath()), "projectFileFocus"); // 返回焦点 Tool。
        }
        return null; // 没有 Tool 目标。
    }

    private boolean looksLikeToolFileTarget(String path) { // 判断候选 path/className 是否明确指向 AI Tool 文件或 Tool 类。
        if (path == null || path.isBlank()) { // 空路径无效。
            return false; // 返回false。
        }
        String normalizedPath = path.replace('\\', '/'); // 统一分隔符。
        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT); // 小写判断路径。
        String className = deriveClassNameFromProjectPath(normalizedPath); // 尝试推导类名。
        return lowerPath.endsWith("tool.java")
                || lowerPath.contains("/tool/")
                || className.endsWith("Tool")
                || lowerPath.endsWith("tool"); // 支持 SearchCodeTool 这类类名。
    }

    private boolean isProjectFileFocusAiTool(ToolCallingRequestContext requestContext) { // 判断当前 projectFileFocus 是否是 AI Tool 文件。
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取焦点。
        if (focus == null || focus.getPath() == null || focus.getPath().isBlank()) { // 没有项目文件焦点时不能按 Tool 专项追问处理。
            return false; // 返回false。
        }
        return looksLikeToolFileTarget(focus.getPath()); // Tool 模块不读取源码内容，真实安全校验和 AI Tool 特征校验交给 analyzeCode(TOOL_SERVICE) 内部分析器。
    }

    private boolean isRecentProjectTargetAiTool(ToolCallingRequestContext requestContext) { // 判断当前 recentProjectTarget 是否是 AI Tool 文件。
        ConversationFocusContext target = resolveRecentProjectTarget(requestContext); // 读取最近明确项目目标。
        return target != null && looksLikeToolFileTarget(target.getPath()); // 最近目标是 Tool 文件时可处理“这个工具”追问。
    }

    private String extractToolServiceEntryMethodName(String message) { // 提取 Tool 链路分析入口方法名。
        String methodName = extractCallChainMethodName(message); // 复用调用链方法名提取规则。
        return methodName == null || methodName.isBlank() ? "execute" : methodName; // 默认 execute。
    }

    private boolean isToolServiceDepthIntent(String message) { // 判断是否需要 maxDepth=2 展开 ServiceImpl 一层。
        if (message == null || message.isBlank()) { // 空消息不需要二层。
            return false; // 返回false。
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT); // 统一小写。
        return normalizedMessage.contains("serviceimpl")
                || normalizedMessage.contains("实现类")
                || normalizedMessage.contains("内部一层")
                || normalizedMessage.contains("mapper")
                || normalizedMessage.contains("repository")
                || normalizedMessage.contains("数据库")
                || normalizedMessage.contains("最终")
                || normalizedMessage.contains("落库")
                || normalizedMessage.contains("insert")
                || normalizedMessage.contains("select")
                || normalizedMessage.contains("update")
                || normalizedMessage.contains("delete"); // 这些问题通常需要看 ServiceImpl 一层。
    }

    private boolean shouldForceAnalyzeSseEventChain(String message,
                                                    ToolCallingRequestContext requestContext) { // 判断用户是否要求分析前后端 SSE 事件链路。
        if (message == null || message.isBlank()) { // 空消息不能触发。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写兼容中英文。
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || shouldForceListProjectTree(message)) { // 知识库、上传文件、完整源码、目录树都有自己的路由。
            return false; // 返回false。
        }
        if (!containsAny(normalizedMessage, SSE_EVENT_CHAIN_INTENT_KEYWORDS)) { // 必须命中 SSE/流式/事件链路等意图词。
            return false; // 返回false。
        }
        String endpoint = projectCodeTargetResolver.extractEndpoint(message); // 提取接口路径。
        String eventName = extractSseEventName(message); // 提取事件名。
        String frontendKeyword = extractSseFrontendKeyword(message); // 提取前端关键词。
        boolean strongSse = containsAny(normalizedMessage, SSE_STRONG_SIGNAL_KEYWORDS); // 强 SSE 信号（sse/流式/事件链路/前后端）。
        // 满足以下之一：明确 endpoint / eventName / frontendKeyword、焦点是 SSE 文件、或消息出现强 SSE 信号。
        return endpoint != null || eventName != null || frontendKeyword != null || isSseFocus(requestContext) || strongSse; // 命中即路由。
    }

    private String extractSseEventName(String message) { // 从消息中提取 SSE 事件名（先识别“xxx 事件”，再识别非歧义事件名）。
        if (message == null || message.isBlank()) { // 空消息没有事件名。
            return null; // 返回null。
        }
        Matcher withWord = SSE_EVENT_WITH_WORD_PATTERN.matcher(message); // 识别“done 事件 / summary_result 事件”。
        if (withWord.find()) { // 命中。
            return withWord.group(1); // 返回事件名。
        }
        String lower = message.toLowerCase(Locale.ROOT); // 小写。
        for (String event : new String[]{"summary_result", "tool_call", "tool_result"}) { // 非歧义事件名可独立出现。
            if (lower.contains(event)) { // 命中。
                return event; // 返回事件名。
            }
        }
        return null; // message/done/error 等歧义词必须配“事件”才识别，避免误判。
    }

    private String extractSseFrontendKeyword(String message) { // 从消息中提取前端 SSE 关键词。
        if (message == null || message.isBlank()) { // 空消息没有关键词。
            return null; // 返回null。
        }
        String lower = message.toLowerCase(Locale.ROOT); // 小写。
        for (String keyword : SSE_FRONTEND_KEYWORDS) { // 遍历已知前端关键词。
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) { // 命中。
                return keyword; // 返回原始关键词。
            }
        }
        return null; // 未命中。
    }

    private boolean isSseFocus(ToolCallingRequestContext requestContext) { // 判断当前 projectFileFocus 是否是前端 SSE 文件或后端 SSE Controller/Service。
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取焦点。
        if (focus == null || focus.getPath() == null || focus.getPath().isBlank()) { // 没有焦点。
            return false; // 返回false。
        }
        return isSseProjectPath(focus.getPath()); // 复用 SSE 路径判断。
    }

    private boolean isSseProjectPath(String path) { // 判断项目相对路径是否可能参与 SSE 链路。
        if (path == null || path.isBlank()) { // 空路径不是 SSE 文件。
            return false; // 返回false。
        }
        String normalizedPath = path.toLowerCase(Locale.ROOT); // 焦点相对路径小写。
        return normalizedPath.endsWith(".vue") || normalizedPath.endsWith(".ts") || normalizedPath.endsWith(".tsx") || normalizedPath.endsWith(".js") || normalizedPath.endsWith(".jsx") // 前端 SSE 文件。
                || normalizedPath.contains("/controller/") || normalizedPath.endsWith("controller.java") // 后端 Controller。
                || normalizedPath.endsWith("serviceimpl.java") || normalizedPath.contains("chatmessage"); // 后端 SSE Service。
    }

    private boolean isSseDepthIntent(String message) { // 判断是否需要 maxDepth=2 展开 Service/Tool 一层。
        if (message == null || message.isBlank()) { // 空消息默认一层。
            return false; // 返回false。
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT); // 统一小写。
        return normalizedMessage.contains("serviceimpl")
                || normalizedMessage.contains("实现类")
                || normalizedMessage.contains("内部")
                || normalizedMessage.contains("tool")
                || normalizedMessage.contains("工具")
                || normalizedMessage.contains("完整")
                || normalizedMessage.contains("最终")
                || normalizedMessage.contains("调用了哪些 service"); // 这些问题通常需要展开一层。
    }

    private String buildToolServiceChainTargetMissingPrompt(ToolCallingRequestContext requestContext) { // Tool 链路缺目标时的提示。
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取焦点。
        if (focus != null && focus.getPath() != null && !focus.getPath().isBlank()
                && !isProjectFileFocusAiTool(requestContext)) { // 当前焦点不是 AI Tool 文件。
            return "当前项目焦点不是 AI Tool 文件，请指定要分析的 Tool，例如 SearchCodeTool 或 readProjectFile。"; // 明确说明焦点不适用。
        }
        return "请指定要分析的 AI Tool 类名、源码路径或 toolName，例如 searchCode、readProjectFile、SearchCodeTool。"; // 常规缺目标提示。
    }

    private String deriveClassNameFromProjectPath(String path) { // 从项目相对路径或文件名推导 Java 类名。
        if (path == null || path.isBlank()) { // 空路径无法推导。
            return ""; // 返回空。
        }
        String fileName = fileNameFromProjectPath(path); // 取最后一段文件名。
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".java")) { // Java 文件名。
            return fileName.substring(0, fileName.length() - ".java".length()); // 去掉扩展名。
        }
        return fileName; // 类名场景直接返回。
    }

    private String fileNameFromProjectPath(String path) { // 从项目相对路径中取文件名。
        if (path == null || path.isBlank()) { // 空路径。
            return ""; // 返回空。
        }
        String normalizedPath = path.replace('\\', '/'); // 统一斜杠。
        int slashIndex = normalizedPath.lastIndexOf('/'); // 找最后一个分隔符。
        return slashIndex >= 0 ? normalizedPath.substring(slashIndex + 1) : normalizedPath; // 返回文件名或原值。
    }

    private String resolveRecentProjectTargetType(String path) { // 根据路径给 recentProjectTarget 标记轻量目标类型。
        String normalizedPath = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT); // 统一路径小写。
        if (normalizedPath.contains("/tool/") || normalizedPath.endsWith("tool.java")) { // AI Tool 文件。
            return "TOOL"; // 返回 Tool 目标。
        }
        if (normalizedPath.contains("/controller/") || normalizedPath.endsWith("controller.java")) { // Controller 文件。
            return "CONTROLLER"; // 返回 Controller 目标。
        }
        return "CLASS_OR_FILE"; // 默认类或文件目标。
    }

    private boolean isSafeRecentProjectTargetPath(String path) { // 判断 recentProjectTarget 是否是安全 workspace 相对路径。
        if (path == null || path.isBlank()) { // 空路径无效。
            return false; // 返回false。
        }
        String normalizedPath = path.trim().replace('\\', '/'); // 统一路径分隔符。
        if (normalizedPath.startsWith("/") || normalizedPath.matches("^[A-Za-z]:/.*")) { // 绝对路径不允许进入焦点。
            return false; // 返回false。
        }
        if (normalizedPath.contains("../") || normalizedPath.equals("..") || normalizedPath.contains("/..")) { // 路径穿越不允许。
            return false; // 返回false。
        }
        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT); // 小写用于敏感目录判断。
        return !lowerPath.contains("/.git/")
                && !lowerPath.contains("/node_modules/")
                && !lowerPath.contains("/target/")
                && !lowerPath.contains("/build/")
                && !lowerPath.contains("/dist/")
                && !lowerPath.contains("/uploads/")
                && !lowerPath.contains("/data/")
                && !lowerPath.contains("/logs/")
                && !lowerPath.endsWith("/.git"); // 只保存普通项目源码/文本文件相对路径。
    }

    private boolean shouldForceAnalyzeCallChain(String message,
                                                ToolCallingRequestContext requestContext) { // 判断用户是否要求分析项目源码调用链。
        if (message == null || message.isBlank()) { // 空消息不能触发调用链分析。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写兼容英文。
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || isProjectCodeSearchOnlyIntent(normalizedMessage)
                || shouldForceListProjectTree(message)) { // 知识库、上传文件、定位、目录树都有自己的路由。
            return false; // 返回false。
        }
        if (shouldForceAnalyzeToolServiceChain(message, requestContext)) { // Tool→Service/组件专项意图优先，普通调用链必须避让。
            return false; // 返回false。
        }
        if (!isAnalyzeCallChainIntent(normalizedMessage)) { // 没有调用链分析意图。
            return false; // 返回false。
        }
        AnalyzeCodeTarget target = resolveAnalyzeCodeTargetFromMessage(message, requestContext); // 复用 analyzeCode 目标解析：明确路径、类名/文件名或当前项目文件焦点。
        return target != null && target.path() != null && !target.path().isBlank(); // 必须有可执行目标才强制 analyzeCode(CALL_CHAIN)。
    }

    private boolean isAnalyzeCallChainIntent(String normalizedMessage) { // 判断是否是调用链、调用关系或依赖对象分析意图。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有调用链意图。
            return false; // 返回false。
        }
        return containsAny(normalizedMessage, ANALYZE_CALL_CHAIN_INTENT_KEYWORDS); // 命中调用链分析关键词。
    }

    private String extractCallChainMethodName(String message) { // 从“分析 execute 方法调用链”等表达中提取入口方法名。
        if (message == null || message.isBlank()) { // 空消息没有方法名。
            return null; // 返回null。
        }
        Matcher explicitMatcher = CALL_CHAIN_METHOD_NAME_PATTERN.matcher(message); // 优先识别带“方法”字样的入口方法。
        if (explicitMatcher.find()) { // 命中显式方法名。
            String candidate = explicitMatcher.group(1); // 取候选方法名。
            if (candidate != null && !candidate.isBlank() && !isCallChainMethodStopWord(candidate)) { // 排除停用词。
                return candidate; // 返回显式方法名。
            }
        }
        Matcher lowerMatcher = CALL_CHAIN_LOWER_METHOD_PATTERN.matcher(message); // 再识别小写开头标识符直接接调用链短语的方法名。
        while (lowerMatcher.find()) { // 逐个候选。
            String candidate = lowerMatcher.group(1); // 取候选标识符。
            if (candidate != null && !candidate.isBlank()
                    && Character.isLowerCase(candidate.charAt(0))
                    && !isCallChainMethodStopWord(candidate)) { // 仅接受小写开头且非停用词，避免把类名当方法。
                return candidate; // 返回方法名。
            }
        }
        return null; // 没有明确方法名时分析整个类。
    }

    private boolean isCallChainMethodStopWord(String candidate) { // 判断方法名候选是否是调用链短语自身的停用词。
        if (candidate == null) { // 空候选视为停用词。
            return true; // 返回true。
        }
        return CALL_CHAIN_METHOD_STOP_WORDS.contains(candidate.toLowerCase(Locale.ROOT)); // 命中停用词集合。
    }

    private boolean shouldForceAnalyzeControllerServiceChain(String message,
                                                            ToolCallingRequestContext requestContext) { // 判断用户是否要求分析 Controller→Service 接口链路。
        if (message == null || message.isBlank()) { // 空消息不能触发。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写兼容英文。
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || shouldForceListProjectTree(message)) { // 知识库、上传文件、完整源码、目录树都有自己的路由，任何情况下都不抢占。
            return false; // 返回false。
        }
        String endpoint = extractControllerEndpoint(message); // 优先识别 /api/xxx、/chat/message、/batch 这类接口路径。
        boolean endpointQuery = endpoint != null && !endpoint.isBlank(); // 是否带接口路径。
        if (endpointQuery) { // endpoint 查询：即使带“在哪个类/哪个 Controller”等定位词也要走全项目扫描，由真实结果回答，避免模型按 endpoint 脑补 XxxController。
            return isControllerServiceChainIntent(normalizedMessage)
                    || isEndpointServiceIntent(normalizedMessage)
                    || isEndpointLocateIntent(normalizedMessage); // 接口链路 / 接口+Service / 接口定位 三类意图任一命中即路由。
        }
        if (isProjectCodeSearchOnlyIntent(normalizedMessage)) { // 无 endpoint 的纯定位类问题（如“AgentController 在哪”）仍交给 searchCode。
            return false; // 返回false。
        }
        if (!isControllerServiceChainIntent(normalizedMessage)) { // 没有 Controller→Service 接口链路意图。
            return false; // 返回false。
        }
        AnalyzeCodeTarget target = resolveControllerTargetFromMessage(message, requestContext); // 解析显式 Controller 路径/类名或项目文件焦点。
        if (target == null || target.path() == null || target.path().isBlank()) { // 无可执行目标。
            return false; // 返回false。
        }
        if (target.byFocus()) { // 目标来自 projectFileFocus 时，必须焦点是 Controller 文件。
            return isControllerFocus(requestContext); // 焦点是 Controller 才路由。
        }
        return target.path().toLowerCase(Locale.ROOT).contains("controller"); // 显式目标必须明确指向 Controller 文件，避免误吞普通类或接口路径。
    }

    private boolean isControllerServiceChainIntent(String normalizedMessage) { // 判断是否是 Controller→Service 接口链路意图。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有意图。
            return false; // 返回false。
        }
        return containsAny(normalizedMessage, CONTROLLER_SERVICE_CHAIN_INTENT_KEYWORDS); // 命中接口链路关键词。
    }

    private boolean isEndpointServiceIntent(String normalizedMessage) { // 判断“接口路径 + Service/调用/链路”这类弱信号，配合 endpoint 触发全项目扫描。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有意图。
            return false; // 返回false。
        }
        return normalizedMessage.contains("service")
                || normalizedMessage.contains("调用")
                || normalizedMessage.contains("链路")
                || normalizedMessage.contains("接口")
                || normalizedMessage.contains("mapper")
                || normalizedMessage.contains("repository")
                || normalizedMessage.contains("背后"); // 接口路径配合这些词即视为接口链路查询。
    }

    private boolean isEndpointLocateIntent(String normalizedMessage) { // 判断“这个接口在哪个类/哪个 Controller”这类接口定位意图，配合 endpoint 触发全项目扫描。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息没有意图。
            return false; // 返回false。
        }
        return normalizedMessage.contains("在哪个类")
                || normalizedMessage.contains("哪个类")
                || normalizedMessage.contains("在哪个 controller")
                || normalizedMessage.contains("在哪个controller")
                || normalizedMessage.contains("哪个 controller")
                || normalizedMessage.contains("哪个controller")
                || normalizedMessage.contains("属于哪")
                || normalizedMessage.contains("在哪个文件")
                || normalizedMessage.contains("which controller")
                || normalizedMessage.contains("在哪") // 接口路径 + “在哪”即视为接口定位查询。
                || normalizedMessage.contains("哪个方法"); // 接口对应哪个方法也走全项目扫描。
    }

    private boolean hasStandaloneMethodTarget(String message) { // 判断是否“仅给出方法名（无类名/文件/接口路径）”的目标，需要全项目搜索方法声明。
        if (message == null || message.isBlank()) { // 空消息没有方法目标。
            return false; // 返回false。
        }
        if (projectCodeTargetResolver.extractMethodName(message) == null) { // 没有方法名。
            return false; // 返回false。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null) { // 带接口路径时交给 Controller→Service 链路。
            return false; // 返回false。
        }
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 是否同时给了明确类名/文件。
        return explicitProjectPath == null || explicitProjectPath.isBlank(); // 仅方法名（无类名/文件）时才算独立方法目标。
    }

    private boolean isControllerFocus(ToolCallingRequestContext requestContext) { // 判断当前 projectFileFocus 是否为 Controller 文件。
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取焦点。
        if (focus == null || focus.getPath() == null || focus.getPath().isBlank()) { // 没有焦点。
            return false; // 返回false。
        }
        String path = focus.getPath().toLowerCase(Locale.ROOT); // 焦点相对路径小写（已包含文件名）。
        return path.contains("controller"); // 路径或文件名命中 Controller。
    }

    private String extractControllerEndpoint(String message) { // 从用户输入中提取接口路径，统一委托给 ProjectCodeTargetResolver，避免重复实现。
        return projectCodeTargetResolver.extractEndpoint(message); // 复用统一目标解析器的 endpoint 提取逻辑。
    }

    private String extractControllerMethodName(String message) { // 从“chat 接口链路”等表达中提取 Controller 方法名。
        if (message == null || message.isBlank()) { // 空消息没有方法名。
            return null; // 返回null。
        }
        Matcher matcher = CONTROLLER_METHOD_NAME_PATTERN.matcher(message); // 匹配方法名+接口/方法短语。
        while (matcher.find()) { // 逐个候选。
            String candidate = matcher.group(1); // 候选方法名。
            if (candidate != null && !candidate.isBlank()
                    && Character.isLowerCase(candidate.charAt(0))
                    && !isCallChainMethodStopWord(candidate)
                    && !"controller".equals(candidate.toLowerCase(Locale.ROOT))) { // 仅接受小写开头且非停用词，避免把类名当方法。
                return candidate; // 返回方法名。
            }
        }
        return null; // 没有明确方法名。
    }

    private boolean isServiceImplDepthIntent(String message) { // 判断用户是否希望分析到 ServiceImpl 内部一层（maxDepth=2）。
        if (message == null || message.isBlank()) { // 空消息默认 1 层。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写。
        return normalizedMessage.contains("serviceimpl")
                || normalizedMessage.contains("实现类")
                || normalizedMessage.contains("impl 内部")
                || normalizedMessage.contains("service 内部")
                || normalizedMessage.contains("内部调用")
                || normalizedMessage.contains("更深")
                || normalizedMessage.contains("深一层")
                || normalizedMessage.contains("两层")
                || normalizedMessage.contains("2 层")
                || normalizedMessage.contains("maxdepth=2"); // 命中实现类/更深一层意图。
    }

    private boolean shouldUseProjectFileFocus(String message,
                                              ToolCallingRequestContext requestContext) { // 判断是否应使用当前会话 projectFileFocus。
        if (message == null || message.isBlank() || requestContext == null) { // 空消息或无上下文不能使用焦点。
            return false; // 返回false。
        }
        if (!isProjectFileFollowUpIntent(message)) { // 没有项目源码追问意图。
            return false; // 返回false。
        }
        if (!hasProjectFileFocus(requestContext)) { // 没有可用项目文件焦点。
            return false; // 返回false。
        }
        if (hasRecentProjectTarget(requestContext)) { // 有最近明确项目目标时，projectFileFocus 必须避让。
            return false; // 避免旧读取焦点抢走“它/这个类”。
        }
        if (hasAttachedFiles(requestContext)) { // 本轮携带上传附件时，优先交给 readFile/附件链路处理。
            return false; // 避免项目文件焦点抢占新上传文件。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写判断排除场景。
        if (isExplicitRagIntent(message) || isUploadedFileExplicitIntent(normalizedMessage)) { // 知识库或上传附件意图不能使用项目文件焦点。
            return false; // 返回false。
        }
        if (isProjectCodeSearchOnlyIntent(normalizedMessage) || shouldForceListProjectTree(message)) { // 代码定位或目录树请求不能使用项目文件焦点。
            return false; // 返回false。
        }
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 检查是否有新的明确项目文件目标。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()) { // 有明确文件/类名目标。
            return false; // 交给 readProjectFile 显式路由切换焦点，不使用旧 focus。
        }
        // endpoint / 方法名属于明确目标，必须全项目扫描，不能被 focus 限制。
        return projectCodeTargetResolver.extractEndpoint(message) == null
                && projectCodeTargetResolver.extractMethodName(message) == null; // 仅纯指代追问才允许使用 focus。
    }

    private boolean shouldPromptProjectFileFocusMissing(String message,
                                                        ToolCallingRequestContext requestContext) { // 没有 projectFileFocus 时是否应提示用户先指定文件。
        if (message == null || message.isBlank()) { // 空消息不处理。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写判断排除场景。
        boolean projectFollowUpIntent = isProjectFileFollowUpIntent(message) || isAnalyzeCodeIntent(normalizedMessage); // 结构分析类追问缺少目标时也提示指定文件。
        if (!projectFollowUpIntent) { // 非项目源码追问不提示。
            return false; // 返回false。
        }
        if (isExplicitRagIntent(message)
                || isUploadedFileExplicitIntent(normalizedMessage)
                || hasAttachmentContext(requestContext)
                || isProjectCodeSearchOnlyIntent(normalizedMessage)
                || shouldForceListProjectTree(message)) { // 知识库、上传文件、代码搜索、目录树都有自己的路由。
            return false; // 返回false。
        }
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 检查是否有新的明确项目文件目标。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()) { // 有明确目标时不提示缺焦点。
            return false; // 返回false。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null
                || projectCodeTargetResolver.extractMethodName(message) != null) { // 有明确 endpoint/方法目标。
            return false; // 交给全项目扫描或 searchCode，不提示缺焦点。
        }
        return !hasRecentProjectTarget(requestContext) && !hasProjectFileFocus(requestContext); // 两类项目焦点都不存在时才提示用户指定文件。
    }

    private boolean hasProjectFileFocus(ToolCallingRequestContext requestContext) { // 判断上下文中是否有有效 projectFileFocus 元信息。
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 读取焦点对象。
        return focus != null && focus.getPath() != null && !focus.getPath().isBlank(); // path 是项目文件焦点的最小可用条件。
    }

    private boolean hasRecentProjectTarget(ToolCallingRequestContext requestContext) { // 判断上下文中是否有有效 recentProjectTarget 元信息。
        return resolveRecentProjectTarget(requestContext) != null; // 复用安全路径校验。
    }

    private ConversationFocusContext resolveRecentProjectTarget(ToolCallingRequestContext requestContext) { // 获取安全可用的 recentProjectTarget。
        ConversationFocusContext target = requestContext == null ? null : requestContext.getRecentProjectTarget(); // 读取最近明确项目目标。
        if (target == null || target.getPath() == null || target.getPath().isBlank()) { // 没有最近目标。
            return null; // 返回空。
        }
        if (!isSafeRecentProjectTargetPath(target.getPath())) { // 路径不符合 workspace 相对路径安全要求。
            log.warn("[RecentProjectTarget] ignore unsafe path: {}", target.getPath()); // 只打印候选相对路径。
            return null; // 忽略 recentProjectTarget，允许后续回退 projectFileFocus。
        }
        return target; // 返回安全最近目标。
    }

    private boolean isProjectFileFollowUpIntent(String message) { // 判断用户是否在围绕最近项目源码文件继续追问。
        if (message == null || message.isBlank()) { // 空消息没有追问意图。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写兼容英文。
        return containsAny(normalizedMessage, PROJECT_FILE_FOLLOW_UP_KEYWORDS); // 命中“给我代码/继续分析/这个类”等词。
    }

    private boolean isUploadedFileExplicitIntent(String normalizedMessage) { // 判断当前问题是否明确指向用户上传文件或聊天附件。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息不是上传文件意图。
            return false; // 返回false。
        }
        return containsAny(normalizedMessage, UPLOADED_FILE_EXPLICIT_KEYWORDS); // 命中上传、附件、fileId等词时 projectFileFocus 必须避让。
    }

    private String resolveProjectFileFocusReadMode(String message) { // 根据项目文件追问语义决定 readProjectFile 模式。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 空消息兜底。
        if (containsAny(normalizedMessage, READ_PROJECT_FILE_ANALYZE_KEYWORDS)
                || normalizedMessage.contains("继续分析")
                || normalizedMessage.contains("继续优化")
                || normalizedMessage.contains("优化")
                || normalizedMessage.contains("重构")
                || normalizedMessage.contains("有什么问题")
                || normalizedMessage.contains("哪里可以优化")
                || normalizedMessage.contains("讲一下")) { // 分析、优化、讲解类追问走 SUMMARY。
            return READ_PROJECT_FILE_MODE_SUMMARY; // 返回分析模式。
        }
        if (containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || normalizedMessage.contains("给我代码")
                || normalizedMessage.contains("输出代码")
                || normalizedMessage.contains("源码")) { // 明确要代码原文时走 FULL。
            return READ_PROJECT_FILE_MODE_FULL; // 返回完整输出模式。
        }
        return READ_PROJECT_FILE_MODE_SUMMARY; // 默认追问以分析为主，避免无意重复刷完整代码。
    }

    private ReadProjectFileTarget resolveReadProjectFileTarget(String message,
                                                               ToolCallingRequestContext requestContext) { // 解析 readProjectFile 强制路由目标。
        String explicitProjectPath = resolveReadProjectFilePathFromMessage(message); // 显式路径、文件名或类名优先。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()) { // 用户本轮给了明确目标。
            return new ReadProjectFileTarget(explicitProjectPath, "explicit"); // 不受任何上下文焦点影响。
        }
        return resolveReadProjectFileContextTarget(message, requestContext); // 没有显式目标时才允许用上下文指代。
    }

    private ReadProjectFileTarget resolveReadProjectFileContextTarget(String message,
                                                                      ToolCallingRequestContext requestContext) { // 从 recentProjectTarget/projectFileFocus 解析读取目标。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 统一小写。
        boolean readFollowUp = isProjectSourceOutputIntent(normalizedMessage)
                || containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || (containsAny(normalizedMessage, READ_PROJECT_FILE_ACTION_KEYWORDS)
                && isProjectFileFollowUpIntent(message)
                && !containsAny(normalizedMessage, READ_PROJECT_FILE_ANALYZE_KEYWORDS)); // “给我它的代码/打开它”这类读取追问。
        if (!readFollowUp) { // 不是读取源码追问。
            return null; // 不使用上下文目标。
        }
        ConversationFocusContext recentTarget = resolveRecentProjectTarget(requestContext); // 先取最近明确项目目标。
        if (recentTarget != null && recentTarget.getPath() != null && !recentTarget.getPath().isBlank()) { // 有最近目标。
            return new ReadProjectFileTarget(recentTarget.getPath(), "recentProjectTarget"); // recentProjectTarget 优先。
        }
        ConversationFocusContext focus = requestContext == null ? null : requestContext.getProjectFileFocus(); // 回退项目文件读取焦点。
        if (focus != null && focus.getPath() != null && !focus.getPath().isBlank()) { // 有项目文件焦点。
            return new ReadProjectFileTarget(focus.getPath(), "projectFileFocus"); // projectFileFocus 兜底。
        }
        return null; // 没有可用上下文目标。
    }

    private String resolveReadProjectFileRouteReason(String userMessage,
                                                     ReadProjectFileTarget readTarget) { // 构造 readProjectFile route_reason。
        String source = readTarget == null ? "" : readTarget.source(); // 读取目标来源。
        if ("recentProjectTarget".equals(source)) { // 来自最近明确项目目标。
            return ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_RECENT_TARGET; // 标记 recentProjectTarget。
        }
        if ("projectFileFocus".equals(source)) { // 来自旧项目文件焦点。
            return ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_FOCUS; // 标记 projectFileFocus。
        }
        return containsProjectFilePath(userMessage)
                ? ROUTE_REASON_FORCE_READ_PROJECT_FILE
                : ROUTE_REASON_FORCE_READ_PROJECT_FILE_BY_NAME; // 明确路径和类名/文件名唯一定位分别记录路由原因。
    }

    private String buildProjectFileFocusMissingPrompt() { // 构造没有项目文件焦点时的友好提示。
        return "我还没有确定当前项目文件。请先告诉我要读取哪个类或文件，例如：\n\n"
                + "* 给我 SearchCodeTool 代码\n"
                + "* 分析 SearchCodeTool 代码结构\n"
                + "* SearchCodeTool 在哪\n"
                + "* 读取 Tech-Brain-Agent/src/main/java/.../SearchCodeTool.java"; // 不调用RAG或代码搜索乱猜。
    }

    private String resolveReadProjectFileMode(String message) { // 根据用户表达解析readProjectFile读取模式。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 空消息兜底。
        boolean hasExplicitFileTarget = containsProjectFilePath(message)
                || extractProjectPathByPattern(message, PROJECT_FILE_NAME_PATTERN) != null; // 明确路径或带扩展名文件名可视为文件内容输出目标。
        boolean hasOutputAction = containsAny(normalizedMessage, READ_PROJECT_FILE_ACTION_KEYWORDS)
                && !containsAny(normalizedMessage, READ_PROJECT_FILE_ANALYZE_KEYWORDS); // 获取、打开、查看类动作默认输出原文。
        return containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)
                || isProjectSourceOutputIntent(normalizedMessage)
                || (hasOutputAction && hasExplicitFileTarget)
                ? READ_PROJECT_FILE_MODE_FULL
                : READ_PROJECT_FILE_MODE_SUMMARY; // 明确要求给代码/输出源码时使用FULL，分析解释场景使用SUMMARY。
    }

    private boolean isReadProjectFileIntent(String message, String projectFilePath) { // 判断当前消息是否要读取项目源码文件内容。
        String normalizedMessage = message == null ? "" : message.trim().toLowerCase(Locale.ROOT); // 统一小写判断。
        boolean hasTarget = projectFilePath != null && !projectFilePath.isBlank(); // 已解析出类名、文件名或相对路径。
        if (!hasTarget) { // 没有明确目标不能读项目文件。
            return false; // 返回false。
        }
        if (containsAny(normalizedMessage, READ_PROJECT_FILE_FULL_KEYWORDS)) { // 完整代码、全部源码等强意图。
            return true; // 直接触发readProjectFile。
        }
        boolean hasAction = containsAny(normalizedMessage, READ_PROJECT_FILE_ACTION_KEYWORDS); // 给我、输出、读取、打开、查看等动作。
        boolean hasContent = containsAny(normalizedMessage, READ_PROJECT_FILE_CONTENT_KEYWORDS); // 代码、源码、文件、内容等对象。
        if (hasAction && hasContent) { // “给我 xxx 代码”“查看 xxx 源码”。
            return true; // 触发readProjectFile。
        }
        if (hasAction && containsProjectFilePath(message)) { // “给我 Tech-Brain-Agent/.../SearchCodeTool.java”。
            return true; // 明确路径加获取动作也应读取文件。
        }
        if (hasAction && extractProjectPathByPattern(message, PROJECT_FILE_NAME_PATTERN) != null) { // “打开 SearchCodeTool.java”。
            return true; // 带扩展名文件名加读取动作也应读取文件。
        }
        return false; // “分析/解释 xxx 代码”属于 analyzeCode 场景，不再作为 readProjectFile 兜底读取。
    }

    private boolean isProjectSourceOutputIntent(String normalizedMessage) { // 判断是否属于“给我/输出/读取 xxx 代码”的完整源码输出意图。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息不是输出源码。
            return false; // 返回false。
        }
        boolean hasAction = containsAny(normalizedMessage, READ_PROJECT_FILE_ACTION_KEYWORDS); // 给我、输出、读取、打开、查看等动作。
        boolean hasContent = containsAny(normalizedMessage, READ_PROJECT_FILE_CONTENT_KEYWORDS); // 代码、源码、文件、内容等对象。
        boolean hasAnalyzeAction = containsAny(normalizedMessage, READ_PROJECT_FILE_ANALYZE_KEYWORDS); // 分析、解释、总结等动作。
        return hasAction && hasContent && !hasAnalyzeAction; // 获取/展示源码默认FULL，分析类请求保留SUMMARY。
    }

    private boolean isProjectCodeSearchOnlyIntent(String normalizedMessage) { // 判断是否为纯代码位置搜索请求。
        if (normalizedMessage == null || normalizedMessage.isBlank()) { // 空消息不是搜索。
            return false; // 返回false。
        }
        return containsAny(normalizedMessage, PROJECT_CODE_SEARCH_ONLY_KEYWORDS); // 命中在哪、相关代码、搜索、查找、定位等词。
    }

    private String resolveReadProjectFilePathFromMessage(String message) { // 从自然语言中提取readProjectFile的path参数。
        if (message == null || message.isBlank()) { // 空消息没有路径。
            return ""; // 返回空。
        }
        String normalizedMessage = message.trim(); // 保留大小写，实际文件路径可能区分大小写。
        String windowsAbsolutePath = extractProjectPathByPattern(normalizedMessage, PROJECT_WINDOWS_ABSOLUTE_PATH_PATTERN); // Windows绝对路径优先识别。
        if (windowsAbsolutePath != null) { // 命中D:/xxx这类路径。
            return windowsAbsolutePath; // 交给工具拒绝绝对路径。
        }
        String relativeFilePath = extractProjectPathByPattern(normalizedMessage, PROJECT_RELATIVE_FILE_PATH_PATTERN); // 优先识别带扩展名相对路径。
        if (relativeFilePath != null) { // 命中Tech-Brain-Agent/src/.../SearchCodeTool.java。
            return relativeFilePath; // 直接返回明确相对路径。
        }
        String relativePathToken = extractProjectPathByPattern(normalizedMessage, PROJECT_RELATIVE_PATH_TOKEN_PATTERN); // 识别.git/config等无扩展名路径。
        if (relativePathToken != null) { // 命中相对路径。
            if (isEndpointProjectPathToken(normalizedMessage, relativePathToken)) { // /chat/message 被路径正则剥成 chat/message 时要识别为 endpoint。
                return ""; // endpoint 不允许作为 readProjectFile.path。
            }
            return relativePathToken; // 交给工具做敏感路径或普通文件校验。
        }
        String unixAbsolutePath = extractProjectPathByPattern(normalizedMessage, PROJECT_UNIX_ABSOLUTE_PATH_PATTERN); // 再识别/root/.ssh/id_rsa这类Unix绝对路径。
        if (unixAbsolutePath != null) { // 命中Unix绝对路径。
            return unixAbsolutePath; // 交给工具拒绝绝对路径。
        }
        String sensitiveFileName = extractProjectPathByPattern(normalizedMessage, PROJECT_SENSITIVE_FILENAME_PATTERN); // 识别.env/id_rsa等敏感文件名。
        if (sensitiveFileName != null) { // 命中敏感文件名。
            return sensitiveFileName; // 交给工具返回敏感文件拒绝。
        }
        String fileName = extractProjectPathByPattern(normalizedMessage, PROJECT_FILE_NAME_PATTERN); // 提取SearchCodeTool.java、pom.xml等文件名。
        if (fileName == null) { // 没有带扩展名的文件名。
            String classFileName = resolveProjectClassFileNameFromMessage(normalizedMessage); // 尝试把 ChatMessageServiceImpl 这类 Java 类名补成 .java。
            if (classFileName == null || classFileName.isBlank()) { // 没有类名候选。
                return ""; // 返回空。
            }
            String knownProjectClassPath = resolveKnownProjectFilePath(classFileName); // 已知项目文件优先映射完整相对路径。
            return knownProjectClassPath == null ? classFileName : knownProjectClassPath; // 未映射时交给 readProjectFile 在 workspace 内唯一定位。
        }
        String knownProjectFilePath = resolveKnownProjectFilePath(fileName); // 常见项目工具文件名可以直接映射真实相对路径。
        return knownProjectFilePath == null ? fileName : knownProjectFilePath; // 未映射时按根目录文件名交给工具处理。
    }

    private String resolveProjectClassFileNameFromMessage(String message) { // 从自然语言中提取项目类名并转换为 Java 文件名。
        if (message == null || message.isBlank()) { // 空消息没有类名。
            return ""; // 返回空。
        }
        Matcher matcher = CODE_IDENTIFIER_PATTERN.matcher(message); // 复用代码标识提取规则。
        while (matcher.find()) { // 遍历候选标识。
            String candidate = matcher.group(1); // 取当前候选。
            if (candidate == null || candidate.isBlank()) { // 空候选跳过。
                continue; // 继续下一个。
            }
            String normalizedCandidate = candidate.endsWith("()")
                    ? candidate.substring(0, candidate.length() - 2)
                    : candidate; // 去掉方法调用括号，避免 saveNote() 被当成类名。
            if (looksLikeProjectClassName(normalizedCandidate)) { // 命中 Java 类名形态。
                return normalizedCandidate + ".java"; // 默认补 .java，具体路径由 readProjectFile 工具安全唯一定位。
            }
        }
        return ""; // 没有可用类名。
    }

    private boolean looksLikeProjectClassName(String candidate) { // 判断候选是否像 Java 类名。
        if (candidate == null || candidate.isBlank()) { // 空候选不是类名。
            return false; // 返回 false。
        }
        if (candidate.startsWith("@") || candidate.startsWith("/") || candidate.contains(".")) { // 注解、接口路径、文件名不走类名补全。
            return false; // 返回 false。
        }
        if (isWeakEnglishToken(candidate)) { // 排除自然语言弱词。
            return false; // 返回 false。
        }
        if (!candidate.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { // 必须是标准代码标识符。
            return false; // 返回 false。
        }
        return Character.isUpperCase(candidate.charAt(0)); // Java 类名通常以大写字母开头。
    }

    private boolean containsProjectFilePath(String message) { // 判断消息是否包含明确项目文件路径。
        if (message == null || message.isBlank()) { // 空消息没有路径。
            return false; // 返回false。
        }
        if (projectCodeTargetResolver.extractEndpoint(message) != null) { // 接口路径不是项目文件路径。
            return false; // endpoint 不参与 readProjectFile 路径判断。
        }
        return extractProjectPathByPattern(message, PROJECT_WINDOWS_ABSOLUTE_PATH_PATTERN) != null
                || extractProjectPathByPattern(message, PROJECT_RELATIVE_FILE_PATH_PATTERN) != null
                || extractProjectPathByPattern(message, PROJECT_RELATIVE_PATH_TOKEN_PATTERN) != null
                || extractProjectPathByPattern(message, PROJECT_UNIX_ABSOLUTE_PATH_PATTERN) != null; // 任意路径模式命中即认为包含路径。
    }

    private boolean isEndpointProjectPathToken(String message, String candidate) { // 判断路径正则命中的 token 是否实际来自 API endpoint。
        if (message == null || message.isBlank() || candidate == null || candidate.isBlank()) { // 空值不能判断。
            return false; // 返回false。
        }
        String endpoint = projectCodeTargetResolver.extractEndpoint(message); // 使用统一 endpoint 提取规则。
        if (endpoint == null || endpoint.isBlank()) { // 没有 endpoint。
            return false; // 返回false。
        }
        String normalizedCandidate = candidate.trim().replace('\\', '/'); // 统一候选路径分隔符。
        if (normalizedCandidate.contains(".")) { // 带源码扩展名或文件后缀的候选不按 endpoint 处理。
            return false; // 返回false。
        }
        String endpointWithoutSlash = endpoint.startsWith("/") ? endpoint.substring(1) : endpoint; // 去掉 endpoint 开头斜杠用于比较。
        return endpointWithoutSlash.equals(normalizedCandidate)
                || endpointWithoutSlash.startsWith(normalizedCandidate)
                || normalizedCandidate.startsWith(endpointWithoutSlash); // 兼容 /article/ai/summary/{id} 被路径正则截短的情况。
    }

    private String extractProjectPathByPattern(String message, Pattern pattern) { // 使用正则从用户消息中提取路径候选。
        if (message == null || message.isBlank() || pattern == null) { // 输入为空时不提取。
            return null; // 返回null。
        }
        Matcher matcher = pattern.matcher(message); // 创建匹配器。
        if (!matcher.find()) { // 未命中。
            return null; // 返回null。
        }
        String candidate = matcher.group(1); // 所有项目路径正则都把目标放在第一个分组。
        return sanitizeProjectFilePathCandidate(candidate); // 清理尾部标点并统一分隔符。
    }

    private String sanitizeProjectFilePathCandidate(String candidate) { // 清理项目文件路径候选值。
        if (candidate == null || candidate.isBlank()) { // 空候选无效。
            return ""; // 返回空。
        }
        String normalizedPath = candidate.trim().replace('\\', '/'); // 统一路径分隔符。
        while (normalizedPath.endsWith("，")
                || normalizedPath.endsWith("。")
                || normalizedPath.endsWith("；")
                || normalizedPath.endsWith(";")
                || normalizedPath.endsWith(",")
                || normalizedPath.endsWith(".")
                || normalizedPath.endsWith("？")
                || normalizedPath.endsWith("?")
                || normalizedPath.endsWith("！")
                || normalizedPath.endsWith("!")
                || normalizedPath.endsWith("：")
                || normalizedPath.endsWith(":")) { // 移除自然语言末尾标点。
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1); // 去掉最后一个标点。
        }
        return normalizedPath; // 返回清理后的路径候选。
    }

    private boolean isFilenameOnlyProjectPath(String path) { // 判断候选path是否只是文件名。
        return path != null && !path.contains("/") && !path.contains("\\"); // 不含路径分隔符即文件名。
    }

    private boolean isProjectRootReadableFile(String path) { // 判断是否为根目录可直接读取的项目文件。
        if (path == null || path.isBlank()) { // 空路径不是根目录文件。
            return false; // 返回false。
        }
        for (String rootFile : PROJECT_ROOT_READABLE_FILES) { // 遍历允许根目录文件。
            if (rootFile.equalsIgnoreCase(path.trim())) { // 大小写不敏感匹配。
                return true; // 返回true。
            }
        }
        return false; // 未命中。
    }

    private String resolveKnownProjectFilePath(String fileName) { // 将已知项目Tool源码文件名映射为相对workspace路径。
        if (fileName == null || fileName.isBlank()) { // 空文件名无法映射。
            return null; // 返回null。
        }
        return switch (fileName) {
            case "AnalyzeCodeTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/project/AnalyzeCodeTool.java";
            case "CodeStructureAnalyzer.java" -> "Tech-Brain-Agent/src/main/java/com/agent/analysis/code/CodeStructureAnalyzer.java";
            case "CallChainAnalyzer.java" -> "Tech-Brain-Agent/src/main/java/com/agent/analysis/code/CallChainAnalyzer.java";
            case "ControllerServiceChainAnalyzer.java" -> "Tech-Brain-Agent/src/main/java/com/agent/analysis/code/ControllerServiceChainAnalyzer.java";
            case "ToolServiceChainAnalyzer.java" -> "Tech-Brain-Agent/src/main/java/com/agent/analysis/code/ToolServiceChainAnalyzer.java";
            case "SseEventChainAnalyzer.java" -> "Tech-Brain-Agent/src/main/java/com/agent/analysis/code/SseEventChainAnalyzer.java";
            case "SearchCodeTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/project/SearchCodeTool.java";
            case "ListProjectTreeTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/project/ListProjectTreeTool.java";
            case "ReadProjectFileTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/project/ReadProjectFileTool.java";
            case "ReadFileTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/file/ReadFileTool.java";
            case "RagSearchTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/rag/RagSearchTool.java";
            case "SummarizeArticleTool.java" -> "Tech-Brain-Agent/src/main/java/com/agent/tool/summary/SummarizeArticleTool.java";
            case "ProjectPathGuard.java" -> "Tech-Brain-Agent/src/main/java/com/agent/security/ProjectPathGuard.java";
            case "ProjectWorkspaceProperties.java" -> "Tech-Brain-Agent/src/main/java/com/agent/config/ProjectWorkspaceProperties.java";
            case "ToolCallingChatServiceImpl.java" -> "Tech-Brain-Tool/src/main/java/com/agent/toolcalling/core/ToolCallingChatServiceImpl.java";
            case "ChatMessageServiceImpl.java" -> "Tech-Brain-Agent/src/main/java/com/agent/service/impl/ChatMessageServiceImpl.java";
            default -> null;
        }; // 未知文件名不盲目全项目读取。
    }

    private boolean shouldForceSearchCode(String message) { // 判断用户是否要求搜索项目代码位置。
        if (message == null || message.isBlank()) { // 空消息不能触发强制代码搜索。
            return false; // 返回false。
        }
        if (isExplicitRagIntent(message)) { // 用户明确要求知识库时保留RAG优先级。
            return false; // 不抢占显式知识库查询。
        }
        if (shouldForceReadProjectFile(message, null)) { // 明确读取项目文件时searchCode必须避让。
            return false; // 避免“读取 SearchCodeTool.java”被误判为搜索SearchCodeTool。
        }
        if (isProjectToolInventoryIntent(message)) { // 项目工具清单/存在性问题强制搜索真实Tool实现。
            return true; // 走searchCode而不是普通聊天或日志统计推断。
        }
        if (hasStandaloneMethodTarget(message)) { // 仅给出方法名（无类名/文件/接口路径）时全项目搜索方法声明，不被 focus 限制。
            return true; // 走 searchCode 做 METHOD 全项目定位。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写匹配英文关键词。
        if (containsAny(normalizedMessage, PROJECT_TREE_INTENT_KEYWORDS) && !containsCodeIdentifier(message)) { // 纯目录树问题不走代码搜索。
            return false; // 交给listProjectTree。
        }
        if (containsAny(normalizedMessage, SEARCH_CODE_INTENT_KEYWORDS)) { // 命中代码搜索意图关键词。
            return containsCodeIdentifier(message) || normalizedMessage.contains("相关代码") || normalizedMessage.contains("代码在哪"); // 有代码标识或明确代码意图才强制。
        }
        return containsCodeIdentifier(message)
                && (normalizedMessage.contains("controller")
                || normalizedMessage.contains("service")
                || normalizedMessage.contains("mapper")
                || normalizedMessage.contains("tool")
                || normalizedMessage.contains("impl")); // 常见代码标识可触发搜索。
    }

    private String resolveSearchCodeQueryFromMessage(String message) { // 从自然语言中提取searchCode query。
        if (message == null || message.isBlank()) { // 空消息兜底。
            return ""; // 返回空，由工具侧失败处理。
        }
        if (isProjectToolInventoryIntent(message)) { // 工具清单/工具存在性问题使用专用query。
            return resolveProjectToolInventoryQueryFromMessage(message); // 具体工具搜工具名，清单搜extends AbstractAiTool。
        }
        Matcher matcher = CODE_IDENTIFIER_PATTERN.matcher(message); // 优先提取代码标识。
        String fallbackIdentifier = null; // 保存兜底代码标识。
        while (matcher.find()) { // 遍历候选标识。
            String candidate = matcher.group(1); // 当前候选。
            if (candidate == null || candidate.isBlank()) { // 空候选跳过。
                continue; // 继续下一个。
            }
            if (isWeakEnglishToken(candidate)) { // 普通英文弱词不作为query。
                continue; // 继续下一个。
            }
            if (candidate.startsWith("@") || candidate.startsWith("/") || looksLikeCodeIdentifier(candidate)) { // 注解、接口路径、类名、方法名优先。
                return candidate; // 返回明确query。
            }
            fallbackIdentifier = candidate; // 保存可用兜底。
        }
        if (fallbackIdentifier != null) { // 有兜底标识。
            return fallbackIdentifier; // 返回兜底query。
        }
        String query = message; // 没有英文代码标识时用整句清理。
        for (String stopWord : SEARCH_CODE_QUERY_STOP_WORDS) { // 移除描述词。
            query = query.replace(stopWord, ""); // 简单替换。
        }
        return query.trim(); // 返回清理后的query。
    }

    private String resolveSearchCodeTypeFromMessage(String message, String query) { // 推断searchCode搜索类型。
        String safeMessage = message == null ? "" : message; // 空消息兜底。
        String safeQuery = query == null ? "" : query.trim(); // 空query兜底。
        if (isProjectToolInventoryIntent(safeMessage)) { // 工具识别场景使用关键词搜索。
            return "KEYWORD"; // 确保 listProjectTree/searchCode/extends AbstractAiTool 按文本命中。
        }
        if (safeMessage.contains("方法") || safeQuery.endsWith("()")) { // 用户明确说方法或query带括号。
            return "METHOD"; // 方法搜索。
        }
        if (safeQuery.startsWith("@") || safeQuery.startsWith("/") || safeQuery.contains("_") || safeQuery.contains("-")) { // 注解、接口路径或特殊关键词。
            return "KEYWORD"; // 关键词搜索。
        }
        if (!safeQuery.isBlank()
                && Character.isUpperCase(safeQuery.charAt(0))
                && safeQuery.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { // 首字母大写且像类名。
            return "CLASS"; // 类名搜索。
        }
        return "AUTO"; // 其它交给工具内部AUTO规则。
    }

    private String resolveSearchCodePathFromMessage(String message) { // 从用户消息中轻量提取搜索范围。
        if (message == null || message.isBlank()) { // 空消息没有路径。
            return ""; // 空字符串表示workspace根目录。
        }
        for (String modulePath : PROJECT_MODULE_PATH_KEYWORDS) { // 优先识别多模块目录名。
            if (message.contains(modulePath)) { // 用户提到模块名。
                return modulePath; // 限定到该模块搜索。
            }
        }
        Matcher pathMatcher = Pattern.compile("(?i)path\\s*[=:：]\\s*([A-Za-z0-9_./\\\\-]+)").matcher(message); // 支持path=Tech-Brain-Agent。
        if (pathMatcher.find()) { // 命中显式path参数。
            return pathMatcher.group(1).replace('\\', '/'); // 返回相对路径。
        }
        return ""; // 默认搜索整个workspace。
    }

    private boolean containsCodeIdentifier(String message) { // 判断消息中是否存在代码标识。
        if (message == null || message.isBlank()) { // 空消息没有代码标识。
            return false; // 返回false。
        }
        Matcher matcher = CODE_IDENTIFIER_PATTERN.matcher(message); // 匹配代码标识。
        while (matcher.find()) { // 遍历候选。
            String candidate = matcher.group(1); // 当前候选。
            if (candidate == null || candidate.isBlank() || isWeakEnglishToken(candidate)) { // 弱词不算代码标识。
                continue; // 继续下一个。
            }
            if (candidate.startsWith("@") || candidate.startsWith("/") || looksLikeCodeIdentifier(candidate)) { // 注解、接口路径、类名、方法名都算。
                return true; // 返回true。
            }
        }
        return false; // 未发现代码标识。
    }

    private boolean looksLikeCodeIdentifier(String candidate) { // 判断候选词是否像代码标识。
        if (candidate == null || candidate.isBlank()) { // 空候选。
            return false; // 返回false。
        }
        return candidate.contains("()")
                || candidate.length() >= 4 && candidate.matches(".*[A-Z].*")
                || candidate.contains("_")
                || candidate.contains("$")
                || "readFile".equals(candidate)
                || "ragSearch".equals(candidate)
                || "summarizeArticle".equals(candidate)
                || "listProjectTree".equals(candidate)
                || "searchCode".equals(candidate)
                || "analyzeCode".equals(candidate)
                || "readProjectFile".equals(candidate); // 常见代码命名形态。
    }

    private boolean isWeakEnglishToken(String candidate) { // 排除自然语言中的普通英文弱词。
        if (candidate == null) { // 空候选。
            return true; // 视为弱词。
        }
        String token = candidate.toLowerCase(Locale.ROOT); // 统一小写。
        return "path".equals(token)
                || "list".equals(token)
                || "tree".equals(token)
                || "controller".equals(token)
                || "service".equals(token)
                || "mapper".equals(token)
                || "tool".equals(token)
                || "impl".equals(token); // 单独出现这些词不足以作为query。
    }

    private boolean shouldForceListProjectTree(String message) { // 判断用户是否要求查看项目目录树或模块结构。
        if (message == null || message.isBlank()) { // 空消息不能触发强制目录树。
            return false; // 返回false。
        }
        String normalizedMessage = message.trim().toLowerCase(Locale.ROOT); // 统一小写匹配英文关键词。
        if (containsAny(normalizedMessage, PROJECT_TREE_EXCLUDED_KEYWORDS)) { // 用户文件库、聊天附件场景不走项目目录树。
            return false; // 避免“上传了哪些文件”误触发。
        }
        if (isExplicitRagIntent(message)) { // 用户明确要求知识库时保留RAG优先级。
            return false; // 不抢占显式知识库查询。
        }
        return containsAny(normalizedMessage, PROJECT_TREE_INTENT_KEYWORDS); // 命中目录树/项目结构关键词时强制listProjectTree。
    }

    private String resolveProjectTreePathFromMessage(String userMessage) { // 从用户自然语言中轻量提取 workspace 相对路径。
        if (userMessage == null || userMessage.isBlank()) { // 空消息没有路径。
            return ""; // 空字符串表示 workspace 根目录。
        }
        String normalizedMessage = userMessage.trim(); // 保留原始大小写，路径可能区分大小写。
        for (Pattern pattern : PROJECT_TREE_PATH_PATTERNS) { // 逐个尝试 path=、demo项目、src/main/java 等轻量规则。
            Matcher matcher = pattern.matcher(normalizedMessage); // 匹配当前规则。
            if (!matcher.find()) { // 未命中当前规则。
                continue; // 尝试下一条。
            }
            String candidatePath = sanitizeProjectTreePathCandidate(matcher.group(1)); // 清理候选路径末尾标点。
            if (candidatePath != null && !candidatePath.isBlank()) { // 候选路径有效时返回。
                return candidatePath; // 工具内部会继续执行路径安全校验。
            }
        }
        return ""; // 未提取到路径时读取 workspace 根目录。
    }

    private String sanitizeProjectTreePathCandidate(String candidatePath) { // 清理目录树路径候选值。
        if (candidatePath == null || candidatePath.isBlank()) { // 空候选无效。
            return ""; // 返回空字符串。
        }
        String normalizedPath = candidatePath.trim()
                .replace('\\', '/')
                .replaceAll("[，。；;,.]+$", ""); // 统一路径分隔符并移除末尾标点。
        while (normalizedPath.startsWith("./")) { // 去掉开头的 ./，保持相对workspace路径。
            normalizedPath = normalizedPath.substring(2); // 移除一层 ./。
        }
        return normalizedPath; // 返回清理后的候选路径。
    }

    private boolean shouldForceSummarizeArticle(String currentMessage) { // 判断用户是否明确要求总结某篇文章/笔记。
        if (currentMessage == null || currentMessage.isBlank()) { // 空消息不能触发强制总结。
            return false; // 保持空输入安全返回。
        }
        boolean hasSummaryAction = containsAny(currentMessage, FORCE_SUMMARY_KEYWORDS); // 只基于当前输入判断是否有总结、提炼、要点等动作。
        boolean hasExplicitTarget = containsAny(currentMessage, FORCE_SUMMARY_TARGET_KEYWORDS); // 判断是否明确指向笔记、文章、代码、篇号或articleId。
        boolean hasContextReference = containsAny(currentMessage, FORCE_SUMMARY_CONTEXT_REFERENCE_KEYWORDS); // 判断是否指向刚才、之前、上面等会话focus目标。
        boolean hasArticleId = resolveArticleIdFromMessage(currentMessage) != null; // 显式ID本身也说明目标清晰。
        log.debug("[ToolChatStream] summary action detected: {}", hasSummaryAction); // 总结细分识别降级为DEBUG。
        log.debug("[ToolChatStream] summary explicit target detected: {}", hasExplicitTarget || hasArticleId); // 总结细分识别降级为DEBUG。
        log.debug("[ToolChatStream] summary context reference detected: {}", hasContextReference); // 总结细分识别降级为DEBUG。
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

    private record AnalyzeCodeTarget(String path, String source) { // analyzeCode 强制路由目标。
        private boolean byFocus() { // 是否来自 projectFileFocus。
            return "projectFileFocus".equals(source); // 用于 route_reason 标记。
        }

        private boolean byRecentProjectTarget() { // 是否来自 recentProjectTarget。
            return "recentProjectTarget".equals(source); // 用于 route_reason 标记。
        }
    }

    private record AnalyzeToolTarget(String path, String toolName, String className, String source) { // analyzeCode(TOOL_SERVICE) 强制路由目标。
        private boolean byFocus() { // 是否来自 projectFileFocus。
            return "projectFileFocus".equals(source); // 用于 route_reason 标记。
        }

        private boolean byRecentProjectTarget() { // 是否来自 recentProjectTarget。
            return "recentProjectTarget".equals(source); // 用于 route_reason 标记。
        }

        private boolean hasAnyTarget() { // path、toolName、className 至少一个才可执行。
            return (path != null && !path.isBlank())
                    || (toolName != null && !toolName.isBlank())
                    || (className != null && !className.isBlank()); // 返回是否有可用目标。
        }
    }

    private record RecentProjectTargetCandidate(String path, String fileName, String targetType) { // searchCode 唯一命中的最近项目目标候选。
    }

    private record ReadProjectFileTarget(String path, String source) { // readProjectFile 强制路由目标和来源。
    }

    private static final class ForceReadFileTarget { // 强制readFile目标选择结果。

        private final ChatAttachedFileContext file; // 明确匹配到的附件文件。

        private final List<ChatAttachedFileContext> focusFiles; // 参与本次判断的文件焦点列表。

        private final boolean ambiguous; // 是否因为多附件或无法匹配而不应读取。

        private ForceReadFileTarget(ChatAttachedFileContext file,
                                    List<ChatAttachedFileContext> focusFiles,
                                    boolean ambiguous) { // 私有构造，保证状态只能通过工厂方法创建。
            this.file = file; // 保存目标文件。
            this.focusFiles = focusFiles == null ? Collections.emptyList() : focusFiles; // 保存文件焦点，供模糊提示使用。
            this.ambiguous = ambiguous; // 保存模糊状态。
        }

        private static ForceReadFileTarget of(ChatAttachedFileContext file) { // 创建明确目标。
            return new ForceReadFileTarget(file, file == null ? Collections.emptyList() : List.of(file), false); // 明确目标不模糊。
        }

        private static ForceReadFileTarget ambiguous(List<ChatAttachedFileContext> focusFiles) { // 创建模糊结果。
            return new ForceReadFileTarget(null, focusFiles, true); // 没有明确文件时不读取。
        }

        private ChatAttachedFileContext file() { // 获取目标文件。
            return file; // 返回目标文件。
        }

        private List<ChatAttachedFileContext> focusFiles() { // 获取本次解析出的文件焦点。
            return focusFiles; // 返回焦点文件列表。
        }

        private boolean isAmbiguous() { // 获取是否模糊。
            return ambiguous; // 返回模糊状态。
        }
    }

}
