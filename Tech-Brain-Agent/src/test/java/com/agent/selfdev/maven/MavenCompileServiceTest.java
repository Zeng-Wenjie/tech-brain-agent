package com.agent.selfdev.maven;

import com.agent.config.MavenCompileProperties;
import com.agent.config.SandboxWorkspaceProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import com.agent.selfdev.workspace.SandboxWorkspaceService;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P11 MavenCompileService 主流程测试。
 *
 * <p>适用场景：用临时 sandbox workspace 与 mock CompileCommandExecutor 验证缺少确认标记、编译成功、
 * 编译失败、超时、危险参数拒绝和 sourceRepoDir 拒绝。测试不执行真实 Maven，不调用 Claude Code，
 * 不修改真实项目目录。</p>
 */
class MavenCompileServiceTest {

    @TempDir
    Path tempDir; // JUnit 自动清理的临时目录。

    @Test
    void rejectMissingConfirmTokenBeforeCommand() throws Exception {
        ServiceFixture fixture = createFixture(); // 创建 P11 测试夹具。
        MavenCompileRequest request = baseRequest("selfdev-demo"); // 创建请求。
        request.setConfirmToken(null); // 缺少 RUN_COMPILE。

        MavenCompileResult result = fixture.service.runCompile(request); // 执行编译验证。

        assertFalse(result.isSuccess()); // 缺确认必须失败。
        assertTrue(result.getErrorMsg().contains("RUN_COMPILE")); // 错误信息应提示确认标记。
        verify(fixture.commandExecutor, never()).run(any(Path.class), anyList(), any(Duration.class)); // 不应执行命令。
        verify(fixture.devActionLogService).recordCompileVerified(any(DevActionLogCreateRequest.class)); // 失败也记录 dev_action_log。
    }

    @Test
    void compileSuccessGeneratesReportAndDevLog() throws Exception {
        ServiceFixture fixture = createFixture(); // 创建 P11 测试夹具。
        when(fixture.commandExecutor.run(any(Path.class), anyList(), any(Duration.class)))
                .thenReturn(new CompileCommandExecutor.CommandResult(0, "[INFO] BUILD SUCCESS\n", "", false, 1200L)); // 模拟成功。
        MavenCompileRequest request = baseRequest("selfdev-demo"); // 创建请求。
        request.setModule("Tech-Brain-Agent"); // 指定模块。

        MavenCompileResult result = fixture.service.runCompile(request); // 执行编译验证。

        assertTrue(result.isSuccess()); // 编译应通过。
        assertEquals("BUILD_SUCCESS", result.getReport().get("status")); // 报告为成功。
        assertTrue(result.getCommandPreview().contains("-pl Tech-Brain-Agent -am -DskipTests compile")); // 模块命令形态正确。
        assertEquals(300L, result.getDevLogId()); // devLogId 回填。
        verify(fixture.devActionLogService).recordCompileVerified(any(DevActionLogCreateRequest.class)); // 写入 dev_action_log。
    }

    @Test
    void compileFailureReturnsErrorSummary() throws Exception {
        ServiceFixture fixture = createFixture(); // 创建 P11 测试夹具。
        when(fixture.commandExecutor.run(any(Path.class), anyList(), any(Duration.class)))
                .thenReturn(new CompileCommandExecutor.CommandResult(
                        1,
                        "[ERROR] COMPILATION ERROR\n"
                                + "[ERROR] Tech-Brain-Agent/src/main/java/com/example/App.java:[88,12] cannot find symbol\n"
                                + "[INFO] BUILD FAILURE\n",
                        "",
                        false,
                        1500L)); // 模拟失败。
        MavenCompileRequest request = baseRequest("selfdev-demo"); // 创建请求。
        request.setModule("Tech-Brain-Agent"); // 指定模块。

        MavenCompileResult result = fixture.service.runCompile(request); // 执行编译验证。

        assertFalse(result.isSuccess()); // 编译失败。
        assertEquals("BUILD_FAILURE", result.getReport().get("status")); // 报告为失败。
        assertTrue(result.getErrorSummary().contains("cannot find symbol")); // 摘要包含关键错误。
        assertFalse(result.getErrors().isEmpty()); // 结构化错误存在。
        assertTrue(result.getErrors().stream().anyMatch(error -> Integer.valueOf(88).equals(error.getLine()))); // 提取行号。
    }

