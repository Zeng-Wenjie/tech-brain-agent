package com.agent.selfdev.orchestrator;

import com.agent.config.SelfDevProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SelfDevRequest;
import com.agent.entity.dto.SelfDevResult;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionStatus;
import com.agent.entity.enums.DevTargetType;
import com.agent.selfdev.client.ClaudeCodeClient;
import com.agent.selfdev.client.ClaudeCodeResult;
import com.agent.selfdev.diff.WorkspaceDiffResult;
import com.agent.selfdev.diff.WorkspaceDiffService;
import com.agent.selfdev.security.SelfDevAccessGuard;
import com.agent.selfdev.security.SelfDevWorkspaceGuard;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates sandbox-only Claude Code execution and diff extraction.
 */
@Slf4j
@Service
public class SelfDevOrchestrator {

    private static final int DISPLAY_TEXT_LIMIT = 200_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SelfDevProperties properties;
    private final SelfDevAccessGuard accessGuard;
    private final SelfDevWorkspaceGuard workspaceGuard;
    private final ClaudeCodeClient claudeCodeClient;
    private final WorkspaceDiffService diffService;
    private final DevActionLogService devActionLogService;

    public SelfDevOrchestrator(SelfDevProperties properties,
                               SelfDevAccessGuard accessGuard,
                               SelfDevWorkspaceGuard workspaceGuard,
                               ClaudeCodeClient claudeCodeClient,
                               WorkspaceDiffService diffService,
                               DevActionLogService devActionLogService) {
        this.properties = properties;
        this.accessGuard = accessGuard;
        this.workspaceGuard = workspaceGuard;
        this.claudeCodeClient = claudeCodeClient;
        this.diffService = diffService;
        this.devActionLogService = devActionLogService;
    }

    public SelfDevResult execute(SelfDevRequest request, Long userId) {
        return execute(request, userId, null);
    }

    public SelfDevResult execute(SelfDevRequest request, Long userId, String username) {
        accessGuard.assertOwner(userId, username);
        SelfDevRequest safeRequest = request == null ? new SelfDevRequest() : request;
        long startedAt = System.currentTimeMillis();
        SelfDevResult result = new SelfDevResult();
        result.setIntent(blankToDefault(safeRequest.getIntent(), "Claude Code sandbox development"));
        try {
            validateRequest(safeRequest);
            Path workspace = workspaceGuard.resolveProjectWorkspace(safeRequest.getProject());
            List<String> allowedPaths = workspaceGuard.sanitizeAllowedPaths(safeRequest.getAllowedPaths());
            if (allowedPaths.isEmpty()) {
                throw new IllegalArgumentException("At least one allowed path is required.");
            }

            String prompt = buildPrompt(safeRequest, allowedPaths);
            result.setPrompt(prompt);

            ClaudeCodeResult claudeResult = claudeCodeClient.run(workspace, prompt, resolveTimeout(safeRequest));
            copyClaudeResult(result, claudeResult);

            WorkspaceDiffResult diffResult = diffService.collect(workspace);
            result.setChangedFiles(diffResult.getChangedFiles());
            result.setDiff(limitText(diffResult.getDiff(), DISPLAY_TEXT_LIMIT));

            List<String> rejectedFiles = diffService.findRejectedFiles(diffResult.getChangedFiles(), allowedPaths);
            result.setRejectedFiles(rejectedFiles);
            result.setRejected(!rejectedFiles.isEmpty());

            boolean success = claudeResult.isSuccess() && rejectedFiles.isEmpty();
            result.setSuccess(success);
            result.setStatus(success ? "SUCCESS" : rejectedFiles.isEmpty() ? "FAILED" : "REJECTED_OUT_OF_SCOPE");
            result.setSummary(buildSummary(result));
            return result;
        } catch (Exception e) {
            log.warn("[SelfDev] orchestration failed: {}", e.getMessage(), e);
            result.setSuccess(false);
            result.setStatus("FAILED");
            result.setErrorMessage(e.getMessage());
            result.setSummary(buildSummary(result));
            return result;
        } finally {
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            saveDevActionLog(safeRequest, userId, result);
        }
    }

    private void validateRequest(SelfDevRequest request) {
        if (isBlank(request.getRequirement())) {
            throw new IllegalArgumentException("Requirement must not be blank.");
        }
        if (isBlank(request.getIntent())) {
            request.setIntent("Claude Code sandbox development");
        }
    }

    private Duration resolveTimeout(SelfDevRequest request) {
        int configured = properties.getClaudeCode() == null || properties.getClaudeCode().getTimeoutSeconds() == null
                ? 900
                : properties.getClaudeCode().getTimeoutSeconds();
        int requested = request.getTimeoutSeconds() == null || request.getTimeoutSeconds() <= 0
                ? configured
                : request.getTimeoutSeconds();
        int bounded = Math.max(30, Math.min(requested, Math.max(configured, 30)));
        return Duration.ofSeconds(bounded);
    }

