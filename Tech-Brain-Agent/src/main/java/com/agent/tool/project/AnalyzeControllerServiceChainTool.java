package com.agent.tool.project;

import com.agent.security.ProjectPathGuard;
import com.agent.tool.project.ProjectJavaAnalysisSupport.JavaCall;
import com.agent.tool.project.ProjectJavaAnalysisSupport.JavaField;
import com.agent.tool.project.ProjectJavaAnalysisSupport.JavaMethod;
import com.agent.toolcalling.project.language.CodeLanguageRegistry;
import com.agent.toolcalling.support.AbstractAiTool;
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
 * analyzeControllerServiceChain Spring Controller → Service 调用链分析工具（P5.3）。
 *
 * <p>适用场景：当用户在聊天中要求分析某个 Spring Controller 的接口到 Service 的链路、接口背后调用了哪些 Service、
 * 某个 /api 接口后面调用了哪些方法时，Tool Calling 调用本工具做静态文本级分析，提取接口路径、Controller 方法、
 * Service 调用、Service 接口和 ServiceImpl 候选文件，maxDepth=2 时再尝试分析 ServiceImpl 对应方法内部的一层调用。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 识别模型 tool_call 或后端强制 analyzeControllerServiceChain 路由
 * -> ToolRegistry 根据工具名获取 AnalyzeControllerServiceChainTool
 * -> execute(arguments) 解析 path/endpoint/methodName/maxDepth/includeSnippet/maxItems
 * -> ProjectPathGuard 校验 workspace 边界与敏感文件
 * -> 复用 ProjectJavaAnalysisSupport 完成去注释、方法扫描、依赖提取、调用提取、候选文件定位
 * -> 返回 controller_service_chain_analysis JSON 给模型和 tool_call_log。</p>
 *
 * <p>边界说明：本工具属于 Tech-Brain-Agent 项目代码业务工具，只做静态文本级分析，不修改文件，不生成/应用 patch，
 * 不做完整 AST、不做全项目精准调用图、不做 Tool→Service 链路、不做前后端 SSE 链路、不做风险分析、不做测试步骤、
 * 不返回完整源码，不接入 RAG/Milvus/向量化，不返回服务器绝对路径，候选 ServiceImpl 为候选结果不保证百分百精准。</p>
 */
@Slf4j // 输出 [AnalyzeControllerServiceChainTool] 前缀日志，不打印服务器绝对路径和完整文件内容。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动发现 analyzeControllerServiceChain 工具。
public class AnalyzeControllerServiceChainTool extends AbstractAiTool { // Controller → Service 调用链分析业务工具。
    private static final String TOOL_NAME = "analyzeControllerServiceChain"; // 工具名称必须和模型 tool_call.function.name 一致。
    private static final String RESULT_TYPE = "controller_service_chain_analysis"; // 工具返回 JSON 类型。
    private static final int DEFAULT_MAX_DEPTH = 1; // 默认分析深度（Controller → Service）。
    private static final int MAX_ALLOWED_DEPTH = 2; // 最大分析深度（额外分析 ServiceImpl 一层调用）。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回的接口和调用项数量。
    private static final int MAX_ALLOWED_ITEMS = 300; // 最大允许返回的接口和调用项数量。
    private static final int MAX_CANDIDATE_FILES = 5; // 单个依赖最多返回的候选文件数量。
    private static final int MAX_DEPTH2_SERVICE_CALLS = 10; // maxDepth=2 时最多分析的 serviceCall 数量。
    private static final int MAX_ENDPOINT_CANDIDATES = 20; // endpoint 未命中或多命中时最多返回 20 个候选接口。
    private static final String ANALYSIS_WARNING = "当前为轻量静态分析，ServiceImpl 目标为候选路径，不保证百分百精准。"; // 统一不确定性说明。
    private static final Set<String> SKIPPED_SCAN_DIRECTORIES = Set.of( // endpoint-only 扫描 Controller 时必须跳过的目录（只跳产物/缓存/运行数据，绝不跳子模块、src、controller 包）。
            ".git", ".svn", ".hg", ".idea", ".vscode", ".codex-tmp",
            "node_modules", "target", "build", "dist", "out", ".gradle", ".mvn",
            "__pycache__", "pycache", "venv", ".venv",
            "coverage", ".cache", ".next", ".nuxt",
            "data", "uploads", "volumes", "logs", "log", "tmp", "temp"); // 与 ProjectPathGuard 敏感目录保持一致，覆盖各子模块的产物与缓存目录。

    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"); // 提取类名。
    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;"); // 提取 package。
    private static final String[] MAPPING_ANNOTATIONS = { // 方法级映射注解。
            "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping", "@RequestMapping"};

    private final ProjectPathGuard projectPathGuard; // P4.1 workspace 路径安全守卫，用于文件安全校验。
    private final ProjectJavaAnalysisSupport support; // P5 Java 解析复用支持组件，避免重复编写解析原语。

    public AnalyzeControllerServiceChainTool(ProjectPathGuard projectPathGuard,
                                             ProjectJavaAnalysisSupport support) { // 构造器注入路径守卫和解析支持组件。
        this.projectPathGuard = projectPathGuard; // 保存路径安全守卫。
        this.support = support; // 保存解析支持组件。
    }

    @Override // 实现 AiTool 工具名。
    public String name() {
        return TOOL_NAME; // 固定返回 analyzeControllerServiceChain。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return "分析项目 workspace 内 Spring Controller 到 Service 的轻量调用链，提取接口路径、Controller 方法、Service 调用、Service 接口和实现类候选路径。只做静态文本级分析，不修改文件，不访问 workspace 外路径，不做完整 AST，不保证百分百精准。"; // 给模型判断调用时机。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "path", createStringProperty("可选。相对于项目 workspace 的 Controller 源码文件路径，例如 Tech-Brain-Agent/src/main/java/com/agent/controller/AgentController.java"), false); // path 可选，endpoint-only 场景不再强制要求。
        addProperty(schema, "endpoint", createStringProperty("可选。要分析的接口路径，例如 /api/agent/chat、/chat/message"), false); // endpoint 可选，可单独触发 Controller 扫描。
        addProperty(schema, "methodName", createStringProperty("可选。指定要分析的 Controller 方法名，例如 chatStream、sendMessage"), false); // methodName 可选。
        addProperty(schema, "maxDepth", createIntegerProperty("分析深度，可选，默认 1，最大 2。1 表示 Controller 到 Service，2 表示尝试分析 ServiceImpl 内部一层调用"), false); // maxDepth 可选。
        ObjectNode includeSnippetProperty = objectMapper.createObjectNode(); // bool 字段直接构造。
        includeSnippetProperty.put("type", "boolean"); // 标记类型为 boolean。
        includeSnippetProperty.put("description", "是否包含少量声明代码片段，可选，默认 true"); // 字段说明。
        addProperty(schema, "includeSnippet", includeSnippetProperty, false); // includeSnippet 可选。
        addProperty(schema, "maxItems", createIntegerProperty("最多返回接口和调用项数量，可选，默认 100，最大 300"), false); // maxItems 可选。
        return schema; // 返回完整参数 Schema。
    }

