package com.agent.selfdev.security;

import com.agent.config.SelfDevProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自迭代沙箱 workspace 守卫。
 */
@Component
public class SelfDevWorkspaceGuard {

    private final SelfDevProperties properties;

    public SelfDevWorkspaceGuard(SelfDevProperties properties) {
        this.properties = properties;
    }

    public Path resolveSandboxWorkspace() {
        String configured = properties.getSandboxWorkspaceDir();
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("未配置 techbrain.self-dev.sandbox-workspace-dir。");
        }
        Path sandbox = Paths.get(configured.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(sandbox)) {
            throw new IllegalStateException("沙箱 workspace 不存在或不是目录：" + sandbox);
        }
        rejectRuntimeDirectory(sandbox);
        return sandbox;
    }

    public void assertSandboxWorkspace(Path workspace) {
        Path sandbox = resolveSandboxWorkspace();
        Path actual = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (actual == null || !samePath(actual, sandbox)) {
            throw new IllegalArgumentException("Claude Code 工作目录必须是配置的沙箱 workspace。");
        }
    }

    public List<String> sanitizeAllowedPaths(List<String> allowedPaths) {
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sanitized = new ArrayList<>();
        for (String raw : allowedPaths) {
            String path = sanitizeRelativePath(raw);
            if (path != null && !sanitized.contains(path)) {
                sanitized.add(path);
            }
        }
        return sanitized;
    }

    public String sanitizeRelativePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String path = raw.trim().replace('\\', '/');
        if (".".equals(path)) {
            return ".";
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/") || path.contains(":") || path.contains("..")) {
            throw new IllegalArgumentException("允许范围只能使用 workspace 相对路径：" + raw);
        }
        if (path.equals(".git") || path.startsWith(".git/")) {
            throw new IllegalArgumentException("禁止把 .git 加入允许修改范围。");
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void rejectRuntimeDirectory(Path sandbox) {
        Path runtime = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (samePath(sandbox, runtime) || sandbox.startsWith(runtime) || runtime.startsWith(sandbox)) {
            throw new IllegalStateException("Claude Code 沙箱不能指向线上运行目录或其父子目录。");
        }
    }

    private boolean samePath(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (Exception e) {
            return left.equals(right);
        }
    }
}