    private String buildPrompt(SelfDevRequest request, List<String> allowedPaths) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Claude Code running inside the Tech-Brain sandbox workspace.").append('\n');
        prompt.append("Complete the requested code change only inside the current working directory.").append('\n');
        prompt.append("Do not edit files outside the allowed paths. Do not apply changes to any production or live workspace.").append('\n');
        prompt.append("Return a concise implementation summary and mention any tests you ran.").append("\n\n");
        prompt.append("Intent:\n").append(request.getIntent()).append("\n\n");
        prompt.append("Requirement:\n").append(request.getRequirement()).append("\n\n");
        prompt.append("Allowed paths:\n");
        for (String allowedPath : allowedPaths) {
            prompt.append("- ").append(allowedPath).append('\n');
        }
        appendSection(prompt, "Module scope", request.getModuleScope());
        appendSection(prompt, "Project conventions", request.getProjectConventions());
        appendSection(prompt, "Forbidden paths or constraints", request.getForbiddenPaths());
        prompt.append("\nHistorical development memory:\n");
        prompt.append("(reserved for RecallDevMemoryTool injection; none provided in this call)\n");
        return prompt.toString();
    }

    private void copyClaudeResult(SelfDevResult target, ClaudeCodeResult source) {
        if (source == null) {
            target.setErrorMessage("Claude Code returned no result.");
            return;
        }
        target.setTimedOut(source.isTimedOut());
        target.setExitCode(source.getExitCode());
        target.setStdout(limitText(source.getStdout(), DISPLAY_TEXT_LIMIT));
        target.setStderr(limitText(source.getStderr(), DISPLAY_TEXT_LIMIT));
        target.setErrorMessage(source.getErrorMessage());
        target.setDurationMs(source.getDurationMs());
    }

    private String buildSummary(SelfDevResult result) {
        int changedCount = result.getChangedFiles() == null ? 0 : result.getChangedFiles().size();
        int rejectedCount = result.getRejectedFiles() == null ? 0 : result.getRejectedFiles().size();
        if (result.isSuccess()) {
            return "Claude Code completed in the sandbox. Changed files: " + changedCount + ". Diff extracted for review only.";
        }
        if (result.isRejected()) {
            return "Claude Code produced out-of-scope changes. Rejected files: " + rejectedCount + ".";
        }
        if (result.isTimedOut()) {
            return "Claude Code timed out before producing an accepted sandbox diff.";
        }
        return "Claude Code self-development flow failed before acceptance.";
    }

    private void saveDevActionLog(SelfDevRequest request, Long userId, SelfDevResult result) {
        try {
            DevActionLogCreateRequest logRequest = new DevActionLogCreateRequest();
            logRequest.setUserId(userId);
            logRequest.setTraceId(UUID.randomUUID().toString());
            logRequest.setIntent(result.getIntent());
            logRequest.setResult(result.isSuccess() ? DevActionResult.SUCCESS.name() : DevActionResult.FAILED.name());
            logRequest.setStatus(result.isSuccess() ? DevActionStatus.SUCCESS.name() : DevActionStatus.FAILED.name());
            logRequest.setTargetType(DevTargetType.MODULE.name());
            logRequest.setTargetModule(request.getModuleScope());
            logRequest.setTargetFile(firstOrNull(result.getChangedFiles()));
            logRequest.setTargetPath(firstOrNull(result.getChangedFiles()));
            logRequest.setTitle("Claude Code sandbox execution");
            logRequest.setSummary(result.getSummary());
            logRequest.setErrorMsg(result.getErrorMessage());
            logRequest.setResultJson(toJson(result));
            DevActionLogSaveResult saveResult = devActionLogService.recordClaudeCodeExecuted(logRequest);
            if (saveResult != null && saveResult.isSaved()) {
                result.setDevLogId(saveResult.getDevLogId());
            }
        } catch (Exception e) {
            log.warn("[SelfDev] failed to save dev_action_log: {}", e.getMessage(), e);
        }
    }

    private String toJson(SelfDevResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("intent", result.getIntent());
            payload.put("status", result.getStatus());
            payload.put("summary", result.getSummary());
            payload.put("prompt", result.getPrompt());
            payload.put("stdout", result.getStdout());
            payload.put("stderr", result.getStderr());
            payload.put("errorMessage", result.getErrorMessage());
            payload.put("exitCode", result.getExitCode());
            payload.put("timedOut", result.isTimedOut());
            payload.put("durationMs", result.getDurationMs());
            payload.put("changedFiles", result.getChangedFiles());
            payload.put("rejectedFiles", result.getRejectedFiles());
            payload.put("diff", result.getDiff());
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize self-dev result\"}";
        }
    }

    private void appendSection(StringBuilder prompt, String title, String value) {
        if (!isBlank(value)) {
            prompt.append('\n').append(title).append(":\n").append(value.trim()).append('\n');
        }
    }

    private String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n[truncated]";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
