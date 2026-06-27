package com.agent.service.impl;

import com.agent.entity.DevActionLog;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionStatus;
import com.agent.entity.enums.DevActionType;
import com.agent.entity.enums.DevTargetType;
import com.agent.mapper.DevActionLogMapper;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开发行为日志服务实现。
 *
 * <p>只负责把开发链路行为写入 dev_action_log，并补齐 intent/result/summary 等语义字段。</p>
 */
@Slf4j
@Service
public class DevActionLogServiceImpl extends ServiceImpl<DevActionLogMapper, DevActionLog> implements DevActionLogService {

    private static final int TRACE_ID_MAX_LENGTH = 64;
    private static final int ACTION_TYPE_MAX_LENGTH = 50;
    private static final int INTENT_MAX_LENGTH = 512;
    private static final int RESULT_MAX_LENGTH = 64;
    private static final int ANALYSIS_TYPE_MAX_LENGTH = 30;
    private static final int TARGET_TYPE_MAX_LENGTH = 30;
    private static final int TARGET_MODULE_MAX_LENGTH = 255;
    private static final int TARGET_FILE_MAX_LENGTH = 512;
    private static final int TARGET_PATH_MAX_LENGTH = 500;
    private static final int CLASS_NAME_MAX_LENGTH = 200;
    private static final int METHOD_NAME_MAX_LENGTH = 200;
    private static final int ENDPOINT_MAX_LENGTH = 300;
    private static final int TOOL_NAME_MAX_LENGTH = 100;
    private static final int EVENT_NAME_MAX_LENGTH = 100;
    private static final int RELATED_BUG_ID_MAX_LENGTH = 128;
    private static final int TITLE_MAX_LENGTH = 255;
    private static final int SUMMARY_MAX_LENGTH = 1000;
    private static final int ERROR_MSG_MAX_LENGTH = 1000;
    private static final int RESULT_JSON_MAX_LENGTH = 1_000_000;

    private static final Set<String> KNOWN_MODULE_DIRS = Set.of(
            "Teach-Brain-Entity", "Tech-Bain-Login", "Tech-Brain-Agent", "Tech-Brain-AOP",
            "Tech-Brain-Common", "Tech-Brain-Notes", "Tech-Brain-Tool", "Tech-Brain-Web", "tech-brain-web");
    private static final Pattern BUG_ID_PATTERN = Pattern.compile("(?i)(bug[-#]?\\d+|issue[-#]?\\d+|#\\d+)");
    private static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile("^[A-Za-z]:/.*");
    private static final String TRUNCATION_NOTE = "（结果较长，已截断保存）";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public DevActionLogSaveResult saveDevAction(DevActionLogCreateRequest request) {
        if (request == null) {
            log.warn("[DevActionLog] skip saveDevAction, request is null");
            return DevActionLogSaveResult.failed("开发行为日志入参为空，未保存。");
        }
        try {
            JsonNode resultNode = readTreeSafely(request.getResultJson());
            String targetFile = sanitizeWorkspacePath(request.getTargetFile());
            String targetPath = sanitizeWorkspacePath(request.getTargetPath());
            if (isBlank(targetFile) && !isBlank(targetPath)) {
                targetFile = targetPath;
            }
            if (isBlank(targetPath) && !isBlank(targetFile)) {
                targetPath = targetFile;
            }

            boolean truncated = false;
            String resultJson = request.getResultJson();
            if (resultJson != null && resultJson.length() > RESULT_JSON_MAX_LENGTH) {
                resultJson = resultJson.substring(0, RESULT_JSON_MAX_LENGTH);
                truncated = true;
            }

            String actionType = resolveActionType(request);
            String result = resolveResult(request);
            String status = resolveStatus(request);
            String displayTarget = resolveDisplayTarget(request, targetFile);
            String intent = resolveIntent(request, actionType, displayTarget);
            String title = resolveTitle(request, actionType, displayTarget);
            String summary = resolveSummary(request, actionType, result, displayTarget, resultNode);
            if (truncated) {
                summary = appendWithin(summary, TRUNCATION_NOTE, SUMMARY_MAX_LENGTH);
            }

            LocalDateTime now = LocalDateTime.now();
            DevActionLog devLog = new DevActionLog();
            devLog.setUserId(request.getUserId());
            devLog.setConversationId(request.getConversationId());
            devLog.setTraceId(safeText(request.getTraceId(), TRACE_ID_MAX_LENGTH));
            devLog.setToolCallLogId(request.getToolCallLogId());
            devLog.setActionType(safeText(actionType, ACTION_TYPE_MAX_LENGTH));
            devLog.setIntent(safeText(intent, INTENT_MAX_LENGTH));
            devLog.setResult(safeText(result, RESULT_MAX_LENGTH));
            devLog.setAnalysisType(safeText(request.getAnalysisType(), ANALYSIS_TYPE_MAX_LENGTH));
            devLog.setTargetType(safeText(resolveTargetType(request, targetFile), TARGET_TYPE_MAX_LENGTH));
            devLog.setTargetModule(safeText(resolveTargetModule(request, targetFile, targetPath), TARGET_MODULE_MAX_LENGTH));
            devLog.setTargetFile(safeText(targetFile, TARGET_FILE_MAX_LENGTH));
            devLog.setTargetPath(safeText(targetPath, TARGET_PATH_MAX_LENGTH));
            devLog.setClassName(safeText(request.getClassName(), CLASS_NAME_MAX_LENGTH));
            devLog.setMethodName(safeText(request.getMethodName(), METHOD_NAME_MAX_LENGTH));
            devLog.setEndpoint(safeText(request.getEndpoint(), ENDPOINT_MAX_LENGTH));
            devLog.setToolName(safeText(request.getToolName(), TOOL_NAME_MAX_LENGTH));
            devLog.setEventName(safeText(request.getEventName(), EVENT_NAME_MAX_LENGTH));
            devLog.setRelatedBugId(safeText(resolveRelatedBugId(request), RELATED_BUG_ID_MAX_LENGTH));
            devLog.setTitle(safeText(title, TITLE_MAX_LENGTH));
            devLog.setSummary(safeText(summary, SUMMARY_MAX_LENGTH));
            devLog.setResultJson(resultJson);
            devLog.setStatus(safeText(status, RESULT_MAX_LENGTH));
            devLog.setErrorMsg(safeText(request.getErrorMsg(), ERROR_MSG_MAX_LENGTH));
            devLog.setCreatedAt(now);
            devLog.setUpdatedAt(now);

            save(devLog);
            log.info("[DevActionLog] save ok, id: {}, actionType: {}, result: {}, traceId: {}",
                    devLog.getId(), devLog.getActionType(), devLog.getResult(), devLog.getTraceId());
            return DevActionLogSaveResult.success(devLog.getId());
        } catch (Exception e) {
            log.warn("[DevActionLog] save failed, traceId: {}, error: {}",
                    request.getTraceId(), e.getMessage(), e);
            return DevActionLogSaveResult.failed("保存开发日志失败，请稍后重试。");
        }
    }

