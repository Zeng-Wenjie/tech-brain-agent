package com.agent.selfdev.workspace;

import com.agent.config.SandboxWorkspaceProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.entity.dto.SandboxWorkspaceOperationResult;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P9 沙箱 workspace 服务测试。
 *
 * <p>适用场景：不启动真实后端、不连接数据库、不调用 Claude Code，仅用临时目录验证
 * SandboxWorkspaceGuard、SandboxWorkspaceService 和 SafeCommandExecutor 的核心护栏。</p>
 *
 * <p>调用链：JUnit -> SandboxWorkspaceService -> 临时 sandboxRoot/sourceRepoDir -> Git 白名单命令。</p>
 */
class SandboxWorkspaceServiceTest {

    @TempDir
    Path tempDir; // JUnit 提供的临时目录，测试结束自动清理。

    @Test
    void createWorkspaceByLocalCopySkipsGeneratedFilesAndCreatesBranch() throws Exception {
        Path source = createSourceRepo(); // 构造只读复制来源。
        Path sandbox = tempDir.resolve("sandbox"); // 构造独立沙箱根目录。
        ServiceFixture fixture = createFixture(sandbox, source, 20, 7); // 创建服务夹具。

        SandboxWorkspaceCreateRequest request = new SandboxWorkspaceCreateRequest(); // 创建 workspace 请求。
        request.setSourceType("LOCAL_COPY"); // 使用本地复制。
        request.setTaskId("P9 Demo"); // 用于生成分支名。
        request.setWorkspaceName("Tech Brain"); // 验证目录名清洗。
        request.setCreateBranch(true); // 创建临时开发分支。

        SandboxWorkspaceInfo info = fixture.service.createWorkspace(request); // 执行创建。
        Path workspace = Path.of(info.getWorkspacePath()); // 获取后端内部绝对路径。

        assertTrue(workspace.startsWith(sandbox.toAbsolutePath().normalize())); // workspace 必须在 sandboxRoot 内。
        assertTrue(Files.exists(workspace.resolve("pom.xml"))); // 业务源码文件被复制。
        assertTrue(Files.exists(workspace.resolve("src/main/java/App.java"))); // 子目录源码被复制。
        assertFalse(Files.exists(workspace.resolve("target/generated.txt"))); // target 被跳过。
        assertFalse(Files.exists(workspace.resolve(".git/secret"))); // 源仓库 .git 没有被复制。
        assertTrue(Files.isDirectory(workspace.resolve(".git"))); // workspace 内重新初始化独立 Git 仓库。
        assertTrue(info.getBranchName().startsWith("selfdev/")); // 创建临时 selfdev 分支。
        verify(fixture.devActionLogService, atLeastOnce()).saveDevAction(any(DevActionLogCreateRequest.class)); // 写入 dev_action_log。
    }

    @Test
    void validateWorkspaceRejectsSourceRepoAndRuntimeDirectory() throws Exception {
        Path source = createSourceRepo(); // 构造源码目录。
        Path sandbox = tempDir.resolve("sandbox"); // 构造沙箱根。
        ServiceFixture fixture = createFixture(sandbox, source, 20, 7); // 创建服务夹具。

        SandboxWorkspaceOperationResult sourceResult = fixture.service.validateWorkspaceForClaude(source.toString()); // 尝试把源码目录交给 Claude。
        SandboxWorkspaceOperationResult runtimeResult = fixture.service.validateWorkspaceForClaude(System.getProperty("user.dir")); // 尝试把运行目录交给 Claude。

        assertFalse(sourceResult.isSuccess()); // 源码目录必须拒绝。
        assertFalse(runtimeResult.isSuccess()); // 当前运行目录必须拒绝。
    }

    @Test
    void guardRejectsPathTraversal() throws Exception {
        Path source = createSourceRepo(); // 构造源码目录。
        Path sandbox = tempDir.resolve("sandbox"); // 构造沙箱根。
        ServiceFixture fixture = createFixture(sandbox, source, 20, 7); // 创建服务夹具。

        assertThrows(IllegalArgumentException.class,
                () -> fixture.guard.toSafeWorkspacePath("../escape")); // 路径穿越必须拒绝。
    }

