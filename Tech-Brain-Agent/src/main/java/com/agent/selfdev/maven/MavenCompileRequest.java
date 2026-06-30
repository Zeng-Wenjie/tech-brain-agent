package com.agent.selfdev.maven;

import lombok.Data;

import java.util.List;

/**
 * P11 Maven 编译验证请求对象。
 *
 * <p>适用场景：RunMavenCompileTool 将模型参数转成该对象后交给 MavenCompileService。
 * workspaceId 必填，workspacePath 仅作为额外一致性校验；所有实际执行目录都必须由 P9 workspaceId 解析得到。</p>
 */
@Data
public class MavenCompileRequest {

    private String workspaceId; // 必填，P9 sandbox workspace ID。
    private String workspacePath; // 可选，必须与 workspaceId 解析出的 workspace 一致。
    private String module; // 可选 Maven 模块名，例如 Tech-Brain-Agent。
    private Boolean skipTests; // 是否跳过测试，默认取配置。
    private List<String> profiles; // 可选 Maven profile 列表。
    private List<String> extraArgs; // 可选 Maven 安全额外参数。
    private Integer timeoutSeconds; // 本次编译超时秒数。
    private Boolean requireConfirm; // 是否需要确认标记，默认 true。
    private String confirmToken; // requireConfirm=true 时必须为 RUN_COMPILE。
    private Long userId; // Tool 上下文用户 ID。
    private Long conversationId; // Tool 上下文会话 ID。
    private String traceId; // Tool 调用链 traceId。
}
