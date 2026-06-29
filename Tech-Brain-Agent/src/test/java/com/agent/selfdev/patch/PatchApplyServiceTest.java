package com.agent.selfdev.patch;

import com.agent.config.ApplyPatchProperties;
import com.agent.config.SandboxWorkspaceProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.selfdev.workspace.SafeCommandExecutor;
import com.agent.selfdev.workspace.SandboxWorkspaceGuard;
import com.agent.selfdev.workspace.SandboxWorkspaceService;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P10 patch 应用服务测试。
 *
 * <p>适用场景：使用临时 sourceRepoDir、sandboxRoot 和 backupRoot 验证 PatchApplyService 的 dryRun、
 * 成功应用、安全拒绝与失败自动回滚。测试复用 P9 SandboxWorkspaceService/SandboxWorkspaceGuard，
 * 不连接数据库、不调用 Claude Code、不修改真实项目目录。</p>
 *
 * <p>调用链：JUnit -> SandboxWorkspaceService 创建临时 workspace -> PatchApplyService.applyPatch
 * -> PatchParser/PatchSafetyGuard/PatchBackupService/PatchCommandExecutor -> mock DevActionLogService。</p>
 */
class PatchApplyServiceTest {

    @TempDir
    Path tempDir; // JUnit 自动创建和清理的临时目录，保证测试写操作不落到真实项目。

    @Test
    void dryRunOnlyValidatesPatchAndDoesNotWriteFiles() throws Exception {
        PatchFixture fixture = createPatchFixture(); // 构造 P9/P10 测试夹具。
        SandboxWorkspaceInfo workspaceInfo = fixture.createWorkspace(); // 创建隔离 workspace。
        Path targetFile = Path.of(workspaceInfo.getWorkspacePath())
                .resolve("Tech-Brain-Notes/src/main/java/com/example/App.java"); // 受控业务文件。

        ApplyPatchRequest request = baseRequest(workspaceInfo.getWorkspaceId(), modifiedAppPatch()); // 构造 applyPatch 请求。
        request.setDryRun(true); // dryRun 只校验，不写文件。
        ApplyPatchResult result = fixture.patchApplyService.applyPatch(request); // 执行 P10 dryRun。

        assertTrue(result.isSuccess()); // dryRun 校验应通过。
        assertTrue(result.isDryRun()); // 结果应标记 dryRun。
        assertTrue(result.isSafetyPassed()); // 白名单和黑名单校验应通过。
        assertEquals(originalAppContent(), Files.readString(targetFile)); // 文件内容不能被修改。
        verify(fixture.devActionLogService, atLeastOnce()).saveDevAction(any(DevActionLogCreateRequest.class)); // 行为进入 dev_action_log。
    }

    @Test
    void applyPatchBacksUpAndModifiesAllowedBusinessFile() throws Exception {
        PatchFixture fixture = createPatchFixture(); // 构造 P9/P10 测试夹具。
        SandboxWorkspaceInfo workspaceInfo = fixture.createWorkspace(); // 创建隔离 workspace。
        Path targetFile = Path.of(workspaceInfo.getWorkspacePath())
                .resolve("Tech-Brain-Notes/src/main/java/com/example/App.java"); // 受控业务文件。

        ApplyPatchResult result = fixture.patchApplyService.applyPatch(
                baseRequest(workspaceInfo.getWorkspaceId(), modifiedAppPatch())); // 真正应用 patch。

        assertTrue(result.isSuccess()); // 应用应成功。
        assertNotNull(result.getBackupId()); // 成功应用前必须生成备份。
        assertTrue(Files.readString(targetFile).contains("new")); // 业务文件应被 patch 修改。
        assertEquals("Patch 已成功应用，可进入 P11 编译验证。", result.getMessage()); // 成功后只提示进入 P11。
        verify(fixture.devActionLogService, atLeastOnce()).saveDevAction(any(DevActionLogCreateRequest.class)); // 行为进入 dev_action_log。
    }

