package com.agent.selfdev.maven;

import com.agent.config.MavenCompileProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionStatus;
import com.agent.entity.enums.DevTargetType;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import com.agent.selfdev.workspace.SandboxWorkspaceService;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P11 后端 Maven compile 编译验证主服务。
 *
 * <p>适用场景：P10 已经把 patch 应用到 P9 sandbox workspace 后，由本服务在该 workspace 内重新独立执行
 * Maven compile，捕获 stdout/stderr/exitCode/耗时，解析错误摘要，并把验证结果写入 dev_action_log。
 * 本服务只做编译验证，不修代码、不生成 patch、不应用 patch、不调用 Claude Code、不发布、不回滚。</p>
 *
 * <p>调用链：RunMavenCompileTool -> MavenCompileService.runCompile -> SandboxWorkspaceService/SandboxWorkspaceGuard
 * -> MavenCommandBuilder -> CompileCommandExecutor -> CompileOutputParser -> DevActionLogService。
 * 所有命令工作目录必须由 P9 workspaceId 解析得到，并通过 SandboxWorkspaceGuard 校验，严禁指向 sourceRepoDir
 * 或当前线上运行目录。</p>
 */
@Slf4j
@Service
public class MavenCompileService {

    private static final String CONFIRM_TOKEN = "RUN_COMPILE"; // P11 高风险命令执行确认标记。
    private static final String RESULT_TYPE = "maven_compile"; // 工具结果固定类型。
    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("(?i)[A-Z]:[\\\\/][^\\s,;\\]\\)'\"]+"); // 日志脱敏用绝对路径匹配。

    private final MavenCompileProperties properties; // P11 Maven 编译配置。
    private final SandboxWorkspaceService sandboxWorkspaceService; // P9 workspace 查询服务。
    private final SandboxWorkspaceGuard sandboxWorkspaceGuard; // P9 路径护栏。
    private final MavenCommandBuilder commandBuilder; // Maven 命令白名单构造器。
    private final CompileCommandExecutor commandExecutor; // Maven 命令执行器。
    private final CompileOutputParser outputParser; // Maven 输出解析器。
    private final DevActionLogService devActionLogService; // 开发行为日志服务。
    private final ObjectMapper objectMapper; // JSON 序列化工具。

    public MavenCompileService(MavenCompileProperties properties,
                               SandboxWorkspaceService sandboxWorkspaceService,
                               SandboxWorkspaceGuard sandboxWorkspaceGuard,
                               MavenCommandBuilder commandBuilder,
                               CompileCommandExecutor commandExecutor,
                               CompileOutputParser outputParser,
                               DevActionLogService devActionLogService,
                               ObjectMapper objectMapper) {
        this.properties = properties; // 注入 Maven 编译配置。
        this.sandboxWorkspaceService = sandboxWorkspaceService; // 注入 P9 workspace 服务。
        this.sandboxWorkspaceGuard = sandboxWorkspaceGuard; // 注入 P9 Guard。
        this.commandBuilder = commandBuilder; // 注入命令构造器。
        this.commandExecutor = commandExecutor; // 注入命令执行器。
        this.outputParser = outputParser; // 注入输出解析器。
        this.devActionLogService = devActionLogService; // 注入 dev_action_log 服务。
        this.objectMapper = objectMapper; // 注入 ObjectMapper。
    }

