package com.agent.toolcalling.project;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目代码统一目标解析器（P5 路由复用组件）。
 *
 * <p>适用场景：6 个项目代码工具（listProjectTree / searchCode / readProjectFile / analyzeCode /
 * analyzeCode 内部不同 analysisType（STRUCTURE / CALL_CHAIN / CONTROLLER_SERVICE 等）在路由阶段都需要回答同一个问题：
 * “用户这一轮指向的是接口路径、类名、文件、方法名，还是仅仅是‘这个类/这个接口/继续分析’这类指代？
 * 是否允许使用上一轮的 recentProjectTarget 或 projectFileFocus？” 过去这套判断分散在各 force 分支里，容易让 projectFileFocus
 * 把明确目标锁死。本组件把目标解析与优先级集中到一处，供所有项目代码工具路由复用。</p>
 *
 * <p>统一优先级：<b>明确目标 &gt; 全项目扫描解析目标 &gt; recentProjectTarget &gt; projectFileFocus</b>，即
 * endpoint &gt; 明确文件/类名(path) &gt; 方法名 &gt; recentProjectTarget &gt; projectFileFocus。只要用户给了 endpoint / 类名 /
 * 文件名 / 方法名，就必须走全项目扫描，不允许被当前 focus 限制；只有“这个类 / 这个接口 / 继续分析 / 给我代码”
 * 这类没有明确目标的指代追问，才允许先使用 recentProjectTarget，再回退 projectFileFocus。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 路由阶段
 * -> resolve(userMessage, explicitProjectPath, hasRecentProjectTarget, hasProjectFocus, controllerFocus) 得到 {@link ProjectCodeTargetResolution}
 * -> 各 force 分支据此决定调用哪个工具、用 endpoint 还是 path、是否允许 focus
 * -> 真正的全项目扫描由工具在 Tech-Brain-Agent 内基于 ProjectPathGuard 完成（覆盖所有子模块）。</p>
 *
 * <p>边界说明：本组件只做“文本级目标识别”，不读取磁盘、不扫描文件、不接入 RAG/数据库/Milvus；
 * explicitProjectPath（明确文件路径/类名定位结果）由调用方复用既有解析逻辑后传入，避免在此重复实现。</p>
 */
@Component // 注册为 Spring Bean，供 ToolCallingChatServiceImpl 及后续项目代码类工具复用，统一目标解析策略。
public class ProjectCodeTargetResolver { // 项目代码统一目标解析器。

