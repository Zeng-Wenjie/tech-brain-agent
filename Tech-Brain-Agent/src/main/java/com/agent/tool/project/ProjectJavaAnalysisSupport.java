package com.agent.tool.project;

import com.agent.security.ProjectPathGuard;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 项目 Java 源码轻量静态分析公共支持组件（P5 代码分析能力复用层）。
 *
 * <p>适用场景：analyzeCode 内部 P5 系列 Analyzer（如 CallChainAnalyzer、ControllerServiceChainAnalyzer）都需要在不引入
 * Tree-sitter / 完整 AST 的前提下，对单个 Java 文件做行扫描级别的解析：去注释、扫描方法体范围、提取注入依赖字段、
 * 提取方法体内的调用候选、按类型名在 workspace 内安全查找候选文件。本组件把这些通用原语集中实现一处，
 * 供多个 Tool 复用，避免在每个 Tool 内重复编写相同的解析代码。</p>
 *
 * <p>调用链：具体内部 Analyzer 注入本组件
 * -> resolveReadableProjectFile 安全解析路径（复用 ProjectPathGuard 边界）
 * -> stripComments 去掉行/块注释，保留字符串字面量
 * -> scanJavaMethods 基于大括号计数得到方法体范围和方法注解
 * -> extractDependencies 提取注入依赖字段
 * -> extractCalls 提取方法体内的调用候选
 * -> findTypeCandidatePaths 按类型名定位候选源码文件。</p>
 *
 * <p>边界说明：本组件只做静态文本级解析，不修改文件，不访问 workspace 外路径，不接入 RAG/Milvus/向量化，
 * 不做完整精准 AST，不保证百分百精准；它不是 AI Tool 本体，不进入 ToolRegistry，仅作为内部复用支持类。</p>
 */
