package com.agent.selfdev.patch;

import com.agent.config.ApplyPatchProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionType;
import com.agent.entity.enums.DevTargetType;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import com.agent.selfdev.workspace.SandboxWorkspaceService;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P10 应用 patch 主流程服务。
 *
 * <p>适用场景：在 P9 sandbox workspace 中受控应用 P8 产出的 patch。负责确认 token、patch 大小限制、
 * changedFiles 解析、白名单/黑名单校验、应用前备份、git apply、失败自动回滚和 dev_action_log 记录。</p>
 *
 * <p>调用链：ApplyPatchTool -> PatchApplyService.applyPatch -> SandboxWorkspaceService/SandboxWorkspaceGuard
 * -> PatchParser -> PatchSafetyGuard -> PatchBackupService -> PatchCommandExecutor -> DevActionLogService。
 * 本类不执行编译、不发布、不回滚版本、不调用 Claude Code。</p>
 */
@Slf4j
@Service
public class PatchApplyService {

    private static final String CONFIRM_TOKEN = "APPLY_PATCH"; // 高风险写操作确认标记。
    private static final String RESULT_TYPE = "patch_apply"; // 工具结果类型。
    private static final int OUTPUT_LIMIT = 4000; // 命令输出写日志前截断长度。
    private static final DateTimeFormatter PATCH_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); // 临时 patch 文件时间戳。
    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("(?i)[A-Z]:[\\\\/][^\\s,;\\]\\)'\"]+"); // Windows 绝对路径脱敏。
    private static final Pattern URL_CREDENTIAL_PATTERN = Pattern.compile("(?i)(https?://)([^/@\\s]+)@"); // URL 凭据脱敏。
    private static final Pattern URL_TOKEN_QUERY_PATTERN = Pattern.compile("(?i)(token|access_token|password)=([^&\\s]+)"); // URL token 脱敏。
    private static final Pattern COMMON_SECRET_PATTERN = Pattern.compile("(?i)(ghp|glpat|claude|sk|ak)[A-Za-z0-9_\\-]{12,}"); // 常见 token 脱敏。

    private final ApplyPatchProperties properties; // P10 patch 配置。
    private final SandboxWorkspaceService sandboxWorkspaceService; // P9 workspace 查询服务。
    private final SandboxWorkspaceGuard sandboxWorkspaceGuard; // P9 workspace 路径守卫。
    private final PatchParser patchParser; // patch 文件清单解析器。
    private final PatchSafetyGuard patchSafetyGuard; // patch 安全守卫。
    private final PatchBackupService patchBackupService; // 备份/回滚服务。
    private final PatchCommandExecutor patchCommandExecutor; // git apply 命令执行器。
    private final DevActionLogService devActionLogService; // 开发行为日志服务。
    private final ObjectMapper objectMapper; // JSON 序列化工具。

    public PatchApplyService(ApplyPatchProperties properties,
                             SandboxWorkspaceService sandboxWorkspaceService,
                             SandboxWorkspaceGuard sandboxWorkspaceGuard,
                             PatchParser patchParser,
                             PatchSafetyGuard patchSafetyGuard,
                             PatchBackupService patchBackupService,
                             PatchCommandExecutor patchCommandExecutor,
                             DevActionLogService devActionLogService,
                             ObjectMapper objectMapper) {
        this.properties = properties; // 注入配置。
        this.sandboxWorkspaceService = sandboxWorkspaceService; // 注入 P9 workspace 服务。
        this.sandboxWorkspaceGuard = sandboxWorkspaceGuard; // 注入 P9 Guard。
        this.patchParser = patchParser; // 注入解析器。
        this.patchSafetyGuard = patchSafetyGuard; // 注入安全守卫。
        this.patchBackupService = patchBackupService; // 注入备份服务。
        this.patchCommandExecutor = patchCommandExecutor; // 注入命令执行器。
        this.devActionLogService = devActionLogService; // 注入 dev_action_log 服务。
        this.objectMapper = objectMapper; // 注入 ObjectMapper。
    }

    public ApplyPatchResult applyPatch(ApplyPatchRequest request) {
        ApplyPatchRequest safeRequest = request == null ? new ApplyPatchRequest() : request; // 兜底请求对象。
        String traceId = firstNonBlank(safeRequest.getTraceId(), UUID.randomUUID().toString()); // 兜底 traceId。
        ApplyPatchResult result = new ApplyPatchResult(); // 创建结果对象。
        result.setType(RESULT_TYPE); // 固定类型。
        result.setWorkspaceId(safeRequest.getWorkspaceId()); // 先回填 workspaceId。
        result.setDryRun(isTrue(safeRequest.getDryRun())); // 回填 dryRun。
        PatchBackupResult backupResult = null; // 保存备份结果，失败时用于回滚。
        Path workspace = null; // workspace 路径只在后端内部使用。
        Path tempPatchFile = null; // patchContent 写出的临时文件。
        try {
            validateConfirmation(safeRequest); // 校验确认 token 和高风险开关。
            workspace = resolveWorkspace(safeRequest); // 通过 workspaceId 解析并校验 workspace。
            PatchText patchText = resolvePatchText(safeRequest, workspace); // 读取 patchContent 或 patchFilePath。
            validatePatchSize(patchText.content()); // 校验 patch 大小。
            PatchParseResult parseResult = patchParser.parse(patchText.content()); // 解析 changedFiles。
            if (!parseResult.isSuccess()) {
                return failAndLog(result, safeRequest, traceId, null, parseResult, null,
                        parseResult.getMessage(), false, false, DevActionResult.FAILED); // 解析失败。
            }
            fillChangedFiles(result, parseResult); // 回填文件列表。
            validateChangedFileLimit(safeRequest, parseResult); // 校验变更文件数量。
            List<PatchRejectedFile> rejectedFiles = patchSafetyGuard.validateChanges(
                    workspace, parseResult, safeRequest.getAllowedDirectories()); // 执行白名单/黑名单校验。
            if (!rejectedFiles.isEmpty()) {
                result.setRejectedFiles(rejectedFiles); // 回填拒绝文件。
                result.setSafetyPassed(false); // 标记安全校验失败。
                return failAndLog(result, safeRequest, traceId, workspace, parseResult, null,
                        "Patch 包含不允许修改的文件，已拒绝应用。", false, false, DevActionResult.FAILED); // 安全拒绝。
            }
            result.setSafetyPassed(true); // 安全校验通过。
            if (result.isDryRun()) {
                result.setSuccess(true); // dryRun 成功。
                result.setMessage("Patch 校验通过，未实际应用。"); // dryRun 说明。
                saveDevLog(safeRequest, traceId, result, parseResult, null, null, DevActionResult.SUCCESS); // 记录 dryRun。
                return result; // 不写文件、不备份、不执行 git apply。
            }
            requireBackupAndRollbackEnabled(safeRequest); // 真实应用时不允许关闭备份和失败回滚。
            backupResult = patchBackupService.createBackup(safeRequest.getWorkspaceId(), workspace, parseResult); // 应用前备份。
            if (!backupResult.isSuccess()) {
                return failAndLog(result, safeRequest, traceId, workspace, parseResult, backupResult,
                        "Patch 应用前备份失败: " + backupResult.getErrorMsg(), false, false, DevActionResult.FAILED); // 备份失败。
            }
            result.setBackupId(backupResult.getBackupId()); // 回填备份 ID。
            Path patchFile = patchText.patchFile(); // 读取 patch 文件路径。
            if (patchFile == null) {
                tempPatchFile = writeTempPatchFile(workspace, patchText.content()); // patchContent 写入临时文件。
                patchFile = tempPatchFile; // 后续 git apply 使用临时文件。
            }
            PatchCommandExecutor.CommandResult checkResult = patchCommandExecutor.check(workspace, patchFile); // 先执行 git apply --check。
            if (!checkResult.isSuccess()) {
                return rollbackAndFail(result, safeRequest, traceId, workspace, parseResult, backupResult,
                        "git apply --check 失败: " + firstNonBlank(checkResult.getStderr(), checkResult.getStdout())); // check 失败。
            }
            PatchCommandExecutor.CommandResult applyResult = patchCommandExecutor.apply(workspace, patchFile); // 再执行 git apply。
            if (!applyResult.isSuccess()) {
                return rollbackAndFail(result, safeRequest, traceId, workspace, parseResult, backupResult,
                        "git apply 失败: " + firstNonBlank(applyResult.getStderr(), applyResult.getStdout())); // apply 失败。
            }
            result.setSuccess(true); // 标记应用成功。
            result.setMessage("Patch 已成功应用，可进入 P11 编译验证。"); // 成功后只提示进入 P11。
            saveDevLog(safeRequest, traceId, result, parseResult, backupResult,
                    Map.of("stdoutSummary", summarizeOutput(checkResult.getStdout() + applyResult.getStdout()),
                            "stderrSummary", summarizeOutput(checkResult.getStderr() + applyResult.getStderr())),
                    DevActionResult.SUCCESS); // 记录成功日志。
            return result; // 返回成功。
        } catch (Exception e) {
            log.warn("[PatchApply] applyPatch failed: {}", e.getMessage(), e); // 服务端记录异常。
            if (backupResult != null && backupResult.isSuccess() && !result.isDryRun()) {
                PatchRollbackResult rollback = patchBackupService.rollback(workspace, backupResult); // 有备份时尝试回滚。
                result.setRollbackExecuted(rollback.isExecuted()); // 回填回滚状态。
                result.setRollbackSuccess(rollback.isSuccess()); // 回填回滚结果。
                String error = "Patch 应用失败，" + (rollback.isSuccess() ? "已回滚。" : "回滚失败。")
                        + " 原因: " + firstNonBlank(e.getMessage(), rollback.getErrorMsg()); // 构造失败说明。
                return failAndLog(result, safeRequest, traceId, workspace, null, backupResult, error,
                        rollback.isExecuted(), rollback.isSuccess(),
                        rollback.isSuccess() ? DevActionResult.FAILED : DevActionResult.PARTIAL); // 记录失败。
            }
            return failAndLog(result, safeRequest, traceId, workspace, null, backupResult,
                    firstNonBlank(e.getMessage(), "Patch 应用失败。"), false, false, DevActionResult.FAILED); // 无备份失败。
        } finally {
            deleteTempPatchFile(tempPatchFile); // 清理 patchContent 临时文件，避免污染 workspace。
        }
    }

    private void validateConfirmation(ApplyPatchRequest request) {
        if (request.getWorkspaceId() == null || request.getWorkspaceId().trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId 不能为空。"); // workspaceId 必填。
        }
        boolean requireConfirm = request.getRequireConfirm() == null || Boolean.TRUE.equals(request.getRequireConfirm()); // 默认需要确认。
        if (requireConfirm && !CONFIRM_TOKEN.equals(request.getConfirmToken())) {
            throw new SecurityException("该操作会修改 sandbox workspace 文件，需要确认标记 APPLY_PATCH。"); // 缺确认拒绝。
        }
    }

    private void requireBackupAndRollbackEnabled(ApplyPatchRequest request) {
        if (Boolean.FALSE.equals(request.getBackupEnabled())) {
            throw new SecurityException("P10 第一版不允许关闭 backupEnabled。"); // 不允许关闭备份。
        }
        if (Boolean.FALSE.equals(request.getRollbackOnFailure())) {
            throw new SecurityException("P10 第一版不允许关闭 rollbackOnFailure。"); // 不允许关闭失败回滚。
        }
    }

    private Path resolveWorkspace(ApplyPatchRequest request) {
        SandboxWorkspaceInfo info = sandboxWorkspaceService.getWorkspaceInfo(request.getWorkspaceId()); // 优先通过 P9 workspaceId 解析。
        Path workspace = Path.of(info.getWorkspacePath()).toAbsolutePath().normalize(); // 获取后端内部绝对路径。
        sandboxWorkspaceGuard.validateWorkspacePath(workspace); // 禁止 sourceRepoDir / 线上运行目录。
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("workspace 不存在或不是目录。"); // 必须存在。
        }
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            throw new IllegalArgumentException("workspace 必须是 Git 仓库。"); // git apply 需要在仓库内执行。
        }
        return workspace; // 返回安全 workspace。
    }

    private PatchText resolvePatchText(ApplyPatchRequest request, Path workspace) throws IOException {
        if (request.getPatchContent() != null && !request.getPatchContent().isBlank()) {
            return new PatchText(request.getPatchContent(), null); // patchContent 优先。
        }
        if (request.getPatchFilePath() != null && !request.getPatchFilePath().isBlank()) {
            Path patchFile = patchSafetyGuard.resolveSafePatchFile(workspace, request.getPatchFilePath()); // patch 文件必须在 workspace 内。
            String content = Files.readString(patchFile, StandardCharsets.UTF_8); // 读取 patch 文本。
            return new PatchText(content, patchFile); // 返回文件内容和路径。
        }
        throw new IllegalArgumentException("patchContent 和 patchFilePath 至少需要提供一个。"); // 两者都缺失拒绝。
    }

    private void validatePatchSize(String patchContent) {
        int maxKb = properties.getMaxPatchSizeKb() == null ? 1024 : Math.max(1, properties.getMaxPatchSizeKb()); // 读取大小限制。
        int bytes = patchContent.getBytes(StandardCharsets.UTF_8).length; // 计算 UTF-8 字节数。
        if (bytes > maxKb * 1024L) {
            throw new IllegalArgumentException("patch 大小超过限制: " + maxKb + "KB"); // 超限拒绝。
        }
    }

    private void validateChangedFileLimit(ApplyPatchRequest request, PatchParseResult parseResult) {
        int max = request.getMaxChangedFiles() == null || request.getMaxChangedFiles() <= 0
                ? valueOrDefault(properties.getMaxChangedFiles(), 50)
                : request.getMaxChangedFiles(); // 请求值优先。
        if (parseResult.getAllChangedFiles().size() > max) {
            throw new IllegalArgumentException("patch 变更文件数超过限制: " + max); // 超限拒绝。
        }
    }

    private Path writeTempPatchFile(Path workspace, String patchContent) throws IOException {
        Path patchDir = workspace.resolve(".techbrain-patches").normalize(); // 临时 patch 文件目录。
        if (!patchDir.startsWith(workspace)) {
            throw new IOException("临时 patch 目录逃逸 workspace。"); // 防穿越。
        }
        Files.createDirectories(patchDir); // 创建临时 patch 目录。
        String name = "patch-" + LocalDateTime.now().format(PATCH_TIME_FORMATTER)
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".diff"; // 生成临时文件名。
        Path patchFile = patchDir.resolve(name).normalize(); // 拼出临时文件路径。
        Files.writeString(patchFile, patchContent, StandardCharsets.UTF_8); // 写入 patchContent。
        return patchFile; // 返回临时文件。
    }

    private ApplyPatchResult rollbackAndFail(ApplyPatchResult result,
                                             ApplyPatchRequest request,
                                             String traceId,
                                             Path workspace,
                                             PatchParseResult parseResult,
                                             PatchBackupResult backupResult,
                                             String errorMsg) {
        PatchRollbackResult rollback = patchBackupService.rollback(workspace, backupResult); // 执行自动回滚。
        result.setRollbackExecuted(rollback.isExecuted()); // 回填是否回滚。
        result.setRollbackSuccess(rollback.isSuccess()); // 回填回滚是否成功。
        String message = rollback.isSuccess()
                ? "Patch 应用失败，已回滚。"
                : "Patch 应用失败，自动回滚也失败。"; // 构造消息。
        return failAndLog(result, request, traceId, workspace, parseResult, backupResult,
                message + " 原因: " + errorMsg, rollback.isExecuted(), rollback.isSuccess(),
                rollback.isSuccess() ? DevActionResult.FAILED : DevActionResult.PARTIAL); // 失败记录日志。
    }

    private ApplyPatchResult failAndLog(ApplyPatchResult result,
                                        ApplyPatchRequest request,
                                        String traceId,
                                        Path workspace,
                                        PatchParseResult parseResult,
                                        PatchBackupResult backupResult,
                                        String errorMsg,
                                        boolean rollbackExecuted,
                                        boolean rollbackSuccess,
                                        DevActionResult actionResult) {
        result.setSuccess(false); // 标记失败。
        String safeErrorMsg = redactSensitive(errorMsg); // 失败信息返回前先脱敏，避免把服务器绝对路径带到前端或日志。
        result.setMessage(safeErrorMsg); // 写入脱敏后的失败消息。
        result.setErrorMsg(safeErrorMsg); // 写入脱敏失败原因。
        result.setRollbackExecuted(rollbackExecuted); // 写入回滚执行状态。
        result.setRollbackSuccess(rollbackSuccess); // 写入回滚成功状态。
        if (backupResult != null && backupResult.getBackupId() != null) {
            result.setBackupId(backupResult.getBackupId()); // 回填 backupId。
        }
        saveDevLog(request, traceId, result, parseResult, backupResult, null, actionResult); // 记录 dev_action_log。
        return result; // 返回失败结果。
    }

    private void fillChangedFiles(ApplyPatchResult result, PatchParseResult parseResult) {
        result.getChangedFiles().addAll(parseResult.getAllChangedFiles()); // 回填全量文件。
        result.getAddedFiles().addAll(parseResult.getAddedFiles()); // 回填新增。
        result.getModifiedFiles().addAll(parseResult.getModifiedFiles()); // 回填修改。
        result.getDeletedFiles().addAll(parseResult.getDeletedFiles()); // 回填删除。
        result.getRenamedFiles().addAll(parseResult.getRenamedFiles()); // 回填重命名。
    }

    private void saveDevLog(ApplyPatchRequest request,
                            String traceId,
                            ApplyPatchResult result,
                            PatchParseResult parseResult,
                            PatchBackupResult backupResult,
                            Map<String, Object> extra,
                            DevActionResult actionResult) {
        try {
            DevActionLogCreateRequest logRequest = new DevActionLogCreateRequest(); // 创建开发日志请求。
            logRequest.setUserId(request.getUserId()); // 写用户 ID。
            logRequest.setConversationId(request.getConversationId()); // 写会话 ID。
            logRequest.setTraceId(traceId); // 写 traceId。
            logRequest.setActionType(DevActionType.PATCH_APPLIED.name()); // P10 actionType。
            logRequest.setResult(actionResult.name()); // 写结果。
            logRequest.setTargetType(DevTargetType.FILE.name()); // patch 最终作用到文件。
            logRequest.setTargetModule(resolveTargetModule(result.getChangedFiles())); // 写目标模块。
            logRequest.setTargetFile(result.getChangedFiles().size() == 1 ? result.getChangedFiles().get(0) : null); // 单文件时写 targetFile。
            logRequest.setTitle(result.isDryRun() ? "Patch dryRun 校验" : "应用 patch 到 sandbox workspace"); // 标题。
            logRequest.setIntent("在受控 sandbox workspace 中应用 Claude Code 生成的 patch，并确保变更文件符合白名单和安全限制。"); // 意图。
            logRequest.setSummary(buildSummary(result)); // 语义摘要。
            logRequest.setResultJson(toResultJson(result, parseResult, backupResult, extra)); // 只写摘要 JSON。
            logRequest.setErrorMsg(result.isSuccess() ? null : safeText(redactSensitive(result.getErrorMsg()), 1000)); // 失败原因。
            DevActionLogSaveResult saveResult = devActionLogService.saveDevAction(logRequest); // 保存 dev_action_log。
            if (saveResult != null && saveResult.isSaved()) {
                result.setDevLogId(saveResult.getDevLogId()); // 回填日志 ID。
            }
        } catch (Exception e) {
            log.warn("[PatchApply] save dev_action_log failed: {}", e.getMessage(), e); // 日志失败不影响主流程。
        }
    }

    private String toResultJson(ApplyPatchResult result,
                                PatchParseResult parseResult,
                                PatchBackupResult backupResult,
                                Map<String, Object> extra) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(); // 构造日志 payload。
            payload.put("type", RESULT_TYPE); // 写类型。
            payload.put("success", result.isSuccess()); // 写成功状态。
            payload.put("workspaceId", result.getWorkspaceId()); // 写 workspaceId。
            payload.put("dryRun", result.isDryRun()); // 写 dryRun。
            payload.put("safetyPassed", result.isSafetyPassed()); // 写安全状态。
            payload.put("backupId", result.getBackupId()); // 写 backupId。
            payload.put("rollbackExecuted", result.isRollbackExecuted()); // 写是否回滚。
            payload.put("rollbackSuccess", result.isRollbackSuccess()); // 写回滚结果。
            payload.put("changedFiles", result.getChangedFiles()); // 写文件列表。
            payload.put("addedFiles", result.getAddedFiles()); // 写新增列表。
            payload.put("modifiedFiles", result.getModifiedFiles()); // 写修改列表。
            payload.put("deletedFiles", result.getDeletedFiles()); // 写删除列表。
            payload.put("renamedFiles", result.getRenamedFiles()); // 写重命名列表。
            payload.put("rejectedFiles", result.getRejectedFiles()); // 写拒绝列表。
            payload.put("message", result.getMessage()); // 写消息。
            payload.put("errorMsg", redactSensitive(result.getErrorMsg())); // 写脱敏错误。
            if (backupResult != null && backupResult.getManifest() != null) {
                payload.put("backedUpFiles", backupResult.getManifest().getBackedUpFiles()); // 写已备份相对路径。
                payload.put("createdFiles", backupResult.getManifest().getCreatedFiles()); // 写新增相对路径。
            }
            if (parseResult != null) {
                payload.put("changedFileCount", parseResult.getAllChangedFiles().size()); // 写变更数量。
            }
            if (extra != null) {
                payload.putAll(extra); // 合并命令摘要。
            }
            return objectMapper.writeValueAsString(payload); // 序列化 JSON。
        } catch (Exception e) {
            return "{\"type\":\"patch_apply\",\"error\":\"failed to serialize patch apply result\"}"; // 序列化失败兜底。
        }
    }

    private String buildSummary(ApplyPatchResult result) {
        if (result.isSuccess() && result.isDryRun()) {
            return "Patch 校验通过，涉及 " + result.getChangedFiles().size() + " 个文件，未实际应用。"; // dryRun 摘要。
        }
        if (result.isSuccess()) {
            return "Patch 已应用到 workspace " + result.getWorkspaceId() + "，共修改 "
                    + result.getChangedFiles().size() + " 个文件，备份 ID 为 "
                    + firstNonBlank(result.getBackupId(), "-") + "，可进入 P11 编译验证。"; // 成功摘要。
        }
        if (result.isRollbackExecuted()) {
            return "Patch 应用失败，涉及 " + result.getChangedFiles().size() + " 个文件，已执行自动回滚，失败原因："
                    + safeText(redactSensitive(result.getErrorMsg()), 300); // 失败回滚摘要。
        }
        return "Patch 应用被拒绝或失败，原因：" + safeText(redactSensitive(result.getErrorMsg()), 300); // 安全拒绝摘要。
    }

    private String resolveTargetModule(List<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return "UNKNOWN"; // 无文件时兜底。
        }
        String module = firstSegment(changedFiles.get(0)); // 取第一个模块。
        for (String file : changedFiles) {
            if (!module.equals(firstSegment(file))) {
                return "MULTI_MODULE"; // 多模块。
            }
        }
        return module; // 单模块。
    }

    private String firstSegment(String path) {
        if (path == null || path.isBlank()) {
            return "UNKNOWN"; // 空路径兜底。
        }
        String normalized = path.replace('\\', '/'); // 统一分隔符。
        int slash = normalized.indexOf('/'); // 找第一个分隔符。
        return slash > 0 ? normalized.substring(0, slash) : normalized; // 返回第一段。
    }

    private void deleteTempPatchFile(Path tempPatchFile) {
        if (tempPatchFile == null) {
            return; // 没有临时文件。
        }
        try {
            Files.deleteIfExists(tempPatchFile); // 删除临时 patch 文件。
            Path parent = tempPatchFile.getParent(); // 获取 .techbrain-patches。
            if (parent != null && isDirectoryEmpty(parent)) {
                Files.deleteIfExists(parent); // 空目录一并清理。
            }
        } catch (Exception e) {
            log.warn("[PatchApply] delete temp patch file failed: {}", e.getMessage()); // 清理失败只 warning。
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return !stream.iterator().hasNext(); // 没有子节点即为空。
        }
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value; // Integer 兜底。
    }

    private boolean isTrue(Boolean value) {
        return Boolean.TRUE.equals(value); // Boolean 安全判断。
    }

    private String summarizeOutput(String output) {
        return safeText(redactSensitive(output == null ? "" : output), OUTPUT_LIMIT); // 脱敏后截断。
    }

    private String redactSensitive(String text) {
        if (text == null) {
            return null; // 空值直接返回。
        }
        String result = URL_CREDENTIAL_PATTERN.matcher(text).replaceAll("$1***@"); // 脱敏 URL user/token。
        result = URL_TOKEN_QUERY_PATTERN.matcher(result).replaceAll("$1=***"); // 脱敏 URL query token。
        result = COMMON_SECRET_PATTERN.matcher(result).replaceAll("***"); // 脱敏常见 token。
        return WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(result).replaceAll("[absolute-path-redacted]"); // 脱敏绝对路径。
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null; // 空值保持为空。
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength); // 截断超长文本。
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null; // 无候选。
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim(); // 返回第一个非空文本。
            }
        }
        return null; // 全为空。
    }

    private record PatchText(String content, Path patchFile) {
    }
}
