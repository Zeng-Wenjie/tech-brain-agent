package com.agent.entity.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request body for the Claude Code self-development flow.
 */
@Data
public class SelfDevRequest {

    private String intent;
    private String requirement;
    private List<String> allowedPaths = new ArrayList<>();
    private String moduleScope;
    private String projectConventions;
    private String forbiddenPaths;
    private Integer timeoutSeconds;
}
