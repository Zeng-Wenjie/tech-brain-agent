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
 * analyzeToolServiceChain AI Tool 到业务组件的轻量调用链分析工具（P5.4）。
 *
 * <p>适用场景：当用户要求分析 SearchCodeTool、readProjectFile、analyzeCode、readFile 等 AI Tool
 * 背后调用了哪些 Service、Guard、Registry、Mapper、Repository 或工具组件时，Tool Calling 调用本工具做
 * 文本级静态分析。它会先定位真实 Tool 源码文件，再识别 toolName、execute 入口、注入依赖、内部方法调用和
 * 外部组件调用，并在 maxDepth=2 时尝试展开 ServiceImpl 的一层内部调用。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 的模型 tool_call 或强制路由
 * -> ToolRegistry 根据 analyzeToolServiceChain 找到本工具
 * -> execute(arguments) 解析 path/toolName/className/methodName/maxDepth
 * -> ProjectPathGuard 校验 workspace 边界和文件安全
 * -> ProjectJavaAnalysisSupport 复用 Java 方法、依赖和调用提取
 * -> 返回 tool_service_chain_analysis JSON 给 finalAnswer 和 tool_call_log。</p>
 *
 * <p>边界说明：本工具只做 workspace 内 Java AI Tool 的轻量静态文本分析，不修改文件，不访问 workspace 外路径，
 * 不接入 RAG/Milvus/向量化，不引入 Tree-sitter 或复杂 AST，不构建完整精准调用图，不输出完整源码，也不生成风险分析、
 * 测试步骤或 patch。</p>
 */