    @Override // 执行 analyzeControllerServiceChain 工具。
    public String execute(JsonNode arguments) {
        String requestedPath = support.trimToNull(getOptionalText(arguments, "path", null)); // 读取 Controller 文件路径。
        String endpointFilter = normalizeEndpoint(support.trimToNull(getOptionalText(arguments, "endpoint", null))); // 可选接口路径过滤，统一归一化。
        String methodFilter = support.trimToNull(getOptionalText(arguments, "methodName", null)); // 可选 Controller 方法过滤。
        int maxDepth = resolveMaxDepth(arguments); // 解析并限制分析深度。
        int maxItems = resolveMaxItems(arguments); // 解析并限制接口/调用项上限。
        boolean includeSnippet = resolveIncludeSnippet(arguments); // 解析是否返回代码片段。
        if (endpointFilter == null && isEndpointOnlyPath(requestedPath)) { // 兼容模型误把 /api/xxx 放进 path 的情况。
            endpointFilter = normalizeEndpoint(requestedPath); // 将错误的 path 转为 endpoint。
            requestedPath = null; // 清空 path，避免继续按文件读取。
        }
        if (requestedPath == null && endpointFilter == null) { // path 和 endpoint 都缺失时才失败。
            return buildFailureResult("", null, "请提供 Controller 文件路径或接口路径。"); // 返回结构化失败 JSON。
        }

        try {
            ControllerEndpointMatch endpointResolvedMatch = null; // endpoint-only 自动定位出的 Controller 匹配项。
            if (requestedPath == null) { // endpoint-only：扫描 workspace 内 Controller 自动定位。
                EndpointSearchResult endpointSearchResult = searchControllerEndpoint(endpointFilter, methodFilter); // 搜索 Controller Mapping。
                if (endpointSearchResult.multipleMatches()) { // 多个 Controller 命中不能乱选。
                    return buildEndpointFailureResult(endpointFilter,
                            "找到多个可能匹配 " + endpointFilter + " 的 Controller，请指定 Controller 文件路径。",
                            endpointSearchResult.matches()); // 返回多个匹配候选。
                }
                if (endpointSearchResult.singleMatch()) { // 精确命中或唯一弱匹配。
                    endpointResolvedMatch = endpointSearchResult.firstMatch(); // 记录匹配 Controller。
                    requestedPath = endpointResolvedMatch.controllerPath; // 后续复用既有 path 分析流程。
                } else { // 未找到 endpoint。
                    return buildEndpointFailureResult(endpointFilter,
                            "未在项目 Controller 中找到该接口路径。",
                            endpointSearchResult.candidates()); // 返回相似接口候选。
                }
            }
            Path filePath = endpointResolvedMatch == null
                    ? support.resolveReadableProjectFile(requestedPath)
                    : endpointResolvedMatch.filePath; // endpoint-only 已有安全扫描结果时直接使用匹配文件。
            projectPathGuard.validateReadableCodeFile(filePath); // 统一校验 workspace、敏感路径、扩展名、普通文件和大小限制。
            String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 文件名。
            String extension = projectPathGuard.getExtension(fileName); // 小写扩展名。
            String relativePath = support.toWorkspaceRelativePath(filePath, requestedPath); // 相对 workspace 路径。
            if (!"java".equals(extension)) { // 第一版只支持 Java Spring Controller。
                return buildFailureResult(relativePath, endpointFilter, "当前 Controller → Service 分析仅支持 Java Spring Controller。"); // 返回提示。
            }
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8); // 读取源码行。
            List<String> codeLines = support.stripComments(lines); // 去注释，避免逐行尾注释干扰正则。
            String language = CodeLanguageRegistry.resolveByFileName(fileName).getDisplayName(); // 语言展示名。

            String packageName = extractPackageName(codeLines); // 提取 packageName。
            int classLineIndex = findClassLineIndex(codeLines); // 定位类声明行。
            String className = classLineIndex < 0 ? "" : extractClassName(codeLines.get(classLineIndex)); // 提取 className。
            List<String> classAnnotations = collectClassAnnotations(codeLines, classLineIndex); // 收集类级注解。
            String basePath = extractBasePath(classAnnotations); // 提取类级 basePath。

            if (!isSpringController(classAnnotations, className, relativePath)) { // 校验是否为 Spring Controller。
                return buildFailureResult(relativePath, endpointFilter,
                        "该文件未识别为 Spring Controller（无 @RestController/@Controller、类名不以 Controller 结尾、路径不含 controller），如需分析普通类调用链请使用 analyzeCallChain。"); // 返回提示。
            }

            List<JavaMethod> methods = support.scanJavaMethods(codeLines); // 扫描方法声明、方法体范围和注解。
            List<JavaField> dependencies = support.extractDependencies(codeLines, methods, className); // 提取依赖 Service 字段。
            Map<String, JavaField> dependencyByName = new LinkedHashMap<>(); // 字段名到依赖的映射，供 Service 调用归类。
            for (JavaField dependency : dependencies) { // 构建依赖映射。
                dependencyByName.put(dependency.getFieldName(), dependency); // 记录字段名 -> 依赖。
            }

            ObjectNode result = buildBaseResult(relativePath, fileName, language, packageName, className, basePath,
                    classAnnotations, maxDepth, Files.size(filePath), extension); // 构造结果骨架。
            result.put("matchedControllerPath", relativePath); // 明确写入最终匹配到的 Controller 文件。
            if (endpointFilter != null) { // 指定 endpoint 时把用户输入的接口路径写回结果。
                result.put("endpoint", endpointFilter); // tool_call_log result_json 可直接看到 endpoint。
            }
            if (endpointResolvedMatch != null) { // endpoint-only 命中时记录实际匹配到的 Mapping。
                result.put("matchedEndpoint", endpointResolvedMatch.path); // 记录完整匹配接口路径。
                result.put("matchedControllerClass", className); // 记录全项目扫描命中的 Controller 类名。
                result.put("matchedBy", "ENDPOINT_GLOBAL_SCAN"); // 标记由全项目 endpoint 扫描定位，而非传入 path。
            }
            ChainState state = new ChainState(maxItems); // 初始化接口/调用项计数。

            String endpointForAnalysis = endpointResolvedMatch == null ? endpointFilter : endpointResolvedMatch.path; // 弱匹配时用实际 Mapping 精准过滤。
            List<EndpointInfo> endpoints = buildEndpoints(methods, basePath, endpointForAnalysis, methodFilter, result, state); // 提取并过滤接口端点。
            if (endpointForAnalysis != null && endpoints.isEmpty()) { // path 指定 Controller 但 endpoint 未命中。
                return buildEndpointFailureResult(endpointFilter,
                        "未在指定 Controller 中找到该接口路径。",
                        collectControllerEndpointMatches(filePath, null, methodFilter)); // 返回该 Controller 的候选接口。
            }
            fillDependencies(dependencies, result, state); // 写入依赖对象及候选文件。
            List<ServiceCallInfo> serviceCalls = buildServiceCalls(endpoints, codeLines, dependencyByName, includeSnippet, result, state); // 提取 Controller 方法中的 Service 调用。
            if (maxDepth >= MAX_ALLOWED_DEPTH) { // maxDepth=2 时分析 ServiceImpl 一层调用。
                analyzeServiceImplCalls(serviceCalls, result, state); // 读取 ServiceImpl 候选并提取一层调用。
            }
            result.put("truncated", state.truncated); // 标记是否按 maxItems 截断。
            return result.toString(); // 返回结构化 JSON 字符串。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[AnalyzeControllerServiceChainTool] access denied, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // 安全校验失败不打印堆栈。
            return buildFailureResult(requestedPath, endpointFilter, normalizeFailureMessage(e.getMessage())); // 返回友好失败 JSON。
        } catch (MalformedInputException e) {
            log.warn("[AnalyzeControllerServiceChainTool] non utf8 file, path: {}", safeRelativePath(requestedPath)); // 编码不支持时记录相对路径。
            return buildFailureResult(requestedPath, endpointFilter, "文件编码暂不支持，请确认文件为 UTF-8 文本。"); // 返回友好失败 JSON。
        } catch (IOException e) {
            log.warn("[AnalyzeControllerServiceChainTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // IO 失败时只打印相对路径。
            return buildFailureResult(requestedPath, endpointFilter, "读取文件失败，请稍后重试。"); // 返回友好失败 JSON。
        } catch (Exception e) {
            log.error("[AnalyzeControllerServiceChainTool] analyze failed, path: {}", safeRelativePath(requestedPath), e); // 系统错误保留堆栈但不打印正文。
            return buildFailureResult(requestedPath, endpointFilter, "Controller 调用链分析失败，请稍后重试。"); // 返回友好失败 JSON。
        }
    }

    // ===================== endpoint-only Controller 自动定位 =====================

