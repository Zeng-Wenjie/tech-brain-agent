package com.agent.entity.dto;

import lombok.Data;

/**
 * P9 创建沙箱 workspace 的内部请求对象。
 *
 * <p>适用场景：后端 Service 或后续编排器发起隔离 workspace 创建时使用，承载用户、会话、
 * 需求文本、来源类型、基线分支和临时分支参数。第一版不暴露为 AI Tool，也不直接暴露给前端。</p>
 *
 * <p>调用链：后续 SelfDev 编排入口构造本对象 -> SandboxWorkspaceService.createWorkspace
 * -> SandboxWorkspaceGuard 校验路径 -> LOCAL_COPY/GIT_CLONE 生成代码副本。</p>
 */
@Data
public class SandboxWorkspaceCreateRequest {

    private Long userId; // 触发用户 ID，用于 dev_action_log 归属。
    private Long conversationId; // 关联会话 ID，可为空。
    private String traceId; // 链路追踪 ID，可为空，Service 会兜底生成。
    private String taskId; // 开发任务 ID，用于生成临时分支名。
    private String requirement; // 本次开发需求，只用于日志 intent/summary，不交给 Claude Code。
    private String sourceType; // 代码来源：LOCAL_COPY 或 GIT_CLONE。
    private String sourceRepoDir; // 只允许等于配置的 sourceRepoDir，避免任意目录读取。
    private String gitRemoteUrl; // 可选 Git 远程地址，启用 GIT_CLONE 时使用。
    private String baseBranch; // 基线分支，为空时使用配置 defaultBranch。
    private String workspaceName; // 可选 workspace 名称，Service 会清洗并追加唯一 ID。
    private Boolean createBranch; // 是否创建临时开发分支，默认 true。
    private String branchName; // 可选临时分支名，Service 会清洗非法字符。
}
