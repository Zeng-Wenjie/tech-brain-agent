package com.agent.entity.dto;

import lombok.Data;

/**
 * Capability state for the Claude Code self-development entry.
 */
@Data
public class SelfDevCapabilityResult {

    private boolean owner;
    private boolean sandboxConfigured;
    private boolean claudeCodeAvailable;
    private boolean claudeCodeAuthenticated;
    private Long currentUserId;
    private String currentUsername;
    private String sandboxWorkspaceDir;
    private String claudeCodeLoginCommand;
    private String claudeCodeAuthOutput;
    private String message;
}
