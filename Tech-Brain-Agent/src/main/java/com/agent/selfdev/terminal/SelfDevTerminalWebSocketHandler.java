package com.agent.selfdev.terminal;

import com.agent.config.SelfDevProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionStatus;
import com.agent.entity.enums.DevTargetType;
import com.agent.selfdev.diff.WorkspaceDiffResult;
import com.agent.selfdev.diff.WorkspaceDiffService;
import com.agent.selfdev.security.SelfDevAccessGuard;
import com.agent.selfdev.security.SelfDevWorkspaceGuard;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams a real PTY-backed Claude Code terminal over WebSocket.
 */
@Slf4j
@Component
public class SelfDevTerminalWebSocketHandler extends TextWebSocketHandler {

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 32;
    private static final int DISPLAY_TEXT_LIMIT = 200_000;

    private final SelfDevProperties properties;
    private final SelfDevAccessGuard accessGuard;
    private final SelfDevWorkspaceGuard workspaceGuard;
    private final WorkspaceDiffService diffService;
    private final DevActionLogService devActionLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, TerminalSessionState> sessions = new ConcurrentHashMap<>();

    public SelfDevTerminalWebSocketHandler(SelfDevProperties properties,
                                           SelfDevAccessGuard accessGuard,
                                           SelfDevWorkspaceGuard workspaceGuard,
                                           WorkspaceDiffService diffService,
                                           DevActionLogService devActionLogService) {
        this.properties = properties;
        this.accessGuard = accessGuard;
        this.workspaceGuard = workspaceGuard;
        this.diffService = diffService;
        this.devActionLogService = devActionLogService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        TerminalSessionState state = new TerminalSessionState(session);
        sessions.put(session.getId(), state);
        send(session, "connected", Map.of("message", "self-dev terminal connected"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TerminalSessionState state = sessions.get(session.getId());
        if (state == null) {
            send(session, "error", Map.of("message", "terminal session not found"));
            return;
        }
        JsonNode payload = objectMapper.readTree(message.getPayload());
        String type = payload.path("type").asText();
        switch (type) {
            case "start" -> startTerminal(session, state, payload);
            case "input" -> writeInput(state, payload.path("data").asText(""));
            case "resize" -> resize(state, payload.path("cols").asInt(DEFAULT_COLS), payload.path("rows").asInt(DEFAULT_ROWS));
            case "stop" -> stopTerminal(state);
            default -> send(session, "error", Map.of("message", "unknown terminal message type: " + type));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        TerminalSessionState state = sessions.remove(session.getId());
        if (state != null) {
            stopTerminal(state);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[SelfDevTerminal] transport error, session: {}, error: {}", session.getId(), exception.getMessage());
        TerminalSessionState state = sessions.remove(session.getId());
        if (state != null) {
            stopTerminal(state);
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(this::stopTerminal);
        executorService.shutdownNow();
    }

    private void startTerminal(WebSocketSession session, TerminalSessionState state, JsonNode payload) throws Exception {
        if (state.process != null && state.process.isRunning()) {
            send(session, "error", Map.of("message", "terminal already running"));
            return;
        }
        Long userId = getLongAttribute(session, SelfDevTerminalHandshakeInterceptor.ATTR_USER_ID);
        String username = getStringAttribute(session, SelfDevTerminalHandshakeInterceptor.ATTR_USERNAME);
        accessGuard.assertOwner(userId, username);

        WorkspaceContext workspaceContext = resolveWorkspaceContext(
                payload.path("workspaceId").asText(null),
                payload.path("project").asText(null));
        Path workspace = workspaceContext.workspace();
        List<String> allowedPaths = readAllowedPaths(payload.path("allowedPaths"));
        if (allowedPaths.isEmpty()) {
            allowedPaths = List.of(".");
        }

        List<String> command = buildTerminalCommand();
        int cols = clamp(payload.path("cols").asInt(DEFAULT_COLS), 40, 260);
        int rows = clamp(payload.path("rows").asInt(DEFAULT_ROWS), 10, 80);
        Map<String, String> environment = buildEnvironment();

        PtyProcess process = new PtyProcessBuilder(command.toArray(new String[0]))
                .setDirectory(workspace.toString())
                .setEnvironment(environment)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setRedirectErrorStream(true)
                .setWindowsAnsiColorEnabled(true)
                .setUseWinConPty(true)
                .start();

        state.process = process;
        state.workspace = workspace;
        state.workspaceId = workspaceContext.info().getWorkspaceId();
        state.workspaceName = workspaceContext.info().getWorkspaceName();
        state.relativeWorkspacePath = workspaceContext.info().getRelativeWorkspacePath();
        state.command = command;
        state.allowedPaths = allowedPaths;
        state.intent = textOrDefault(payload.path("intent").asText(null), "Claude Code terminal session");
        state.startedAt = System.currentTimeMillis();
        state.traceId = UUID.randomUUID().toString();

        send(session, "started", Map.of(
                "cwd", workspace.toString(),
                "workspaceId", state.workspaceId,
                "workspaceName", state.workspaceName,
                "relativeWorkspacePath", state.relativeWorkspacePath,
                "command", displayCommand(command),
                "traceId", state.traceId
        ));
        executorService.submit(() -> readOutputLoop(state));
        executorService.submit(() -> waitForExit(state));
    }

    private void readOutputLoop(TerminalSessionState state) {
        byte[] buffer = new byte[8192];
        try (InputStream inputStream = state.process.getInputStream()) {
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                appendTranscript(state, text);
                send(state.session, "output", Map.of("data", text));
            }
        } catch (Exception e) {
            if (!state.finalized.get()) {
                log.warn("[SelfDevTerminal] output read failed: {}", e.getMessage());
                sendQuietly(state.session, "error", Map.of("message", "terminal output stream failed: " + e.getMessage()));
            }
        }
    }

    private void waitForExit(TerminalSessionState state) {
        int exitCode = -1;
        try {
            exitCode = state.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finishSession(state, exitCode, null);
        }
    }

    private void finishSession(TerminalSessionState state, int exitCode, String failureMessage) {
        if (!state.finalized.compareAndSet(false, true)) {
            return;
        }
        long durationMs = state.startedAt <= 0 ? 0 : System.currentTimeMillis() - state.startedAt;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exitCode", exitCode);
        payload.put("durationMs", durationMs);
        payload.put("traceId", state.traceId);
        payload.put("workspaceId", state.workspaceId);
        payload.put("workspaceName", state.workspaceName);
        payload.put("relativeWorkspacePath", state.relativeWorkspacePath);
        payload.put("success", false);
        payload.put("status", "FAILED");

        String errorMessage = failureMessage;
        try {
            WorkspaceDiffResult diffResult = diffService.collect(state.workspace);
            List<String> changedFiles = safeList(diffResult.getChangedFiles());
            List<String> rejectedFiles = diffService.findRejectedFiles(changedFiles, state.allowedPaths);
            boolean success = exitCode == 0 && rejectedFiles.isEmpty() && failureMessage == null;
            String status = success ? "SUCCESS" : rejectedFiles.isEmpty() ? "FAILED" : "REJECTED_OUT_OF_SCOPE";
            String summary = buildSummary(success, exitCode, changedFiles, rejectedFiles);

            payload.put("success", success);
            payload.put("status", status);
            payload.put("summary", summary);
            payload.put("changedFiles", changedFiles);
            payload.put("rejectedFiles", rejectedFiles);
            payload.put("diff", limitText(diffResult.getDiff(), DISPLAY_TEXT_LIMIT));
            if (errorMessage != null) {
                payload.put("errorMessage", errorMessage);
            }
            Long devLogId = saveDevActionLog(state, success, status, summary, errorMessage, exitCode, durationMs,
                    changedFiles, rejectedFiles, limitText(diffResult.getDiff(), DISPLAY_TEXT_LIMIT));
            payload.put("devLogId", devLogId);
        } catch (Exception e) {
            errorMessage = errorMessage == null ? e.getMessage() : errorMessage + "; " + e.getMessage();
            String summary = "Claude Code terminal session failed before an accepted diff could be extracted.";
            payload.put("summary", summary);
            payload.put("errorMessage", errorMessage);
            Long devLogId = saveDevActionLog(state, false, "FAILED", summary, errorMessage, exitCode, durationMs,
                    Collections.emptyList(), Collections.emptyList(), "");
            payload.put("devLogId", devLogId);
            log.warn("[SelfDevTerminal] finish failed: {}", e.getMessage(), e);
        }
        sendQuietly(state.session, "exit", payload);
    }

    private void writeInput(TerminalSessionState state, String data) throws Exception {
        if (state.process == null || !state.process.isRunning()) {
            send(state.session, "error", Map.of("message", "terminal is not running"));
            return;
        }
        OutputStream outputStream = state.process.getOutputStream();
        outputStream.write(data.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private void resize(TerminalSessionState state, int cols, int rows) {
        if (state.process == null || !state.process.isRunning()) {
            return;
        }
        try {
            state.process.setWinSize(new WinSize(clamp(cols, 40, 260), clamp(rows, 10, 80)));
        } catch (Exception e) {
            log.debug("[SelfDevTerminal] resize ignored: {}", e.getMessage());
        }
    }

    private void stopTerminal(TerminalSessionState state) {
        PtyProcess process = state.process;
        if (process != null && process.isRunning()) {
            process.destroy();
            executorService.submit(() -> {
                try {
                    Thread.sleep(1500);
                    if (process.isRunning()) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private List<String> buildTerminalCommand() {
        SelfDevProperties.ClaudeCode claudeCode = properties.getClaudeCode();
        String executable = claudeCode == null || isBlank(claudeCode.getExecutable()) ? "claude" : claudeCode.getExecutable().trim();
        List<String> arguments = claudeCode == null || claudeCode.getTerminalArguments() == null
                ? Collections.emptyList()
                : claudeCode.getTerminalArguments();
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(arguments);
        return command;
    }

    private Map<String, String> buildEnvironment() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putIfAbsent("TERM", "xterm-256color");
        environment.putIfAbsent("COLORTERM", "truecolor");
        environment.put("TECHBRAIN_SELF_DEV_MODE", "terminal");
        return environment;
    }

    private List<String> readAllowedPaths(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of(".");
        }
        List<String> raw = new ArrayList<>();
        node.forEach(item -> raw.add(item.asText()));
        return workspaceGuard.sanitizeAllowedPaths(raw);
    }

    /**
     * 把前端传来的 project 解析成沙箱下的项目工作目录；为空时若沙箱里仅有一个项目则用它，否则提示先选/先导入。
     */
    private WorkspaceContext resolveWorkspaceContext(String workspaceId, String project) {
        SandboxWorkspaceInfo info = workspaceGuard.resolveWorkspaceInfo(workspaceId, project); // workspaceId first.
        Path workspace = Path.of(info.getWorkspacePath()).toAbsolutePath().normalize(); // Internal absolute path.
        workspaceGuard.validateWorkspaceForClaude(workspace); // P9 validation before starting Claude Code.
        return new WorkspaceContext(info, workspace); // Bind path and metadata for state/logs.
    }

    private Long saveDevActionLog(TerminalSessionState state,
                                  boolean success,
                                  String status,
                                  String summary,
                                  String errorMessage,
                                  int exitCode,
                                  long durationMs,
                                  List<String> changedFiles,
                                  List<String> rejectedFiles,
                                  String diff) {
        try {
            DevActionLogCreateRequest request = new DevActionLogCreateRequest();
            request.setUserId(getLongAttribute(state.session, SelfDevTerminalHandshakeInterceptor.ATTR_USER_ID));
            request.setTraceId(state.traceId == null ? UUID.randomUUID().toString() : state.traceId);
            request.setIntent(state.intent);
            request.setResult(success ? DevActionResult.SUCCESS.name() : DevActionResult.FAILED.name());
            request.setStatus(success ? DevActionStatus.SUCCESS.name() : DevActionStatus.FAILED.name());
            request.setTargetType(DevTargetType.MODULE.name());
            request.setTargetModule(firstNonBlank(state.workspaceName, "Claude Code terminal"));
            request.setTargetFile(firstOrNull(changedFiles));
            request.setTargetPath(firstOrNull(changedFiles));
            request.setTitle("Claude Code terminal session");
            request.setSummary(summary);
            request.setErrorMsg(errorMessage);
            request.setResultJson(toResultJson(state, status, summary, errorMessage, exitCode, durationMs,
                    changedFiles, rejectedFiles, diff));
            DevActionLogSaveResult saveResult = devActionLogService.recordClaudeCodeExecuted(request);
            return saveResult != null && saveResult.isSaved() ? saveResult.getDevLogId() : null;
        } catch (Exception e) {
            log.warn("[SelfDevTerminal] failed to save dev_action_log: {}", e.getMessage(), e);
            return null;
        }
    }

    private String toResultJson(TerminalSessionState state,
                                String status,
                                String summary,
                                String errorMessage,
                                int exitCode,
                                long durationMs,
                                List<String> changedFiles,
                                List<String> rejectedFiles,
                                String diff) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "terminal");
        result.put("intent", state.intent);
        result.put("status", status);
        result.put("summary", summary);
        result.put("command", displayCommand(state.command));
        result.put("workspaceId", state.workspaceId);
        result.put("workspaceName", state.workspaceName);
        result.put("relativeWorkspacePath", state.relativeWorkspacePath);
        result.put("output", limitText(state.transcript.toString(), DISPLAY_TEXT_LIMIT));
        result.put("exitCode", exitCode);
        result.put("durationMs", durationMs);
        result.put("changedFiles", changedFiles);
        result.put("rejectedFiles", rejectedFiles);
        result.put("diff", diff);
        result.put("errorMessage", errorMessage);
        return objectMapper.writeValueAsString(result);
    }

    private String buildSummary(boolean success, int exitCode, List<String> changedFiles, List<String> rejectedFiles) {
        int changedCount = changedFiles == null ? 0 : changedFiles.size();
        int rejectedCount = rejectedFiles == null ? 0 : rejectedFiles.size();
        if (success) {
            return "Claude Code terminal session completed in the sandbox. Changed files: " + changedCount + ". Diff extracted for review only.";
        }
        if (rejectedCount > 0) {
            return "Claude Code terminal session produced out-of-scope changes. Rejected files: " + rejectedCount + ".";
        }
        return "Claude Code terminal session exited with code " + exitCode + ".";
    }

    private void appendTranscript(TerminalSessionState state, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (state.transcript) {
            state.transcript.append(text);
            if (state.transcript.length() > DISPLAY_TEXT_LIMIT) {
                state.transcript.delete(0, state.transcript.length() - DISPLAY_TEXT_LIMIT);
                if (!state.transcriptTruncated) {
                    state.transcript.insert(0, "[earlier terminal output truncated]\n");
                    state.transcriptTruncated = true;
                }
            }
        }
    }

    private void send(WebSocketSession session, String type, Map<String, ?> data) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        if (data != null) {
            payload.putAll(data);
        }
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        }
    }

    private void sendQuietly(WebSocketSession session, String type, Map<String, ?> data) {
        try {
            send(session, type, data);
        } catch (Exception e) {
            log.debug("[SelfDevTerminal] send ignored after close: {}", e.getMessage());
        }
    }

    private Long getLongAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof Long longValue ? longValue : null;
    }

    private String getStringAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof String stringValue ? stringValue : null;
    }

    private String displayCommand(List<String> command) {
        return command == null ? "" : String.join(" ", command);
    }

    private String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n[truncated]";
    }

    private String textOrDefault(String text, String fallback) {
        return isBlank(text) ? fallback : text.trim();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class TerminalSessionState {
        private final WebSocketSession session;
        private final AtomicBoolean finalized = new AtomicBoolean(false);
        private final StringBuilder transcript = new StringBuilder();
        private boolean transcriptTruncated;
        private PtyProcess process;
        private Path workspace;
        private String workspaceId;
        private String workspaceName;
        private String relativeWorkspacePath;
        private List<String> command = Collections.emptyList();
        private List<String> allowedPaths = List.of(".");
        private String intent;
        private String traceId;
        private long startedAt;

        private TerminalSessionState(WebSocketSession session) {
            this.session = session;
        }
    }

    private record WorkspaceContext(SandboxWorkspaceInfo info, Path workspace) {
    }
}
