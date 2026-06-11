package com.agent.toolcalling.project.language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P4 项目代码工具统一使用的语言识别注册表。
 *
 * <p>适用场景：本注册表根据文件扩展名识别语言，返回 Markdown 代码块语言名，并向 SearchCodeTool
 * 和 ReadProjectFileTool 暴露类、函数、import、注释等轻量级匹配规则。这里刻意使用 Java 硬编码元数据，
 * 不放数据库，不接 RAG，不做向量索引，不引入 Tree-sitter，不构建 AST，也不是代码分析工具。</p>
 *
 * <p>调用链：SearchCodeTool 通过 resolveByFileName 识别文件语言并复用类/函数规则；
 * ReadProjectFileTool 通过本注册表解析 displayName 和 markdownName 写入 JSON；
 * ToolCallingChatServiceImpl 在 FULL 代码块输出和保存 projectFileFocus 元信息时复用 markdownName。</p>
 */
public final class CodeLanguageRegistry { // P4 项目代码语言识别统一入口。
    private static final CodeLanguage UNKNOWN = language("Code", "text", List.of(),
            List.of(), List.of(), List.of(), "//", "/*", "*/", false, false); // 未知类型兜底。
    private static final Map<String, CodeLanguage> LANGUAGES_BY_EXTENSION = buildRegistry(); // 扩展名到语言元数据映射。

    private CodeLanguageRegistry() { // 工具类禁止实例化。
    }

    public static CodeLanguage resolveByFileName(String fileName) { // 根据文件名识别语言。
        return resolveByExtension(extractExtension(fileName)); // 先提取扩展名再查注册表。
    }

    public static CodeLanguage resolveByExtension(String extension) { // 根据扩展名识别语言。
        String normalizedExtension = normalizeExtension(extension); // 标准化扩展名。
        if (normalizedExtension.isBlank()) { // 没有扩展名。
            return UNKNOWN; // 返回兜底语言。
        }
        return LANGUAGES_BY_EXTENSION.getOrDefault(normalizedExtension, UNKNOWN); // 未注册时返回 Code/text。
    }

    public static List<CodeLanguage> supportedLanguages() { // 获取已注册语言列表。
        return LANGUAGES_BY_EXTENSION.values().stream().distinct().toList(); // 去重后按注册顺序返回。
    }

    public static String extractExtension(String fileName) { // 兼容 ProjectPathGuard 的扩展名提取逻辑。
        if (fileName == null || fileName.isBlank()) { // 空文件名。
            return ""; // 返回空扩展名。
        }
        String lowerName = fileName.trim().toLowerCase(Locale.ROOT); // 文件名统一小写。
        if (lowerName.startsWith(".") && lowerName.indexOf('.', 1) > 0) { // 支持 .env.example 这类多段点文件。
            return lowerName.substring(1); // 返回 env.example。
        }
        int dotIndex = lowerName.lastIndexOf('.'); // 普通文件取最后一个点。
        if (dotIndex < 0 || dotIndex == lowerName.length() - 1) { // 无点或点在末尾。
            return ""; // 返回空。
        }
        return lowerName.substring(dotIndex + 1); // 返回末尾扩展名。
    }

