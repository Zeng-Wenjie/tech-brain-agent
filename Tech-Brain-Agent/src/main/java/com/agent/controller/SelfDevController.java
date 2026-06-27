package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.SelfDevCapabilityResult;
import com.agent.entity.dto.SelfDevClaudeAuthResult;
import com.agent.entity.dto.SelfDevOwnerBootstrapResult;
import com.agent.entity.dto.SelfDevRequest;
import com.agent.entity.dto.SelfDevResult;
import com.agent.config.SelfDevProperties;
import com.agent.selfdev.client.ClaudeCodeClient;
import com.agent.selfdev.orchestrator.SelfDevOrchestrator;
import com.agent.selfdev.security.SelfDevAccessGuard;
import com.agent.selfdev.security.SelfDevWorkspaceGuard;
import com.agent.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;

/**
 * HTTP entry for the sandbox-only Claude Code self-development flow.
 */
@RestController
@RequestMapping("/api/self-dev")
@Tag(name = "Claude Code self-development")
public class SelfDevController {

    private final SelfDevOrchestrator orchestrator;
    private final SelfDevAccessGuard accessGuard;
    private final SelfDevWorkspaceGuard workspaceGuard;
    private final ClaudeCodeClient claudeCodeClient;
    private final SelfDevProperties properties;

    public SelfDevController(SelfDevOrchestrator orchestrator,
                             SelfDevAccessGuard accessGuard,
                             SelfDevWorkspaceGuard workspaceGuard,
                             ClaudeCodeClient claudeCodeClient,
                             SelfDevProperties properties) {
        this.orchestrator = orchestrator;
        this.accessGuard = accessGuard;
        this.workspaceGuard = workspaceGuard;
        this.claudeCodeClient = claudeCodeClient;
        this.properties = properties;
    }

    @Operation(summary = "Read current user's Claude Code capability")
    @GetMapping("/capability")
    public ResponseEntity<Result<SelfDevCapabilityResult>> capability(HttpServletRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Result.error(HttpServletResponse.SC_UNAUTHORIZED, "Login required."));
        }
        String username = UserContext.getUsername();
        SelfDevCapabilityResult result = new SelfDevCapabilityResult();
        boolean owner = accessGuard.isOwner(userId, username);
        result.setOwner(owner);
        result.setOwnerConfigured(accessGuard.hasAnyOwner());
        result.setOwnerBootstrapAvailable(canBootstrapOwner(request));
        result.setCurrentUserId(userId);
        result.setCurrentUsername(username);
        try {
            Path sandbox = workspaceGuard.resolveSandboxWorkspace();
            result.setSandboxConfigured(true);
            if (owner) {
                result.setSandboxWorkspaceDir(sandbox.toString());
            }
        } catch (Exception e) {
            result.setSandboxConfigured(false);
            result.setMessage(e.getMessage());
        }
        SelfDevClaudeAuthResult authResult = claudeCodeClient.readAuthStatus(resolveAuthStatusTimeout());
        fillClaudeAuth(result, authResult);
        return ResponseEntity.ok(Result.success(result));
    }

    @Operation(summary = "Bootstrap the first self-dev OWNER")
    @PostMapping("/owner/bootstrap")
    public ResponseEntity<Result<SelfDevOwnerBootstrapResult>> bootstrapOwner(HttpServletRequest request) {
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Result.error(HttpServletResponse.SC_UNAUTHORIZED, "Login required."));
        }
        SelfDevOwnerBootstrapResult result = new SelfDevOwnerBootstrapResult();
        result.setUserId(userId);
        result.setUsername(username);
        if (!Boolean.TRUE.equals(properties.getOwnerBootstrapEnabled())) {
            result.setSuccess(false);
            result.setMessage("OWNER 初始化未开启。");
            return ResponseEntity.ok(Result.success(result));
        }
        if (Boolean.TRUE.equals(properties.getOwnerBootstrapLocalOnly()) && !isLocalRequest(request)) {
            return ResponseEntity.status(HttpServletResponse.SC_FORBIDDEN)
                    .body(Result.error(HttpServletResponse.SC_FORBIDDEN, "OWNER bootstrap only allows local requests."));
        }
        if (accessGuard.hasAnyOwner()) {
            result.setOwner(accessGuard.isOwner(userId, username));
            result.setSuccess(result.isOwner());
            result.setMessage(result.isOwner() ? "当前账号已经是 OWNER。" : "系统已经存在 OWNER，不能重复初始化。");
            return ResponseEntity.ok(Result.success(result));
        }
        boolean bootstrapped = accessGuard.bootstrapOwner(userId, username);
        result.setSuccess(bootstrapped);
        result.setOwner(accessGuard.isOwner(userId, username));
        result.setMessage(bootstrapped ? "已将当前账号设为 OWNER。" : "OWNER 初始化失败。");
        return ResponseEntity.ok(Result.success(result));
    }

    @Operation(summary = "Start Claude Code account login")
    @PostMapping("/claude/login")
    public ResponseEntity<Result<SelfDevClaudeAuthResult>> startClaudeLogin() {
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Result.error(HttpServletResponse.SC_UNAUTHORIZED, "Login required."));
        }
        if (!accessGuard.isOwner(userId, username)) {
            return ResponseEntity.status(HttpServletResponse.SC_FORBIDDEN)
                    .body(Result.error(HttpServletResponse.SC_FORBIDDEN, "OWNER role required."));
        }
        SelfDevClaudeAuthResult result = claudeCodeClient.startAuthLogin();
        return ResponseEntity.ok(Result.success(result));
    }

    @Operation(summary = "Run Claude Code in the sandbox workspace")
    @PostMapping("/run")
    public ResponseEntity<Result<SelfDevResult>> run(@RequestBody(required = false) SelfDevRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Result.error(HttpServletResponse.SC_UNAUTHORIZED, "Login required."));
        }
        String username = UserContext.getUsername();
        if (!accessGuard.isOwner(userId, username)) {
            return ResponseEntity.status(HttpServletResponse.SC_FORBIDDEN)
                    .body(Result.error(HttpServletResponse.SC_FORBIDDEN, "OWNER role required."));
        }
        SelfDevResult result = orchestrator.execute(request, userId, username);
        return ResponseEntity.ok(Result.success(result));
    }

    private Duration resolveAuthStatusTimeout() {
        Integer timeoutSeconds = properties.getClaudeCode() == null
                ? null
                : properties.getClaudeCode().getAuthStatusTimeoutSeconds();
        return Duration.ofSeconds(timeoutSeconds == null || timeoutSeconds <= 0 ? 15 : timeoutSeconds);
    }

    private void fillClaudeAuth(SelfDevCapabilityResult target, SelfDevClaudeAuthResult source) {
        if (source == null) {
            target.setClaudeCodeAvailable(false);
            target.setClaudeCodeAuthenticated(false);
            return;
        }
        target.setClaudeCodeAvailable(source.isAvailable());
        target.setClaudeCodeAuthenticated(source.isAuthenticated());
        target.setClaudeCodeLoginCommand("claude auth login");
        String output = firstNonBlank(source.getStdout(), source.getStderr(), source.getErrorMessage());
        target.setClaudeCodeAuthOutput(output);
    }

    private boolean canBootstrapOwner(HttpServletRequest request) {
        if (accessGuard.hasAnyOwner() || !Boolean.TRUE.equals(properties.getOwnerBootstrapEnabled())) {
            return false;
        }
        return !Boolean.TRUE.equals(properties.getOwnerBootstrapLocalOnly()) || isLocalRequest(request);
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String remoteAddr = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "localhost".equalsIgnoreCase(remoteAddr);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
