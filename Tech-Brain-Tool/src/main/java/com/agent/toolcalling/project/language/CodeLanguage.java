package com.agent.toolcalling.project.language;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 项目源码语言元数据和轻量级匹配规则。
 *
 * <p>适用场景：本类保存 P4 项目工具需要的一种语言或语言族信息，包括展示名、Markdown 代码块语言名、
 * 文件扩展名、类/函数/import 行级匹配规则以及基础注释符号。它只是注册表数据模型，不读取文件、
 * 不执行工具、不访问 Redis 或数据库，也不做 AST 解析。</p>
 *
 * <p>调用链：CodeLanguageRegistry 初始化 CodeLanguage 实例 -> SearchCodeTool 使用 classPatterns
 * 和 functionPatterns 完成 CLASS/METHOD 搜索 -> ReadProjectFileTool 和 ToolCallingChatServiceImpl
 * 使用 displayName 与 markdownName 输出 readProjectFile JSON 和 FULL 代码块
 * -> ConversationFocusService 保存安全元信息。</p>
 */
public final class CodeLanguage { // 单个语言或语言族的统一描述。
    private final String displayName; // 展示给模型和 projectFileFocus 的语言名。
    private final String markdownName; // Markdown code fence 使用的语言名。
    private final Set<String> extensions; // 小写扩展名集合，不包含点号。
    private final List<CodeSearchPattern> classPatterns; // 类/接口/类型声明匹配规则。
    private final List<CodeSearchPattern> functionPatterns; // 函数/方法声明匹配规则。
    private final List<CodeSearchPattern> importPatterns; // import/include/use 匹配规则，P4.6 先保留。
    private final String singleLineCommentPrefix; // 单行注释前缀。
    private final String multiLineCommentStart; // 多行注释开始标记。
    private final String multiLineCommentEnd; // 多行注释结束标记。
    private final boolean methodSearchFallbackToKeyword; // METHOD 搜索是否降级为普通关键词。
    private final boolean componentFileNameClassFallback; // Vue/Svelte 等组件文件是否允许按文件名辅助类搜索。

    CodeLanguage(String displayName,
                 String markdownName,
                 List<String> extensions,
                 List<CodeSearchPattern> classPatterns,
                 List<CodeSearchPattern> functionPatterns,
                 List<CodeSearchPattern> importPatterns,
                 String singleLineCommentPrefix,
                 String multiLineCommentStart,
                 String multiLineCommentEnd,
                 boolean methodSearchFallbackToKeyword,
                 boolean componentFileNameClassFallback) { // 包内构造，保证只能由 Registry 组装。
        this.displayName = defaultText(displayName, "Code"); // 展示名兜底为 Code。
        this.markdownName = defaultText(markdownName, "text"); // Markdown 兜底为 text。
        this.extensions = normalizeExtensions(extensions); // 扩展名统一小写并去掉点号。
        this.classPatterns = immutablePatterns(classPatterns); // 类声明规则不可变。
        this.functionPatterns = immutablePatterns(functionPatterns); // 函数声明规则不可变。
        this.importPatterns = immutablePatterns(importPatterns); // import 规则不可变。
        this.singleLineCommentPrefix = singleLineCommentPrefix == null ? "" : singleLineCommentPrefix; // 注释前缀兜底。
        this.multiLineCommentStart = multiLineCommentStart == null ? "" : multiLineCommentStart; // 多行开始兜底。
        this.multiLineCommentEnd = multiLineCommentEnd == null ? "" : multiLineCommentEnd; // 多行结束兜底。
        this.methodSearchFallbackToKeyword = methodSearchFallbackToKeyword; // 保存 METHOD 降级策略。
        this.componentFileNameClassFallback = componentFileNameClassFallback; // 保存组件文件名辅助策略。
    }

    public String getDisplayName() { // 获取展示语言名。
        return displayName; // 返回 displayName。
    }

    public String getMarkdownName() { // 获取 Markdown code fence 语言名。
        return markdownName; // 返回 markdownName。
    }

