package com.agent.selfdev.project;

import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SelfDevProjectImportRequest;
import com.agent.entity.dto.SelfDevProjectImportResult;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevTargetType;
import com.agent.selfdev.security.SelfDevWorkspaceGuard;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Imports a local project copy into the sandbox root used by Claude Code.
 */
@Slf4j
@Service
public class SelfDevProjectImportService {

    private final SelfDevWorkspaceGuard workspaceGuard;
    private final DevActionLogService devActionLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SelfDevProjectImportService(SelfDevWorkspaceGuard workspaceGuard,
                                       DevActionLogService devActionLogService) {
        this.workspaceGuard = workspaceGuard;
        this.devActionLogService = devActionLogService;
    }

    public SelfDevProjectImportResult importProject(SelfDevProjectImportRequest request, Long userId) {
        SelfDevProjectImportResult result = new SelfDevProjectImportResult();
        String traceId = UUID.randomUUID().toString();
        boolean overwrite = request != null && Boolean.TRUE.equals(request.getOverwrite());
        try {
            Path source = resolveSourcePath(request);
            String projectName = source.getFileName() == null ? source.toString() : source.getFileName().toString();
            Path sandbox = workspaceGuard.ensureSandboxWorkspace();
            Path realSource = source.toRealPath();
            Path realSandbox = sandbox.toRealPath();
            rejectOverlappingPaths(realSource, realSandbox);

            result.setProjectName(projectName);
            result.setSandboxWorkspaceDir(realSandbox.toString());
            if (!isDirectoryEmpty(realSandbox)) {
                if (!overwrite) {
                    result.setSuccess(false);
                    result.setMessage("Sandbox workspace is not empty. Enable overwrite to replace it.");
                    saveLog(userId, traceId, result, overwrite, null);
                    return result;
                }
                clearDirectory(realSandbox);
            }

            CopyStats stats = copyProjectContents(realSource, realSandbox);
            result.setSuccess(true);
            result.setFileCount(stats.fileCount);
            result.setDirectoryCount(stats.directoryCount);
            result.setByteCount(stats.byteCount);
            result.setMessage("Project imported into the Claude Code sandbox.");
            saveLog(userId, traceId, result, overwrite, null);
            return result;
        } catch (Exception e) {
            log.warn("[SelfDevProjectImport] import failed: {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setMessage(e.getMessage());
            saveLog(userId, traceId, result, overwrite, e);
            return result;
        }
    }

    private Path resolveSourcePath(SelfDevProjectImportRequest request) {
        if (request == null || isBlank(request.getSourcePath())) {
            throw new IllegalArgumentException("Project source path is required.");
        }
        Path source = Paths.get(request.getSourcePath().trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Project source path does not exist or is not a directory: " + source);
        }
        return source;
    }

    private void rejectOverlappingPaths(Path source, Path sandbox) {
        if (samePath(source, sandbox) || source.startsWith(sandbox) || sandbox.startsWith(source)) {
            throw new IllegalArgumentException("Project source and sandbox workspace must be separate directories.");
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return !stream.iterator().hasNext();
        }
    }

    private void clearDirectory(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                deleteRecursively(child);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private CopyStats copyProjectContents(Path source, Path sandbox) throws IOException {
        CopyStats stats = new CopyStats();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty()) {
                    Files.createDirectories(sandbox.resolve(relative).normalize());
                    stats.directoryCount++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = sandbox.resolve(source.relativize(file)).normalize();
                if (!target.startsWith(sandbox)) {
                    throw new IOException("Refused to copy outside sandbox: " + target);
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                stats.fileCount++;
                stats.byteCount += safeSize(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return stats;
    }

    private long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0L;
        }
    }

    private void saveLog(Long userId,
                         String traceId,
                         SelfDevProjectImportResult result,
                         boolean overwrite,
                         Exception error) {
        try {
            DevActionLogCreateRequest request = new DevActionLogCreateRequest();
            request.setUserId(userId);
            request.setTraceId(traceId);
            request.setResult(result.isSuccess() ? DevActionResult.SUCCESS.name() : DevActionResult.FAILED.name());
            request.setTargetType(DevTargetType.MODULE.name());
            request.setTargetModule(isBlank(result.getProjectName()) ? "Claude Code sandbox" : result.getProjectName());
            request.setTargetPath(".");
            request.setTitle("Import project into Claude Code sandbox");
            request.setIntent("Import a local project copy into the Claude Code sandbox workspace.");
            request.setSummary(result.isSuccess()
                    ? "Project imported into the Claude Code sandbox for isolated development."
                    : "Project import into the Claude Code sandbox failed.");
            request.setResultJson(toJson(result, overwrite));
            request.setErrorMsg(error == null ? null : error.getMessage());
            DevActionLogSaveResult saveResult = devActionLogService.recordFileModified(request);
            if (saveResult != null && saveResult.isSaved()) {
                result.setDevLogId(saveResult.getDevLogId());
            }
        } catch (Exception e) {
            log.warn("[SelfDevProjectImport] failed to save dev_action_log: {}", e.getMessage(), e);
        }
    }

    private String toJson(SelfDevProjectImportResult result, boolean overwrite) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", result.isSuccess());
            payload.put("projectName", result.getProjectName());
            payload.put("sandboxWorkspaceDir", result.getSandboxWorkspaceDir());
            payload.put("fileCount", result.getFileCount());
            payload.put("directoryCount", result.getDirectoryCount());
            payload.put("byteCount", result.getByteCount());
            payload.put("overwrite", overwrite);
            payload.put("message", result.getMessage());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize project import result\"}";
        }
    }

    private boolean samePath(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (Exception e) {
            return left.equals(right);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class CopyStats {
        private long fileCount;
        private long directoryCount;
        private long byteCount;
    }
}
