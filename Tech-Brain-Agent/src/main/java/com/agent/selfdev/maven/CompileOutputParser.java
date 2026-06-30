package com.agent.selfdev.maven;

import com.agent.config.MavenCompileProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P11 Maven 输出解析器。
 *
 * <p>适用场景：Maven compile 执行完成后，MavenCompileService 调用本类从 stdout/stderr 中提取 BUILD SUCCESS /
 * BUILD FAILURE、关键 [ERROR] 行、文件路径、行号和错误摘要。解析结果只做展示和日志摘要，不修复代码。</p>
 *
 * <p>调用链：MavenCompileService -> CompileOutputParser.parse -> MavenCompileResult。
 * 本类不执行命令，不读取或写入源码文件。</p>
 */
@Component
public class CompileOutputParser {

    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("(?i)[A-Z]:[\\\\/][^\\s,;\\]\\)'\"]+"); // Windows 绝对路径。
    private static final Pattern JAVA_ERROR_PATTERN = Pattern.compile("(.+?\\.java):\\[(\\d+)(?:,\\d+)?]\\s*(.*)"); // Maven Java 错误格式。
    private final MavenCompileProperties properties; // 输出截断配置。

    public CompileOutputParser(MavenCompileProperties properties) {
        this.properties = properties; // 注入配置。
    }

    public void fillResult(MavenCompileResult result,
                           CompileCommandExecutor.CommandResult commandResult,
                           Path workspace,
                           String module) {
        String stdout = commandResult.getStdout(); // 读取 stdout。
        String stderr = commandResult.getStderr(); // 读取 stderr。
        String combined = sanitizeOutput((stdout == null ? "" : stdout) + System.lineSeparator()
                + (stderr == null ? "" : stderr), workspace); // 合并并脱敏。
        result.setExitCode(commandResult.getExitCode()); // 回填退出码。
        result.setCostTimeMs(commandResult.getCostTimeMs()); // 回填耗时。
        result.setTimeout(commandResult.isTimeout()); // 回填超时。
        result.setOutputPreview(limitText(combined, valueOrDefault(properties.getMaxOutputChars(), 60000))); // 输出预览。
        result.setErrors(extractErrors(combined)); // 关键错误列表。
        result.setErrorSummary(buildErrorSummary(combined, commandResult)); // 错误摘要。
        result.setReport(buildReport(result, module, combined)); // 编译报告。
    }

    private Map<String, Object> buildReport(MavenCompileResult result, String module, String output) {
        Map<String, Object> report = new LinkedHashMap<>(); // 保持 JSON 字段顺序。
        if (result.isTimeout()) {
            report.put("status", "TIMEOUT"); // 超时状态。
            report.put("message", "Maven compile 执行超时，已中断进程。"); // 超时说明。
        } else if (result.isSuccess()) {
            report.put("status", "BUILD_SUCCESS"); // 编译成功状态。
            report.put("message", "后端编译通过，可进入下一阶段。"); // 成功说明。
        } else {
            report.put("status", output.contains("BUILD FAILURE") ? "BUILD_FAILURE" : "COMPILE_FAILED"); // 失败状态。
            report.put("message", "后端编译失败，请根据错误摘要修复后重试。"); // 失败说明。
        }
        report.put("compiledModule", module == null || module.isBlank() ? "MULTI_MODULE" : module); // 编译模块。
        report.put("mavenBuildSuccess", output.contains("BUILD SUCCESS")); // Maven 成功标记。
        report.put("mavenBuildFailure", output.contains("BUILD FAILURE")); // Maven 失败标记。
        return report; // 返回报告。
    }

    private List<CompileError> extractErrors(String output) {
        List<CompileError> errors = new ArrayList<>(); // 错误列表。
        for (String line : output.split("\\R")) {
            String trimmed = stripErrorPrefix(line); // 去掉 [ERROR] 前缀。
            if (!isErrorLine(line, trimmed)) {
                continue; // 非关键错误行跳过。
            }
            CompileError error = parseErrorLine(trimmed); // 尝试解析文件和行号。
            if (error != null) {
                errors.add(error); // 加入错误列表。
            }
            if (errors.size() >= 50) {
                break; // 防止错误列表过长。
            }
        }
        return errors; // 返回错误列表。
    }

