package com.agent.selfdev.patch;

import lombok.Data;

/**
 * patch 失败回滚结果。
 *
 * <p>适用场景：PatchApplyService 在 git apply 失败后调用 PatchBackupService.rollback，
 * 用本对象记录回滚是否执行、是否成功以及失败原因摘要。</p>
 *
 * <p>调用链：PatchApplyService.applyPatch catch 分支 -> PatchBackupService.rollback -> PatchRollbackResult。</p>
 */
@Data
public class PatchRollbackResult {

    private boolean executed; // 是否执行过回滚。
    private boolean success; // 回滚是否成功。
    private String errorMsg; // 回滚失败原因。
}
