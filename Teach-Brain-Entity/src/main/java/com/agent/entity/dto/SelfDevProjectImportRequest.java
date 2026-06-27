package com.agent.entity.dto;

import lombok.Data;

/**
 * Request body for importing a local project copy into the Claude Code sandbox.
 */
@Data
public class SelfDevProjectImportRequest {

    private String sourcePath;
    private Boolean overwrite;
}
