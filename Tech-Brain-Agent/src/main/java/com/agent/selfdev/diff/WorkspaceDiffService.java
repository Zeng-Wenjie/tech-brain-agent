package com.agent.selfdev.diff;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the git worktree changes produced inside the sandbox workspace.
 */
@Slf4j
@Component
public class WorkspaceDiffService {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final long UNTRACKED_TEXT_LIMIT_BYTES = 200_000L;

    public WorkspaceDiffResult collect(Path workspace) {
        assertGitWorkspace(workspace);
        WorkspaceDiffResult result = new WorkspaceDiffResult();
        List<StatusEntry> statusEntries = readStatus(workspace);
        List<String> changedFiles = statusEntries.stream()
                .map(StatusEntry::path)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        result.setChangedFiles(changedFiles);
        result.setDiff(readDiff(workspace, statusEntries));
        return result;
    }

    public List<String> findRejectedFiles(List<String> changedFiles, List<String> allowedPaths) {
        List<String> rejected = new ArrayList<>();
        if (changedFiles == null || changedFiles.isEmpty()) {
            return rejected;
        }
        if (allowedPaths != null && allowedPaths.contains(".")) {
            return rejected;
        }
        for (String changedFile : changedFiles) {
            String normalized = normalizePath(changedFile);
            if (normalized == null || normalized.equals(".git") || normalized.startsWith(".git/")) {
                rejected.add(changedFile);
                continue;
            }
            if (!isAllowed(normalized, allowedPaths)) {
                rejected.add(changedFile);
            }
        }
        return rejected;
    }

    private void assertGitWorkspace(Path workspace) {
        GitResult result = runGit(workspace, List.of("rev-parse", "--is-inside-work-tree"));
        if (result.exitCode() != 0 || !"true".equals(result.stdout().trim())) {
            throw new IllegalStateException("Sandbox workspace must be a git worktree.");
        }
    }

    private List<StatusEntry> readStatus(Path workspace) {
        GitResult result = runGit(workspace, List.of("status", "--porcelain=v1", "--untracked-files=all"));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Failed to read sandbox git status: " + result.stderr());
        }
        Set<StatusEntry> entries = new LinkedHashSet<>();
        for (String line : result.stdout().split("\\R")) {
            if (line == null || line.isBlank() || line.length() < 4) {
                continue;
            }
            String status = line.substring(0, 2);
            String pathPart = line.substring(3).trim();
            String path = parseStatusPath(pathPart);
            if (path != null && !path.isBlank()) {
                entries.add(new StatusEntry(status, path));
            }
        }
        return new ArrayList<>(entries);
    }

    private String readDiff(Path workspace, List<StatusEntry> entries) {
        GitResult result = runGit(workspace, List.of("diff", "--no-ext-diff", "--binary", "HEAD", "--"));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Failed to read sandbox diff: " + result.stderr());
        }
        StringBuilder diff = new StringBuilder(result.stdout());
        for (StatusEntry entry : entries) {
            if ("??".equals(entry.status())) {
                appendUntrackedDiff(workspace, entry.path(), diff);
            }
        }
        return diff.toString();
    }

    private void appendUntrackedDiff(Path workspace, String relativePath, StringBuilder diff) {
        try {
            Path file = workspace.resolve(relativePath).normalize();
            if (!file.startsWith(workspace.normalize()) || !Files.isRegularFile(file)) {
                return;
            }
            long size = Files.size(file);
            if (size > UNTRACKED_TEXT_LIMIT_BYTES) {
                appendBinaryNotice(relativePath, diff, "file too large to inline");
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (isBinary(bytes)) {
                appendBinaryNotice(relativePath, diff, "binary file");
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", -1);
            int lineCount = content.isEmpty() ? 0 : lines.length;
            if (lineCount > 0 && lines[lines.length - 1].isEmpty()) {
                lineCount--;
            }
            diff.append(System.lineSeparator())
                    .append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append(System.lineSeparator())
                    .append("new file mode 100644").append(System.lineSeparator())
                    .append("--- /dev/null").append(System.lineSeparator())
                    .append("+++ b/").append(relativePath).append(System.lineSeparator())
                    .append("@@ -0,0 +1,").append(lineCount).append(" @@").append(System.lineSeparator());
            for (int i = 0; i < lines.length; i++) {
                if (i == lines.length - 1 && lines[i].isEmpty()) {
                    continue;
                }
                diff.append('+').append(lines[i]).append(System.lineSeparator());
            }
        } catch (Exception e) {
            log.warn("[SelfDevDiff] failed to inline untracked file: {}, error: {}", relativePath, e.getMessage());
            appendBinaryNotice(relativePath, diff, "failed to inline: " + e.getMessage());
        }
    }

    private void appendBinaryNotice(String relativePath, StringBuilder diff, String reason) {
        diff.append(System.lineSeparator())
                .append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append(System.lineSeparator())
                .append("new file mode 100644").append(System.lineSeparator())
                .append("--- /dev/null").append(System.lineSeparator())
                .append("+++ b/").append(relativePath).append(System.lineSeparator())
                .append("@@ -0,0 +1 @@").append(System.lineSeparator())
                .append("+[untracked file omitted: ").append(reason).append("]").append(System.lineSeparator());
    }

    private boolean isAllowed(String changedFile, List<String> allowedPaths) {
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return false;
        }
        for (String allowedPath : allowedPaths) {
            String allowed = normalizePath(allowedPath);
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            if (changedFile.equals(allowed) || changedFile.startsWith(allowed + "/")) {
                return true;
            }
        }
        return false;
    }

    private String parseStatusPath(String pathPart) {
        int renameArrow = pathPart.indexOf(" -> ");
        String path = renameArrow >= 0 ? pathPart.substring(renameArrow + 4) : pathPart;
        return normalizePath(stripQuotes(path));
    }

    private String stripQuotes(String path) {
        if (path == null) {
            return null;
        }
        String value = path.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean isBinary(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
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

    private record StatusEntry(String status, String path) {
    }

    private record GitResult(int exitCode, String stdout, String stderr) {
    }
}
