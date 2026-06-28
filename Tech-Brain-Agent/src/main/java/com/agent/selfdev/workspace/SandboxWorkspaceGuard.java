package com.agent.selfdev.workspace;

import com.agent.config.SandboxWorkspaceProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * P9 沙箱 workspace 路径护栏。
 *
 * <p>适用场景：所有会创建、删除、恢复、执行 Git 命令或后续交给 Claude Code 的路径，
 * 都必须先经过本类校验。本类只做路径安全判断，不执行代码修改、不调用 Claude Code、不生成 patch。</p>
 *
 * <p>调用链：SandboxWorkspaceService / SafeCommandExecutor -> SandboxWorkspaceGuard
 * -> 确认 workspace 位于 sandboxRoot 内，且不等于、不包含、不落入 sourceRepoDir 或当前运行目录。</p>
 */
@Component
public class SandboxWorkspaceGuard {

    private final SandboxWorkspaceProperties properties; // P9 workspace 配置来源。

    public SandboxWorkspaceGuard(SandboxWorkspaceProperties properties) {
        this.properties = properties; // 注入配置对象。
    }

    public Path ensureSandboxRoot() {
        Path sandboxRoot = configuredPath(properties.getSandboxRoot(), "techbrain.selfdev.workspace.sandbox-root"); // 解析沙箱根目录。
        validateSandboxRootConfiguration(sandboxRoot); // 创建前先校验根目录不会落到源码或运行目录内。
        try {
            Files.createDirectories(sandboxRoot); // 只允许创建 sandboxRoot，不触碰 sourceRepoDir。
            return realOrAbsolute(sandboxRoot); // 返回规范化后的安全根目录。
        } catch (IOException e) {
            throw new IllegalStateException("创建 sandboxRoot 失败: " + sandboxRoot, e); // 明确抛出根目录创建失败。
        }
    }

    public Path sandboxRoot() {
        Path sandboxRoot = configuredPath(properties.getSandboxRoot(), "techbrain.selfdev.workspace.sandbox-root"); // 解析沙箱根目录。
        validateSandboxRootConfiguration(sandboxRoot); // 每次读取也校验配置关系。
        return realOrAbsolute(sandboxRoot); // 返回真实路径或规范化绝对路径。
    }

    public Path sourceRepoDir() {
        return configuredPath(properties.getSourceRepoDir(), "techbrain.selfdev.workspace.source-repo-dir"); // 解析只读源码目录。
    }

    public Path runtimeDir() {
        return realOrAbsolute(Paths.get(System.getProperty("user.dir"))); // 当前 Java 进程运行目录，视为线上运行目录。
    }

    public Path toSafeWorkspacePath(String workspaceName) {
        String safeName = sanitizeWorkspaceName(workspaceName); // workspaceName 必须是单层目录名。
        Path workspace = ensureSandboxRoot().resolve(safeName).normalize(); // 只允许从 sandboxRoot 拼出子目录。
        validateWorkspacePath(workspace); // 拼接后再次校验，防止路径穿越。
        return workspace; // 返回可用于写操作的 workspace 路径。
    }

    public Path validateWorkspacePath(Path workspacePath) {
        if (workspacePath == null) {
            throw new IllegalArgumentException("workspacePath 不能为空。"); // 空路径直接拒绝。
        }
        Path sandboxRoot = ensureSandboxRoot(); // 取得已校验并可存在的沙箱根目录。
        Path workspace = realOrAbsolute(workspacePath); // 规范化候选 workspace。
        if (workspace.equals(sandboxRoot)) {
            throw new IllegalArgumentException("workspacePath 不能指向 sandboxRoot 本身。"); // 禁止把根目录当工作区。
        }
        if (!workspace.startsWith(sandboxRoot)) {
            throw new IllegalArgumentException("workspacePath 必须位于 sandboxRoot 内。"); // 绝对路径绕过会在这里被拒绝。
        }
        rejectProtectedOverlap(workspace); // 禁止 workspace 与源码目录/运行目录重叠。
        return workspace; // 返回校验后的 workspace。
    }