    private EndpointSearchResult searchControllerEndpoint(String endpointFilter,
                                                          String methodFilter) { // 在 workspace 内按 endpoint 搜索 Controller Mapping。
        String normalizedEndpoint = normalizeEndpoint(endpointFilter); // 归一化用户输入接口路径。
        List<ControllerEndpointMatch> allEndpoints = scanWorkspaceControllerEndpoints(methodFilter); // 扫描全部 Controller 端点。
        List<ControllerEndpointMatch> exactMatches = new ArrayList<>(); // 精确匹配（优先级最高）。
        List<ControllerEndpointMatch> pathVariableMatches = new ArrayList<>(); // 路径变量匹配（次优先）。
        List<ControllerEndpointMatch> weakMatches = new ArrayList<>(); // /batch 与 /article/batch 这类后缀弱匹配（最低）。
        for (ControllerEndpointMatch endpoint : allEndpoints) { // 遍历 Controller 端点。
            String matchType = endpointMatchType(endpoint.path, normalizedEndpoint); // 判断端点匹配类型。
            if ("EXACT".equals(matchType)) { // 精确匹配。
                exactMatches.add(endpoint.withMatchType(matchType)); // 记录精确匹配。
            } else if ("PATH_VARIABLE".equals(matchType)) { // 路径变量匹配。
                pathVariableMatches.add(endpoint.withMatchType(matchType)); // 记录路径变量匹配。
            } else if ("WEAK".equals(matchType)) { // 弱匹配只有唯一时才能自动选择。
                weakMatches.add(endpoint.withMatchType(matchType)); // 记录弱匹配。
            }
        }
        // 严格按 精确 > 路径变量 > 弱匹配 的优先级分层选择，避免 /article/batch 同时命中 /article/{id} 被判为多命中。
        List<ControllerEndpointMatch> matches = !exactMatches.isEmpty()
                ? exactMatches
                : (!pathVariableMatches.isEmpty() ? pathVariableMatches : weakMatches); // 高优先级层非空即采用该层结果。
        List<ControllerEndpointMatch> candidates = matches.isEmpty()
                ? chooseSimilarEndpointCandidates(normalizedEndpoint, allEndpoints)
                : matches; // 没命中时返回相似候选，多命中时返回命中候选。
        if (matches.size() == 1) { // 唯一命中时打印真实匹配结果。
            ControllerEndpointMatch matched = matches.get(0); // 唯一匹配项。
            log.info("[ControllerScan] endpoint matched: endpoint={}, controller={}, path={}",
                    normalizedEndpoint, matched.controllerPath, matched.path); // 只打印相对路径与接口路径。
        } else if (matches.isEmpty()) { // 全项目都没命中时打印候选数量，绝不脑补 Controller。
            log.info("[ControllerScan] endpoint not found: {}, candidates={}", normalizedEndpoint, candidates.size()); // 记录未找到与真实候选数量。
        } else { // 多命中需要用户指定。
            log.info("[ControllerScan] endpoint multiple matches: endpoint={}, count={}", normalizedEndpoint, matches.size()); // 记录多命中数量。
        }
        return new EndpointSearchResult(matches, candidates); // 返回搜索结果。
    }

    private List<ControllerEndpointMatch> scanWorkspaceControllerEndpoints(String methodFilter) { // 扫描 workspace 内全部 Controller endpoint。
        List<ControllerEndpointMatch> endpoints = new ArrayList<>(); // 收集结果。
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // 获取项目 workspace 根目录（含所有子模块）。
        log.info("[ControllerScan] workspaceRoot={}", workspaceRoot); // 打印 workspace 根目录，便于确认扫描范围。
        log.info("[ControllerScan] scanning all modules under workspace"); // 明确从根目录全量扫描所有子模块，不限制层级。
        collectControllerEndpoints(workspaceRoot, methodFilter, endpoints); // 从根目录递归扫描，无深度限制。
        endpoints.sort(Comparator
                .comparing((ControllerEndpointMatch match) -> match.controllerPath, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(match -> match.path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(match -> match.controllerMethod, String.CASE_INSENSITIVE_ORDER)); // 稳定排序，避免候选顺序抖动。
        log.info("[ControllerScan] total endpoints found across all modules: {}", endpoints.size()); // 打印全量扫描到的 endpoint 数量。
        return endpoints; // 返回全部端点。
    }

    private void collectControllerEndpoints(Path directory,
                                            String methodFilter,
                                            List<ControllerEndpointMatch> endpoints) { // 递归扫描安全目录中的 Controller endpoint。
        if (!isSafeScanDirectory(directory)) { // 不安全或不可读目录不进入。
            log.debug("[ControllerScan] skip dir={}", directory == null || directory.getFileName() == null ? directory : directory.getFileName()); // 只打印目录名，不打印绝对路径正文。
            return; // 跳过该目录。
        }
        try (Stream<Path> stream = Files.list(directory)) { // 只列当前目录，便于显式跳过敏感目录。
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName() == null ? "" : path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList(); // 目录项稳定排序。
            for (Path child : children) { // 遍历子项。
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { // 子目录递归。
                    collectControllerEndpoints(child, methodFilter, endpoints); // 继续扫描子目录。
                    continue; // 处理下一个子项。
                }
                if (isSafeJavaFile(child)) { // 只分析安全 Java 文件。
                    endpoints.addAll(collectControllerEndpointMatches(child, null, methodFilter)); // 从候选 Controller 中提取 endpoint。
                }
            }
        } catch (IOException e) {
            log.debug("[AnalyzeControllerServiceChainTool] skip unreadable directory while scanning Controller endpoints"); // 不打印绝对路径。
        }
    }

    private List<ControllerEndpointMatch> collectControllerEndpointMatches(Path filePath,
                                                                          String endpointFilter,
                                                                          String methodFilter) { // 从单个 Controller 文件提取 endpoint 候选。
        List<ControllerEndpointMatch> endpoints = new ArrayList<>(); // 单文件 endpoint 结果。
        try {
            projectPathGuard.validateReadableCodeFile(filePath); // 复用统一源码文件安全校验。
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8); // 读取候选 Controller。
            String relativePath = support.toWorkspaceRelativePath(filePath, ""); // 转成 workspace 相对路径。
            if (!isControllerCandidateFile(filePath, relativePath, lines)) { // 非 Controller 候选文件不做方法扫描。
                return endpoints; // 返回空列表。
            }
            List<String> codeLines = support.stripComments(lines); // 去掉注释，减少 Mapping 误判。
            int classLineIndex = findClassLineIndex(codeLines); // 定位类声明。
            String className = classLineIndex < 0 ? "" : extractClassName(codeLines.get(classLineIndex)); // 提取 Controller 类名。
            List<String> classAnnotations = collectClassAnnotations(codeLines, classLineIndex); // 收集类级注解。
            if (!isSpringController(classAnnotations, className, relativePath)) { // 最终确认 Controller 候选。
                return endpoints; // 不是 Controller 时跳过。
            }
            String basePath = extractBasePath(classAnnotations); // 提取类级 basePath。
            log.debug("[ControllerScan] controller candidate={}", relativePath); // 命中真实 Controller 文件，记录相对路径。
            for (JavaMethod method : support.scanJavaMethods(codeLines)) { // 遍历 Controller 方法。
                String mappingAnnotation = findMappingAnnotation(method.getAnnotations()); // 查找 Mapping 注解。
                if (mappingAnnotation == null) { // 无 Mapping 注解不是接口方法。
                    continue; // 跳过。
                }
                if (methodFilter != null && !methodFilter.equals(method.getName())) { // methodName 过滤仍然生效。
                    continue; // 跳过非目标方法。
                }
                String path = joinPaths(basePath, extractAnnotationPath(mappingAnnotation)); // 拼接完整 endpoint。
                if (endpointFilter != null && endpointMatchType(path, endpointFilter) == null) { // 单 Controller 候选构造时可按 endpoint 过滤。
                    continue; // 不匹配则跳过。
                }
                log.debug("[ControllerScan] endpoint found: {} {} -> {}", httpMethodFromMapping(mappingAnnotation), path, relativePath); // 记录真实扫描到的 endpoint。
                endpoints.add(new ControllerEndpointMatch(filePath, relativePath, httpMethodFromMapping(mappingAnnotation),
                        path, method.getName(), method.getLineNumber(), mappingAnnotation, "CANDIDATE")); // 记录候选 endpoint（全部来自真实文件）。
            }
        } catch (Exception e) {
            log.debug("[AnalyzeControllerServiceChainTool] skip unreadable Controller candidate while scanning endpoints"); // 单个文件失败不影响全局扫描。
        }
        return endpoints; // 返回单文件 endpoints。
    }

    private boolean isControllerCandidateFile(Path filePath,
                                              String relativePath,
                                              List<String> lines) { // 判断 Java 文件是否值得作为 Controller 候选解析。
        String fileName = filePath == null || filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 文件名。
        String relative = relativePath == null ? "" : relativePath.replace('\\', '/').toLowerCase(Locale.ROOT); // 相对路径小写。
        if (fileName.endsWith("Controller.java") || relative.contains("/controller/")) { // 文件名或目录已明确是 Controller。
            return true; // 是 Controller 候选。
        }
        for (String line : lines == null ? List.<String>of() : lines) { // 扫描少量文本特征。
            if (line != null && (line.contains("@RestController") || line.contains("@Controller"))) { // 命中 Spring Controller 注解。
                return true; // 是 Controller 候选。
            }
        }
        return false; // 不像 Controller。
    }

    private boolean isSafeScanDirectory(Path directory) { // 判断 endpoint-only 扫描是否可以进入该目录。
        if (directory == null) { // 空目录不安全。
            return false; // 返回 false。
        }
        Path normalizedPath = directory.toAbsolutePath().normalize(); // 规范化目录路径。
        String name = normalizedPath.getFileName() == null ? "" : normalizedPath.getFileName().toString(); // 目录名。
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !SKIPPED_SCAN_DIRECTORIES.contains(name)
                && Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS); // workspace 内、非软链、非敏感目录、非构建/缓存目录。
    }

