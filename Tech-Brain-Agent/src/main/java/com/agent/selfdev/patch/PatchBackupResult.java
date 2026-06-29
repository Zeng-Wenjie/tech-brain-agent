package com.agent.selfdev.patch;

import lombok.Data;

import java.nio.file.Path;

/**
 * patch 备份创建结果。
 *
 * <p>适用场景：PatchBackupService.createBackup 返回备份 ID、备份目录和 manifest，
 * 供 PatchApplyService 在失败时执行 rollback。</p>
 *
 * <p>调用链：PatchApplyService -> PatchBackupService.createBackup -> PatchBackupResult。</p>
 */
@Data
public class PatchBackupResult {

    private boolean success; // 备份是否成功。
    private String backupId; // 备份 ID。
    private Path backupDir; // 备份目录，后端内部使用，不写入日志绝对路径。
    private PatchBackupManifest manifest; // 备份清单。
    private String errorMsg; // 失败原因。
}
