package com.agent.analysis.code;

import com.agent.security.ProjectPathGuard;
import com.agent.tool.project.ProjectJavaAnalysisSupport.JavaCall;
import com.agent.tool.project.ProjectJavaAnalysisSupport.JavaField;
import com.agent.tool.project.ProjectJavaAnalysisSupport.JavaMethod;
import com.agent.tool.project.ProjectJavaAnalysisSupport;
import com.agent.toolcalling.project.analysis.CodeAnalysisType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 前后端 SSE 事件链路内部分析器（P5.5）。
 *
 * <p>适用场景：当用户要求分析“前端触发点 → 后端 SSE Controller → Service → Tool/业务逻辑 → SSE 事件发送 →
 * 前端事件接收”的完整流式链路时，Tool Calling 调用本工具做跨前后端的轻量静态文本分析。它会按 endpoint、
 * eventName、前端关键词或文件路径，从 workspaceRoot 全量扫描所有子模块（前端 .vue/.ts/.js/.tsx/.jsx 与后端
 * Java），定位前端 SSE 请求发起点、前端事件接收点、后端 Controller SSE 接口、Controller→Service 调用、
 * 后端 SSE 事件发送点，并在 maxDepth=2 时尝试展开 Service/Tool 一层调用。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 的模型 tool_call 或后端强制分析路由
 * -> ToolRegistry 根据 analyzeCode 找到统一工具
 * -> execute(arguments) 解析 path/endpoint/eventName/frontendKeyword/methodName/maxDepth
 * -> ProjectPathGuard 校验 workspace 边界与文件安全
 * -> ProjectJavaAnalysisSupport 复用 Java 方法、依赖、调用提取（不重复实现）
 * -> 返回 sse_event_chain_analysis JSON 给 finalAnswer 和 tool_call_log。</p>
 *
 * <p>边界说明：本工具只做静态文本级分析，不修改任何前端/后端业务代码，不访问 workspace 外路径，不读取敏感/二进制文件，
 * 不做完整精准 AST，不做运行时抓包，不调用外部服务，不接入 RAG/Milvus/向量化，不输出完整源码，也不生成风险分析、
 * 测试步骤或 patch；所有事件名、接口、类、文件、方法均来自真实扫描结果，找不到时只返回真实候选，绝不捏造。</p>
 */
@Slf4j // 仅输出分析器名、相对路径、事件名和失败原因，不打印完整源码或服务器绝对路径。
@Component // 注册为内部 Spring Bean，供 AnalyzeCodeTool 分发使用，不会被 ToolRegistry 暴露。
public class SseEventChainAnalyzer extends AbstractCodeAnalysisHandler { // P5.5 前后端 SSE 事件链路内部分析器。
    private static final String RESULT_TYPE = "sse_event_chain_analysis"; // 工具返回 JSON 的 type 字段。
    private static final String ANALYSIS_WARNING = "当前为轻量静态分析，跨前后端链路为候选结果，不保证百分百精准。"; // 统一不确定性说明。
    private static final int DEFAULT_MAX_DEPTH = 1; // 默认前端→Controller→Service 一层。
    private static final int MAX_ALLOWED_DEPTH = 2; // 第一版最多展开 Service/Tool 一层。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回项数量。
    private static final int MAX_ALLOWED_ITEMS = 300; // 防止返回过多项。
    private static final int MAX_SCAN_FILES = 5000; // 全 workspace 扫描文件兜底上限。
    private static final int MAX_CANDIDATE_FILES = 5; // 单个 Service 类型最多返回候选文件数量。
    private static final int MAX_DEPTH2_SERVICE_CALLS = 10; // maxDepth=2 时最多展开前 10 个 Service 调用。
    private static final int MAX_SNIPPET = 200; // 单条 snippet 最长字符数。

    private static final Set<String> SKIPPED_SCAN_DIRECTORIES = Set.of( // 全 workspace 扫描必须跳过的产物/缓存/运行数据目录。
            ".git", ".svn", ".hg", ".idea", ".vscode", ".codex-tmp",
            "node_modules", "target", "build", "dist", "out", ".gradle", ".mvn",
            "__pycache__", "pycache", "venv", ".venv",
            "coverage", ".cache", ".next", ".nuxt",
            "data", "uploads", "volumes", "logs", "log", "tmp", "temp"); // 与 ProjectPathGuard 敏感目录保持一致。
    private static final Set<String> FRONTEND_EXTENSIONS = Set.of( // 前端 SSE 扫描文件类型。
            "vue", "ts", "tsx", "js", "jsx", "mjs", "cjs", "html");
    private static final List<String> KNOWN_SSE_EVENT_NAMES = List.of( // 常见 SSE 事件名（仅用于辅助识别，不捏造）。
            "summary_result", "message", "done", "error", "tool_call", "tool_result");
    private static final Set<String> SSE_RETURN_TYPES = Set.of( // 后端 SSE 返回类型。
            "SseEmitter", "ResponseBodyEmitter", "StreamingResponseBody", "Flux", "ServerSentEvent");

    // 前端 SSE 发起点模式。
    private static final Pattern FE_EVENTSOURCE = Pattern.compile("new\\s+EventSource\\s*\\(([^)]*)\\)"); // new EventSource(...)。
    private static final Pattern FE_FETCH = Pattern.compile("\\bfetch\\s*\\(([^)]*)"); // fetch(...)。
    private static final Pattern FE_AXIOS = Pattern.compile("\\b(axios|http|request)\\s*\\.\\s*(post|get|put|delete)\\s*\\(([^)]*)"); // axios.post(...)。
    private static final Pattern FE_CUSTOM_WRAPPER = Pattern.compile("\\b(sendMessageStream|chatStream|streamChat|fetchEventSource|createSseConnection|sendMessage)\\s*\\("); // 自定义 SSE 包装方法调用。
    private static final Pattern FE_STRING_ARG = Pattern.compile("[\"'`](/[^\"'`]*)[\"'`]"); // 提取参数中的接口路径字符串。
    // 前端 SSE 接收点模式。
    private static final Pattern FE_ADD_LISTENER = Pattern.compile("addEventListener\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*(?:,\\s*([A-Za-z_$][\\w$]*))?"); // addEventListener("event", handler)。
    private static final Pattern FE_ONMESSAGE = Pattern.compile("\\.(onmessage|onerror|onopen)\\s*="); // source.onmessage = ...。
    private static final Pattern FE_EVENT_TYPE = Pattern.compile("(?:event\\.type|type|eventName|e\\.type)\\s*===?\\s*[\"']([^\"']+)[\"']"); // if (event.type === "summary_result")。
    private static final Pattern FE_HANDLER_FN = Pattern.compile("\\b(handleSummaryResult|handleToolCall|handleToolResult|handleSseEvent|handleStreamChunk|parseSse|updateStreamingMessage|appendMessage)\\b"); // 前端事件处理函数。
    private static final Pattern FE_FUNCTION_DECL = Pattern.compile("(?:function\\s+([A-Za-z_$][\\w$]*)|(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=|([A-Za-z_$][\\w$]*)\\s*\\([^)]*\\)\\s*\\{)"); // 前端函数声明（用于推导 functionName）。