    private boolean isSafeJavaFile(Path filePath) { // 判断 endpoint-only 扫描中的 Java 文件是否安全可读。
        if (filePath == null || filePath.getFileName() == null) { // 空路径不安全。
            return false; // 返回 false。
        }
        Path normalizedPath = filePath.toAbsolutePath().normalize(); // 规范化文件路径。
        String fileName = filePath.getFileName().toString(); // 文件名。
        String extension = projectPathGuard.getExtension(fileName); // 文件扩展名。
        return "java".equals(extension)
                && projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !projectPathGuard.isBlockedFilename(fileName)
                && projectPathGuard.isAllowedExtension(extension)
                && Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 只允许 workspace 内安全 Java 源码文件。
    }

    private List<ControllerEndpointMatch> chooseSimilarEndpointCandidates(String endpointFilter,
                                                                          List<ControllerEndpointMatch> endpoints) { // 为未命中 endpoint 返回相似接口。
        List<ControllerEndpointMatch> sorted = new ArrayList<>(endpoints == null ? List.of() : endpoints); // 拷贝候选列表。
        sorted.sort(Comparator
                .comparingInt((ControllerEndpointMatch match) -> endpointSimilarityScore(endpointFilter, match.path)).reversed()
                .thenComparing(match -> match.path, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(match -> match.controllerPath, String.CASE_INSENSITIVE_ORDER)); // 相似度优先，再按路径稳定排序。
        List<ControllerEndpointMatch> limited = new ArrayList<>(); // 截断后的候选。
        for (ControllerEndpointMatch candidate : sorted) { // 逐个收集。
            if (limited.size() >= MAX_ENDPOINT_CANDIDATES) { // 达到 20 个候选上限。
                break; // 停止。
            }
            limited.add(candidate.withMatchType("CANDIDATE")); // 标记为候选接口。
        }
        return limited; // 返回候选接口。
    }

    private int endpointSimilarityScore(String requestedEndpoint, String candidateEndpoint) { // 计算 endpoint 候选相似度。
        String requested = normalizeEndpoint(requestedEndpoint); // 归一化请求路径。
        String candidate = normalizeEndpoint(candidateEndpoint); // 归一化候选路径。
        if (requested == null || candidate == null) { // 空值无相似度。
            return 0; // 返回 0。
        }
        if (candidate.equals(requested)) { // 完全一致。
            return 100; // 最高分。
        }
        if (endpointTemplateMatches(candidate, requested) || endpointTemplateMatches(requested, candidate)) { // 路径变量可匹配。
            return 95; // 次高分。
        }
        if (candidate.endsWith(requested) || requested.endsWith(candidate)) { // /chat/message 与 /api/chat/message。
            return 80; // 弱匹配高分。
        }
        String requestedTail = lastEndpointSegment(requested); // 请求路径尾段。
        String candidateTail = lastEndpointSegment(candidate); // 候选路径尾段。
        if (!requestedTail.isBlank() && requestedTail.equals(candidateTail)) { // 尾段一致。
            return 60; // 中等相似。
        }
        return sharedEndpointSegmentCount(requested, candidate); // 其它按公共 segment 数量排序。
    }

    private String endpointMatchType(String candidateEndpoint, String requestedEndpoint) { // 判断候选 endpoint 与用户 endpoint 的匹配类型。
        String candidate = normalizeEndpoint(candidateEndpoint); // 归一化候选接口。
        String requested = normalizeEndpoint(requestedEndpoint); // 归一化请求接口。
        if (candidate == null || requested == null) { // 空值不匹配。
            return null; // 返回空。
        }
        if (candidate.equals(requested)) { // 精确匹配。
            return "EXACT"; // 返回精确匹配。
        }
        if (endpointTemplateMatches(candidate, requested) || endpointTemplateMatches(requested, candidate)) { // 支持 /api/user/123 匹配 /api/user/{id}。
            return "PATH_VARIABLE"; // 返回路径变量匹配。
        }
        if (candidate.endsWith(requested) || requested.endsWith(candidate)) { // 支持唯一弱匹配，如 /chat/message 与 /api/chat/message。
            return "WEAK"; // 返回弱匹配。
        }
        return null; // 不匹配。
    }

    private boolean endpointTemplateMatches(String templateEndpoint, String actualEndpoint) { // 判断带 {id} 的模板是否匹配实际路径。
        String template = normalizeEndpoint(templateEndpoint); // 归一化模板路径。
        String actual = normalizeEndpoint(actualEndpoint); // 归一化实际路径。
        if (template == null || actual == null) { // 空值不匹配。
            return false; // 返回 false。
        }
        String[] templateSegments = endpointSegments(template); // 拆分模板路径。
        String[] actualSegments = endpointSegments(actual); // 拆分实际路径。
        if (templateSegments.length != actualSegments.length) { // segment 数量不同不能匹配。
            return false; // 返回 false。
        }
        for (int i = 0; i < templateSegments.length; i++) { // 逐段比较。
            String templateSegment = templateSegments[i]; // 模板段。
            String actualSegment = actualSegments[i]; // 实际段。
            if (templateSegment.startsWith("{") && templateSegment.endsWith("}") && templateSegment.length() > 2) { // {id} 变量段。
                continue; // 变量段匹配任意非空 segment。
            }
            if (!templateSegment.equals(actualSegment)) { // 固定段不同。
                return false; // 不匹配。
            }
        }
        return true; // 全部 segment 匹配。
    }

    private String normalizeEndpoint(String endpoint) { // 归一化接口路径。
        String value = endpoint == null ? "" : endpoint.trim(); // 去掉首尾空白。
        if (value.isEmpty()) { // 空字符串无 endpoint。
            return null; // 返回 null。
        }
        int queryIndex = value.indexOf('?'); // 查找 query string。
        if (queryIndex >= 0) { // 带查询参数。
            value = value.substring(0, queryIndex); // 去掉 query string。
        }
        int hashIndex = value.indexOf('#'); // 查找片段。
        if (hashIndex >= 0) { // 带 URL hash。
            value = value.substring(0, hashIndex); // 去掉 hash。
        }
        value = value.replace('\\', '/').replaceAll("/{2,}", "/"); // 统一斜杠并压缩多个 /。
        if (!value.startsWith("/")) { // 缺少开头斜杠。
            value = "/" + value; // 补齐斜杠。
        }
        while (value.length() > 1 && value.endsWith("/")) { // 根路径之外去掉尾部 /。
            value = value.substring(0, value.length() - 1); // 删除尾部斜杠。
        }
        return value.isBlank() ? null : value; // 返回规范 endpoint。
    }

    private boolean isEndpointOnlyPath(String requestedPath) { // 判断 path 参数是否其实是 endpoint。
        String normalized = normalizeEndpoint(requestedPath); // 归一化候选路径。
        return normalized != null
                && normalized.startsWith("/")
                && !normalized.contains(".")
                && normalized.matches("(/[A-Za-z0-9_\\-{}]+)+"); // /api/xxx 这类无扩展名 API 路径。
    }

    private String[] endpointSegments(String endpoint) { // 拆分 endpoint 为 segment。
        String normalized = normalizeEndpoint(endpoint); // 归一化路径。
        if (normalized == null || "/".equals(normalized)) { // 空或根路径。
            return new String[0]; // 返回空数组。
        }
        return normalized.substring(1).split("/"); // 去掉开头 / 后按 / 拆分。
    }

    private String lastEndpointSegment(String endpoint) { // 获取 endpoint 最后一个 segment。
        String[] segments = endpointSegments(endpoint); // 拆分路径。
        return segments.length == 0 ? "" : segments[segments.length - 1]; // 返回尾段。
    }

    private int sharedEndpointSegmentCount(String leftEndpoint, String rightEndpoint) { // 计算两个 endpoint 共享 segment 数量。
        Set<String> leftSegments = new LinkedHashSet<>(List.of(endpointSegments(leftEndpoint))); // 左侧 segment 集合。
        int count = 0; // 命中数量。
        for (String segment : endpointSegments(rightEndpoint)) { // 遍历右侧 segment。
            if (leftSegments.contains(segment)) { // 共享 segment。
                count++; // 计数加一。
            }
        }
        return count; // 返回共享数量。
    }

    // ===================== Controller 信息与端点 =====================

    private List<EndpointInfo> buildEndpoints(List<JavaMethod> methods,
                                              String basePath,
                                              String endpointFilter,
                                              String methodFilter,
                                              ObjectNode result,
                                              ChainState state) { // 提取接口端点并按 endpoint/methodName 过滤。
        ArrayNode endpointArray = result.withArray("endpoints"); // 复用 endpoints 数组。
        List<EndpointInfo> endpoints = new ArrayList<>(); // 端点信息列表。
        for (JavaMethod method : methods) { // 遍历方法寻找映射注解。
            String mappingAnnotation = findMappingAnnotation(method.getAnnotations()); // 方法上的映射注解原文。
            if (mappingAnnotation == null) { // 不是接口方法。
                continue; // 跳过。
            }
            String httpMethod = httpMethodFromMapping(mappingAnnotation); // HTTP 方法。
            String path = joinPaths(basePath, extractAnnotationPath(mappingAnnotation)); // 接口完整路径。
            if (methodFilter != null && !methodFilter.equals(method.getName())) { // 指定 methodName 时只保留该方法。
                continue; // 跳过非目标方法。
            }
            if (endpointFilter != null) { // 指定 endpoint 时只保留匹配端点。
                if (!endpointMatches(path, endpointFilter)) { // 路径不匹配。
                    continue; // 跳过。
                }
                state.endpointMatched = true; // 标记已精确匹配。
            }
            EndpointInfo endpoint = new EndpointInfo(httpMethod, path, method.getName(), method.getLineNumber(), mappingAnnotation, method); // 端点信息。
            endpoints.add(endpoint); // 收集端点。
            if (state.reachedLimit()) { // 达到上限。
                state.truncated = true; // 标记截断。
                continue; // 不再写入 JSON，但仍保留用于内部 serviceCall（已截断标记）。
            }
            ObjectNode node = objectMapper.createObjectNode(); // 端点 JSON。
            node.put("httpMethod", httpMethod); // HTTP 方法。
            node.put("path", path); // 接口路径。
            node.put("controllerMethod", method.getName()); // Controller 方法名。
            node.put("lineNumber", method.getLineNumber()); // 行号。
            node.put("mappingAnnotation", mappingAnnotation); // 映射注解原文。
            endpointArray.add(node); // 写入端点数组。
            state.count++; // 计数加一。
        }
        return endpoints; // 返回过滤后的端点。
    }

    private List<EndpointInfo> rebuildAllEndpoints(List<JavaMethod> methods,
                                                   String basePath,
                                                   String methodFilter,
                                                   ObjectNode result,
                                                   ChainState state) { // endpoint 未匹配时回退返回全部端点。
        ArrayNode endpointArray = result.withArray("endpoints"); // 端点数组。
        endpointArray.removeAll(); // 清空之前过滤的结果。
        List<EndpointInfo> endpoints = new ArrayList<>(); // 全部端点。
        for (JavaMethod method : methods) { // 遍历方法。
            String mappingAnnotation = findMappingAnnotation(method.getAnnotations()); // 映射注解。
            if (mappingAnnotation == null) { // 不是接口方法。
                continue; // 跳过。
            }
            if (methodFilter != null && !methodFilter.equals(method.getName())) { // methodName 过滤仍生效。
                continue; // 跳过。
            }
            String httpMethod = httpMethodFromMapping(mappingAnnotation); // HTTP 方法。
            String path = joinPaths(basePath, extractAnnotationPath(mappingAnnotation)); // 接口路径。
            endpoints.add(new EndpointInfo(httpMethod, path, method.getName(), method.getLineNumber(), mappingAnnotation, method)); // 收集端点。
            ObjectNode node = objectMapper.createObjectNode(); // 端点 JSON。
            node.put("httpMethod", httpMethod); // HTTP 方法。
            node.put("path", path); // 接口路径。
            node.put("controllerMethod", method.getName()); // 方法名。
            node.put("lineNumber", method.getLineNumber()); // 行号。
            node.put("mappingAnnotation", mappingAnnotation); // 映射注解。
            endpointArray.add(node); // 写入数组。
        }
        return endpoints; // 返回全部端点。
    }

    private void fillDependencies(List<JavaField> dependencies, ObjectNode result, ChainState state) { // 写入依赖对象及候选文件。
        ArrayNode dependencyArray = result.withArray("dependencies"); // 复用 dependencies 数组。
        for (JavaField dependency : dependencies) { // 遍历依赖。
            if (state.reachedLimit()) { // 达到上限。
                state.truncated = true; // 标记截断。
                return; // 停止写入。
            }
            ObjectNode node = objectMapper.createObjectNode(); // 依赖 JSON。
            node.put("fieldName", dependency.getFieldName()); // 字段名。
            node.put("type", dependency.getType()); // 类型。
            node.put("kind", resolveKind(dependency.getType())); // 依赖种类（SERVICE/MAPPER/...）。
            node.put("injectionType", dependency.getInjectionType()); // 注入方式。
            node.put("lineNumber", dependency.getLineNumber()); // 行号。
            node.set("candidateFiles", buildCandidateFiles(dependency.getType())); // 候选文件。
            dependencyArray.add(node); // 写入依赖数组。
            state.count++; // 计数加一。
        }
    }

    private List<ServiceCallInfo> buildServiceCalls(List<EndpointInfo> endpoints,
                                                    List<String> codeLines,
                                                    Map<String, JavaField> dependencyByName,
                                                    boolean includeSnippet,
                                                    ObjectNode result,
                                                    ChainState state) { // 提取 Controller 方法体内对依赖对象的调用。
        ArrayNode serviceCallArray = result.withArray("serviceCalls"); // 复用 serviceCalls 数组。
        List<ServiceCallInfo> serviceCalls = new ArrayList<>(); // Service 调用列表。
        for (EndpointInfo endpoint : endpoints) { // 遍历接口端点。
            List<JavaCall> calls = support.extractCalls(codeLines, endpoint.method); // 提取该方法体内的调用候选。
            for (JavaCall call : calls) { // 遍历调用候选。
                String receiver = call.getReceiver(); // 调用对象。
                if (receiver == null) { // 裸调用不是对依赖对象的调用。
                    continue; // 跳过。
                }
                JavaField dependency = dependencyByName.get(receiver); // 命中依赖对象。
                if (dependency == null) { // 不是注入依赖（局部变量/工具类）。
                    continue; // 跳过。
                }
                ServiceCallInfo serviceCall = new ServiceCallInfo(endpoint, receiver, dependency.getType(), call.getCallee(),
                        call.getLineNumber(), call.getCallExpression()); // Service 调用信息。
                serviceCalls.add(serviceCall); // 收集 Service 调用。
                if (state.reachedLimit()) { // 达到上限。
                    state.truncated = true; // 标记截断。
                    continue; // 不再写入 JSON。
                }
                ObjectNode node = objectMapper.createObjectNode(); // Service 调用 JSON。
                node.put("endpointPath", endpoint.path); // 接口路径。
                node.put("httpMethod", endpoint.httpMethod); // HTTP 方法。
                node.put("controllerMethod", endpoint.controllerMethod); // Controller 方法。
                node.put("serviceObject", receiver); // Service 对象。
                node.put("serviceType", dependency.getType()); // Service 类型。
                node.put("serviceMethod", call.getCallee()); // Service 方法。
                node.put("lineNumber", call.getLineNumber()); // 行号。
                if (includeSnippet) { // 需要片段时输出调用表达式。
                    node.put("callExpression", call.getCallExpression()); // 调用表达式。
                }
                node.set("candidateServiceFiles", buildCandidateFiles(dependency.getType())); // 候选 Service/ServiceImpl 文件。
                serviceCallArray.add(node); // 写入 serviceCalls 数组。
                state.count++; // 计数加一。
            }
        }
        return serviceCalls; // 返回 Service 调用列表。
    }

    // ===================== maxDepth=2：ServiceImpl 一层调用 =====================

    private void analyzeServiceImplCalls(List<ServiceCallInfo> serviceCalls, ObjectNode result, ChainState state) { // maxDepth=2 时分析 ServiceImpl 一层调用。
        ArrayNode serviceMethodCallArray = result.withArray("serviceMethodCalls"); // serviceMethodCalls 数组。
        Map<String, ServiceImplModel> implCache = new LinkedHashMap<>(); // ServiceImpl 解析缓存，每个文件最多读取一次。
        java.util.Set<String> analyzedKeys = new java.util.LinkedHashSet<>(); // 已分析的 serviceType#serviceMethod 去重。
        int analyzed = 0; // 已分析的 serviceCall 数量。
        for (ServiceCallInfo serviceCall : serviceCalls) { // 遍历 Service 调用。
            if (analyzed >= MAX_DEPTH2_SERVICE_CALLS) { // 最多分析前 10 个 serviceCall。
                break; // 停止。
            }
            String dedupKey = serviceCall.serviceType + "#" + serviceCall.serviceMethod; // 去重键。
            if (!analyzedKeys.add(dedupKey)) { // 同一 Service 方法只分析一次。
                continue; // 跳过重复。
            }
            analyzed++; // 计数加一。
            ServiceImplModel implModel = resolveServiceImplModel(serviceCall.serviceType, implCache); // 解析 ServiceImpl（带缓存）。
            if (implModel == null) { // 未找到可读 ServiceImpl。
                continue; // 跳过该 Service 方法。
            }
            JavaMethod implMethod = findMethodByName(implModel.methods, serviceCall.serviceMethod); // 在 ServiceImpl 中找对应方法。
            if (implMethod == null || !implMethod.hasBody()) { // 没有匹配方法或无方法体。
                continue; // 跳过。
            }
            ObjectNode node = objectMapper.createObjectNode(); // serviceMethodCalls JSON。
            node.put("serviceType", serviceCall.serviceType); // Service 类型。
            node.put("serviceImplPath", implModel.relativePath); // ServiceImpl 相对路径。
            node.put("serviceMethod", serviceCall.serviceMethod); // Service 方法。
            ArrayNode internalCalls = objectMapper.createArrayNode(); // ServiceImpl 内部一层调用。
            ArrayNode externalCalls = objectMapper.createArrayNode(); // ServiceImpl 外部对象一层调用。
            for (JavaCall call : support.extractCalls(implModel.codeLines, implMethod)) { // 提取 ServiceImpl 方法体一层调用。
                if (call.getReceiver() == null || "this".equals(call.getReceiver())) { // 裸调用或 this 调用归为内部调用。
                    ObjectNode internal = objectMapper.createObjectNode(); // 内部调用 JSON。
                    internal.put("toMethod", call.getCallee()); // 目标方法。
                    internal.put("lineNumber", call.getLineNumber()); // 行号。
                    internalCalls.add(internal); // 写入内部调用。
                } else { // 对象方法调用归为外部调用。
                    ObjectNode external = objectMapper.createObjectNode(); // 外部调用 JSON。
                    external.put("objectName", call.getReceiver()); // 调用对象。
                    external.put("methodName", call.getCallee()); // 被调用方法。
                    external.put("lineNumber", call.getLineNumber()); // 行号。
                    externalCalls.add(external); // 写入外部调用。
                }
            }
            node.set("internalCalls", internalCalls); // 挂载内部调用。
            node.set("externalCalls", externalCalls); // 挂载外部调用。
            serviceMethodCallArray.add(node); // 写入 serviceMethodCalls。
        }
    }

    private ServiceImplModel resolveServiceImplModel(String serviceType, Map<String, ServiceImplModel> cache) { // 解析并缓存 ServiceImpl 内容。
        if (cache.containsKey(serviceType)) { // 命中缓存。
            return cache.get(serviceType); // 直接返回（可能为 null）。
        }
        ServiceImplModel model = null; // 默认未找到。
        try {
            Path implPath = pickServiceImplPath(serviceType); // 选取优先的 ServiceImpl 候选路径。
            if (implPath != null) { // 找到候选实现类。
                projectPathGuard.validateReadableCodeFile(implPath); // 校验文件安全性。
                List<String> implLines = Files.readAllLines(implPath, StandardCharsets.UTF_8); // 读取 ServiceImpl。
                List<String> implCode = support.stripComments(implLines); // 去注释。
                List<JavaMethod> implMethods = support.scanJavaMethods(implCode); // 扫描方法体。
                model = new ServiceImplModel(support.toWorkspaceRelativePath(implPath, ""), implCode, implMethods); // 构造模型。
            }
        } catch (Exception e) {
            log.debug("[AnalyzeControllerServiceChainTool] skip unreadable ServiceImpl for type: {}", serviceType); // 不打印绝对路径。
        }
        cache.put(serviceType, model); // 写入缓存（含 null），保证每个类型只尝试一次。
        return model; // 返回模型。
    }

    private Path pickServiceImplPath(String serviceType) { // 选取优先的 ServiceImpl 候选路径。
        List<Path> candidates = support.findTypeCandidatePaths(serviceType, MAX_CANDIDATE_FILES); // 候选路径，Impl 在后但单独识别。
        for (Path candidate : candidates) { // 优先选取 *Impl.java。
            String name = candidate.getFileName() == null ? "" : candidate.getFileName().toString().toLowerCase(Locale.ROOT); // 文件名小写。
            if (name.endsWith("impl.java")) { // 命中实现类。
                return candidate; // 返回实现类路径。
            }
        }
        return null; // 没有实现类候选时返回空。
    }

    private JavaMethod findMethodByName(List<JavaMethod> methods, String methodName) { // 在方法列表中按名查找方法。
        if (methods == null || methodName == null) { // 空值兜底。
            return null; // 返回空。
        }
        for (JavaMethod method : methods) { // 遍历方法。
            if (methodName.equals(method.getName())) { // 命中方法名。
                return method; // 返回方法。
            }
        }
        return null; // 未找到。
    }

    // ===================== 端点注解解析（Controller 专属） =====================

    private String findMappingAnnotation(List<String> annotations) { // 在方法注解中找映射注解原文。
        if (annotations == null) { // 空注解。
            return null; // 返回空。
        }
        for (String annotation : annotations) { // 遍历注解。
            for (String mapping : MAPPING_ANNOTATIONS) { // 匹配映射注解。
                if (annotation != null && annotation.contains(mapping)) { // 命中。
                    return annotation.trim(); // 返回注解原文。
                }
            }
        }
        return null; // 不是接口方法。
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
        Matcher matcher = Pattern.compile("RequestMethod\\.([A-Z]+)").matcher(annotation); // @RequestMapping(method = RequestMethod.X)。
        return matcher.find() ? matcher.group(1) : "REQUEST"; // 取 RequestMethod 或兜底 REQUEST。
    }

    private String extractAnnotationPath(String annotation) { // 从映射注解中提取路径。
        if (annotation == null || annotation.isBlank()) { // 空注解。
            return ""; // 返回空。
        }
        Matcher named = Pattern.compile("(?:path|value)\\s*=\\s*\\{?\\s*\"([^\"]*)\"").matcher(annotation); // 优先取 path=/value= 的值。
        if (named.find()) { // 命中具名属性。
            return named.group(1); // 返回路径。
        }
        Matcher direct = Pattern.compile("@[A-Za-z]+Mapping\\s*\\(\\s*\"([^\"]*)\"").matcher(annotation); // 支持 @GetMapping("/chat") 这种直接路径。
        return direct.find() ? direct.group(1) : ""; // 无 path/value 时返回空，避免把 produces 等字符串误当路径。
    }

    private String joinPaths(String basePath, String methodPath) { // 拼接类级路径和方法级路径。
        String base = normalizeEndpointPath(basePath); // 归一化 basePath。
        String method = normalizeEndpointPath(methodPath); // 归一化方法路径。
        if (base.isEmpty()) { // 没有类级路径。
            return method.isEmpty() ? "/" : method; // 方法路径或根路径。
        }
        if (method.isEmpty() || "/".equals(method)) { // 没有方法路径。
            return base; // 用类级路径。
        }
        return (base + "/" + method.substring(1)).replaceAll("/{2,}", "/"); // 拼接并压缩多余斜杠。
    }

    private String normalizeEndpointPath(String path) { // 归一化接口路径，确保以 / 开头。
        String value = normalizeEndpoint(path); // 复用 endpoint 归一化规则。
        return value == null ? "" : value; // 空路径返回空字符串，便于 joinPaths 处理。
    }

    private boolean endpointMatches(String path, String endpointFilter) { // 判断接口路径是否匹配用户指定 endpoint。
        String matchType = endpointMatchType(path, endpointFilter); // 统一使用 endpoint 匹配规则。
        return "EXACT".equals(matchType) || "PATH_VARIABLE".equals(matchType); // path 已明确时只接受强匹配，不用 contains 误伤。
    }

    private String resolveKind(String type) { // 根据类型后缀识别依赖种类。
        if (type == null) { // 空类型。
            return "DEPENDENCY"; // 兜底。
        }
        if (type.endsWith("Service")) { // Service。
            return "SERVICE";
        }
        if (type.endsWith("Mapper")) { // Mapper。
            return "MAPPER";
        }
        if (type.endsWith("Repository")) { // Repository。
            return "REPOSITORY";
        }
        if (type.endsWith("Client")) { // Client。
            return "CLIENT";
        }
        return "DEPENDENCY"; // 其它依赖。
    }

    private ArrayNode buildCandidateFiles(String type) { // 构造依赖类型的候选文件相对路径数组。
        ArrayNode targets = objectMapper.createArrayNode(); // 候选文件数组。
        for (Path candidate : support.findTypeCandidatePaths(type, MAX_CANDIDATE_FILES)) { // 查找候选文件。
            targets.add(support.toWorkspaceRelativePath(candidate, "")); // 写入相对路径。
        }
        return targets; // 返回候选文件数组。
    }

    // ===================== Controller 头部解析 =====================

    private String extractPackageName(List<String> codeLines) { // 提取 packageName。
        for (String line : codeLines) { // 逐行扫描。
            Matcher matcher = JAVA_PACKAGE_PATTERN.matcher(line); // 匹配 package。
            if (matcher.find()) { // 命中。
                return matcher.group(1); // 返回包名。
            }
        }
        return ""; // 未找到。
    }

    private int findClassLineIndex(List<String> codeLines) { // 定位第一个类/接口声明行索引。
        for (int i = 0; i < codeLines.size(); i++) { // 逐行扫描。
            Matcher matcher = JAVA_CLASS_PATTERN.matcher(codeLines.get(i)); // 匹配类声明。
            if (matcher.find()) { // 命中。
                return i; // 返回索引。
            }
        }
        return -1; // 未找到。
    }

    private String extractClassName(String line) { // 提取类名。
        Matcher matcher = JAVA_CLASS_PATTERN.matcher(line); // 匹配类声明。
        return matcher.find() ? matcher.group(2) : ""; // 返回类名。
    }

    private List<String> collectClassAnnotations(List<String> codeLines, int classLineIndex) { // 收集类声明上方的类级注解。
        List<String> annotations = new ArrayList<>(); // 类级注解列表。
        if (classLineIndex < 0) { // 没有类声明。
            return annotations; // 返回空列表。
        }
        for (int i = classLineIndex - 1; i >= 0; i--) { // 从类声明向上扫描。
            String trimmed = codeLines.get(i).trim(); // 去空白后的行。
            if (trimmed.isEmpty()) { // 空行跳过。
                continue; // 继续向上。
            }
            if (trimmed.startsWith("@")) { // 类级注解。
                annotations.add(0, trimmed); // 头插保持原顺序。
                continue; // 继续向上。
            }
            break; // 遇到非注解非空行停止。
        }
        return annotations; // 返回类级注解。
    }

    private String extractBasePath(List<String> classAnnotations) { // 从类级注解中提取 basePath。
        for (String annotation : classAnnotations) { // 遍历类级注解。
            if (annotation != null && annotation.contains("@RequestMapping")) { // 命中类级 @RequestMapping。
                return extractAnnotationPath(annotation); // 返回类级路径。
            }
        }
        return ""; // 没有类级路径。
    }

    private boolean isSpringController(List<String> classAnnotations, String className, String relativePath) { // 判断是否为 Spring Controller。
        for (String annotation : classAnnotations) { // 检查类级注解。
            if (annotation != null && (annotation.contains("@RestController") || annotation.contains("@Controller"))) { // 命中 Controller 注解。
                return true; // 是 Controller。
            }
        }
        if (className != null && className.endsWith("Controller")) { // 类名以 Controller 结尾。
            return true; // 是 Controller。
        }
        return relativePath != null && relativePath.toLowerCase(Locale.ROOT).contains("controller"); // 路径包含 controller。
    }

    // ===================== 结果构造与安全辅助 =====================

    private ObjectNode buildBaseResult(String relativePath,
                                       String fileName,
                                       String language,
                                       String packageName,
                                       String className,
                                       String basePath,
                                       List<String> classAnnotations,
                                       int maxDepth,
                                       long fileSize,
                                       String extension) { // 构造成功结果骨架。
        ObjectNode result = objectMapper.createObjectNode(); // 结果对象。
        result.put("type", RESULT_TYPE); // 结果类型。
        result.put("success", true); // 标记成功。
        result.put("path", relativePath); // 相对路径。
        result.put("fileName", fileName); // 文件名。
        result.put("extension", extension); // 扩展名，供 projectFileFocus 复用。
        result.put("language", language); // 语言展示名。
        result.put("packageName", packageName); // 包名。
        result.put("maxDepth", maxDepth); // 分析深度。
        result.put("fileSize", fileSize); // 文件大小。
        ObjectNode controller = objectMapper.createObjectNode(); // controller 概览。
        controller.put("className", className); // 类名。
        controller.put("basePath", basePath); // 类级路径。
        ArrayNode annotationArray = objectMapper.createArrayNode(); // 类级注解数组。
        for (String annotation : classAnnotations) { // 写入类级注解。
            annotationArray.add(annotation); // 加入数组。
        }
        controller.set("annotations", annotationArray); // 挂载注解。
        result.set("controller", controller); // 挂载 controller 概览。
        result.set("endpoints", objectMapper.createArrayNode()); // endpoints 数组。
        result.set("dependencies", objectMapper.createArrayNode()); // dependencies 数组。
        result.set("serviceCalls", objectMapper.createArrayNode()); // serviceCalls 数组。
        result.set("serviceMethodCalls", objectMapper.createArrayNode()); // serviceMethodCalls 数组。
        ArrayNode warnings = objectMapper.createArrayNode(); // warnings 数组。
        warnings.add(ANALYSIS_WARNING); // 统一不确定性说明。
        result.set("warnings", warnings); // 挂载 warnings。
        result.put("truncated", false); // 默认未截断。
        return result; // 返回结果骨架。
    }

    private void appendWarning(ObjectNode result, String warning) { // 追加一条 warning。
        result.withArray("warnings").add(warning); // 写入 warnings 数组。
    }

    private int resolveMaxDepth(JsonNode arguments) { // 解析并限制 maxDepth。
        int maxDepth = getOptionalInt(arguments, "maxDepth", DEFAULT_MAX_DEPTH); // 默认 1。
        if (maxDepth < DEFAULT_MAX_DEPTH) { // 小于 1 改为 1。
            return DEFAULT_MAX_DEPTH; // 返回 1。
        }
        return Math.min(maxDepth, MAX_ALLOWED_DEPTH); // 最大 2。
    }

    private int resolveMaxItems(JsonNode arguments) { // 解析并限制 maxItems。
        int maxItems = getOptionalInt(arguments, "maxItems", DEFAULT_MAX_ITEMS); // 默认 100。
        if (maxItems <= 0) { // 非法值使用默认。
            return DEFAULT_MAX_ITEMS; // 返回 100。
        }
        return Math.min(maxItems, MAX_ALLOWED_ITEMS); // 最大 300。
    }

    private boolean resolveIncludeSnippet(JsonNode arguments) { // 解析 includeSnippet，默认 true。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // 参数为空。
            return true; // 默认包含片段。
        }
        JsonNode valueNode = arguments.path("includeSnippet"); // 读取字段。
        return valueNode.isMissingNode() || valueNode.isNull() || valueNode.asBoolean(true); // 默认 true。
    }

