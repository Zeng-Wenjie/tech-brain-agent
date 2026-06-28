package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P9 沙箱 workspace 配置。
 *
 * <p>适用场景：为后续 Claude Code、自迭代开发、编译验证等流程提供隔离工作区配置。
 * 本配置只描述“沙箱根目录、只读源码目录、Git 来源、保留策略、复制/克隆开关”等基础参数，
 * 不负责真实调用 Claude Code，也不负责生成或应用 patch。</p>
 *
 * <p>调用链：application.yml -> Spring Boot ConfigurationProperties 绑定
 * -> SandboxWorkspaceGuard 做路径护栏 -> SandboxWorkspaceService 创建、恢复、清理 workspace。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "techbrain.selfdev.workspace")
public class SandboxWorkspaceProperties {

    private String sandboxRoot = "../selfdev-workspaces"; // 沙箱根目录，默认放到当前运行目录之外。
    private String sourceRepoDir = "."; // 真实源码目录，只能作为 LOCAL_COPY 的只读来源。
    private String gitRemoteUrl; // 可选 Git 远程地址，启用 GIT_CLONE 时使用。
    private String defaultBranch = "main"; // 默认基线分支。
    private String workspacePrefix = "selfdev-"; // workspace 目录名前缀，清理时只处理此前缀目录。
    private String branchPrefix = "selfdev/"; // 临时开发分支名前缀，只在沙箱仓库内创建。
    private Integer maxWorkspaces = 20; // 沙箱根目录最多保留的 workspace 数量。
    private Integer retentionDays = 7; // 超过该天数的旧 workspace 可被清理。
    private Integer maxOperationTimeoutSeconds = 120; // Git 等外部命令最大等待时间。
    private Boolean allowLocalCopy = true; // 是否允许从本地 sourceRepoDir 复制代码。
    private Boolean allowGitClone = true; // 是否允许从 gitRemoteUrl 克隆代码。
    private Boolean protectSourceRepo = true; // 是否启用 sourceRepoDir 写保护，默认必须开启。
}
