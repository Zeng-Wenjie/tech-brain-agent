package com.agent.entity.dto;

import lombok.Data;

import java.util.List;

/**
 * Capability state for the Claude Code self-development entry.
 */
@Data
public class SelfDevCapabilityResult {

    private boolean owner;
    private boolean ownerConfigured;
    private boolean ownerBootstrapAvailable;
    private boolean sandboxConfigured;
    private boolean claudeCodeAvailable;
    private boolean claudeCodeAuthenticated;
    private Long currentUserId;
    private String currentUsername;
    private String sandboxWorkspaceDir;
    private List<String> availableProjects;
    private String claudeCodeLoginCommand;
    private String claudeCodeAuthOutput;
    private String message;
}
