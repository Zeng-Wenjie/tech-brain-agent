package com.agent.entity.dto;

import lombok.Data;

/**
 * Result returned when bootstrapping the first self-dev OWNER.
 */
@Data
public class SelfDevOwnerBootstrapResult {

    private boolean success;
    private boolean owner;
    private Long userId;
    private String username;
    private String message;
}