    public Set<String> getExtensions() { // 获取扩展名集合。
        return extensions; // 返回不可变集合。
    }

    public List<CodeSearchPattern> getClassPatterns() { // 获取类声明匹配规则。
        return classPatterns; // 返回不可变列表。
    }

    public List<CodeSearchPattern> getFunctionPatterns() { // 获取函数/方法匹配规则。
        return functionPatterns; // 返回不可变列表。
    }

    public List<CodeSearchPattern> getImportPatterns() { // 获取 import 匹配规则。
        return importPatterns; // 返回不可变列表。
    }

    public String getSingleLineCommentPrefix() { // 获取单行注释前缀。
        return singleLineCommentPrefix; // 返回单行注释前缀。
    }

    public String getMultiLineCommentStart() { // 获取多行注释开始标记。
        return multiLineCommentStart; // 返回多行注释开始。
    }

    public String getMultiLineCommentEnd() { // 获取多行注释结束标记。
        return multiLineCommentEnd; // 返回多行注释结束。
    }

    public boolean isMethodSearchFallbackToKeyword() { // 获取 METHOD 是否降级关键词。
        return methodSearchFallbackToKeyword; // 返回降级策略。
    }

    public boolean isComponentFileNameClassFallback() { // 获取组件文件是否支持文件名辅助类搜索。
        return componentFileNameClassFallback; // 返回组件类搜索策略。
    }

    public boolean supportsExtension(String extension) { // 判断扩展名是否属于当前语言。
        return extensions.contains(normalizeExtension(extension)); // 使用统一小写扩展名匹配。
    }

    public boolean matchesClassDeclaration(String line, String query, boolean caseSensitive) { // 判断类/类型声明是否命中。
        return matchesAny(classPatterns, line, query, caseSensitive); // 委托统一规则列表。
    }

    public boolean matchesFunctionDeclaration(String line, String query, boolean caseSensitive) { // 判断函数/方法声明是否命中。
        return matchesAny(functionPatterns, line, query, caseSensitive); // 委托统一规则列表。
    }

    private boolean matchesAny(List<CodeSearchPattern> patterns,
                               String line,
                               String query,
                               boolean caseSensitive) { // 遍历规则列表。
        if (patterns == null || patterns.isEmpty()) { // 没有规则时不匹配。
            return false; // 返回未命中。
        }
        for (CodeSearchPattern pattern : patterns) { // 逐条尝试当前语言规则。
            if (pattern != null && pattern.matches(line, query, caseSensitive)) { // 命中任意规则即可。
                return true; // 返回命中。
            }
        }
        return false; // 没有规则命中。
    }

    private static Set<String> normalizeExtensions(List<String> values) { // 归一化扩展名集合。
        if (values == null || values.isEmpty()) { // 空配置。
            return Collections.emptySet(); // 返回空集合。
        }
        Set<String> result = new LinkedHashSet<>(); // 保留注册顺序便于报告。
        for (String value : values) { // 遍历扩展名。
            String extension = normalizeExtension(value); // 去点号并小写。
            if (!extension.isBlank()) { // 跳过空项。
                result.add(extension); // 加入集合。
            }
        }
        return Collections.unmodifiableSet(result); // 暴露不可变集合。
    }

    private static List<CodeSearchPattern> immutablePatterns(List<CodeSearchPattern> patterns) { // 归一化规则列表。
        return patterns == null ? List.of() : List.copyOf(patterns); // 返回不可变规则列表。
    }

    private static String normalizeExtension(String extension) { // 标准化单个扩展名。
        if (extension == null || extension.isBlank()) { // 空扩展名。
            return ""; // 返回空。
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT); // 小写并去空白。
        return normalized.startsWith(".") ? normalized.substring(1) : normalized; // 去掉开头点号。
    }

    private static String defaultText(String value, String fallback) { // 文本兜底。
        return value == null || value.isBlank() ? fallback : value; // 空值返回 fallback。
    }
}
