package com.agent.selfdev.patch;

import com.agent.config.ApplyPatchProperties;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * P10 patch 应用前备份与失败回滚服务。
 *
 * <p>适用场景：PatchApplyService 在 git apply 前调用本服务备份将被修改/删除的既有文件；
 * 如果 git apply 失败，则调用 rollback 删除新增文件并恢复备份文件。</p>
 *
 * <p>调用链：PatchApplyService -> PatchBackupService.createBackup -> backup-manifest.json；
 * PatchApplyService catch 分支 -> PatchBackupService.rollback。所有目标文件仍通过 PatchSafetyGuard
 * 和 SandboxWorkspaceGuard 校验，避免回滚写出 workspace。</p>
 */
@Slf4j
@Service
public class PatchBackupService {

    private static final DateTimeFormatter BACKUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); // 备份时间戳。
    private static final String FILES_DIR = "files"; // 备份文件子目录名。
    private static final String MANIFEST_FILE = "backup-manifest.json"; // 备份清单文件名。

    private final ApplyPatchProperties properties; // patch 配置。
    private final SandboxWorkspaceGuard workspaceGuard; // P9 workspace 路径护栏。
    private final PatchSafetyGuard patchSafetyGuard; // 复用 patch 路径解析。
    private final ObjectMapper objectMapper; // JSON manifest 序列化工具。

    public PatchBackupService(ApplyPatchProperties properties,
                              SandboxWorkspaceGuard workspaceGuard,
                              PatchSafetyGuard patchSafetyGuard,
                              ObjectMapper objectMapper) {
        this.properties = properties; // 注入 patch 配置。
        this.workspaceGuard = workspaceGuard; // 注入 workspace Guard。
        this.patchSafetyGuard = patchSafetyGuard; // 注入 patch 安全 Guard。
        this.objectMapper = objectMapper; // 注入 ObjectMapper。
    }

    public PatchBackupResult createBackup(String workspaceId, Path workspace, PatchParseResult parseResult) {
        PatchBackupResult result = new PatchBackupResult(); // 创建备份结果。
        try {
            Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 备份前再次确认 workspace 合法。
            Path backupRoot = ensureBackupRoot(safeWorkspace); // 创建并校验备份根目录。
            String backupId = createBackupId(); // 生成唯一 backupId。
            Path backupDir = backupRoot.resolve(backupId).normalize(); // 创建本次备份目录。
            Path backupFilesRoot = backupDir.resolve(FILES_DIR).normalize(); // 创建文件备份根目录。
            Files.createDirectories(backupFilesRoot); // 确保文件备份目录存在。
            PatchBackupManifest manifest = buildManifest(workspaceId, backupId, parseResult); // 构造备份清单。
            for (String changedFile : parseResult.getAllChangedFiles()) {
                Path workspaceFile = patchSafetyGuard.resolveWorkspaceFile(safeWorkspace, changedFile); // 目标文件仍通过路径校验。
                if (Files.exists(workspaceFile) && Files.isDirectory(workspaceFile)) {
                    throw new IOException("patch 目标是目录，拒绝备份: " + changedFile); // patch 不应修改目录本身。
                }
                if (Files.isRegularFile(workspaceFile)) {
                    copyFileToBackup(safeWorkspace, backupFilesRoot, workspaceFile, changedFile); // 备份既有文件。
                    addIfAbsent(manifest.getBackedUpFiles(), changedFile); // 记录已备份文件。
                } else {
                    addIfAbsent(manifest.getCreatedFiles(), changedFile); // 应用前不存在，失败回滚时删除。
                }
            }
            writeManifest(backupDir, manifest); // 保存 backup-manifest.json。
            result.setSuccess(true); // 标记备份成功。
            result.setBackupId(backupId); // 回填 backupId。
            result.setBackupDir(backupDir); // 回填备份目录。
            result.setManifest(manifest); // 回填 manifest。
            return result; // 返回成功。
        } catch (Exception e) {
            log.warn("[PatchBackup] create backup failed: {}", e.getMessage(), e); // 记录备份失败。
            result.setSuccess(false); // 标记失败。
            result.setErrorMsg(e.getMessage()); // 返回失败原因。
            return result; // 返回失败。
        }
    }

    public PatchRollbackResult rollback(Path workspace, PatchBackupResult backupResult) {
        PatchRollbackResult result = new PatchRollbackResult(); // 创建回滚结果。
        result.setExecuted(true); // 调用本方法即视为已执行回滚。
        try {
            if (backupResult == null || backupResult.getManifest() == null || backupResult.getBackupDir() == null) {
                throw new IllegalArgumentException("缺少备份清单，无法回滚。"); // 没 manifest 不能回滚。
            }
            Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 回滚目标必须是合法 workspace。
            PatchBackupManifest manifest = backupResult.getManifest(); // 获取备份清单。
            for (String createdFile : manifest.getCreatedFiles()) {
                deleteCreatedFile(safeWorkspace, createdFile); // 删除 patch 新增文件。
            }
            Path backupFilesRoot = backupResult.getBackupDir().resolve(FILES_DIR).normalize(); // 备份文件根目录。
            for (String backedUpFile : manifest.getBackedUpFiles()) {
                restoreBackedUpFile(safeWorkspace, backupFilesRoot, backedUpFile); // 恢复已备份文件。
            }
            result.setSuccess(true); // 标记回滚成功。
            return result; // 返回成功。
        } catch (Exception e) {
            log.warn("[PatchBackup] rollback failed: {}", e.getMessage(), e); // 记录回滚失败。
            result.setSuccess(false); // 标记失败。
            result.setErrorMsg(e.getMessage()); // 保存失败原因。
            return result; // 返回失败。
        }
    }

    private PatchBackupManifest buildManifest(String workspaceId, String backupId, PatchParseResult parseResult) {
        PatchBackupManifest manifest = new PatchBackupManifest(); // 创建 manifest。
        manifest.setBackupId(backupId); // 写 backupId。
        manifest.setWorkspaceId(workspaceId); // 写 workspaceId。
        manifest.setCreatedAt(LocalDateTime.now().toString()); // 写创建时间，manifest 落盘使用字符串避免 Jackson JavaTime 依赖。
        manifest.getChangedFiles().addAll(parseResult.getAllChangedFiles()); // 写全量变更文件。
        manifest.getAddedFiles().addAll(parseResult.getAddedFiles()); // 写新增文件。
        manifest.getModifiedFiles().addAll(parseResult.getModifiedFiles()); // 写修改文件。
        manifest.getDeletedFiles().addAll(parseResult.getDeletedFiles()); // 写删除文件。
        manifest.getRenamedFiles().addAll(parseResult.getRenamedFiles()); // 写重命名文件。
        return manifest; // 返回 manifest。
    }

    private void copyFileToBackup(Path workspace,
                                  Path backupFilesRoot,
                                  Path workspaceFile,
                                  String relativeFile) throws IOException {
        Path backupFile = backupFilesRoot.resolve(relativeFile).normalize(); // 保留 workspace 相对路径结构。
        if (!backupFile.startsWith(backupFilesRoot)) {
            throw new IOException("备份路径逃逸 backupRoot。"); // 防止 manifest 路径污染。
        }
        Files.createDirectories(backupFile.getParent()); // 创建备份父目录。
        Files.copy(workspaceFile, backupFile, StandardCopyOption.REPLACE_EXISTING); // 复制既有文件。
    }

    private void deleteCreatedFile(Path workspace, String relativeFile) throws IOException {
        Path target = patchSafetyGuard.resolveWorkspaceFile(workspace, relativeFile); // 解析新增文件路径。
        if (Files.exists(target) && Files.isRegularFile(target)) {
            Files.delete(target); // 删除新增普通文件。
            deleteEmptyParents(workspace, target.getParent()); // 清理 patch 创建的空目录。
        }
    }

    private void restoreBackedUpFile(Path workspace, Path backupFilesRoot, String relativeFile) throws IOException {
        Path source = backupFilesRoot.resolve(relativeFile).normalize(); // 备份源文件。
        if (!source.startsWith(backupFilesRoot) || !Files.isRegularFile(source)) {
            throw new IOException("备份文件缺失: " + relativeFile); // 缺少备份时失败。
        }
        Path target = patchSafetyGuard.resolveWorkspaceFile(workspace, relativeFile); // 目标文件路径。
        Files.createDirectories(target.getParent()); // 确保目标父目录存在。
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING); // 用备份覆盖恢复。
    }

    private void deleteEmptyParents(Path workspace, Path start) throws IOException {
        Path current = start; // 从目标文件父目录开始。
        while (current != null && current.startsWith(workspace) && !current.equals(workspace)) {
            if (!isDirectoryEmpty(current)) {
                return; // 非空目录停止。
            }
            Files.deleteIfExists(current); // 删除空目录。
            current = current.getParent(); // 继续向上。
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false; // 非目录不算空目录。
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return !stream.iterator().hasNext(); // 没有子节点即为空。
        }
    }

    private void writeManifest(Path backupDir, PatchBackupManifest manifest) throws IOException {
        Files.createDirectories(backupDir); // 确保备份目录存在。
        Path manifestFile = backupDir.resolve(MANIFEST_FILE).normalize(); // manifest 文件路径。
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest); // 写 JSON 清单。
    }

    private Path ensureBackupRoot(Path workspace) throws IOException {
        String configured = properties.getBackupRoot(); // 读取备份根配置。
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("techbrain.selfdev.patch.backup-root 未配置。"); // 配置缺失。
        }
        Path backupRoot = Path.of(configured.trim()).toAbsolutePath().normalize(); // 解析备份根。
        validateBackupRoot(backupRoot, workspace); // 校验备份根不碰源码/运行/workspace。
        Files.createDirectories(backupRoot); // 创建备份根目录。
        return backupRoot.toRealPath(); // 返回真实路径。
    }

    private void validateBackupRoot(Path backupRoot, Path workspace) {
        Path sourceRepo = workspaceGuard.sourceRepoDir(); // P9 配置的源码目录。
        Path runtime = workspaceGuard.runtimeDir(); // 当前运行目录。
        Path sandboxRoot = workspaceGuard.sandboxRoot(); // 沙箱根目录。
        if (sameOrNested(backupRoot, sourceRepo) || sameOrNested(sourceRepo, backupRoot)) {
            throw new IllegalStateException("backupRoot 不能与 sourceRepoDir 相同或互相包含。"); // 保护源码目录。
        }
        if (sameOrNested(backupRoot, runtime) || sameOrNested(runtime, backupRoot)) {
            throw new IllegalStateException("backupRoot 不能与当前运行目录相同或互相包含。"); // 保护运行目录。
        }
        if (sameOrNested(backupRoot, workspace) || sameOrNested(workspace, backupRoot)
                || sameOrNested(backupRoot, sandboxRoot) || sameOrNested(sandboxRoot, backupRoot)) {
            throw new IllegalStateException("backupRoot 不能位于 sandbox workspace 或 sandboxRoot 内。"); // 避免备份被 patch 影响。
        }
    }

    private boolean sameOrNested(Path left, Path right) {
        Path normalizedLeft = realOrAbsolute(left); // 规范化左路径。
        Path normalizedRight = realOrAbsolute(right); // 规范化右路径。
        return normalizedLeft.equals(normalizedRight) || normalizedLeft.startsWith(normalizedRight); // 判断相同或嵌套。
    }

    private Path realOrAbsolute(Path path) {
        try {
            return path.toRealPath(); // 存在时使用真实路径。
        } catch (IOException e) {
            return path.toAbsolutePath().normalize(); // 不存在时使用规范绝对路径。
        }
    }

    private String createBackupId() {
        return "backup-" + LocalDateTime.now().format(BACKUP_TIME_FORMATTER)
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8); // 生成可读且唯一的 backupId。
    }

    private void addIfAbsent(java.util.List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value); // 去重添加。
        }
    }
}
