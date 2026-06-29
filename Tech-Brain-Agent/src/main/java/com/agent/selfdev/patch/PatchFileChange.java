package com.agent.selfdev.patch;

import lombok.Data;

/**
 * patch 中单个文件变更描述。
 *
 * <p>适用场景：PatchParser 解析 unified diff 后输出文件级变更，供 PatchSafetyGuard 校验路径，
 * PatchBackupService 判断哪些文件需要备份或失败回滚。</p>
 *
 * <p>调用链：PatchParser.parse -> PatchFileChange -> PatchSafetyGuard.validateChanges
 * -> PatchBackupService.createBackup。</p>
 */
@Data
public class PatchFileChange {

    private String oldPath; // 修改前路径，新增文件为空。
    private String newPath; // 修改后路径，删除文件为空。
    private String changeType; // ADDED、MODIFIED、DELETED、RENAMED。
}