    @Test
    void timeoutInterruptsCompileAndReturnsTimeoutReport() throws Exception {
        ServiceFixture fixture = createFixture(); // 创建 P11 测试夹具。
        when(fixture.commandExecutor.run(any(Path.class), anyList(), any(Duration.class)))
                .thenReturn(new CompileCommandExecutor.CommandResult(-1, "", "", true, 600000L)); // 模拟超时。
        MavenCompileRequest request = baseRequest("selfdev-demo"); // 创建请求。
        request.setTimeoutSeconds(1); // 指定短超时。

        MavenCompileResult result = fixture.service.runCompile(request); // 执行编译验证。

        assertFalse(result.isSuccess()); // 超时视为失败。
        assertTrue(result.isTimeout()); // 标记超时。
        assertEquals("TIMEOUT", result.getReport().get("status")); // 报告为超时。
        assertTrue(result.getErrorSummary().contains("超时")); // 摘要包含超时。
    }

    @Test
    void rejectDangerousExtraArgsBeforeCommand() throws Exception {
        ServiceFixture fixture = createFixture(); // 创建 P11 测试夹具。
        MavenCompileRequest request = baseRequest("selfdev-demo"); // 创建请求。
        request.setExtraArgs(List.of("install")); // 传入危险 goal。

        MavenCompileResult result = fixture.service.runCompile(request); // 执行编译验证。

        assertFalse(result.isSuccess()); // 危险参数必须拒绝。
        assertNotNull(result.getErrorMsg()); // 返回错误原因。
        verify(fixture.commandExecutor, never()).run(any(Path.class), anyList(), any(Duration.class)); // 不应执行命令。
    }

    @Test
    void rejectSourceRepoDirectoryBeforeCommand() throws Exception {
        ServiceFixture fixture = createFixture(); // 创建 P11 测试夹具。
        SandboxWorkspaceInfo unsafeInfo = workspaceInfo("selfdev-demo", fixture.source.toString()); // 伪造 sourceRepoDir 作为 workspace。
        when(fixture.workspaceService.getWorkspaceInfo("selfdev-demo")).thenReturn(unsafeInfo); // 返回不安全路径。
        MavenCompileRequest request = baseRequest("selfdev-demo"); // 创建请求。

        MavenCompileResult result = fixture.service.runCompile(request); // 执行编译验证。

        assertFalse(result.isSuccess()); // sourceRepoDir 必须拒绝。
        verify(fixture.commandExecutor, never()).run(any(Path.class), anyList(), any(Duration.class)); // 不应执行命令。
    }

