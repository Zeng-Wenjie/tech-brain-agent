package com.agent.selfdev.patch;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * patch 安全拒绝文件信息。
 *
 * <p>适用场景：PatchSafetyGuard 发现某个文件不在白名单、命中保护目录、生产配置或敏感扩展名时，
 * 用本对象返回给工具调用方和日志摘要。</p>
 *
 * <p>调用链：PatchSafetyGuard.validateChanges -> PatchRejectedFile -> ApplyPatchResult.rejectedFiles。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatchRejectedFile {

    private String file; // 被拒绝的 workspace 相对路径。
    private String reason; // 拒绝原因。
}
