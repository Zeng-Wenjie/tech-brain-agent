package com.agent.entity.enums;

/**
 * 开发行为结果质量枚举（P6.1 语义化开发日志）。
 *
 * <p>适用场景：表示一次开发行为本身的“结果质量”，写入 dev_action_log.result，供后续 P18 记忆质量筛选使用
 * （例如只召回 SUCCESS / 过滤 FAILED 或 ROLLED_BACK 的低质量记忆）。</p>
 *
 * <p>调用链：DevActionLogService.saveDevAction 在保存时写入 result；P5.9 代码分析成功保存时为 {@link #SUCCESS}。</p>
 *
 * <p>边界说明：result 表达“行为做得怎么样”，与“日志是否成功落库”的 {@link DevActionStatus} 不是一回事，不要混用。</p>
 */
public enum DevActionResult { // 开发行为结果质量。
    SUCCESS,      // 行为成功（如分析成功并保存成功）。
    FAILED,       // 行为失败（如分析失败、P11 编译失败）。
    PARTIAL,      // 部分成功。
    PENDING,      // 待确认（结果尚未定论）。
    ROLLED_BACK;  // 已回滚（P14 预留）。

    /**
     * 安全解析：非法值返回 {@link #SUCCESS} 兜底。
     */
    public static DevActionResult fromName(String raw) { // 从字符串解析结果质量。
        if (raw == null || raw.isBlank()) { // 空值兜底为成功。
            return SUCCESS; // 返回默认。
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT); // 统一大写。
        for (DevActionResult value : values()) { // 遍历枚举。
            if (value.name().equals(normalized)) { // 命中合法值。
                return value; // 返回解析结果。
            }
        }
        return SUCCESS; // 非法值兜底。
    }
}