    public Path validateDeletionTarget(Path targetPath) {
        Path target = validateWorkspacePath(targetPath); // 删除目标必须先是合法 workspace 路径。
        Path sandboxRoot = ensureSandboxRoot(); // 取沙箱根目录用于根目录保护。
        if (target.equals(sandboxRoot)) {
            throw new IllegalArgumentException("禁止删除 sandboxRoot 本身。"); // 双重保护 sandboxRoot。
        }
        rejectProtectedOverlap(target); // 禁止删除 sourceRepoDir 或当前运行目录。
        return target; // 返回允许删除的目标。
    }

    public Path validateSourceRepoForRead(String sourceRepoDir) {
        Path configuredSource = sourceRepoDir(); // 配置中的 sourceRepoDir 是唯一允许的本地复制来源。
        Path requestedSource = configuredPath(firstNonBlank(sourceRepoDir, properties.getSourceRepoDir()),
                "sourceRepoDir"); // 解析请求来源。
        Path source = realOrAbsolute(requestedSource); // 规范化请求来源。
        Path configured = realOrAbsolute(configuredSource); // 规范化配置来源。
        if (!sameOrEqual(source, configured)) {
            throw new IllegalArgumentException("LOCAL_COPY 只允许从配置的 sourceRepoDir 读取。"); // 拒绝任意目录读取。
        }
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("sourceRepoDir 不存在或不是目录。"); // 源目录必须存在。
        }
        Path sandboxRoot = ensureSandboxRoot(); // 沙箱根目录不能和源码互相包含。
        if (sameOrNested(source, sandboxRoot) || sameOrNested(sandboxRoot, source)) {
            throw new IllegalStateException("sourceRepoDir 与 sandboxRoot 不能互相包含。"); // 防止自复制和误写源码。
        }
        return source; // 返回只读复制来源。
    }

    public void validateSandboxRootForCommand(Path workingDir) {
        Path sandboxRoot = ensureSandboxRoot(); // clone 等命令只能在 sandboxRoot 下启动。
        Path actual = realOrAbsolute(workingDir); // 规范化命令工作目录。
        if (!sameOrEqual(actual, sandboxRoot)) {
            throw new IllegalArgumentException("命令工作目录必须是 sandboxRoot。"); // 防止 clone 在任意目录执行。
        }
    }

    public String relativeToSandbox(Path path) {
        Path sandboxRoot = ensureSandboxRoot(); // 获取安全沙箱根。
        Path actual = realOrAbsolute(path); // 规范化路径。
        if (!actual.startsWith(sandboxRoot)) {
            return null; // 不在沙箱内的路径不返回，避免泄露绝对路径。
        }
        return sandboxRoot.relativize(actual).toString().replace('\\', '/'); // 只返回相对路径供日志使用。
    }

    public String sanitizeWorkspaceName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceName 不能为空。"); // workspace 必须有目录名。
        }
        String name = rawName.trim(); // 去掉首尾空白。
        if (name.contains("/") || name.contains("\\") || name.contains(":") || name.contains("..")
                || name.startsWith(".") || Paths.get(name).isAbsolute()) {
            throw new IllegalArgumentException("workspaceName 必须是 sandboxRoot 下的单层安全目录名。"); // 禁止路径穿越和盘符。
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "-"); // 只保留安全目录字符。
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("workspaceName 清洗后为空。"); // 防止非法字符组成空目录。
        }
        return cleaned; // 返回安全目录名。
    }

    public String sanitizeBranchName(String rawName) {
        String branch = firstNonBlank(rawName, "selfdev/workspace"); // 分支名为空时兜底。
        branch = branch.trim().replace('\\', '/'); // Git 分支统一使用 / 分层。
        branch = branch.replaceAll("[^A-Za-z0-9._/-]", "-"); // 清理空格、冒号等 Git 非法字符。
        while (branch.contains("..")) {
            branch = branch.replace("..", "."); // Git 分支不允许连续点。
        }
        while (branch.contains("//")) {
            branch = branch.replace("//", "/"); // 避免空路径段。
        }
        branch = branch.replace("@{", "-"); // Git 分支不允许 @{。
        branch = trimBranchEdge(branch); // 清理开头/结尾危险字符。
        if (branch.isBlank()) {
            throw new IllegalArgumentException("branchName 清洗后为空。"); // 分支名必须有效。
        }
        return branch; // 返回可用于 git switch -c 的分支名。
    }

    private void validateSandboxRootConfiguration(Path sandboxRoot) {
        Path root = realOrAbsolute(sandboxRoot); // 规范化沙箱根。
        Path source = realOrAbsolute(sourceRepoDir()); // 规范化源码目录。
        Path runtime = runtimeDir(); // 规范化当前运行目录。
        if (Boolean.FALSE.equals(properties.getProtectSourceRepo())) {
            throw new IllegalStateException("protect-source-repo 必须开启，P9 不允许关闭源码目录保护。"); // 第一版不提供不安全开关。
        }
        if (sameOrNested(root, source) || sameOrNested(source, root)) {
            throw new IllegalStateException("sandboxRoot 与 sourceRepoDir 不能相同或互相包含。"); // 禁止写入源码树。
        }
        if (sameOrNested(root, runtime) || sameOrNested(runtime, root)) {
            throw new IllegalStateException("sandboxRoot 与当前运行目录不能相同或互相包含。"); // 禁止写入线上运行目录。
        }
    }

    private void rejectProtectedOverlap(Path workspace) {
        Path source = realOrAbsolute(sourceRepoDir()); // 获取源码目录。
        Path runtime = runtimeDir(); // 获取当前运行目录。
        if (sameOrNested(workspace, source) || sameOrNested(source, workspace)) {
            throw new IllegalArgumentException("workspacePath 不能指向或包含 sourceRepoDir。"); // 保护源码目录。
        }
        if (sameOrNested(workspace, runtime) || sameOrNested(runtime, workspace)) {
            throw new IllegalArgumentException("workspacePath 不能指向或包含当前运行目录。"); // 保护线上运行目录。
        }
    }

    private Path configuredPath(String raw, String fieldName) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalStateException(fieldName + " 未配置。"); // 配置缺失直接失败。
        }
        return Paths.get(raw.trim()).toAbsolutePath().normalize(); // 统一成绝对规范路径。
    }

    private Path realOrAbsolute(Path path) {
        try {
            return path.toRealPath(); // 优先使用真实路径，消除软链和大小写差异。
        } catch (IOException e) {
            return path.toAbsolutePath().normalize(); // 不存在时仍使用规范绝对路径。
        }
    }

    private boolean sameOrNested(Path candidate, Path protectedPath) {
        Path left = realOrAbsolute(candidate); // 规范化候选路径。
        Path right = realOrAbsolute(protectedPath); // 规范化受保护路径。
        return sameOrEqual(left, right) || left.startsWith(right); // candidate 是否等于或位于 protectedPath 内。
    }

    private boolean sameOrEqual(Path left, Path right) {
        try {
            return Files.isSameFile(left, right); // 路径存在时按文件系统真实身份比较。
        } catch (IOException e) {
            return left.toString().toLowerCase(Locale.ROOT)
                    .equals(right.toString().toLowerCase(Locale.ROOT)); // 不存在时用大小写不敏感字符串兜底。
        }
    }

    private String trimBranchEdge(String branch) {
        String value = branch; // 保存可变分支名。
        while (value.startsWith("/") || value.startsWith(".") || value.startsWith("-")) {
            value = value.substring(1); // 去掉开头危险字符。
        }
        while (value.endsWith("/") || value.endsWith(".") || value.endsWith("-")) {
            value = value.substring(0, value.length() - 1); // 去掉结尾危险字符。
        }
        return value; // 返回清理后的分支名。
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.trim().isEmpty() ? fallback : first.trim(); // 简单兜底工具。
    }
}
