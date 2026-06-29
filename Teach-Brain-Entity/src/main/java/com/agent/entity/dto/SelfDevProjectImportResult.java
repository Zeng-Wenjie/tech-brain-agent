package com.agent.entity.dto;

import lombok.Data;

/**
 * Result of importing a local project into a P9 sandbox workspace.
 *
 * <p>projectName remains for old frontend compatibility and currently equals workspaceName.</p>
 */
@Data
public class SelfDevProjectImportResult {

    private boolean success; // Whether import succeeded.
    private String projectName; // Legacy frontend selector value.
    private String workspaceId; // P9 workspace ID.
    private String workspaceName; // P9 workspace directory name.
    private String relativeWorkspacePath; // Path relative to sandboxRoot.
    private String sandboxWorkspaceDir; // sandboxRoot for existing frontend display.
    private String message; // Result message.
    private long fileCount; // Copied file count.
    private long directoryCount; // Copied directory count.
    private long byteCount; // Copied bytes.
    private Long devLogId; // dev_action_log ID.
}
