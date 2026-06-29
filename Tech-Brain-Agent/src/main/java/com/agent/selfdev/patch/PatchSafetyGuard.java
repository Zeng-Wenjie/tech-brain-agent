package com.agent.selfdev.patch;

import com.agent.config.ApplyPatchProperties;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * P10 patch 文件级安全守卫。
 *
 * <p>适用场景：PatchApplyService 在备份和 git apply 前，必须用本类校验 patch 涉及的每一个文件。
 * 本类只做路径和白/黑名单判断，不写文件、不执行命令。</p>
 *
 * <p>调用链：PatchApplyService -> PatchParser 得到 changedFiles -> PatchSafetyGuard.validateChanges
 * -> 返回 rejectedFiles；只有没有拒绝项时才允许进入 PatchBackupService 和 PatchCommandExecutor。</p>
 */
@Component
public class PatchSafetyGuard {

    private final ApplyPatchProperties properties; // patch 安全配置。
    private final SandboxWorkspaceGuard workspaceGuard; // 复用 P9 workspace 路径护栏。

    public PatchSafetyGuard(ApplyPatchProperties properties, SandboxWorkspaceGuard workspaceGuard) {
        this.properties = properties; // 注入配置。
        this.workspaceGuard = workspaceGuard; // 注入 P9 Guard。
    }

    public List<PatchRejectedFile> validateChanges(Path workspace,
                                                   PatchParseResult parseResult,
                                                   List<String> requestAllowedDirectories) {
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 所有目标必须先是合法 sandbox workspace。
        List<String> allowedDirectories = normalizeDirectoryList(resolveAllowedDirectories(requestAllowedDirectories)); // 解析白名单。
        List<String> protectedDirectories = normalizeDirectoryList(properties.getProtectedDirectories()); // 解析保护目录。
        List<String> blockedFileNames = lowerList(properties.getBlockedFileNames()); // 解析文件名黑名单。
        List<String> blockedExtensions = lowerList(properties.getBlockedExtensions()); // 解析扩展名黑名单。
        List<PatchRejectedFile> rejectedFiles = new ArrayList<>(); // 收集拒绝项。
        if (parseResult == null || parseResult.getAllChangedFiles() == null || parseResult.getAllChangedFiles().isEmpty()) {
            rejectedFiles.add(new PatchRejectedFile("", "patch 未解析出变更文件")); // 没文件直接拒绝。
            return rejectedFiles; // 返回拒绝。
        }
        for (String file : parseResult.getAllChangedFiles()) {
            validateOneFile(safeWorkspace, file, allowedDirectories, protectedDirectories,
                    blockedFileNames, blockedExtensions, rejectedFiles); // 校验单个文件。
        }
        return rejectedFiles; // 返回全部拒绝项。
    }

    public Path resolveSafePatchFile(Path workspace, String patchFilePath) {
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // patch 文件也必须围绕合法 workspace 解析。
        if (patchFilePath == null || patchFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("patchFilePath 不能为空。"); // 空路径拒绝。
        }
        Path raw = Path.of(patchFilePath.trim()); // 解析传入路径。
        Path patchFile = raw.isAbsolute()
                ? raw.toAbsolutePath().normalize()
                : safeWorkspace.resolve(normalizeRelativePath(patchFilePath)).normalize(); // 相对路径按 workspace 解析。
        if (!patchFile.startsWith(safeWorkspace)) {
            throw new IllegalArgumentException("patchFilePath 必须位于 sandbox workspace 内。"); // 拒绝 workspace 外 patch。
        }
        if (!Files.isRegularFile(patchFile)) {
            throw new IllegalArgumentException("patchFilePath 不存在或不是普通文件。"); // 必须是文件。
        }
        return patchFile; // 返回安全 patch 文件路径。
    }

    public Path resolveWorkspaceFile(Path workspace, String relativePath) {
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 目标 workspace 必须合法。
        String normalized = normalizeRelativePath(relativePath); // 清洗相对路径。
        Path file = safeWorkspace.resolve(normalized).normalize(); // 拼出目标文件。
        if (!file.startsWith(safeWorkspace)) {
            throw new IllegalArgumentException("文件路径逃逸 sandbox workspace。"); // 双重防穿越。
        }
        return file; // 返回目标文件路径。
    }