    @Test
    void restoreCleanStateResetsTrackedChangesAndRemovesUntrackedFiles() throws Exception {
        Path source = createSourceRepo(); // 构造源码目录。
        Path sandbox = tempDir.resolve("sandbox"); // 构造沙箱根。
        ServiceFixture fixture = createFixture(sandbox, source, 20, 7); // 创建服务夹具。

        SandboxWorkspaceCreateRequest request = new SandboxWorkspaceCreateRequest(); // 创建 workspace 请求。
        request.setSourceType("LOCAL_COPY"); // 使用本地复制。
        request.setCreateBranch(true); // 创建临时分支。
        SandboxWorkspaceInfo info = fixture.service.createWorkspace(request); // 创建 workspace。
        Path workspace = Path.of(info.getWorkspacePath()); // 获取 workspace 路径。
        Path tracked = workspace.resolve("src/main/java/App.java"); // 已跟踪文件。
        Files.writeString(tracked, "class App { String changed = \"yes\"; }"); // 制造已跟踪修改。
        Files.writeString(workspace.resolve("untracked.txt"), "temporary"); // 制造未跟踪文件。

        SandboxWorkspaceOperationResult result = fixture.service.restoreCleanState(info.getWorkspaceId()); // 恢复干净状态。

        assertTrue(result.isSuccess()); // 恢复应成功。
        assertEquals("class App {}", Files.readString(tracked)); // 已跟踪修改被 reset。
        assertFalse(Files.exists(workspace.resolve("untracked.txt"))); // 未跟踪文件被 clean。
    }

    @Test
    void cleanOldWorkspacesKeepsNewestWithinLimit() throws Exception {
        Path source = createSourceRepo(); // 构造源码目录。
        Path sandbox = tempDir.resolve("sandbox"); // 构造沙箱根。
        ServiceFixture fixture = createFixture(sandbox, source, 1, 0); // maxWorkspaces=1，只保留最新一个。
        Files.createDirectories(sandbox); // 创建沙箱根。
        Path oldWorkspace = Files.createDirectories(sandbox.resolve("selfdev-old")); // 旧 workspace。
        Path newWorkspace = Files.createDirectories(sandbox.resolve("selfdev-new")); // 新 workspace。
        Files.setLastModifiedTime(oldWorkspace, FileTime.from(Instant.now().minusSeconds(3600))); // 设置旧时间。
        Files.setLastModifiedTime(newWorkspace, FileTime.from(Instant.now())); // 设置新时间。

        SandboxWorkspaceOperationResult result = fixture.service.cleanOldWorkspaces(); // 执行清理。

        assertTrue(result.isSuccess()); // 清理应成功。
        assertFalse(Files.exists(oldWorkspace)); // 旧 workspace 被删除。
        assertTrue(Files.exists(newWorkspace)); // 最新 workspace 被保留。
    }

    private Path createSourceRepo() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("source")); // 创建源码目录。
        Files.writeString(source.resolve("pom.xml"), "<project></project>"); // 项目标识文件。
        Files.createDirectories(source.resolve("src/main/java")); // 创建源码子目录。
        Files.writeString(source.resolve("src/main/java/App.java"), "class App {}"); // 写入源码文件。
        Files.createDirectories(source.resolve("target")); // 创建构建产物目录。
        Files.writeString(source.resolve("target/generated.txt"), "generated"); // 构建产物应跳过。
        Files.createDirectories(source.resolve(".git")); // 模拟源仓库 .git。
        Files.writeString(source.resolve(".git/secret"), "do-not-copy"); // 源 .git 内容应跳过。
        return source; // 返回源码目录。
    }

    private ServiceFixture createFixture(Path sandbox, Path source, int maxWorkspaces, int retentionDays) {
        SandboxWorkspaceProperties properties = new SandboxWorkspaceProperties(); // 创建配置对象。
        properties.setSandboxRoot(sandbox.toString()); // 设置测试沙箱根。
        properties.setSourceRepoDir(source.toString()); // 设置只读源码目录。
        properties.setWorkspacePrefix("selfdev-"); // 设置目录前缀。
        properties.setBranchPrefix("selfdev/"); // 设置分支前缀。
        properties.setMaxWorkspaces(maxWorkspaces); // 设置最大保留数量。
        properties.setRetentionDays(retentionDays); // 设置保留天数。
        properties.setMaxOperationTimeoutSeconds(30); // 测试命令超时。
        properties.setAllowLocalCopy(true); // 允许本地复制。
        properties.setAllowGitClone(false); // 测试不走真实远程 clone。
        properties.setProtectSourceRepo(true); // 开启源码保护。
        SandboxWorkspaceGuard guard = new SandboxWorkspaceGuard(properties); // 创建路径守卫。
        SafeCommandExecutor executor = new SafeCommandExecutor(properties, guard); // 创建安全命令执行器。
        DevActionLogService devActionLogService = mock(DevActionLogService.class); // mock 日志服务，避免连接数据库。
        when(devActionLogService.saveDevAction(any(DevActionLogCreateRequest.class)))
                .thenReturn(DevActionLogSaveResult.success(1L)); // 日志保存恒成功。
        SandboxWorkspaceService service = new SandboxWorkspaceService(
                properties, guard, executor, devActionLogService, new ObjectMapper()); // 创建待测服务。
        return new ServiceFixture(service, guard, devActionLogService); // 返回夹具。
    }

    private record ServiceFixture(SandboxWorkspaceService service,
                                  SandboxWorkspaceGuard guard,
                                  DevActionLogService devActionLogService) {
    }
}
