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
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Imports a local project copy into the sandbox root used by Claude Code.
 */
@Slf4j
@Service
public class SelfDevProjectImportService {

    private final SelfDevWorkspaceGuard workspaceGuard;
    private final DevActionLogService devActionLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);

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
            ensureGitBaseline(realSandbox);
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

    public SelfDevProjectImportResult importUploadedProject(List<MultipartFile> files,
                                                            List<String> relativePaths,
                                                            String projectName,
                                                            boolean overwrite,
                                                            Long userId) {
        SelfDevProjectImportResult result = new SelfDevProjectImportResult();
        String traceId = UUID.randomUUID().toString();
        try {
            List<UploadEntry> entries = normalizeUploadEntries(files, relativePaths);
            String resolvedProjectName = firstNonBlank(sanitizeProjectName(projectName), deriveProjectName(entries));
            Path sandbox = workspaceGuard.ensureSandboxWorkspace().toRealPath();

            result.setProjectName(resolvedProjectName);
            result.setSandboxWorkspaceDir(sandbox.toString());
            if (!isDirectoryEmpty(sandbox)) {
                if (!overwrite) {
                    result.setSuccess(false);
                    result.setMessage("Sandbox workspace is not empty. Enable overwrite to replace it.");
                    saveLog(userId, traceId, result, overwrite, null);
                    return result;
                }
                clearDirectory(sandbox);
            }

            CopyStats stats = copyUploadedProject(entries, sandbox);
            ensureGitBaseline(sandbox);
            result.setSuccess(true);
            result.setFileCount(stats.fileCount);
            result.setDirectoryCount(stats.directoryCount);
            result.setByteCount(stats.byteCount);
            result.setMessage("Local project uploaded into the Claude Code sandbox.");
            saveLog(userId, traceId, result, overwrite, null);
            return result;
        } catch (Exception e) {
            log.warn("[SelfDevProjectImport] upload import failed: {}", e.getMessage(), e);
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

    private List<UploadEntry> normalizeUploadEntries(List<MultipartFile> files, List<String> relativePaths) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Choose a local project folder first.");
        }
        List<UploadEntry> entries = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) {
                continue;
            }
            String rawPath = relativePaths != null && i < relativePaths.size()
                    ? relativePaths.get(i)
                    : file.getOriginalFilename();
            String relativePath = sanitizeUploadRelativePath(rawPath);
            if (relativePath != null) {
                entries.add(new UploadEntry(file, relativePath));
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("The selected project folder contains no importable files.");
        }
        return entries;
    }

    private CopyStats copyUploadedProject(List<UploadEntry> entries, Path sandbox) throws IOException {
        CopyStats stats = new CopyStats();
        String commonRoot = commonRootSegment(entries);
        Set<String> directories = new LinkedHashSet<>();
        for (UploadEntry entry : entries) {
            String relativePath = stripCommonRoot(entry.relativePath, commonRoot);
            if (isBlank(relativePath)) {
                continue;
            }
            Path target = sandbox.resolve(relativePath).normalize();
            if (!target.startsWith(sandbox)) {
                throw new IOException("Refused to copy outside sandbox: " + target);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                rememberDirectories(sandbox, parent, directories);
            }
            try (InputStream inputStream = entry.file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            stats.fileCount++;
            stats.byteCount += entry.file.getSize();
        }
        stats.directoryCount = directories.size();
        return stats;
    }

    private void rememberDirectories(Path sandbox, Path directory, Set<String> directories) {
        Path current = directory.normalize();
        while (current != null && current.startsWith(sandbox) && !current.equals(sandbox)) {
            directories.add(sandbox.relativize(current).toString().replace('\\', '/'));
            current = current.getParent();
        }
    }

    private String sanitizeUploadRelativePath(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/") || path.contains(":") || path.contains("..")) {
            throw new IllegalArgumentException("Uploaded project paths must be folder-relative: " + raw);
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private String deriveProjectName(List<UploadEntry> entries) {
        String commonRoot = commonRootSegment(entries);
        if (!isBlank(commonRoot)) {
            return commonRoot;
        }
        return "local-project";
    }

    private String commonRootSegment(List<UploadEntry> entries) {
        String root = null;
        for (UploadEntry entry : entries) {
            String first = firstSegment(entry.relativePath);
            if (isBlank(first)) {
                return null;
            }
            if (root == null) {
                root = first;
            } else if (!root.equals(first)) {
                return null;
            }
        }
        return root;
    }

    private String stripCommonRoot(String path, String commonRoot) {
        if (isBlank(commonRoot)) {
            return path;
        }
        if (path.equals(commonRoot)) {
            return null;
        }
        return path.startsWith(commonRoot + "/") ? path.substring(commonRoot.length() + 1) : path;
    }

    private String firstSegment(String path) {
        if (isBlank(path)) {
            return null;
        }
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    private String sanitizeProjectName(String projectName) {
        if (isBlank(projectName)) {
            return null;
        }
        String cleaned = projectName.trim().replace('\\', '/');
        cleaned = firstSegment(cleaned);
        if (cleaned == null || cleaned.contains(":") || cleaned.contains("..") || cleaned.startsWith("/")) {
            return null;
        }
        return cleaned;
    }

    private void ensureGitBaseline(Path sandbox) throws IOException {
        if (Files.isDirectory(sandbox.resolve(".git"))) {
            return;
        }
        GitResult init = runGit(sandbox, List.of("init"));
        if (init.exitCode() != 0) {
            throw new IOException("Failed to initialize sandbox git repository: " + firstNonBlank(init.stderr(), init.stdout()));
        }
        GitResult add = runGit(sandbox, List.of("add", "-A"));
        if (add.exitCode() != 0) {
            throw new IOException("Failed to stage sandbox import baseline: " + firstNonBlank(add.stderr(), add.stdout()));
        }
        GitResult commit = runGit(sandbox, List.of(
                "-c", "user.name=Tech-Brain",
                "-c", "user.email=tech-brain@local",
                "commit", "-m", "Sandbox import baseline"));
        if (commit.exitCode() != 0 && !containsNothingToCommit(commit)) {
            throw new IOException("Failed to commit sandbox import baseline: " + firstNonBlank(commit.stderr(), commit.stdout()));
        }
    }

    private boolean containsNothingToCommit(GitResult result) {
        String output = (result.stdout() + "\n" + result.stderr()).toLowerCase();
        return output.contains("nothing to commit") || output.contains("no changes added to commit");
    }

    private GitResult runGit(Path workspace, List<String> args) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(args);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.toFile());
            process = builder.start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GitResult(-1, "", "git command timed out: " + String.join(" ", args));
            }
            return new GitResult(
                    process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new GitResult(-1, "", e.getMessage());
        }
    }

    private CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator());
                }
            } catch (Exception e) {
                builder.append("[stream read failed] ").append(e.getMessage());
            }
            return builder.toString();
        });
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record UploadEntry(MultipartFile file, String relativePath) {
    }

    private record GitResult(int exitCode, String stdout, String stderr) {
    }

    private static class CopyStats {
        private long fileCount;
        private long directoryCount;
        private long byteCount;
    }
}