    private void validateOneFile(Path workspace,
                                 String file,
                                 List<String> allowedDirectories,
                                 List<String> protectedDirectories,
                                 List<String> blockedFileNames,
                                 List<String> blockedExtensions,
                                 List<PatchRejectedFile> rejectedFiles) {
        String normalized;
        try {
            normalized = normalizeRelativePath(file); // 清洗 patch 文件路径。
        } catch (Exception e) {
            rejectedFiles.add(new PatchRejectedFile(safeDisplay(file), e.getMessage())); // 路径非法。
            return; // 当前文件结束。
        }
        Path target = workspace.resolve(normalized).normalize(); // 拼出 workspace 内目标路径。
        if (!target.startsWith(workspace)) {
            rejectedFiles.add(new PatchRejectedFile(normalized, "文件路径逃逸 sandbox workspace")); // 防止写到 workspace 外。
            return; // 当前文件结束。
        }
        if (!isInAnyDirectory(normalized, allowedDirectories)) {
            rejectedFiles.add(new PatchRejectedFile(normalized, "不在允许修改目录白名单内")); // 白名单不通过。
        }
        if (isInAnyDirectory(normalized, protectedDirectories)) {
            rejectedFiles.add(new PatchRejectedFile(normalized, "命中核心框架保护目录")); // 核心保护目录拒绝。
        }
        String fileName = fileNameOf(normalized).toLowerCase(Locale.ROOT); // 获取小写文件名。
        if (blockedFileNames.contains(fileName)) {
            rejectedFiles.add(new PatchRejectedFile(normalized, "命中生产配置或敏感文件名黑名单")); // 文件名黑名单。
        }
        String lower = normalized.toLowerCase(Locale.ROOT); // 小写完整路径。
        for (String extension : blockedExtensions) {
            if (lower.endsWith(extension)) {
                rejectedFiles.add(new PatchRejectedFile(normalized, "命中敏感文件扩展名黑名单")); // 扩展名黑名单。
                break; // 一个原因足够。
            }
        }
    }

    private List<String> resolveAllowedDirectories(List<String> requestAllowedDirectories) {
        if (requestAllowedDirectories != null && !requestAllowedDirectories.isEmpty()) {
            return requestAllowedDirectories; // 请求级白名单优先。
        }
        return properties.getDefaultAllowedDirectories(); // 否则使用系统默认白名单。
    }

    private List<String> normalizeDirectoryList(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            return Collections.emptyList(); // 空目录列表。
        }
        List<String> normalized = new ArrayList<>(); // 规范化后的目录列表。
        for (String directory : directories) {
            if (directory == null || directory.trim().isEmpty()) {
                continue; // 忽略空目录。
            }
            String value = normalizeRelativePath(directory); // 目录也按相对路径规则清洗。
            if (!normalized.contains(value)) {
                normalized.add(value); // 去重添加。
            }
        }
        return normalized; // 返回白/黑名单目录。
    }

    private List<String> lowerList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList(); // 空列表。
        }
        List<String> lowered = new ArrayList<>(); // 小写列表。
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                lowered.add(value.trim().toLowerCase(Locale.ROOT)); // 小写保存。
            }
        }
        return lowered; // 返回小写列表。
    }

    private String normalizeRelativePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("文件路径为空"); // 空路径拒绝。
        }
        String path = raw.trim().replace('\\', '/'); // 统一分隔符。
        while (path.startsWith("./")) {
            path = path.substring(2); // 去掉 ./。
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("不允许绝对路径"); // 拒绝绝对路径。
        }
        Path normalized = Path.of(path).normalize(); // 标准化。
        String normalizedText = normalized.toString().replace('\\', '/'); // 转回统一分隔符。
        if (normalized.isAbsolute() || normalizedText.equals("..") || normalizedText.startsWith("../")
                || normalizedText.contains("/../")) {
            throw new IllegalArgumentException("不允许路径穿越"); // 拒绝路径穿越。
        }
        if (normalizedText.isBlank() || ".".equals(normalizedText)) {
            throw new IllegalArgumentException("文件路径无效"); // 拒绝无效路径。
        }
        return normalizedText; // 返回清洗后的相对路径。
    }

    private boolean isInAnyDirectory(String file, List<String> directories) {
        for (String directory : directories) {
            if (file.equals(directory) || file.startsWith(directory + "/")) {
                return true; // 文件位于指定目录内。
            }
        }
        return false; // 未命中任何目录。
    }

    private String fileNameOf(String path) {
        int slash = path.lastIndexOf('/'); // 找最后一个分隔符。
        return slash >= 0 ? path.substring(slash + 1) : path; // 返回文件名。
    }

    private String safeDisplay(String file) {
        if (file == null) {
            return ""; // 空值显示为空。
        }
        String normalized = file.replace('\\', '/'); // 统一分隔符。
        if (normalized.matches("^[A-Za-z]:/.*") || normalized.startsWith("/")) {
            return "[absolute-path-redacted]"; // 绝对路径不进入日志。
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500); // 限制长度。
    }
}