    public MavenCompileResult runCompile(MavenCompileRequest request) {
        MavenCompileRequest safeRequest = request == null ? new MavenCompileRequest() : request; // 兜底请求对象。
        String traceId = firstNonBlank(safeRequest.getTraceId(), UUID.randomUUID().toString()); // 兜底 traceId。
        MavenCompileResult result = new MavenCompileResult(); // 创建返回对象。
        result.setType(RESULT_TYPE); // 写入固定类型。
        result.setWorkspaceId(safeRequest.getWorkspaceId()); // 回填 workspaceId。
        result.setModule(safeRequest.getModule()); // 回填 module。
        Path workspace = null; // workspace 只在后端内部使用。
        try {
            validateConfirmation(safeRequest); // 校验 workspaceId 和 RUN_COMPILE 确认标记。
            result.setConfirmationPassed(true); // 确认标记已通过。
            workspace = resolveWorkspace(safeRequest); // 通过 workspaceId 解析并校验 P9 workspace。
            result.setRelativeWorkspacePath(sandboxWorkspaceGuard.relativeToSandbox(workspace)); // 只记录 sandbox 相对路径。
            validateMavenProject(workspace, safeRequest.getModule()); // 校验 pom.xml 和可选 module。
            MavenCommand command = commandBuilder.build(safeRequest); // 构造只允许 compile 的 Maven 命令。
            result.setCommandPreview(command.commandPreview()); // 回填安全命令预览。
            Duration timeout = resolveTimeout(safeRequest); // 解析超时时间。
            CompileCommandExecutor.CommandResult commandResult = commandExecutor.run(workspace, command.command(), timeout); // 在 sandbox 内执行 Maven。
            result.setSuccess(!commandResult.isTimeout() && commandResult.getExitCode() == 0); // exitCode=0 且未超时才算通过。
            outputParser.fillResult(result, commandResult, workspace, safeRequest.getModule()); // 解析 stdout/stderr。
            result.setMessage(buildMessage(result)); // 回填面向调用方的短消息。
            if (!result.isSuccess()) {
                result.setErrorMsg(firstNonBlank(result.getErrorSummary(), result.getMessage())); // 失败时保存错误摘要。
            }
            saveDevLog(safeRequest, traceId, result); // 成功/失败都写 dev_action_log。
            return result; // 返回编译验证结果。
        } catch (Exception e) {
            log.warn("[MavenCompile] runCompile failed: {}", e.getMessage(), e); // 服务端记录异常。
            result.setSuccess(false); // 标记失败。
            result.setMessage(redactSensitive(firstNonBlank(e.getMessage(), "Maven compile 验证失败。"))); // 返回脱敏消息。
            result.setErrorMsg(result.getMessage()); // 记录失败原因。
            result.setReport(buildFailureReport("VALIDATION_FAILED", result.getMessage(), safeRequest.getModule())); // 构造校验失败报告。
            if (workspace != null) {
                result.setRelativeWorkspacePath(sandboxWorkspaceGuard.relativeToSandbox(workspace)); // 有 workspace 时只记录相对路径。
            }
            saveDevLog(safeRequest, traceId, result); // 校验失败也写 dev_action_log。
            return result; // 返回失败结果。
        }
    }

    private void validateConfirmation(MavenCompileRequest request) {
        if (isBlank(request.getWorkspaceId())) {
            throw new IllegalArgumentException("workspaceId 不能为空。"); // P11 必须绑定 P9 workspace。
        }
        boolean requireConfirm = request.getRequireConfirm() == null || Boolean.TRUE.equals(request.getRequireConfirm()); // 默认要求确认。
        if (requireConfirm && !CONFIRM_TOKEN.equals(request.getConfirmToken())) {
            throw new SecurityException("该操作会在 sandbox workspace 中执行 Maven 编译命令，需要确认标记 RUN_COMPILE。"); // 缺确认拒绝。
        }
    }

