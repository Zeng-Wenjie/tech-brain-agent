package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * P11 Maven 编译验证配置。
 *
 * <p>适用场景：RunMavenCompileTool 在 P9 sandbox workspace 内执行后端 Maven compile 前，
 * 通过本配置读取 Maven 可执行文件、超时时间、输出截断长度和允许的额外参数白名单。</p>
 *
 * <p>调用链：application.yml -> MavenCompileProperties -> MavenCommandBuilder /
 * CompileCommandExecutor / CompileOutputParser / MavenCompileService。本配置不执行命令，
 * 不修改源码目录，也不负责发布或回滚。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "techbrain.selfdev.compile.maven")
public class MavenCompileProperties {

    private String executable = "mvn"; // Maven 可执行文件，默认只使用 PATH 中的 mvn。
    private Integer defaultTimeoutSeconds = 180; // 默认编译超时时间。
    private Integer maxTimeoutSeconds = 600; // 最大允许超时时间。
    private Boolean defaultSkipTests = true; // 默认跳过测试，只做 compile。
    private Integer maxOutputChars = 60000; // stdout/stderr 返回和日志摘要最大长度。
    private Integer maxErrorSummaryChars = 12000; // 错误摘要最大长度。
    private List<String> allowedExtraArgs = new ArrayList<>(List.of("-q", "-U", "-e", "-X")); // 安全额外参数白名单。
}
