package com.agent.selfdev.client;

import com.agent.config.SelfDevProperties;
import com.agent.entity.dto.SelfDevClaudeAuthResult;
import com.agent.selfdev.security.SelfDevWorkspaceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Claude Code headless CLI 客户端。
 */
@Slf4j
@Component
public class ClaudeCodeClient {

    private static final int STREAM_DRAIN_TIMEOUT_SECONDS = 5;

    private final SelfDevProperties properties;
    private final SelfDevWorkspaceGuard workspaceGuard;

    public ClaudeCodeClient(SelfDevProperties properties, SelfDevWorkspaceGuard workspaceGuard) {
        this.properties = properties;
        this.workspaceGuard = workspaceGuard;
    }

    public ClaudeCodeResult run(Path workspace, String prompt, Duration timeout) {
        workspaceGuard.assertSandboxWorkspace(workspace);
        long start = System.currentTimeMillis();
        ClaudeCodeResult result = new ClaudeCodeResult();
        Process process = null;
        try {
            List<String> command = buildCommand(prompt);
            log.info("[ClaudeCode] start, workspace: {}, timeoutSeconds: {}", workspace, timeout.toSeconds());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.toFile());
            process = builder.start();

            CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.setTimedOut(true);
                result.setExitCode(-1);
                result.setErrorMessage("Claude Code 执行超时。");
            } else {
                result.setExitCode(process.exitValue());
            }
            result.setStdout(stdoutFuture.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            result.setStderr(stderrFuture.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            result.setSuccess(!result.isTimedOut() && result.getExitCode() == 0);
            if (!result.isSuccess() && result.getErrorMessage() == null) {
                result.setErrorMessage("Claude Code 执行失败，exitCode=" + result.getExitCode());
            }
            return result;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            result.setSuccess(false);
            result.setExitCode(-1);
            result.setErrorMessage(e.getMessage());
            log.warn("[ClaudeCode] run failed: {}", e.getMessage(), e);
            return result;
        } finally {
            result.setDurationMs(System.currentTimeMillis() - start);
        }
    }

    public SelfDevClaudeAuthResult readAuthStatus(Duration timeout) {
        long start = System.currentTimeMillis();
        SelfDevClaudeAuthResult result = new SelfDevClaudeAuthResult();
        List<String> command = buildAuthCommand("status", "--text");
        result.setCommand(String.join(" ", command));
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
            CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.setAvailable(true);
                result.setAuthenticated(false);
                result.setExitCode(-1);
                result.setErrorMessage("Claude Code 登录状态检测超时。");
                return result;
            }
            result.setExitCode(process.exitValue());
            result.setStdout(stdoutFuture.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            result.setStderr(stderrFuture.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            result.setAvailable(true);
            result.setAuthenticated(result.getExitCode() == 0);
            if (!result.isAuthenticated()) {
                result.setErrorMessage("Claude Code CLI 尚未登录。");
            }
            return result;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            result.setAvailable(false);
            result.setAuthenticated(false);
            result.setExitCode(-1);
            result.setErrorMessage(e.getMessage());
            return result;
        } finally {
            result.setDurationMs(System.currentTimeMillis() - start);
        }
    }

    public SelfDevClaudeAuthResult startAuthLogin() {
        long start = System.currentTimeMillis();
        SelfDevClaudeAuthResult result = new SelfDevClaudeAuthResult();
        List<String> command = buildAuthCommand("login");
        result.setCommand(String.join(" ", command));
        try {
            List<String> launcher = buildLoginLauncher(command);
            new ProcessBuilder(launcher).start();
            result.setAvailable(true);
            result.setLoginStarted(true);
            result.setExitCode(0);
            return result;
        } catch (Exception e) {
            result.setAvailable(false);
            result.setLoginStarted(false);
            result.setExitCode(-1);
            result.setErrorMessage(e.getMessage());
            return result;
        } finally {
            result.setDurationMs(System.currentTimeMillis() - start);
        }
    }

    private List<String> buildCommand(String prompt) {
        SelfDevProperties.ClaudeCode claudeCode = properties.getClaudeCode();
        List<String> command = new ArrayList<>();
        command.add(claudeCode.getExecutable());
        if (claudeCode.getArguments() != null) {
            command.addAll(claudeCode.getArguments());
        }
        command.add(prompt == null ? "" : prompt);
        return command;
    }

    private List<String> buildAuthCommand(String... arguments) {
        SelfDevProperties.ClaudeCode claudeCode = properties.getClaudeCode();
        List<String> command = new ArrayList<>();
        command.add(claudeCode.getExecutable());
        command.add("auth");
        if (arguments != null) {
            command.addAll(List.of(arguments));
        }
        return command;
    }

    private List<String> buildLoginLauncher(List<String> command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            List<String> launcher = new ArrayList<>();
            launcher.add("cmd.exe");
            launcher.add("/c");
            launcher.add("start");
            launcher.add("Claude Code Login");
            launcher.addAll(command);
            return launcher;
        }
        return command;
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
}
