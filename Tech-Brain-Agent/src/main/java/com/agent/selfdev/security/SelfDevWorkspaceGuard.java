package com.agent.selfdev.security;

import com.agent.config.SelfDevProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
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

    /**
     * 列出沙箱根目录下的所有项目（一级子目录）。每个子目录都是一个可被 Claude Code 选中操作的独立项目。
     */
    public List<String> listProjects() {
        Path sandbox = ensureSandboxWorkspace();
        List<String> projects = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sandbox)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    String name = child.getFileName().toString();
                    if (!name.startsWith(".")) {
                        projects.add(name);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list sandbox projects: " + e.getMessage(), e);
        }
        projects.sort(String.CASE_INSENSITIVE_ORDER);
        return projects;
    }

    /**
     * 校验并清洗项目名：必须是单层目录名，不允许路径分隔符、盘符、.. 或以 . 开头。
     */
    public String sanitizeProjectName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Project name is required.");
        }
        String name = rawName.trim().replace('\\', '/');
        if (name.contains("/") || name.contains(":") || name.contains("..") || name.startsWith(".")) {
            throw new IllegalArgumentException("Invalid project name: " + rawName);
        }
        return name;
    }

    /**
     * 把项目名解析成沙箱下的项目工作目录（sandbox/&lt;project&gt;），并校验它是沙箱的直接子目录且已存在。
     */
    public Path resolveProjectWorkspace(String projectName) {
        Path sandbox = resolveSandboxWorkspace();
        Path project = sandbox.resolve(sanitizeProjectName(projectName)).normalize();
        if (project.getParent() == null || !project.getParent().equals(sandbox)) {
            throw new IllegalArgumentException("Project must be a direct child of the sandbox workspace.");
        }
        if (!Files.isDirectory(project)) {
            throw new IllegalStateException("Project does not exist in the sandbox: " + projectName);
        }
        return project;
    }

    /**
     * 校验给定路径是沙箱下的某个项目目录（沙箱的直接子目录），用于约束 Claude Code 的工作目录。
     */
    public void assertProjectWorkspace(Path workspace) {
        Path sandbox = resolveSandboxWorkspace();
        Path actual = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (actual == null || actual.getParent() == null
                || !actual.getParent().equals(sandbox) || !Files.isDirectory(actual)) {
            throw new IllegalArgumentException("Claude Code working directory must be a project under the sandbox workspace.");
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
