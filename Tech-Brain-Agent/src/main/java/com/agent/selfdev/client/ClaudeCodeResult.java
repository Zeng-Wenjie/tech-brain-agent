package com.agent.selfdev.client;

import lombok.Data;

@Data
public class ClaudeCodeResult {
    private boolean success;
    private int exitCode;
    private boolean timedOut;
    private long durationMs;
    private String stdout;
    private String stderr;
    private String errorMessage;
}
