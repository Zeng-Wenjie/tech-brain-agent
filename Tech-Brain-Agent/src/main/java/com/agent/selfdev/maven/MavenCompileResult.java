package com.agent.selfdev.maven;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P11 Maven 编译验证结果对象。
 *
 * <p>适用场景：MavenCompileService 返回给 RunMavenCompileTool，并最终写入 tool_call_log.result_json。
 * 结果只包含命令预览、输出摘要、错误摘要和验证报告，不保存完整超长日志和服务器绝对路径。</p>
 */
@Data
public class MavenCompileResult {

    private String type = "maven_compile"; // 固定结果类型。
    private boolean success; // 编译是否通过。
    private boolean timeout; // 是否超时。
    private boolean confirmationPassed; // 确认标记是否通过。
    private String workspaceId; // P9 workspace ID。
    private String relativeWorkspacePath; // 相对 sandboxRoot 的 workspace 路径。
    private String module; // 本次编译模块。
    private String commandPreview; // 安全命令预览。
    private Integer exitCode; // Maven 进程退出码。
    private Long costTimeMs; // 编译耗时。
    private String outputPreview; // stdout/stderr 脱敏截断预览。
    private String errorSummary; // 编译错误摘要。
    private List<CompileError> errors = new ArrayList<>(); // 关键错误列表。
    private Map<String, Object> report = new LinkedHashMap<>(); // 编译验证报告。
    private Long devLogId; // dev_action_log ID。
    private String message; // 面向调用方的短消息。
    private String errorMsg; // 失败原因。
}