    @Test
    void rejectPatchThatTouchesCoreSelfDevDirectory() throws Exception {
        PatchFixture fixture = createPatchFixture(); // 构造 P9/P10 测试夹具。
        SandboxWorkspaceInfo workspaceInfo = fixture.createWorkspace(); // 创建隔离 workspace。
        ApplyPatchRequest request = baseRequest(workspaceInfo.getWorkspaceId(), coreSelfDevPatch()); // 构造命中核心目录的 patch。
        request.setAllowedDirectories(List.of("Tech-Brain-Agent/src/main/java")); // 即使白名单放宽，protectedDirectories 仍要生效。

        ApplyPatchResult result = fixture.patchApplyService.applyPatch(request); // 执行安全校验。

        assertFalse(result.isSuccess()); // 核心目录必须拒绝。
        assertFalse(result.isSafetyPassed()); // 安全校验应失败。
        assertTrue(result.getRejectedFiles().stream()
                .anyMatch(item -> item.getFile().contains("Tech-Brain-Agent/src/main/java/com/agent/selfdev"))); // 命中 protected directory。
    }

    @Test
    void rejectPatchThatTouchesProductionConfigFile() throws Exception {
        PatchFixture fixture = createPatchFixture(); // 构造 P9/P10 测试夹具。
        SandboxWorkspaceInfo workspaceInfo = fixture.createWorkspace(); // 创建隔离 workspace。

        ApplyPatchResult result = fixture.patchApplyService.applyPatch(
                baseRequest(workspaceInfo.getWorkspaceId(), productionConfigPatch())); // 尝试新增生产配置。

        assertFalse(result.isSuccess()); // 生产配置必须拒绝。
        assertFalse(result.isSafetyPassed()); // 安全校验应失败。
        assertTrue(result.getRejectedFiles().stream()
                .anyMatch(item -> item.getFile().endsWith("application-prod.yml"))); // 命中文件名黑名单。
    }

    @Test
    void rejectPatchPathTraversalBeforeApplying() throws Exception {
        PatchFixture fixture = createPatchFixture(); // 构造 P9/P10 测试夹具。
        SandboxWorkspaceInfo workspaceInfo = fixture.createWorkspace(); // 创建隔离 workspace。

        ApplyPatchResult result = fixture.patchApplyService.applyPatch(
                baseRequest(workspaceInfo.getWorkspaceId(), pathTraversalPatch())); // 尝试路径穿越。

        assertFalse(result.isSuccess()); // 路径穿越必须拒绝。
        assertFalse(result.isSafetyPassed()); // 解析阶段失败时不会进入安全通过状态。
        assertTrue(result.getErrorMsg().contains("路径穿越") || result.getMessage().contains("路径穿越")); // 错误应明确指向路径穿越。
    }

    @Test
    void rollbackRestoresFileWhenGitApplyFails() throws Exception {
        PatchFixture fixture = createPatchFixture(); // 构造 P9/P10 测试夹具。
        SandboxWorkspaceInfo workspaceInfo = fixture.createWorkspace(); // 创建隔离 workspace。
        Path targetFile = Path.of(workspaceInfo.getWorkspacePath())
                .resolve("Tech-Brain-Notes/src/main/java/com/example/App.java"); // 受控业务文件。

        ApplyPatchResult result = fixture.patchApplyService.applyPatch(
                baseRequest(workspaceInfo.getWorkspaceId(), failingContextPatch())); // 传入上下文不匹配的 patch。

        assertFalse(result.isSuccess()); // git apply --check 应失败。
        assertTrue(result.isRollbackExecuted()); // 失败后必须触发自动回滚。
        assertTrue(result.isRollbackSuccess()); // 回滚应成功。
        assertEquals(originalAppContent(), Files.readString(targetFile)); // 文件内容应恢复为原始状态。
    }

