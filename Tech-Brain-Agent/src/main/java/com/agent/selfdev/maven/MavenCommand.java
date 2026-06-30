package com.agent.selfdev.maven;

import java.util.List;

/**
 * 安全 Maven 命令描述。
 *
 * <p>适用场景：MavenCommandBuilder 构造 ProcessBuilder 参数数组后，用该对象同时承载
 * 真正执行的 command 和可写入日志的 commandPreview。</p>
 */
public record MavenCommand(List<String> command, String commandPreview) {
}
