package com.agent.analysis.code;

import com.agent.security.ProjectPathGuard;
import com.agent.toolcalling.project.analysis.CodeAnalysisType;
import com.agent.toolcalling.project.language.CodeLanguageRegistry;
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
 * 普通源码轻量级调用链内部分析器（P5.2）。
 *
 * <p>适用场景：作为 analyzeCode 的 CALL_CHAIN 内部 Analyzer，处理项目 workspace 内某个源码文件的调用链、
 * 调用关系、依赖对象、方法内部调用、对 Service/Mapper/Repository/Tool 的外部调用等请求，做静态文本级
 * “调用关系候选”提取。第一版不是完整精准调用图，只给出候选结果。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 将调用链类请求统一构造成 analyzeCode.arguments.analysisType=CALL_CHAIN
 * -> AnalyzeCodeTool 分发到本分析器
 * -> execute(arguments) 解析 path/methodName/maxDepth/maxItems/includeSnippet
 * -> ProjectPathGuard 校验 workspace 边界、敏感目录、敏感文件和扩展名
 * -> 按 UTF-8 读取源码并做 Java 优先的轻量调用关系提取
 * -> 返回 call_chain_analysis 内部结果给 AnalyzeCodeTool 包装。</p>
 *
 * <p>边界说明：本分析器不继承 AbstractAiTool、不实现 AiTool，不进入 ToolRegistry；
 * 它不修改项目文件、不生成/应用 patch、不做完整 AST、不做精准调用图、不做跨仓库调用链、
 * 不递归读取大量文件、不接入 RAG/Milvus/向量化、不返回完整源码，也不返回服务器绝对路径。</p>
 */
