package com.agent.entity.enums;

/**
 * 开发行为类型枚举（P6.1 语义化开发日志）。
 *
 * <p>适用场景：标记一条 dev_action_log 属于哪一类开发行为，写入 dev_action_log.action_type。
 * P6.1 实际只接 {@link #CODE_ANALYSIS}（analyzeCode 分析结果保存，对应 P5.9）；其余取值是为 P7-P14
 * （修改方案 / patch / 文件修改 / 编译 / 前端构建 / 发布确认 / 回滚）预留的语义占位，本步骤不接入真实流程。</p>
 *
 * <p>调用链：DevActionLogService.saveDevAction / recordCodeAnalysis 等在构造 DevActionLog 时写入 action_type；
 * 后续 P17 记忆召回可按 action_type 过滤“分析类 / 修改类 / 验证类”行为。</p>
 *
 * <p>边界说明：本枚举只描述行为类别，不代表行为结果质量（结果质量见 {@link DevActionResult}），
 * 也不代表日志保存状态（见 {@link DevActionStatus}）。</p>
 */
public enum DevActionType { // 开发行为类型。
    CODE_ANALYSIS,            // 代码分析（P5.9 已接入：结构/调用链/风险/测试步骤/说明等统一归类）。
    CHANGE_PLAN_GENERATED,    // 生成修改方案（P7 预留）。
    PATCH_GENERATED,          // 生成 patch（P8 预留）。
    FILE_MODIFIED,            // 文件修改（P10 预留）。
    COMPILE_VERIFIED,         // 编译验证（P11 预留）。
    FRONTEND_BUILD_VERIFIED,  // 前端构建验证（预留）。
    RELEASE_CONFIRMED,        // 发布/上线确认（预留）。
    ROLLBACK_EXECUTED;        // 回滚执行（P14 预留）。

    /**
     * 安全解析：忽略大小写与首尾空白，非法值返回 {@link #CODE_ANALYSIS} 兜底，避免脏字符串影响保存。
     */
    public static DevActionType fromName(String raw) { // 从字符串解析行为类型。
        if (raw == null || raw.isBlank()) { // 空值兜底为代码分析。
            return CODE_ANALYSIS; // 返回默认类型。
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT); // 统一大写。
        for (DevActionType type : values()) { // 遍历枚举。
            if (type.name().equals(normalized)) { // 命中合法值。
                return type; // 返回解析结果。
            }
        }
        return CODE_ANALYSIS; // 非法值兜底。
    }
}
