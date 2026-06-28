package com.agent.entity.dto;

import lombok.Data;

/**
 * P9 沙箱 workspace 操作结果。
 *
 * <p>适用场景：清理、恢复、校验等非创建类操作返回统一结果，便于后续编排器判断是否允许继续进入
 * Claude Code、编译验证或人工确认流程。</p>
 *
 * <p>调用链：SandboxWorkspaceService.cleanOldWorkspaces/restoreCleanState/validateWorkspaceForClaude
 * -> 调用方根据 success 和 message 决定下一步。</p>
 */
@Data
public class SandboxWorkspaceOperationResult {

    private boolean success; // 操作是否成功。
    private String workspaceId; // 关联 workspace ID，可为空。
    private String workspacePath; // 绝对路径，仅供后端内部使用，不写入 dev_action_log。
    private String branchName; // 关联分支名，可为空。
    private String message; // 给内部调用方看的简短结果说明。
    private String errorMsg; // 失败原因，可为空。

    public static SandboxWorkspaceOperationResult success(String message) { // 构造成功结果。
        SandboxWorkspaceOperationResult result = new SandboxWorkspaceOperationResult(); // 创建结果对象。
        result.setSuccess(true); // 标记成功。
        result.setMessage(message); // 写入说明。
        return result; // 返回成功结果。
    }

    public static SandboxWorkspaceOperationResult failed(String errorMsg) { // 构造失败结果。
        SandboxWorkspaceOperationResult result = new SandboxWorkspaceOperationResult(); // 创建结果对象。
        result.setSuccess(false); // 标记失败。
        result.setMessage(errorMsg); // 失败时 message 与 errorMsg 保持一致。
        result.setErrorMsg(errorMsg); // 写入失败原因。
        return result; // 返回失败结果。
    }
}
