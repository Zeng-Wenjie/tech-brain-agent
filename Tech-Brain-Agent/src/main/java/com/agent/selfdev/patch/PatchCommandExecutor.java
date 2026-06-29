package com.agent.selfdev.patch;

import com.agent.config.ApplyPatchProperties;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * P10 patch 命令安全执行器。
 *
 * <p>适用场景：PatchApplyService 需要执行 git apply --check 和 git apply 时使用。
 * 本类不通过 shell 拼接命令，不执行 git commit、git push 或任意用户输入命令。</p>
 *
 * <p>调用链：PatchApplyService -> PatchCommandExecutor.check/apply -> SandboxWorkspaceGuard 校验工作目录
 * -> ProcessBuilder 执行白名单 git apply 命令。</p>
 */
@Component
public class PatchCommandExecutor {

    private final ApplyPatchProperties properties; // patch 命令超时配置。
    private final SandboxWorkspaceGuard workspaceGuard; // P9 workspace 路径护栏。

    public PatchCommandExecutor(ApplyPatchProperties properties, SandboxWorkspaceGuard workspaceGuard) {
        this.properties = properties; // 注入配置。
        this.workspaceGuard = workspaceGuard; // 注入路径护栏。
    }

    public CommandResult check(Path workspace, Path patchFile) {
        return runGitApply(workspace, patchFile, true); // 执行 git apply --check。
    }

    public CommandResult apply(Path workspace, Path patchFile) {
        return runGitApply(workspace, patchFile, false); // 执行 git apply。
    }

    private CommandResult runGitApply(Path workspace, Path patchFile, boolean checkOnly) {
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 命令工作目录必须是合法 workspace。
        Path safePatchFile = validatePatchFile(safeWorkspace, patchFile); // patch 文件必须位于 workspace 内。
        Path relativePatchFile = safeWorkspace.relativize(safePatchFile); // 使用相对路径，减少绝对路径泄露。
        List<String> command = checkOnly
                ? List.of("git", "apply", "--check", relativePatchFile.toString())
                : List.of("git", "apply", relativePatchFile.toString()); // 白名单命令形态。
        return run(command, safeWorkspace, operationTimeout()); // 执行命令。
    }

    private Path validatePatchFile(Path workspace, Path patchFile) {
        if (patchFile == null) {
            throw new IllegalArgumentException("patchFile 不能为空。"); // 空 patch 文件拒绝。
        }
        Path safePatchFile = patchFile.toAbsolutePath().normalize(); // 规范化 patch 文件。
        if (!safePatchFile.startsWith(workspace)) {
            throw new IllegalArgumentException("patchFile 必须位于 sandbox workspace 内。"); // 拒绝 workspace 外文件。
        }
        if (!Files.isRegularFile(safePatchFile)) {
            throw new IllegalArgumentException("patchFile 不存在或不是普通文件。"); // 必须是普通文件。
        }
        return safePatchFile; // 返回安全 patch 文件。
    }

    private CommandResult run(List<String> command, Path workingDir, Duration timeout) {
        Process process = null; // 保存进程引用，便于超时销毁。
        try {
            ProcessBuilder builder = new ProcessBuilder(command); // 使用参数数组，不走 shell。
            builder.directory(workingDir.toFile()); // 工作目录已由 Guard 校验。
            process = builder.start(); // 启动进程。
            CompletableFuture<String> stdout = readAsync(process.getInputStream()); // 异步读取 stdout。
            CompletableFuture<String> stderr = readAsync(process.getErrorStream()); // 异步读取 stderr。
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); // 等待有限时间。
            if (!finished) {
                process.destroyForcibly(); // 超时强制结束。
                return new CommandResult(-1, "", "git apply 命令超时。"); // 返回超时结果。
            }
            return new CommandResult(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS)); // 返回执行结果。
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly(); // 异常也结束进程。
            }
            return new CommandResult(-1, "", e.getMessage()); // 返回异常摘要。
        }
    }

    private CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder builder = new StringBuilder(); // 收集输出。
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator()); // 保留换行。
                }
            } catch (Exception e) {
                builder.append("[读取命令输出失败] ").append(e.getMessage()); // 输出读取失败也返回摘要。
            }
            return builder.toString(); // 返回输出。
        });
    }

    private Duration operationTimeout() {
        int seconds = properties.getMaxOperationTimeoutSeconds() == null
                ? 120
                : Math.max(1, properties.getMaxOperationTimeoutSeconds()); // 至少 1 秒。
        return Duration.ofSeconds(seconds); // 转为 Duration。
    }

    /**
     * git apply 命令执行结果。
     *
     * <p>适用场景：承载 exitCode/stdout/stderr；调用方负责脱敏、截断后再写日志。</p>
     */
    @Getter
    public static class CommandResult {
        private final int exitCode; // 进程退出码。
        private final String stdout; // 标准输出。
        private final String stderr; // 错误输出。

        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode; // 保存退出码。
            this.stdout = stdout == null ? "" : stdout; // stdout 兜底。
            this.stderr = stderr == null ? "" : stderr; // stderr 兜底。
        }

        public boolean isSuccess() {
            return exitCode == 0; // exitCode=0 才算成功。
        }
    }
}
