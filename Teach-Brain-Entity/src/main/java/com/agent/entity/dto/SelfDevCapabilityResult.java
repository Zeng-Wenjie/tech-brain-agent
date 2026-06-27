package com.agent.entity.dto;

import lombok.Data;

/**
 * Capability state for the Claude Code self-development entry.
 */
@Data
public class SelfDevCapabilityResult {

    private boolean owner;
    private boolean sandboxConfigured;
    private String sandboxWorkspaceDir;
    private String message;
}
