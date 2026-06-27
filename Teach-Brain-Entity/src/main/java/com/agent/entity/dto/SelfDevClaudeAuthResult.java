package com.agent.entity.dto;

import lombok.Data;

/**
 * Claude Code CLI authentication state.
 */
@Data
public class SelfDevClaudeAuthResult {

    private boolean available;
    private boolean authenticated;
    private boolean loginStarted;
    private String command;
    private String stdout;
    private String stderr;
    private String errorMessage;
    private Integer exitCode;
    private Long durationMs;
}
