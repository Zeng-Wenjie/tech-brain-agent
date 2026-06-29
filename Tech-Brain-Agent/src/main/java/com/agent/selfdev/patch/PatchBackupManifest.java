package com.agent.selfdev.patch;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * patch 应用前备份清单。
 *
 * <p>适用场景：PatchBackupService 在 backupRoot/backupId 下保存 backup-manifest.json，
 * 记录本次 patch 涉及文件、实际备份文件和新增文件，便于失败后自动回滚。</p>
 *
 * <p>调用链：PatchBackupService.createBackup -> backup-manifest.json；
 * PatchBackupService.rollback -> 读取 manifest -> 恢复 modified/deleted、删除 created。</p>
 */
@Data
public class PatchBackupManifest {

    private String backupId; // 备份 ID。
    private String workspaceId; // 对应 workspace ID。
    private String createdAt; // 备份创建时间，使用 ISO 字符串避免依赖 Jackson JavaTime 模块。
    private List<String> changedFiles = new ArrayList<>(); // 本次 patch 全量涉及文件。
    private List<String> createdFiles = new ArrayList<>(); // 应用前不存在、失败回滚时需要删除的文件。
    private List<String> backedUpFiles = new ArrayList<>(); // 应用前已存在并已备份的文件。
    private List<String> modifiedFiles = new ArrayList<>(); // 修改文件。
    private List<String> deletedFiles = new ArrayList<>(); // 删除文件。
    private List<String> addedFiles = new ArrayList<>(); // 新增文件。
    private List<String> renamedFiles = new ArrayList<>(); // 重命名文件。
}
