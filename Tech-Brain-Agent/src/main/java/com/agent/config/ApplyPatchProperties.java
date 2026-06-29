package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P10 应用 patch 的安全配置。
 *
 * <p>适用场景：集中配置 ApplyPatchTool / PatchApplyService 的默认允许目录、保护目录、
 * 敏感文件黑名单、备份目录和 patch 大小限制。Java 代码只提供安全默认值，实际生产路径应由
 * application.yml 或环境变量覆盖。</p>
 *
 * <p>调用链：application.yml -> ApplyPatchProperties -> PatchSafetyGuard / PatchBackupService
 * / PatchApplyService，在真正执行 git apply 前完成目录白名单、敏感文件和备份策略约束。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "techbrain.selfdev.patch")
public class ApplyPatchProperties {

    private List<String> defaultAllowedDirectories = new ArrayList<>(List.of(
            "Tech-Brain-Notes/src/main/java",
            "Tech-Brain-Notes/src/main/resources",
            "business-plugins"
    )); // 默认只允许业务模块目录，第一版不默认放开自迭代核心框架。
    private List<String> protectedDirectories = new ArrayList<>(List.of(
            "Tech-Brain-Agent/src/main/java/com/agent/security",
            "Tech-Brain-Agent/src/main/java/com/agent/tool",
            "Tech-Brain-Agent/src/main/java/com/agent/config",
            "Tech-Brain-Agent/src/main/java/com/agent/selfdev",
            "Tech-Brain-Agent/src/main/resources"
    )); // 核心框架和配置目录默认保护，P10 第一版拒绝修改。
    private List<String> blockedFileNames = new ArrayList<>(List.of(
            ".env",
            ".env.local",
            ".env.production",
            "application-prod.yml",
            "application-secret.yml",
            "bootstrap-prod.yml",
            "id_rsa",
            "id_dsa",
            "id_ecdsa",
            "id_ed25519",
            "authorized_keys"
    )); // 生产配置和密钥类文件名黑名单。
    private List<String> blockedExtensions = new ArrayList<>(List.of(
            ".pem",
            ".key",
            ".p12",
            ".pfx",
            ".jks",
            ".keystore",
            ".crt",
            ".cer",
            ".der"
    )); // 敏感证书/密钥扩展名黑名单。
    private String backupRoot = "../selfdev-backups"; // patch 应用前备份根目录，默认放到运行目录之外。
    private Integer maxChangedFiles = 50; // 单个 patch 默认最多允许变更文件数。
    private Integer maxPatchSizeKb = 1024; // 单个 patch 默认最大 1MB。
    private Integer maxOperationTimeoutSeconds = 120; // git apply --check/apply 最大等待时间。
}
