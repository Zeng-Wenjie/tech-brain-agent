package com.agent.entity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * P9 沙箱 workspace 信息对象。
 *
 * <p>适用场景：SandboxWorkspaceService 创建、查询 workspace 后返回给内部调用方，
 * 描述 workspace 标识、路径、分支、来源和生命周期状态。</p>
 *
 * <p>调用链：SandboxWorkspaceService.createWorkspace/getWorkspaceInfo 构造本对象；
 * 后续 Claude Code 客户端只能使用已通过 SandboxWorkspaceGuard 校验的 workspacePath。</p>
 */
@Data
public class SandboxWorkspaceInfo {

    private String workspaceId; // 唯一 workspace ID。
    private String workspaceName; // sandboxRoot 下的目录名。
    private String workspacePath; // 绝对路径，仅供后端内部使用，不写入 dev_action_log。
    private String relativeWorkspacePath; // 相对 sandboxRoot 的安全路径，可写入日志。
    private String branchName; // 临时开发分支名。
    private String baseBranch; // 基线分支。
    private String sourceType; // LOCAL_COPY 或 GIT_CLONE。
    private String status; // CREATED、READY、FAILED、CLEANED、RESTORED。
    private LocalDateTime createdAt; // workspace 创建时间。
    private LocalDateTime updatedAt; // workspace 更新时间。
}
