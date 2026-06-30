package com.agent.selfdev.maven;

import com.agent.config.MavenCompileProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P11 Maven compile 命令构造器。
 *
 * <p>适用场景：MavenCompileService 在执行编译前调用本类，把 module、skipTests、profiles 和 extraArgs
 * 转成 ProcessBuilder 参数数组。本类只允许构造 mvn compile 这一类命令，不允许 test/package/install/deploy/release
 * 等目标，也不接受 shell 拼接片段。</p>
 *
 * <p>调用链：RunMavenCompileTool -> MavenCompileService -> MavenCommandBuilder -> CompileCommandExecutor。
 * 本类只做命令白名单和参数清洗，不执行命令。</p>
 */
@Component
public class MavenCommandBuilder {

    private static final Pattern SAFE_MODULE_PATTERN = Pattern.compile("[A-Za-z0-9._-]+"); // 第一版只允许单层 Maven 模块名。
    private static final Pattern SAFE_PROFILE_PATTERN = Pattern.compile("[A-Za-z0-9._-]+"); // profile 只允许安全标识符。
    private static final List<String> BLOCKED_TOKENS = List.of(
            ";", "&&", "||", "|", ">", "<", "`", "$(", "powershell", "cmd", " sh ", "bash"); // shell/命令注入片段。
    private static final Set<String> BLOCKED_GOALS = Set.of(
            "deploy", "release", "install", "test", "package", "clean", "exec", "dependency:get"); // P11 禁止目标。

    private final MavenCompileProperties properties; // Maven 编译配置。

    public MavenCommandBuilder(MavenCompileProperties properties) {
        this.properties = properties; // 注入配置。
    }

    public MavenCommand build(MavenCompileRequest request) {
        List<String> command = new ArrayList<>(); // ProcessBuilder 参数数组，不经过 shell。
        command.add(validateExecutable(properties.getExecutable())); // Maven 可执行文件。
        appendExtraArgs(command, request.getExtraArgs()); // 安全额外参数。
        appendProfiles(command, request.getProfiles()); // 安全 profile。
        if (!isBlank(request.getModule())) {
            command.add("-pl"); // 指定 Maven 模块。
            command.add(validateModule(request.getModule())); // 模块名必须安全。
            command.add("-am"); // 同时构建依赖模块。
        }
        if (resolveSkipTests(request)) {
            command.add("-DskipTests"); // 只跳过测试，不允许执行 test。
        }
        command.add("compile"); // 固定目标，只允许 compile。
        validateFinalCommand(command); // 最终兜底校验。
        return new MavenCommand(List.copyOf(command), toPreview(command)); // 返回不可变命令和预览。
    }

    public String validateModule(String module) {
        if (isBlank(module)) {
            return null; // 未指定模块时允许根目录编译。
        }
        String safe = module.trim(); // 去掉首尾空白。
        rejectShellTokens(safe); // 禁止 shell 片段。
        if (!SAFE_MODULE_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException("Maven module 名称非法。"); // 禁止路径、空格和复杂坐标。
        }
        return safe; // 返回安全模块名。
    }

    private void appendProfiles(List<String> command, List<String> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return; // 未传 profile。
        }
        List<String> safeProfiles = new ArrayList<>(); // 去重后的 profile。
        for (String profile : profiles) {
            if (isBlank(profile)) {
                continue; // 空 profile 跳过。
            }
            String safe = profile.trim(); // 去掉空白。
            rejectShellTokens(safe); // 禁止 shell 片段。
            if (!SAFE_PROFILE_PATTERN.matcher(safe).matches()) {
                throw new IllegalArgumentException("Maven profile 名称非法: " + profile); // 非法 profile 拒绝。
            }
            if (!safeProfiles.contains(safe)) {
                safeProfiles.add(safe); // 去重保序。
            }
        }
        if (!safeProfiles.isEmpty()) {
            command.add("-P" + String.join(",", safeProfiles)); // Maven profile 安全拼接。
        }
    }

    private void appendExtraArgs(List<String> command, List<String> extraArgs) {
        if (extraArgs == null || extraArgs.isEmpty()) {
            return; // 未传额外参数。
        }
        Set<String> allowed = new LinkedHashSet<>(properties.getAllowedExtraArgs() == null
                ? List.of()
                : properties.getAllowedExtraArgs()); // 配置白名单。
        for (String arg : extraArgs) {
            if (isBlank(arg)) {
                continue; // 空参数跳过。
            }
            String safe = arg.trim(); // 去掉空白。
            rejectShellTokens(safe); // 禁止 shell 片段。
            if (!allowed.contains(safe)) {
                throw new IllegalArgumentException("Maven extraArgs 不在白名单内: " + safe); // 非白名单拒绝。
            }
            command.add(safe); // 加入安全参数。
        }
    }

    private boolean resolveSkipTests(MavenCompileRequest request) {
        if (request.getSkipTests() != null) {
            return request.getSkipTests(); // 请求优先。
        }
        return !Boolean.FALSE.equals(properties.getDefaultSkipTests()); // 默认 true。
    }

    private String validateExecutable(String executable) {
        String value = isBlank(executable) ? "mvn" : executable.trim(); // 默认 mvn。
        rejectShellTokens(value); // 可执行文件也不能含 shell 片段。
        return value; // 返回可执行文件。
    }

    private void validateFinalCommand(List<String> command) {
        for (String part : command) {
            rejectShellTokens(part); // 每个参数都做 shell 片段兜底。
            if (BLOCKED_GOALS.contains(part)) {
                throw new IllegalArgumentException("P11 只允许执行 Maven compile。"); // 禁止危险 goal。
            }
        }
        long compileCount = command.stream().filter("compile"::equals).count(); // compile 必须且只能出现一次。
        if (compileCount != 1 || !"compile".equals(command.get(command.size() - 1))) {
            throw new IllegalArgumentException("P11 Maven 命令必须以 compile 作为唯一 goal。"); // 防止混入其他 goal。
        }
    }

    private void rejectShellTokens(String value) {
        String lower = value == null ? "" : value.toLowerCase(); // 小写比较。
        for (String token : BLOCKED_TOKENS) {
            if (lower.contains(token)) {
                throw new IllegalArgumentException("Maven 参数包含不允许的 shell 片段。"); // 拒绝 shell 注入。
            }
        }
    }

    private String toPreview(List<String> command) {
        return String.join(" ", command); // 预览仅由白名单参数组成。
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty(); // 空白判断。
    }
}
