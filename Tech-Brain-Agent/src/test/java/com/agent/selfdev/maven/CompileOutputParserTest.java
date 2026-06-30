package com.agent.selfdev.maven;

import com.agent.config.MavenCompileProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P11 CompileOutputParser 单元测试。
 *
 * <p>适用场景：验证 Maven stdout/stderr 能被压缩成验证报告、错误摘要和结构化错误列表，并且不会把
 * sandbox workspace 的服务器绝对路径透出给调用方或 dev_action_log。</p>
 */
class CompileOutputParserTest {

    @Test
    void parseBuildSuccessAndRedactWorkspacePath() {
        CompileOutputParser parser = new CompileOutputParser(properties()); // 创建输出解析器。
        MavenCompileResult result = new MavenCompileResult(); // 创建结果对象。
        result.setSuccess(true); // 服务层已根据 exitCode 标记成功。
        Path workspace = Path.of("D:/sandbox/selfdev-1"); // 模拟 Windows sandbox 路径。
        CompileCommandExecutor.CommandResult commandResult = new CompileCommandExecutor.CommandResult(
                0,
                "[INFO] Building in D:/sandbox/selfdev-1\n[INFO] BUILD SUCCESS\n",
                "",
                false,
                1234L); // 模拟成功输出。

        parser.fillResult(result, commandResult, workspace, "Tech-Brain-Agent"); // 解析输出。

        assertEquals(0, result.getExitCode()); // exitCode 回填。
        assertEquals("BUILD_SUCCESS", result.getReport().get("status")); // 成功报告。
        assertFalse(result.getOutputPreview().contains("D:/sandbox/selfdev-1")); // 绝对 workspace 路径被移除。
        assertTrue(result.getErrorSummary().isBlank()); // 成功时无错误摘要。
    }

    @Test
    void parseBuildFailureErrors() {
        CompileOutputParser parser = new CompileOutputParser(properties()); // 创建输出解析器。
        MavenCompileResult result = new MavenCompileResult(); // 创建结果对象。
        result.setSuccess(false); // 服务层已根据 exitCode 标记失败。
        CompileCommandExecutor.CommandResult commandResult = new CompileCommandExecutor.CommandResult(
                1,
                "[ERROR] COMPILATION ERROR\n"
                        + "[ERROR] Tech-Brain-Agent/src/main/java/com/example/App.java:[88,12] cannot find symbol\n"
                        + "[INFO] BUILD FAILURE\n",
                "",
                false,
                2345L); // 模拟失败输出。

        parser.fillResult(result, commandResult, Path.of("D:/sandbox/selfdev-1"), "Tech-Brain-Agent"); // 解析输出。

        assertEquals(1, result.getExitCode()); // exitCode 回填。
        assertEquals("BUILD_FAILURE", result.getReport().get("status")); // 失败报告。
        assertTrue(result.getErrorSummary().contains("cannot find symbol")); // 错误摘要包含关键错误。
        assertFalse(result.getErrors().isEmpty()); // 结构化错误不为空。
        assertTrue(result.getErrors().stream().anyMatch(error -> Integer.valueOf(88).equals(error.getLine()))); // 提取行号。
    }

    private MavenCompileProperties properties() {
        MavenCompileProperties properties = new MavenCompileProperties(); // 创建测试配置。
        properties.setMaxOutputChars(60000); // 输出预览限制。
        properties.setMaxErrorSummaryChars(12000); // 错误摘要限制。
        return properties; // 返回配置。
    }
}