    // 后端 Java 模式。
    private static final Pattern JAVA_CLASS = Pattern.compile("\\b(?:class|interface)\\s+([A-Za-z_$][\\w$]*)"); // 提取类名。
    private static final Pattern JAVA_REQUEST_MAPPING_BASE = Pattern.compile("@RequestMapping\\s*\\(\\s*(?:path\\s*=\\s*|value\\s*=\\s*)?[\"']([^\"']*)[\"']"); // 类级 basePath。
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b"); // 方法级映射注解。
    private static final Pattern MAPPING_PATH = Pattern.compile("(?:path\\s*=\\s*|value\\s*=\\s*)?[\"']([^\"']*)[\"']"); // 映射注解中的路径。
    private static final Pattern PRODUCES_SSE = Pattern.compile("TEXT_EVENT_STREAM_VALUE|text/event-stream"); // produces SSE。
    private static final Pattern EVENT_NAME_LITERAL = Pattern.compile("\\.(?:name|event)\\s*\\(\\s*[\"']([^\"']+)[\"']"); // .name("summary_result")。
    private static final Pattern EVENT_NAME_CONST = Pattern.compile("\\.(?:name|event)\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*\\)"); // .name(CONST)。
    private static final Pattern ON_TOOL_EVENT = Pattern.compile("onToolEvent\\s*\\(\\s*(?:[\"']([^\"']+)[\"']|([A-Za-z_$][\\w$]*))"); // callback.onToolEvent(name,...)。
    private static final Pattern CONST_STRING_DEF = Pattern.compile("\\b([A-Z_][A-Z0-9_]*)\\s*=\\s*[\"']([^\"']+)[\"']"); // 常量定义 XXX = "value"。
    private static final Pattern KNOWN_EVENT_IN_LINE = Pattern.compile("[\"'](summary_result|message|done|error|tool_call|tool_result)[\"']"); // 行内已知事件名字面量。
    private static final Set<String> TOOL_CALL_MARKERS = Set.of( // Service/Tool 一层链路的 Tool 调用标记。
            "ToolRegistry", "toolRegistry", "toolCallingService", "toolCallingChatService", "getTool", "callTool",
            "invokeTool", "runTool", "chatStream", "streamChat");

    private final ProjectPathGuard projectPathGuard; // workspace 路径安全守卫，负责边界、敏感路径和文件类型校验。
    private final ProjectJavaAnalysisSupport support; // P5 公共 Java 轻量解析组件，复用方法扫描、依赖与调用提取。

    public SseEventChainAnalyzer(ProjectPathGuard projectPathGuard,
                                 ProjectJavaAnalysisSupport support) { // 构造器注入安全守卫和公共解析器。
        this.projectPathGuard = projectPathGuard; // 保存 workspace 安全守卫。
        this.support = support; // 保存 Java 解析复用组件。
    }

    @Override // 返回 analyzeCode 内部分发类型。
    public CodeAnalysisType analysisType() {
        return CodeAnalysisType.SSE_EVENT_CHAIN; // 前后端 SSE 事件链路分析。
    }

    @Override // 返回内部分析器名。
    public String name() {
        return analysisType().name(); // 不再返回旧工具名。
    }

    @Override // 实现 AiTool 描述，供模型判断调用边界。
    public String description() {
        return "分析项目 workspace 内前后端 SSE 事件链路，定位前端 SSE 请求发起点、事件接收点、后端 Controller SSE 接口、Service 调用、Tool / 业务方法调用以及 SSE 事件发送逻辑。只做静态文本级分析，不修改文件，不访问 workspace 外路径，不做完整 AST，不保证百分百精准。"; // 明确前后端 SSE 链路能力和静态分析边界。
    }