    private ServiceFixture createFixture() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("source")); // 创建只读源目录占位。
        Path sandbox = Files.createDirectories(tempDir.resolve("sandbox")); // 创建 sandboxRoot。
        Path workspace = Files.createDirectories(sandbox.resolve("selfdev-demo")); // 创建 P9 workspace。
        writeMavenProject(workspace); // 写入最小 Maven 多模块结构。

        SandboxWorkspaceProperties workspaceProperties = new SandboxWorkspaceProperties(); // 创建 P9 配置。
        workspaceProperties.setSandboxRoot(sandbox.toString()); // 设置 sandboxRoot。
        workspaceProperties.setSourceRepoDir(source.toString()); // 设置 sourceRepoDir。
        workspaceProperties.setProtectSourceRepo(true); // 开启源目录保护。
        SandboxWorkspaceGuard guard = new SandboxWorkspaceGuard(workspaceProperties); // 创建 P9 Guard。

        MavenCompileProperties compileProperties = new MavenCompileProperties(); // 创建 P11 配置。
        compileProperties.setExecutable("mvn"); // 使用 PATH 中 mvn。
        compileProperties.setDefaultTimeoutSeconds(180); // 默认超时。
        compileProperties.setMaxTimeoutSeconds(600); // 最大超时。
        compileProperties.setDefaultSkipTests(true); // 默认跳过测试。
        compileProperties.setMaxOutputChars(60000); // 输出预览上限。
        compileProperties.setMaxErrorSummaryChars(12000); // 错误摘要上限。
        compileProperties.setAllowedExtraArgs(List.of("-q", "-U", "-e", "-X")); // 白名单参数。

        SandboxWorkspaceService workspaceService = mock(SandboxWorkspaceService.class); // mock P9 workspace 查询。
        when(workspaceService.getWorkspaceInfo("selfdev-demo"))
                .thenReturn(workspaceInfo("selfdev-demo", workspace.toString())); // 返回安全 workspace。
        CompileCommandExecutor commandExecutor = mock(CompileCommandExecutor.class); // mock Maven 执行器，不执行真实 Maven。
        DevActionLogService devActionLogService = mock(DevActionLogService.class); // mock dev_action_log。
        when(devActionLogService.recordCompileVerified(any(DevActionLogCreateRequest.class)))
                .thenReturn(DevActionLogSaveResult.success(300L)); // 日志保存固定成功。

        MavenCommandBuilder commandBuilder = new MavenCommandBuilder(compileProperties); // 命令构造器。
        CompileOutputParser outputParser = new CompileOutputParser(compileProperties); // 输出解析器。
        MavenCompileService service = new MavenCompileService(
                compileProperties, workspaceService, guard, commandBuilder, commandExecutor,
                outputParser, devActionLogService, new ObjectMapper()); // 创建待测服务。
        return new ServiceFixture(service, workspaceService, commandExecutor, devActionLogService, source); // 返回夹具。
    }

    private void writeMavenProject(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>Tech-Brain-Agent</module>
                  </modules>
                </project>
                """); // 根 pom 声明模块。
        Path module = Files.createDirectories(workspace.resolve("Tech-Brain-Agent")); // 创建模块目录。
        Files.writeString(module.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>agent</artifactId>
                  <version>1.0.0</version>
                </project>
                """); // 模块 pom。
    }

    private MavenCompileRequest baseRequest(String workspaceId) {
        MavenCompileRequest request = new MavenCompileRequest(); // 创建请求。
        request.setWorkspaceId(workspaceId); // 指定 workspaceId。
        request.setRequireConfirm(true); // 要求确认。
        request.setConfirmToken("RUN_COMPILE"); // 设置确认 token。
        request.setTraceId("test-trace"); // 测试 traceId。
        request.setUserId(1L); // 测试用户 ID。
        request.setConversationId(2L); // 测试会话 ID。
        return request; // 返回请求。
    }

    private SandboxWorkspaceInfo workspaceInfo(String workspaceId, String workspacePath) {
        SandboxWorkspaceInfo info = new SandboxWorkspaceInfo(); // 创建 workspace 信息。
        info.setWorkspaceId(workspaceId); // 设置 workspaceId。
        info.setWorkspaceName(workspaceId); // 设置 workspaceName。
        info.setWorkspacePath(workspacePath); // 设置后端内部路径。
        info.setRelativeWorkspacePath(workspaceId); // 设置相对路径。
        info.setStatus("READY"); // 标记可用。
        return info; // 返回 workspace 信息。
    }

    private record ServiceFixture(MavenCompileService service,
                                  SandboxWorkspaceService workspaceService,
                                  CompileCommandExecutor commandExecutor,
                                  DevActionLogService devActionLogService,
                                  Path source) {
    }
}
