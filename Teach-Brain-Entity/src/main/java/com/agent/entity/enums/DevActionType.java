package com.agent.entity.enums;

import java.util.Locale;

/**
 * 开发行为类型枚举。
 *
 * <p>适用场景：标识一条 dev_action_log 属于哪一类开发行为，供后续开发记忆召回、质量筛选、
 * 沙箱审计、编译验证和回滚链路按 action_type 聚合或过滤。</p>
 *
 * <p>调用链：DevActionLogService.saveDevAction 解析 actionType -> 写入 dev_action_log.action_type；
 * SandboxWorkspaceService、SelfDevOrchestrator 以及后续 P10-P14 流程都复用本枚举。</p>
 */
public enum DevActionType {

    CLAUDE_CODE_EXECUTED, // Claude Code 沙箱开发执行。
    CODE_ANALYSIS, // 历史代码分析类型，保留兼容已有数据。
    CHANGE_PLAN_GENERATED, // 生成修改方案，P7 预留。
    PATCH_GENERATED, // 生成 patch，P8 预留。
    FILE_MODIFIED, // 文件修改，P10 预留。
    COMPILE_VERIFIED, // 编译验证，P11 预留。
    FRONTEND_BUILD_VERIFIED, // 前端构建验证，P12 预留。
    RELEASE_CONFIRMED, // 发布或人工确认，P13 预留。
    ROLLBACK_EXECUTED, // 回滚执行，P14 预留。
    SANDBOX_WORKSPACE_CREATED, // P9 创建隔离沙箱 workspace。
    SANDBOX_WORKSPACE_CLEANED, // P9 清理旧沙箱 workspace。
    SANDBOX_WORKSPACE_RESTORED, // P9 恢复沙箱 workspace 干净状态。
    SANDBOX_WORKSPACE_VALIDATED; // P9 校验 Claude Code 工作目录。

    public static DevActionType fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return CODE_ANALYSIS; // 空值沿用历史兜底类型，避免旧调用保存失败。
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT); // 忽略大小写和首尾空白。
        for (DevActionType type : values()) {
            if (type.name().equals(normalized)) {
                return type; // 命中合法枚举。
            }
        }
        return CODE_ANALYSIS; // 非法值兜底，保证日志链路不抛异常。
    }
}
