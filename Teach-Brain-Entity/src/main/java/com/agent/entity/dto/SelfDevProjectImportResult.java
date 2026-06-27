package com.agent.entity.dto;

import lombok.Data;

/**
 * Result returned after copying a local project into the Claude Code sandbox.
 */
@Data
public class SelfDevProjectImportResult {

    private boolean success;
    private String projectName;
    private String sandboxWorkspaceDir;
    private String message;
    private long fileCount;
    private long directoryCount;
    private long byteCount;
    private Long devLogId;
}
