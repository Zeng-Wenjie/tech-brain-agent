package com.agent.selfdev.security;

import com.agent.config.SandboxWorkspaceProperties;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.entity.dto.SandboxWorkspaceOperationResult;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import com.agent.selfdev.workspace.SandboxWorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Compatibility facade between the old self-dev "project" concept and the P9 sandbox workspace model.
 *
 * <p>Call chain: SelfDevController / SelfDevProjectImportService / SelfDevTerminalWebSocketHandler /
 * SelfDevOrchestrator -> SelfDevWorkspaceGuard -> SandboxWorkspaceGuard / SandboxWorkspaceService.
 * This class does not run Claude Code and does not apply patches. Its job is to make every legacy
 * entry resolve to a P9 workspace and force P9 validation before Claude Code can use the directory.</p>
 */
@Component
public class SelfDevWorkspaceGuard {

    private final SandboxWorkspaceProperties workspaceProperties; // P9 workspace configuration.
    private final SandboxWorkspaceGuard sandboxWorkspaceGuard; // P9 path guard.
    private final SandboxWorkspaceService sandboxWorkspaceService; // P9 workspace service.

    public SelfDevWorkspaceGuard(SandboxWorkspaceProperties workspaceProperties,
                                 SandboxWorkspaceGuard sandboxWorkspaceGuard,
                                 SandboxWorkspaceService sandboxWorkspaceService) {
        this.workspaceProperties = workspaceProperties;
        this.sandboxWorkspaceGuard = sandboxWorkspaceGuard;
        this.sandboxWorkspaceService = sandboxWorkspaceService;
    }

    public Path resolveSandboxWorkspace() {
        return sandboxWorkspaceGuard.ensureSandboxRoot(); // Always use the P9 sandbox root.
    }

    public Path ensureSandboxWorkspace() {
        return sandboxWorkspaceGuard.ensureSandboxRoot(); // Keep old callers compatible.
    }

    public void assertSandboxWorkspace(Path workspace) {
        Path root = resolveSandboxWorkspace();
        Path actual = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (actual == null || !actual.equals(root)) {
            throw new IllegalArgumentException("Claude Code sandbox root must be the P9 sandboxRoot.");
        }
    }

    public List<String> listProjects() {
        List<String> projects = new ArrayList<>();
        Path sandboxRoot = ensureSandboxWorkspace();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sandboxRoot)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && isManagedWorkspaceName(child.getFileName().toString())) {
                    projects.add(child.getFileName().toString());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list P9 sandbox workspaces: " + e.getMessage(), e);
        }
        projects.sort(String.CASE_INSENSITIVE_ORDER);
        return projects;
    }

    public List<String> listWorkspaceIds() {
        List<String> workspaceIds = new ArrayList<>();
        for (String project : listProjects()) {
            workspaceIds.add(extractWorkspaceId(project));
        }
        return workspaceIds;
    }

    public String sanitizeProjectName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Project name is required.");
        }
        String name = rawName.trim().replace('\\', '/');
        if (name.contains("/") || name.contains(":") || name.contains("..") || name.startsWith(".")) {
            throw new IllegalArgumentException("Invalid project name: " + rawName);
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Project name is empty after sanitizing.");
        }
        return cleaned;
    }

    public String createWorkspaceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public String createWorkspaceName(String projectName, String workspaceId) {
        String safeProject = sanitizeProjectName(projectName);
        String id = workspaceId == null || workspaceId.isBlank() ? createWorkspaceId() : workspaceId.trim();
        String prefix = workspacePrefix();
        String base = safeProject.startsWith(prefix) ? safeProject : prefix + safeProject;
        if (!base.endsWith("-" + id) && !base.equals(prefix + id)) {
            base = base + "-" + id;
        }
        return sandboxWorkspaceGuard.sanitizeWorkspaceName(base);
    }

    public Path resolveNewProjectWorkspace(String workspaceName) {
        return sandboxWorkspaceGuard.toSafeWorkspacePath(workspaceName);
    }

    public Path resolveProjectWorkspace(String projectNameOrWorkspaceId) {
        SandboxWorkspaceInfo info = resolveWorkspaceInfo(projectNameOrWorkspaceId);
        Path workspace = Path.of(info.getWorkspacePath()).toAbsolutePath().normalize();
        validateWorkspaceForClaude(workspace);
        return workspace;
    }

    public Path resolveProjectWorkspace(String workspaceId, String projectName) {
        String target = firstNonBlank(workspaceId, projectName);
        if (target != null) {
            return resolveProjectWorkspace(target);
        }
        List<String> projects = listProjects();
        if (projects.size() == 1) {
            return resolveProjectWorkspace(projects.get(0));
        }
        if (projects.isEmpty()) {
            throw new IllegalStateException("No P9 workspace exists. Import a project first.");
        }
        throw new IllegalArgumentException("Choose a P9 workspace before running Claude Code.");
    }

    public SandboxWorkspaceInfo resolveWorkspaceInfo(String workspaceId, String projectName) {
        String target = firstNonBlank(workspaceId, projectName);
        if (target != null) {
            return resolveWorkspaceInfo(target);
        }
        List<String> projects = listProjects();
        if (projects.size() == 1) {
            return resolveWorkspaceInfo(projects.get(0));
        }
        if (projects.isEmpty()) {
            throw new IllegalStateException("No P9 workspace exists. Import a project first.");
        }
        throw new IllegalArgumentException("Choose a P9 workspace before running Claude Code.");
    }

    public SandboxWorkspaceInfo resolveWorkspaceInfo(String projectNameOrWorkspaceId) {
        if (projectNameOrWorkspaceId == null || projectNameOrWorkspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId or project is required.");
        }
        return sandboxWorkspaceService.getWorkspaceInfo(projectNameOrWorkspaceId.trim());
    }

    public void assertProjectWorkspace(Path workspace) {
        validateWorkspaceForClaude(workspace);
    }

    public void validateWorkspaceForClaude(Path workspace) {
        Path safeWorkspace = sandboxWorkspaceGuard.validateWorkspacePath(workspace);
        SandboxWorkspaceOperationResult validation =
                sandboxWorkspaceService.validateWorkspaceForClaude(safeWorkspace.toString());
        if (!validation.isSuccess()) {
            throw new IllegalArgumentException(validation.getErrorMsg() == null
                    ? validation.getMessage()
                    : validation.getErrorMsg());
        }
    }

    public Path validateDeletionTarget(Path targetPath) {
        return sandboxWorkspaceGuard.validateDeletionTarget(targetPath);
    }

    public String relativeToSandbox(Path workspace) {
        return sandboxWorkspaceGuard.relativeToSandbox(workspace);
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

    public String extractWorkspaceId(String workspaceName) {
        String name = workspaceName == null ? "" : workspaceName.trim();
        String prefix = workspacePrefix();
        if (name.startsWith(prefix)) {
            String rest = name.substring(prefix.length());
            int dash = rest.lastIndexOf('-');
            return dash >= 0 && dash < rest.length() - 1 ? rest.substring(dash + 1) : rest;
        }
        int dash = name.lastIndexOf('-');
        return dash >= 0 && dash < name.length() - 1 ? name.substring(dash + 1) : name;
    }

    private boolean isManagedWorkspaceName(String name) {
        return name != null && name.startsWith(workspacePrefix()) && !name.startsWith(".");
    }

    private String workspacePrefix() {
        String prefix = workspaceProperties.getWorkspacePrefix();
        return prefix == null || prefix.trim().isEmpty() ? "selfdev-" : prefix.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
