package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.SelfDevCapabilityResult;
import com.agent.entity.dto.SelfDevClaudeAuthResult;
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
    public ResponseEntity<Result<SelfDevCapabilityResult>> capability() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Result.error(HttpServletResponse.SC_UNAUTHORIZED, "Login required."));
        }
        String username = UserContext.getUsername();
        SelfDevCapabilityResult result = new SelfDevCapabilityResult();
        boolean owner = accessGuard.isOwner(userId, username);
        result.setOwner(owner);
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