@Slf4j // 输出 [CallChainAnalyzer] 前缀日志，不打印服务器绝对路径和完整文件内容。
@Component // 注册为内部 Spring Bean，供 AnalyzeCodeTool 分发使用，不会被 ToolRegistry 暴露。
public class CallChainAnalyzer extends AbstractCodeAnalysisHandler { // 项目代码调用链内部分析器。
    private static final String RESULT_TYPE = "call_chain_analysis"; // 工具返回 JSON 类型。
    private static final int DEFAULT_MAX_DEPTH = 1; // 默认调用链分析深度。
    private static final int MAX_ALLOWED_DEPTH = 2; // 第一版最大调用链分析深度。
    private static final int DEFAULT_MAX_ITEMS = 100; // 默认最多返回的调用项数量。
    private static final int MAX_ALLOWED_ITEMS = 300; // 最大允许返回的调用项数量。
    private static final int MAX_FILENAME_MATCHES = 20; // 文件名定位时最多收集的候选数量。
    private static final int MAX_CANDIDATE_TARGETS = 5; // 单个外部调用最多返回的候选目标文件数量。
    private static final int MAX_CALL_EXPRESSION_CHARS = 200; // 单条调用表达式最长字符数。
    private static final String ANALYSIS_WARNING = "第一版为轻量静态分析，调用目标为候选结果，不保证完全精准。"; // 统一不确定性说明。

    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;"); // 提取 package。
    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([^;]+);"); // 提取 import。
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"); // 提取类/接口名。
    private static final Pattern JAVA_FIELD_PATTERN = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:static\\s+)?(final\\s+)?([A-Za-z_$][\\w$.]*(?:<[^;=]*>)?)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=.*)?;\\s*$"); // 提取字段声明，用于依赖对象识别。
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:(?:static|final|synchronized|abstract|default|native|strictfp)\\s+)*([A-Za-z_$][\\w$<>\\[\\], ?.&]+?)\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_$.,\\s<>]+)?\\s*(?:\\{|;)?\\s*$"); // 提取方法声明，复用 analyzeCode 同款规则。
    private static final Pattern JAVA_CALL_PATTERN = Pattern.compile(
            "(?:([A-Za-z_$][\\w$]*)\\s*\\.\\s*)?([A-Za-z_$][\\w$]*)\\s*\\("); // 提取 object.method( / this.method( / method( 调用候选。

    private static final Pattern PY_FUNCTION_PATTERN = Pattern.compile("^\\s*(?:async\\s+)?def\\s+([A-Za-z_][\\w]*)\\s*\\("); // Python 函数声明。
    private static final Pattern JS_FUNCTION_PATTERN = Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\("); // JS/TS 函数声明。
    private static final Pattern GO_FUNCTION_PATTERN = Pattern.compile("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)\\s*\\("); // Go 函数声明。
    private static final Pattern GENERIC_CALL_PATTERN = Pattern.compile(
            "(?:([A-Za-z_$][\\w$]*)\\s*\\.\\s*)?([A-Za-z_$][\\w$]*)\\s*\\("); // 其它语言基础调用候选。

    private static final Set<String> JAVA_CALL_KEYWORDS = Set.of( // 调用提取时需要排除的语言关键字。
            "if", "for", "while", "switch", "catch", "try", "return", "new", "throw",
            "synchronized", "do", "else", "case", "instanceof", "assert");
    private static final Set<String> UTILITY_RECEIVERS = Set.of( // 常见基础/工具类调用降级为 utilityCalls。
            "log", "logger", "System", "Objects", "Collections", "CollectionUtils", "Optional",
            "Arrays", "Math", "Pattern", "Files", "Comparator", "Stream", "Locale",
            "StandardCharsets", "String", "Integer", "Long", "Boolean", "Double", "Character",
            "StringUtils", "JSON", "JSONObject", "JSONArray");
    private static final Set<String> COMMON_VALUE_TYPES = Set.of( // 这些类型不作为依赖对象，避免常量和集合污染依赖列表。
            "String", "Integer", "Long", "Boolean", "Double", "Float", "Object", "Byte", "Short",
            "Character", "BigDecimal", "BigInteger", "Number", "CharSequence", "Void",
            "List", "Map", "Set", "Collection", "Optional", "ArrayList", "HashMap", "LinkedHashMap");

    private final ProjectPathGuard projectPathGuard; // P4.1 workspace 路径安全守卫，复用 analyzeCode 的安全策略。

    public CallChainAnalyzer(ProjectPathGuard projectPathGuard) { // 构造器注入路径守卫，保持现有安全策略一致。
        this.projectPathGuard = projectPathGuard; // 保存路径安全守卫。
    }

    @Override // 返回 analyzeCode 内部分发类型。
    public CodeAnalysisType analysisType() {
        return CodeAnalysisType.CALL_CHAIN; // 普通调用链分析。
    }

    @Override // 返回内部分析器名。
    public String name() {
        return analysisType().name(); // 不再返回旧工具名。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return "分析项目 workspace 内指定源码文件的轻量调用链，提取当前类依赖、方法调用、对象调用和可疑目标文件候选。只做静态文本级分析，不修改文件，不访问 workspace 外路径，不做完整 AST，不保证百分百精准。"; // 给模型判断调用时机。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "path", createStringProperty("相对于项目 workspace 的源码文件路径，例如 Tech-Brain-Agent/src/main/java/com/agent/tool/project/SearchCodeTool.java"), true); // path 必填。
        addProperty(schema, "methodName", createStringProperty("可选。指定要分析的入口方法名，例如 execute、chatStream、saveMessage"), false); // methodName 可选。
        addProperty(schema, "maxDepth", createIntegerProperty("调用链分析深度，可选，默认 1，最大 2。第一版建议只支持 1-2 层"), false); // maxDepth 可选。
        addProperty(schema, "maxItems", createIntegerProperty("最多返回调用项数量，可选，默认 100，最大 300"), false); // maxItems 可选。
        ObjectNode includeSnippetProperty = objectMapper.createObjectNode(); // bool 字段没有公共 helper，直接构造。
        includeSnippetProperty.put("type", "boolean"); // 标记字段类型为 boolean。
        includeSnippetProperty.put("description", "是否包含少量代码片段，可选，默认 true"); // 写入字段说明。
        addProperty(schema, "includeSnippet", includeSnippetProperty, false); // includeSnippet 可选。
        return schema; // 返回完整参数 Schema。
    }

    @Override // 执行 analyzeCode(CALL_CHAIN) 内部分析器。
    public String execute(JsonNode arguments) {
        String requestedPath = trimToNull(getOptionalText(arguments, "path", null)); // 读取相对 workspace 的源码文件路径。
        if (requestedPath == null) { // path 缺失时直接失败。
            return buildFailureResult("", "请提供要分析调用链的项目文件路径。"); // 返回结构化失败 JSON。
        }
        String methodName = trimToNull(getOptionalText(arguments, "methodName", null)); // 读取可选入口方法名。
        int maxDepth = resolveMaxDepth(arguments); // 解析并限制调用链分析深度。
        int maxItems = resolveMaxItems(arguments); // 解析并限制调用项上限。
        boolean includeSnippet = resolveIncludeSnippet(arguments); // 解析是否返回代码片段。

        try {
            Path filePath = resolveReadableAnalysisFilePath(requestedPath); // 解析明确路径或在文件名/类名场景下安全唯一定位项目文件。
            projectPathGuard.validateReadableCodeFile(filePath); // 统一校验 workspace、敏感路径、扩展名、普通文件和大小限制。
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8); // 按 UTF-8 读取源码行。
            String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 获取文件名。
            String extension = projectPathGuard.getExtension(fileName); // 获取小写扩展名。
            String language = CodeLanguageRegistry.resolveByFileName(fileName).getDisplayName(); // 统一识别语言展示名。

            ObjectNode result = buildBaseSuccessResult(filePath, requestedPath, fileName, extension, language, methodName, maxDepth, lines); // 构造成功结果骨架。
            CallChainState state = new CallChainState(maxItems, includeSnippet); // 初始化调用项计数和片段开关。
            if ("java".equals(extension)) { // Java 优先做完整轻量调用链分析。
                boolean methodFound = analyzeJava(lines, methodName, result, state); // 提取依赖、方法、内部/外部/工具/未解析调用。
                if (methodName != null && !methodFound) { // 指定方法但文件中找不到时返回失败。
                    return buildFailureResult(toWorkspaceRelativePath(filePath, requestedPath),
                            "未在该文件 " + fileName + " 中找到方法 " + methodName + "。"); // 提示用户确认方法名。
                }
            } else { // 其它语言只做基础函数调用提取。
                analyzeBasicLanguage(extension, lines, result, state); // 提取 functions 和 basicCalls。
            }
            result.put("truncated", state.truncated); // 标记调用项是否按 maxItems 截断。
            return result.toString(); // 返回结构化 JSON 字符串。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[AnalyzeCallChainTool] access denied, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // 安全校验失败不打印堆栈。
            return buildFailureResult(requestedPath, normalizeFailureMessage(e.getMessage())); // 返回友好失败 JSON。
        } catch (MalformedInputException e) {
            log.warn("[AnalyzeCallChainTool] non utf8 file, path: {}", safeRelativePath(requestedPath)); // 编码不支持时记录相对路径。
            return buildFailureResult(requestedPath, "文件编码暂不支持，请确认文件为 UTF-8 文本。"); // 返回友好失败 JSON。
        } catch (IOException e) {
            log.warn("[AnalyzeCallChainTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // IO 失败时只打印相对路径。
            return buildFailureResult(requestedPath, "读取文件失败，请稍后重试。"); // 返回友好失败 JSON。
        } catch (Exception e) {
            log.error("[AnalyzeCallChainTool] analyze failed, path: {}", safeRelativePath(requestedPath), e); // 系统错误保留堆栈但不打印正文。
            return buildFailureResult(requestedPath, "调用链分析失败，请稍后重试。"); // 返回友好失败 JSON。
        }
    }

    // ===================== Java 调用链分析 =====================

    private boolean analyzeJava(List<String> lines,
                                String methodName,
                                ObjectNode result,
                                CallChainState state) { // Java 调用链主流程，返回指定方法是否命中。
        List<String> safeLines = lines == null ? List.of() : lines; // 空文件兜底。
        List<String> codeLines = stripComments(safeLines); // 去掉行注释和块注释，保留字符串字面量，避免逐行尾注释干扰正则。
        collectJavaHeader(codeLines, result); // 提取 packageName、imports 和 className。

        List<MethodScan> methods = scanJavaMethods(codeLines); // 扫描方法声明和方法体范围。
        Set<String> classMethodNames = new LinkedHashSet<>(); // 当前类自有方法名，用于区分内部调用。
        for (MethodScan method : methods) { // 收集方法名。
            classMethodNames.add(method.name); // 记录内部方法名。
        }

        String className = result.path("className").asText(""); // 读取已识别的类名，用于构造器依赖。
        Map<String, String> dependencyTypes = collectJavaDependencies(codeLines, methods, className, result); // 提取依赖对象并返回 fieldName -> type 映射。
        fillMethods(methods, result); // 写入方法列表 methods。

        boolean methodFound = false; // 标记指定方法是否命中。
        Map<String, List<String>> candidateCache = new LinkedHashMap<>(); // 候选目标文件缓存，避免重复扫描 workspace。
        for (MethodScan method : methods) { // 遍历方法体提取调用关系。
            if (!method.hasBody) { // 抽象/接口方法没有方法体。
                continue; // 跳过无方法体方法。
            }
            if (methodName != null && !methodName.equals(method.name)) { // 指定入口方法时只分析该方法。
                continue; // 跳过非目标方法。
            }
            if (methodName != null) { // 命中目标方法。
                methodFound = true; // 标记已找到。
            }
            extractMethodCalls(method, codeLines, classMethodNames, dependencyTypes, candidateCache, result, state); // 提取该方法内部/外部/工具/未解析调用。
        }
        return methodName == null || methodFound; // 未指定方法时恒为 true。
    }

    private List<String> stripComments(List<String> lines) { // 去掉 // 行注释和 /* */ 块注释，保留字符串与字符字面量内容，行数与原文件一致。
        List<String> result = new ArrayList<>(); // 去注释后的代码行。
        boolean inBlockComment = false; // 是否处于跨行块注释中。
        for (String line : lines) { // 逐行处理。
            StringBuilder builder = new StringBuilder(); // 当前行去注释后的代码。
            boolean inString = false; // 是否在双引号字符串内。
            boolean inChar = false; // 是否在单引号字符字面量内。
            int i = 0; // 当前字符位置。
            int length = line == null ? 0 : line.length(); // 行长度。
            while (i < length) { // 逐字符扫描。
                char ch = line.charAt(i); // 当前字符。
                char next = i + 1 < length ? line.charAt(i + 1) : '\0'; // 下一个字符。
                if (inBlockComment) { // 块注释内只找结束标记。
                    if (ch == '*' && next == '/') { // 命中 */。
                        inBlockComment = false; // 退出块注释。
                        i += 2; // 跳过 */。
                    } else {
                        i++; // 继续吞掉块注释内容。
                    }
                    continue; // 处理下一个字符。
                }
                if (inString) { // 字符串内原样保留。
                    builder.append(ch); // 写入字符。
                    if (ch == '\\' && i + 1 < length) { // 处理转义。
                        builder.append(next); // 写入被转义字符。
                        i += 2; // 跳过转义对。
                        continue; // 处理下一个字符。
                    }
                    if (ch == '"') { // 字符串结束。
                        inString = false; // 退出字符串。
                    }
                    i++; // 继续。
                    continue; // 处理下一个字符。
                }
                if (inChar) { // 字符字面量内原样保留。
                    builder.append(ch); // 写入字符。
                    if (ch == '\\' && i + 1 < length) { // 处理转义。
                        builder.append(next); // 写入被转义字符。
                        i += 2; // 跳过转义对。
                        continue; // 处理下一个字符。
                    }
                    if (ch == '\'') { // 字符字面量结束。
                        inChar = false; // 退出字符字面量。
                    }
                    i++; // 继续。
                    continue; // 处理下一个字符。
                }
                if (ch == '/' && next == '/') { // 行注释，丢弃本行剩余部分。
                    break; // 结束本行。
                }
                if (ch == '/' && next == '*') { // 块注释开始。
                    inBlockComment = true; // 进入块注释。
                    i += 2; // 跳过 /*。
                    continue; // 处理下一个字符。
                }
                if (ch == '"') { // 字符串开始。
                    inString = true; // 进入字符串。
                    builder.append(ch); // 写入引号。
                    i++; // 继续。
                    continue; // 处理下一个字符。
                }
                if (ch == '\'') { // 字符字面量开始。
                    inChar = true; // 进入字符字面量。
                    builder.append(ch); // 写入引号。
                    i++; // 继续。
                    continue; // 处理下一个字符。
                }
                builder.append(ch); // 普通代码字符直接保留。
                i++; // 继续。
            }
            result.add(builder.toString()); // 收集去注释后的代码行。
        }
        return result; // 返回去注释代码行。
    }

    private void collectJavaHeader(List<String> lines, ObjectNode result) { // 提取 Java 文件头部信息。
        ArrayNode imports = result.withArray("imports"); // 复用 imports 数组。
        for (String line : lines) { // 逐行扫描头部。
            Matcher packageMatcher = JAVA_PACKAGE_PATTERN.matcher(line); // 匹配 package。
            if (packageMatcher.find()) { // 命中 package。
                result.put("packageName", packageMatcher.group(1)); // 写入包名。
            }
            Matcher importMatcher = JAVA_IMPORT_PATTERN.matcher(line); // 匹配 import。
            if (importMatcher.find()) { // 命中 import。
                imports.add(importMatcher.group(1).trim()); // 写入 import。
            }
            if (result.path("className").asText("").isBlank()) { // 仅取第一个类/接口名。
                Matcher classMatcher = JAVA_CLASS_PATTERN.matcher(line); // 匹配类声明。
                if (classMatcher.find()) { // 命中类声明。
                    result.put("className", classMatcher.group(2)); // 写入类名。
                }
            }
        }
    }

    private List<MethodScan> scanJavaMethods(List<String> lines) { // 基于大括号计数扫描方法体范围。
        List<MethodScan> methods = new ArrayList<>(); // 保存方法扫描结果。
        int depth = 0; // 当前大括号深度。
        MethodScan current = null; // 当前正在收集方法体的方法。
        for (int i = 0; i < lines.size(); i++) { // 逐行扫描。
            String line = lines.get(i); // 当前行。
            String trimmed = line.trim(); // 去空白后的行。
            if (current == null && depth == 1) { // 仅在类体层级（depth==1）识别方法声明。
                Matcher methodMatcher = JAVA_METHOD_PATTERN.matcher(line); // 匹配方法声明。
                if (methodMatcher.matches() && isJavaMethodLine(trimmed, methodMatcher)) { // 命中且不是控制语句或字段。
                    MethodScan scan = new MethodScan(safeText(methodMatcher.group(3)), i + 1,
                            compactSpaces(methodMatcher.group(2)), compactSpaces(methodMatcher.group(4)), i); // 记录方法名、行号、返回类型、参数。
                    if (trimmed.endsWith(";")) { // 抽象方法或接口方法没有方法体。
                        scan.hasBody = false; // 标记无方法体。
                        scan.bodyEndIndex = i; // 方法体结束行等于声明行。
                        methods.add(scan); // 直接登记。
                    } else { // 普通方法进入方法体收集。
                        scan.openDepth = depth; // 记录开口深度（类体层级为 1）。
                        current = scan; // 设为当前方法。
                    }
                }
            }
            depth += braceDelta(line); // 应用当前行的大括号增量。
            if (current != null && depth <= current.openDepth) { // 方法体闭合，深度回到开口层级。
                current.bodyEndIndex = i; // 记录方法体结束行。
                current.hasBody = true; // 标记有方法体。
                methods.add(current); // 登记方法。
                current = null; // 清空当前方法。
            }
        }
        return methods; // 返回方法扫描结果。
    }

    private Map<String, String> collectJavaDependencies(List<String> lines,
                                                        List<MethodScan> methods,
                                                        String className,
                                                        ObjectNode result) { // 提取依赖对象，返回 fieldName -> type 映射。
        ArrayNode dependencies = result.withArray("dependencies"); // 复用 dependencies 数组。
        Map<String, String> dependencyTypes = new LinkedHashMap<>(); // 字段名到类型的映射，供调用分类使用。

        int depth = 0; // 大括号深度，用于只取类体层级字段。
        List<String> pendingAnnotations = new ArrayList<>(); // 字段上方的注解，用于识别注入类型。
        for (int i = 0; i < lines.size(); i++) { // 逐行扫描字段。
            String line = lines.get(i); // 当前行。
            String trimmed = line.trim(); // 去空白后的行。
            if (depth <= 1 && trimmed.startsWith("@")) { // 类体层级的注解先暂存。
                pendingAnnotations.add(trimmed); // 暂存注解。
                depth += braceDelta(line); // 更新深度。
                continue; // 处理下一行。
            }
            if (depth == 1) { // 仅识别类体层级（depth==1）的字段声明。
                Matcher fieldMatcher = JAVA_FIELD_PATTERN.matcher(line); // 匹配字段声明。
                if (fieldMatcher.matches() && !trimmed.contains("(")) { // 命中字段且不是方法。
                    String type = compactSpaces(fieldMatcher.group(3)); // 字段类型。
                    String fieldName = safeText(fieldMatcher.group(4)); // 字段名。
                    if (isLikelyDependency(type, fieldName)) { // 仅保留类类型的实例依赖对象。
                        String injectionType = resolveInjectionType(pendingAnnotations, fieldMatcher.group(2) != null); // 识别注入方式。
                        addDependency(dependencies, dependencyTypes, fieldName, simpleTypeName(type), injectionType, i + 1); // 登记依赖，行号取当前行。
                    }
                }
            }
            if (!trimmed.isBlank() && !trimmed.startsWith("//") && !trimmed.startsWith("*")) { // 非注释行重置待处理注解。
                pendingAnnotations.clear(); // 清空注解缓存。
            }
            depth += braceDelta(line); // 更新深度。
        }

        if (className != null && !className.isBlank()) { // 类名可用时再补构造器参数注入。
            for (MethodScan method : methods) { // 遍历方法找构造器。
                if (className.equals(method.name) && method.parameters != null && !method.parameters.isBlank()) { // 方法名等于类名即构造器。
                    collectConstructorParams(method.parameters, method.lineNumber, dependencies, dependencyTypes); // 提取构造器参数依赖。
                }
            }
        }
        return dependencyTypes; // 返回依赖类型映射。
    }

    private void collectConstructorParams(String parameters,
                                          int lineNumber,
                                          ArrayNode dependencies,
                                          Map<String, String> dependencyTypes) { // 提取构造器参数作为依赖对象。
        for (String rawParam : splitTopLevel(parameters)) { // 逐个参数解析。
            String param = stripParamAnnotations(rawParam).trim(); // 去掉 @Qualifier 等注解前缀。
            if (param.isBlank()) { // 空参数跳过。
                continue; // 处理下一个。
            }
            int lastSpace = param.lastIndexOf(' '); // 类型和参数名以空格分隔。
            if (lastSpace <= 0) { // 没有空格说明无法解析类型和名称。
                continue; // 跳过。
            }
            String type = compactSpaces(param.substring(0, lastSpace)); // 参数类型。
            String paramName = safeText(param.substring(lastSpace + 1)); // 参数名。
            if (!isLikelyDependency(type, paramName) || dependencyTypes.containsKey(paramName)) { // 仅保留类类型且未登记过的依赖。
                continue; // 跳过非依赖或重复项。
            }
            addDependency(dependencies, dependencyTypes, paramName, simpleTypeName(type), "CONSTRUCTOR", lineNumber); // 登记构造器注入依赖。
        }
    }

    private void extractMethodCalls(MethodScan method,
                                    List<String> lines,
                                    Set<String> classMethodNames,
                                    Map<String, String> dependencyTypes,
                                    Map<String, List<String>> candidateCache,
                                    ObjectNode result,
                                    CallChainState state) { // 提取单个方法体内的调用关系。
        ArrayNode internalCalls = result.withArray("internalCalls"); // 内部方法调用。
        ArrayNode externalCalls = result.withArray("externalCalls"); // 依赖对象方法调用。
        ArrayNode utilityCalls = result.withArray("utilityCalls"); // 基础/工具类调用。
        ArrayNode unresolvedCalls = result.withArray("unresolvedCalls"); // 无法确定目标的调用。
        Set<String> seen = new LinkedHashSet<>(); // 去重，避免同行重复计数。

        for (int i = method.bodyStartIndex; i <= method.bodyEndIndex && i < lines.size(); i++) { // 遍历方法体每一行。
            String line = lines.get(i); // 当前行。
            int lineNumber = i + 1; // 行号从 1 开始。
            Matcher matcher = JAVA_CALL_PATTERN.matcher(line); // 匹配调用候选。
            while (matcher.find()) { // 逐个调用候选。
                if (state.reachedLimit()) { // 达到调用项上限。
                    state.truncated = true; // 标记截断。
                    return; // 停止提取。
                }
                String receiver = matcher.group(1); // 调用对象，可能为空。
                String callee = matcher.group(2); // 被调用方法名。
                if (callee == null || JAVA_CALL_KEYWORDS.contains(callee)) { // 排除关键字。
                    continue; // 跳过。
                }
                if (i == method.bodyStartIndex && receiver == null && callee.equals(method.name)) { // 跳过方法声明本身。
                    continue; // 跳过声明行的方法名。
                }
                if (isPrecededByNew(line, matcher.start())) { // 排除 new Xxx() 构造调用。
                    continue; // 跳过对象创建。
                }
                String callExpression = extractCallExpression(line, receiver == null ? matcher.start(2) : matcher.start(1)); // 提取调用表达式片段。
                String dedupKey = method.name + "|" + (receiver == null ? "" : receiver) + "|" + callee + "|" + lineNumber; // 去重键。
                if (!seen.add(dedupKey)) { // 同方法同行重复调用只记一次。
                    continue; // 跳过重复。
                }
                classifyJavaCall(method.name, receiver, callee, lineNumber, callExpression, classMethodNames,
                        dependencyTypes, candidateCache, internalCalls, externalCalls, utilityCalls, unresolvedCalls, state); // 分类并写入对应数组。
            }
        }
    }

    private void classifyJavaCall(String fromMethod,
                                  String receiver,
                                  String callee,
                                  int lineNumber,
                                  String callExpression,
                                  Set<String> classMethodNames,
                                  Map<String, String> dependencyTypes,
                                  Map<String, List<String>> candidateCache,
                                  ArrayNode internalCalls,
                                  ArrayNode externalCalls,
                                  ArrayNode utilityCalls,
                                  ArrayNode unresolvedCalls,
                                  CallChainState state) { // 将单个调用候选分类为内部/外部/工具/未解析调用。
        if (receiver == null) { // 无调用对象的裸调用。
            if (classMethodNames.contains(callee)) { // 是当前类自有方法。
                addInternalCall(internalCalls, fromMethod, callee, lineNumber, callExpression, state); // 记为内部调用。
            }
            return; // 其它裸调用（构造器/静态导入/链式）第一版不强行解析。
        }
        if ("this".equals(receiver)) { // this.method() 一定是内部调用。
            addInternalCall(internalCalls, fromMethod, callee, lineNumber, callExpression, state); // 记为内部调用。
            return; // 处理结束。
        }
        if (dependencyTypes.containsKey(receiver)) { // 调用对象是已识别的依赖字段。
            String targetType = dependencyTypes.get(receiver); // 依赖类型。
            ObjectNode call = buildCall(fromMethod, lineNumber, callExpression, state.includeSnippet); // 构造外部调用节点。
            call.put("objectName", receiver); // 调用对象名。
            call.put("targetType", targetType); // 目标类型。
            call.put("methodName", callee); // 被调用方法名。
            call.set("candidateTargets", buildCandidateTargets(targetType, candidateCache)); // 候选目标文件。
            addLimited(externalCalls, call, state); // 写入外部调用。
            return; // 处理结束。
        }
        if (UTILITY_RECEIVERS.contains(receiver)) { // 调用对象是常见基础/工具类。
            ObjectNode call = buildCall(fromMethod, lineNumber, callExpression, state.includeSnippet); // 构造工具调用节点。
            call.put("objectName", receiver); // 调用对象名。
            call.put("methodName", callee); // 被调用方法名。
            addLimited(utilityCalls, call, state); // 写入工具调用。
            return; // 处理结束。
        }
        ObjectNode call = buildCall(fromMethod, lineNumber, callExpression, state.includeSnippet); // 构造未解析调用节点。
        call.put("objectName", receiver); // 调用对象名（局部变量或未识别对象）。
        call.put("methodName", callee); // 被调用方法名。
        addLimited(unresolvedCalls, call, state); // 写入未解析调用。
    }

    private void addInternalCall(ArrayNode internalCalls,
                                 String fromMethod,
                                 String toMethod,
                                 int lineNumber,
                                 String callExpression,
                                 CallChainState state) { // 写入内部方法调用。
        ObjectNode call = objectMapper.createObjectNode(); // 创建调用节点。
        call.put("fromMethod", fromMethod); // 来源方法。
        call.put("toMethod", toMethod); // 目标方法。
        call.put("lineNumber", lineNumber); // 行号。
        call.put("callExpression", callExpression); // 调用表达式。
        addLimited(internalCalls, call, state); // 写入内部调用数组。
    }

    private ObjectNode buildCall(String fromMethod,
                                 int lineNumber,
                                 String callExpression,
                                 boolean includeSnippet) { // 构造外部/工具/未解析调用的公共字段。
        ObjectNode call = objectMapper.createObjectNode(); // 创建调用节点。
        call.put("fromMethod", fromMethod); // 来源方法。
        call.put("lineNumber", lineNumber); // 行号。
        if (includeSnippet) { // 仅在需要片段时输出调用表达式。
            call.put("callExpression", callExpression); // 调用表达式片段。
        }
        return call; // 返回调用节点。
    }

    private ArrayNode buildCandidateTargets(String targetType,
                                            Map<String, List<String>> candidateCache) { // 根据依赖类型解析候选目标文件路径。
        ArrayNode targets = objectMapper.createArrayNode(); // 候选目标数组。
        if (targetType == null || targetType.isBlank()) { // 没有类型无法解析。
            return targets; // 返回空数组。
        }
        List<String> cached = candidateCache.get(targetType); // 读取缓存，避免重复扫描 workspace。
        if (cached == null) { // 缓存未命中时扫描一次。
            cached = findCandidateTargets(targetType); // 按文件名搜索候选。
            candidateCache.put(targetType, cached); // 写入缓存。
        }
        for (String path : cached) { // 写入候选路径。
            targets.add(path); // 加入数组。
        }
        return targets; // 返回候选目标。
    }

    private List<String> findCandidateTargets(String simpleType) { // 仅按文件名在 workspace 内搜索候选目标文件。
        Set<String> candidateNames = new LinkedHashSet<>(); // 候选文件名集合，小写匹配。
        candidateNames.add((simpleType + ".java").toLowerCase(Locale.ROOT)); // 接口/类本体。
        if (!simpleType.endsWith("Impl")) { // 接口常见实现类后缀。
            candidateNames.add((simpleType + "Impl.java").toLowerCase(Locale.ROOT)); // 默认实现类。
        }
        List<Path> matches = new ArrayList<>(); // 命中的文件路径。
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // workspace 根目录。
        if (Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) { // workspace 存在时才扫描。
            collectFilesByNames(workspaceRoot, candidateNames, matches); // 递归收集候选文件。
        }
        List<String> relativePaths = new ArrayList<>(); // 候选相对路径。
        matches.sort(Comparator.comparing(path -> toWorkspaceRelativePath(path, ""), String.CASE_INSENSITIVE_ORDER)); // 稳定排序。
        for (Path match : matches) { // 转换为相对路径。
            if (relativePaths.size() >= MAX_CANDIDATE_TARGETS) { // 最多返回 5 个候选。
                break; // 达到上限。
            }
            relativePaths.add(toWorkspaceRelativePath(match, "")); // 加入相对路径。
        }
        return relativePaths; // 返回候选路径。
    }

    private void collectFilesByNames(Path directory, Set<String> candidateNamesLower, List<Path> matches) { // 递归收集匹配文件名的安全文件。
        if (matches.size() >= MAX_FILENAME_MATCHES || !isSafeDirectory(directory)) { // 达到上限或目录不安全时停止。
            return; // 不进入递归。
        }
        try (Stream<Path> stream = Files.list(directory)) { // 只列当前目录。
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName() == null ? "" : path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList(); // 目录项稳定排序。
            for (Path child : children) { // 遍历子项。
                if (matches.size() >= MAX_FILENAME_MATCHES) { // 达到上限。
                    return; // 停止收集。
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { // 子目录继续递归。
                    collectFilesByNames(child, candidateNamesLower, matches); // 递归扫描。
                    continue; // 处理下一个。
                }
                String childName = child.getFileName() == null ? "" : child.getFileName().toString(); // 子文件名。
                if (candidateNamesLower.contains(childName.toLowerCase(Locale.ROOT)) && isSafeCandidateFile(child)) { // 文件名命中且安全。
                    matches.add(child.toAbsolutePath().normalize()); // 登记候选文件。
                }
            }
        } catch (IOException e) {
            log.debug("[AnalyzeCallChainTool] skip unreadable directory while resolving candidate targets"); // 不打印目录绝对路径。
        }
    }

    // ===================== 其它语言基础支持 =====================

    private void analyzeBasicLanguage(String extension,
                                      List<String> lines,
                                      ObjectNode result,
                                      CallChainState state) { // 非 Java 语言只做基础函数与调用提取。
        ArrayNode methods = result.withArray("methods"); // 复用 methods 数组保存函数。
        ArrayNode basicCalls = result.withArray("basicCalls"); // 非 Java 的基础调用候选。
        Pattern functionPattern = resolveBasicFunctionPattern(extension); // 选择对应语言的函数声明规则。
        List<String> safeLines = lines == null ? List.of() : lines; // 空文件兜底。
        for (int i = 0; i < safeLines.size(); i++) { // 逐行扫描。
            String line = safeLines.get(i); // 当前行。
            int lineNumber = i + 1; // 行号。
            if (functionPattern != null) { // 命中语言函数规则。
                Matcher functionMatcher = functionPattern.matcher(line); // 匹配函数声明。
                if (functionMatcher.find()) { // 命中函数。
                    ObjectNode methodNode = objectMapper.createObjectNode(); // 创建函数节点。
                    methodNode.put("name", safeText(functionMatcher.group(1))); // 函数名。
                    methodNode.put("lineNumber", lineNumber); // 行号。
                    addLimited(methods, methodNode, state); // 写入 methods。
                }
            }
            Matcher callMatcher = GENERIC_CALL_PATTERN.matcher(line); // 匹配基础调用候选。
            while (callMatcher.find()) { // 逐个调用候选。
                if (state.reachedLimit()) { // 达到调用项上限。
                    state.truncated = true; // 标记截断。
                    return; // 停止提取。
                }
                String receiver = callMatcher.group(1); // 调用对象。
                String callee = callMatcher.group(2); // 被调用名。
                if (callee == null || JAVA_CALL_KEYWORDS.contains(callee)) { // 排除关键字。
                    continue; // 跳过。
                }
                ObjectNode call = objectMapper.createObjectNode(); // 创建基础调用节点。
                if (receiver != null) { // 有调用对象时记录。
                    call.put("objectName", receiver); // 调用对象名。
                }
                call.put("methodName", callee); // 被调用名。
                call.put("lineNumber", lineNumber); // 行号。
                addLimited(basicCalls, call, state); // 写入基础调用。
            }
        }
    }

    private Pattern resolveBasicFunctionPattern(String extension) { // 根据扩展名选择基础函数规则。
        if ("py".equals(extension)) { // Python。
            return PY_FUNCTION_PATTERN; // 返回 def 规则。
        }
        if ("js".equals(extension) || "ts".equals(extension) || "jsx".equals(extension) || "tsx".equals(extension)) { // JS/TS。
            return JS_FUNCTION_PATTERN; // 返回 function 规则。
        }
        if ("go".equals(extension)) { // Go。
            return GO_FUNCTION_PATTERN; // 返回 func 规则。
        }
        return null; // 其它语言不提取函数声明，只提取基础调用。
    }

    // ===================== 结果构造与安全辅助 =====================

    private ObjectNode buildBaseSuccessResult(Path filePath,
                                              String requestedPath,
                                              String fileName,
                                              String extension,
                                              String language,
                                              String methodName,
                                              int maxDepth,
                                              List<String> lines) throws IOException { // 构造成功结果骨架。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", true); // 标记成功。
        result.put("path", toWorkspaceRelativePath(filePath, requestedPath)); // 只返回相对 workspace 路径。
        result.put("fileName", fileName); // 文件名。
        result.put("extension", extension); // 扩展名。
        result.put("language", language); // 语言展示名。
        result.putNull("packageName"); // 包名默认空。
        result.putNull("className"); // 类名默认空。
        result.put("entryMethod", methodName == null ? "" : methodName); // 入口方法，未指定时为空。
        result.put("maxDepth", maxDepth); // 调用链分析深度。
        result.put("lineCount", lines == null ? 0 : lines.size()); // 行数。
        result.put("fileSize", Files.size(filePath)); // 文件字节大小。
        result.set("imports", objectMapper.createArrayNode()); // imports 数组。
        result.set("dependencies", objectMapper.createArrayNode()); // 依赖对象数组。
        result.set("methods", objectMapper.createArrayNode()); // 方法数组。
        result.set("internalCalls", objectMapper.createArrayNode()); // 内部调用数组。
        result.set("externalCalls", objectMapper.createArrayNode()); // 外部调用数组。
        result.set("utilityCalls", objectMapper.createArrayNode()); // 工具调用数组。
        result.set("unresolvedCalls", objectMapper.createArrayNode()); // 未解析调用数组。
        ArrayNode warnings = objectMapper.createArrayNode(); // 不确定性说明数组。
        warnings.add(ANALYSIS_WARNING); // 写入统一警告。
        result.set("warnings", warnings); // 挂到结果。
        result.put("truncated", false); // 默认未截断。
        return result; // 返回结果骨架。
    }

    private void fillMethods(List<MethodScan> methods, ObjectNode result) { // 写入方法列表。
        ArrayNode methodArray = result.withArray("methods"); // 复用 methods 数组。
        for (MethodScan method : methods) { // 遍历方法。
            ObjectNode node = objectMapper.createObjectNode(); // 创建方法节点。
            node.put("name", method.name); // 方法名。
            node.put("lineNumber", method.lineNumber); // 声明行号。
            node.put("returnType", method.returnType == null ? "" : method.returnType); // 返回类型。
            node.put("parameters", method.parameters == null ? "" : method.parameters); // 参数列表。
            methodArray.add(node); // 写入方法数组。
        }
    }

    private void addDependency(ArrayNode dependencies,
                               Map<String, String> dependencyTypes,
                               String fieldName,
                               String type,
                               String injectionType,
                               int lineNumber) { // 登记单个依赖对象。
        if (dependencyTypes.containsKey(fieldName)) { // 同名字段只登记一次。
            return; // 跳过重复。
        }
        ObjectNode node = objectMapper.createObjectNode(); // 创建依赖节点。
        node.put("fieldName", fieldName); // 字段名。
        node.put("type", type); // 字段类型。
        node.put("injectionType", injectionType); // 注入方式。
        node.put("lineNumber", lineNumber); // 行号。
        dependencies.add(node); // 写入依赖数组。
        dependencyTypes.put(fieldName, type); // 记录字段名到类型映射。
    }

    private String resolveInjectionType(List<String> annotations, boolean isFinal) { // 根据注解和 final 判断注入方式。
        if (hasAnnotation(annotations, "Autowired")) { // 字段上有 @Autowired。
            return "AUTOWIRED"; // 字段注入。
        }
        if (hasAnnotation(annotations, "Resource")) { // 字段上有 @Resource。
            return "RESOURCE"; // 资源注入。
        }
        return isFinal ? "FIELD_OR_CONSTRUCTOR" : "FIELD"; // final 字段通常由构造器注入。
    }

    private boolean hasAnnotation(List<String> annotations, String annotationName) { // 判断注解集合是否包含指定注解。
        if (annotations == null) { // 空集合不命中。
            return false; // 返回 false。
        }
        for (String annotation : annotations) { // 遍历注解。
            if (annotation != null && annotation.contains("@" + annotationName)) { // 命中注解名。
                return true; // 返回 true。
            }
        }
        return false; // 未命中。
    }

    private boolean isLikelyDependency(String type, String fieldName) { // 判断字段是否是类类型的依赖对象。
        if (type == null || type.isBlank() || fieldName == null || fieldName.isBlank()) { // 空值不是依赖。
            return false; // 返回 false。
        }
        if (fieldName.matches("[A-Z0-9_]+")) { // 全大写字段是常量，不是依赖对象。
            return false; // 返回 false。
        }
        String simpleType = simpleTypeName(type); // 取简单类型名。
        if (simpleType.isBlank() || !Character.isUpperCase(simpleType.charAt(0))) { // 类型首字母非大写说明不是类类型。
            return false; // 返回 false。
        }
        return !COMMON_VALUE_TYPES.contains(simpleType); // 排除 String、集合等常见值类型。
    }

    private String simpleTypeName(String type) { // 提取去泛型、去包名后的简单类型名。
        if (type == null) { // 空类型。
            return ""; // 返回空。
        }
        String normalized = type.trim(); // 去空白。
        int genericIndex = normalized.indexOf('<'); // 去掉泛型部分。
        if (genericIndex > 0) { // 命中泛型。
            normalized = normalized.substring(0, genericIndex); // 截断泛型。
        }
        int dotIndex = normalized.lastIndexOf('.'); // 去掉包名前缀。
        if (dotIndex >= 0 && dotIndex < normalized.length() - 1) { // 命中包名。
            normalized = normalized.substring(dotIndex + 1); // 取简单名。
        }
        return normalized.trim(); // 返回简单类型名。
    }

    private Path resolveReadableAnalysisFilePath(String requestedPath) { // 解析明确路径或在文件名/类名场景下安全唯一定位项目文件。
        Path directPath = projectPathGuard.resolveProjectPath(requestedPath); // 必须先走 P4.1 路径解析，防止绝对路径和路径穿越。
        if (Files.exists(directPath, LinkOption.NOFOLLOW_LINKS) || !isFilenameOnly(requestedPath)) { // 明确路径或根目录文件存在时直接返回。
            return directPath; // 后续交给 validateReadableCodeFile 做完整安全校验。
        }
        String fileName = normalizeFileNameCandidate(requestedPath); // 类名直传时补成 Xxx.java。
        String extension = projectPathGuard.getExtension(fileName); // 提取候选扩展名。
        if (projectPathGuard.isBlockedFilename(fileName) || projectPathGuard.isBlockedExtension(extension)) { // 敏感文件不能进入全 workspace 搜索。
            throw new ProjectPathGuard.ProjectPathAccessException("该文件属于敏感文件，禁止读取。"); // 返回统一敏感文件文案。
        }
        if (!extension.isEmpty() && !projectPathGuard.isAllowedExtension(extension)) { // 明确扩展名但不在白名单。
            throw new ProjectPathGuard.ProjectPathAccessException("不支持读取该文件类型。"); // 返回统一不支持类型文案。
        }
        List<Path> matches = findProjectFilesByName(fileName); // 在 workspace 内安全搜索同名项目文件。
        if (matches.isEmpty()) { // 未找到任何安全候选。
            throw new ProjectPathGuard.ProjectPathAccessException("未在 workspace 中找到 " + fileName + "，请先使用 searchCode 定位文件位置或提供完整相对路径。"); // 友好提示先定位。
        }
        if (matches.size() > 1) { // 同名文件不止一个时不能乱猜。
            throw new ProjectPathGuard.ProjectPathAccessException(buildAmbiguousFileMessage(fileName, matches)); // 返回相对路径候选。
        }
        return matches.get(0); // 唯一匹配时进入分析流程。
    }

    private String normalizeFileNameCandidate(String requestedPath) { // 将文件名或类名候选标准化为具体文件名。
        String fileName = trimToNull(requestedPath); // 去空白。
        if (fileName == null) { // 理论上外层已校验。
            return ""; // 返回空。
        }
        if (projectPathGuard.isBlockedFilename(fileName)) { // 敏感文件名不能被自动补后缀绕过。
            return fileName; // 原样返回。
        }
        if (!fileName.contains(".") && fileName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { // 类名直传且没有扩展名。
            return fileName + ".java"; // 默认按 Java 类名补 .java。
        }
        return fileName; // 其它文件名原样使用。
    }

    private List<Path> findProjectFilesByName(String fileName) { // 在 workspace 内按文件名搜索安全候选。
        List<Path> matches = new ArrayList<>(); // 候选列表。
        String normalizedFileName = trimToNull(fileName); // 标准化文件名。
        if (normalizedFileName == null) { // 空文件名无法搜索。
            return matches; // 返回空列表。
        }
        Set<String> targetNames = Set.of(normalizedFileName.toLowerCase(Locale.ROOT)); // 单文件名集合。
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // workspace 根路径。
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) { // workspace 不存在。
            return matches; // 返回空列表。
        }
        collectFilesByNames(workspaceRoot, targetNames, matches); // 递归收集安全候选。
        matches.sort(Comparator.comparing(path -> toWorkspaceRelativePath(path, ""), String.CASE_INSENSITIVE_ORDER)); // 稳定排序。
        return matches; // 返回候选。
    }

    private boolean isSafeDirectory(Path directory) { // 判断目录是否可进入扫描。
        if (directory == null) { // 空目录不安全。
            return false; // 返回 false。
        }
        Path normalizedPath = directory.toAbsolutePath().normalize(); // 标准化目录路径。
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 必须在 workspace 内、非软链、非敏感目录。
    }

    private boolean isSafeCandidateFile(Path filePath) { // 判断候选文件是否安全可返回。
        if (filePath == null || filePath.getFileName() == null) { // 空路径不安全。
            return false; // 返回 false。
        }
        Path normalizedPath = filePath.toAbsolutePath().normalize(); // 标准化路径。
        String candidateName = filePath.getFileName().toString(); // 文件名。
        String extension = projectPathGuard.getExtension(candidateName); // 扩展名。
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !projectPathGuard.isBlockedFilename(candidateName)
                && !projectPathGuard.isBlockedExtension(extension)
                && projectPathGuard.isAllowedExtension(extension)
                && Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 只允许安全白名单文件。
    }

    private String buildAmbiguousFileMessage(String fileName, List<Path> matches) { // 构造同名文件多匹配提示。
        StringBuilder builder = new StringBuilder("找到多个 ").append(fileName).append("，请指定要分析哪一个："); // 不自动选择。
        int index = 1; // 候选序号。
        for (Path match : matches) { // 输出候选。
            builder.append('\n').append(index++).append(". ").append(toWorkspaceRelativePath(match, "")); // 只输出相对路径。
        }
        return builder.toString(); // 返回提示。
    }

    private boolean isFilenameOnly(String path) { // 判断用户是否只传了文件名。
        String normalizedPath = trimToNull(path); // 标准化。
        if (normalizedPath == null) { // 空路径不是文件名。
            return false; // 返回 false。
        }
        return !normalizedPath.contains("/") && !normalizedPath.contains("\\"); // 不含分隔符即视为文件名。
    }

    private String extractCallExpression(String line, int startIndex) { // 从调用起点提取平衡括号内的调用表达式片段。
        if (line == null || startIndex < 0 || startIndex >= line.length()) { // 越界兜底。
            return ""; // 返回空。
        }
        int open = line.indexOf('(', startIndex); // 找到第一个左括号。
        if (open < 0) { // 没有左括号。
            return abbreviate(line.substring(startIndex).trim()); // 返回剩余片段。
        }
        int depth = 0; // 括号深度。
        for (int i = open; i < line.length(); i++) { // 从左括号开始平衡匹配。
            char ch = line.charAt(i); // 当前字符。
            if (ch == '(') { // 左括号。
                depth++; // 深度加一。
            } else if (ch == ')') { // 右括号。
                depth--; // 深度减一。
                if (depth == 0) { // 平衡闭合。
                    return abbreviate(line.substring(startIndex, i + 1).trim()); // 返回完整调用表达式。
                }
            }
        }
        return abbreviate(line.substring(startIndex).trim()); // 同行未闭合时返回剩余片段。
    }

    private boolean isPrecededByNew(String line, int matchStart) { // 判断调用候选前是否紧跟 new 关键字。
        if (line == null || matchStart <= 0) { // 行首不可能是 new 调用。
            return false; // 返回 false。
        }
        String prefix = line.substring(0, matchStart).trim(); // 调用前的文本。
        return prefix.endsWith("new"); // 以 new 结尾说明是构造调用。
    }

    private List<String> splitTopLevel(String parameters) { // 按顶层逗号拆分参数，忽略泛型内的逗号。
        List<String> parts = new ArrayList<>(); // 拆分结果。
        if (parameters == null || parameters.isBlank()) { // 空参数。
            return parts; // 返回空列表。
        }
        int depth = 0; // 泛型尖括号深度。
        StringBuilder current = new StringBuilder(); // 当前参数缓冲。
        for (int i = 0; i < parameters.length(); i++) { // 逐字符扫描。
            char ch = parameters.charAt(i); // 当前字符。
            if (ch == '<') { // 进入泛型。
                depth++; // 深度加一。
            } else if (ch == '>') { // 退出泛型。
                depth--; // 深度减一。
            }
            if (ch == ',' && depth <= 0) { // 顶层逗号分隔参数。
                parts.add(current.toString()); // 收集当前参数。
                current.setLength(0); // 重置缓冲。
                continue; // 处理下一个字符。
            }
            current.append(ch); // 累积当前参数字符。
        }
        if (current.length() > 0) { // 收尾最后一个参数。
            parts.add(current.toString()); // 加入结果。
        }
        return parts; // 返回拆分结果。
    }

    private String stripParamAnnotations(String param) { // 去掉构造器参数前的注解，例如 @Qualifier("x")。
        if (param == null) { // 空参数。
            return ""; // 返回空。
        }
        return param.replaceAll("@[A-Za-z_$][\\w$]*(?:\\([^)]*\\))?", " ").trim(); // 去掉注解后保留类型和参数名。
    }

    private boolean isJavaMethodLine(String trimmed, Matcher methodMatcher) { // 判断方法正则命中行是否真的是方法声明。
        if (trimmed == null || trimmed.isBlank() || trimmed.contains("=")) { // 含赋值号的不是方法。
            return false; // 返回 false。
        }
        String name = methodMatcher.group(3); // 方法名。
        String returnType = methodMatcher.group(2); // 返回类型。
        return !isJavaKeyword(name) && !isJavaKeyword(returnType) && !trimmed.startsWith("new "); // 排除控制语句和 new 表达式。
    }

    private boolean isJavaKeyword(String value) { // 判断是否是常见 Java 控制关键字。
        if (value == null) { // 空值视为关键字，拒绝。
            return true; // 返回 true。
        }
        return Set.of("if", "for", "while", "switch", "catch", "return", "throw", "new", "else", "do", "try")
                .contains(value.trim()); // 命中控制关键字。
    }

    private int braceDelta(String line) { // 计算一行的大括号净增量，忽略字符串中的括号。
        int delta = 0; // 净增量。
        boolean inString = false; // 是否在字符串内。
        char previous = 0; // 上一个字符。
        for (int i = 0; line != null && i < line.length(); i++) { // 逐字符扫描。
            char ch = line.charAt(i); // 当前字符。
            if (ch == '"' && previous != '\\') { // 进出字符串。
                inString = !inString; // 翻转状态。
            }
            if (!inString && ch == '{') { // 字符串外的左括号。
                delta++; // 增量加一。
            } else if (!inString && ch == '}') { // 字符串外的右括号。
                delta--; // 增量减一。
            }
            previous = ch; // 推进上一个字符。
        }
        return delta; // 返回净增量。
    }

    private void addLimited(ArrayNode arrayNode, ObjectNode item, CallChainState state) { // 写入调用项并执行 maxItems 限制。
        if (arrayNode == null || item == null || state == null) { // 空值兜底。
            return; // 直接返回。
        }
        if (state.reachedLimit()) { // 达到上限。
            state.truncated = true; // 标记截断。
            return; // 不再写入。
        }
        arrayNode.add(item); // 写入数组。
        state.items++; // 计数加一。
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

    private String toWorkspaceRelativePath(Path filePath, String fallbackPath) { // 将绝对路径转换为相对 workspace 路径。
        try {
            Path workspaceRoot = projectPathGuard.getWorkspaceRoot().toAbsolutePath().normalize(); // workspace 根目录。
            Path normalizedPath = filePath.toAbsolutePath().normalize(); // 标准化目标路径。
            if (normalizedPath.startsWith(workspaceRoot)) { // 路径位于 workspace 内。
                return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/'); // 返回统一斜杠相对路径。
            }
        } catch (Exception ignored) {
            // 解析失败时回退到入参路径形态。
        }
        return safeRelativePath(fallbackPath); // 返回兜底相对路径。
    }

    private String safeRelativePath(String value) { // 标准化日志和 JSON 中的路径。
        return value == null ? "" : value.replace('\\', '/'); // 不做绝对路径展开。
    }

    private String normalizeFailureMessage(String message) { // 规范化失败文案。
        if (message == null || message.isBlank()) { // 缺失时兜底。
            return "调用链分析失败。"; // 友好兜底。
        }
        if (message.contains("workspace 外")) { // 路径穿越或绝对路径。
            return "不允许访问 workspace 外的路径。"; // 不暴露真实路径。
        }
        return message; // 其它受控错误保持原文案。
    }

    private String buildFailureResult(String path, String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 创建结果对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", false); // 标记失败。
        result.put("path", safeRelativePath(path)); // 写入相对路径形态。
        result.put("message", message == null || message.isBlank() ? "调用链分析失败，请稍后重试。" : message); // 写入友好错误。
        return result.toString(); // 返回 JSON 字符串。
    }

    private String abbreviate(String value) { // 限制调用表达式长度。
        String text = value == null ? "" : value; // 空值兜底。
        if (text.length() <= MAX_CALL_EXPRESSION_CHARS) { // 未超长。
            return text; // 原样返回。
        }
        return text.substring(0, MAX_CALL_EXPRESSION_CHARS) + "...[truncated]"; // 截断并标记。
    }

    private String compactSpaces(String value) { // 压缩多余空白。
        return value == null ? "" : value.trim().replaceAll("\\s+", " "); // 合并空白并去首尾。
    }

    private String safeText(String value) { // 去空白兜底。
        return value == null ? "" : value.trim(); // 返回去空白文本。
    }

    private String trimToNull(String value) { // 将空白字符串统一转换为 null。
        return value == null || value.trim().isEmpty() ? null : value.trim(); // 空白返回 null。
    }

    /**
     * 方法体扫描结果，仅用于调用链提取，不对外暴露。
     */
    private static final class MethodScan { // 单个方法的扫描信息。
        private final String name; // 方法名。
        private final int lineNumber; // 声明行号。
        private final String returnType; // 返回类型。
        private final String parameters; // 参数列表。
        private final int bodyStartIndex; // 方法体起始行索引（含声明行）。
        private int bodyEndIndex; // 方法体结束行索引。
        private int openDepth; // 方法体开口时的大括号深度。
        private boolean hasBody; // 是否有方法体。

        private MethodScan(String name, int lineNumber, String returnType, String parameters, int bodyStartIndex) { // 构造方法扫描结果。
            this.name = name; // 保存方法名。
            this.lineNumber = lineNumber; // 保存声明行号。
            this.returnType = returnType; // 保存返回类型。
            this.parameters = parameters; // 保存参数列表。
            this.bodyStartIndex = bodyStartIndex; // 保存方法体起始索引。
            this.bodyEndIndex = bodyStartIndex; // 默认结束索引等于起始索引。
        }
    }

    /**
     * 调用链提取状态，统一控制 maxItems 上限和片段开关。
     */
    private static final class CallChainState { // 调用项计数与截断状态。
        private final int maxItems; // 调用项上限。
        private final boolean includeSnippet; // 是否输出调用表达式片段。
        private int items; // 已写入调用项数量。
        private boolean truncated; // 是否截断。

        private CallChainState(int maxItems, boolean includeSnippet) { // 构造调用链状态。
            this.maxItems = maxItems; // 保存上限。
            this.includeSnippet = includeSnippet; // 保存片段开关。
        }

        private boolean reachedLimit() { // 判断是否达到调用项上限。
            return items >= maxItems; // 达到上限返回 true。
        }
    }
}
