package com.agent.tool.project;

import com.agent.security.ProjectPathGuard;
import com.agent.toolcalling.project.language.CodeLanguage;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * analyzeCode 项目源码结构分析工具。
 *
 * <p>本工具只在 project workspace 内读取单个安全代码/文本文件，通过轻量行扫描和正则提取类、字段、方法、
 * Spring 接口和 AI Tool 基础信息。它不做 AST 解析，不修改文件，不接入 RAG、Milvus、向量化或数据库。</p>
 */
@Slf4j
@Component
public class AnalyzeCodeTool extends AbstractAiTool {
    private static final String TOOL_NAME = "analyzeCode";
    private static final String RESULT_TYPE = "code_analysis";
    private static final int DEFAULT_MAX_ITEMS = 100;
    private static final int MAX_ALLOWED_ITEMS = 300;
    private static final int MAX_FILENAME_MATCHES = 20;
    private static final int MAX_SNIPPET_CHARS = 300;

    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([^;]+);");
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)(?:\\s+extends\\s+([A-Za-z0-9_$.<>]+))?(?:\\s+implements\\s+([A-Za-z0-9_$.,\\s<>]+))?");
    private static final Pattern JAVA_FIELD_PATTERN = Pattern.compile("^\\s*(?:(public|protected|private)\\s+)?(?:(?:static|final|transient|volatile)\\s+)*([A-Za-z_$][\\w$<>\\[\\], ?.&]+?)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=.*)?;\\s*$");
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile("^\\s*(?:(public|protected|private)\\s+)?(?:(?:static|final|synchronized|abstract|default|native|strictfp)\\s+)*([A-Za-z_$][\\w$<>\\[\\], ?.&]+?)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_$.,\\s<>]+)?\\s*(?:\\{|;)?\\s*$");
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("\\bTOOL_NAME\\s*=\\s*\"([^\"]+)\"");

    private static final Pattern PY_IMPORT_PATTERN = Pattern.compile("^\\s*(import\\s+.+|from\\s+.+\\s+import\\s+.+)");
    private static final Pattern PY_CLASS_PATTERN = Pattern.compile("^\\s*class\\s+([A-Za-z_][\\w]*)(?:\\([^)]*\\))?\\s*:");
    private static final Pattern PY_FUNCTION_PATTERN = Pattern.compile("^\\s*(async\\s+)?def\\s+([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)\\s*:");

    private static final Pattern JS_IMPORT_PATTERN = Pattern.compile("^\\s*(import\\s+.+|(?:const|let|var)\\s+.+\\s*=\\s*require\\(.+\\).*)");
    private static final Pattern JS_CLASS_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?class\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern JS_INTERFACE_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?interface\\s+([A-Za-z_$][\\w$]*)\\b");
    private static final Pattern JS_TYPE_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?type\\s+([A-Za-z_$][\\w$]*)\\s*=");
    private static final Pattern JS_FUNCTION_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)");
    private static final Pattern JS_ARROW_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
    private static final Pattern JS_FUNCTION_VALUE_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s+)?function\\b");

    private static final Pattern GO_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z_][\\w]*)");
    private static final Pattern GO_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:\\(|\"([^\"]+)\"|`([^`]+)`)");
    private static final Pattern GO_TYPE_PATTERN = Pattern.compile("^\\s*type\\s+([A-Za-z_][\\w]*)\\s+(struct|interface)\\b");
    private static final Pattern GO_FUNCTION_PATTERN = Pattern.compile("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)\\s*\\(([^)]*)\\)");

    private static final Pattern SQL_STATEMENT_PATTERN = Pattern.compile("\\b(select|insert\\s+into|update|delete\\s+from|create\\s+table|alter\\s+table)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_TABLE_PATTERN = Pattern.compile("\\b(?:from|join|into|update|table)\\s+([A-Za-z0-9_.$`\"\\[\\]]+)", Pattern.CASE_INSENSITIVE);

    private final ProjectPathGuard projectPathGuard;

    public AnalyzeCodeTool(ProjectPathGuard projectPathGuard) {
        this.projectPathGuard = projectPathGuard;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "分析 project workspace 内的单个源码文件结构，提取类、注解、字段、方法、Spring 接口和 AI Tool 基础信息。只做轻量行扫描/正则分析，不修改文件，不访问 workspace 外路径。";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema();
        addProperty(schema, "path", createStringProperty("相对 workspace 的源码文件路径，例如 Tech-Brain-Agent/src/main/java/com/agent/tool/project/SearchCodeTool.java"), true);
        addProperty(schema, "language", createStringProperty("可选语言名；为空时按文件扩展名自动识别，例如 java、python、typescript、vue、go、sql"), false);
        ObjectNode includeSnippetProperty = objectMapper.createObjectNode();
        includeSnippetProperty.put("type", "boolean");
        includeSnippetProperty.put("description", "是否返回声明行 snippet，可选，默认 true");
        addProperty(schema, "includeSnippet", includeSnippetProperty, false);
        addProperty(schema, "maxItems", createIntegerProperty("最多返回的结构项数量，可选，默认 100，最大 300"), false);
        return schema;
    }

    @Override
    public String execute(JsonNode arguments) {
        String requestedPath = trimToNull(getOptionalText(arguments, "path", null));
        if (requestedPath == null) {
            return buildFailureResult("", "path 不能为空。");
        }
        String requestedLanguage = trimToNull(getOptionalText(arguments, "language", null));
        boolean includeSnippet = resolveIncludeSnippet(arguments);
        int maxItems = resolveMaxItems(arguments);

        try {
            Path filePath = resolveReadableAnalysisFilePath(requestedPath);
            projectPathGuard.validateReadableCodeFile(filePath);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString();
            String extension = projectPathGuard.getExtension(fileName);
            CodeLanguage registryLanguage = CodeLanguageRegistry.resolveByFileName(fileName);
            String language = requestedLanguage == null ? registryLanguage.getDisplayName() : requestedLanguage;
            String languageKey = normalizeLanguageKey(requestedLanguage == null ? registryLanguage.getMarkdownName() : requestedLanguage, extension);

            ObjectNode result = buildBaseSuccessResult(filePath, requestedPath, fileName, extension, language, lines);
            AnalysisState state = new AnalysisState(maxItems, includeSnippet);
            analyzeByLanguage(languageKey, extension, lines, result, state);
            result.put("truncated", state.truncated);
            return result.toString();
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[AnalyzeCodeTool] access denied, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage());
            return buildFailureResult(requestedPath, normalizeFailureMessage(e.getMessage()));
        } catch (MalformedInputException e) {
            log.warn("[AnalyzeCodeTool] non utf8 file, path: {}", safeRelativePath(requestedPath));
            return buildFailureResult(requestedPath, "文件编码暂不支持，请确认文件为 UTF-8 文本。");
        } catch (IOException e) {
            log.warn("[AnalyzeCodeTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage());
            return buildFailureResult(requestedPath, "读取文件失败，请稍后重试。");
        } catch (Exception e) {
            log.error("[AnalyzeCodeTool] analyze failed, path: {}", safeRelativePath(requestedPath), e);
            return buildFailureResult(requestedPath, "代码结构分析失败，请稍后重试。");
        }
    }

    private Path resolveReadableAnalysisFilePath(String requestedPath) {
        Path directPath = projectPathGuard.resolveProjectPath(requestedPath);
        if (Files.exists(directPath, LinkOption.NOFOLLOW_LINKS) || !isFilenameOnly(requestedPath)) {
            return directPath;
        }

        String fileName = normalizeFileNameCandidate(requestedPath);
        String extension = projectPathGuard.getExtension(fileName);
        if (projectPathGuard.isBlockedFilename(fileName) || projectPathGuard.isBlockedExtension(extension)) {
            throw new ProjectPathGuard.ProjectPathAccessException("该文件属于敏感文件，禁止读取。");
        }
        if (!extension.isEmpty() && !projectPathGuard.isAllowedExtension(extension)) {
            throw new ProjectPathGuard.ProjectPathAccessException("不支持读取该文件类型。");
        }
        List<Path> matches = findProjectFilesByName(fileName);
        if (matches.isEmpty()) {
            throw new ProjectPathGuard.ProjectPathAccessException("未在 workspace 中找到 " + fileName + "，请先使用 searchCode 定位文件位置或提供完整相对路径。");
        }
        if (matches.size() > 1) {
            throw new ProjectPathGuard.ProjectPathAccessException(buildAmbiguousFileMessage(fileName, matches));
        }
        return matches.get(0);
    }

    private String normalizeFileNameCandidate(String requestedPath) {
        String fileName = trimToNull(requestedPath);
        if (fileName == null) {
            return "";
        }
        if (projectPathGuard.isBlockedFilename(fileName)) {
            return fileName;
        }
        if (!fileName.contains(".") && fileName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return fileName + ".java";
        }
        return fileName;
    }

    private List<Path> findProjectFilesByName(String fileName) {
        List<Path> matches = new ArrayList<>();
        String normalizedFileName = trimToNull(fileName);
        if (normalizedFileName == null) {
            return matches;
        }
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot();
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return matches;
        }
        collectProjectFilesByName(workspaceRoot, normalizedFileName, matches);
        matches.sort(Comparator.comparing(path -> toWorkspaceRelativePath(path, ""), String.CASE_INSENSITIVE_ORDER));
        return matches;
    }

    private void collectProjectFilesByName(Path directory, String fileName, List<Path> matches) {
        if (matches.size() >= MAX_FILENAME_MATCHES || !isSafeDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName() == null ? "" : path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (Path child : children) {
                if (matches.size() >= MAX_FILENAME_MATCHES) {
                    return;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    collectProjectFilesByName(child, fileName, matches);
                    continue;
                }
                if (isSafeFileNameMatch(child, fileName)) {
                    matches.add(child.toAbsolutePath().normalize());
                }
            }
        } catch (IOException e) {
            log.debug("[AnalyzeCodeTool] skip unreadable directory while resolving file name");
        }
    }

    private boolean isSafeDirectory(Path directory) {
        if (directory == null) {
            return false;
        }
        Path normalizedPath = directory.toAbsolutePath().normalize();
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isSafeFileNameMatch(Path filePath, String fileName) {
        if (filePath == null || filePath.getFileName() == null) {
            return false;
        }
        Path normalizedPath = filePath.toAbsolutePath().normalize();
        String candidateName = filePath.getFileName().toString();
        String extension = projectPathGuard.getExtension(candidateName);
        return candidateName.equalsIgnoreCase(fileName)
                && projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !projectPathGuard.isBlockedFilename(candidateName)
                && !projectPathGuard.isBlockedExtension(extension)
                && projectPathGuard.isAllowedExtension(extension)
                && Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS);
    }

    private String buildAmbiguousFileMessage(String fileName, List<Path> matches) {
        StringBuilder builder = new StringBuilder("找到多个 ").append(fileName).append("，请指定要分析哪一个：");
        int index = 1;
        for (Path match : matches) {
            builder.append('\n').append(index++).append(". ").append(toWorkspaceRelativePath(match, ""));
        }
        return builder.toString();
    }

    private boolean isFilenameOnly(String path) {
        String normalizedPath = trimToNull(path);
        if (normalizedPath == null) {
            return false;
        }
        return !normalizedPath.contains("/") && !normalizedPath.contains("\\");
    }

    private ObjectNode buildBaseSuccessResult(Path filePath,
                                              String requestedPath,
                                              String fileName,
                                              String extension,
                                              String language,
                                              List<String> lines) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", RESULT_TYPE);
        result.put("success", true);
        result.put("path", toWorkspaceRelativePath(filePath, requestedPath));
        result.put("fileName", fileName);
        result.put("extension", extension);
        result.put("language", language);
        result.put("lineCount", lines == null ? 0 : lines.size());
        result.put("fileSize", Files.size(filePath));
        result.putNull("packageName");
        result.set("imports", objectMapper.createArrayNode());
        result.set("classInfo", objectMapper.createObjectNode());
        result.set("fields", objectMapper.createArrayNode());
        result.set("methods", objectMapper.createArrayNode());
        result.set("springEndpoints", objectMapper.createArrayNode());
        result.set("aiToolInfo", objectMapper.createObjectNode());
        result.set("basicSymbols", objectMapper.createArrayNode());
        return result;
    }

    private void analyzeByLanguage(String languageKey,
                                   String extension,
                                   List<String> lines,
                                   ObjectNode result,
                                   AnalysisState state) {
        if ("java".equals(languageKey) || "java".equals(extension)) {
            analyzeJava(lines, result, state);
            return;
        }
        if ("python".equals(languageKey) || "py".equals(extension)) {
            analyzePython(lines, result, state);
            return;
        }
        if ("javascript".equals(languageKey) || "typescript".equals(languageKey)
                || "js".equals(extension) || "jsx".equals(extension) || "ts".equals(extension) || "tsx".equals(extension)) {
            analyzeJavaScriptLike(lines, result, state);
            return;
        }
        if ("vue".equals(languageKey) || "vue".equals(extension)) {
            analyzeVue(lines, result, state);
            return;
        }
        if ("go".equals(languageKey) || "go".equals(extension)) {
            analyzeGo(lines, result, state);
            return;
        }
        if ("sql".equals(languageKey) || "sql".equals(extension)) {
            analyzeSql(lines, result, state);
            return;
        }
        analyzeBasic(lines, result, state);
    }

    private void analyzeJava(List<String> lines, ObjectNode result, AnalysisState state) {
        ArrayNode imports = result.withArray("imports");
        ArrayNode fields = result.withArray("fields");
        ArrayNode methods = result.withArray("methods");
        ArrayNode endpoints = result.withArray("springEndpoints");
        List<String> pendingAnnotations = new ArrayList<>();
        List<String> allLines = lines == null ? List.of() : lines;
        String allContent = String.join("\n", allLines);
        ObjectNode classInfo = objectMapper.createObjectNode();
        String classBasePath = "";
        boolean controllerClass = false;
        int braceDepth = 0;

        for (int index = 0; index < allLines.size(); index++) {
            String line = allLines.get(index);
            String trimmed = line.trim();
            int lineNumber = index + 1;

            Matcher packageMatcher = JAVA_PACKAGE_PATTERN.matcher(line);
            if (packageMatcher.find()) {
                result.put("packageName", packageMatcher.group(1));
            }
            Matcher importMatcher = JAVA_IMPORT_PATTERN.matcher(line);
            if (importMatcher.find()) {
                imports.add(importMatcher.group(1));
            }

            if (trimmed.startsWith("@") && braceDepth <= 1) {
                pendingAnnotations.add(trimmed);
                braceDepth += braceDelta(line);
                continue;
            }

            Matcher classMatcher = JAVA_CLASS_PATTERN.matcher(line);
            if (classInfo.isEmpty() && classMatcher.find()) {
                classInfo = buildJavaClassInfo(classMatcher, pendingAnnotations, lineNumber);
                result.set("classInfo", classInfo);
                controllerClass = hasAnnotation(pendingAnnotations, "RestController") || hasAnnotation(pendingAnnotations, "Controller");
                classBasePath = firstMappingPath(pendingAnnotations, "@RequestMapping");
                pendingAnnotations.clear();
                braceDepth += braceDelta(line);
                continue;
            }

            if (braceDepth <= 1 && !pendingAnnotations.isEmpty()) {
                Matcher methodMatcher = JAVA_METHOD_PATTERN.matcher(line);
                if (methodMatcher.matches() && isJavaMethodLine(trimmed, methodMatcher)) {
                    ObjectNode method = buildJavaMethod(methodMatcher, pendingAnnotations, lineNumber, line, state.includeSnippet);
                    addLimited(methods, method, state);
                    addSpringEndpointsIfNeeded(controllerClass, classBasePath, pendingAnnotations, methodMatcher.group(3),
                            lineNumber, endpoints, state);
                    pendingAnnotations.clear();
                    braceDepth += braceDelta(line);
                    continue;
                }
            }

            if (braceDepth <= 1) {
                Matcher methodMatcher = JAVA_METHOD_PATTERN.matcher(line);
                if (methodMatcher.matches() && isJavaMethodLine(trimmed, methodMatcher)) {
                    ObjectNode method = buildJavaMethod(methodMatcher, pendingAnnotations, lineNumber, line, state.includeSnippet);
                    addLimited(methods, method, state);
                    addSpringEndpointsIfNeeded(controllerClass, classBasePath, pendingAnnotations, methodMatcher.group(3),
                            lineNumber, endpoints, state);
                    pendingAnnotations.clear();
                    braceDepth += braceDelta(line);
                    continue;
                }

                Matcher fieldMatcher = JAVA_FIELD_PATTERN.matcher(line);
                if (fieldMatcher.matches() && isJavaFieldLine(trimmed)) {
                    ObjectNode field = buildJavaField(fieldMatcher, pendingAnnotations, lineNumber, line, state.includeSnippet);
                    addLimited(fields, field, state);
                    pendingAnnotations.clear();
                    braceDepth += braceDelta(line);
                    continue;
                }
            }

            if (!trimmed.isBlank() && !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                pendingAnnotations.clear();
            }
            braceDepth += braceDelta(line);
        }

        result.set("aiToolInfo", buildAiToolInfo(allContent, classInfo));
    }

    private ObjectNode buildJavaClassInfo(Matcher classMatcher, List<String> annotations, int lineNumber) {
        ObjectNode node = objectMapper.createObjectNode();
        String type = classMatcher.group(1) == null ? "CLASS" : classMatcher.group(1).toUpperCase(Locale.ROOT);
        node.put("name", safeText(classMatcher.group(2)));
        node.put("type", type);
        node.set("annotations", toArrayNode(annotations));
        node.put("extendsClass", safeText(classMatcher.group(3)));
        node.set("implementsInterfaces", splitToArrayNode(classMatcher.group(4)));
        node.put("lineNumber", lineNumber);
        return node;
    }

    private ObjectNode buildJavaField(Matcher fieldMatcher,
                                      List<String> annotations,
                                      int lineNumber,
                                      String line,
                                      boolean includeSnippet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", safeText(fieldMatcher.group(3)));
        node.put("type", compactSpaces(fieldMatcher.group(2)));
        node.put("visibility", visibility(fieldMatcher.group(1)));
        node.set("annotations", toArrayNode(annotations));
        node.put("lineNumber", lineNumber);
        putSnippetIfNeeded(node, line, includeSnippet);
        return node;
    }

    private ObjectNode buildJavaMethod(Matcher methodMatcher,
                                       List<String> annotations,
                                       int lineNumber,
                                       String line,
                                       boolean includeSnippet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", safeText(methodMatcher.group(3)));
        node.put("returnType", compactSpaces(methodMatcher.group(2)));
        node.put("visibility", visibility(methodMatcher.group(1)));
        node.put("parameters", compactSpaces(methodMatcher.group(4)));
        node.set("annotations", toArrayNode(annotations));
        node.put("lineNumber", lineNumber);
        putSnippetIfNeeded(node, line, includeSnippet);
        return node;
    }

    private void addSpringEndpointsIfNeeded(boolean controllerClass,
                                            String classBasePath,
                                            List<String> annotations,
                                            String methodName,
                                            int lineNumber,
                                            ArrayNode endpoints,
                                            AnalysisState state) {
        if (!controllerClass || annotations == null || annotations.isEmpty()) {
            return;
        }
        for (String annotation : annotations) {
            String mappingAnnotation = mappingAnnotationName(annotation);
            if (mappingAnnotation == null) {
                continue;
            }
            ObjectNode endpoint = objectMapper.createObjectNode();
            endpoint.put("httpMethod", httpMethodFromMappingAnnotation(annotation, mappingAnnotation));
            endpoint.put("path", joinPaths(classBasePath, extractAnnotationPath(annotation)));
            endpoint.put("methodName", safeText(methodName));
            endpoint.put("lineNumber", lineNumber);
            endpoint.put("mappingAnnotation", mappingAnnotation);
            addLimited(endpoints, endpoint, state);
        }
    }

    private ObjectNode buildAiToolInfo(String content, ObjectNode classInfo) {
        ObjectNode node = objectMapper.createObjectNode();
        Matcher toolNameMatcher = TOOL_NAME_PATTERN.matcher(content == null ? "" : content);
        String toolName = toolNameMatcher.find() ? toolNameMatcher.group(1) : "";
        boolean extendsAbstractAiTool = contains(content, "extends AbstractAiTool");
        boolean implementsAiTool = contains(content, "implements AiTool");
        boolean hasExecuteMethod = Pattern.compile("\\bexecute\\s*\\(\\s*JsonNode\\b").matcher(content == null ? "" : content).find();
        boolean hasParametersSchema = contains(content, "parametersSchema(");
        boolean hasDescription = contains(content, "description()");
        boolean isAiTool = !toolName.isBlank() || extendsAbstractAiTool || implementsAiTool || hasExecuteMethod || hasParametersSchema;

        node.put("isAiTool", isAiTool);
        node.put("toolName", toolName);
        node.put("extendsAbstractAiTool", extendsAbstractAiTool);
        node.put("implementsAiTool", implementsAiTool);
        node.put("hasExecuteMethod", hasExecuteMethod);
        node.put("hasParametersSchema", hasParametersSchema);
        node.put("hasDescription", hasDescription);
        if (classInfo != null && classInfo.hasNonNull("name")) {
            node.put("className", classInfo.path("name").asText(""));
        }
        return node;
    }

    private void analyzePython(List<String> lines, ObjectNode result, AnalysisState state) {
        ArrayNode imports = result.withArray("imports");
        ArrayNode symbols = result.withArray("basicSymbols");
        List<String> decorators = new ArrayList<>();
        List<String> safeLines = lines == null ? List.of() : lines;
        for (int index = 0; index < safeLines.size(); index++) {
            String line = safeLines.get(index);
            String trimmed = line.trim();
            int lineNumber = index + 1;
            Matcher importMatcher = PY_IMPORT_PATTERN.matcher(line);
            if (importMatcher.matches()) {
                imports.add(trimmed);
            }
            if (trimmed.startsWith("@")) {
                decorators.add(trimmed);
                continue;
            }
            Matcher classMatcher = PY_CLASS_PATTERN.matcher(line);
            if (classMatcher.matches()) {
                addLimited(symbols, buildSymbol("CLASS", classMatcher.group(1), decorators, lineNumber, line, state.includeSnippet), state);
                decorators.clear();
                continue;
            }
            Matcher functionMatcher = PY_FUNCTION_PATTERN.matcher(line);
            if (functionMatcher.matches()) {
                String kind = functionMatcher.group(1) == null ? "FUNCTION" : "ASYNC_FUNCTION";
                ObjectNode symbol = buildSymbol(kind, functionMatcher.group(2), decorators, lineNumber, line, state.includeSnippet);
                symbol.put("parameters", compactSpaces(functionMatcher.group(3)));
                addLimited(symbols, symbol, state);
                decorators.clear();
                continue;
            }
            if (!trimmed.isBlank() && !trimmed.startsWith("#")) {
                decorators.clear();
            }
        }
    }

    private void analyzeJavaScriptLike(List<String> lines, ObjectNode result, AnalysisState state) {
        ArrayNode imports = result.withArray("imports");
        ArrayNode symbols = result.withArray("basicSymbols");
        List<String> safeLines = lines == null ? List.of() : lines;
        for (int index = 0; index < safeLines.size(); index++) {
            String line = safeLines.get(index);
            String trimmed = line.trim();
            int lineNumber = index + 1;
            Matcher importMatcher = JS_IMPORT_PATTERN.matcher(line);
            if (importMatcher.matches()) {
                imports.add(trimmed);
            }
            addJsSymbolIfMatches(symbols, state, line, lineNumber, JS_CLASS_PATTERN, "CLASS");
            addJsSymbolIfMatches(symbols, state, line, lineNumber, JS_INTERFACE_PATTERN, "INTERFACE");
            addJsSymbolIfMatches(symbols, state, line, lineNumber, JS_TYPE_PATTERN, "TYPE");
            addJsSymbolIfMatches(symbols, state, line, lineNumber, JS_FUNCTION_PATTERN, trimmed.startsWith("async ") ? "ASYNC_FUNCTION" : "FUNCTION");
            addJsSymbolIfMatches(symbols, state, line, lineNumber, JS_ARROW_PATTERN, "ARROW_FUNCTION");
            addJsSymbolIfMatches(symbols, state, line, lineNumber, JS_FUNCTION_VALUE_PATTERN, "FUNCTION_VALUE");
            if (trimmed.startsWith("export default")) {
                addLimited(symbols, buildSymbol("EXPORT_DEFAULT", "default", List.of(), lineNumber, line, state.includeSnippet), state);
            }
        }
    }

    private void addJsSymbolIfMatches(ArrayNode symbols,
                                      AnalysisState state,
                                      String line,
                                      int lineNumber,
                                      Pattern pattern,
                                      String kind) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            addLimited(symbols, buildSymbol(kind, matcher.group(1), List.of(), lineNumber, line, state.includeSnippet), state);
        }
    }

    private void analyzeVue(List<String> lines, ObjectNode result, AnalysisState state) {
        analyzeJavaScriptLike(lines, result, state);
        List<String> safeLines = lines == null ? List.of() : lines;
        ObjectNode vueInfo = objectMapper.createObjectNode();
        vueInfo.put("hasTemplate", containsLine(safeLines, "<template"));
        vueInfo.put("hasScript", containsLine(safeLines, "<script"));
        vueInfo.put("hasStyle", containsLine(safeLines, "<style"));
        vueInfo.put("hasDefineComponent", containsLine(safeLines, "defineComponent"));
        vueInfo.put("hasSetup", containsLine(safeLines, "setup(") || containsLine(safeLines, "<script setup"));
        result.set("vueInfo", vueInfo);
    }

    private void analyzeGo(List<String> lines, ObjectNode result, AnalysisState state) {
        ArrayNode imports = result.withArray("imports");
        ArrayNode symbols = result.withArray("basicSymbols");
        boolean inImportBlock = false;
        List<String> safeLines = lines == null ? List.of() : lines;
        for (int index = 0; index < safeLines.size(); index++) {
            String line = safeLines.get(index);
            String trimmed = line.trim();
            int lineNumber = index + 1;
            Matcher packageMatcher = GO_PACKAGE_PATTERN.matcher(line);
            if (packageMatcher.matches()) {
                result.put("packageName", packageMatcher.group(1));
            }
            Matcher importMatcher = GO_IMPORT_PATTERN.matcher(line);
            if (importMatcher.find()) {
                inImportBlock = trimmed.endsWith("(");
                String directImport = importMatcher.group(1) != null ? importMatcher.group(1) : importMatcher.group(2);
                if (directImport != null) {
                    imports.add(directImport);
                }
                continue;
            }
            if (inImportBlock) {
                if (trimmed.startsWith(")")) {
                    inImportBlock = false;
                } else if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    imports.add(trimmed.substring(1, trimmed.length() - 1));
                }
            }
            Matcher typeMatcher = GO_TYPE_PATTERN.matcher(line);
            if (typeMatcher.matches()) {
                addLimited(symbols, buildSymbol(typeMatcher.group(2).toUpperCase(Locale.ROOT), typeMatcher.group(1), List.of(), lineNumber, line, state.includeSnippet), state);
            }
            Matcher functionMatcher = GO_FUNCTION_PATTERN.matcher(line);
            if (functionMatcher.matches()) {
                ObjectNode symbol = buildSymbol("FUNCTION", functionMatcher.group(1), List.of(), lineNumber, line, state.includeSnippet);
                symbol.put("parameters", compactSpaces(functionMatcher.group(2)));
                addLimited(symbols, symbol, state);
            }
        }
    }

    private void analyzeSql(List<String> lines, ObjectNode result, AnalysisState state) {
        ArrayNode symbols = result.withArray("basicSymbols");
        Set<String> tableNames = new LinkedHashSet<>();
        List<String> safeLines = lines == null ? List.of() : lines;
        for (int index = 0; index < safeLines.size(); index++) {
            String line = safeLines.get(index);
            Matcher statementMatcher = SQL_STATEMENT_PATTERN.matcher(line);
            if (statementMatcher.find()) {
                String statement = compactSpaces(statementMatcher.group(1)).toUpperCase(Locale.ROOT);
                addLimited(symbols, buildSymbol("SQL_STATEMENT", statement, List.of(), index + 1, line, state.includeSnippet), state);
            }
            Matcher tableMatcher = SQL_TABLE_PATTERN.matcher(line);
            while (tableMatcher.find()) {
                tableNames.add(cleanSqlTableName(tableMatcher.group(1)));
            }
        }
        ArrayNode tables = objectMapper.createArrayNode();
        tableNames.forEach(tables::add);
        result.set("tables", tables);
    }

    private void analyzeBasic(List<String> lines, ObjectNode result, AnalysisState state) {
        ArrayNode imports = result.withArray("imports");
        ArrayNode symbols = result.withArray("basicSymbols");
        List<String> safeLines = lines == null ? List.of() : lines;
        for (int index = 0; index < safeLines.size(); index++) {
            String line = safeLines.get(index);
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("using ")
                    || trimmed.startsWith("#include") || trimmed.startsWith("use ")
                    || trimmed.contains("require(")) {
                imports.add(trimmed);
            }
            if (looksLikeBasicSymbol(trimmed)) {
                addLimited(symbols, buildSymbol("SYMBOL", firstSymbolName(trimmed), List.of(), index + 1, line, state.includeSnippet), state);
            }
        }
    }

    private ObjectNode buildSymbol(String kind,
                                   String name,
                                   List<String> annotations,
                                   int lineNumber,
                                   String line,
                                   boolean includeSnippet) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("kind", safeText(kind));
        node.put("name", safeText(name));
        node.set("annotations", toArrayNode(annotations));
        node.put("lineNumber", lineNumber);
        putSnippetIfNeeded(node, line, includeSnippet);
        return node;
    }

    private void addLimited(ArrayNode arrayNode, ObjectNode item, AnalysisState state) {
        if (arrayNode == null || item == null || state == null) {
            return;
        }
        if (state.items >= state.maxItems) {
            state.truncated = true;
            return;
        }
        arrayNode.add(item);
        state.items++;
    }

    private String mappingAnnotationName(String annotation) {
        if (annotation == null) {
            return null;
        }
        String[] names = {"@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping", "@RequestMapping"};
        for (String name : names) {
            if (annotation.contains(name)) {
                return name.substring(1);
            }
        }
        return null;
    }

    private String httpMethodFromMappingAnnotation(String annotation, String mappingAnnotation) {
        if ("GetMapping".equals(mappingAnnotation)) {
            return "GET";
        }
        if ("PostMapping".equals(mappingAnnotation)) {
            return "POST";
        }
        if ("PutMapping".equals(mappingAnnotation)) {
            return "PUT";
        }
        if ("DeleteMapping".equals(mappingAnnotation)) {
            return "DELETE";
        }
        if ("PatchMapping".equals(mappingAnnotation)) {
            return "PATCH";
        }
        Matcher matcher = Pattern.compile("RequestMethod\\.([A-Z]+)").matcher(annotation == null ? "" : annotation);
        return matcher.find() ? matcher.group(1) : "REQUEST";
    }

    private String firstMappingPath(List<String> annotations, String targetAnnotation) {
        if (annotations == null || annotations.isEmpty()) {
            return "";
        }
        for (String annotation : annotations) {
            if (annotation != null && annotation.contains(targetAnnotation)) {
                return extractAnnotationPath(annotation);
            }
        }
        return "";
    }

    private String extractAnnotationPath(String annotation) {
        if (annotation == null || annotation.isBlank()) {
            return "";
        }
        Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(annotation);
        if (quoted.find()) {
            return quoted.group(1);
        }
        Matcher named = Pattern.compile("(?:path|value)\\s*=\\s*\\{?\\s*\"([^\"]*)\"").matcher(annotation);
        return named.find() ? named.group(1) : "";
    }

    private String joinPaths(String basePath, String methodPath) {
        String base = normalizeEndpointPath(basePath);
        String method = normalizeEndpointPath(methodPath);
        if (base.isEmpty()) {
            return method.isEmpty() ? "/" : method;
        }
        if (method.isEmpty() || "/".equals(method)) {
            return base;
        }
        return (base + "/" + method.substring(1)).replaceAll("/{2,}", "/");
    }

    private String normalizeEndpointPath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private boolean hasAnnotation(List<String> annotations, String annotationName) {
        if (annotations == null) {
            return false;
        }
        for (String annotation : annotations) {
            if (annotation != null && annotation.contains("@" + annotationName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJavaMethodLine(String trimmed, Matcher methodMatcher) {
        if (trimmed == null || trimmed.isBlank() || trimmed.contains("=")) {
            return false;
        }
        String name = methodMatcher.group(3);
        String returnType = methodMatcher.group(2);
        return !isJavaKeyword(name) && !isJavaKeyword(returnType) && !trimmed.startsWith("new ");
    }

    private boolean isJavaFieldLine(String trimmed) {
        return trimmed != null
                && !trimmed.contains("(")
                && !trimmed.startsWith("return ")
                && !trimmed.startsWith("throw ")
                && !trimmed.startsWith("package ")
                && !trimmed.startsWith("import ");
    }

    private boolean isJavaKeyword(String value) {
        if (value == null) {
            return true;
        }
        return Set.of("if", "for", "while", "switch", "catch", "return", "throw", "new", "else", "do", "try")
                .contains(value.trim());
    }

    private int braceDelta(String line) {
        int delta = 0;
        boolean inString = false;
        char previous = 0;
        for (int i = 0; line != null && i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"' && previous != '\\') {
                inString = !inString;
            }
            if (!inString && ch == '{') {
                delta++;
            } else if (!inString && ch == '}') {
                delta--;
            }
            previous = ch;
        }
        return delta;
    }

    private String visibility(String value) {
        return value == null || value.isBlank() ? "package-private" : value.trim();
    }

    private ArrayNode toArrayNode(List<String> values) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    arrayNode.add(value.trim());
                }
            }
        }
        return arrayNode;
    }

    private ArrayNode splitToArrayNode(String value) {
        ArrayNode arrayNode = objectMapper.createArrayNode();
        if (value == null || value.isBlank()) {
            return arrayNode;
        }
        for (String item : value.split(",")) {
            String normalized = compactSpaces(item);
            if (!normalized.isBlank()) {
                arrayNode.add(normalized);
            }
        }
        return arrayNode;
    }

    private void putSnippetIfNeeded(ObjectNode node, String line, boolean includeSnippet) {
        if (includeSnippet) {
            node.put("snippet", abbreviate(compactSpaces(line), MAX_SNIPPET_CHARS));
        }
    }

    private boolean resolveIncludeSnippet(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return true;
        }
        JsonNode valueNode = arguments.path("includeSnippet");
        return valueNode.isMissingNode() || valueNode.isNull() || valueNode.asBoolean(true);
    }

    private int resolveMaxItems(JsonNode arguments) {
        int maxItems = getOptionalInt(arguments, "maxItems", DEFAULT_MAX_ITEMS);
        if (maxItems <= 0) {
            return DEFAULT_MAX_ITEMS;
        }
        return Math.min(maxItems, MAX_ALLOWED_ITEMS);
    }

    private String normalizeLanguageKey(String language, String extension) {
        String value = language == null || language.isBlank() ? extension : language;
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "java" -> "java";
            case "py", "python" -> "python";
            case "js", "jsx", "javascript" -> "javascript";
            case "ts", "tsx", "typescript" -> "typescript";
            case "vue" -> "vue";
            case "go", "golang" -> "go";
            case "sql" -> "sql";
            default -> normalized;
        };
    }

    private String toWorkspaceRelativePath(Path filePath, String fallbackPath) {
        try {
            Path workspaceRoot = projectPathGuard.getWorkspaceRoot().toAbsolutePath().normalize();
            Path normalizedPath = filePath.toAbsolutePath().normalize();
            if (normalizedPath.startsWith(workspaceRoot)) {
                return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // fallback below
        }
        return safeRelativePath(fallbackPath);
    }

    private String safeRelativePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private String normalizeFailureMessage(String message) {
        return message == null || message.isBlank() ? "代码结构分析失败。" : message;
    }

    private String buildFailureResult(String path, String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", RESULT_TYPE);
        result.put("success", false);
        result.put("path", safeRelativePath(path));
        result.put("message", message);
        return result.toString();
    }

    private String compactSpaces(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private boolean contains(String text, String keyword) {
        return text != null && keyword != null && text.contains(keyword);
    }

    private boolean containsLine(List<String> lines, String keyword) {
        if (lines == null || keyword == null) {
            return false;
        }
        for (String line : lines) {
            if (line != null && line.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String value, int maxLength) {
        String text = value == null ? "" : value;
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)) + "...[snippet truncated]";
    }

    private boolean looksLikeBasicSymbol(String trimmed) {
        return trimmed.startsWith("function ")
                || trimmed.startsWith("class ")
                || trimmed.startsWith("interface ")
                || trimmed.startsWith("type ")
                || trimmed.startsWith("def ")
                || trimmed.startsWith("func ");
    }

    private String firstSymbolName(String trimmed) {
        if (trimmed == null || trimmed.isBlank()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+|\\(");
        return parts.length >= 2 ? parts[1].replaceAll("[^A-Za-z0-9_$-]", "") : parts[0];
    }

    private String cleanSqlTableName(String tableName) {
        return tableName == null ? "" : tableName.replace("`", "")
                .replace("\"", "")
                .replace("[", "")
                .replace("]", "")
                .trim();
    }

    private static class AnalysisState {
        private final int maxItems;
        private final boolean includeSnippet;
        private int items;
        private boolean truncated;

        private AnalysisState(int maxItems, boolean includeSnippet) {
            this.maxItems = maxItems;
            this.includeSnippet = includeSnippet;
        }
    }
}