    private String safeRelativePath(String value) { // 标准化日志中的路径。
        return value == null ? "" : value.replace('\\', '/'); // 不做绝对路径展开。
    }

    private String normalizeFailureMessage(String message) { // 规范化失败文案。
        if (message == null || message.isBlank()) { // 缺失时兜底。
            return "Controller 调用链分析失败。"; // 友好兜底。
        }
        if (message.contains("workspace 外")) { // 路径穿越或绝对路径。
            return "不允许访问 workspace 外的路径。"; // 不暴露真实路径。
        }
        return message; // 其它受控错误保持原文案。
    }

    private String buildFailureResult(String path, String endpoint, String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 结果对象。
        result.put("type", RESULT_TYPE); // 结果类型。
        result.put("success", false); // 标记失败。
        result.put("path", safeRelativePath(path)); // 相对路径形态。
        if (endpoint != null && !endpoint.isBlank()) { // endpoint 失败也要写入结果，便于 tool_call_log 排查。
            result.put("endpoint", endpoint); // 写入接口路径。
        }
        result.put("message", message == null || message.isBlank() ? "Controller 调用链分析失败，请稍后重试。" : message); // 友好错误。
        return result.toString(); // 返回 JSON 字符串。
    }

    private String buildEndpointFailureResult(String endpoint,
                                              String message,
                                              List<ControllerEndpointMatch> candidates) { // 构造 endpoint-only 或 endpoint 过滤失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 结果对象。
        result.put("type", RESULT_TYPE); // 结果类型。
        result.put("success", false); // 标记失败。
        result.put("path", ""); // endpoint-only 失败时没有源码文件 path。
        result.put("endpoint", endpoint == null ? "" : endpoint); // 写入用户请求的 endpoint。
        result.put("matchedBy", "ENDPOINT_GLOBAL_SCAN"); // 标记失败来自全项目 endpoint 扫描，而非只看单个 Controller。
        result.put("message", message == null || message.isBlank() ? "未在项目 Controller 中找到该接口路径。" : message); // 失败说明。
        ArrayNode candidateArray = objectMapper.createArrayNode(); // 候选接口数组。
        int count = 0; // 候选数量。
        for (ControllerEndpointMatch candidate : candidates == null ? List.<ControllerEndpointMatch>of() : candidates) { // 遍历候选接口。
            if (count >= MAX_ENDPOINT_CANDIDATES) { // 最多返回 20 个。
                break; // 停止。
            }
            ObjectNode node = objectMapper.createObjectNode(); // 单个候选接口。
            node.put("httpMethod", candidate.httpMethod); // HTTP 方法。
            node.put("path", candidate.path); // 接口路径。
            node.put("controllerPath", candidate.controllerPath); // Controller 文件相对路径。
            node.put("controllerMethod", candidate.controllerMethod); // Controller 方法名。
            node.put("lineNumber", candidate.lineNumber); // Mapping 行号。
            node.put("mappingAnnotation", candidate.mappingAnnotation); // Mapping 注解原文。
            node.put("matchType", candidate.matchType); // 候选匹配类型。
            candidateArray.add(node); // 加入候选数组。
            count++; // 数量加一。
        }
        result.set("candidateEndpoints", candidateArray); // 写入候选接口。
        return result.toString(); // 返回 JSON 字符串。
    }

