package com.agent.entity.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result object returned by Patch Run / Terminal after Claude Code runs in a P9 workspace.
 *
 * <p>The diff is returned for review only. Applying the diff is still handled by P10 ApplyPatchTool.</p>
 */
@Data
public class SelfDevResult {

    private boolean success; // Whether Claude Code succeeded and stayed in scope.
    private boolean rejected; // Whether changed files exceeded allowed paths.
    private boolean timedOut; // Whether Claude Code timed out.
    private String status; // SUCCESS / FAILED / REJECTED_OUT_OF_SCOPE.
    private String intent; // Development intent.
    private String workspaceId; // P9 workspace ID.
    private String workspaceName; // P9 workspace directory name.
    private String relativeWorkspacePath; // Path relative to sandboxRoot.
    private String summary; // Human summary.
    private String prompt; // Prompt passed to Claude Code.
    private String stdout; // Claude Code stdout.
    private String stderr; // Claude Code stderr.
    private String errorMessage; // Failure reason.
    private Integer exitCode; // Claude Code exit code.
    private Long durationMs; // Execution duration.
    private List<String> changedFiles = new ArrayList<>(); // Changed files from workspace diff.
    private List<String> rejectedFiles = new ArrayList<>(); // Out-of-scope files.
    private String diff; // Extracted diff for review only.
    private Long devLogId; // dev_action_log ID.
}