    private CompileError parseErrorLine(String line) {
        Matcher matcher = JAVA_ERROR_PATTERN.matcher(line); // 匹配 Java 编译错误。
        if (matcher.find()) {
            String file = sanitizePath(matcher.group(1)); // 文件路径。
            Integer lineNo = parseInt(matcher.group(2)); // 行号。
            String message = matcher.group(3) == null || matcher.group(3).isBlank()
                    ? line
                    : matcher.group(3).trim(); // 错误消息。
            return new CompileError(file, lineNo, limitText(message, 1000)); // 返回结构化错误。
        }
        return new CompileError(null, null, limitText(line, 1000)); // 兜底保存错误行。
    }

    private String buildErrorSummary(String output, CompileCommandExecutor.CommandResult commandResult) {
        if (commandResult.isTimeout()) {
            return "Maven compile 执行超时，已中断进程。"; // 超时摘要。
        }
        if (commandResult.getExitCode() == 0) {
            return ""; // 成功没有错误摘要。
        }
        StringBuilder summary = new StringBuilder(); // 收集关键错误行。
        for (String line : output.split("\\R")) {
            String trimmed = line == null ? "" : line.trim(); // 去掉空白。
            String lower = trimmed.toLowerCase(); // 小写匹配。
            if (trimmed.contains("[ERROR]") || lower.contains("build failure")
                    || lower.contains("compilation failure") || lower.contains("cannot find symbol")
                    || lower.contains("package ") && lower.contains(" does not exist")
                    || lower.contains("method ") && lower.contains("cannot be applied")
                    || lower.contains("incompatible types")) {
                summary.append(trimmed).append(System.lineSeparator()); // 加入摘要。
            }
            if (summary.length() > valueOrDefault(properties.getMaxErrorSummaryChars(), 12000)) {
                break; // 达到限制后停止。
            }
        }
        if (summary.isEmpty()) {
            summary.append(limitText(output, valueOrDefault(properties.getMaxErrorSummaryChars(), 12000))); // 兜底截取输出。
        }
        return limitText(summary.toString().trim(), valueOrDefault(properties.getMaxErrorSummaryChars(), 12000)); // 截断摘要。
    }

    private boolean isErrorLine(String rawLine, String strippedLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return false; // 空行跳过。
        }
        String lower = rawLine.toLowerCase(); // 小写匹配。
        return rawLine.contains("[ERROR]") || lower.contains("cannot find symbol")
                || lower.contains("does not exist") || lower.contains("incompatible types")
                || strippedLine.contains(".java:["); // 常见 Java 编译错误。
    }

    private String stripErrorPrefix(String line) {
        String value = line == null ? "" : line.trim(); // 去掉首尾空白。
        while (value.startsWith("[ERROR]")) {
            value = value.substring("[ERROR]".length()).trim(); // 去掉 Maven error 前缀。
        }
        return value; // 返回清理后的行。
    }

    private String sanitizeOutput(String text, Path workspace) {
        String result = text == null ? "" : text; // 空输出兜底。
        if (workspace != null) {
            String windowsPath = workspace.toAbsolutePath().normalize().toString(); // Windows 风格 workspace。
            String unixPath = windowsPath.replace('\\', '/'); // / 风格 workspace。
            result = result.replace(windowsPath, ""); // 去掉 workspace 绝对路径。
            result = result.replace(unixPath, ""); // 去掉 / 风格 workspace 绝对路径。
        }
        return WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(result).replaceAll("[absolute-path-redacted]"); // 兜底脱敏绝对路径。
    }

    private String sanitizePath(String path) {
        if (path == null) {
            return null; // 空路径。
        }
        String normalized = path.replace('\\', '/'); // 统一分隔符。
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1); // 去掉因 workspace 脱敏留下的开头斜杠。
        }
        return normalized; // 返回相对路径。
    }

    private Integer parseInt(String text) {
        try {
            return Integer.parseInt(text); // 解析整数。
        } catch (Exception e) {
            return null; // 解析失败返回空。
        }
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text; // 未超长直接返回。
        }
        return text.substring(0, Math.max(0, maxLength)) + "\n[truncated]"; // 超长截断。
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value; // 正整数配置兜底。
    }
}
