package com.agent.selfdev.workspace;

import com.agent.config.SandboxWorkspaceProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * P9 沙箱 Git 命令安全执行器。
 *
 * <p>适用场景：SandboxWorkspaceService 需要在沙箱 workspace 内执行极少量 Git 命令时使用。
 * 本类只允许白名单 Git 子命令，不通过 shell 拼接，不接受任意用户输入命令，不执行删除系统文件命令。</p>
 *
 * <p>调用链：SandboxWorkspaceService -> SafeCommandExecutor -> SandboxWorkspaceGuard 校验工作目录
 * -> ProcessBuilder 执行 git -> 返回 stdout/stderr/exitCode。</p>
 */
@Component
public class SafeCommandExecutor {

    private static final Set<String> ALLOWED_GIT_CONFIG_KEYS = Set.of("user.name", "user.email"); // 仅允许临时提交身份配置。

    private final SandboxWorkspaceProperties properties; // 命令超时配置。
    private final SandboxWorkspaceGuard workspaceGuard; // 命令工作目录护栏。

    public SafeCommandExecutor(SandboxWorkspaceProperties properties, SandboxWorkspaceGuard workspaceGuard) {
        this.properties = properties; // 注入 P9 配置。
        this.workspaceGuard = workspaceGuard; // 注入路径守卫。
    }

    public CommandResult runGitInWorkspace(Path workspace, List<String> args) {
        validateGitArgs(args, false); // 校验 Git 子命令白名单。
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // Git 工作目录必须是合法 workspace。
        if (!Files.isDirectory(safeWorkspace)) {
            return CommandResult.failed("workspace 不存在或不是目录。"); // 目录不存在直接失败。
        }
        List<String> command = new ArrayList<>(); // 使用 ProcessBuilder 参数数组，避免 shell 注入。
        command.add("git"); // 固定只执行 git。
        command.addAll(args); // 添加已校验白名单参数。
        return run(command, safeWorkspace, operationTimeout()); // 在 workspace 内执行。
    }

    public CommandResult runGitClone(String gitRemoteUrl, Path workspace) {
        if (gitRemoteUrl == null || gitRemoteUrl.trim().isEmpty()) {
            return CommandResult.failed("gitRemoteUrl 不能为空。"); // clone 必须有远程地址。
        }
        Path safeWorkspace = workspaceGuard.validateWorkspacePath(workspace); // 克隆目标必须是 sandboxRoot 内的 workspace。
        Path sandboxRoot = workspaceGuard.ensureSandboxRoot(); // clone 的 workingDir 固定为 sandboxRoot。
        workspaceGuard.validateSandboxRootForCommand(sandboxRoot); // 双重确认 clone 不在任意目录执行。
        List<String> command = List.of("git", "clone", gitRemoteUrl.trim(), safeWorkspace.toString()); // clone 不走 shell 拼接。
        return run(command, sandboxRoot, operationTimeout()); // 在 sandboxRoot 内执行 clone。
    }

