package com.agent.entity.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result returned by the Claude Code self-development flow.
 */
@Data
public class SelfDevResult {

    private boolean success;
    private boolean rejected;
    private boolean timedOut;
    private String status;
    private String intent;
    private String summary;
    private String prompt;
    private String stdout;
    private String stderr;
    private String errorMessage;
    private Integer exitCode;
    private Long durationMs;
    private List<String> changedFiles = new ArrayList<>();
    private List<String> rejectedFiles = new ArrayList<>();
    private String diff;
    private Long devLogId;
}
