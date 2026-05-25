package com.agent.toolcalling.summary;

import java.util.Locale;

/**
 * 通用总结类型协议常量与归一化工具。
 *
 * <p>适用场景：ToolCallingChatServiceImpl 在强制总结路由中解析 summaryType，SummarizeArticleTool 在读取
 * tool arguments 后校验 summaryType，SummaryServiceImpl 在构造总结 Prompt 前最终兜底 summaryType。</p>
 * <p>调用链：/chat/message -> ToolCallingChatServiceImpl.resolveSummaryType(...) -> summarizeArticleTool arguments
 * -> SummarizeArticleTool.execute(...) -> SummaryService.summarize(...) -> SummaryServiceImpl.normalizeSummaryType(...)</p>
 * <p>本类只定义通用协议字符串和轻量归一化规则，不依赖具体文章、笔记、数据库、Redis 或任何业务 Tool。</p>
 */
public final class SummaryTypeConstants {
    public static final String NORMAL = "normal"; // 普通自然语言摘要。
    public static final String POINTS = "points"; // 要点/列表式总结。
    public static final String INTERVIEW = "interview"; // 面试话术式总结。

    private static final String[] NORMAL_KEYWORDS = { // 中文普通摘要兼容词。
            "普通",
            "摘要",
            "总结"
    };
    private static final String[] POINTS_KEYWORDS = { // 中文要点摘要兼容词。
            "要点",
            "列点",
            "核心要点",
            "提炼"
    };
    private static final String[] INTERVIEW_KEYWORDS = { // 中文面试话术兼容词。
            "面试",
            "面试话术",
            "面试表达"
    };

    private SummaryTypeConstants() {
        // 工具类不允许实例化。
    }

    public static String normalizeSummaryType(String summaryType) {
        if (summaryType == null || summaryType.trim().isEmpty()) { // 空值统一兜底为普通摘要。
            return NORMAL; // 返回 normal。
        }
        String normalized = summaryType.trim().toLowerCase(Locale.ROOT); // 英文类型统一小写。
        if (NORMAL.equals(normalized) || POINTS.equals(normalized) || INTERVIEW.equals(normalized)) { // 合法英文类型直接返回。
            return normalized; // 保留 normal/points/interview。
        }
        if (containsAny(normalized, INTERVIEW_KEYWORDS)) { // 面试类优先级最高，避免“面试要点”被识别成 points。
            return INTERVIEW; // 返回 interview。
        }
        if (containsAny(normalized, POINTS_KEYWORDS)) { // 要点类关键词命中时返回 points。
            return POINTS; // 返回 points。
        }
        if (containsAny(normalized, NORMAL_KEYWORDS)) { // 普通摘要类关键词命中时返回 normal。
            return NORMAL; // 返回 normal。
        }
        return NORMAL; // 其它非法值统一兜底 normal。
    }

    private static boolean containsAny(String content, String[] keywords) {
        if (content == null || keywords == null || keywords.length == 0) { // 空文本或空关键词不匹配。
            return false; // 返回 false。
        }
        for (String keyword : keywords) { // 遍历兼容关键词。
            if (keyword != null && !keyword.isBlank() && content.contains(keyword.toLowerCase(Locale.ROOT))) { // 命中任意关键词。
                return true; // 返回 true。
            }
        }
        return false; // 未命中任何关键词。
    }
}