    @Override // 实现工具参数 JSON Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // path/endpoint/eventName/frontendKeyword/methodName 都不放 required，业务层校验至少一个。
        addProperty(schema, "path", createStringProperty("可选，前端文件或后端 Controller / Service 源码文件的 workspace 相对路径"), false); // path 可选。
        addProperty(schema, "endpoint", createStringProperty("可选，SSE 接口路径，例如 /chat/message、/api/agent/chat"), false); // endpoint 可选。
        addProperty(schema, "eventName", createStringProperty("可选，SSE 事件名，例如 summary_result、message、error、done"), false); // eventName 可选。
        addProperty(schema, "frontendKeyword", createStringProperty("可选，前端关键词，例如 EventSource、fetch、onmessage、addEventListener、TextDecoder"), false); // frontendKeyword 可选。
        addProperty(schema, "methodName", createStringProperty("可选，后端 Controller 或 Service 方法名"), false); // methodName 可选。
        addProperty(schema, "maxDepth", createIntegerProperty("分析深度，可选，默认 1，最大 2。1 表示前端到 Controller 到 Service，2 表示尝试分析 Service / Tool 内部一层调用"), false); // maxDepth 可选。
        ObjectNode includeSnippetProperty = objectMapper.createObjectNode(); // boolean 参数需要手动构造 schema。
        includeSnippetProperty.put("type", "boolean"); // 标记 includeSnippet 类型。
        includeSnippetProperty.put("description", "是否包含少量代码片段，可选，默认 true"); // 说明片段开关。
        addProperty(schema, "includeSnippet", includeSnippetProperty, false); // includeSnippet 可选。
        addProperty(schema, "maxItems", createIntegerProperty("最多返回结果数量，可选，默认 100，最大 300"), false); // maxItems 可选。
        return schema; // 返回无 required 的 schema。
    }

    @Override // 执行 analyzeCode(SSE_EVENT_CHAIN) 内部分析器。
    public String execute(JsonNode arguments) {
        String pathArg = support.trimToNull(getOptionalText(arguments, "path", null)); // 可选源码文件路径。
        String endpoint = normalizeEndpoint(support.trimToNull(getOptionalText(arguments, "endpoint", null))); // 可选 SSE 接口路径，统一归一化。
        String eventName = support.trimToNull(getOptionalText(arguments, "eventName", null)); // 可选 SSE 事件名。
        String frontendKeyword = support.trimToNull(getOptionalText(arguments, "frontendKeyword", null)); // 可选前端关键词。
        String methodName = support.trimToNull(getOptionalText(arguments, "methodName", null)); // 可选后端方法名。
        int maxDepth = resolveMaxDepth(arguments); // 解析并限制分析深度。
        int maxItems = resolveMaxItems(arguments); // 解析并限制返回项上限。
        boolean includeSnippet = resolveIncludeSnippet(arguments); // 解析是否返回片段。

        if (pathArg == null && endpoint == null && eventName == null && frontendKeyword == null && methodName == null) { // 目标参数全空。
            return buildFailureResult(endpoint, eventName, "请提供 SSE 接口路径、事件名、前端关键词或相关文件路径。"); // 返回失败。
        }

        try {
            SseState state = new SseState(maxItems, includeSnippet, endpoint, eventName, frontendKeyword, methodName); // 汇总扫描状态与过滤条件。
            if (pathArg != null) { // 用户给了明确文件 path：只分析该文件（前端或后端）。
                analyzeSinglePath(pathArg, maxDepth, state); // 单文件分析。
            } else { // 否则跨前后端全项目扫描。
                scanWorkspace(maxDepth, state); // 全项目扫描。
            }
            return buildResult(pathArg, endpoint, eventName, frontendKeyword, methodName, maxDepth, state); // 构造结果 JSON。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[AnalyzeSseEventChainTool] access denied, path: {}, reason: {}", safe(pathArg), e.getMessage()); // 安全校验失败。
            return buildFailureResult(endpoint, eventName, normalizeFailureMessage(e.getMessage())); // 返回失败。
        } catch (Exception e) {
            log.error("[AnalyzeSseEventChainTool] analyze failed, endpoint: {}, eventName: {}", safe(endpoint), safe(eventName), e); // 系统错误保留堆栈但不打印正文。
            return buildFailureResult(endpoint, eventName, "SSE 事件链路分析失败，请稍后重试。"); // 返回失败。
        }
    }

    // ===================== 单文件 path 分析 =====================

    private void analyzeSinglePath(String pathArg, int maxDepth, SseState state) { // 用户给定 path 时只分析该文件。
        Path file = support.resolveReadableProjectFile(pathArg); // 安全解析路径或唯一定位文件。
        projectPathGuard.validateReadableCodeFile(file); // 校验 workspace、敏感路径、扩展名、普通文件和大小限制。
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString(); // 文件名。
        String extension = projectPathGuard.getExtension(fileName); // 扩展名。
        List<String> lines = readLines(file); // 读取源码行。
        if (lines == null) { // 读取失败。
            return; // 跳过。
        }
        state.matchedBy = "PATH_SCOPED_SCAN"; // 标记按 path 限定分析。
        if (FRONTEND_EXTENSIONS.contains(extension)) { // 前端文件。
            analyzeFrontendFile(file, lines, state); // 重点分析前端 SSE 调用/接收逻辑。
        } else if ("java".equals(extension)) { // 后端 Java 文件。
            List<String> code = support.stripComments(lines); // 去注释后用于结构分析。
            collectBackendConstants(code, state); // 收集常量映射（用于事件名解析）。
            analyzeBackendJavaFile(file, lines, code, maxDepth, state); // 分析后端 SSE 接口、Service 调用、事件发送。
        }
    }

    // ===================== 全项目扫描 =====================

    private void scanWorkspace(int maxDepth, SseState state) { // 从 workspaceRoot 全量扫描所有子模块前后端文件。
        List<Path> frontendFiles = new ArrayList<>(); // 前端文件列表。
        List<Path> javaFiles = new ArrayList<>(); // 后端 Java 文件列表。
        Path root = projectPathGuard.getWorkspaceRoot(); // workspace 根目录（含所有子模块）。
        log.info("[SseScan] workspaceRoot={}", root); // 打印扫描范围，便于确认覆盖全部子模块。
        int[] counter = {0}; // 已扫描文件计数，用于上限保护。
        collectFiles(root, frontendFiles, javaFiles, counter, state); // 递归收集前端与后端文件。
        log.info("[SseScan] scanned files: {}, frontend: {}, java: {}", counter[0], frontendFiles.size(), javaFiles.size()); // 打印扫描数量。

        for (Path file : frontendFiles) { // 扫描前端文件。
            List<String> lines = readLines(file); // 读取前端源码。
            if (lines != null) { // 读取成功。
                analyzeFrontendFile(file, lines, state); // 提取前端 SSE 调用点与事件接收点。
            }
        }
        List<JavaFileModel> controllerModels = new ArrayList<>(); // 后端 Controller 候选模型（供后续 Controller→Service）。
        for (Path file : javaFiles) { // 先收集全局常量映射，保证 .name(CONST) 能解析事件名。
            List<String> lines = readLines(file); // 读取 Java 源码。
            if (lines == null) { // 读取失败。
                continue; // 跳过。
            }
            List<String> code = support.stripComments(lines); // 去注释。
            collectBackendConstants(code, state); // 收集常量映射。
            controllerModels.add(new JavaFileModel(file, lines, code)); // 缓存，避免重复读取/去注释。
        }
        for (JavaFileModel model : controllerModels) { // 再分析后端 SSE 接口、Service 调用、事件发送。
            analyzeBackendJavaFile(model.file, model.rawLines, model.codeLines, maxDepth, state); // 复用缓存的去注释结果。
        }
    }

    private void collectFiles(Path directory,
                              List<Path> frontendFiles,
                              List<Path> javaFiles,
                              int[] counter,
                              SseState state) { // 递归收集安全前端/后端源码文件，跳过敏感/产物目录。
        if (counter[0] >= MAX_SCAN_FILES || !isSafeScanDirectory(directory)) { // 达到上限或目录不安全时停止。
            if (counter[0] >= MAX_SCAN_FILES) { // 文件过多。
                state.truncated = true; // 标记结果可能不完整。
            }
            return; // 不进入。
        }
        try (Stream<Path> stream = Files.list(directory)) { // 只列当前目录。
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName() == null ? "" : path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList(); // 稳定排序。
            for (Path child : children) { // 遍历子项。
                if (counter[0] >= MAX_SCAN_FILES) { // 达到上限。
                    state.truncated = true; // 标记截断。
                    return; // 停止。
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { // 子目录递归。
                    collectFiles(child, frontendFiles, javaFiles, counter, state); // 递归扫描所有子模块。
                    continue; // 处理下一个。
                }
                if (!isSafeFile(child)) { // 非安全源码文件跳过。
                    continue; // 跳过敏感/二进制/非白名单文件。
                }
                String ext = projectPathGuard.getExtension(child.getFileName().toString()); // 扩展名。
                if (FRONTEND_EXTENSIONS.contains(ext)) { // 前端文件。
                    frontendFiles.add(child.toAbsolutePath().normalize()); // 收集前端文件。
                    counter[0]++; // 计数。
                } else if ("java".equals(ext)) { // 后端 Java 文件。
                    javaFiles.add(child.toAbsolutePath().normalize()); // 收集 Java 文件。
                    counter[0]++; // 计数。
                }
            }
        } catch (IOException e) {
            log.debug("[SseScan] skip unreadable directory while scanning SSE chain"); // 不打印绝对路径。
        }
    }

    // ===================== 前端 SSE 识别 =====================

    private void analyzeFrontendFile(Path file, List<String> lines, SseState state) { // 识别前端 SSE 发起点和事件接收点。
        String relativePath = support.toWorkspaceRelativePath(file, ""); // 相对路径。
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString(); // 文件名。
        String framework = resolveFramework(fileName); // 前端框架类型。
        boolean fileHasStreamConsume = fileContainsStreamConsume(lines); // 文件内是否有 reader.read/TextDecoder 等流式消费。
        for (int i = 0; i < lines.size(); i++) { // 逐行扫描。
            String line = lines.get(i); // 当前行。
            int lineNumber = i + 1; // 行号。
            collectFrontendCall(relativePath, framework, line, lineNumber, fileHasStreamConsume, state); // 提取前端 SSE 发起点。
            collectFrontendHandler(relativePath, line, lineNumber, lines, state); // 提取前端事件接收点。
        }
    }

    private void collectFrontendCall(String relativePath,
                                     String framework,
                                     String line,
                                     int lineNumber,
                                     boolean fileHasStreamConsume,
                                     SseState state) { // 提取前端 SSE 请求发起点。
        String callType = null; // 调用类型。
        String endpoint = null; // 接口路径。
        Matcher es = FE_EVENTSOURCE.matcher(line); // EventSource。
        if (es.find()) { // 命中 EventSource。
            callType = "EventSource"; // 标记类型。
            endpoint = firstPathLiteral(es.group(1)); // 提取 url 字面量。
        }
        if (callType == null) { // 未命中再试 fetch。
            Matcher fe = FE_FETCH.matcher(line); // fetch。
            if (fe.find()) { // 命中 fetch。
                callType = fileHasStreamConsume ? "fetch-stream" : "fetch"; // 文件内有流式消费时标记 fetch-stream。
                endpoint = firstPathLiteral(fe.group(1)); // 提取接口路径。
            }
        }
        if (callType == null) { // 再试 axios。
            Matcher ax = FE_AXIOS.matcher(line); // axios/http/request。
            if (ax.find()) { // 命中。
                callType = "axios"; // 标记类型。
                endpoint = firstPathLiteral(ax.group(3)); // 提取接口路径。
            }
        }
        if (callType == null) { // 再试自定义包装方法。
            Matcher cw = FE_CUSTOM_WRAPPER.matcher(line); // 自定义包装。
            if (cw.find() && (fileHasStreamConsume || line.contains("fetch") || line.contains("EventSource"))) { // 仅在文件含流式消费时关联。
                callType = "custom-wrapper"; // 标记类型。
            }
        }
        if (callType == null) { // 不是 SSE 发起点。
            return; // 跳过。
        }
        if (state.endpoint != null && endpoint != null && !endpointMatches(endpoint, state.endpoint)) { // endpoint 过滤。
            return; // 不匹配则跳过。
        }
        if (state.frontendKeyword != null && !line.toLowerCase(Locale.ROOT).contains(state.frontendKeyword.toLowerCase(Locale.ROOT))
                && callType.toLowerCase(Locale.ROOT).indexOf(state.frontendKeyword.toLowerCase(Locale.ROOT)) < 0) { // frontendKeyword 过滤。
            return; // 不匹配则跳过。
        }
        ObjectNode node = objectMapper.createObjectNode(); // 前端调用点 JSON。
        node.put("filePath", relativePath); // 相对路径。
        node.put("framework", framework); // 框架类型。
        node.put("callType", callType); // 调用类型。
        node.put("endpoint", endpoint == null ? "" : endpoint); // 接口路径。
        node.put("method", resolveHttpMethod(line)); // HTTP 方法。
        node.put("functionName", ""); // functionName 第一版留空，避免误判。
        node.put("lineNumber", lineNumber); // 行号。
        if (state.includeSnippet) { // 片段开关。
            node.put("snippet", snippet(line)); // 调用片段。
        }
        if (endpoint != null) { // 记录真实接口路径，供失败候选。
            state.allEndpoints.add(endpoint); // 收集。
        }
        addLimited(state.frontendCalls, node, state); // 写入前端调用点。
    }

    private void collectFrontendHandler(String relativePath,
                                        String line,
                                        int lineNumber,
                                        List<String> lines,
                                        SseState state) { // 提取前端 SSE 事件接收/消费点。
        String eventName = null; // 事件名。
        String handlerName = null; // 处理函数名。
        Matcher al = FE_ADD_LISTENER.matcher(line); // addEventListener。
        if (al.find()) { // 命中 addEventListener。
            eventName = al.group(1); // 事件名。
            handlerName = al.group(2); // 处理函数。
        }
        if (eventName == null) { // onmessage/onerror。
            Matcher om = FE_ONMESSAGE.matcher(line); // onmessage 等。
            if (om.find()) { // 命中。
                eventName = "onmessage".equals(om.group(1)) ? "message" : ("onerror".equals(om.group(1)) ? "error" : "open"); // 映射事件名。
            }
        }
        if (eventName == null) { // if (event.type === "xxx")。
            Matcher et = FE_EVENT_TYPE.matcher(line); // 事件分发。
            if (et.find()) { // 命中。
                eventName = et.group(1); // 事件名。
            }
        }
        if (eventName == null) { // 单独的处理函数声明也作为接收点。
            Matcher hf = FE_HANDLER_FN.matcher(line); // 处理函数。
            if (hf.find() && line.contains("function") || (hf.find() && line.contains("="))) { // 仅声明行。
                handlerName = hf.group(1); // 处理函数名。
                eventName = inferEventFromHandler(handlerName); // 从处理函数名推断事件名。
            }
        }
        if (eventName == null) { // 不是事件接收点。
            return; // 跳过。
        }
        if (state.eventName != null && !state.eventName.equalsIgnoreCase(eventName)) { // eventName 过滤。
            return; // 不匹配则跳过。
        }
        ObjectNode node = objectMapper.createObjectNode(); // 前端事件接收点 JSON。
        node.put("filePath", relativePath); // 相对路径。
        node.put("eventName", eventName); // 事件名。
        node.put("handlerName", handlerName == null ? "" : handlerName); // 处理函数名。
        node.put("lineNumber", lineNumber); // 行号。
        if (state.includeSnippet) { // 片段开关。
            node.put("snippet", snippet(line)); // 接收片段。
        }
        state.allEvents.add(eventName); // 收集真实事件名。
        addLimited(state.frontendEventHandlers, node, state); // 写入事件接收点。
    }

    // ===================== 后端 SSE 识别 =====================

    private void analyzeBackendJavaFile(Path file,
                                        List<String> rawLines,
                                        List<String> code,
                                        int maxDepth,
                                        SseState state) { // 分析后端 SSE 接口、Controller→Service、事件发送。
        String relativePath = support.toWorkspaceRelativePath(file, ""); // 相对路径。
        String className = firstMatch(code, JAVA_CLASS); // 类名。
        boolean isController = isControllerCandidate(relativePath, code); // 是否 Controller 候选。
        List<JavaMethod> methods = support.scanJavaMethods(code); // 复用方法扫描。
        collectBackendEventSenders(relativePath, className, code, methods, state); // 提取后端事件发送点。
        if (isController) { // Controller 文件才提取 SSE 接口和 Service 调用。
            analyzeBackendController(file, relativePath, className, code, methods, maxDepth, state); // 提取 SSE 接口与 Controller→Service。
        }
    }

    private void analyzeBackendController(Path file,
                                          String relativePath,
                                          String className,
                                          List<String> code,
                                          List<JavaMethod> methods,
                                          int maxDepth,
                                          SseState state) { // 提取后端 Controller SSE 接口与 Service 调用。
        String basePath = firstMatch(code, JAVA_REQUEST_MAPPING_BASE); // 类级 basePath。
        List<JavaField> dependencies = support.extractDependencies(code, methods, className); // 复用依赖提取。
        Map<String, String> depTypeByName = new LinkedHashMap<>(); // fieldName -> type。
        for (JavaField dep : dependencies) { // 构建依赖映射。
            depTypeByName.put(dep.getFieldName(), dep.getType()); // 记录。
        }
        for (JavaMethod method : methods) { // 遍历方法找 SSE 接口。
            String mappingAnnotation = findMappingAnnotation(method.getAnnotations()); // 方法映射注解。
            if (mappingAnnotation == null) { // 非接口方法。
                continue; // 跳过。
            }
            String returnType = support.simpleTypeName(method.getReturnType()); // 返回类型简单名。
            boolean sseByReturn = SSE_RETURN_TYPES.contains(returnType); // 返回类型是否 SSE。
            boolean sseByProduces = PRODUCES_SSE.matcher(mappingAnnotation).find(); // produces 是否 SSE。
            boolean sseByBody = methodBodyHasSseSend(code, method); // 方法体是否有 emitter.send/complete。
            if (!sseByReturn && !sseByProduces && !sseByBody) { // 不是 SSE 接口。
                continue; // 跳过普通接口。
            }
            String endpoint = joinPaths(basePath, extractMappingPath(mappingAnnotation)); // 完整接口路径。
            state.allEndpoints.add(endpoint); // 收集真实接口。
            if (state.endpoint != null && !endpointMatches(endpoint, state.endpoint)) { // endpoint 过滤。
                continue; // 不匹配则跳过。
            }
            if (state.methodName != null && !state.methodName.equals(method.getName())) { // methodName 过滤。
                continue; // 不匹配则跳过。
            }
            ObjectNode node = objectMapper.createObjectNode(); // 后端 SSE 接口 JSON。
            node.put("controllerClass", className); // Controller 类名。
            node.put("controllerPath", relativePath); // Controller 相对路径。
            node.put("httpMethod", httpMethodFromMapping(mappingAnnotation)); // HTTP 方法。
            node.put("endpoint", endpoint); // 接口路径。
            node.put("controllerMethod", method.getName()); // Controller 方法。
            node.put("returnType", returnType); // 返回类型。
            node.put("sseType", resolveSseType(returnType, sseByProduces)); // SSE 类型。
            node.put("lineNumber", method.getLineNumber()); // 行号。
            node.put("mappingAnnotation", mappingAnnotation); // 映射注解原文。
            addLimited(state.backendSseEndpoints, node, state); // 写入后端 SSE 接口。
            collectServiceCalls(relativePath, method, code, depTypeByName, maxDepth, state); // 提取 Controller→Service 调用。
        }
    }

    private void collectServiceCalls(String controllerPath,
                                     JavaMethod method,
                                     List<String> code,
                                     Map<String, String> depTypeByName,
                                     int maxDepth,
                                     SseState state) { // 复用依赖+调用提取得到 Controller→Service 调用。
        for (JavaCall call : support.extractCalls(code, method)) { // 复用方法体调用提取。
            String receiver = call.getReceiver(); // 调用对象。
            if (receiver == null) { // 裸调用不是 Service 调用。
                continue; // 跳过。
            }
            String serviceType = depTypeByName.get(receiver); // 命中注入依赖。
            if (serviceType == null) { // 不是注入依赖（局部变量/工具类）。
                continue; // 跳过。
            }
            ObjectNode node = objectMapper.createObjectNode(); // Service 调用 JSON。
            node.put("controllerMethod", method.getName()); // Controller 方法。
            node.put("serviceObject", receiver); // Service 对象。
            node.put("serviceType", serviceType); // Service 类型。
            node.put("serviceMethod", call.getCallee()); // Service 方法。
            node.put("lineNumber", call.getLineNumber()); // 行号。
            ArrayNode candidates = buildCandidateFiles(serviceType); // Service/ServiceImpl 候选文件。
            node.set("candidateServiceFiles", candidates); // 候选文件。
            addLimited(state.serviceCalls, node, state); // 写入 Service 调用。
            if (maxDepth >= MAX_ALLOWED_DEPTH && state.depth2Count < MAX_DEPTH2_SERVICE_CALLS) { // maxDepth=2 时展开一层。
                state.depth2Count++; // 计数。
                analyzeServiceImplToolCalls(serviceType, call.getCallee(), state); // 分析 ServiceImpl 一层 Tool 调用。
            }
        }
    }

    private void analyzeServiceImplToolCalls(String serviceType, String serviceMethod, SseState state) { // maxDepth=2 时分析 ServiceImpl 一层 Tool/业务调用。
        Path impl = pickImplPath(serviceType); // 选取 ServiceImpl 候选。
        if (impl == null) { // 没有实现类候选。
            return; // 跳过。
        }
        try {
            projectPathGuard.validateReadableCodeFile(impl); // 校验安全。
            List<String> lines = Files.readAllLines(impl, StandardCharsets.UTF_8); // 读取 ServiceImpl。
            List<String> code = support.stripComments(lines); // 去注释。
            String implRelative = support.toWorkspaceRelativePath(impl, ""); // 相对路径。
            List<JavaMethod> methods = support.scanJavaMethods(code); // 扫描方法。
            JavaMethod target = findMethod(methods, serviceMethod); // 定位对应方法。
            if (target == null || !target.hasBody()) { // 没有方法体。
                return; // 跳过。
            }
            for (JavaCall call : support.extractCalls(code, target)) { // 提取一层调用。
                if (!isToolCall(call)) { // 仅保留 Tool/Registry 相关调用。
                    continue; // 跳过普通调用。
                }
                ObjectNode node = objectMapper.createObjectNode(); // Tool 调用 JSON。
                node.put("fromFile", implRelative); // 来源文件。
                node.put("fromMethod", serviceMethod); // 来源方法。
                String toolName = extractToolNameLiteral(code, call.getLineNumber()); // 尝试提取 getTool("X") 字面量。
                node.put("toolName", toolName == null ? "" : toolName); // 工具名（可空）。
                node.put("toolClassCandidate", toolName == null ? "" : firstCandidate(toolName)); // 候选 Tool 文件。
                node.put("lineNumber", call.getLineNumber()); // 行号。
                addLimited(state.toolCalls, node, state); // 写入 Tool 调用。
            }
        } catch (Exception e) {
            log.debug("[SseScan] skip unreadable ServiceImpl while expanding tool calls"); // 单文件失败不影响整体。
        }
    }

    private void collectBackendEventSenders(String relativePath,
                                            String className,
                                            List<String> code,
                                            List<JavaMethod> methods,
                                            SseState state) { // 提取后端 SSE 事件发送点。
        for (int i = 0; i < code.size(); i++) { // 逐行扫描去注释代码。
            String line = code.get(i); // 当前行。
            int lineNumber = i + 1; // 行号。
            String eventName = extractSenderEventName(line, state); // 提取该行发送的事件名。
            if (eventName == null) { // 非事件发送行。
                continue; // 跳过。
            }
            state.allEvents.add(eventName); // 收集真实事件名。
            if (state.eventName != null && !state.eventName.equalsIgnoreCase(eventName)) { // eventName 过滤。
                continue; // 不匹配则跳过。
            }
            ObjectNode node = objectMapper.createObjectNode(); // 事件发送点 JSON。
            node.put("eventName", eventName); // 事件名。
            node.put("senderFile", relativePath); // 来源文件。
            node.put("senderClass", className); // 来源类。
            node.put("senderMethod", enclosingMethodName(methods, lineNumber)); // 来源方法（近似：最近声明方法）。
            node.put("lineNumber", lineNumber); // 行号。
            node.put("sendType", resolveSendType(line)); // 发送方式。
            if (state.includeSnippet) { // 片段开关。
                node.put("snippet", snippet(line)); // 发送片段。
            }
            addLimited(state.backendEventSenders, node, state); // 写入事件发送点。
        }
    }

    private String extractSenderEventName(String line, SseState state) { // 从单行提取 SSE 发送事件名（字面量或常量）。
        Matcher lit = EVENT_NAME_LITERAL.matcher(line); // .name("x")/.event("x")。
        if (lit.find()) { // 命中字面量。
            return lit.group(1); // 返回事件名。
        }
        Matcher constMatcher = EVENT_NAME_CONST.matcher(line); // .name(CONST)。
        if (constMatcher.find()) { // 命中常量引用。
            String resolved = state.constMap.get(constMatcher.group(1)); // 解析常量值。
            if (resolved != null) { // 解析成功。
                return resolved; // 返回事件名。
            }
        }
        Matcher onTool = ON_TOOL_EVENT.matcher(line); // onToolEvent(name,...)。
        if (onTool.find()) { // 命中。
            if (onTool.group(1) != null) { // 字面量。
                return onTool.group(1); // 返回事件名。
            }
            String resolved = state.constMap.get(onTool.group(2)); // 常量解析。
            if (resolved != null) { // 解析成功。
                return resolved; // 返回事件名。
            }
        }
        if (hasSseSendContext(line)) { // 仅在 SSE 发送上下文中接受已知事件名字面量。
            Matcher known = KNOWN_EVENT_IN_LINE.matcher(line); // 已知事件名字面量。
            if (known.find()) { // 命中。
                return known.group(1); // 返回事件名。
            }
        }
        return null; // 非事件发送行。
    }

    private boolean hasSseSendContext(String line) { // 判断该行是否处于 SSE 发送上下文，避免普通 "message" 字面量误判。
        return line.contains(".send(") || line.contains("emitter") || line.contains("Emitter")
                || line.contains("ServerSentEvent") || line.contains(".accept(") || line.contains("onToolEvent"); // 命中发送上下文。
    }

    private void collectBackendConstants(List<String> code, SseState state) { // 收集 String 常量定义，供 .name(CONST) 解析事件名。
        for (String line : code) { // 逐行。
            Matcher m = CONST_STRING_DEF.matcher(line); // 常量定义。
            while (m.find()) { // 逐个。
                String value = m.group(2); // 常量值。
                if (KNOWN_SSE_EVENT_NAMES.contains(value) || m.group(1).contains("EVENT")) { // 仅收集事件类常量，减少噪声。
                    state.constMap.putIfAbsent(m.group(1), value); // 记录常量映射。
                }
            }
        }
    }

    // ===================== 结果构造 =====================

    private String buildResult(String pathArg,
                               String endpoint,
                               String eventName,
                               String frontendKeyword,
                               String methodName,
                               int maxDepth,
                               SseState state) { // 构造成功/失败结果 JSON。
        boolean anyResult = !state.frontendCalls.isEmpty() || !state.frontendEventHandlers.isEmpty()
                || !state.backendSseEndpoints.isEmpty() || !state.backendEventSenders.isEmpty(); // 是否有任何命中。
        if (!anyResult) { // 全空时返回失败 + 真实候选。
            return buildNoMatchResult(endpoint, eventName, state); // 返回未找到 + candidateEndpoints/candidateEvents。
        }
        ObjectNode result = objectMapper.createObjectNode(); // 结果对象。
        result.put("type", RESULT_TYPE); // 结果类型。
        result.put("success", true); // 标记成功。
        if (endpoint != null) { // 写入 endpoint。
            result.put("endpoint", endpoint); // 接口路径。
        } else {
            result.putNull("endpoint"); // 无 endpoint。
        }
        if (eventName != null) { // 写入 eventName。
            result.put("eventName", eventName); // 事件名。
        } else {
            result.putNull("eventName"); // 无 eventName。
        }
        result.put("matchedBy", resolveMatchedBy(pathArg, endpoint, eventName, frontendKeyword, state)); // 匹配方式。
        result.put("maxDepth", maxDepth); // 分析深度。
        result.set("frontendCalls", state.frontendCalls); // 前端调用点。
        result.set("frontendEventHandlers", state.frontendEventHandlers); // 前端事件接收点。
        result.set("backendSseEndpoints", state.backendSseEndpoints); // 后端 SSE 接口。
        result.set("serviceCalls", state.serviceCalls); // Controller→Service 调用。
        result.set("backendEventSenders", state.backendEventSenders); // 后端事件发送点。
        result.set("toolCalls", state.toolCalls); // Service→Tool 候选链路。
        ArrayNode warnings = objectMapper.createArrayNode(); // 不确定性说明。
        warnings.add(ANALYSIS_WARNING); // 统一警告。
        if (state.frontendCalls.isEmpty() && state.frontendEventHandlers.isEmpty()) { // 没有前端命中时补充说明。
            warnings.add("未在 workspace 中扫描到前端 SSE 调用/接收点（可能项目未包含前端模块或前端代码不在 workspace 内）。"); // 真实说明。
        }
        result.set("warnings", warnings); // 挂载 warnings。
        result.put("truncated", state.truncated); // 标记是否截断。
        return result.toString(); // 返回 JSON 字符串。
    }

    private String buildNoMatchResult(String endpoint, String eventName, SseState state) { // 未命中时返回真实候选。
        ObjectNode result = objectMapper.createObjectNode(); // 结果对象。
        result.put("type", RESULT_TYPE); // 结果类型。
        result.put("success", false); // 标记失败。
        result.put("endpoint", endpoint == null ? "" : endpoint); // 接口路径。
        if (eventName != null) { // 事件名。
            result.put("eventName", eventName); // 写入。
        } else {
            result.putNull("eventName"); // 无事件名。
        }
        result.put("message", "未在项目中找到匹配的 SSE 前后端链路。"); // 失败说明。
        result.set("candidateEndpoints", toArray(state.allEndpoints)); // 真实扫描到的 SSE 接口候选。
        result.set("candidateEvents", toArray(state.allEvents)); // 真实扫描到的事件名候选。
        return result.toString(); // 返回 JSON 字符串。
    }

    private String resolveMatchedBy(String pathArg,
                                    String endpoint,
                                    String eventName,
                                    String frontendKeyword,
                                    SseState state) { // 计算 matchedBy。
        if (pathArg != null) { // 单文件分析。
            return "PATH_SCOPED_SCAN"; // 按 path 限定。
        }
        if (endpoint != null && eventName != null) { // endpoint + eventName。
            return "ENDPOINT_AND_EVENT_GLOBAL_SCAN"; // 全项目联合扫描。
        }
        if (endpoint != null) { // 仅 endpoint。
            return "ENDPOINT_GLOBAL_SCAN"; // 全项目 endpoint 扫描。
        }
        if (eventName != null) { // 仅 eventName。
            return "EVENT_GLOBAL_SCAN"; // 全项目事件扫描。
        }
        if (frontendKeyword != null) { // 仅前端关键词。
            return "FRONTEND_KEYWORD_GLOBAL_SCAN"; // 全项目前端扫描。
        }
        return "GLOBAL_SCAN"; // 兜底全项目扫描。
    }

    // ===================== 复用与工具方法 =====================

    private ArrayNode buildCandidateFiles(String serviceType) { // 复用 support 查找 Service/ServiceImpl 候选文件。
        ArrayNode arr = objectMapper.createArrayNode(); // 候选数组。
        for (Path p : support.findTypeCandidatePaths(serviceType, MAX_CANDIDATE_FILES)) { // 复用类型候选定位。
            arr.add(support.toWorkspaceRelativePath(p, "")); // 相对路径。
        }
        return arr; // 返回候选。
    }

    private Path pickImplPath(String serviceType) { // 选取 *Impl.java 候选实现类。
        for (Path p : support.findTypeCandidatePaths(serviceType, MAX_CANDIDATE_FILES)) { // 复用候选定位。
            String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT); // 文件名小写。
            if (name.endsWith("impl.java")) { // 命中实现类。
                return p; // 返回实现类路径。
            }
        }
        return null; // 无实现类候选。
    }

    private String firstCandidate(String toolName) { // 根据 toolName 推导候选 Tool 文件（首选）。
        String className = capitalize(toolName) + "Tool"; // 约定 toolName -> XxxTool。
        List<Path> paths = support.findTypeCandidatePaths(className, 1); // 复用候选定位。
        return paths.isEmpty() ? "" : support.toWorkspaceRelativePath(paths.get(0), ""); // 返回相对路径。
    }

    private boolean isToolCall(JavaCall call) { // 判断一层调用是否是 Tool/Registry 相关调用。
        if (call.getReceiver() != null && TOOL_CALL_MARKERS.contains(call.getReceiver())) { // 接收者命中 Tool 标记。
            return true; // 是 Tool 调用。
        }
        return call.getCallee() != null && TOOL_CALL_MARKERS.contains(call.getCallee()); // 方法名命中 Tool 标记。
    }

    private String extractToolNameLiteral(List<String> code, int lineNumber) { // 提取 getTool("X")/callTool("X") 的 toolName 字面量。
        if (lineNumber <= 0 || lineNumber > code.size()) { // 越界保护。
            return null; // 返回 null。
        }
        Matcher m = Pattern.compile("(?:getTool|callTool|invokeTool|runTool)\\s*\\(\\s*[\"']([^\"']+)[\"']").matcher(code.get(lineNumber - 1)); // 工具名字面量。
        return m.find() ? m.group(1) : null; // 返回 toolName。
    }

    private boolean methodBodyHasSseSend(List<String> code, JavaMethod method) { // 判断方法体是否有 SSE 发送/完成调用（近似：声明行到下一方法前）。
        int start = method.getLineNumber(); // 方法声明行（1 基）。
        int end = Math.min(code.size(), start + 200); // 限制扫描范围，避免误判过大。
        for (int i = start; i < end && i < code.size(); i++) { // 扫描方法体附近。
            String line = code.get(i); // 当前行。
            if (line.contains("emitter.send") || line.contains("Emitter.send") || line.contains("ServerSentEvent")
                    || line.contains(".completeWithError") || line.contains("emitter.complete")) { // SSE 发送/完成。
                return true; // 是 SSE 方法体。
            }
        }
        return false; // 不是。
    }

    private boolean fileContainsStreamConsume(List<String> lines) { // 判断前端文件是否包含流式消费逻辑。
        for (String line : lines) { // 逐行。
            if (line.contains("reader.read") || line.contains("getReader") || line.contains("TextDecoder")
                    || line.contains("ReadableStream") || line.contains("response.body")) { // 流式消费标记。
                return true; // 含流式消费。
            }
        }
        return false; // 不含。
    }

    private boolean isControllerCandidate(String relativePath, List<String> code) { // 判断 Java 文件是否 Controller 候选。
        String lower = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT); // 相对路径小写。
        if (lower.endsWith("controller.java") || lower.contains("/controller/")) { // 文件名/目录命中。
            return true; // 是候选。
        }
        for (String line : code) { // 扫描注解特征。
            if (line.contains("@RestController") || line.contains("@Controller") || line.contains("@RequestMapping")) { // 命中。
                return true; // 是候选。
            }
        }
        return false; // 不是。
    }

    private String findMappingAnnotation(List<String> annotations) { // 在方法注解中找映射注解原文。
        if (annotations == null) { // 空注解。
            return null; // 返回 null。
        }
        for (String annotation : annotations) { // 遍历。
            if (annotation != null && MAPPING_ANNOTATION.matcher(annotation).find()) { // 命中映射注解。
                return annotation.trim(); // 返回原文。
            }
        }
        return null; // 非接口方法。
    }

    private String extractMappingPath(String annotation) { // 从映射注解提取路径。
        if (annotation == null) { // 空注解。
            return ""; // 返回空。
        }
        Matcher m = MAPPING_PATH.matcher(annotation); // 路径字面量。
        return m.find() ? m.group(1) : ""; // 返回路径。
    }

    private String httpMethodFromMapping(String annotation) { // 从映射注解推断 HTTP 方法。
        if (annotation == null) { // 空注解。
            return "REQUEST"; // 兜底。
        }
        if (annotation.contains("@GetMapping")) { // GET。
            return "GET";
        }
        if (annotation.contains("@PostMapping")) { // POST。
            return "POST";
        }
        if (annotation.contains("@PutMapping")) { // PUT。
            return "PUT";
        }
        if (annotation.contains("@DeleteMapping")) { // DELETE。
            return "DELETE";
        }
        if (annotation.contains("@PatchMapping")) { // PATCH。
            return "PATCH";
        }
        Matcher m = Pattern.compile("RequestMethod\\.([A-Z]+)").matcher(annotation); // @RequestMapping(method=...)。
        return m.find() ? m.group(1) : "REQUEST"; // 取 RequestMethod。
    }

    private String resolveSseType(String returnType, boolean sseByProduces) { // 推断 sseType。
        if ("SseEmitter".equals(returnType)) { // SseEmitter。
            return "SSE_EMITTER";
        }
        if ("ResponseBodyEmitter".equals(returnType)) { // ResponseBodyEmitter。
            return "RESPONSE_BODY_EMITTER";
        }
        if ("StreamingResponseBody".equals(returnType)) { // StreamingResponseBody。
            return "STREAMING_RESPONSE_BODY";
        }
        if ("Flux".equals(returnType) || "ServerSentEvent".equals(returnType)) { // Flux/ServerSentEvent。
            return "FLUX";
        }
        return sseByProduces ? "FETCH_STREAM" : "SSE_EMITTER"; // produces text/event-stream 但返回普通类型时按 fetch-stream。
    }

    private String resolveSendType(String line) { // 推断后端发送方式。
        if (line.contains("ServerSentEvent")) { // ServerSentEvent。
            return "ServerSentEvent";
        }
        if (line.contains("onToolEvent")) { // callback.onToolEvent。
            return "callback.onToolEvent";
        }
        if (line.contains(".accept(")) { // callback.accept。
            return "callback.accept";
        }
        return "emitter.send"; // 默认 emitter.send。
    }

    private String enclosingMethodName(List<JavaMethod> methods, int lineNumber) { // 近似定位某行所属的顶层方法（最近声明 <= 行号）。
        String best = ""; // 当前最佳方法名。
        int bestLine = -1; // 当前最佳声明行。
        for (JavaMethod method : methods) { // 遍历方法。
            if (method.getLineNumber() <= lineNumber && method.getLineNumber() > bestLine) { // 取声明行最大且不超过目标行。
                bestLine = method.getLineNumber(); // 更新。
                best = method.getName(); // 更新。
            }
        }
        return best; // 返回近似所属方法。
    }

    private JavaMethod findMethod(List<JavaMethod> methods, String name) { // 按名查找方法。
        if (methods == null || name == null) { // 空值。
            return null; // 返回 null。
        }
        for (JavaMethod method : methods) { // 遍历。
            if (name.equals(method.getName())) { // 命中。
                return method; // 返回。
            }
        }
        return null; // 未找到。
    }

    private String joinPaths(String basePath, String methodPath) { // 拼接类级和方法级路径。
        String base = normalizeEndpointSegment(basePath); // 归一化 basePath。
        String method = normalizeEndpointSegment(methodPath); // 归一化方法路径。
        if (base.isEmpty()) { // 无 basePath。
            return method.isEmpty() ? "/" : method; // 方法路径或根。
        }
        if (method.isEmpty() || "/".equals(method)) { // 无方法路径。
            return base; // 用 basePath。
        }
        return (base + "/" + method.substring(1)).replaceAll("/{2,}", "/"); // 拼接并压缩斜杠。
    }

    private String normalizeEndpointSegment(String path) { // 归一化路径段，确保以 / 开头。
        String value = path == null ? "" : path.trim(); // 去空白。
        if (value.isEmpty()) { // 空。
            return ""; // 返回空。
        }
        return value.startsWith("/") ? value : "/" + value; // 补斜杠。
    }

    private String normalizeEndpoint(String endpoint) { // 归一化用户 endpoint。
        if (endpoint == null || endpoint.isBlank()) { // 空。
            return null; // 返回 null。
        }
        String value = endpoint.trim(); // 去空白。
        int q = value.indexOf('?'); // 去 query。
        if (q >= 0) { // 命中。
            value = value.substring(0, q); // 截断。
        }
        value = value.replace('\\', '/').replaceAll("/{2,}", "/"); // 统一斜杠。
        if (!value.startsWith("/")) { // 补斜杠。
            value = "/" + value; // 补。
        }
        while (value.length() > 1 && value.endsWith("/")) { // 去尾斜杠。
            value = value.substring(0, value.length() - 1); // 删除。
        }
        return value.isBlank() ? null : value; // 返回。
    }

    private boolean endpointMatches(String candidate, String requested) { // 判断接口路径是否匹配（精确或后缀/包含弱匹配）。
        String c = normalizeEndpoint(candidate); // 归一化候选。
        String r = normalizeEndpoint(requested); // 归一化请求。
        if (c == null || r == null) { // 空。
            return false; // 不匹配。
        }
        return c.equals(r) || c.endsWith(r) || r.endsWith(c); // 精确或后缀弱匹配。
    }

    private String firstPathLiteral(String args) { // 从参数文本提取第一个接口路径字符串。
        if (args == null) { // 空。
            return null; // 返回 null。
        }
        Matcher m = FE_STRING_ARG.matcher(args); // 路径字面量。
        return m.find() ? m.group(1) : null; // 返回路径。
    }

    private String resolveHttpMethod(String line) { // 推断前端请求方法。
        String lower = line.toLowerCase(Locale.ROOT); // 小写。
        if (lower.contains("method") && lower.contains("post") || lower.contains(".post(")) { // POST。
            return "POST";
        }
        if (lower.contains("method") && lower.contains("get") || lower.contains(".get(")) { // GET。
            return "GET";
        }
        if (lower.contains("eventsource")) { // EventSource 固定 GET。
            return "GET";
        }
        return "POST"; // SSE 默认 POST（按本项目实际）。
    }

    private String resolveFramework(String fileName) { // 推断前端框架。
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT); // 小写。
        if (lower.endsWith(".vue")) { // Vue。
            return "Vue";
        }
        if (lower.endsWith(".tsx")) { // React TSX。
            return "React";
        }
        if (lower.endsWith(".jsx")) { // React JSX。
            return "React";
        }
        if (lower.endsWith(".ts")) { // TypeScript。
            return "TypeScript";
        }
        return "JavaScript"; // 兜底。
    }

    private String inferEventFromHandler(String handlerName) { // 从处理函数名推断事件名。
        if (handlerName == null) { // 空。
            return "message"; // 兜底 message。
        }
        String lower = handlerName.toLowerCase(Locale.ROOT); // 小写。
        if (lower.contains("summary")) { // summary_result。
            return "summary_result";
        }
        if (lower.contains("toolcall")) { // tool_call。
            return "tool_call";
        }
        if (lower.contains("toolresult")) { // tool_result。
            return "tool_result";
        }
        if (lower.contains("error")) { // error。
            return "error";
        }
        if (lower.contains("done")) { // done。
            return "done";
        }
        return "message"; // 兜底。
    }

    private boolean isSafeScanDirectory(Path directory) { // 判断目录是否可进入扫描。
        if (directory == null) { // 空。
            return false; // 不安全。
        }
        Path normalized = directory.toAbsolutePath().normalize(); // 规范化。
        String name = normalized.getFileName() == null ? "" : normalized.getFileName().toString(); // 目录名。
        return projectPathGuard.isInsideWorkspace(normalized)
                && !Files.isSymbolicLink(normalized)
                && !projectPathGuard.isSensitivePath(normalized)
                && !SKIPPED_SCAN_DIRECTORIES.contains(name)
                && Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS); // workspace 内、非软链、非敏感、非产物目录。
    }

    private boolean isSafeFile(Path file) { // 判断文件是否安全可读源码文件。
        if (file == null || file.getFileName() == null) { // 空。
            return false; // 不安全。
        }
        Path normalized = file.toAbsolutePath().normalize(); // 规范化。
        String fileName = file.getFileName().toString(); // 文件名。
        String ext = projectPathGuard.getExtension(fileName); // 扩展名。
        return projectPathGuard.isInsideWorkspace(normalized)
                && !Files.isSymbolicLink(normalized)
                && !projectPathGuard.isSensitivePath(normalized)
                && !projectPathGuard.isBlockedFilename(fileName)
                && !projectPathGuard.isBlockedExtension(ext)
                && projectPathGuard.isAllowedExtension(ext)
                && Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS); // 只允许安全白名单源码文件。
    }

    private List<String> readLines(Path file) { // 安全读取 UTF-8 源码行，失败返回 null。
        try {
            projectPathGuard.validateReadableCodeFile(file); // 统一安全校验。
            return Files.readAllLines(file, StandardCharsets.UTF_8); // 读取。
        } catch (MalformedInputException e) {
            log.debug("[SseScan] skip non-utf8 file"); // 非 UTF-8 跳过。
            return null; // 返回 null。
        } catch (Exception e) {
            log.debug("[SseScan] skip unreadable file"); // 不可读跳过。
            return null; // 返回 null。
        }
    }

    private String firstMatch(List<String> code, Pattern pattern) { // 在代码行中找第一个匹配组。
        for (String line : code) { // 逐行。
            Matcher m = pattern.matcher(line); // 匹配。
            if (m.find()) { // 命中。
                return m.group(1); // 返回第一组。
            }
        }
        return ""; // 未命中。
    }

    private ArrayNode toArray(Set<String> values) { // 集合转 JSON 数组（去重、限量）。
        ArrayNode arr = objectMapper.createArrayNode(); // 数组。
        int count = 0; // 计数。
        for (String value : values) { // 遍历。
            if (count >= MAX_ALLOWED_ITEMS) { // 上限。
                break; // 停止。
            }
            arr.add(value); // 加入。
            count++; // 计数。
        }
        return arr; // 返回。
    }

    private void addLimited(ArrayNode arr, ObjectNode node, SseState state) { // 写入并执行 maxItems 限制。
        if (arr == null || node == null || state == null) { // 空值。
            return; // 跳过。
        }
        if (state.items >= state.maxItems) { // 达到上限。
            state.truncated = true; // 标记截断。
            return; // 不再写入。
        }
        arr.add(node); // 写入。
        state.items++; // 计数。
    }

    private String snippet(String line) { // 生成代码片段。
        String text = line == null ? "" : line.trim(); // 去空白。
        return text.length() <= MAX_SNIPPET ? text : text.substring(0, MAX_SNIPPET) + "...[truncated]"; // 截断。
    }

    private String capitalize(String value) { // 首字母大写。
        if (value == null || value.isBlank()) { // 空。
            return ""; // 返回空。
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1); // 首字母大写。
    }

    private int resolveMaxDepth(JsonNode arguments) { // 解析并限制 maxDepth。
        int maxDepth = getOptionalInt(arguments, "maxDepth", DEFAULT_MAX_DEPTH); // 默认 1。
        if (maxDepth < DEFAULT_MAX_DEPTH) { // 小于 1。
            return DEFAULT_MAX_DEPTH; // 返回 1。
        }
        return Math.min(maxDepth, MAX_ALLOWED_DEPTH); // 最大 2。
    }

    private int resolveMaxItems(JsonNode arguments) { // 解析并限制 maxItems。
        int maxItems = getOptionalInt(arguments, "maxItems", DEFAULT_MAX_ITEMS); // 默认 100。
        if (maxItems <= 0) { // 非法值。
            return DEFAULT_MAX_ITEMS; // 返回 100。
        }
        return Math.min(maxItems, MAX_ALLOWED_ITEMS); // 最大 300。
    }

    private boolean resolveIncludeSnippet(JsonNode arguments) { // 解析 includeSnippet，默认 true。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // 空。
            return true; // 默认 true。
        }
        JsonNode valueNode = arguments.path("includeSnippet"); // 读取。
        return valueNode.isMissingNode() || valueNode.isNull() || valueNode.asBoolean(true); // 默认 true。
    }

    private String normalizeFailureMessage(String message) { // 规范化失败文案。
        if (message == null || message.isBlank()) { // 空。
            return "SSE 事件链路分析失败。"; // 兜底。
        }
        if (message.contains("workspace 外")) { // 越界。
            return "不允许访问 workspace 外的路径。"; // 不暴露真实路径。
        }
        return message; // 其它受控错误保持原文案。
    }

    private String buildFailureResult(String endpoint, String eventName, String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 结果对象。
        result.put("type", RESULT_TYPE); // 类型。
        result.put("success", false); // 失败。
        result.put("endpoint", endpoint == null ? "" : endpoint); // 接口路径。
        if (eventName != null) { // 事件名。
            result.put("eventName", eventName); // 写入。
        } else {
            result.putNull("eventName"); // 无。
        }
        result.put("message", message == null || message.isBlank() ? "SSE 事件链路分析失败，请稍后重试。" : message); // 失败说明。
        result.set("candidateEndpoints", objectMapper.createArrayNode()); // 空候选。
        result.set("candidateEvents", objectMapper.createArrayNode()); // 空候选。
        return result.toString(); // 返回。
    }

    private String safe(String value) { // 日志安全文本。
        return value == null ? "" : value.replace('\\', '/'); // 不展开绝对路径。
    }

    /**
     * SSE 扫描状态：汇总过滤条件、结果数组、常量映射和计数。
     */
    private final class SseState { // 扫描状态（内部类便于直接构造 JSON 节点）。
        private final int maxItems; // 返回项上限。
        private final boolean includeSnippet; // 是否带片段。
        private final String endpoint; // endpoint 过滤。
        private final String eventName; // eventName 过滤。
        private final String frontendKeyword; // 前端关键词过滤。
        private final String methodName; // 方法名过滤。
        private final ArrayNode frontendCalls = objectMapper.createArrayNode(); // 前端调用点。
        private final ArrayNode frontendEventHandlers = objectMapper.createArrayNode(); // 前端事件接收点。
        private final ArrayNode backendSseEndpoints = objectMapper.createArrayNode(); // 后端 SSE 接口。
        private final ArrayNode serviceCalls = objectMapper.createArrayNode(); // Controller→Service 调用。
        private final ArrayNode backendEventSenders = objectMapper.createArrayNode(); // 后端事件发送点。
        private final ArrayNode toolCalls = objectMapper.createArrayNode(); // Service→Tool 候选。
        private final Set<String> allEndpoints = new LinkedHashSet<>(); // 真实接口候选集合。
        private final Set<String> allEvents = new LinkedHashSet<>(); // 真实事件候选集合。
        private final Map<String, String> constMap = new LinkedHashMap<>(); // 常量名->事件名映射。
        private int items; // 已写入项数量。
        private int depth2Count; // 已展开 Service 调用数量。
        private boolean truncated; // 是否截断。
        private String matchedBy; // 匹配方式（单文件场景）。

        private SseState(int maxItems, boolean includeSnippet, String endpoint, String eventName,
                         String frontendKeyword, String methodName) { // 构造扫描状态。
            this.maxItems = maxItems; // 保存上限。
            this.includeSnippet = includeSnippet; // 保存片段开关。
            this.endpoint = endpoint; // 保存 endpoint 过滤。
            this.eventName = eventName; // 保存 eventName 过滤。
            this.frontendKeyword = frontendKeyword; // 保存前端关键词过滤。
            this.methodName = methodName; // 保存方法名过滤。
        }
    }

    /**
     * 后端 Java 文件缓存模型：避免重复读取与去注释。
     */
    private static final class JavaFileModel { // Java 文件缓存。
        private final Path file; // 文件路径。
        private final List<String> rawLines; // 原始行。
        private final List<String> codeLines; // 去注释行。

        private JavaFileModel(Path file, List<String> rawLines, List<String> codeLines) { // 构造。
            this.file = file; // 保存路径。
            this.rawLines = rawLines; // 保存原始行。
            this.codeLines = codeLines; // 保存去注释行。
        }
    }
}
