package com.agent.entity.dto;

import lombok.Data;

import java.util.List;

/**
 * Capability snapshot for the Claude Code self-development page.
 */
@Data
public class SelfDevCapabilityResult {

    private boolean owner; // Whether current user is OWNER.
    private boolean ownerConfigured; // Whether any OWNER is configured.
    private boolean ownerBootstrapAvailable; // Whether local bootstrap can be used.
    private boolean sandboxConfigured; // Whether P9 sandboxRoot is valid.
    private boolean claudeCodeAvailable; // Whether Claude Code CLI is available.
    private boolean claudeCodeAuthenticated; // Whether Claude Code is authenticated.
    private Long currentUserId; // Current user ID.
    private String currentUsername; // Current username.
    private String sandboxWorkspaceDir; // P9 sandboxRoot.
    private List<String> availableProjects; // Legacy list of workspace names.
    private List<String> availableWorkspaceIds; // P9 workspace IDs.
    private String claudeCodeLoginCommand; // Login command hint.
    private String claudeCodeAuthOutput; // Auth status output summary.
    private String message; // Capability message.
}
