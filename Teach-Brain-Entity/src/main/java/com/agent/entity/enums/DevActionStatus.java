package com.agent.entity.enums;

/**
 * 开发日志保存状态枚举（P6.1 语义化开发日志）。
 *
 * <p>适用场景：表示这条 dev_action_log 记录本身的“保存/处理状态”，写入 dev_action_log.status。
 * 它描述的是“日志这条记录的状态”，例如成功写入、写入失败、待处理、被跳过。</p>
 *
 * <p>调用链：DevActionLogService.saveDevAction 成功落库时写入 {@link #SUCCESS}。</p>
 *
 * <p>边界说明：status 表达“日志记录状态”，与表达“开发行为结果质量”的 {@link DevActionResult} 含义不同，不要混用。</p>
 */
public enum DevActionStatus { // 开发日志保存状态。
    SUCCESS,  // 日志成功落库。
    FAILED,   // 日志处理/写入失败。
    PENDING,  // 待处理。
    SKIPPED;  // 跳过（无需保存）。

    /**
     * 安全解析：非法值返回 {@link #SUCCESS} 兜底。
     */
    public static DevActionStatus fromName(String raw) { // 从字符串解析保存状态。
        if (raw == null || raw.isBlank()) { // 空值兜底为成功。
            return SUCCESS; // 返回默认。
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT); // 统一大写。
        for (DevActionStatus value : values()) { // 遍历枚举。
            if (value.name().equals(normalized)) { // 命中合法值。
                return value; // 返回解析结果。
            }
        }
        return SUCCESS; // 非法值兜底。
    }
}