    private PatchFixture createPatchFixture() throws Exception {
        Path source = createSourceRepo(); // 创建只读复制源。
        Path sandbox = tempDir.resolve("sandbox"); // 创建沙箱根目录。
        Path backup = tempDir.resolve("backups"); // 创建备份根目录。

        SandboxWorkspaceProperties workspaceProperties = new SandboxWorkspaceProperties(); // P9 workspace 配置。
        workspaceProperties.setSandboxRoot(sandbox.toString()); // sandboxRoot 使用临时目录。
        workspaceProperties.setSourceRepoDir(source.toString()); // sourceRepoDir 使用临时目录。
        workspaceProperties.setWorkspacePrefix("selfdev-"); // 使用固定 workspace 前缀。
        workspaceProperties.setBranchPrefix("selfdev/"); // 使用固定分支前缀。
        workspaceProperties.setMaxWorkspaces(20); // 测试保留数量。
        workspaceProperties.setRetentionDays(7); // 测试保留天数。
        workspaceProperties.setMaxOperationTimeoutSeconds(30); // Git 命令测试超时。
        workspaceProperties.setAllowLocalCopy(true); // 允许本地复制。
        workspaceProperties.setAllowGitClone(false); // 测试不走真实远程 clone。
        workspaceProperties.setProtectSourceRepo(true); // 保持 sourceRepoDir 写保护。

        SandboxWorkspaceGuard workspaceGuard = new SandboxWorkspaceGuard(workspaceProperties); // P9 路径护栏。
        SafeCommandExecutor safeCommandExecutor = new SafeCommandExecutor(workspaceProperties, workspaceGuard); // P9 Git 白名单执行器。
        DevActionLogService devActionLogService = mock(DevActionLogService.class); // mock dev_action_log，避免数据库依赖。
        when(devActionLogService.saveDevAction(any(DevActionLogCreateRequest.class)))
                .thenReturn(DevActionLogSaveResult.success(100L)); // 日志保存固定成功。
        ObjectMapper objectMapper = new ObjectMapper(); // JSON 序列化工具。
        SandboxWorkspaceService workspaceService = new SandboxWorkspaceService(
                workspaceProperties, workspaceGuard, safeCommandExecutor, devActionLogService, objectMapper); // P9 workspace 服务。

        ApplyPatchProperties patchProperties = new ApplyPatchProperties(); // P10 patch 配置。
        patchProperties.setBackupRoot(backup.toString()); // backupRoot 使用临时目录。
        patchProperties.setDefaultAllowedDirectories(List.of("Tech-Brain-Notes/src/main/java")); // 默认只允许业务目录。
        patchProperties.setProtectedDirectories(List.of(
                "Tech-Brain-Agent/src/main/java/com/agent/security",
                "Tech-Brain-Agent/src/main/java/com/agent/tool",
                "Tech-Brain-Agent/src/main/java/com/agent/config",
                "Tech-Brain-Agent/src/main/java/com/agent/selfdev",
                "Tech-Brain-Agent/src/main/resources")); // 核心框架目录保护。
        patchProperties.setBlockedFileNames(List.of(".env", ".env.local", ".env.production",
                "application-prod.yml", "application-secret.yml", "bootstrap-prod.yml",
                "id_rsa", "authorized_keys")); // 生产配置和敏感文件名黑名单。
        patchProperties.setBlockedExtensions(List.of(".pem", ".key", ".p12", ".pfx", ".jks",
                ".keystore", ".crt", ".cer", ".der")); // 敏感扩展名黑名单。
        patchProperties.setMaxChangedFiles(50); // 变更文件数限制。
        patchProperties.setMaxPatchSizeKb(1024); // patch 大小限制。
        patchProperties.setMaxOperationTimeoutSeconds(30); // git apply 超时。

        PatchParser patchParser = new PatchParser(); // patch 文件列表解析器。
        PatchSafetyGuard patchSafetyGuard = new PatchSafetyGuard(patchProperties, workspaceGuard); // patch 安全护栏。
        PatchBackupService patchBackupService = new PatchBackupService(
                patchProperties, workspaceGuard, patchSafetyGuard, objectMapper); // patch 备份/回滚服务。
        PatchCommandExecutor patchCommandExecutor = new PatchCommandExecutor(patchProperties, workspaceGuard); // git apply 执行器。
        PatchApplyService patchApplyService = new PatchApplyService(
                patchProperties, workspaceService, workspaceGuard, patchParser, patchSafetyGuard,
                patchBackupService, patchCommandExecutor, devActionLogService, objectMapper); // P10 主服务。

        return new PatchFixture(workspaceService, patchApplyService, devActionLogService); // 返回测试夹具。
    }