    private static Map<String, CodeLanguage> buildRegistry() { // 构造所有 P4.6 支持语言。
        List<CodeLanguage> languages = new ArrayList<>(); // 保留注册顺序，便于支持范围报告。

        languages.add(language("Java", "java", List.of("java"),
                jvmClassPatterns(), javaLikeMethodPatterns(), javaImportPatterns(), "//", "/*", "*/", false, false)); // Java。
        languages.add(language("Kotlin", "kotlin", List.of("kt", "kts"),
                List.of(pattern("\\b(class|interface|enum\\s+class|object|data\\s+class)\\s+%s\\b")),
                List.of(pattern("\\bfun\\s+%s\\s*\\("), pattern("\\b%s\\s*\\(")),
                List.of(pattern("^\\s*import\\s+%s\\b")), "//", "/*", "*/", false, false)); // Kotlin。
        languages.add(language("Groovy", "groovy", List.of("groovy"),
                jvmClassPatterns(), javaLikeMethodPatterns(), javaImportPatterns(), "//", "/*", "*/", false, false)); // Groovy。
        languages.add(language("Scala", "scala", List.of("scala"),
                List.of(pattern("\\b(class|object|trait|enum)\\s+%s\\b")),
                List.of(pattern("\\bdef\\s+%s\\s*\\("), pattern("\\b%s\\s*\\(")),
                List.of(pattern("^\\s*import\\s+%s\\b")), "//", "/*", "*/", false, false)); // Scala。
        languages.add(language("Python", "python", List.of("py"),
                List.of(pattern("^\\s*class\\s+%s\\b.*:")),
                List.of(pattern("^\\s*(async\\s+)?def\\s+%s\\s*\\(")),
                List.of(pattern("^\\s*(from\\s+%s\\b|import\\s+%s\\b)")), "#", "\"\"\"", "\"\"\"", false, false)); // Python。
        languages.add(language("JavaScript", "javascript", List.of("js", "jsx"),
                jsClassPatterns(), jsMethodPatterns(), jsImportPatterns(), "//", "/*", "*/", false, false)); // JavaScript。
        languages.add(language("TypeScript", "typescript", List.of("ts", "tsx"),
                jsClassPatterns(), jsMethodPatterns(), jsImportPatterns(), "//", "/*", "*/", false, false)); // TypeScript。
        languages.add(language("Vue", "vue", List.of("vue"),
                jsClassPatterns(), jsMethodPatterns(), jsImportPatterns(), "//", "/*", "*/", false, true)); // Vue 单文件组件。
        languages.add(language("Svelte", "svelte", List.of("svelte"),
                jsClassPatterns(), jsMethodPatterns(), jsImportPatterns(), "//", "/*", "*/", false, true)); // Svelte 单文件组件。
        languages.add(language("Go", "go", List.of("go"),
                List.of(pattern("\\btype\\s+%s\\s+(struct|interface)\\b")),
                List.of(pattern("\\bfunc\\s+%s\\s*\\("), pattern("\\bfunc\\s*\\([^)]*\\)\\s*%s\\s*\\(")),
                List.of(pattern("^\\s*import\\s+.*%s")), "//", "/*", "*/", false, false)); // Go。
        languages.add(language("Rust", "rust", List.of("rs"),
                List.of(pattern("\\b(struct|enum|trait|impl)\\s+%s\\b")),
                List.of(pattern("\\bfn\\s+%s\\s*\\(")),
                List.of(pattern("^\\s*use\\s+.*%s")), "//", "/*", "*/", false, false)); // Rust。
        languages.add(language("C", "c", List.of("c", "h"),
                List.of(pattern("\\b(class|struct|enum)\\s+%s\\b"), pattern("\\btypedef\\s+struct\\b.*\\b%s\\b")),
                List.of(pattern("\\b%s\\s*\\(")),
                List.of(pattern("^\\s*#\\s*include\\s+.*%s")), "//", "/*", "*/", false, false)); // C / 头文件。
        languages.add(language("C++", "cpp", List.of("cpp", "cc", "cxx", "hpp", "hxx"),
                List.of(pattern("\\b(class|struct|enum)\\s+%s\\b"), pattern("\\btypedef\\s+struct\\b.*\\b%s\\b")),
                List.of(pattern("\\b%s\\s*\\(")),
                List.of(pattern("^\\s*#\\s*include\\s+.*%s")), "//", "/*", "*/", false, false)); // C++。
        languages.add(language("C#", "csharp", List.of("cs"),
                jvmClassPatterns(), javaLikeMethodPatterns(), List.of(pattern("^\\s*using\\s+.*%s")), "//", "/*", "*/", false, false)); // C#。
        languages.add(language(".NET Project", "xml", List.of("csproj", "sln"),
                List.of(), List.of(), List.of(), "", "<!--", "-->", false, false)); // .NET 项目文件。
        languages.add(language("PHP", "php", List.of("php"),
                List.of(pattern("\\b(class|interface|enum|trait)\\s+%s\\b")),
                List.of(pattern("\\bfunction\\s+%s\\s*\\(")),
                List.of(pattern("^\\s*(use|include|require).*%s")), "//", "/*", "*/", false, false)); // PHP。
        languages.add(language("Ruby", "ruby", List.of("rb"),
                List.of(pattern("^\\s*class\\s+%s\\b"), pattern("^\\s*module\\s+%s\\b")),
                List.of(pattern("^\\s*def\\s+%s\\b")),
                List.of(pattern("^\\s*require.*%s")), "#", "=begin", "=end", false, false)); // Ruby。
        languages.add(language("Swift", "swift", List.of("swift"),
                List.of(pattern("\\b(class|struct|enum|protocol)\\s+%s\\b")),
                List.of(pattern("\\bfunc\\s+%s\\s*\\(")),
                List.of(pattern("^\\s*import\\s+%s\\b")), "//", "/*", "*/", false, false)); // Swift。
        languages.add(language("Objective-C", "objectivec", List.of("m", "mm"),
                List.of(pattern("@interface\\s+%s\\b"), pattern("@implementation\\s+%s\\b")),
                List.of(pattern("[+-]\\s*\\([^)]*\\)\\s*%s\\b")),
                List.of(pattern("^\\s*#\\s*import\\s+.*%s")), "//", "/*", "*/", false, false)); // Objective-C。
        languages.add(language("SQL", "sql", List.of("sql"),
                List.of(), List.of(), List.of(), "--", "/*", "*/", true, false)); // SQL METHOD 降级 KEYWORD。
        languages.add(language("Shell", "bash", List.of("sh", "bash", "zsh"),
                List.of(), List.of(pattern("^\\s*%s\\s*\\(\\)\\s*\\{")), List.of(), "#", "", "", false, false)); // Shell。
        languages.add(language("PowerShell", "powershell", List.of("ps1"),
                List.of(), List.of(pattern("\\bfunction\\s+%s\\b")), List.of(), "#", "<#", "#>", false, false)); // PowerShell。
        languages.add(language("Batch", "batch", List.of("bat", "cmd"),
                List.of(), List.of(pattern("^\\s*:%s\\b")), List.of(), "REM", "", "", false, false)); // Windows 批处理。
        languages.add(language("HTML", "html", List.of("html"),
                List.of(), List.of(), List.of(), "", "<!--", "-->", false, false)); // HTML。
        languages.add(language("CSS", "css", List.of("css", "scss", "less"),
                List.of(), List.of(), List.of(), "/*", "/*", "*/", false, false)); // CSS/SCSS/Less。
        languages.add(language("XML", "xml", List.of("xml"),
                List.of(), List.of(), List.of(), "", "<!--", "-->", false, false)); // XML。
        languages.add(language("JSON", "json", List.of("json", "ipynb"),
                List.of(), List.of(), List.of(), "", "", "", false, false)); // JSON / Notebook。
        languages.add(language("YAML", "yaml", List.of("yaml", "yml"),
                List.of(), List.of(), List.of(), "#", "", "", false, false)); // YAML。
        languages.add(language("Properties", "properties", List.of("properties", "ini", "env.example"),
                List.of(), List.of(), List.of(), "#", "", "", false, false)); // properties/ini/env.example。
        languages.add(language("TOML", "toml", List.of("toml"),
                List.of(), List.of(), List.of(), "#", "", "", false, false)); // TOML。
        languages.add(language("Markdown", "markdown", List.of("md"),
                List.of(), List.of(), List.of(), "", "<!--", "-->", false, false)); // Markdown。
        languages.add(language("Text", "text", List.of("txt"),
                List.of(), List.of(), List.of(), "", "", "", false, false)); // 纯文本。

        Map<String, CodeLanguage> registry = new LinkedHashMap<>(); // 扩展名映射。
        for (CodeLanguage language : languages) { // 遍历语言。
            for (String extension : language.getExtensions()) { // 遍历语言扩展名。
                registry.put(extension, language); // 后注册同名扩展会覆盖前者。
            }
        }
        return Map.copyOf(registry); // 返回不可变注册表。
    }