    // ===================== 内部数据模型 =====================

    private static final class ControllerEndpointMatch { // workspace 扫描命中的 Controller endpoint 候选。
        private final Path filePath; // Controller 源码文件绝对路径，内部使用，不返回给用户。
        private final String controllerPath; // Controller 源码文件 workspace 相对路径。
        private final String httpMethod; // HTTP 方法。
        private final String path; // 完整接口路径。
        private final String controllerMethod; // Controller 方法名。
        private final int lineNumber; // Mapping 所在行号。
        private final String mappingAnnotation; // Mapping 注解原文。
        private final String matchType; // EXACT / PATH_VARIABLE / WEAK / CANDIDATE。

        private ControllerEndpointMatch(Path filePath,
                                        String controllerPath,
                                        String httpMethod,
                                        String path,
                                        String controllerMethod,
                                        int lineNumber,
                                        String mappingAnnotation,
                                        String matchType) { // 构造 Controller endpoint 候选。
            this.filePath = filePath; // 保存内部文件路径。
            this.controllerPath = controllerPath; // 保存相对路径。
            this.httpMethod = httpMethod; // 保存 HTTP 方法。
            this.path = path; // 保存接口路径。
            this.controllerMethod = controllerMethod; // 保存 Controller 方法名。
            this.lineNumber = lineNumber; // 保存行号。
            this.mappingAnnotation = mappingAnnotation; // 保存注解原文。
            this.matchType = matchType; // 保存匹配类型。
        }

