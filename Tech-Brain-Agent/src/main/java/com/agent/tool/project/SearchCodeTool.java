package com.agent.tool.project;

import com.agent.config.ProjectWorkspaceProperties;
import com.agent.security.ProjectPathGuard;
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
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * searchCode 项目代码位置搜索工具。
 *
 * <p>适用场景：当用户在聊天中询问某个类、方法、接口路径、注解或关键词位于项目哪里时，
 * Tool Calling 可以调用本工具在 techbrain.project.workspace-dir 工作区内搜索安全代码/文本文件，
 * 返回相对路径、行号、命中行和少量上下文片段。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 识别模型 tool_call 或后端强制 searchCode 路由
 * -> ToolRegistry 根据工具名获取 SearchCodeTool
 * -> execute(arguments) 解析 query/searchType/path/maxResults/contextLines/caseSensitive
 * -> ProjectPathGuard 校验 workspace 边界、敏感目录、敏感文件和扩展名
 * -> 按 UTF-8 搜索允许范围内的项目代码文件
 * -> 返回 code_search JSON 给模型和 tool_call_log。</p>
 *
 * <p>边界说明：本工具属于 Tech-Brain-Agent 项目代码业务工具，不放入 Tech-Brain-Tool 公共模块。
 * 本工具不修改项目文件，不生成 patch，不应用 patch，不做 AST 解析，不读取或返回完整代码文件，
 * 不接入 RAG/Milvus/向量化，不返回服务器绝对路径。</p>
 */
@Slf4j // 输出 [SearchCodeTool] 前缀日志，不打印服务器绝对路径和完整文件内容。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动发现 searchCode 工具。
public class SearchCodeTool extends AbstractAiTool { // 项目代码搜索业务工具，继承公共工具基类复用参数 Schema 和 JSON 辅助能力。
    private static final String TOOL_NAME = "searchCode"; // 工具名称必须和模型 tool_call.function.name 一致。
    private static final String RESULT_TYPE = "code_search"; // 工具返回 JSON 类型。
    private static final String SEARCH_TYPE_AUTO = "AUTO"; // 自动判断搜索类型。
    private static final String SEARCH_TYPE_CLASS = "CLASS"; // 类名搜索类型。
    private static final String SEARCH_TYPE_METHOD = "METHOD"; // 方法名搜索类型。
    private static final String SEARCH_TYPE_KEYWORD = "KEYWORD"; // 关键词搜索类型。
    private static final String MATCH_TYPE_FILE_NAME = "FILE_NAME"; // 文件名命中类型。
    private static final int DEFAULT_MAX_RESULTS = 20; // 默认最大返回结果数。
    private static final int MAX_ALLOWED_RESULTS = 100; // 最大允许返回结果数。
    private static final int DEFAULT_CONTEXT_LINES = 3; // 默认上下文行数。
    private static final int MAX_ALLOWED_CONTEXT_LINES = 10; // 最大上下文行数。
    private static final int MAX_SCAN_FILES = 5000; // 单次最多扫描文件数，避免大项目拖垮请求。
    private static final int MAX_MATCHES_PER_FILE = 5; // 单文件最多返回命中数，避免一个文件刷屏。
    private static final int MAX_SNIPPET_CHARS = 4000; // 单条 snippet 最长字符数。
    private static final long DEFAULT_MAX_FILE_SIZE_KB = 512L; // 配置缺失时的单文件大小上限。
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[A-Z][A-Za-z0-9_]*$"); // 简单类名识别。

    private final ProjectPathGuard projectPathGuard; // P4.1 workspace 路径安全守卫。
    private final ProjectWorkspaceProperties projectWorkspaceProperties; // P4.1 项目工作区配置。

    public SearchCodeTool(ProjectPathGuard projectPathGuard,
                          ProjectWorkspaceProperties projectWorkspaceProperties) { // 构造器注入依赖，保持和现有 Tool 风格一致。
        this.projectPathGuard = projectPathGuard; // 保存路径安全守卫。
        this.projectWorkspaceProperties = projectWorkspaceProperties; // 保存项目工作区配置。
    }

    @Override // 实现 AiTool 工具名。
    public String name() {
        return TOOL_NAME; // 固定返回 searchCode。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return "在项目 workspace 内搜索代码位置。支持按类名、方法名、关键词、注解、接口路径等搜索，返回相对路径、行号和上下文代码片段。只搜索安全的代码/文本文件，不读取敏感文件，不访问 workspace 外路径。"; // 给模型判断调用时机。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "query", createStringProperty("搜索关键词，例如 AgentController、saveNote、ragSearch、@PostMapping、/api/articles"), true); // query 必填。
        addProperty(schema, "searchType", createStringProperty("搜索类型，可选：AUTO、CLASS、METHOD、KEYWORD。默认 AUTO"), false); // searchType 可选。
        addProperty(schema, "path", createStringProperty("相对于 workspace 的搜索目录，可选。为空表示搜索整个 workspace，例如 Tech-Brain-Agent 或 Tech-Brain-Notes/src/main/java"), false); // path 可选且必须是相对路径。
        addProperty(schema, "maxResults", createIntegerProperty("最多返回结果数，可选，默认 20，最大 100"), false); // maxResults 可选。
        addProperty(schema, "contextLines", createIntegerProperty("每个命中结果前后返回的上下文行数，可选，默认 3，最大 10"), false); // contextLines 可选。
        ObjectNode caseSensitiveProperty = objectMapper.createObjectNode(); // bool 字段没有公共 helper，直接构造。
        caseSensitiveProperty.put("type", "boolean"); // 标记字段类型为 boolean。
        caseSensitiveProperty.put("description", "是否大小写敏感，可选，默认 false"); // 写入字段说明。
        addProperty(schema, "caseSensitive", caseSensitiveProperty, false); // caseSensitive 可选。
        return schema; // 返回完整参数 Schema。
    }

