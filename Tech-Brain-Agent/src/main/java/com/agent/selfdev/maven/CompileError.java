package com.agent.selfdev.maven;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maven 编译错误摘要项。
 *
 * <p>适用场景：CompileOutputParser 从 Maven 输出中提取关键 [ERROR] 行，
 * 尽量解析出文件、行号和错误消息，供前端和 dev_action_log 展示。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompileError {

    private String file; // workspace 相对文件路径，无法解析时为空。
    private Integer line; // 错误行号，无法解析时为空。
    private String message; // 错误消息摘要。
}
