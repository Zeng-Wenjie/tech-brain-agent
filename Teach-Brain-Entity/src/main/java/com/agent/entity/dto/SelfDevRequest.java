package com.agent.entity.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request object for the Claude Code self-development flow.
 *
 * <p>The new flow prefers P9 workspaceId. The project field is kept only for compatibility with
 * the existing frontend selector.</p>
 */
@Data
public class SelfDevRequest {

    private String intent; // Development intent.
    private String requirement; // Requirement passed to Claude Code.
    private String workspaceId; // Preferred P9 sandbox workspace ID.
    private String project; // Legacy frontend project/workspace name.
    private List<String> allowedPaths = new ArrayList<>(); // Workspace-relative allowed change paths.
    private String moduleScope; // Optional module scope.
    private String projectConventions; // Optional project conventions.
    private String forbiddenPaths; // Optional forbidden path notes.
    private Integer timeoutSeconds; // Per-run timeout.
}