    @Override
    public DevActionLogSaveResult recordClaudeCodeExecuted(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.CLAUDE_CODE_EXECUTED);
    }

    @Override
    public DevActionLogSaveResult recordChangePlanGenerated(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.CHANGE_PLAN_GENERATED);
    }

    @Override
    public DevActionLogSaveResult recordPatchGenerated(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.PATCH_GENERATED);
    }

    @Override
    public DevActionLogSaveResult recordFileModified(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.FILE_MODIFIED);
    }

    @Override
    public DevActionLogSaveResult recordCompileVerified(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.COMPILE_VERIFIED);
    }

    @Override
    public DevActionLogSaveResult recordFrontendBuildVerified(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.FRONTEND_BUILD_VERIFIED);
    }

    @Override
    public DevActionLogSaveResult recordReleaseConfirmed(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.RELEASE_CONFIRMED);
    }

    @Override
    public DevActionLogSaveResult recordRollbackExecuted(DevActionLogCreateRequest request) {
        return saveWithType(request, DevActionType.ROLLBACK_EXECUTED);
    }

    private DevActionLogSaveResult saveWithType(DevActionLogCreateRequest request, DevActionType type) {
        if (request == null) {
            return DevActionLogSaveResult.failed("开发行为日志入参为空，未保存。");
        }
        request.setActionType(type.name());
        return saveDevAction(request);
    }

    private String resolveActionType(DevActionLogCreateRequest request) {
        return DevActionType.fromName(request.getActionType()).name();
    }

    private String resolveResult(DevActionLogCreateRequest request) {
        return DevActionResult.fromName(request.getResult()).name();
    }

    private String resolveStatus(DevActionLogCreateRequest request) {
        return DevActionStatus.fromName(request.getStatus()).name();
    }

    private String resolveTargetType(DevActionLogCreateRequest request, String targetFile) {
        if (!isBlank(request.getTargetType())) {
            return DevTargetType.fromName(request.getTargetType()).name();
        }
        if (!isBlank(request.getEndpoint())) {
            return DevTargetType.ENDPOINT.name();
        }
        if (!isBlank(request.getToolName())) {
            return DevTargetType.TOOL.name();
        }
        if (!isBlank(request.getEventName())) {
            return DevTargetType.EVENT.name();
        }
        if (!isBlank(request.getClassName())) {
            return DevTargetType.CLASS.name();
        }
        if (!isBlank(request.getMethodName())) {
            return DevTargetType.METHOD.name();
        }
        if (!isBlank(targetFile)) {
            return DevTargetType.FILE.name();
        }
        if (!isBlank(request.getTargetModule())) {
            return DevTargetType.MODULE.name();
        }
        return DevTargetType.AUTO.name();
    }

    private String resolveDisplayTarget(DevActionLogCreateRequest request, String targetFile) {
        if (!isBlank(request.getTargetModule())) {
            return request.getTargetModule().trim();
        }
        if (!isBlank(targetFile)) {
            return fileNameOf(targetFile);
        }
        if (!isBlank(request.getClassName())) {
            return request.getClassName().trim();
        }
        if (!isBlank(request.getEndpoint())) {
            return request.getEndpoint().trim();
        }
        return "沙箱 workspace";
    }

    private String resolveIntent(DevActionLogCreateRequest request, String actionType, String target) {
        if (!isBlank(request.getIntent())) {
            return request.getIntent().trim();
        }
        if (DevActionType.CLAUDE_CODE_EXECUTED.name().equals(actionType)) {
            return "调用 Claude Code 在 " + target + " 中执行开发需求，并提取沙箱 diff 供人工确认。";
        }
        if (!isBlank(request.getTitle())) {
            return request.getTitle().trim();
        }
        if (!isBlank(request.getSummary())) {
            return request.getSummary().trim();
        }
        return "记录一次 " + actionType + " 开发行为。";
    }

    private String resolveTitle(DevActionLogCreateRequest request, String actionType, String target) {
        if (!isBlank(request.getTitle())) {
            return request.getTitle().trim();
        }
        if (DevActionType.CLAUDE_CODE_EXECUTED.name().equals(actionType)) {
            return "Claude Code 开发执行：" + target;
        }
        return "开发行为：" + target;
    }

    private String resolveSummary(DevActionLogCreateRequest request,
                                  String actionType,
                                  String result,
                                  String target,
                                  JsonNode resultNode) {
        if (!isBlank(request.getSummary())) {
            return request.getSummary().trim();
        }
        int changedCount = resultNode.path("changedFiles").isArray() ? resultNode.path("changedFiles").size() : 0;
        int rejectedCount = resultNode.path("rejectedFiles").isArray() ? resultNode.path("rejectedFiles").size() : 0;
        if (DevActionType.CLAUDE_CODE_EXECUTED.name().equals(actionType)) {
            if (DevActionResult.SUCCESS.name().equals(result)) {
                return "Claude Code 已在沙箱完成修改，产生 " + changedCount + " 个文件变更，diff 已提取但未自动应用。";
            }
            if (rejectedCount > 0) {
                return "Claude Code 修改越过允许范围，已拒绝本次结果；越界文件数：" + rejectedCount + "。";
            }
            return "Claude Code 开发执行失败，未进入自动应用流程。";
        }
        return "完成一次针对 " + target + " 的 " + actionType + " 开发行为，结果为 " + result + "。";
    }

    private String resolveTargetModule(DevActionLogCreateRequest request, String targetFile, String targetPath) {
        if (!isBlank(request.getTargetModule())) {
            return request.getTargetModule().trim();
        }
        String module = moduleFromPath(targetFile);
        if (module == null) {
            module = moduleFromPath(targetPath);
        }
        return module;
    }

    private String sanitizeWorkspacePath(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String path = raw.trim().replace('\\', '/');
        boolean absolute = ABSOLUTE_PATH_PATTERN.matcher(path).matches() || path.startsWith("/");
        if (!absolute) {
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            return path;
        }
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (KNOWN_MODULE_DIRS.contains(segments[i])) {
                return String.join("/", Arrays.copyOfRange(segments, i, segments.length));
            }
        }
        return null;
    }

    private String moduleFromPath(String path) {
        if (isBlank(path)) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int slash = normalized.indexOf('/');
        String first = slash >= 0 ? normalized.substring(0, slash) : normalized;
        return KNOWN_MODULE_DIRS.contains(first) ? first : null;
    }

    private String resolveRelatedBugId(DevActionLogCreateRequest request) {
        if (!isBlank(request.getRelatedBugId())) {
            return request.getRelatedBugId().trim();
        }
        String haystack = joinNonBlank(request.getTitle(), request.getIntent(), request.getSummary());
        Matcher matcher = BUG_ID_PATTERN.matcher(haystack);
        return matcher.find() ? matcher.group(1) : null;
    }

    private JsonNode readTreeSafely(String json) {
        try {
            return OBJECT_MAPPER.readTree(isBlank(json) ? "{}" : json);
        } catch (Exception e) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private String fileNameOf(String path) {
        if (isBlank(path)) {
            return "workspace";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 && slash < normalized.length() - 1 ? normalized.substring(slash + 1) : normalized;
    }

    private String joinNonBlank(String... parts) {
        StringBuilder builder = new StringBuilder();
        if (parts != null) {
            for (String part : parts) {
                if (!isBlank(part)) {
                    builder.append(part).append(' ');
                }
            }
        }
        return builder.toString().trim();
    }

    private String appendWithin(String base, String suffix, int maxLength) {
        String prefix = base == null ? "" : base;
        if (suffix == null || suffix.isEmpty()) {
            return prefix;
        }
        if (prefix.length() + suffix.length() <= maxLength) {
            return prefix + suffix;
        }
        int keep = Math.max(0, maxLength - suffix.length());
        return prefix.substring(0, Math.min(prefix.length(), keep)) + suffix;
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