    private static List<CodeSearchPattern> jvmClassPatterns() { // JVM/Java-like 类声明规则。
        return List.of(pattern("\\b(class|interface|enum|record|object|trait)\\s+%s\\b")); // 覆盖 class/interface/enum/record/object/trait。
    }

    private static List<CodeSearchPattern> javaLikeMethodPatterns() { // Java/C#/Groovy 方法声明规则。
        return List.of(pattern("\\b(public|private|protected|static|final|synchronized|abstract|override|virtual|async|\\s)*[\\w<>\\[\\], ?]+\\s+%s\\s*\\("),
                pattern("\\b%s\\s*\\(")); // 兼容带访问修饰符和简单方法名括号。
    }

    private static List<CodeSearchPattern> javaImportPatterns() { // Java-like import 规则。
        return List.of(pattern("^\\s*import\\s+.*%s")); // 支持 import 包名或类名关键词。
    }

    private static List<CodeSearchPattern> jsClassPatterns() { // JS/TS/Vue/Svelte 类型声明规则。
        return List.of(pattern("\\b(class|interface)\\s+%s\\b"),
                pattern("\\btype\\s+%s\\s*="),
                pattern("\\bconst\\s+%s\\s*="),
                pattern("\\bfunction\\s+%s\\s*\\(")); // 覆盖 class/interface/type/const/function。
    }