    private void validateGitArgs(List<String> args, boolean clone) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("Git 命令参数不能为空。"); // 空命令拒绝。
        }
        int commandIndex = primaryCommandIndex(args); // 跳过受限的 -c 配置项。
        if (commandIndex < 0 || commandIndex >= args.size()) {
            throw new IllegalArgumentException("缺少 Git 子命令。"); // 没找到子命令。
        }
        String command = args.get(commandIndex); // 取出真正子命令。
        if ("clone".equals(command) && !clone) {
            throw new IllegalArgumentException("git clone 只能通过 runGitClone 执行。"); // clone 需要特殊目录校验。
        }
        switch (command) {
            case "init" -> requireExact(args, commandIndex, List.of("init")); // LOCAL_COPY 后初始化仓库。
            case "add" -> requireExact(args, commandIndex, List.of("add", "-A")); // 只允许添加 workspace 全量基线。
            case "commit" -> requireCommit(args, commandIndex); // 只允许提交本地基线。
            case "checkout" -> requireCheckout(args, commandIndex); // 只允许 checkout 指定分支。
            case "switch" -> requireSwitch(args, commandIndex); // 只允许 switch 或 switch -c。
            case "branch" -> requireBranch(args, commandIndex); // 只允许查看当前分支。
            case "reset" -> requireExact(args, commandIndex, List.of("reset", "--hard")); // 只允许恢复干净状态。
            case "clean" -> requireExact(args, commandIndex, List.of("clean", "-fd")); // 只允许清理未跟踪文件。
            case "status" -> requireStatus(args, commandIndex); // 只允许查询状态。
            default -> throw new IllegalArgumentException("不允许执行 Git 子命令: " + command); // 非白名单拒绝。
        }
    }

    private int primaryCommandIndex(List<String> args) {
        int index = 0; // 从第一个参数开始扫描。
        while (index < args.size() && "-c".equals(args.get(index))) {
            if (index + 1 >= args.size()) {
                throw new IllegalArgumentException("git -c 缺少配置项。"); // -c 必须成对出现。
            }
            String key = args.get(index + 1).split("=", 2)[0]; // 只取配置键名。
            if (!ALLOWED_GIT_CONFIG_KEYS.contains(key)) {
                throw new IllegalArgumentException("不允许的 git -c 配置项: " + key); // 禁止绕过 Git 配置。
            }
            index += 2; // 跳过 -c key=value。
        }
        return index; // 返回真正子命令位置。
    }

    private void requireExact(List<String> args, int commandIndex, List<String> expected) {
        List<String> actual = args.subList(commandIndex, args.size()); // 只比较子命令及其后参数。
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Git 命令参数不在白名单内: " + String.join(" ", actual)); // 精确白名单。
        }
    }

    private void requireCommit(List<String> args, int commandIndex) {
        List<String> actual = args.subList(commandIndex, args.size()); // 取 commit 参数。
        if (actual.size() != 3 || !"commit".equals(actual.get(0)) || !"-m".equals(actual.get(1))) {
            throw new IllegalArgumentException("只允许 git commit -m <message>。"); // 限定提交格式。
        }
    }

    private void requireCheckout(List<String> args, int commandIndex) {
        List<String> actual = args.subList(commandIndex, args.size()); // 取 checkout 参数。
        if (actual.size() != 2 || !"checkout".equals(actual.get(0)) || actual.get(1).startsWith("-")) {
            throw new IllegalArgumentException("只允许 git checkout <branch>。"); // 禁止 checkout 任意选项。
        }
    }

    private void requireSwitch(List<String> args, int commandIndex) {
        List<String> actual = args.subList(commandIndex, args.size()); // 取 switch 参数。
        boolean switchExisting = actual.size() == 2 && "switch".equals(actual.get(0)) && !actual.get(1).startsWith("-"); // 切换已有分支。
        boolean createBranch = actual.size() == 3 && "switch".equals(actual.get(0)) && "-c".equals(actual.get(1)); // 创建新分支。
        if (!switchExisting && !createBranch) {
            throw new IllegalArgumentException("只允许 git switch <branch> 或 git switch -c <branch>。"); // 限定 switch 形态。
        }
    }

    private void requireBranch(List<String> args, int commandIndex) {
        List<String> actual = args.subList(commandIndex, args.size()); // 取 branch 参数。
        if (!(actual.size() == 2 && "branch".equals(actual.get(0)) && "--show-current".equals(actual.get(1)))) {
            throw new IllegalArgumentException("只允许 git branch --show-current。"); // 只读查询当前分支。
        }
    }

    private void requireStatus(List<String> args, int commandIndex) {
        List<String> actual = args.subList(commandIndex, args.size()); // 取 status 参数。
        boolean plainStatus = actual.size() == 1 && "status".equals(actual.get(0)); // 普通状态查询。
        boolean shortStatus = actual.size() == 2 && "status".equals(actual.get(0)) && "--short".equals(actual.get(1)); // 简短状态查询。
        if (!plainStatus && !shortStatus) {
            throw new IllegalArgumentException("只允许 git status 或 git status --short。"); // 限定 status 形态。
        }
    }

    private CommandResult run(List<String> command, Path workingDir, Duration timeout) {
        Process process = null; // 保存进程以便超时销毁。
        try {
            ProcessBuilder builder = new ProcessBuilder(command); // 不使用 cmd/powershell shell。
            builder.directory(workingDir.toFile()); // 工作目录已由 Guard 校验。
            process = builder.start(); // 启动进程。
            CompletableFuture<String> stdout = readAsync(process.getInputStream()); // 异步读取 stdout，避免阻塞。
            CompletableFuture<String> stderr = readAsync(process.getErrorStream()); // 异步读取 stderr，避免阻塞。
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); // 等待有限时间。
            if (!finished) {
                process.destroyForcibly(); // 超时强制终止。
                return new CommandResult(-1, "", "Git 命令超时。"); // 返回超时结果。
            }
            return new CommandResult(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS)); // 返回完整执行结果。
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly(); // 异常时也确保进程结束。
            }
            return new CommandResult(-1, "", e.getMessage()); // 返回异常信息。
        }
    }

    private CompletableFuture<String> readAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder builder = new StringBuilder(); // 收集输出。
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append(System.lineSeparator()); // 保留换行，方便日志摘要。
                }
            } catch (Exception e) {
                builder.append("[读取命令输出失败] ").append(e.getMessage()); // 输出读取失败也返回可诊断文本。
            }
            return builder.toString(); // 返回输出字符串。
        });
    }

    private Duration operationTimeout() {
        int seconds = properties.getMaxOperationTimeoutSeconds() == null
                ? 120
                : Math.max(1, properties.getMaxOperationTimeoutSeconds()); // 超时时间至少 1 秒。
        return Duration.ofSeconds(seconds); // 转换为 Duration。
    }

    /**
     * 安全命令执行结果。
     *
     * <p>适用场景：承载 Git 命令的退出码、标准输出与错误输出。调用方负责对 stdout/stderr 做脱敏和截断后再写日志。</p>
     */
    @Getter
    public static class CommandResult {
        private final int exitCode; // Git 进程退出码。
        private final String stdout; // 标准输出。
        private final String stderr; // 错误输出。

        public CommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode; // 保存退出码。
            this.stdout = stdout == null ? "" : stdout; // stdout 兜底为空字符串。
            this.stderr = stderr == null ? "" : stderr; // stderr 兜底为空字符串。
        }

        public static CommandResult failed(String errorMsg) {
            return new CommandResult(-1, "", errorMsg); // 构造失败结果。
        }

        public boolean isSuccess() {
            return exitCode == 0; // exitCode 为 0 才视为成功。
        }
    }
}