@Slf4j // 仅输出工具名、相对路径和失败原因，不打印完整源码或服务器绝对路径。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动暴露 analyzeToolServiceChain。
public class AnalyzeToolServiceChainTool extends AbstractAiTool { // P5.4 Tool→业务组件专项分析工具，继承公共 AI Tool 基类复用 schema 和 JSON 辅助能力。
    private static final String TOOL_NAME = "analyzeToolServiceChain"; // 工具名称必须和模型 tool_call.function.name 一致。
    private static final String RESULT_TYPE = "tool_service_chain_analysis"; // 工具返回 JSON 的 type 字段。
    private static final String DEFAULT_ENTRY_METHOD = "execute"; // 默认入口方法为 AI Tool 的 execute。
    private static final String ANALYSIS_WARNING = "当前为轻量静态分析，调用目标为候选结果，不保证完全精准。"; // 统一不确定性说明。
    private static final int DEFAULT_MAX_DEPTH = 1; // 默认只分析 Tool 内部和直接依赖调用。
    private static final int MAX_ALLOWED_DEPTH = 2; // 第一版最多展开 ServiceImpl 一层。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回调用项数量。
    private static final int MAX_ALLOWED_ITEMS = 300; // 防止大文件返回过多调用项。
    private static final int MAX_CANDIDATE_FILES = 5; // 单个组件类型最多返回候选文件数量。
    private static final int MAX_TOOL_MATCHES = 20; // toolName 多匹配时最多返回候选 Tool。
    private static final int MAX_TOOL_SCAN_FILES = 20000; // 全 workspace 扫描 Java 文件的兜底上限。
    private static final int MAX_DEPTH2_SERVICE_CALLS = 10; // maxDepth=2 时最多展开前 10 个 Service 调用。
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("\\bTOOL_NAME\\s*=\\s*\"([^\"]+)\""); // 提取常量形式的 toolName。
    private static final Pattern NAME_RETURN_PATTERN = Pattern.compile("\\b(?:name|getName)\\s*\\(\\s*\\)\\s*\\{[^{}]*?return\\s+\"([^\"]+)\"", Pattern.DOTALL); // 提取 name()/getName() 直接返回字面量的 toolName。
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;"); // 提取 Java package。
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_$][\\w$]*)"); // 提取 Java class 名。
    private static final Set<String> SKIPPED_SCAN_DIRECTORIES = Set.of( // 全 workspace 定位 Tool 时跳过这些目录。
            ".git", ".idea", ".codex-tmp", "data", "uploads", "node_modules", "target", "build", "dist", "logs", "tmp");
    private static final Set<String> UTILITY_TYPES = Set.of( // 这些依赖类型归为工具/基础设施调用。
            "ObjectMapper", "JsonNode", "ObjectNode", "ArrayNode", "Files", "Path", "Paths", "Pattern", "Matcher",
            "StringBuilder", "StringBuffer", "Logger");
    private static final Set<String> UTILITY_RECEIVERS = Set.of( // 静态或常见工具对象接收者。
            "log", "logger", "System", "Objects", "Collections", "CollectionUtils", "Optional", "Arrays", "Math",
            "Pattern", "Matcher", "Files", "Paths", "Path", "Comparator", "Stream", "Locale", "StandardCharsets",
            "String", "Integer", "Long", "Boolean", "Double", "Character", "StringUtils", "JSON", "JSONObject", "JSONArray");

    private final ProjectPathGuard projectPathGuard; // workspace 路径安全守卫，负责边界、敏感路径和文件类型校验。
    private final ProjectJavaAnalysisSupport support; // P5 公共 Java 轻量解析组件，避免重复实现方法扫描和调用提取。

    public AnalyzeToolServiceChainTool(ProjectPathGuard projectPathGuard,
                                       ProjectJavaAnalysisSupport support) { // 构造器注入安全守卫和公共解析器。
        this.projectPathGuard = projectPathGuard; // 保存 workspace 安全守卫。
        this.support = support; // 保存 Java 解析复用组件。
    }

    @Override // 实现 AiTool 工具注册名。
    public String name() {
        return TOOL_NAME; // 固定返回 analyzeToolServiceChain。
    }

    @Override // 实现 AiTool 描述，供模型判断调用边界。
    public String description() {
        return "分析项目 workspace 内 AI Tool 实现类到业务 Service、Guard、Registry、Mapper、Repository 等组件的轻量调用链，提取 toolName、execute 方法、内部调用、外部依赖和候选目标文件。只做静态文本级分析，不修改文件，不访问 workspace 外路径，不做完整 AST，不保证百分百精准。"; // 明确 Tool→Service 专项能力和静态分析边界。
    }

    @Override // 实现工具参数 JSON Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建 object schema，path/toolName/className 都不放 required，业务层校验三选一。
        addProperty(schema, "path", createStringProperty("可选，相对于项目 workspace 的 AI Tool 源码文件路径，例如 Tech-Brain-Agent/src/main/java/com/agent/tool/project/SearchCodeTool.java"), false); // path 可选。
        addProperty(schema, "toolName", createStringProperty("可选，AI Tool 名称，例如 searchCode、readProjectFile、analyzeCode"), false); // toolName 可选。
        addProperty(schema, "className", createStringProperty("可选，AI Tool 类名，例如 SearchCodeTool、ReadProjectFileTool"), false); // className 可选。
        addProperty(schema, "methodName", createStringProperty("可选，入口方法名，默认 execute"), false); // methodName 可选。
        addProperty(schema, "maxDepth", createIntegerProperty("分析深度，可选，默认 1，最大 2。1 表示 Tool 内部和直接依赖，2 表示尝试分析 ServiceImpl 内部一层调用"), false); // maxDepth 可选。
        ObjectNode includeSnippetProperty = objectMapper.createObjectNode(); // boolean 参数需要手动构造 schema。
        includeSnippetProperty.put("type", "boolean"); // 标记 includeSnippet 类型。
        includeSnippetProperty.put("description", "是否包含少量声明代码片段，可选，默认 true"); // 说明片段开关。
        addProperty(schema, "includeSnippet", includeSnippetProperty, false); // includeSnippet 可选。
        addProperty(schema, "maxItems", createIntegerProperty("最多返回调用项数量，可选，默认 100，最大 300"), false); // maxItems 可选。
        return schema; // 返回无 required path 的 schema。
    }

    @Override // 执行 Tool→Service 调用链分析。
    public String execute(JsonNode arguments) {
        String requestedPath = trimToNull(getOptionalText(arguments, "path", null)); // 读取可选 Tool 文件路径。
        String requestedToolName = trimToNull(getOptionalText(arguments, "toolName", null)); // 读取可选 toolName。
        String requestedClassName = trimToNull(getOptionalText(arguments, "className", null)); // 读取可选 Tool 类名。
        String entryMethod = trimToNull(getOptionalText(arguments, "methodName", DEFAULT_ENTRY_METHOD)); // 默认分析 execute 入口。
        int maxDepth = resolveMaxDepth(arguments); // 限制分析深度到 1-2。
        int maxItems = resolveMaxItems(arguments); // 限制返回调用项数量。
        boolean includeSnippet = resolveIncludeSnippet(arguments); // 控制是否输出调用表达式片段。
        if (entryMethod == null) { // 兜底避免空入口。
            entryMethod = DEFAULT_ENTRY_METHOD; // 回到 execute。
        }
        if (requestedPath == null && requestedToolName == null && requestedClassName == null) { // 三个目标参数必须至少提供一个。
            return buildFailureResult("", requestedToolName, requestedClassName, "请提供 AI Tool 源码文件路径、toolName 或 Tool 类名。").toString(); // 业务校验失败。
        }
        try {
            ToolFileResolution resolvedTool = resolveToolFile(requestedPath, requestedToolName, requestedClassName); // 根据 path/toolName/className 定位真实源码文件。
            if (!resolvedTool.isSuccess()) { // 多匹配或找不到时直接返回结构化失败结果。
                return resolvedTool.getFailureResult().toString(); // 返回候选提示。
            }
            Path filePath = resolvedTool.getFilePath(); // 真实 workspace 内文件路径。
            projectPathGuard.validateReadableCodeFile(filePath); // 再次复用 P4 安全校验。
            String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 读取文件名。
            String extension = projectPathGuard.getExtension(fileName); // 读取扩展名。
            String relativePath = support.toWorkspaceRelativePath(filePath, requestedPath); // 只返回 workspace 相对路径。
            if (!"java".equals(extension)) { // 第一版只支持 Java AI Tool。
                return buildFailureResult(relativePath, requestedToolName, requestedClassName, "当前 Tool → Service 分析仅支持 Java AI Tool。").toString(); // 非 Java 明确提示。
            }
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8); // 按 UTF-8 读取源码行。
            return analyzeJavaTool(filePath, relativePath, fileName, requestedToolName, requestedClassName,
                    entryMethod, maxDepth, maxItems, includeSnippet, lines).toString(); // 进入 Java Tool 专项分析。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[AnalyzeToolServiceChainTool] access denied, path: {}, toolName: {}, className: {}, reason: {}",
                    safeRelativePath(requestedPath), safeText(requestedToolName), safeText(requestedClassName), e.getMessage()); // 安全失败不打印绝对路径。
            return buildFailureResult(safeText(requestedPath), requestedToolName, requestedClassName, normalizeFailureMessage(e.getMessage())).toString(); // 返回友好失败 JSON。
        } catch (MalformedInputException e) {
            log.warn("[AnalyzeToolServiceChainTool] non utf8 file, path: {}", safeRelativePath(requestedPath)); // 编码错误只记录相对路径。
            return buildFailureResult(safeText(requestedPath), requestedToolName, requestedClassName, "文件编码暂不支持，请确认文件为 UTF-8 文本。").toString(); // 返回编码提示。
        } catch (IOException e) {
            log.warn("[AnalyzeToolServiceChainTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // IO 失败不打印源码内容。
            return buildFailureResult(safeText(requestedPath), requestedToolName, requestedClassName, "读取 Tool 源码失败，请稍后重试。").toString(); // 返回读取失败。
        } catch (Exception e) {
            log.error("[AnalyzeToolServiceChainTool] analyze failed, path: {}", safeRelativePath(requestedPath), e); // 系统错误保留堆栈供排查。
            return buildFailureResult(safeText(requestedPath), requestedToolName, requestedClassName, "Tool → Service 调用链分析失败，请稍后重试。").toString(); // 返回兜底失败。
        }
    }

    private ObjectNode analyzeJavaTool(Path filePath,
                                       String relativePath,
                                       String fileName,
                                       String requestedToolName,
                                       String requestedClassName,
                                       String entryMethod,
                                       int maxDepth,
                                       int maxItems,
                                       boolean includeSnippet,
                                       List<String> lines) throws IOException { // Java AI Tool 主分析流程。
        List<String> codeLines = support.stripComments(lines); // 去掉注释，保留字符串字面量，降低正则干扰。
        String content = String.join("\n", codeLines); // 合并去注释源码，供 Tool 特征识别。
        String packageName = extractPackageName(codeLines); // 提取 package。
        String className = firstNonBlank(extractClassName(codeLines), requestedClassName); // 提取真实类名，参数仅兜底。
        List<JavaMethod> methods = support.scanJavaMethods(codeLines); // 复用公共方法扫描器。
        ObjectNode aiToolInfo = buildAiToolInfo(content, relativePath, className, requestedToolName); // 构造 AI Tool 基础信息。
        if (!aiToolInfo.path("isAiTool").asBoolean(false)) { // 非 AI Tool 文件不做专项分析。
            ObjectNode failure = buildFailureResult(relativePath, requestedToolName, requestedClassName,
                    "该文件不是 AI Tool 实现类，无法进行 Tool → Service 专项分析。"); // 返回明确失败。
            failure.set("aiToolInfo", aiToolInfo); // 附带识别详情，帮助用户判断目标文件。
            return failure; // 结束分析。
        }
        ObjectNode result = buildBaseSuccessResult(filePath, relativePath, fileName, packageName, className,
                entryMethod, maxDepth, lines, aiToolInfo); // 构造成功结果骨架。
        ChainState state = new ChainState(maxItems, includeSnippet); // 初始化调用项计数和片段开关。
        Map<String, JavaMethod> methodByName = indexMethods(methods); // 建立方法名索引。
        JavaMethod entry = methodByName.get(entryMethod); // 查找 execute 或用户指定入口。
        if (entry == null || !entry.hasBody()) { // 入口方法不存在或没有方法体。
            ObjectNode failure = buildFailureResult(relativePath, requestedToolName, requestedClassName,
                    "未在该 Tool 中找到入口方法 " + entryMethod + "。"); // 返回入口缺失提示。
            failure.set("aiToolInfo", aiToolInfo); // 附带 Tool 识别信息。
            return failure; // 结束。
        }
        List<JavaField> dependencies = support.extractDependencies(codeLines, methods, className); // 提取字段注入和构造器注入依赖。
        Map<String, DependencyInfo> dependencyByField = fillDependencies(result.withArray("dependencies"), dependencies); // 写入依赖数组并建立 fieldName -> type/kind 映射。
        Map<String, List<String>> candidateCache = new LinkedHashMap<>(); // 候选文件缓存，避免同一类型重复扫描 workspace。
        List<ServiceCallCandidate> serviceCalls = new ArrayList<>(); // 记录 Service 调用，供 maxDepth=2 使用。
        List<String> firstLevelInternalMethods = new ArrayList<>(); // 记录 execute 直接调用的内部方法。
        extractCallsForMethod(entry, codeLines, methodByName, dependencyByField, candidateCache, result,
                state, serviceCalls, firstLevelInternalMethods); // 先分析 execute 入口方法。
        Set<String> expanded = new LinkedHashSet<>(); // 避免重复展开同一个内部方法。
        for (String internalMethodName : firstLevelInternalMethods) { // 展开 execute 直接内部调用。
            if (state.reachedLimit()) { // 达到 maxItems 即停止。
                state.truncated = true; // 标记截断。
                break; // 停止内部方法分析。
            }
            JavaMethod internalMethod = methodByName.get(internalMethodName); // 定位内部方法体。
            if (internalMethod != null && internalMethod.hasBody() && expanded.add(internalMethodName)) { // 只展开一层。
                extractCallsForMethod(internalMethod, codeLines, methodByName, dependencyByField, candidateCache, result,
                        state, serviceCalls, new ArrayList<>()); // 内部方法里的外部依赖会被分类，新的内部调用只记录不递归展开。
            }
        }
        if (maxDepth >= 2 && !serviceCalls.isEmpty()) { // 用户要求二层分析且存在 Service 调用。
            analyzeServiceImplOneLevel(serviceCalls, result.withArray("serviceMethodCalls"), state); // 尝试展开 ServiceImpl 一层。
        }
        result.put("truncated", state.truncated); // 写入是否截断。
        return result; // 返回完整结构化结果。
    }

    private Map<String, DependencyInfo> fillDependencies(ArrayNode dependenciesNode,
                                                         List<JavaField> dependencies) { // 写入依赖对象并返回字段索引。
        Map<String, DependencyInfo> dependencyByField = new LinkedHashMap<>(); // fieldName -> DependencyInfo。
        for (JavaField dependency : dependencies == null ? List.<JavaField>of() : dependencies) { // 遍历公共解析器识别出的依赖。
            String fieldName = safeText(dependency.getFieldName()); // 字段名。
            String type = support.simpleTypeName(dependency.getType()); // 简单类型名。
            if (fieldName.isBlank() || type.isBlank()) { // 空字段跳过。
                continue; // 处理下一个依赖。
            }
            String kind = classifyDependencyKind(type); // 按命名约定归类 Service/Mapper/Guard 等。
            DependencyInfo info = new DependencyInfo(fieldName, type, kind); // 保存到索引。
            dependencyByField.put(fieldName, info); // 建立字段到依赖信息的映射。
            ObjectNode node = objectMapper.createObjectNode(); // 构造依赖节点。
            node.put("fieldName", fieldName); // 写入字段名。
            node.put("type", type); // 写入依赖类型。
            node.put("kind", kind); // 写入依赖分类。
            node.put("injectionType", safeText(dependency.getInjectionType())); // 写入注入方式。
            node.put("lineNumber", dependency.getLineNumber()); // 写入声明行号。
            node.set("candidateFiles", buildCandidateTargets(type, new LinkedHashMap<>())); // 写入候选目标文件。
            dependenciesNode.add(node); // 追加到 dependencies。
        }
        return dependencyByField; // 返回字段索引。
    }

    private void extractCallsForMethod(JavaMethod method,
                                       List<String> codeLines,
                                       Map<String, JavaMethod> methodByName,
                                       Map<String, DependencyInfo> dependencyByField,
                                       Map<String, List<String>> candidateCache,
                                       ObjectNode result,
                                       ChainState state,
                                       List<ServiceCallCandidate> serviceCalls,
                                       List<String> collectedInternalMethods) { // 分析单个 Tool 方法内部的调用。
        List<JavaCall> calls = support.extractCalls(codeLines, method); // 复用公共调用提取器。
        Set<String> seenInMethod = new LinkedHashSet<>(); // 当前方法内按接收者/方法/行号去重。
        for (JavaCall call : calls) { // 遍历调用候选。
            if (state.reachedLimit()) { // 达到 maxItems 即停止。
                state.truncated = true; // 标记截断。
                return; // 停止当前方法。
            }
            String receiver = trimToNull(call.getReceiver()); // 调用接收者，可为空。
            String callee = safeText(call.getCallee()); // 被调用方法。
            String dedupKey = (receiver == null ? "" : receiver) + "|" + callee + "|" + call.getLineNumber(); // 去重键。
            if (!seenInMethod.add(dedupKey)) { // 避免同一行重复匹配。
                continue; // 跳过重复。
            }
            classifyCall(method.getName(), receiver, callee, call.getLineNumber(), call.getCallExpression(),
                    methodByName, dependencyByField, candidateCache, result, state, serviceCalls, collectedInternalMethods); // 分类写入对应数组。
        }
    }

    private void classifyCall(String fromMethod,
                              String receiver,
                              String callee,
                              int lineNumber,
                              String callExpression,
                              Map<String, JavaMethod> methodByName,
                              Map<String, DependencyInfo> dependencyByField,
                              Map<String, List<String>> candidateCache,
                              ObjectNode result,
                              ChainState state,
                              List<ServiceCallCandidate> serviceCalls,
                              List<String> collectedInternalMethods) { // 将调用候选分类成内部调用、Service、Guard、Registry 等。
        if (callee == null || callee.isBlank()) { // 空方法名不可分析。
            return; // 跳过。
        }
        if (receiver == null && methodByName.containsKey(callee)) { // 裸调用且命中当前类方法。
            addInternalCall(result.withArray("internalCalls"), fromMethod, callee, lineNumber, callExpression, state); // 写入内部调用。
            collectedInternalMethods.add(callee); // 记录供 execute 一层展开。
            return; // 完成分类。
        }
        if ("this".equals(receiver) && methodByName.containsKey(callee)) { // this.xxx() 命中当前类方法。
            addInternalCall(result.withArray("internalCalls"), fromMethod, callee, lineNumber, callExpression, state); // 写入内部调用。
            collectedInternalMethods.add(callee); // 记录供一层展开。
            return; // 完成分类。
        }
        if (receiver != null && dependencyByField.containsKey(receiver)) { // 接收者是已识别依赖字段。
            DependencyInfo dependency = dependencyByField.get(receiver); // 读取依赖类型和分类。
            ArrayNode targetArray = resolveCallArray(result, dependency.kind); // 根据分类选择输出数组。
            ObjectNode node = buildComponentCall(fromMethod, receiver, dependency.type, dependency.kind, callee,
                    lineNumber, callExpression, candidateCache, state.includeSnippet); // 构造组件调用节点。
            addLimited(targetArray, node, state); // 写入对应数组。
            if ("SERVICE".equals(dependency.kind)) { // Service 调用需要保留给二层分析。
                serviceCalls.add(new ServiceCallCandidate(dependency.type, callee, candidatePaths(dependency.type, candidateCache))); // 保存 Service 调用候选。
            }
            return; // 完成分类。
        }
        if (receiver != null && UTILITY_RECEIVERS.contains(receiver)) { // 常见基础设施或静态工具调用。
            ObjectNode node = buildSimpleCall(fromMethod, lineNumber, callExpression, state.includeSnippet); // 构造工具调用节点。
            node.put("objectName", receiver); // 写入接收者。
            node.put("methodName", callee); // 写入方法名。
            node.put("kind", "UTILITY"); // 标记为工具调用。
            addLimited(result.withArray("utilityCalls"), node, state); // 写入 utilityCalls。
            return; // 完成分类。
        }
        if (receiver != null && looksLikeStaticUtility(receiver)) { // 大写开头的静态工具类调用。
            ObjectNode node = buildSimpleCall(fromMethod, lineNumber, callExpression, state.includeSnippet); // 构造工具调用节点。
            node.put("objectName", receiver); // 写入类名或对象名。
            node.put("methodName", callee); // 写入方法名。
            node.put("kind", "UTILITY"); // 标记工具调用。
            addLimited(result.withArray("utilityCalls"), node, state); // 写入 utilityCalls。
            return; // 完成分类。
        }
        if (receiver != null) { // 有接收者但无法识别为依赖或工具类。
            ObjectNode node = buildSimpleCall(fromMethod, lineNumber, callExpression, state.includeSnippet); // 构造未解析调用。
            node.put("objectName", receiver); // 写入接收者。
            node.put("methodName", callee); // 写入方法名。
            addLimited(result.withArray("unresolvedCalls"), node, state); // 写入 unresolvedCalls。
        }
    }

    private void analyzeServiceImplOneLevel(List<ServiceCallCandidate> serviceCalls,
                                            ArrayNode serviceMethodCalls,
                                            ChainState state) { // maxDepth=2 时展开 ServiceImpl 一层调用。
        Map<String, ServiceImplContext> implCache = new LinkedHashMap<>(); // 每个 ServiceImpl 文件最多读取一次。
        int analyzed = 0; // 已展开的 Service 调用数量。
        for (ServiceCallCandidate serviceCall : serviceCalls) { // 遍历 Tool 内部直接 Service 调用。
            if (analyzed >= MAX_DEPTH2_SERVICE_CALLS || state.reachedLimit()) { // 达到数量或调用项限制时停止。
                state.truncated = state.truncated || state.reachedLimit(); // 标记可能截断。
                return; // 停止二层分析。
            }
            String implPath = pickServiceImplPath(serviceCall.candidateTargets); // 选择 ServiceImpl 候选文件。
            if (implPath == null) { // 没有实现类候选。
                continue; // 跳过该 Service 调用。
            }
            ServiceImplContext context = implCache.computeIfAbsent(implPath, this::readServiceImplContext); // 读取或复用 ServiceImpl 上下文。
            if (context == null || context.methodsByName.isEmpty()) { // 读取失败或没有方法。
                continue; // 跳过。
            }
            JavaMethod serviceMethod = context.methodsByName.get(serviceCall.methodName); // 找到对应 Service 方法。
            if (serviceMethod == null || !serviceMethod.hasBody()) { // 方法不存在或无方法体。
                continue; // 跳过。
            }
            analyzed++; // 记录已展开数量。
            List<JavaCall> calls = support.extractCalls(context.codeLines, serviceMethod); // 提取 ServiceImpl 方法内部调用。
            Set<String> seen = new LinkedHashSet<>(); // 当前 Service 方法内去重。
            for (JavaCall call : calls) { // 遍历内部调用。
                if (state.reachedLimit()) { // 达到 maxItems 停止。
                    state.truncated = true; // 标记截断。
                    return; // 结束二层分析。
                }
                String receiver = trimToNull(call.getReceiver()); // 接收者。
                String callee = safeText(call.getCallee()); // 方法名。
                String dedupKey = (receiver == null ? "" : receiver) + "|" + callee + "|" + call.getLineNumber(); // 去重键。
                if (!seen.add(dedupKey)) { // 同一行重复跳过。
                    continue; // 处理下一条。
                }
                addServiceMethodCall(serviceMethodCalls, serviceCall, context, receiver, callee,
                        call.getLineNumber(), call.getCallExpression(), state); // 写入 serviceMethodCalls。
            }
        }
    }

    private void addServiceMethodCall(ArrayNode serviceMethodCalls,
                                      ServiceCallCandidate serviceCall,
                                      ServiceImplContext context,
                                      String receiver,
                                      String callee,
                                      int lineNumber,
                                      String callExpression,
                                      ChainState state) { // 写入 ServiceImpl 内部一层调用。
        if (callee == null || callee.isBlank()) { // 空方法名跳过。
            return; // 不写入。
        }
        ObjectNode node = buildSimpleCall(serviceCall.methodName, lineNumber, callExpression, state.includeSnippet); // 构造基础调用字段。
        node.put("serviceType", serviceCall.serviceType); // 写入 Service 接口类型。
        node.put("serviceMethod", serviceCall.methodName); // 写入 Service 方法。
        node.put("serviceImplPath", context.relativePath); // 写入 ServiceImpl 相对路径。
        if (receiver == null && context.methodsByName.containsKey(callee)) { // 裸调用命中 ServiceImpl 内部方法。
            node.put("kind", "INTERNAL"); // 标记内部调用。
            node.put("targetType", context.className); // 目标类型是当前实现类。
            node.put("methodName", callee); // 写入目标方法。
            addLimited(serviceMethodCalls, node, state); // 写入结果。
            return; // 完成。
        }
        if ("this".equals(receiver)) { // this.xxx() 视为 ServiceImpl 内部调用。
            node.put("kind", "INTERNAL"); // 标记内部调用。
            node.put("objectName", receiver); // 写入接收者。
            node.put("targetType", context.className); // 目标类型是当前类。
            node.put("methodName", callee); // 写入目标方法。
            addLimited(serviceMethodCalls, node, state); // 写入结果。
            return; // 完成。
        }
        if (receiver != null && context.dependencyByField.containsKey(receiver)) { // ServiceImpl 内部依赖调用。
            DependencyInfo dependency = context.dependencyByField.get(receiver); // 读取依赖信息。
            node.put("kind", dependency.kind); // 写入分类。
            node.put("objectName", receiver); // 写入依赖字段名。
            node.put("targetType", dependency.type); // 写入依赖类型。
            node.put("methodName", callee); // 写入被调方法。
            node.set("candidateTargets", buildCandidateTargets(dependency.type, new LinkedHashMap<>())); // 写入候选文件。
            addLimited(serviceMethodCalls, node, state); // 写入结果。
            return; // 完成。
        }
        if (receiver != null && (UTILITY_RECEIVERS.contains(receiver) || looksLikeStaticUtility(receiver))) { // 工具类调用。
            node.put("kind", "UTILITY"); // 标记工具调用。
            node.put("objectName", receiver); // 写入接收者。
            node.put("methodName", callee); // 写入方法名。
            addLimited(serviceMethodCalls, node, state); // 写入结果。
            return; // 完成。
        }
        if (receiver != null) { // 未解析的外部调用。
            node.put("kind", "UNRESOLVED"); // 标记未解析。
            node.put("objectName", receiver); // 写入接收者。
            node.put("methodName", callee); // 写入方法名。
            addLimited(serviceMethodCalls, node, state); // 写入结果。
        }
    }

    private ServiceImplContext readServiceImplContext(String relativePath) { // 安全读取 ServiceImpl 上下文。
        try {
            Path filePath = support.resolveReadableProjectFile(relativePath); // 解析 workspace 相对路径。
            projectPathGuard.validateReadableCodeFile(filePath); // 校验文件可读且安全。
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8); // 读取实现类源码。
            List<String> codeLines = support.stripComments(lines); // 去注释以便方法和调用扫描。
            String className = extractClassName(codeLines); // 提取实现类名。
            List<JavaMethod> methods = support.scanJavaMethods(codeLines); // 扫描方法。
            Map<String, JavaMethod> methodsByName = indexMethods(methods); // 建立方法索引。
            List<JavaField> dependencies = support.extractDependencies(codeLines, methods, className); // 提取实现类依赖。
            Map<String, DependencyInfo> dependencyByField = new LinkedHashMap<>(); // 构造依赖索引。
            for (JavaField dependency : dependencies) { // 遍历依赖。
                String fieldName = safeText(dependency.getFieldName()); // 字段名。
                String type = support.simpleTypeName(dependency.getType()); // 简单类型名。
                if (!fieldName.isBlank() && !type.isBlank()) { // 有效依赖才记录。
                    dependencyByField.put(fieldName, new DependencyInfo(fieldName, type, classifyDependencyKind(type))); // 保存分类。
                }
            }
            return new ServiceImplContext(relativePath, className, codeLines, methodsByName, dependencyByField); // 返回上下文。
        } catch (Exception e) {
            log.debug("[AnalyzeToolServiceChainTool] skip service impl analyze, path: {}, reason: {}", safeRelativePath(relativePath), e.getMessage()); // 二层失败降级，不影响主结果。
            return null; // 读取失败时跳过。
        }
    }

    private ToolFileResolution resolveToolFile(String requestedPath,
                                               String requestedToolName,
                                               String requestedClassName) throws IOException { // 根据三类目标定位 Tool 源码。
        if (requestedPath != null) { // path 优先。
            Path path = support.resolveReadableProjectFile(requestedPath); // 支持明确路径、文件名或类名定位。
            return ToolFileResolution.success(path); // 返回唯一文件。
        }
        if (requestedToolName != null) { // toolName 次优先。
            List<ToolFileMatch> matches = findToolFilesByToolName(requestedToolName); // 全 workspace 扫描真实 Tool。
            if (matches.isEmpty()) { // 没有真实匹配。
                return ToolFileResolution.failure(buildFailureResult("", requestedToolName, requestedClassName,
                        "未在 workspace 中找到 toolName 为 " + requestedToolName + " 的 AI Tool 实现类。")); // 不捏造路径。
            }
            if (matches.size() > 1) { // 多个匹配不乱选。
                ObjectNode failure = buildFailureResult("", requestedToolName, requestedClassName,
                        "找到多个 toolName 为 " + requestedToolName + " 的 AI Tool，请指定 className 或 path。"); // 返回选择提示。
                failure.set("candidateTools", buildCandidateTools(matches)); // 附带候选 Tool。
                return ToolFileResolution.failure(failure); // 返回多匹配失败。
            }
            return ToolFileResolution.success(matches.get(0).filePath); // 唯一匹配。
        }
        String classTarget = requestedClassName.endsWith(".java") ? requestedClassName : requestedClassName + ".java"; // 类名补 .java。
        Path path = support.resolveReadableProjectFile(classTarget); // 复用公共文件唯一定位。
        return ToolFileResolution.success(path); // 返回真实文件。
    }

    private List<ToolFileMatch> findToolFilesByToolName(String requestedToolName) throws IOException { // 根据 toolName 扫描 AI Tool 实现类。
        List<ToolFileMatch> matches = new ArrayList<>(); // 匹配结果。
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // workspace 根路径。
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) { // workspace 不存在时无法扫描。
            return matches; // 返回空结果。
        }
        List<Path> javaFiles = new ArrayList<>(); // 安全 Java 文件列表。
        collectSafeJavaFiles(workspaceRoot, javaFiles); // 递归收集，跳过敏感目录。
        for (Path javaFile : javaFiles) { // 遍历 Java 文件。
            if (matches.size() >= MAX_TOOL_MATCHES) { // 候选上限。
                break; // 停止扫描。
            }
            List<String> lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8); // 读取源码。
            String content = String.join("\n", support.stripComments(lines)); // 去注释后识别 Tool 特征。
            if (!looksLikeToolSource(javaFile, content)) { // 非 Tool 候选文件跳过。
                continue; // 处理下一个文件。
            }
            String actualToolName = extractToolName(content); // 提取真实 toolName。
            if (requestedToolName.equals(actualToolName)) { // 精确匹配 toolName。
                String className = extractClassName(support.stripComments(lines)); // 提取类名。
                String relativePath = support.toWorkspaceRelativePath(javaFile, ""); // 转相对路径。
                matches.add(new ToolFileMatch(actualToolName, className, javaFile, relativePath)); // 记录候选。
            }
        }
        matches.sort(Comparator.comparing(match -> match.relativePath, String.CASE_INSENSITIVE_ORDER)); // 稳定排序。
        return matches; // 返回真实匹配。
    }

    private void collectSafeJavaFiles(Path directory,
                                      List<Path> javaFiles) { // 递归收集 workspace 内安全 Java 文件。
        if (javaFiles.size() >= MAX_TOOL_SCAN_FILES || !isSafeScanDirectory(directory)) { // 到上限或目录不安全时停止。
            return; // 不进入该目录。
        }
        try (Stream<Path> stream = Files.list(directory)) { // 只列当前目录，便于按目录剪枝。
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName() == null ? "" : path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList(); // 稳定排序，便于结果可复现。
            for (Path child : children) { // 遍历子项。
                if (javaFiles.size() >= MAX_TOOL_SCAN_FILES) { // 达到上限。
                    return; // 停止收集。
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { // 子目录继续递归。
                    collectSafeJavaFiles(child, javaFiles); // 递归扫描。
                } else if (isSafeJavaFile(child)) { // 安全 Java 文件。
                    javaFiles.add(child.toAbsolutePath().normalize()); // 收集候选。
                }
            }
        } catch (IOException ignored) {
            // 跳过不可读目录，不暴露服务器绝对路径。
        }
    }

    private boolean isSafeScanDirectory(Path directory) { // 判断目录是否允许进入扫描。
        if (directory == null) { // 空目录不可进入。
            return false; // 返回 false。
        }
        Path normalizedPath = directory.toAbsolutePath().normalize(); // 标准化目录。
        String name = normalizedPath.getFileName() == null ? "" : normalizedPath.getFileName().toString(); // 当前目录名。
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !SKIPPED_SCAN_DIRECTORIES.contains(name)
                && Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 必须在 workspace 内、非软链、非敏感目录。
    }

    private boolean isSafeJavaFile(Path filePath) { // 判断文件是否可作为 Tool 扫描候选。
        if (filePath == null || filePath.getFileName() == null) { // 空路径不可用。
            return false; // 返回 false。
        }
        Path normalizedPath = filePath.toAbsolutePath().normalize(); // 标准化文件路径。
        String fileName = filePath.getFileName().toString(); // 文件名。
        String extension = projectPathGuard.getExtension(fileName); // 扩展名。
        return "java".equals(extension)
                && projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !projectPathGuard.isBlockedFilename(fileName)
                && !projectPathGuard.isBlockedExtension(extension)
                && projectPathGuard.isAllowedExtension(extension)
                && Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 只允许 workspace 内普通 Java 文件。
    }

    private ObjectNode buildBaseSuccessResult(Path filePath,
                                              String relativePath,
                                              String fileName,
                                              String packageName,
                                              String className,
                                              String entryMethod,
                                              int maxDepth,
                                              List<String> lines,
                                              ObjectNode aiToolInfo) throws IOException { // 构造成功结果骨架。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", true); // 标记成功。
        result.put("path", relativePath); // 写入 workspace 相对路径。
        result.put("matchedToolPath", relativePath); // 明确 Tool 匹配路径，便于 tool_call_log 检索。
        result.put("fileName", fileName); // 写入文件名。
        result.put("extension", "java"); // 第一版只支持 Java。
        result.put("language", CodeLanguageRegistry.resolveByFileName(fileName).getDisplayName()); // 写入语言显示名。
        result.put("packageName", safeText(packageName)); // 写入包名。
        result.put("className", safeText(className)); // 写入类名。
        result.put("entryMethod", entryMethod); // 写入入口方法。
        result.put("maxDepth", maxDepth); // 写入分析深度。
        result.put("lineCount", lines == null ? 0 : lines.size()); // 写入行数。
        result.put("fileSize", Files.size(filePath)); // 写入文件大小。
        result.set("aiToolInfo", aiToolInfo); // 写入 AI Tool 识别信息。
        result.set("dependencies", objectMapper.createArrayNode()); // 注入依赖数组。
        result.set("internalCalls", objectMapper.createArrayNode()); // 内部方法调用数组。
        result.set("serviceCalls", objectMapper.createArrayNode()); // Service 调用数组。
        result.set("guardCalls", objectMapper.createArrayNode()); // Guard 调用数组。
        result.set("registryCalls", objectMapper.createArrayNode()); // Registry 调用数组。
        result.set("mapperCalls", objectMapper.createArrayNode()); // Mapper 调用数组。
        result.set("repositoryCalls", objectMapper.createArrayNode()); // Repository 调用数组。
        result.set("toolCalls", objectMapper.createArrayNode()); // Tool 组件调用数组。
        result.set("utilityCalls", objectMapper.createArrayNode()); // 工具/基础设施调用数组。
        result.set("unresolvedCalls", objectMapper.createArrayNode()); // 未解析调用数组。
        result.set("serviceMethodCalls", objectMapper.createArrayNode()); // ServiceImpl 一层调用数组。
        ArrayNode warnings = objectMapper.createArrayNode(); // warnings 数组。
        warnings.add(ANALYSIS_WARNING); // 写入轻量分析说明。
        result.set("warnings", warnings); // 写入 warnings。
        result.put("truncated", false); // 默认未截断。
        return result; // 返回结果骨架。
    }

    private ObjectNode buildAiToolInfo(String content,
                                       String relativePath,
                                       String className,
                                       String requestedToolName) { // 构造 AI Tool 识别信息。
        String safeContent = content == null ? "" : content; // 内容兜底。
        String toolName = firstNonBlank(extractToolName(safeContent), requestedToolName); // 优先源码真实 toolName。
        boolean extendsAbstractAiTool = safeContent.contains("extends AbstractAiTool"); // 强特征：继承 AbstractAiTool。
        boolean implementsAiTool = safeContent.contains("implements AiTool"); // 强特征：实现 AiTool。
        boolean hasToolNameConstant = TOOL_NAME_PATTERN.matcher(safeContent).find(); // 强特征：TOOL_NAME 常量。
        boolean hasNameMethod = Pattern.compile("\\bname\\s*\\(").matcher(safeContent).find(); // name() 方法。
        boolean hasGetNameMethod = Pattern.compile("\\bgetName\\s*\\(").matcher(safeContent).find(); // getName() 方法。
        boolean hasExecuteMethod = Pattern.compile("\\bexecute\\s*\\(\\s*JsonNode\\b").matcher(safeContent).find(); // execute(JsonNode arguments)。
        boolean hasParametersSchema = safeContent.contains("parametersSchema("); // 参数 schema 方法。
        boolean hasDescription = safeContent.contains("description()"); // description 方法。
        boolean toolPackageOrName = safeText(relativePath).contains("/tool/") || safeText(className).endsWith("Tool"); // 包路径或类名符合 Tool。
        boolean isAiTool = !toolName.isBlank() || extendsAbstractAiTool || implementsAiTool || hasToolNameConstant
                || hasExecuteMethod || (hasNameMethod && hasParametersSchema) || toolPackageOrName; // 任一强特征即可认为是 AI Tool。
        ObjectNode node = objectMapper.createObjectNode(); // 创建识别节点。
        node.put("isAiTool", isAiTool); // 是否 AI Tool。
        node.put("toolName", toolName); // toolName。
        node.put("className", safeText(className)); // 类名。
        node.put("extendsAbstractAiTool", extendsAbstractAiTool); // 是否继承 AbstractAiTool。
        node.put("implementsAiTool", implementsAiTool); // 是否实现 AiTool。
        node.put("hasToolNameConstant", hasToolNameConstant); // 是否有 TOOL_NAME。
        node.put("hasNameMethod", hasNameMethod); // 是否有 name()。
        node.put("hasGetNameMethod", hasGetNameMethod); // 是否有 getName()。
        node.put("hasExecuteMethod", hasExecuteMethod); // 是否有 execute(JsonNode)。
        node.put("hasParametersSchema", hasParametersSchema); // 是否有 parametersSchema。
        node.put("hasDescription", hasDescription); // 是否有 description。
        node.put("registeredByToolRegistryCandidate", isAiTool && (extendsAbstractAiTool || implementsAiTool || hasNameMethod)); // ToolRegistry 自动注册候选判断。
        return node; // 返回识别信息。
    }

    private String extractToolName(String content) { // 从源码中提取 toolName。
        String safeContent = content == null ? "" : content; // 内容兜底。
        Matcher constantMatcher = TOOL_NAME_PATTERN.matcher(safeContent); // 先匹配 TOOL_NAME 常量。
        if (constantMatcher.find()) { // 命中常量。
            return safeText(constantMatcher.group(1)); // 返回常量值。
        }
        Matcher nameReturnMatcher = NAME_RETURN_PATTERN.matcher(safeContent); // 再匹配 name()/getName() 字面量返回。
        if (nameReturnMatcher.find()) { // 命中字面量返回。
            return safeText(nameReturnMatcher.group(1)); // 返回工具名。
        }
        return ""; // 未找到。
    }

    private String extractPackageName(List<String> codeLines) { // 提取 Java package。
        for (String line : codeLines == null ? List.<String>of() : codeLines) { // 逐行扫描。
            Matcher matcher = PACKAGE_PATTERN.matcher(line); // 匹配 package。
            if (matcher.find()) { // 命中。
                return safeText(matcher.group(1)); // 返回包名。
            }
        }
        return ""; // 未找到。
    }

    private String extractClassName(List<String> codeLines) { // 提取 Java class 名。
        for (String line : codeLines == null ? List.<String>of() : codeLines) { // 逐行扫描。
            Matcher matcher = CLASS_PATTERN.matcher(line); // 匹配 class 声明。
            if (matcher.find()) { // 命中。
                return safeText(matcher.group(1)); // 返回类名。
            }
        }
        return ""; // 未找到。
    }

    private boolean looksLikeToolSource(Path javaFile,
                                        String content) { // 判断 Java 文件是否像 AI Tool 实现。
        String relativePath = support.toWorkspaceRelativePath(javaFile, "").replace('\\', '/'); // 相对路径统一斜杠。
        String fileName = javaFile == null || javaFile.getFileName() == null ? "" : javaFile.getFileName().toString(); // 文件名。
        String safeContent = content == null ? "" : content; // 内容兜底。
        return fileName.endsWith("Tool.java")
                || relativePath.contains("/tool/")
                || safeContent.contains("extends AbstractAiTool")
                || safeContent.contains("implements AiTool")
                || TOOL_NAME_PATTERN.matcher(safeContent).find()
                || safeContent.contains("execute(JsonNode"); // 任一特征命中即可作为 Tool 候选。
    }

    private Map<String, JavaMethod> indexMethods(List<JavaMethod> methods) { // 按方法名建立索引。
        Map<String, JavaMethod> methodByName = new LinkedHashMap<>(); // 方法索引。
        for (JavaMethod method : methods == null ? List.<JavaMethod>of() : methods) { // 遍历方法。
            methodByName.putIfAbsent(method.getName(), method); // 同名重载第一版只保留第一项。
        }
        return methodByName; // 返回索引。
    }

    private String classifyDependencyKind(String type) { // 按类型名分类依赖对象。
        String simpleType = support.simpleTypeName(type); // 归一化简单类型名。
        if (simpleType.endsWith("Service")) { // Service 接口或类。
            return "SERVICE"; // 业务 Service。
        }
        if (simpleType.endsWith("Mapper")) { // MyBatis Mapper。
            return "MAPPER"; // Mapper。
        }
        if (simpleType.endsWith("Repository")) { // Repository。
            return "REPOSITORY"; // Repository。
        }
        if (simpleType.contains("Guard")) { // Guard 安全组件。
            return "GUARD"; // Guard。
        }
        if (simpleType.contains("Registry")) { // Registry 注册表组件。
            return "REGISTRY"; // Registry。
        }
        if (simpleType.contains("Properties") || simpleType.contains("Config")) { // 配置对象。
            return "CONFIG"; // 配置。
        }
        if (simpleType.endsWith("Client")) { // 客户端组件。
            return "CLIENT"; // Client。
        }
        if (simpleType.endsWith("Tool")) { // 另一个 Tool。
            return "TOOL"; // Tool。
        }
        if (UTILITY_TYPES.contains(simpleType)) { // 基础工具类型。
            return "UTILITY"; // Utility。
        }
        return "DEPENDENCY"; // 其它依赖。
    }

    private ArrayNode resolveCallArray(ObjectNode result,
                                       String kind) { // 根据依赖分类选择目标数组。
        return switch (kind) {
            case "SERVICE" -> result.withArray("serviceCalls"); // Service 调用。
            case "GUARD" -> result.withArray("guardCalls"); // Guard 调用。
            case "REGISTRY" -> result.withArray("registryCalls"); // Registry 调用。
            case "MAPPER" -> result.withArray("mapperCalls"); // Mapper 调用。
            case "REPOSITORY" -> result.withArray("repositoryCalls"); // Repository 调用。
            case "TOOL" -> result.withArray("toolCalls"); // Tool 调用。
            case "UTILITY", "CONFIG", "CLIENT" -> result.withArray("utilityCalls"); // 配置/客户端/工具统一放 utilityCalls。
            default -> result.withArray("unresolvedCalls"); // 其它未解析依赖放 unresolvedCalls。
        };
    }

    private ObjectNode buildComponentCall(String fromMethod,
                                          String receiver,
                                          String targetType,
                                          String kind,
                                          String methodName,
                                          int lineNumber,
                                          String callExpression,
                                          Map<String, List<String>> candidateCache,
                                          boolean includeSnippet) { // 构造外部组件调用节点。
        ObjectNode node = buildSimpleCall(fromMethod, lineNumber, callExpression, includeSnippet); // 基础字段。
        node.put("objectName", receiver); // 调用对象字段名。
        node.put("targetType", targetType); // 目标类型。
        node.put("kind", kind); // 目标分类。
        node.put("methodName", methodName); // 被调方法。
        node.set("candidateTargets", buildCandidateTargets(targetType, candidateCache)); // 候选目标文件。
        return node; // 返回节点。
    }

    private ObjectNode buildSimpleCall(String fromMethod,
                                       int lineNumber,
                                       String callExpression,
                                       boolean includeSnippet) { // 构造调用节点公共字段。
        ObjectNode node = objectMapper.createObjectNode(); // 创建节点。
        node.put("fromMethod", fromMethod); // 来源方法。
        node.put("lineNumber", lineNumber); // 调用行号。
        if (includeSnippet) { // 用户要求包含少量片段时才输出。
            node.put("callExpression", safeText(callExpression)); // 调用表达式片段，不输出完整源码。
        }
        return node; // 返回节点。
    }

    private void addInternalCall(ArrayNode internalCalls,
                                 String fromMethod,
                                 String toMethod,
                                 int lineNumber,
                                 String callExpression,
                                 ChainState state) { // 写入内部方法调用。
        ObjectNode node = objectMapper.createObjectNode(); // 创建内部调用节点。
        node.put("fromMethod", fromMethod); // 来源方法。
        node.put("toMethod", toMethod); // 目标内部方法。
        node.put("lineNumber", lineNumber); // 行号。
        if (state.includeSnippet) { // 片段开关。
            node.put("callExpression", safeText(callExpression)); // 调用表达式。
        }
        addLimited(internalCalls, node, state); // 写入数组并计数。
    }

    private ArrayNode buildCandidateTargets(String targetType,
                                            Map<String, List<String>> candidateCache) { // 解析目标类型候选文件。
        ArrayNode targets = objectMapper.createArrayNode(); // 候选文件数组。
        for (String path : candidatePaths(targetType, candidateCache)) { // 读取候选路径。
            targets.add(path); // 写入相对路径。
        }
        return targets; // 返回候选数组。
    }

    private List<String> candidatePaths(String targetType,
                                        Map<String, List<String>> candidateCache) { // 获取目标类型候选路径列表。
        if (targetType == null || targetType.isBlank()) { // 空类型无候选。
            return List.of(); // 返回空。
        }
        if (candidateCache != null && candidateCache.containsKey(targetType)) { // 命中缓存。
            return candidateCache.get(targetType); // 返回缓存结果。
        }
        List<String> paths = new ArrayList<>(); // 候选相对路径。
        for (Path candidate : support.findTypeCandidatePaths(targetType, MAX_CANDIDATE_FILES)) { // 复用公共类型定位。
            paths.add(support.toWorkspaceRelativePath(candidate, "")); // 转 workspace 相对路径。
        }
        if (candidateCache != null) { // 有缓存时写入。
            candidateCache.put(targetType, paths); // 缓存候选。
        }
        return paths; // 返回候选。
    }

    private String pickServiceImplPath(List<String> candidateTargets) { // 选择 ServiceImpl 候选文件。
        if (candidateTargets == null || candidateTargets.isEmpty()) { // 没有候选。
            return null; // 返回空。
        }
        for (String candidate : candidateTargets) { // 优先选择 Impl.java。
            if (candidate != null && candidate.endsWith("Impl.java")) { // 命中实现类。
                return candidate; // 返回实现类路径。
            }
        }
        for (String candidate : candidateTargets) { // 如果 Service 类型本身就是实现类，也允许使用第一个 Java 候选。
            if (candidate != null && candidate.endsWith(".java")) { // Java 候选。
                return candidate; // 返回候选。
            }
        }
        return null; // 没有可用实现。
    }

    private void addLimited(ArrayNode array,
                            ObjectNode node,
                            ChainState state) { // 按 maxItems 写入调用项。
        if (array == null || node == null || state == null) { // 兜底。
            return; // 不写入。
        }
        if (state.reachedLimit()) { // 达到上限。
            state.truncated = true; // 标记截断。
            return; // 停止写入。
        }
        array.add(node); // 追加节点。
        state.count++; // 调用项计数加一。
    }

    private ObjectNode buildFailureResult(String path,
                                          String toolName,
                                          String className,
                                          String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", false); // 标记失败。
        result.put("path", safeText(path)); // 写入请求路径或已解析路径。
        if (toolName != null && !toolName.isBlank()) { // 有 toolName 时写入。
            result.put("toolName", toolName); // toolName。
        }
        if (className != null && !className.isBlank()) { // 有 className 时写入。
            result.put("className", className); // className。
        }
        result.put("message", safeText(message)); // 写入失败原因。
        return result; // 返回失败对象。
    }

    private ArrayNode buildCandidateTools(List<ToolFileMatch> matches) { // 构造多匹配候选 Tool 列表。
        ArrayNode array = objectMapper.createArrayNode(); // 创建数组。
        int count = 0; // 控制最多返回数量。
        for (ToolFileMatch match : matches == null ? List.<ToolFileMatch>of() : matches) { // 遍历匹配项。
            if (count++ >= MAX_TOOL_MATCHES) { // 超出上限。
                break; // 停止。
            }
            ObjectNode node = objectMapper.createObjectNode(); // 候选节点。
            node.put("toolName", safeText(match.toolName)); // 工具名。
            node.put("className", safeText(match.className)); // 类名。
            node.put("path", safeText(match.relativePath)); // 相对路径。
            array.add(node); // 写入候选。
        }
        return array; // 返回候选数组。
    }

    private int resolveMaxDepth(JsonNode arguments) { // 读取并限制 maxDepth。
        int depth = getOptionalInt(arguments, "maxDepth", DEFAULT_MAX_DEPTH); // 读取默认值。
        return Math.max(1, Math.min(depth, MAX_ALLOWED_DEPTH)); // 限制范围。
    }

    private int resolveMaxItems(JsonNode arguments) { // 读取并限制 maxItems。
        int maxItems = getOptionalInt(arguments, "maxItems", DEFAULT_MAX_ITEMS); // 读取默认值。
        return Math.max(1, Math.min(maxItems, MAX_ALLOWED_ITEMS)); // 限制范围。
    }

    private boolean resolveIncludeSnippet(JsonNode arguments) { // 读取 includeSnippet。
        if (arguments == null || arguments.path("includeSnippet").isMissingNode()) { // 未传时默认 true。
            return true; // 返回默认值。
        }
        return arguments.path("includeSnippet").asBoolean(true); // 读取 boolean。
    }

    private boolean looksLikeStaticUtility(String receiver) { // 判断接收者是否像静态工具类。
        return receiver != null && !receiver.isBlank() && Character.isUpperCase(receiver.charAt(0)); // 大写开头按工具/静态类处理。
    }

    private String normalizeFailureMessage(String message) { // 归一化底层安全异常文案。
        if (message == null || message.isBlank()) { // 空文案。
            return "Tool → Service 调用链分析失败。"; // 返回兜底。
        }
        if (message.contains("workspace 外")) { // 路径越界。
            return "不允许访问 workspace 外的路径。"; // 不泄露真实路径。
        }
        return message; // 其它文案原样返回。
    }

    private String firstNonBlank(String first,
                                 String second) { // 返回第一个非空字符串。
        String normalizedFirst = trimToNull(first); // 归一化第一个值。
        if (normalizedFirst != null) { // 第一个有效。
            return normalizedFirst; // 返回第一个。
        }
        String normalizedSecond = trimToNull(second); // 归一化第二个值。
        return normalizedSecond == null ? "" : normalizedSecond; // 返回第二个或空串。
    }

    private String trimToNull(String value) { // 空白字符串转 null。
        return value == null || value.trim().isEmpty() ? null : value.trim(); // 统一空值处理。
    }

    private String safeText(String value) { // 文本兜底。
        return value == null ? "" : value.trim(); // null 转空串并去空白。
    }

    private String safeRelativePath(String path) { // 日志中的路径兜底。
        return path == null ? "" : path.replace('\\', '/'); // 统一斜杠。
    }

    private static final class ChainState { // 调用项计数状态。
        private final int maxItems; // 最大调用项数量。
        private final boolean includeSnippet; // 是否输出调用表达式片段。
        private int count; // 已写入调用项数量。
        private boolean truncated; // 是否截断。

        private ChainState(int maxItems,
                           boolean includeSnippet) { // 构造计数状态。
            this.maxItems = maxItems; // 保存上限。
            this.includeSnippet = includeSnippet; // 保存片段开关。
        }

        private boolean reachedLimit() { // 判断是否达到调用项上限。
            return count >= maxItems; // 达到上限时返回 true。
        }
    }

    private static final class DependencyInfo { // Tool 或 ServiceImpl 依赖对象信息。
        private final String fieldName; // 字段名。
        private final String type; // 简单类型名。
        private final String kind; // 依赖分类。

        private DependencyInfo(String fieldName,
                               String type,
                               String kind) { // 构造依赖信息。
            this.fieldName = fieldName; // 保存字段名。
            this.type = type; // 保存类型。
            this.kind = kind; // 保存分类。
        }
    }

    private static final class ServiceCallCandidate { // Tool 内部 Service 调用候选。
        private final String serviceType; // Service 类型名。
        private final String methodName; // 被调 Service 方法名。
        private final List<String> candidateTargets; // Service/ServiceImpl 候选文件。

        private ServiceCallCandidate(String serviceType,
                                     String methodName,
                                     List<String> candidateTargets) { // 构造 Service 调用候选。
            this.serviceType = serviceType; // 保存 Service 类型。
            this.methodName = methodName; // 保存方法名。
            this.candidateTargets = candidateTargets == null ? List.of() : candidateTargets; // 保存候选文件。
        }
    }

    private static final class ServiceImplContext { // ServiceImpl 一层分析上下文。
        private final String relativePath; // ServiceImpl 相对路径。
        private final String className; // 实现类名。
        private final List<String> codeLines; // 去注释后的源码行。
        private final Map<String, JavaMethod> methodsByName; // 方法索引。
        private final Map<String, DependencyInfo> dependencyByField; // 依赖索引。

        private ServiceImplContext(String relativePath,
                                   String className,
                                   List<String> codeLines,
                                   Map<String, JavaMethod> methodsByName,
                                   Map<String, DependencyInfo> dependencyByField) { // 构造 ServiceImpl 上下文。
            this.relativePath = relativePath; // 保存相对路径。
            this.className = className == null ? "" : className; // 保存类名。
            this.codeLines = codeLines == null ? List.of() : codeLines; // 保存源码行。
            this.methodsByName = methodsByName == null ? Map.of() : methodsByName; // 保存方法索引。
            this.dependencyByField = dependencyByField == null ? Map.of() : dependencyByField; // 保存依赖索引。
        }
    }

    private static final class ToolFileMatch { // toolName 扫描命中的 Tool 文件。
        private final String toolName; // 真实 toolName。
        private final String className; // Tool 类名。
        private final Path filePath; // 真实文件路径。
        private final String relativePath; // workspace 相对路径。

        private ToolFileMatch(String toolName,
                              String className,
                              Path filePath,
                              String relativePath) { // 构造 Tool 匹配项。
            this.toolName = toolName; // 保存工具名。
            this.className = className; // 保存类名。
            this.filePath = filePath; // 保存真实路径。
            this.relativePath = relativePath; // 保存相对路径。
        }
    }

    private static final class ToolFileResolution { // Tool 文件定位结果。
        private final Path filePath; // 定位成功时的文件路径。
        private final ObjectNode failureResult; // 失败时的结构化结果。

        private ToolFileResolution(Path filePath,
                                   ObjectNode failureResult) { // 构造定位结果。
            this.filePath = filePath; // 保存文件路径。
            this.failureResult = failureResult; // 保存失败结果。
        }

        private static ToolFileResolution success(Path filePath) { // 构造成功定位。
            return new ToolFileResolution(filePath, null); // 返回成功。
        }

        private static ToolFileResolution failure(ObjectNode failureResult) { // 构造失败定位。
            return new ToolFileResolution(null, failureResult); // 返回失败。
        }

        private boolean isSuccess() { // 是否定位成功。
            return filePath != null; // 有文件路径即成功。
        }

        private Path getFilePath() { // 获取文件路径。
            return filePath; // 返回路径。
        }

        private ObjectNode getFailureResult() { // 获取失败结果。
            return failureResult; // 返回失败 JSON。
        }
    }
}