    private Path createSourceRepo() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("source")); // 创建 sourceRepoDir。
        Files.writeString(source.resolve("pom.xml"), "<project></project>\n"); // 项目标识文件。
        Path notesJava = Files.createDirectories(source.resolve("Tech-Brain-Notes/src/main/java/com/example")); // 业务源码目录。
        Files.writeString(notesJava.resolve("App.java"), originalAppContent()); // 写入可被 patch 修改的业务文件。
        Path selfDevJava = Files.createDirectories(source.resolve("Tech-Brain-Agent/src/main/java/com/agent/selfdev")); // 核心自迭代目录。
        Files.writeString(selfDevJava.resolve("Core.java"), "package com.agent.selfdev;\nclass Core {}\n"); // 写入 protected 测试文件。
        return source; // 返回只读复制源。
    }

    private ApplyPatchRequest baseRequest(String workspaceId, String patchContent) {
        ApplyPatchRequest request = new ApplyPatchRequest(); // 创建 P10 请求。
        request.setWorkspaceId(workspaceId); // 指定 P9 workspaceId。
        request.setPatchContent(patchContent); // 传入 patchContent。
        request.setRequireConfirm(true); // 高风险写操作需要确认。
        request.setConfirmToken("APPLY_PATCH"); // 确认 token。
        request.setBackupEnabled(true); // 启用应用前备份。
        request.setRollbackOnFailure(true); // 启用失败自动回滚。
        request.setTraceId("test-trace"); // 测试 traceId。
        request.setUserId(1L); // 测试用户 ID。
        request.setConversationId(2L); // 测试会话 ID。
        return request; // 返回请求对象。
    }

    private String originalAppContent() {
        return """
                package com.example;

                class App {
                    String value = "old";
                }
                """; // App.java 原始内容。
    }

    private String modifiedAppPatch() {
        return """
                diff --git a/Tech-Brain-Notes/src/main/java/com/example/App.java b/Tech-Brain-Notes/src/main/java/com/example/App.java
                --- a/Tech-Brain-Notes/src/main/java/com/example/App.java
                +++ b/Tech-Brain-Notes/src/main/java/com/example/App.java
                @@ -1,5 +1,5 @@
                 package com.example;

                 class App {
                -    String value = "old";
                +    String value = "new";
                 }
                """; // 合法业务文件修改 patch。
    }

    private String failingContextPatch() {
        return """
                diff --git a/Tech-Brain-Notes/src/main/java/com/example/App.java b/Tech-Brain-Notes/src/main/java/com/example/App.java
                --- a/Tech-Brain-Notes/src/main/java/com/example/App.java
                +++ b/Tech-Brain-Notes/src/main/java/com/example/App.java
                @@ -1,5 +1,5 @@
                 package com.example;

                 class App {
                -    String value = "missing";
                +    String value = "broken";
                 }
                """; // 路径合法但上下文不匹配，用于触发 git apply 失败和回滚。
    }

    private String coreSelfDevPatch() {
        return """
                diff --git a/Tech-Brain-Agent/src/main/java/com/agent/selfdev/Core.java b/Tech-Brain-Agent/src/main/java/com/agent/selfdev/Core.java
                --- a/Tech-Brain-Agent/src/main/java/com/agent/selfdev/Core.java
                +++ b/Tech-Brain-Agent/src/main/java/com/agent/selfdev/Core.java
                @@ -1,2 +1,2 @@
                 package com.agent.selfdev;
                -class Core {}
                +class Core { String changed = "yes"; }
                """; // 命中 P10 核心框架保护目录。
    }

    private String productionConfigPatch() {
        return """
                diff --git a/Tech-Brain-Notes/src/main/java/application-prod.yml b/Tech-Brain-Notes/src/main/java/application-prod.yml
                new file mode 100644
                --- /dev/null
                +++ b/Tech-Brain-Notes/src/main/java/application-prod.yml
                @@ -0,0 +1 @@
                +secret: true
                """; // 文件名命中生产配置黑名单。
    }

    private String pathTraversalPatch() {
        return """
                diff --git a/../escape.txt b/../escape.txt
                --- a/../escape.txt
                +++ b/../escape.txt
                @@ -1 +1 @@
                -old
                +new
                """; // patch 路径穿越，应在解析阶段拒绝。
    }

    private record PatchFixture(SandboxWorkspaceService workspaceService,
                                PatchApplyService patchApplyService,
                                DevActionLogService devActionLogService) {

        private SandboxWorkspaceInfo createWorkspace() {
            SandboxWorkspaceCreateRequest request = new SandboxWorkspaceCreateRequest(); // 创建 P9 workspace 请求。
            request.setSourceType("LOCAL_COPY"); // 使用本地 sourceRepoDir 复制。
            request.setWorkspaceName("p10-test"); // 固定可读 workspace 名称。
            request.setCreateBranch(false); // P10 测试不依赖临时分支。
            return workspaceService.createWorkspace(request); // 创建隔离 workspace。
        }
    }
}
