package com.agent.entity.enums;

/**
 * 开发行为目标类型枚举（P6.1 语义化开发日志）。
 *
 * <p>适用场景：表示一条 dev_action_log 的目标维度，写入 dev_action_log.target_type，供后续 P17 记忆召回
 * 按“类 / 方法 / 接口 / Tool / 事件 / 模块 / 文件”等维度检索定位。</p>
 *
 * <p>调用链：DevActionLogService.saveDevAction 在保存时按 endpoint/toolName/className/methodName/targetFile
 * 等已知目标推断 target_type；无法判断时使用 {@link #AUTO}。</p>
 *
 * <p>边界说明：本枚举只描述目标维度；FILE/CLASS/METHOD/ENDPOINT/TOOL/EVENT/MODULE 为已接入维度，
 * PATCH/BUILD/ROLLBACK 为 P7-P14 预留。</p>
 */
public enum DevTargetType { // 开发行为目标类型。
    FILE,      // 文件维度（workspace 相对路径）。
    CLASS,     // 类维度。
    METHOD,    // 方法维度。
    ENDPOINT,  // 接口路径维度。
    TOOL,      // AI Tool 维度。
    EVENT,     // SSE 事件维度。
    MODULE,    // 模块维度（如 Tech-Brain-Agent）。
    PATCH,     // patch 维度（P8 预留）。
    BUILD,     // 编译/构建维度（P11 预留）。
    ROLLBACK,  // 回滚维度（P14 预留）。
    AUTO;      // 无法判断时的兜底。

    /**
     * 安全解析：非法值返回 {@link #AUTO} 兜底。
     */
    public static DevTargetType fromName(String raw) { // 从字符串解析目标类型。
        if (raw == null || raw.isBlank()) { // 空值兜底为 AUTO。
            return AUTO; // 返回默认。
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT); // 统一大写。
        for (DevTargetType value : values()) { // 遍历枚举。
            if (value.name().equals(normalized)) { // 命中合法值。
                return value; // 返回解析结果。
            }
        }
        return AUTO; // 非法值兜底。
    }
}