    @Override // 执行 searchCode 工具。
    public String execute(JsonNode arguments) {
        String query = trimToNull(getOptionalText(arguments, "query", null)); // 读取搜索关键词。
        if (query == null) { // query 缺失时直接失败。
            return buildFailureResult("", "搜索关键词不能为空。"); // 返回结构化失败 JSON。
        }

        String requestedPath = getOptionalText(arguments, "path", ""); // 读取搜索范围相对路径。
        String requestedSearchType = getOptionalText(arguments, "searchType", SEARCH_TYPE_AUTO); // 读取搜索类型。
        int maxResults = resolveMaxResults(arguments); // 解析最大结果数。
        int contextLines = resolveContextLines(arguments); // 解析上下文行数。
        boolean caseSensitive = resolveCaseSensitive(arguments); // 解析大小写敏感配置。
        SearchOptions options = new SearchOptions(query, resolveSearchType(query, requestedSearchType),
                requestedPath, maxResults, contextLines, caseSensitive); // 汇总搜索参数。
        log.info("[SearchCodeTool] search code, query: {}, searchType: {}, path: {}",
                previewQuery(query), options.searchType(), safeRelativePath(requestedPath)); // 只打印查询词和相对路径。

        try {
            Path targetPath = resolveTargetPath(requestedPath); // 解析并校验搜索目标在 workspace 内。
            validateSearchTarget(targetPath); // 校验目标存在且是目录或普通文件。
            SearchState state = new SearchState(maxResults); // 初始化扫描状态。
            if (Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) { // path 指向单个文件时只搜索该文件。
                searchFileIfAllowed(targetPath, options, state); // 搜索单文件。
            } else {
                scanDirectory(targetPath, options, state); // 递归扫描目录。
            }
            return buildSuccessResult(options, state); // 返回结构化成功 JSON。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[SearchCodeTool] search failed, query: {}, path: {}, reason: {}",
                    previewQuery(query), safeRelativePath(requestedPath), e.getMessage()); // 受控路径错误不打印堆栈。
            return buildFailureResult(query, normalizeFailureMessage(e.getMessage())); // 返回路径安全错误。
        } catch (Exception e) {
            log.error("[SearchCodeTool] search failed, query: {}, path: {}, reason: {}",
                    previewQuery(query), safeRelativePath(requestedPath), "代码搜索失败", e); // 系统错误记录堆栈但不打印绝对路径。
            return buildFailureResult(query, "代码搜索失败，请稍后重试。"); // 返回友好失败 JSON。
        }
    }

    private Path resolveTargetPath(String requestedPath) { // 解析工具入参 path。
        String normalizedPath = trimToNull(requestedPath); // 空字符串视为 workspace 根目录。
        if (normalizedPath == null) { // 未指定 path 时搜索 workspace 根目录。
            return projectPathGuard.getWorkspaceRoot(); // 返回 workspace 根路径。
        }
        return projectPathGuard.resolveProjectPath(normalizedPath); // 非空 path 必须走 P4.1 安全解析。
    }

    private void validateSearchTarget(Path targetPath) { // 校验搜索目标目录或文件。
        if (targetPath == null) { // 理论上不会为空，兜底保护。
            throw new ProjectPathGuard.ProjectPathAccessException("路径不能为空。"); // 返回路径为空错误。
        }
        Path normalizedPath = targetPath.toAbsolutePath().normalize(); // 归一化目标路径。
        if (!projectPathGuard.isInsideWorkspace(normalizedPath)) { // 必须位于 workspace 内。
            throw new ProjectPathGuard.ProjectPathAccessException("不允许访问 workspace 外的路径。"); // 防止路径穿越。
        }
        if (projectPathGuard.isSensitivePath(normalizedPath)) { // 目标本身不能是敏感路径。
            throw new ProjectPathGuard.ProjectPathAccessException("路径属于敏感目录或敏感文件，禁止搜索。"); // 敏感路径直接拒绝。
        }
        if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) { // 目标必须存在。
            throw new ProjectPathGuard.ProjectPathAccessException("路径不存在。"); // 不暴露真实路径。
        }
        if (Files.isSymbolicLink(normalizedPath)) { // 符号链接可能绕过 workspace。
            throw new ProjectPathGuard.ProjectPathAccessException("目标不是普通目录或文件。"); // 拒绝符号链接。
        }
        if (!Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) { // 只支持目录或普通文件。
            throw new ProjectPathGuard.ProjectPathAccessException("目标不是普通目录或文件。"); // 拒绝特殊文件。
        }
    }

    private void scanDirectory(Path directoryPath,
                               SearchOptions options,
                               SearchState state) { // 递归扫描安全目录。
        if (state.shouldStop()) { // 达到结果数或扫描文件数上限时停止。
            return; // 不再继续扫描。
        }
        List<Path> children = listSafeChildren(directoryPath); // 获取安全且排序后的子路径。
        for (Path child : children) { // 遍历子目录和文件。
            if (state.shouldStop()) { // 扫描过程中再次检查停止条件。
                break; // 停止当前目录遍历。
            }
            if (isSafeSearchDirectory(child)) { // 安全目录才进入递归。
                scanDirectory(child, options, state); // 递归搜索子目录。
                continue; // 处理下一个子路径。
            }
            searchFileIfAllowed(child, options, state); // 普通文件按白名单规则搜索。
        }
    }

    private List<Path> listSafeChildren(Path directoryPath) { // 列出目录下可继续处理的安全子路径。
        try (Stream<Path> stream = Files.list(directoryPath)) { // 只读取目录项，不读取文件内容。
            return stream
                    .filter(this::isSafeSearchEntry) // 复用 ProjectPathGuard 过滤敏感目录和文件。
                    .sorted(pathComparator()) // 目录在前、文件在后、名称排序。
                    .toList(); // Java 17 返回不可变列表。
        } catch (IOException e) {
            log.warn("[SearchCodeTool] list directory failed, relativePath: {}, reason: {}",
                    toWorkspaceRelativePath(directoryPath), e.getMessage()); // 只打印相对路径。
            return List.of(); // 子目录读取失败时跳过。
        }
    }

    private boolean isSafeSearchEntry(Path path) { // 判断目录项是否允许参与搜索流程。
        if (path == null || !projectPathGuard.isInsideWorkspace(path)) { // 路径必须位于 workspace 内。
            return false; // 不处理。
        }
        if (Files.isSymbolicLink(path) || projectPathGuard.isSensitivePath(path)) { // 符号链接和敏感路径直接过滤。
            return false; // 不处理。
        }
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); // 只处理目录和普通文件。
    }

    private boolean isSafeSearchDirectory(Path path) { // 判断是否允许进入递归目录。
        return path != null
                && projectPathGuard.isInsideWorkspace(path)
                && !Files.isSymbolicLink(path)
                && !projectPathGuard.isSensitivePath(path)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS); // 必须是 workspace 内非敏感普通目录。
    }

    private void searchFileIfAllowed(Path filePath,
                                     SearchOptions options,
                                     SearchState state) { // 对单个安全文件执行搜索。
        if (state.shouldStop()) { // 已达到停止条件时不继续。
            return; // 直接返回。
        }
        if (!isSearchableFile(filePath, state)) { // 文件不满足安全白名单时跳过。
            return; // 不搜索该文件。
        }
        state.incrementScannedFiles(); // 记录实际参与内容扫描的文件数。
        List<String> lines = readFileLines(filePath, state); // 按 UTF-8 读取小文件行内容。
        if (lines.isEmpty() && state.shouldStop()) { // 读取阶段可能因扫描上限停止。
            return; // 停止处理。
        }
        searchLines(filePath, lines, options, state); // 搜索文件内匹配行。
    }

    private boolean isSearchableFile(Path path, SearchState state) { // 判断普通文件是否允许搜索。
        if (path == null || !projectPathGuard.isInsideWorkspace(path)) { // 文件必须位于 workspace 内。
            return false; // 不搜索。
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { // 只搜索普通文件。
            return false; // 不搜索。
        }
        if (projectPathGuard.isSensitivePath(path)) { // 复用敏感目录、文件名和扩展名判断。
            state.incrementSkippedFiles(); // 记录跳过文件。
            return false; // 不搜索。
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString(); // 获取文件名。
        String extension = projectPathGuard.getExtension(fileName); // 获取小写扩展名。
        if (projectPathGuard.isBlockedFilename(fileName)
                || projectPathGuard.isBlockedExtension(extension)
                || !projectPathGuard.isAllowedExtension(extension)) { // 黑名单优先，且必须在白名单内。
            state.incrementSkippedFiles(); // 记录跳过文件。
            return false; // 不搜索。
        }
        if (isFileTooLarge(path)) { // 超过单文件大小限制。
            state.incrementSkippedFiles(); // 记录跳过文件。
            state.incrementSkippedLargeFiles(); // 单独记录大文件数量。
            return false; // 不搜索大文件。
        }
        return true; // 允许搜索。
    }

    private List<String> readFileLines(Path filePath, SearchState state) { // 读取已通过大小限制的小文件行内容。
        try {
            return Files.readAllLines(filePath, StandardCharsets.UTF_8); // 文件大小已限制，不返回完整内容，只用于搜索匹配。
        } catch (MalformedInputException e) {
            state.incrementSkippedFiles(); // 编码不支持时跳过。
            log.warn("[SearchCodeTool] skip non utf8 file, relativePath: {}", toWorkspaceRelativePath(filePath)); // 不打印绝对路径。
            return List.of(); // 返回空列表。
        } catch (IOException e) {
            state.incrementSkippedFiles(); // IO 失败时跳过。
            log.warn("[SearchCodeTool] read file failed, relativePath: {}, reason: {}",
                    toWorkspaceRelativePath(filePath), e.getMessage()); // 只打印相对路径。
            return List.of(); // 返回空列表。
        }
    }

    private void searchLines(Path filePath,
                             List<String> lines,
                             SearchOptions options,
                             SearchState state) { // 在单文件内逐行匹配。
        if (lines == null || lines.isEmpty()) { // 空文件没有结果。
            maybeAddFileNameMatch(filePath, lines, options, state); // 文件名命中仍可返回定位结果。
            return; // 结束。
        }
        int fileMatchCount = 0; // 单文件命中数量。
        String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 获取文件名。
        for (int i = 0; i < lines.size(); i++) { // 逐行搜索。
            if (state.shouldStop() || fileMatchCount >= MAX_MATCHES_PER_FILE) { // 达到全局或单文件上限。
                break; // 停止当前文件搜索。
            }
            MatchDecision decision = matchLine(fileName, lines.get(i), options); // 判断当前行是否命中。
            if (!decision.matched()) { // 未命中。
                continue; // 继续下一行。
            }
            state.addResult(buildResult(filePath, fileName, lines, i, decision, options)); // 写入搜索结果。
            fileMatchCount++; // 记录单文件命中。
        }
        if (fileMatchCount == 0) { // 内容未命中时检查文件名命中。
            maybeAddFileNameMatch(filePath, lines, options, state); // 文件名命中也可作为定位结果。
        }
    }

    private void maybeAddFileNameMatch(Path filePath,
                                       List<String> lines,
                                       SearchOptions options,
                                       SearchState state) { // 文件名命中兜底。
        if (state.shouldStop()) { // 已达到停止条件。
            return; // 直接返回。
        }
        String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 获取文件名。
        String normalizedFileName = normalizeForMatch(fileName, options.caseSensitive()); // 标准化文件名。
        String normalizedQuery = normalizeForMatch(cleanMethodQuery(options.query()), options.caseSensitive()); // 标准化 query。
        if (normalizedQuery.isBlank() || !normalizedFileName.contains(normalizedQuery)) { // 文件名未命中。
            return; // 不添加结果。
        }
        MatchDecision decision = new MatchDecision(true, MATCH_TYPE_FILE_NAME, scoreForFileName(fileName, options.query()), "文件名匹配"); // 构造文件名命中。
        state.addResult(buildResult(filePath, fileName, lines == null ? List.of() : lines, 0, decision, options)); // 返回第一行附近片段。
    }

    private MatchDecision matchLine(String fileName,
                                    String line,
                                    SearchOptions options) { // 判断单行是否匹配搜索条件。
        String searchType = options.searchType(); // 读取搜索类型。
        String query = cleanMethodQuery(options.query()); // 去掉方法名后的括号。
        String normalizedLine = normalizeForMatch(line, options.caseSensitive()); // 标准化当前行。
        String normalizedQuery = normalizeForMatch(query, options.caseSensitive()); // 标准化 query。
        if (normalizedQuery.isBlank()) { // 空 query 不匹配。
            return MatchDecision.notMatched(); // 返回未命中。
        }
        if (SEARCH_TYPE_CLASS.equals(searchType)) { // 类名搜索。
            return matchClassLine(fileName, line, normalizedLine, normalizedQuery, options); // 按类声明规则匹配。
        }
        if (SEARCH_TYPE_METHOD.equals(searchType)) { // 方法名搜索。
            return matchMethodLine(fileName, line, normalizedLine, normalizedQuery, options); // 按方法声明规则匹配。
        }
        return matchKeywordLine(fileName, line, normalizedLine, normalizedQuery, options); // 默认关键词搜索。
    }

    private MatchDecision matchClassLine(String fileName,
                                         String line,
                                         String normalizedLine,
                                         String normalizedQuery,
                                         SearchOptions options) { // 类名/接口名/枚举名匹配。
        boolean declarationMatched = containsAny(normalizedLine,
                "class " + normalizedQuery,
                "interface " + normalizedQuery,
                "enum " + normalizedQuery,
                "record " + normalizedQuery,
                "object " + normalizedQuery,
                "function " + normalizedQuery,
                "const " + normalizedQuery,
                "type " + normalizedQuery); // 简单声明关键词匹配。
        if (declarationMatched) { // 命中声明。
            return new MatchDecision(true, SEARCH_TYPE_CLASS, 80 + scoreForFileName(fileName, options.query()), line.trim()); // 类声明高分。
        }
        if (normalizeForMatch(fileName, options.caseSensitive()).contains(normalizedQuery)
                && normalizedLine.contains(normalizedQuery)) { // 文件名和行内容同时命中。
            return new MatchDecision(true, SEARCH_TYPE_CLASS, 60 + scoreForFileName(fileName, options.query()), line.trim()); // 次级类名匹配。
        }
        return MatchDecision.notMatched(); // 未命中。
    }

    private MatchDecision matchMethodLine(String fileName,
                                          String line,
                                          String normalizedLine,
                                          String normalizedQuery,
                                          SearchOptions options) { // 方法名/函数名匹配。
        boolean methodMatched = normalizedLine.contains("def " + normalizedQuery + "(")
                || normalizedLine.contains("function " + normalizedQuery + "(")
                || normalizedLine.contains("func " + normalizedQuery + "(")
                || normalizedLine.contains("fn " + normalizedQuery + "(")
                || normalizedLine.contains(normalizedQuery + "(")
                || normalizedLine.contains("const " + normalizedQuery + " =")
                || normalizedLine.contains("let " + normalizedQuery + " =")
                || normalizedLine.contains("var " + normalizedQuery + " ="); // 第一版使用关键词和括号判断方法。
        if (!methodMatched) { // 未命中方法模式。
            return MatchDecision.notMatched(); // 返回未命中。
        }
        return new MatchDecision(true, SEARCH_TYPE_METHOD, 70 + scoreForFileName(fileName, options.query()), line.trim()); // 方法声明高分。
    }

    private MatchDecision matchKeywordLine(String fileName,
                                           String line,
                                           String normalizedLine,
                                           String normalizedQuery,
                                           SearchOptions options) { // 普通关键词匹配。
        if (!normalizedLine.contains(normalizedQuery)) { // 行内容不包含 query。
            return MatchDecision.notMatched(); // 未命中。
        }
        int score = 30 + scoreForFileName(fileName, options.query()); // 普通关键词基础分。
        return new MatchDecision(true, SEARCH_TYPE_KEYWORD, score, line.trim()); // 返回关键词命中。
    }

    private SearchResult buildResult(Path filePath,
                                     String fileName,
                                     List<String> lines,
                                     int lineIndex,
                                     MatchDecision decision,
                                     SearchOptions options) { // 构造单条搜索结果。
        SearchResult result = new SearchResult(); // 创建结果对象。
        result.filePath = toWorkspaceRelativePath(filePath); // 返回相对 workspace 路径。
        result.fileName = fileName; // 返回文件名。
        result.extension = projectPathGuard.getExtension(fileName); // 返回扩展名。
        result.language = resolveLanguage(result.extension); // 返回语言名。
        result.lineNumber = Math.max(1, lineIndex + 1); // 行号从 1 开始。
        result.matchType = decision.matchType(); // 返回匹配类型。
        result.matchedLine = safeMatchedLine(decision.matchedLine()); // 返回命中行。
        result.snippet = buildSnippet(lines, lineIndex, result.lineNumber, options.contextLines()); // 返回上下文片段。
        result.score = decision.score(); // 内部排序分数。
        return result; // 返回结果。
    }

    private String buildSnippet(List<String> lines,
                                int lineIndex,
                                int lineNumber,
                                int contextLines) { // 构造命中行上下文片段。
        if (lines == null || lines.isEmpty()) { // 空文件或无法读取上下文。
            return lineNumber + " | "; // 返回稳定格式。
        }
        int safeLineIndex = Math.max(0, Math.min(lineIndex, lines.size() - 1)); // 防止越界。
        int start = Math.max(0, safeLineIndex - contextLines); // 使用请求指定的上文行数。
        int end = Math.min(lines.size() - 1, safeLineIndex + contextLines); // 使用请求指定的下文行数。
        StringBuilder builder = new StringBuilder(); // 拼接上下文。
        for (int i = start; i <= end; i++) { // 遍历上下文行。
            if (!builder.isEmpty()) { // 非首行时加换行。
                builder.append('\n'); // 保持多行 snippet。
            }
            builder.append(i + 1).append(" | ").append(lines.get(i)); // 输出行号和源码行。
            if (builder.length() > MAX_SNIPPET_CHARS) { // snippet 超过限制。
                builder.setLength(MAX_SNIPPET_CHARS); // 截断到最大长度。
                builder.append("...[snippet truncated]"); // 标记片段截断。
                break; // 停止继续拼接。
            }
        }
        return builder.toString(); // 返回 snippet。
    }

    private String buildSuccessResult(SearchOptions options,
                                      SearchState state) { // 构造成功 JSON。
        state.sortAndLimit(); // 按分数、路径、行号排序并限制数量。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", true); // 标记成功。
        result.put("query", options.query()); // 写入 query。
        result.put("searchType", options.searchType()); // 写入搜索类型。
        result.put("path", safeRelativePath(options.path())); // 写入相对搜索路径。
        result.put("maxResults", options.maxResults()); // 写入最大结果数。
        result.put("contextLines", options.contextLines()); // 写入上下文行数。
        result.put("resultCount", state.results.size()); // 写入结果数量。
        result.put("scannedFiles", state.scannedFiles); // 写入扫描文件数。
        result.put("skippedFiles", state.skippedFiles); // 写入跳过文件数。
        result.put("skippedLargeFiles", state.skippedLargeFiles); // 写入跳过大文件数。
        result.put("stoppedEarly", state.stoppedEarly); // 写入是否提前停止。
        if (state.results.isEmpty()) { // 没有命中。
            result.put("message", "未在项目代码中找到匹配结果。"); // 返回友好提示。
        } else if (state.stoppedEarly) { // 提前停止。
            result.put("message", "搜索结果较多，已按限制返回前 " + options.maxResults() + " 条结果。"); // 返回截断提示。
        }
        ArrayNode results = objectMapper.createArrayNode(); // 创建结果数组。
        for (SearchResult searchResult : state.results) { // 遍历命中结果。
            results.add(toJsonNode(searchResult)); // 转为 JSON。
        }
        result.set("results", results); // 写入结果数组。
        return result.toString(); // 返回 JSON 字符串。
    }

    private String buildFailureResult(String query, String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", false); // 标记失败。
        result.put("query", query == null ? "" : query); // 写入 query。
        result.put("message", message); // 写入错误原因。
        return result.toString(); // 返回 JSON 字符串。
    }

    private ObjectNode toJsonNode(SearchResult searchResult) { // 转换搜索结果为 JSON。
        ObjectNode node = objectMapper.createObjectNode(); // 创建 JSON 对象。
        node.put("filePath", searchResult.filePath); // 写入相对路径。
        node.put("fileName", searchResult.fileName); // 写入文件名。
        node.put("extension", searchResult.extension); // 写入扩展名。
        node.put("language", searchResult.language); // 写入语言。
        node.put("lineNumber", searchResult.lineNumber); // 写入行号。
        node.put("matchType", searchResult.matchType); // 写入命中类型。
        node.put("matchedLine", searchResult.matchedLine); // 写入命中行。
        node.put("snippet", searchResult.snippet); // 写入上下文片段。
        return node; // 返回 JSON 对象。
    }

    private int resolveMaxResults(JsonNode arguments) { // 解析 maxResults。
        int maxResults = getOptionalInt(arguments, "maxResults", DEFAULT_MAX_RESULTS); // 默认 20。
        if (maxResults <= 0) { // 非法值使用默认。
            return DEFAULT_MAX_RESULTS; // 返回默认值。
        }
        return Math.min(maxResults, MAX_ALLOWED_RESULTS); // 最大限制 100。
    }

    private int resolveContextLines(JsonNode arguments) { // 解析 contextLines。
        int contextLines = getOptionalInt(arguments, "contextLines", DEFAULT_CONTEXT_LINES); // 默认 3。
        if (contextLines < 0) { // 小于 0 使用默认。
            return DEFAULT_CONTEXT_LINES; // 返回默认值。
        }
        return Math.min(contextLines, MAX_ALLOWED_CONTEXT_LINES); // 最大限制 10。
    }

    private boolean resolveCaseSensitive(JsonNode arguments) { // 解析大小写敏感参数。
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // 参数为空。
            return false; // 默认不区分大小写。
        }
        JsonNode valueNode = arguments.path("caseSensitive"); // 读取字段。
        return !valueNode.isMissingNode() && !valueNode.isNull() && valueNode.asBoolean(false); // 默认 false。
    }

    private String resolveSearchType(String query, String requestedSearchType) { // 解析搜索类型。
        String normalizedType = requestedSearchType == null ? SEARCH_TYPE_AUTO : requestedSearchType.trim().toUpperCase(Locale.ROOT); // 统一大写。
        if (SEARCH_TYPE_CLASS.equals(normalizedType)
                || SEARCH_TYPE_METHOD.equals(normalizedType)
                || SEARCH_TYPE_KEYWORD.equals(normalizedType)) { // 用户显式指定有效类型。
            return normalizedType; // 直接使用。
        }
        return inferSearchType(query); // AUTO 或非法值时自动判断。
    }

    private String inferSearchType(String query) { // 自动判断搜索类型。
        String normalizedQuery = trimToNull(query); // 标准化 query。
        if (normalizedQuery == null) { // query 为空。
            return SEARCH_TYPE_KEYWORD; // 兜底关键词。
        }
        if (normalizedQuery.endsWith("()")) { // saveNote() 这类表达。
            return SEARCH_TYPE_METHOD; // 视为方法搜索。
        }
        if (CLASS_NAME_PATTERN.matcher(normalizedQuery).matches()) { // 首字母大写且像类名。
            return SEARCH_TYPE_CLASS; // 视为类搜索。
        }
        return SEARCH_TYPE_KEYWORD; // 其它默认关键词搜索。
    }

    private boolean isFileTooLarge(Path path) { // 判断文件大小是否超过项目读取限制。
        try {
            return Files.size(path) > resolveMaxFileSizeBytes(); // 只读取元信息。
        } catch (IOException e) {
            return true; // 文件大小读取失败时保守跳过。
        }
    }

    private long resolveMaxFileSizeBytes() { // 解析单文件大小上限。
        Integer maxFileSizeKb = projectWorkspaceProperties == null ? null : projectWorkspaceProperties.getMaxFileSizeKb(); // 读取配置。
        long safeMaxFileSizeKb = maxFileSizeKb == null || maxFileSizeKb <= 0 ? DEFAULT_MAX_FILE_SIZE_KB : maxFileSizeKb; // 配置缺失时兜底。
        return safeMaxFileSizeKb * 1024L; // KB 转字节。
    }

    private Comparator<Path> pathComparator() { // 构造扫描排序规则。
        return (left, right) -> {
            boolean leftDirectory = Files.isDirectory(left, LinkOption.NOFOLLOW_LINKS); // 判断左侧是否目录。
            boolean rightDirectory = Files.isDirectory(right, LinkOption.NOFOLLOW_LINKS); // 判断右侧是否目录。
            if (leftDirectory != rightDirectory) { // 目录和文件混排时目录优先。
                return leftDirectory ? -1 : 1; // 目录在前。
            }
            String leftName = left.getFileName() == null ? "" : left.getFileName().toString().toLowerCase(Locale.ROOT); // 左侧名称小写。
            String rightName = right.getFileName() == null ? "" : right.getFileName().toString().toLowerCase(Locale.ROOT); // 右侧名称小写。
            return leftName.compareTo(rightName); // 同类型按名称排序。
        };
    }

    private int scoreForFileName(String fileName, String query) { // 计算文件名加分。
        String normalizedFileName = normalizeForMatch(fileName, false); // 文件名不区分大小写。
        String normalizedQuery = normalizeForMatch(cleanMethodQuery(query), false); // query 不区分大小写。
        if (normalizedQuery.isBlank()) { // 空 query 不加分。
            return 0; // 返回 0。
        }
        String baseName = normalizedFileName.contains(".")
                ? normalizedFileName.substring(0, normalizedFileName.lastIndexOf('.'))
                : normalizedFileName; // 获取不含扩展名的文件名。
        if (baseName.equals(normalizedQuery)) { // 文件名完全匹配。
            return 120; // 最高加分。
        }
        return normalizedFileName.contains(normalizedQuery) ? 100 : 0; // 文件名包含 query 次高加分。
    }

    private String cleanMethodQuery(String query) { // 清理方法 query。
        String normalizedQuery = trimToNull(query); // 去空白。
        if (normalizedQuery == null) { // 为空。
            return ""; // 返回空。
        }
        return normalizedQuery.endsWith("()")
                ? normalizedQuery.substring(0, normalizedQuery.length() - 2)
                : normalizedQuery; // 去掉末尾括号。
    }

    private boolean containsAny(String text, String... keywords) { // 判断文本是否包含任一关键词。
        if (text == null || text.isBlank() || keywords == null) { // 空文本不匹配。
            return false; // 返回 false。
        }
        for (String keyword : keywords) { // 遍历关键词。
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) { // 命中。
                return true; // 返回 true。
            }
        }
        return false; // 未命中。
    }

    private String normalizeForMatch(String text, boolean caseSensitive) { // 标准化匹配文本。
        if (text == null) { // 空文本。
            return ""; // 返回空。
        }
        return caseSensitive ? text : text.toLowerCase(Locale.ROOT); // 默认不区分大小写。
    }

    private String resolveLanguage(String extension) { // 根据扩展名推断语言。
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT); // 标准化扩展名。
        return switch (ext) {
            case "java" -> "Java";
            case "kt", "kts" -> "Kotlin";
            case "groovy" -> "Groovy";
            case "scala" -> "Scala";
            case "py" -> "Python";
            case "js", "jsx" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "vue" -> "Vue";
            case "svelte" -> "Svelte";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "c", "h", "cpp", "cc", "cxx", "hpp", "hxx" -> "C/C++";
            case "cs", "csproj", "sln" -> ".NET";
            case "php" -> "PHP";
            case "rb" -> "Ruby";
            case "swift" -> "Swift";
            case "m", "mm" -> "Objective-C";
            case "sql" -> "SQL";
            case "sh", "bash", "zsh", "ps1", "bat", "cmd" -> "Shell";
            case "html", "css", "scss", "less", "xml" -> "Web";
            case "json", "yaml", "yml", "properties", "toml", "ini", "env.example" -> "Config";
            case "md", "txt" -> "Text";
            default -> "Code";
        }; // 返回语言分类。
    }

    private String toWorkspaceRelativePath(Path path) { // 将绝对路径转换为相对 workspace 路径。
        if (path == null) { // 空路径兜底。
            return ""; // 返回空。
        }
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // 获取 workspace 根目录。
        Path normalizedPath = path.toAbsolutePath().normalize(); // 归一化待返回路径。
        if (workspaceRoot.equals(normalizedPath)) { // workspace 根目录。
            return "."; // 用点号表示根目录。
        }
        if (!normalizedPath.startsWith(workspaceRoot)) { // 理论上已过滤，兜底避免泄露绝对路径。
            return ""; // 返回空。
        }
        return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/'); // 返回统一斜杠相对路径。
    }

    private String safeRelativePath(String path) { // 标准化日志和 JSON 中的路径。
        String normalizedPath = trimToNull(path); // 去空白。
        return normalizedPath == null ? "." : normalizedPath.replace('\\', '/'); // 空路径用点号表示 workspace 根目录。
    }

    private String normalizeFailureMessage(String message) { // 规范化失败文案。
        if (message == null || message.isBlank()) { // 错误文案缺失。
            return "代码搜索失败，请稍后重试。"; // 友好兜底。
        }
        if (message.contains("workspace 外")) { // 路径穿越或绝对路径。
            return "不允许访问 workspace 外的路径。"; // 不暴露真实路径。
        }
        return message; // 其它受控错误保持原文案。
    }

    private String trimToNull(String value) { // 将空白字符串转换为 null。
        if (value == null || value.trim().isEmpty()) { // null 或空白。
            return null; // 返回 null。
        }
        return value.trim(); // 返回去空白后的文本。
    }

    private String safeMatchedLine(String value) { // 限制命中行长度。
        String normalizedValue = value == null ? "" : value.trim(); // 空值兜底。
        return normalizedValue.length() <= 1000 ? normalizedValue : normalizedValue.substring(0, 1000); // 防止单行过长。
    }

    private String previewQuery(String query) { // 限制日志中的 query 长度。
        String normalizedQuery = trimToNull(query); // 去空白。
        if (normalizedQuery == null) { // 空 query。
            return ""; // 返回空。
        }
        return normalizedQuery.length() <= 80 ? normalizedQuery : normalizedQuery.substring(0, 80) + "..."; // 最多打印 80 字。
    }

    private record SearchOptions(String query,
                                 String searchType,
                                 String path,
                                 int maxResults,
                                 int contextLines,
                                 boolean caseSensitive) { // 搜索参数快照。
    }

    private record MatchDecision(boolean matched,
                                 String matchType,
                                 int score,
                                 String matchedLine) { // 单行匹配判断结果。
        private static MatchDecision notMatched() { // 创建未命中结果。
            return new MatchDecision(false, "", 0, ""); // 返回未命中。
        }
    }

    private static class SearchResult { // 内部搜索结果，只用于排序和 JSON 输出。
        private String filePath; // 相对 workspace 路径。
        private String fileName; // 文件名。
        private String extension; // 扩展名。
        private String language; // 语言分类。
        private Integer lineNumber; // 行号。
        private String matchType; // 匹配类型。
        private String matchedLine; // 命中行。
        private String snippet; // 上下文片段。
        private int score; // 排序分数。
    }

    private static class SearchState { // 搜索状态，用于统计和限制。
        private final int maxResults; // 最大结果数。
        private final List<SearchResult> results = new ArrayList<>(); // 搜索结果列表。
        private int scannedFiles; // 已扫描文件数。
        private int skippedFiles; // 已跳过文件数。
        private int skippedLargeFiles; // 已跳过大文件数。
        private boolean stoppedEarly; // 是否提前停止。

        private SearchState(int maxResults) { // 构造搜索状态。
            this.maxResults = maxResults; // 保存结果上限。
        }

        private void addResult(SearchResult result) { // 添加搜索结果。
            if (result == null) { // 空结果不处理。
                return; // 直接返回。
            }
            results.add(result); // 加入结果列表。
            if (results.size() >= maxResults) { // 达到结果上限。
                stoppedEarly = true; // 标记提前停止。
            }
        }

        private void incrementScannedFiles() { // 增加已扫描文件数。
            scannedFiles++; // 计数加一。
            if (scannedFiles >= MAX_SCAN_FILES) { // 达到扫描文件上限。
                stoppedEarly = true; // 标记提前停止。
            }
        }

        private void incrementSkippedFiles() { // 增加跳过文件数。
            skippedFiles++; // 计数加一。
        }

        private void incrementSkippedLargeFiles() { // 增加跳过大文件数。
            skippedLargeFiles++; // 计数加一。
        }

        private boolean shouldStop() { // 判断是否应停止搜索。
            return stoppedEarly || results.size() >= maxResults || scannedFiles >= MAX_SCAN_FILES; // 任一限制命中即停止。
        }

        private void sortAndLimit() { // 排序并限制结果数量。
            results.sort(Comparator
                    .comparingInt((SearchResult result) -> result.score).reversed()
                    .thenComparing(result -> result.filePath == null ? "" : result.filePath)
                    .thenComparingInt(result -> result.lineNumber == null ? 0 : result.lineNumber)); // 分数优先，再路径和行号。
            if (results.size() > maxResults) { // 超过最大结果数。
                results.subList(maxResults, results.size()).clear(); // 删除多余结果。
                stoppedEarly = true; // 标记截断。
            }
        }
    }
}