        private ControllerEndpointMatch withMatchType(String newMatchType) { // 复制候选并替换匹配类型。
            return new ControllerEndpointMatch(filePath, controllerPath, httpMethod, path,
                    controllerMethod, lineNumber, mappingAnnotation, newMatchType); // 返回新候选对象。
        }
    }

    private static final class EndpointSearchResult { // endpoint-only 扫描结果。
        private final List<ControllerEndpointMatch> matches; // 可自动采用或需要用户指定的匹配项。
        private final List<ControllerEndpointMatch> candidates; // 未命中时展示的相似候选。

        private EndpointSearchResult(List<ControllerEndpointMatch> matches,
                                     List<ControllerEndpointMatch> candidates) { // 构造 endpoint 搜索结果。
            this.matches = matches == null ? List.of() : matches; // 保存匹配项。
            this.candidates = candidates == null ? List.of() : candidates; // 保存候选项。
        }

        private boolean singleMatch() { // 是否只有一个可采用匹配。
            return matches.size() == 1; // 单个匹配可继续分析。
        }

        private boolean multipleMatches() { // 是否存在多个匹配。
            return matches.size() > 1; // 多个匹配需要用户指定。
        }

        private ControllerEndpointMatch firstMatch() { // 返回第一个匹配项。
            return matches.isEmpty() ? null : matches.get(0); // 空列表返回 null。
        }

