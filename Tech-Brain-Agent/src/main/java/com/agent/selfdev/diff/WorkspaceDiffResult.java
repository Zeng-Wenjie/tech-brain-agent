package com.agent.selfdev.diff;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Git diff snapshot collected from the sandbox workspace.
 */
@Data
public class WorkspaceDiffResult {

    private List<String> changedFiles = new ArrayList<>();
    private String diff = "";
}
