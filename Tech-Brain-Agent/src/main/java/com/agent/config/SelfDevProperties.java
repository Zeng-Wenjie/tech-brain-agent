package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自迭代开发链路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "techbrain.self-dev")
public class SelfDevProperties {

    private String sandboxWorkspaceDir;
    private List<Long> ownerUserIds = new ArrayList<>();
    private ClaudeCode claudeCode = new ClaudeCode();

    @Data
    public static class ClaudeCode {
        private String executable = "claude";
        private List<String> arguments = new ArrayList<>(List.of("-p"));
        private Integer timeoutSeconds = 900;
    }
}