        private List<ControllerEndpointMatch> matches() { // 暴露匹配列表。
            return matches; // 返回匹配项。
        }

        private List<ControllerEndpointMatch> candidates() { // 暴露候选列表。
            return candidates; // 返回候选项。
        }
    }

    private static final class EndpointInfo { // 接口端点信息。
        private final String httpMethod; // HTTP 方法。
        private final String path; // 接口路径。
        private final String controllerMethod; // Controller 方法名。
        private final int lineNumber; // 行号。
        private final String mappingAnnotation; // 映射注解原文。
        private final JavaMethod method; // 对应方法扫描结果。

        private EndpointInfo(String httpMethod, String path, String controllerMethod, int lineNumber,
                             String mappingAnnotation, JavaMethod method) { // 构造端点信息。
            this.httpMethod = httpMethod; // HTTP 方法。
            this.path = path; // 接口路径。
            this.controllerMethod = controllerMethod; // 方法名。
            this.lineNumber = lineNumber; // 行号。
            this.mappingAnnotation = mappingAnnotation; // 映射注解。
            this.method = method; // 方法扫描结果。
        }
    }

    private static final class ServiceCallInfo { // Controller 方法中的 Service 调用信息。
        private final EndpointInfo endpoint; // 所属端点。
        private final String serviceObject; // Service 对象名。
        private final String serviceType; // Service 类型。
        private final String serviceMethod; // Service 方法名。
        private final int lineNumber; // 行号。
        private final String callExpression; // 调用表达式。

        private ServiceCallInfo(EndpointInfo endpoint, String serviceObject, String serviceType, String serviceMethod,
                                int lineNumber, String callExpression) { // 构造 Service 调用信息。
            this.endpoint = endpoint; // 所属端点。
            this.serviceObject = serviceObject; // Service 对象。
            this.serviceType = serviceType; // Service 类型。
            this.serviceMethod = serviceMethod; // Service 方法。
            this.lineNumber = lineNumber; // 行号。
            this.callExpression = callExpression; // 调用表达式。
        }
    }

    private static final class ServiceImplModel { // ServiceImpl 解析结果缓存模型。
        private final String relativePath; // ServiceImpl 相对路径。
        private final List<String> codeLines; // 去注释代码行。
        private final List<JavaMethod> methods; // 方法扫描结果。

        private ServiceImplModel(String relativePath, List<String> codeLines, List<JavaMethod> methods) { // 构造模型。
            this.relativePath = relativePath; // 相对路径。
            this.codeLines = codeLines; // 代码行。
            this.methods = methods; // 方法列表。
        }
    }

    private static final class ChainState { // 接口/调用项计数与截断状态。
        private final int maxItems; // 上限。
        private int count; // 已写入项数量。
        private boolean truncated; // 是否截断。
        private boolean endpointMatched; // 指定 endpoint 是否精确匹配。

        private ChainState(int maxItems) { // 构造状态。
            this.maxItems = maxItems; // 保存上限。
        }

        private boolean reachedLimit() { // 是否达到上限。
            return count >= maxItems; // 达到上限返回 true。
        }
    }
}
