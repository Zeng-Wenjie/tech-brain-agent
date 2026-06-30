package com.agent.selfdev.maven;

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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * P11 Maven compile 命令执行器。
 *
 * <p>适用场景：MavenCompileService 传入 MavenCommand 后，本类在 P9 sandbox workspace 内使用 ProcessBuilder
 * 执行命令，并捕获 stdout、stderr、exitCode、耗时和超时状态。本类不构造命令，不接受任意 shell 字符串。</p>
 *
 * <p>调用链：MavenCompileService -> CompileCommandExecutor.run -> SandboxWorkspaceGuard.validateWorkspacePath
 * -> ProcessBuilder。workingDir 必须是 P9 sandbox workspace，禁止 sourceRepoDir / 线上运行目录。</p>
 */
@Component
public class CompileCommandExecutor {

    private static final List<String> BLOCKED_TOKENS = List.of(
            ";", "&&", "||", "|", ">", "<", "`", "$(", "powershell", "cmd", " sh ", "bash"); // shell/命令注入片段。
    private static final Set<String> BLOCKED_GOALS = Set.of(
            "deploy", "release", "install", "test", "package", "clean", "exec", "dependency:get"); // P11 禁止 Maven goal。

    private final SandboxWorkspaceGuard workspaceGuard; // P9 路径护栏。

    public CompileCommandExecutor(SandboxWorkspaceGuard workspaceGuard) {
        this.workspaceGuard = workspaceGuard; // 注入 P9 Guard。
    }

    public CommandResult run(Path workspace, List<String> command, Duration timeout) {
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 工作目录必须在 sandboxRoot 内。
        validateCompileCommand(command); // 执行器兜底确认只能跑 Maven compile。
        if (!Files.isDirectory(safeWorkspace)) {
            return CommandResult.failed("workspace 不存在或不是目录。"); // 目录不存在直接失败。
        }
        Process process = null; // 保存进程用于超时终止。
        long start = System.currentTimeMillis(); // 记录开始时间。
        try {
            ProcessBuilder builder = new ProcessBuilder(command); // 参数数组，不经过 shell。
            builder.directory(safeWorkspace.toFile()); // 固定在 P9 workspace 根目录执行。
            process = builder.start(); // 启动 Maven。
            CompletableFuture<String> stdout = readAsync(process.getInputStream()); // 异步读取 stdout。
            CompletableFuture<String> stderr = readAsync(process.getErrorStream()); // 异步读取 stderr。
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); // 等待有限时间。
            if (!finished) {
                process.destroyForcibly(); // 超时强制中断。
                return new CommandResult(-1, stdout.getNow(""), stderr.getNow(""), true,
                        System.currentTimeMillis() - start); // 返回超时结果。
            }
            return new CommandResult(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS),
                    false,
                    System.currentTimeMillis() - start); // 返回正常结果。
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly(); // 异常时也清理进程。
            }
            return new CommandResult(-1, "", e.getMessage(), false, System.currentTimeMillis() - start); // 返回异常摘要。
        }
    }

    private void validateCompileCommand(List<String> command) {
        if (command == null || command.size() < 2) {
            throw new IllegalArgumentException("Maven compile 命令不能为空。"); // 命令必须至少包含 mvn 和 compile。
        }
        long compileCount = 0L; // 统计 compile goal。
        for (String part : command) {
            String value = part == null ? "" : part.trim(); // 参数兜底。
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Maven 参数不能为空。"); // 空参数拒绝。
            }
            String lower = value.toLowerCase(Locale.ROOT); // 小写比较。
            for (String token : BLOCKED_TOKENS) {
                if (lower.contains(token)) {
                    throw new IllegalArgumentException("Maven 参数包含不允许的 shell 片段。"); // shell 注入拒绝。
                }
            }
            if (BLOCKED_GOALS.contains(lower)) {
                throw new IllegalArgumentException("P11 只允许执行 Maven compile。"); // 危险 goal 拒绝。
            }
            if ("compile".equals(lower)) {
                compileCount++; // 统计 compile。
            }
        }
        if (compileCount != 1 || !"compile".equals(command.get(command.size() - 1))) {
            throw new IllegalArgumentException("P11 Maven 命令必须以 compile 作为唯一 goal。"); // 最终兜底。
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
                builder.append("[读取 Maven 输出失败] ").append(e.getMessage()); // 输出读取失败也返回摘要。
            }
            return builder.toString(); // 返回输出。
        });
    }

    /**
     * Maven compile 命令执行结果。
     */
    @Getter
    public static class CommandResult {
        private final int exitCode; // 进程退出码。
        private final String stdout; // 标准输出。
        private final String stderr; // 错误输出。
        private final boolean timeout; // 是否超时。
        private final long costTimeMs; // 耗时。

        public CommandResult(int exitCode, String stdout, String stderr, boolean timeout, long costTimeMs) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timeout = timeout;
            this.costTimeMs = costTimeMs;
        }

        public static CommandResult failed(String errorMsg) {
            return new CommandResult(-1, "", errorMsg, false, 0L); // 构造失败结果。
        }
    }
}
