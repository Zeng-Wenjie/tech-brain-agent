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
 * Guard for the sandbox workspace used by Claude Code self-development flows.
 */
@Component
public class SelfDevWorkspaceGuard {

    private final SelfDevProperties properties;

    public SelfDevWorkspaceGuard(SelfDevProperties properties) {
        this.properties = properties;
    }

    public Path resolveSandboxWorkspace() {
        Path sandbox = configuredSandboxPath();
        if (!Files.isDirectory(sandbox)) {
            throw new IllegalStateException("Sandbox workspace does not exist or is not a directory: " + sandbox);
        }
        rejectRuntimeDirectory(sandbox);
        return sandbox;
    }

    public Path ensureSandboxWorkspace() {
        Path sandbox = configuredSandboxPath();
        rejectRuntimeDirectory(sandbox);
        try {
            Files.createDirectories(sandbox);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create sandbox workspace: " + sandbox, e);
        }
        if (!Files.isDirectory(sandbox)) {
            throw new IllegalStateException("Sandbox workspace is not a directory: " + sandbox);
        }
        return sandbox;
    }

    public void assertSandboxWorkspace(Path workspace) {
        Path sandbox = resolveSandboxWorkspace();
        Path actual = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (actual == null || !samePath(actual, sandbox)) {
            throw new IllegalArgumentException("Claude Code working directory must be the configured sandbox workspace.");
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
            throw new IllegalArgumentException("Allowed paths must be workspace-relative paths: " + raw);
        }
        if (path.equals(".git") || path.startsWith(".git/")) {
            throw new IllegalArgumentException(".git cannot be added to the allowed change scope.");
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private Path configuredSandboxPath() {
        String configured = properties.getSandboxWorkspaceDir();
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("Missing techbrain.self-dev.sandbox-workspace-dir.");
        }
        return Paths.get(configured.trim()).toAbsolutePath().normalize();
    }

    private void rejectRuntimeDirectory(Path sandbox) {
        Path runtime = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (samePath(sandbox, runtime) || sandbox.startsWith(runtime) || runtime.startsWith(sandbox)) {
            throw new IllegalStateException("Claude Code sandbox cannot point to the runtime directory or its parent/child.");
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
