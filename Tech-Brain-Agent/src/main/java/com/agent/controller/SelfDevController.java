package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.SelfDevCapabilityResult;
import com.agent.entity.dto.SelfDevRequest;
import com.agent.entity.dto.SelfDevResult;
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

    public SelfDevController(SelfDevOrchestrator orchestrator,
                             SelfDevAccessGuard accessGuard,
                             SelfDevWorkspaceGuard workspaceGuard) {
        this.orchestrator = orchestrator;
        this.accessGuard = accessGuard;
        this.workspaceGuard = workspaceGuard;
    }

    @Operation(summary = "Read current user's Claude Code capability")
    @GetMapping("/capability")
    public ResponseEntity<Result<SelfDevCapabilityResult>> capability() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Result.error(HttpServletResponse.SC_UNAUTHORIZED, "Login required."));
        }
        SelfDevCapabilityResult result = new SelfDevCapabilityResult();
        boolean owner = accessGuard.isOwner(userId);
        result.setOwner(owner);
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
        if (!accessGuard.isOwner(userId)) {
            return ResponseEntity.status(HttpServletResponse.SC_FORBIDDEN)
                    .body(Result.error(HttpServletResponse.SC_FORBIDDEN, "OWNER role required."));
        }
        SelfDevResult result = orchestrator.execute(request, userId);
        int status = result.isSuccess() || result.isRejected()
                ? HttpServletResponse.SC_OK
                : HttpServletResponse.SC_BAD_REQUEST;
        return ResponseEntity.status(status).body(Result.success(result));
    }
}
