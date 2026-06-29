package com.agent.selfdev.patch;

import lombok.Data;

import java.util.List;

/**
 * P10 应用 patch 的内部请求对象。
 *
 * <p>适用场景：ApplyPatchTool 将模型参数转换为本对象，或后续内部编排器直接构造本对象，
 * 再交给 PatchApplyService 执行 dryRun、安全校验、备份和 git apply。</p>
 *
 * <p>调用链：ApplyPatchTool.execute(arguments) -> ApplyPatchRequest -> PatchApplyService.applyPatch
 * -> PatchParser / PatchSafetyGuard / PatchBackupService / PatchCommandExecutor。</p>
 */
@Data
public class ApplyPatchRequest {

    private String workspaceId; // 必填，P9 创建的 sandbox workspace ID。
    private String workspacePath; // 可选，sandbox workspace 相对路径或已校验路径，workspaceId 优先。
    private String patchContent; // 可选，patch/diff 内容，进入日志前必须脱敏或只保存摘要。
    private String patchFilePath; // 可选，patch 文件路径，必须位于 workspace 内。
    private List<String> allowedDirectories; // 可选，本次允许修改目录；为空时使用系统默认白名单。
    private Boolean dryRun; // 是否只校验不应用，默认 false。
    private Boolean backupEnabled; // 是否应用前备份，默认 true，第一版不允许关闭。
    private Boolean rollbackOnFailure; // 应用失败是否自动回滚，默认 true，第一版不允许关闭。
    private Integer maxChangedFiles; // 本次最多允许变更文件数，默认取配置。
    private Boolean requireConfirm; // 是否要求确认标记，默认 true。
    private String confirmToken; // requireConfirm=true 时必须为 APPLY_PATCH。
    private Long userId; // Tool 上下文用户 ID，用于 dev_action_log。
    private Long conversationId; // Tool 上下文会话 ID，用于 dev_action_log。
    private String traceId; // Tool 调用链路 ID，用于 dev_action_log 与 tool_call_log 串联。
}