@Component // 注册为 Spring Bean，供 P5 各内部 Analyzer 构造器注入复用。
public class ProjectJavaAnalysisSupport { // 项目 Java 源码轻量解析复用支持组件。
    private static final int MAX_FILENAME_MATCHES = 50; // 按文件名搜索候选时最多收集的数量，避免大项目扫描过多。
    private static final int MAX_CALL_EXPRESSION_CHARS = 200; // 单条调用表达式最长字符数。

    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;"); // 提取 package。
    private static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([^;]+);"); // 提取 import。
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"); // 提取类/接口名。
    private static final Pattern JAVA_FIELD_PATTERN = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:static\\s+)?(final\\s+)?([A-Za-z_$][\\w$.]*(?:<[^;=]*>)?)\\s+([A-Za-z_$][\\w$]*)\\s*(?:=.*)?;\\s*$"); // 字段声明，用于依赖识别。
    // 方法声明：参数部分用贪婪 (.*) 匹配到行内最后一个右括号，兼容 @PathVariable("id") Long id 这类参数注解中含括号的写法。
    private static final Pattern JAVA_METHOD_DECL_PATTERN = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:(?:static|final|synchronized|abstract|default|native|strictfp)\\s+)*([A-Za-z_$][\\w$<>\\[\\], ?.&]+?)\\s+([A-Za-z_$][\\w$]*)\\s*\\((.*)\\)\\s*(?:throws\\s+[A-Za-z0-9_$.,\\s<>]+)?\\s*(?:\\{|;)\\s*$"); // 方法声明行（以 { 或 ; 结尾）。
    private static final Pattern JAVA_CALL_PATTERN = Pattern.compile(
            "(?:([A-Za-z_$][\\w$]*)\\s*\\.\\s*)?([A-Za-z_$][\\w$]*)\\s*\\("); // 提取 object.method( / this.method( / method( 调用候选。

    private static final Set<String> JAVA_CALL_KEYWORDS = Set.of( // 调用提取需要排除的语言关键字。
            "if", "for", "while", "switch", "catch", "try", "return", "new", "throw",
            "synchronized", "do", "else", "case", "instanceof", "assert");
    private static final Set<String> COMMON_VALUE_TYPES = Set.of( // 这些类型不作为依赖对象，避免常量和集合污染依赖列表。
            "String", "Integer", "Long", "Boolean", "Double", "Float", "Object", "Byte", "Short",
            "Character", "BigDecimal", "BigInteger", "Number", "CharSequence", "Void",
            "List", "Map", "Set", "Collection", "Optional", "ArrayList", "HashMap", "LinkedHashMap");

    private final ProjectPathGuard projectPathGuard; // P4.1 workspace 路径安全守卫，复用统一安全策略。

    public ProjectJavaAnalysisSupport(ProjectPathGuard projectPathGuard) { // 构造器注入路径守卫。
        this.projectPathGuard = projectPathGuard; // 保存路径安全守卫。
    }

    public ProjectPathGuard getProjectPathGuard() { // 暴露守卫，便于 Tool 复用文件名/扩展名判断和校验。
        return projectPathGuard; // 返回守卫实例。
    }

    // ===================== 路径解析与文件名定位 =====================

    /**
     * 解析用户传入路径：明确相对路径直接返回；仅文件名/类名时在 workspace 内安全唯一定位。
     */
    public Path resolveReadableProjectFile(String requestedPath) { // 复用 readProjectFile/analyzeCode 同款安全定位逻辑。
        Path directPath = projectPathGuard.resolveProjectPath(requestedPath); // 必须先走 P4.1 路径解析，防止绝对路径和路径穿越。
        if (Files.exists(directPath, LinkOption.NOFOLLOW_LINKS) || !isFilenameOnly(requestedPath)) { // 明确路径或文件存在时直接返回。
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
        Set<String> targetNames = Set.of(fileName.toLowerCase(Locale.ROOT)); // 单文件名集合。
        List<Path> matches = new ArrayList<>(); // 候选列表。
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // workspace 根路径。
        if (Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) { // workspace 存在时才搜索。
            collectFilesByNames(workspaceRoot, targetNames, matches); // 递归收集安全候选。
        }
        matches.sort(Comparator.comparing(path -> toWorkspaceRelativePath(path, ""), String.CASE_INSENSITIVE_ORDER)); // 稳定排序。
        if (matches.isEmpty()) { // 未找到任何安全候选。
            throw new ProjectPathGuard.ProjectPathAccessException("未在 workspace 中找到 " + fileName + "，请先使用 searchCode 定位文件位置或提供完整相对路径。"); // 友好提示先定位。
        }
        if (matches.size() > 1) { // 同名文件不止一个时不能乱猜。
            throw new ProjectPathGuard.ProjectPathAccessException(buildAmbiguousFileMessage(fileName, matches)); // 返回相对路径候选。
        }
        return matches.get(0); // 唯一匹配时返回。
    }

    /**
     * 按类型名在 workspace 内查找候选源码文件路径，接口在前、实现类在后，service/impl 目录优先。
     */
    public List<Path> findTypeCandidatePaths(String simpleType, int max) { // 用于 Service 接口/ServiceImpl 候选定位。
        List<Path> result = new ArrayList<>(); // 候选路径。
        if (simpleType == null || simpleType.isBlank()) { // 空类型无法查找。
            return result; // 返回空列表。
        }
        Set<String> candidateNames = new LinkedHashSet<>(); // 候选文件名集合，小写匹配。
        candidateNames.add((simpleType + ".java").toLowerCase(Locale.ROOT)); // 接口/类本体。
        if (!simpleType.endsWith("Impl")) { // 常见实现类后缀。
            candidateNames.add((simpleType + "Impl.java").toLowerCase(Locale.ROOT)); // 默认实现类。
        }
        List<Path> matches = new ArrayList<>(); // 命中文件。
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // workspace 根目录。
        if (Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) { // workspace 存在时才扫描。
            collectFilesByNames(workspaceRoot, candidateNames, matches); // 递归收集候选文件。
        }
        matches.sort(candidateOrder()); // 接口在前、实现类在后，service/impl 目录优先。
        int limit = max <= 0 ? 5 : max; // 默认最多 5 个。
        for (Path match : matches) { // 截断到上限。
            if (result.size() >= limit) { // 达到上限。
                break; // 停止。
            }
            result.add(match); // 收集候选。
        }
        return result; // 返回候选路径。
    }

    private Comparator<Path> candidateOrder() { // 候选文件排序规则。
        return Comparator
                .comparingInt((Path path) -> isImplFile(path) ? 1 : 0) // 接口/类本体在前，Impl 在后。
                .thenComparingInt(path -> pathContainsServiceDir(path) ? 0 : 1) // service/impl 目录优先。
                .thenComparing(path -> toWorkspaceRelativePath(path, ""), String.CASE_INSENSITIVE_ORDER); // 其余按相对路径稳定排序。
    }

    private boolean isImplFile(Path path) { // 判断是否为 *Impl.java。
        String name = path == null || path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT); // 文件名小写。
        return name.endsWith("impl.java"); // 以 Impl.java 结尾。
    }

    private boolean pathContainsServiceDir(Path path) { // 判断路径是否包含 service/impl 目录。
        String relative = toWorkspaceRelativePath(path, "").toLowerCase(Locale.ROOT); // 相对路径小写。
        return relative.contains("/service") || relative.contains("/impl"); // 命中 service 或 impl 目录。
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
        } catch (IOException ignored) {
            // 跳过不可读目录，不打印绝对路径。
        }
    }

    private boolean isSafeDirectory(Path directory) { // 判断目录是否可进入扫描。
        if (directory == null) { // 空目录不安全。
            return false; // 返回 false。
        }
        Path normalizedPath = directory.toAbsolutePath().normalize(); // 标准化目录路径。
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS); // workspace 内、非软链、非敏感目录。
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

    private boolean isFilenameOnly(String path) { // 判断是否只传了文件名。
        String normalizedPath = trimToNull(path); // 标准化。
        if (normalizedPath == null) { // 空路径不是文件名。
            return false; // 返回 false。
        }
        return !normalizedPath.contains("/") && !normalizedPath.contains("\\"); // 不含分隔符即视为文件名。
    }

    private String normalizeFileNameCandidate(String requestedPath) { // 将文件名或类名标准化为具体文件名。
        String fileName = trimToNull(requestedPath); // 去空白。
        if (fileName == null) { // 兜底。
            return ""; // 返回空。
        }
        if (projectPathGuard.isBlockedFilename(fileName)) { // 敏感文件名不能被自动补后缀绕过。
            return fileName; // 原样返回。
        }
        if (!fileName.contains(".") && fileName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { // 类名直传且无扩展名。
            return fileName + ".java"; // 默认补 .java。
        }
        return fileName; // 其它文件名原样使用。
    }

    // ===================== 去注释 / 方法扫描 / 依赖 / 调用 =====================

    /**
     * 去掉 // 行注释和 /* *​/ 块注释，保留字符串与字符字面量内容，行数与原文件一致，避免逐行尾注释干扰正则。
     */
    public List<String> stripComments(List<String> lines) { // 去注释，返回与原文件等长的代码行。
        List<String> result = new ArrayList<>(); // 去注释后的代码行。
        boolean inBlockComment = false; // 是否处于跨行块注释中。
        List<String> safeLines = lines == null ? List.of() : lines; // 空文件兜底。
        for (String line : safeLines) { // 逐行处理。
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
                        i++; // 吞掉块注释内容。
                    }
                    continue; // 下一个字符。
                }
                if (inString) { // 字符串内原样保留。
                    builder.append(ch); // 写入字符。
                    if (ch == '\\' && i + 1 < length) { // 处理转义。
                        builder.append(next); // 写入被转义字符。
                        i += 2; // 跳过转义对。
                        continue; // 下一个字符。
                    }
                    if (ch == '"') { // 字符串结束。
                        inString = false; // 退出字符串。
                    }
                    i++; // 继续。
                    continue; // 下一个字符。
                }
                if (inChar) { // 字符字面量内原样保留。
                    builder.append(ch); // 写入字符。
                    if (ch == '\\' && i + 1 < length) { // 处理转义。
                        builder.append(next); // 写入被转义字符。
                        i += 2; // 跳过转义对。
                        continue; // 下一个字符。
                    }
                    if (ch == '\'') { // 字符字面量结束。
                        inChar = false; // 退出字符字面量。
                    }
                    i++; // 继续。
                    continue; // 下一个字符。
                }
                if (ch == '/' && next == '/') { // 行注释，丢弃本行剩余部分。
                    break; // 结束本行。
                }
                if (ch == '/' && next == '*') { // 块注释开始。
                    inBlockComment = true; // 进入块注释。
                    i += 2; // 跳过 /*。
                    continue; // 下一个字符。
                }
                if (ch == '"') { // 字符串开始。
                    inString = true; // 进入字符串。
                    builder.append(ch); // 写入引号。
                    i++; // 继续。
                    continue; // 下一个字符。
                }
                if (ch == '\'') { // 字符字面量开始。
                    inChar = true; // 进入字符字面量。
                    builder.append(ch); // 写入引号。
                    i++; // 继续。
                    continue; // 下一个字符。
                }
                builder.append(ch); // 普通代码字符保留。
                i++; // 继续。
            }
            result.add(builder.toString()); // 收集去注释代码行。
        }
        return result; // 返回去注释代码行。
    }

    /**
     * 基于大括号计数扫描方法体范围，并附带每个方法声明上方的注解，供 endpoint / 注入类型识别。
     */
    public List<JavaMethod> scanJavaMethods(List<String> codeLines) { // 扫描方法声明、方法体范围和方法注解。
        List<JavaMethod> methods = new ArrayList<>(); // 方法扫描结果。
        List<String> safeLines = codeLines == null ? List.of() : codeLines; // 空文件兜底。
        int depth = 0; // 当前大括号深度。
        JavaMethod current = null; // 当前正在收集方法体的方法。
        List<String> pending = new ArrayList<>(); // 方法声明上方累积的注解。
        for (int i = 0; i < safeLines.size(); i++) { // 逐行扫描。
            String line = safeLines.get(i); // 当前行。
            String trimmed = line.trim(); // 去空白后的行。
            if (current == null) { // 不在方法体内时识别注解、方法声明。
                if (depth <= 1 && trimmed.startsWith("@")) { // 类体层级注解先暂存。
                    pending.add(trimmed); // 暂存注解。
                    depth += braceDelta(line); // 更新深度。
                    continue; // 下一行。
                }
                if (depth == 1) { // 仅在类体层级识别方法声明。
                    Matcher methodMatcher = JAVA_METHOD_DECL_PATTERN.matcher(line); // 匹配方法声明。
                    if (methodMatcher.matches() && isJavaMethodLine(trimmed, methodMatcher)) { // 命中且不是控制语句。
                        JavaMethod method = new JavaMethod(safeText(methodMatcher.group(3)), i + 1,
                                compactSpaces(methodMatcher.group(2)), compactSpaces(methodMatcher.group(4)), i); // 记录方法名、行号、返回类型、参数。
                        method.annotations = new ArrayList<>(pending); // 附带方法注解。
                        pending.clear(); // 清空注解缓存。
                        if (trimmed.endsWith(";")) { // 抽象/接口方法无方法体。
                            method.hasBody = false; // 标记无方法体。
                            method.bodyEndIndex = i; // 方法体结束行等于声明行。
                            methods.add(method); // 直接登记。
                        } else { // 普通方法进入方法体收集。
                            method.openDepth = depth; // 记录开口深度。
                            current = method; // 设为当前方法。
                        }
                        depth += braceDelta(line); // 应用当前行大括号增量。
                        if (current != null && depth <= current.openDepth) { // 单行方法体即时闭合。
                            current.bodyEndIndex = i; // 记录结束行。
                            current.hasBody = true; // 标记有方法体。
                            methods.add(current); // 登记方法。
                            current = null; // 清空当前方法。
                        }
                        continue; // 下一行。
                    }
                }
                if (!trimmed.isBlank() && !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")) { // 非注解非方法的代码行重置注解缓存。
                    pending.clear(); // 清空注解缓存。
                }
                depth += braceDelta(line); // 更新深度。
            } else { // 在方法体内，只做大括号计数等待闭合。
                depth += braceDelta(line); // 更新深度。
                if (depth <= current.openDepth) { // 方法体闭合。
                    current.bodyEndIndex = i; // 记录结束行。
                    current.hasBody = true; // 标记有方法体。
                    methods.add(current); // 登记方法。
                    current = null; // 清空当前方法。
                }
            }
        }
        return methods; // 返回方法扫描结果。
    }

    /**
     * 提取注入依赖字段（字段注入 + 构造器参数注入），返回字段名、类型、注入方式、行号。
     */
    public List<JavaField> extractDependencies(List<String> codeLines, List<JavaMethod> methods, String className) { // 提取依赖对象。
        List<JavaField> dependencies = new ArrayList<>(); // 依赖列表。
        Set<String> seenFieldNames = new LinkedHashSet<>(); // 已登记字段名去重。
        List<String> safeLines = codeLines == null ? List.of() : codeLines; // 空文件兜底。
        int depth = 0; // 大括号深度。
        List<String> pending = new ArrayList<>(); // 字段上方注解。
        for (int i = 0; i < safeLines.size(); i++) { // 逐行扫描字段。
            String line = safeLines.get(i); // 当前行。
            String trimmed = line.trim(); // 去空白后的行。
            if (depth <= 1 && trimmed.startsWith("@")) { // 类体层级注解先暂存。
                pending.add(trimmed); // 暂存注解。
                depth += braceDelta(line); // 更新深度。
                continue; // 下一行。
            }
            if (depth == 1) { // 仅识别类体层级字段。
                Matcher fieldMatcher = JAVA_FIELD_PATTERN.matcher(line); // 匹配字段声明。
                if (fieldMatcher.matches() && !trimmed.contains("(")) { // 命中字段且不是方法。
                    String type = compactSpaces(fieldMatcher.group(3)); // 字段类型。
                    String fieldName = safeText(fieldMatcher.group(4)); // 字段名。
                    if (isLikelyDependency(type, fieldName) && seenFieldNames.add(fieldName)) { // 仅保留类类型实例依赖且去重。
                        String injectionType = resolveInjectionType(pending, fieldMatcher.group(2) != null); // 识别注入方式。
                        dependencies.add(new JavaField(fieldName, simpleTypeName(type), injectionType, i + 1)); // 登记依赖。
                    }
                }
            }
            if (!trimmed.isBlank() && !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")) { // 非注解非注释行重置注解缓存。
                pending.clear(); // 清空注解缓存。
            }
            depth += braceDelta(line); // 更新深度。
        }
        if (className != null && !className.isBlank()) { // 类名可用时补构造器参数注入。
            for (JavaMethod method : methods) { // 遍历方法找构造器。
                if (className.equals(method.name) && method.parameters != null && !method.parameters.isBlank()) { // 方法名等于类名即构造器。
                    collectConstructorParams(method.parameters, method.lineNumber, dependencies, seenFieldNames); // 提取构造器参数依赖。
                }
            }
        }
        return dependencies; // 返回依赖列表。
    }

    private void collectConstructorParams(String parameters,
                                          int lineNumber,
                                          List<JavaField> dependencies,
                                          Set<String> seenFieldNames) { // 提取构造器参数作为依赖对象。
        for (String rawParam : splitTopLevel(parameters)) { // 逐个参数解析。
            String param = stripParamAnnotations(rawParam).trim(); // 去掉 @Qualifier 等注解前缀。
            if (param.isBlank()) { // 空参数跳过。
                continue; // 下一个。
            }
            int lastSpace = param.lastIndexOf(' '); // 类型与参数名以空格分隔。
            if (lastSpace <= 0) { // 无法解析类型和名称。
                continue; // 跳过。
            }
            String type = compactSpaces(param.substring(0, lastSpace)); // 参数类型。
            String paramName = safeText(param.substring(lastSpace + 1)); // 参数名。
            if (!isLikelyDependency(type, paramName) || !seenFieldNames.add(paramName)) { // 仅保留类类型且未登记的依赖。
                continue; // 跳过非依赖或重复项。
            }
            dependencies.add(new JavaField(paramName, simpleTypeName(type), "CONSTRUCTOR", lineNumber)); // 登记构造器注入依赖。
        }
    }

    /**
     * 提取指定方法体内的调用候选（object.method / this.method / method），不做分类，由具体 Tool 决定如何归类。
     */
    public List<JavaCall> extractCalls(List<String> codeLines, JavaMethod method) { // 提取方法体内的调用候选。
        List<JavaCall> calls = new ArrayList<>(); // 调用候选列表。
        if (method == null || !method.hasBody || codeLines == null) { // 无方法体不提取。
            return calls; // 返回空列表。
        }
        Set<String> seen = new LinkedHashSet<>(); // 去重，避免同行重复计数。
        for (int i = method.bodyStartIndex; i <= method.bodyEndIndex && i < codeLines.size(); i++) { // 遍历方法体每一行。
            String line = codeLines.get(i); // 当前行。
            int lineNumber = i + 1; // 行号。
            Matcher matcher = JAVA_CALL_PATTERN.matcher(line); // 匹配调用候选。
            while (matcher.find()) { // 逐个调用候选。
                String receiver = matcher.group(1); // 调用对象，可能为空。
                String callee = matcher.group(2); // 被调用方法名。
                if (callee == null || JAVA_CALL_KEYWORDS.contains(callee)) { // 排除关键字。
                    continue; // 跳过。
                }
                if (i == method.bodyStartIndex && receiver == null && callee.equals(method.name)) { // 跳过方法声明本身。
                    continue; // 跳过声明行方法名。
                }
                if (isPrecededByNew(line, matcher.start())) { // 排除 new Xxx() 构造调用。
                    continue; // 跳过对象创建。
                }
                String callExpression = extractCallExpression(line, receiver == null ? matcher.start(2) : matcher.start(1)); // 提取调用表达式。
                String dedupKey = (receiver == null ? "" : receiver) + "|" + callee + "|" + lineNumber; // 去重键。
                if (!seen.add(dedupKey)) { // 同行重复调用只记一次。
                    continue; // 跳过重复。
                }
                calls.add(new JavaCall(receiver, callee, lineNumber, callExpression)); // 登记调用候选。
            }
        }
        return calls; // 返回调用候选。
    }

    // ===================== 通用工具方法 =====================

    public String extractCallExpression(String line, int startIndex) { // 从调用起点提取平衡括号内的调用表达式片段。
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

    public String simpleTypeName(String type) { // 提取去泛型、去包名后的简单类型名。
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

    public String toWorkspaceRelativePath(Path filePath, String fallbackPath) { // 将绝对路径转换为相对 workspace 路径。
        try {
            Path workspaceRoot = projectPathGuard.getWorkspaceRoot().toAbsolutePath().normalize(); // workspace 根目录。
            Path normalizedPath = filePath.toAbsolutePath().normalize(); // 标准化目标路径。
            if (normalizedPath.startsWith(workspaceRoot)) { // 位于 workspace 内。
                return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/'); // 返回统一斜杠相对路径。
            }
        } catch (Exception ignored) {
            // 解析失败时回退到入参路径形态。
        }
        return fallbackPath == null ? "" : fallbackPath.replace('\\', '/'); // 返回兜底相对路径。
    }

    private boolean isLikelyDependency(String type, String fieldName) { // 判断字段是否是类类型依赖对象。
        if (type == null || type.isBlank() || fieldName == null || fieldName.isBlank()) { // 空值不是依赖。
            return false; // 返回 false。
        }
        if (fieldName.matches("[A-Z0-9_]+")) { // 全大写字段是常量，不是依赖。
            return false; // 返回 false。
        }
        String simpleType = simpleTypeName(type); // 简单类型名。
        if (simpleType.isBlank() || !Character.isUpperCase(simpleType.charAt(0))) { // 类型首字母非大写说明不是类类型。
            return false; // 返回 false。
        }
        return !COMMON_VALUE_TYPES.contains(simpleType); // 排除 String、集合等值类型。
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

    private boolean isJavaMethodLine(String trimmed, Matcher methodMatcher) { // 判断方法正则命中行是否真的是方法声明。
        if (trimmed == null || trimmed.isBlank() || trimmed.contains("=")) { // 含赋值号的不是方法。
            return false; // 返回 false。
        }
        String name = methodMatcher.group(3); // 方法名。
        String returnType = methodMatcher.group(2); // 返回类型。
        return !isJavaKeyword(name) && !isJavaKeyword(returnType) && !trimmed.startsWith("new "); // 排除控制语句和 new 表达式。
    }

    private boolean isJavaKeyword(String value) { // 判断是否是常见 Java 控制关键字。
        if (value == null) { // 空值视为关键字。
            return true; // 返回 true。
        }
        return Set.of("if", "for", "while", "switch", "catch", "return", "throw", "new", "else", "do", "try")
                .contains(value.trim()); // 命中控制关键字。
    }

    public int braceDelta(String line) { // 计算一行的大括号净增量，忽略字符串中的括号。
        int delta = 0; // 净增量。
        boolean inString = false; // 是否在字符串内。
        char previous = 0; // 上一个字符。
        for (int i = 0; line != null && i < line.length(); i++) { // 逐字符扫描。
            char ch = line.charAt(i); // 当前字符。
            if (ch == '"' && previous != '\\') { // 进出字符串。
                inString = !inString; // 翻转状态。
            }
            if (!inString && ch == '{') { // 字符串外左括号。
                delta++; // 增量加一。
            } else if (!inString && ch == '}') { // 字符串外右括号。
                delta--; // 增量减一。
            }
            previous = ch; // 推进上一个字符。
        }
        return delta; // 返回净增量。
    }

    private boolean isPrecededByNew(String line, int matchStart) { // 判断调用候选前是否紧跟 new 关键字。
        if (line == null || matchStart <= 0) { // 行首不可能是 new 调用。
            return false; // 返回 false。
        }
        String prefix = line.substring(0, matchStart).trim(); // 调用前文本。
        return prefix.endsWith("new"); // 以 new 结尾说明是构造调用。
    }

    private List<String> splitTopLevel(String parameters) { // 按顶层逗号拆分参数，忽略泛型内逗号。
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
                continue; // 下一个字符。
            }
            current.append(ch); // 累积参数字符。
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

    private String abbreviate(String value) { // 限制调用表达式长度。
        String text = value == null ? "" : value; // 空值兜底。
        if (text.length() <= MAX_CALL_EXPRESSION_CHARS) { // 未超长。
            return text; // 原样返回。
        }
        return text.substring(0, MAX_CALL_EXPRESSION_CHARS) + "...[truncated]"; // 截断并标记。
    }

    public String compactSpaces(String value) { // 压缩多余空白。
        return value == null ? "" : value.trim().replaceAll("\\s+", " "); // 合并空白并去首尾。
    }

    public String safeText(String value) { // 去空白兜底。
        return value == null ? "" : value.trim(); // 返回去空白文本。
    }

    public String trimToNull(String value) { // 将空白字符串统一转换为 null。
        return value == null || value.trim().isEmpty() ? null : value.trim(); // 空白返回 null。
    }

    // ===================== 静态扫描结果数据模型 =====================

    /**
     * Java 字段/参数依赖对象。
     */
    public static final class JavaField { // 单个注入依赖对象。
        private final String fieldName; // 字段名。
        private final String type; // 简单类型名。
        private final String injectionType; // 注入方式。
        private final int lineNumber; // 行号。

        public JavaField(String fieldName, String type, String injectionType, int lineNumber) { // 构造依赖对象。
            this.fieldName = fieldName; // 保存字段名。
            this.type = type; // 保存类型。
            this.injectionType = injectionType; // 保存注入方式。
            this.lineNumber = lineNumber; // 保存行号。
        }

        public String getFieldName() { // 字段名。
            return fieldName;
        }

        public String getType() { // 类型。
            return type;
        }

        public String getInjectionType() { // 注入方式。
            return injectionType;
        }

        public int getLineNumber() { // 行号。
            return lineNumber;
        }
    }

    /**
     * Java 方法扫描结果，包含方法体范围和方法声明上方注解。
     */
    public static final class JavaMethod { // 单个方法的扫描信息。
        private final String name; // 方法名。
        private final int lineNumber; // 声明行号。
        private final String returnType; // 返回类型。
        private final String parameters; // 参数列表。
        private final int bodyStartIndex; // 方法体起始行索引（含声明行）。
        private int bodyEndIndex; // 方法体结束行索引。
        private int openDepth; // 方法体开口时的大括号深度。
        private boolean hasBody; // 是否有方法体。
        private List<String> annotations = new ArrayList<>(); // 方法声明上方注解。

        public JavaMethod(String name, int lineNumber, String returnType, String parameters, int bodyStartIndex) { // 构造方法扫描结果。
            this.name = name; // 保存方法名。
            this.lineNumber = lineNumber; // 保存声明行号。
            this.returnType = returnType; // 保存返回类型。
            this.parameters = parameters; // 保存参数列表。
            this.bodyStartIndex = bodyStartIndex; // 保存方法体起始索引。
            this.bodyEndIndex = bodyStartIndex; // 默认结束索引等于起始索引。
        }

        public String getName() { // 方法名。
            return name;
        }

        public int getLineNumber() { // 声明行号。
            return lineNumber;
        }

        public String getReturnType() { // 返回类型。
            return returnType;
        }

        public String getParameters() { // 参数列表。
            return parameters;
        }

        public boolean hasBody() { // 是否有方法体。
            return hasBody;
        }

        public List<String> getAnnotations() { // 方法注解。
            return annotations;
        }
    }

    /**
     * Java 方法体内的调用候选。
     */
    public static final class JavaCall { // 单条调用候选。
        private final String receiver; // 调用对象，可能为空。
        private final String callee; // 被调用方法名。
        private final int lineNumber; // 行号。
        private final String callExpression; // 调用表达式片段。

        public JavaCall(String receiver, String callee, int lineNumber, String callExpression) { // 构造调用候选。
            this.receiver = receiver; // 保存调用对象。
            this.callee = callee; // 保存被调用方法名。
            this.lineNumber = lineNumber; // 保存行号。
            this.callExpression = callExpression; // 保存调用表达式。
        }

        public String getReceiver() { // 调用对象。
            return receiver;
        }

        public String getCallee() { // 被调用方法名。
            return callee;
        }

        public int getLineNumber() { // 行号。
            return lineNumber;
        }

        public String getCallExpression() { // 调用表达式。
            return callExpression;
        }
    }

    public Collection<String> commonValueTypes() { // 暴露通用值类型集合，便于 Tool 复用判断（保留扩展位）。
        return COMMON_VALUE_TYPES; // 返回只读集合视图。
    }
}