    private Path resolveWorkspace(MavenCompileRequest request) {
        SandboxWorkspaceInfo info = sandboxWorkspaceService.getWorkspaceInfo(request.getWorkspaceId()); // 优先通过 P9 workspaceId 解析。
        Path workspace = Path.of(info.getWorkspacePath()).toAbsolutePath().normalize(); // 解析后端内部绝对路径。
        workspace = sandboxWorkspaceGuard.validateWorkspacePath(workspace); // 禁止 sandboxRoot 外、sourceRepoDir、线上运行目录。
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("workspace 不存在或不是目录。"); // workspace 必须已存在。
        }
        if (!isBlank(request.getWorkspacePath())) {
            Path requested = resolveOptionalWorkspacePath(request.getWorkspacePath()); // 解析可选 workspacePath。
            Path safeRequested = sandboxWorkspaceGuard.validateWorkspacePath(requested); // 可选路径也必须经过 Guard。
            if (!sameNormalized(workspace, safeRequested)) {
                throw new SecurityException("workspacePath 与 workspaceId 解析结果不一致。"); // 防止绕过 workspaceId。
            }
        }
        return workspace; // 返回安全 workspace。
    }

    private Path resolveOptionalWorkspacePath(String workspacePath) {
        Path raw = Path.of(workspacePath.trim()); // 解析原始路径。
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize(); // 绝对路径后续必须被 Guard 接受。
        }
        return sandboxWorkspaceGuard.toSafeWorkspacePath(workspacePath.trim()); // 相对路径只允许单层 workspaceName。
    }

    private void validateMavenProject(Path workspace, String module) {
        Path rootPom = workspace.resolve("pom.xml"); // 根 pom.xml。
        if (!Files.isRegularFile(rootPom)) {
            throw new IllegalArgumentException("workspace 根目录缺少 pom.xml，不能执行 Maven compile。"); // Maven 项目必备。
        }
        if (isBlank(module)) {
            return; // 未指定 module 时在根项目执行。
        }
        String safeModule = commandBuilder.validateModule(module); // module 名先走命令构造器安全校验。
        Path modulePom = workspace.resolve(safeModule).normalize().resolve("pom.xml"); // 首选 workspace/{module}/pom.xml。
        if (modulePom.startsWith(workspace) && Files.isRegularFile(modulePom)) {
            return; // 模块目录存在 pom.xml 则通过。
        }
        if (rootPomDeclaresModule(rootPom, safeModule)) {
            return; // 根 pom.xml 声明了该模块也通过。
        }
        throw new IllegalArgumentException("Maven module 不存在或未在根 pom.xml 中声明: " + safeModule); // module 不存在。
    }

    private boolean rootPomDeclaresModule(Path rootPom, String module) {
        try (InputStream inputStream = Files.newInputStream(rootPom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); // 创建 XML 解析器工厂。
            factory.setNamespaceAware(true); // Maven POM 默认命名空间需要开启。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // 禁止外部 DOCTYPE。
            Document document = factory.newDocumentBuilder().parse(inputStream); // 解析根 pom。
            NodeList modules = document.getElementsByTagNameNS("*", "module"); // 兼容默认 namespace。
            for (int i = 0; i < modules.getLength(); i++) {
                if (module.equals(modules.item(i).getTextContent().trim())) {
                    return true; // 命中 module 声明。
                }
            }
            return false; // 未命中。
        } catch (Exception e) {
            log.warn("[MavenCompile] parse root pom modules failed: {}", e.getMessage()); // 解析失败只影响 module 校验。
            return false; // 解析失败按未声明处理。
        }
    }

    private Duration resolveTimeout(MavenCompileRequest request) {
        int defaultSeconds = positiveOrDefault(properties.getDefaultTimeoutSeconds(), 180); // 默认超时。
        int maxSeconds = Math.max(defaultSeconds, positiveOrDefault(properties.getMaxTimeoutSeconds(), 600)); // 最大超时。
        int requested = request.getTimeoutSeconds() == null ? defaultSeconds : request.getTimeoutSeconds(); // 请求优先。
        if (requested <= 0) {
            requested = defaultSeconds; // 非正数回退默认值。
        }
        return Duration.ofSeconds(Math.min(requested, maxSeconds)); // 强制不超过最大值。
    }

    private String buildMessage(MavenCompileResult result) {
        if (result.isTimeout()) {
            return "Maven compile 执行超时，已中断进程。"; // 超时消息。
        }
        if (result.isSuccess()) {
            return "后端编译通过，可进入下一阶段。"; // 成功消息。
        }
        return "后端编译失败，请根据错误摘要修复后重试。"; // 失败消息。
    }

    private Map<String, Object> buildFailureReport(String status, String message, String module) {
        Map<String, Object> report = new LinkedHashMap<>(); // 保持 JSON 字段顺序。
        report.put("status", status); // 失败状态。
        report.put("message", message); // 失败说明。
        report.put("compiledModule", isBlank(module) ? "MULTI_MODULE" : module); // 编译模块。
        return report; // 返回报告。
    }

    private void saveDevLog(MavenCompileRequest request, String traceId, MavenCompileResult result) {
        try {
            DevActionLogCreateRequest logRequest = new DevActionLogCreateRequest(); // 创建 dev_action_log 请求。
            logRequest.setUserId(request.getUserId()); // 写入用户 ID。
            logRequest.setConversationId(request.getConversationId()); // 写入会话 ID。
            logRequest.setTraceId(traceId); // 写入 traceId。
            logRequest.setActionType("COMPILE_VERIFIED"); // P11 actionType。
            logRequest.setResult(result.isSuccess() ? DevActionResult.SUCCESS.name() : DevActionResult.FAILED.name()); // 行为结果。
            logRequest.setStatus(result.isSuccess() ? DevActionStatus.SUCCESS.name() : DevActionStatus.FAILED.name()); // 日志状态。
            logRequest.setTargetType(DevTargetType.BUILD.name()); // 编译验证维度。
            logRequest.setTargetModule(isBlank(request.getModule()) ? "MULTI_MODULE" : request.getModule().trim()); // 目标模块。
            logRequest.setTitle("Maven compile 编译验证"); // 日志标题。
            logRequest.setIntent("在 sandbox workspace 中执行 Maven compile，验证 Patch 应用后的后端代码是否可编译。"); // 行为意图。
            logRequest.setSummary(buildSummary(result)); // 语义摘要。
            logRequest.setResultJson(toResultJson(result)); // 只保存摘要 JSON，不保存完整超长日志。
            logRequest.setErrorMsg(result.isSuccess() ? null : safeText(redactSensitive(result.getErrorMsg()), 1000)); // 失败原因。
            DevActionLogSaveResult saveResult = devActionLogService.recordCompileVerified(logRequest); // 保存 COMPILE_VERIFIED。
            if (saveResult != null && saveResult.isSaved()) {
                result.setDevLogId(saveResult.getDevLogId()); // 回填 devLogId。
            }
        } catch (Exception e) {
            log.warn("[MavenCompile] save dev_action_log failed: {}", e.getMessage(), e); // 日志失败不影响主流程。
        }
    }

    private String buildSummary(MavenCompileResult result) {
        String module = isBlank(result.getModule()) ? "MULTI_MODULE" : result.getModule().trim(); // 展示模块。
        double seconds = result.getCostTimeMs() == null ? 0D : result.getCostTimeMs() / 1000.0D; // 耗时秒。
        if (result.isSuccess()) {
            return "Maven compile 验证通过，模块 " + module + " 编译成功，耗时 "
                    + String.format(java.util.Locale.ROOT, "%.1f", seconds) + " 秒，可进入下一阶段。"; // 成功摘要。
        }
        if (result.isTimeout()) {
            return "Maven compile 验证超时，模块 " + module + " 未在限定时间内完成，已中断进程。"; // 超时摘要。
        }
        return "Maven compile 验证失败，模块 " + module + " 存在编译错误，主要错误为 "
                + safeText(firstNonBlank(result.getErrorSummary(), result.getErrorMsg(), "unknown error"), 300); // 失败摘要。
    }

    private String toResultJson(MavenCompileResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(); // 构造日志 payload。
            payload.put("type", RESULT_TYPE); // 类型。
            payload.put("success", result.isSuccess()); // 成功状态。
            payload.put("timeout", result.isTimeout()); // 超时状态。
            payload.put("workspaceId", result.getWorkspaceId()); // workspaceId。
            payload.put("relativeWorkspacePath", result.getRelativeWorkspacePath()); // sandbox 相对路径。
            payload.put("module", result.getModule()); // 模块。
            payload.put("commandPreview", result.getCommandPreview()); // 安全命令预览。
            payload.put("exitCode", result.getExitCode()); // 退出码。
            payload.put("costTimeMs", result.getCostTimeMs()); // 耗时。
            payload.put("outputPreview", safeText(redactSensitive(result.getOutputPreview()), positiveOrDefault(properties.getMaxOutputChars(), 60000))); // 输出预览。
            payload.put("errorSummary", safeText(redactSensitive(result.getErrorSummary()), positiveOrDefault(properties.getMaxErrorSummaryChars(), 12000))); // 错误摘要。
            payload.put("errors", result.getErrors()); // 结构化错误。
            payload.put("report", result.getReport()); // 验证报告。
            payload.put("message", result.getMessage()); // 消息。
            return objectMapper.writeValueAsString(payload); // 序列化 JSON。
        } catch (Exception e) {
            return "{\"type\":\"maven_compile\",\"error\":\"failed to serialize compile result\"}"; // 序列化失败兜底。
        }
    }

    private boolean sameNormalized(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize()); // 比较规范化路径。
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value; // 正整数兜底。
    }

    private String redactSensitive(String text) {
        if (text == null) {
            return null; // 空值直接返回。
        }
        return WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(text).replaceAll("[absolute-path-redacted]"); // 脱敏绝对路径。
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null; // 空值保持空。
        }
        return text.length() <= maxLength ? text : text.substring(0, Math.max(0, maxLength)); // 截断超长文本。
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null; // 无候选。
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim(); // 返回第一个非空白值。
            }
        }
        return null; // 全部为空。
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty(); // 空白判断。
    }
}