    private static List<CodeSearchPattern> jsMethodPatterns() { // JS/TS 函数声明规则。
        return List.of(pattern("\\b(async\\s+)?function\\s+%s\\s*\\("),
                pattern("\\b(const|let|var)\\s+%s\\s*="),
                pattern("\\b%s\\s*:\\s*(async\\s*)?(function\\s*)?\\(?"),
                pattern("\\b%s\\s*\\(")); // 覆盖函数声明、变量函数、对象方法和普通括号形式。
    }

    private static List<CodeSearchPattern> jsImportPatterns() { // JS/TS import 规则。
        return List.of(pattern("^\\s*import\\s+.*%s"), pattern("^\\s*const\\s+.*require\\(.*%s")); // 支持 import 和 require。
    }

    private static CodeSearchPattern pattern(String regexTemplate) { // 创建规则的短方法。
        return CodeSearchPattern.of(regexTemplate); // 委托规则对象。
    }

    private static CodeLanguage language(String displayName,
                                         String markdownName,
                                         List<String> extensions,
                                         List<CodeSearchPattern> classPatterns,
                                         List<CodeSearchPattern> functionPatterns,
                                         List<CodeSearchPattern> importPatterns,
                                         String singleLineCommentPrefix,
                                         String multiLineCommentStart,
                                         String multiLineCommentEnd,
                                         boolean methodSearchFallbackToKeyword,
                                         boolean componentFileNameClassFallback) { // 构造语言元数据。
        return new CodeLanguage(displayName, markdownName, extensions, classPatterns, functionPatterns, importPatterns,
                singleLineCommentPrefix, multiLineCommentStart, multiLineCommentEnd,
                methodSearchFallbackToKeyword, componentFileNameClassFallback); // 返回不可变语言对象。
    }

    private static String normalizeExtension(String extension) { // 归一化外部传入扩展名。
        if (extension == null || extension.isBlank()) { // 空扩展名。
            return ""; // 返回空。
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT); // 小写并去空白。
        return normalized.startsWith(".") ? normalized.substring(1) : normalized; // 去掉开头点号。
    }
}