    // 接口路径：识别 /api/agent/chat、/chat/message、/batch、/article/ai/summary/{id} 这类 endpoint。
    private static final Pattern ENDPOINT_PATTERN = Pattern.compile("(/[A-Za-z0-9_{}\\-]+(?:/[A-Za-z0-9_{}\\-]+)*)"); // 至少一段 / 开头路径。
    // 方法名：识别“sendMessage 方法 / execute 方法调用链 / xxx 函数”里的方法名。
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:方法|函数|method)"); // 方法名直接接“方法/函数/method”。
    // Tool 类名：识别 SearchCodeTool、ReadProjectFileTool.java 这类 AI Tool 目标。
    private static final Pattern TOOL_CLASS_NAME_PATTERN = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*Tool)(?:\\.java)?\\b"); // 只识别以 Tool 结尾的类名。
    // Tool 名称：识别“searchCode Tool / readProjectFile 工具”这类 toolName 目标。
    private static final Pattern TOOL_NAME_HINT_PATTERN = Pattern.compile("\\b([a-z][A-Za-z0-9_$]{2,})\\s*(?:Tool|tool|工具)\\b"); // 小驼峰 toolName + Tool/工具。

    private static final Set<String> METHOD_STOP_WORDS = Set.of( // 方法名提取需要排除的停用词。
            "this", "new", "return", "class", "interface", "controller", "service",
            "mapper", "repository", "tool", "impl", "void", "public", "private", "static"); // 这些词不是真正的方法名。

    /**
     * 从消息中提取接口路径（endpoint）。识别 /api/xxx、/chat/message、/batch 等，排除 Tech-Brain-Agent/src/... 这类项目文件路径片段。
     */
    public String extractEndpoint(String message) { // 统一的 endpoint 提取入口，供 Controller→Service 链路等复用。
        if (message == null || message.isBlank() || !message.contains("/")) { // 没有斜杠不会有接口路径。
            return null; // 返回 null。
        }
        Matcher matcher = ENDPOINT_PATTERN.matcher(message); // 匹配 / 开头的接口路径。
        while (matcher.find()) { // 逐个候选。
            String candidate = matcher.group(1); // 候选接口路径。
            int start = matcher.start(1); // 命中起点，用于排除项目文件路径中间的斜杠。
            int end = matcher.end(1); // 命中终点，用于排除 /AgentController.java 被截断成 endpoint。
            if (candidate != null && candidate.length() > 1
                    && !candidate.contains(".")
                    && !isEndpointEmbeddedInProjectPath(message, start, end)) { // 排除项目文件路径里的片段。
                return sanitizeEndpoint(candidate); // 返回清理后的接口路径。
            }
        }
        return null; // 没有接口路径。
    }

    private boolean isEndpointEmbeddedInProjectPath(String message, int start, int end) { // 判断 /xxx 是否只是项目文件路径的一段。
        char previous = start > 0 ? message.charAt(start - 1) : ' '; // 查看 endpoint 前一个字符。
        char next = end < message.length() ? message.charAt(end) : ' '; // 查看 endpoint 后一个字符。
        return Character.isLetterOrDigit(previous)
                || previous == '_' || previous == '-' || previous == '.'
                || next == '.' || next == '\\'; // 前后紧贴文件路径字符时不当作 API endpoint。
    }

    private String sanitizeEndpoint(String candidate) { // 清理自然语言中的 endpoint 候选末尾标点。
        String endpoint = candidate == null ? "" : candidate.trim(); // 去掉首尾空白。
        while (endpoint.endsWith("，") || endpoint.endsWith("。") || endpoint.endsWith("；")
                || endpoint.endsWith(";") || endpoint.endsWith(",") || endpoint.endsWith("？")
                || endpoint.endsWith("?") || endpoint.endsWith("！") || endpoint.endsWith("!")
                || endpoint.endsWith("：") || endpoint.endsWith(":")) { // 去掉句尾标点。
            endpoint = endpoint.substring(0, endpoint.length() - 1); // 删除末尾标点。
        }
        return endpoint.isBlank() ? null : endpoint; // 返回清理后的 endpoint。
    }

    /**
     * 从消息中提取方法名（仅识别“xxx 方法 / xxx 函数 / xxx method”形态），用于方法名级别的全项目目标识别。
     */
    public String extractMethodName(String message) { // 统一的方法名提取入口。
        if (message == null || message.isBlank()) { // 空消息没有方法名。
            return null; // 返回 null。
        }
        Matcher matcher = METHOD_NAME_PATTERN.matcher(message); // 匹配“方法名 + 方法/函数/method”。
        while (matcher.find()) { // 逐个候选。
            String candidate = matcher.group(1); // 候选方法名。
            if (candidate != null && !candidate.isBlank()
                    && !METHOD_STOP_WORDS.contains(candidate.toLowerCase(Locale.ROOT))) { // 排除停用词。
                return candidate; // 返回方法名。
            }
        }
        return null; // 没有方法名。
    }

    /**
     * 从用户消息中提取 AI Tool 类名，例如 SearchCodeTool 或 ReadProjectFileTool.java。
     */
    public String extractToolClassName(String message) { // Tool 类名提取入口，供 P5.4 Tool→Service 路由复用。
        if (message == null || message.isBlank()) { // 空消息没有 Tool 类名。
            return null; // 返回 null。
        }
        Matcher matcher = TOOL_CLASS_NAME_PATTERN.matcher(message); // 匹配 XxxTool。
        while (matcher.find()) { // 逐个检查候选。
            String candidate = matcher.group(1); // 候选 Tool 类名。
            if (candidate != null && !"Tool".equals(candidate)) { // 排除纯 Tool 单词。
                return candidate; // 返回 Tool 类名。
            }
        }
        return null; // 未找到 Tool 类名。
    }

    /**
     * 从用户消息中提取 AI Tool 的 toolName。优先使用已注册工具名集合做精确匹配，避免把普通英文单词误判成工具名。
     */
    public String extractToolName(String message, Collection<String> knownToolNames) { // Tool 名称提取入口。
        if (message == null || message.isBlank()) { // 空消息没有工具名。
            return null; // 返回 null。
        }
        if (knownToolNames != null && !knownToolNames.isEmpty()) { // 有已知工具名时优先精确匹配。
            return knownToolNames.stream()
                    .filter(name -> name != null && !name.isBlank()) // 跳过空工具名。
                    .sorted(Comparator.comparingInt(String::length).reversed()) // 长名称优先，避免 readFile 抢 readProjectFile。
                    .filter(name -> Pattern.compile("(?i)(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])")
                            .matcher(message).find()) // 按单词边界匹配 toolName。
                    .findFirst()
                    .orElse(null); // 未命中返回 null。
        }
        Matcher matcher = TOOL_NAME_HINT_PATTERN.matcher(message); // 兜底识别“xxx Tool/工具”。
        if (matcher.find()) { // 命中提示形态。
            return matcher.group(1); // 返回 toolName 候选。
        }
        return null; // 未找到 toolName。
    }

    /**
     * 统一解析项目代码目标，确定目标类型、是否允许使用 recentProjectTarget 或 projectFileFocus。
     *
     * @param message            用户消息
     * @param explicitProjectPath 调用方复用既有解析得到的明确文件路径/类名定位结果（可空）
     * @param hasRecentProjectTarget 当前会话是否有可用 recentProjectTarget
     * @param hasProjectFocus    当前会话是否有可用 projectFileFocus
     * @param controllerFocus    当前 projectFileFocus 是否指向 Controller 文件
     */
    public ProjectCodeTargetResolution resolve(String message,
                                               String explicitProjectPath,
                                               boolean hasRecentProjectTarget,
                                               boolean hasProjectFocus,
                                               boolean controllerFocus) { // 统一目标解析主入口。
        // 1. endpoint 优先级最高：全项目扫描 Controller，不绑定 focus、不用上一轮 Controller。
        String endpoint = extractEndpoint(message); // 提取接口路径。
        if (endpoint != null) { // 命中 endpoint。
            return ProjectCodeTargetResolution.builder()
                    .success(true)
                    .targetType(ProjectCodeTargetResolution.TargetType.ENDPOINT)
                    .confidence(ProjectCodeTargetResolution.Confidence.EXACT)
                    .query(endpoint)
                    .endpoint(endpoint)
                    .useFocus(false) // endpoint 查询绝不使用 focus。
                    .message("识别到接口路径，需全项目扫描 Controller。")
                    .build(); // 返回 ENDPOINT 目标。
        }
        // 2. 明确文件路径/类名：全项目定位文件/类，不绑定 focus。
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()) { // 命中明确路径或类名。
            ProjectCodeTargetResolution.TargetType targetType = resolveExplicitTargetType(explicitProjectPath); // 区分普通文件/类和 AI Tool 目标。
            return ProjectCodeTargetResolution.builder()
                    .success(true)
                    .targetType(targetType)
                    .confidence(ProjectCodeTargetResolution.Confidence.EXACT)
                    .query(explicitProjectPath)
                    .path(explicitProjectPath)
                    .className(deriveClassName(explicitProjectPath))
                    .useFocus(false) // 明确文件/类名绝不使用 focus。
                    .message(targetType == ProjectCodeTargetResolution.TargetType.TOOL
                            ? "识别到明确 AI Tool 目标，需全项目定位 Tool 文件。"
                            : "识别到明确文件或类名，需全项目定位。")
                    .build(); // 返回 FILE/CLASS 目标。
        }
        // 3. 方法名（无类名）：全项目搜索方法声明，不绑定 focus。
        String methodName = extractMethodName(message); // 提取方法名。
        if (methodName != null) { // 命中独立方法名。
            return ProjectCodeTargetResolution.builder()
                    .success(true)
                    .targetType(ProjectCodeTargetResolution.TargetType.METHOD)
                    .confidence(ProjectCodeTargetResolution.Confidence.EXACT)
                    .query(methodName)
                    .methodName(methodName)
                    .useFocus(false) // 方法名查询绝不使用 focus。
                    .message("识别到方法名，需全项目搜索方法声明。")
                    .build(); // 返回 METHOD 目标。
        }
        // 4. 无明确目标：只有指代追问才允许使用 recentProjectTarget/projectFileFocus。
        if (!isReferenceFollowUp(message)) { // 普通闲聊或没有项目指代语义的问题不能使用上下文目标。
            return ProjectCodeTargetResolution.builder()
                    .success(false)
                    .targetType(ProjectCodeTargetResolution.TargetType.UNKNOWN)
                    .confidence(ProjectCodeTargetResolution.Confidence.NONE)
                    .useFocus(false)
                    .message("未识别到明确项目代码目标，也不是项目目标指代追问。")
                    .build(); // 返回 UNKNOWN。
        }
        // 5. 指代追问优先使用 recentProjectTarget。
        if (hasRecentProjectTarget) { // 有最近明确项目目标。
            return ProjectCodeTargetResolution.builder()
                    .success(true)
                    .targetType(ProjectCodeTargetResolution.TargetType.RECENT_PROJECT_TARGET)
                    .confidence(ProjectCodeTargetResolution.Confidence.UNIQUE)
                    .useFocus(true) // 指代追问场景可使用最近明确目标。
                    .message("无明确目标，使用最近明确项目目标。")
                    .build(); // 返回 RECENT_PROJECT_TARGET 目标。
        }
        // 6. 无明确目标且没有 recentProjectTarget：才允许使用 projectFileFocus。
        if (hasProjectFocus) { // 有可用 focus。
            return ProjectCodeTargetResolution.builder()
                    .success(true)
                    .targetType(ProjectCodeTargetResolution.TargetType.FOCUS)
                    .confidence(ProjectCodeTargetResolution.Confidence.UNIQUE)
                    .useFocus(true) // 仅指代追问场景使用 focus。
                    .message(controllerFocus
                            ? "无明确目标，使用当前 Controller 焦点。"
                            : "无明确目标，使用当前项目文件焦点。")
                    .build(); // 返回 FOCUS 目标。
        }
        // 7. 既无明确目标也无 focus。
        return ProjectCodeTargetResolution.builder()
                .success(false)
                .targetType(ProjectCodeTargetResolution.TargetType.UNKNOWN)
                .confidence(ProjectCodeTargetResolution.Confidence.NONE)
                .useFocus(false)
                .message("未识别到明确项目代码目标。")
                .build(); // 返回 UNKNOWN。
    }

    private boolean isReferenceFollowUp(String message) { // 判断当前消息是否是“它/这个类/刚才那个/继续分析”等项目目标指代追问。
        if (message == null || message.isBlank()) { // 空消息不是追问。
            return false; // 返回false。
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT); // 统一小写兼容 Tool/controller 等英文。
        return normalizedMessage.contains("它")
                || normalizedMessage.contains("这个")
                || normalizedMessage.contains("当前")
                || normalizedMessage.contains("刚才")
                || normalizedMessage.contains("刚刚")
                || normalizedMessage.contains("上面")
                || normalizedMessage.contains("继续")
                || normalizedMessage.contains("这个类")
                || normalizedMessage.contains("这个文件")
                || normalizedMessage.contains("这个工具")
                || normalizedMessage.contains("这个接口")
                || normalizedMessage.contains("这个 controller")
                || normalizedMessage.contains("这个controller")
                || normalizedMessage.contains("这个 tool")
                || normalizedMessage.contains("这个tool")
                || normalizedMessage.contains("分析它")
                || normalizedMessage.contains("给我它")
                || normalizedMessage.contains("its")
                || normalizedMessage.contains("this class")
                || normalizedMessage.contains("this file")
                || normalizedMessage.contains("this tool"); // 只允许明确指代词触发上下文回退。
    }

    /**
     * 判断消息中是否存在“明确目标”（endpoint / 文件或类名 / 方法名），用于决定是否允许回退 recentProjectTarget/projectFileFocus。
     */
    public boolean hasExplicitTarget(String message, String explicitProjectPath) { // 明确目标判断，供 focus 门控复用。
        return extractEndpoint(message) != null
                || (explicitProjectPath != null && !explicitProjectPath.isBlank())
                || extractToolClassName(message) != null
                || extractMethodName(message) != null; // 任一明确目标命中即视为有明确目标。
    }

    private ProjectCodeTargetResolution.TargetType resolveExplicitTargetType(String explicitProjectPath) { // 将明确目标进一步区分为 FILE/CLASS/TOOL。
        String normalized = explicitProjectPath == null ? "" : explicitProjectPath.replace('\\', '/'); // 统一分隔符。
        String lower = normalized.toLowerCase(Locale.ROOT); // 小写便于判断路径。
        String className = deriveClassName(normalized); // 尝试推导类名。
        if (looksLikeToolTarget(normalized, className)) { // AI Tool 文件或类名。
            return ProjectCodeTargetResolution.TargetType.TOOL; // 返回 TOOL 目标。
        }
        if (lower.endsWith(".java") && !normalized.contains("/")) { // 仅 Java 文件名时视为类目标。
            return ProjectCodeTargetResolution.TargetType.CLASS; // 返回 CLASS。
        }
        return ProjectCodeTargetResolution.TargetType.FILE; // 其它明确路径视为 FILE。
    }

    private boolean looksLikeToolTarget(String normalizedPath, String className) { // 判断明确目标是否像 AI Tool。
        String safePath = normalizedPath == null ? "" : normalizedPath; // 路径兜底。
        String safeClassName = className == null ? safePath : className; // 类名兜底。
        return safeClassName.endsWith("Tool") || safePath.contains("/tool/") || safePath.endsWith("Tool.java"); // 命中 Tool 类名或 tool 包路径。
    }

    private String deriveClassName(String explicitProjectPath) { // 从文件路径/文件名推导类名。
        if (explicitProjectPath == null || explicitProjectPath.isBlank()) { // 空路径无类名。
            return null; // 返回 null。
        }
        String normalized = explicitProjectPath.replace('\\', '/'); // 统一分隔符。
        int slash = normalized.lastIndexOf('/'); // 取最后一段文件名。
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized; // 文件名。
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".java")) { // Java 文件名去掉扩展名即类名。
            return fileName.substring(0, fileName.length() - ".java".length()); // 返回类名。
        }
        return null; // 非 Java 文件不推导类名。
    }
}
