package com.agent.selfdev.workspace;

import com.agent.config.SandboxWorkspaceProperties;
import com.agent.entity.dto.DevActionLogCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceCreateRequest;
import com.agent.entity.dto.SandboxWorkspaceInfo;
import com.agent.entity.dto.SandboxWorkspaceOperationResult;
import com.agent.entity.enums.DevActionResult;
import com.agent.entity.enums.DevActionType;
import com.agent.entity.enums.DevTargetType;
import com.agent.service.DevActionLogService;
import com.agent.toolcalling.devlog.DevActionLogSaveResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P9 沙箱 workspace 基础服务。
 *
 * <p>适用场景：为后续 Claude Code 开发执行准备隔离代码副本，支持从本地源码目录复制、从 Git 远程克隆、
 * 创建临时开发分支、清理旧 workspace、恢复干净 Git 状态，并在 Claude Code 调用前校验工作目录。</p>
 *
 * <p>调用链：后续内部编排入口 -> SandboxWorkspaceService -> SandboxWorkspaceGuard 校验所有路径
 * -> SafeCommandExecutor 执行白名单 Git 命令 -> DevActionLogService 写入 dev_action_log。
 * 本类不调用 Claude Code、不生成 patch、不应用 patch、不修改线上运行目录。</p>
 */
@Slf4j
@Service
public class SandboxWorkspaceService {

    private static final String SOURCE_TYPE_LOCAL_COPY = "LOCAL_COPY"; // 本地源码只读复制。
    private static final String SOURCE_TYPE_GIT_CLONE = "GIT_CLONE"; // Git 远程克隆。
    private static final String STATUS_CREATED = "CREATED"; // 目录已创建。
    private static final String STATUS_READY = "READY"; // workspace 可用于后续流程。
    private static final String STATUS_FAILED = "FAILED"; // workspace 创建或操作失败。
    private static final String STATUS_RESTORED = "RESTORED"; // workspace 已恢复干净状态。
    private static final String STATUS_CLEANED = "CLEANED"; // workspace 已被清理。
    private static final String TARGET_MODULE = "Tech-Brain"; // dev_action_log 目标模块兜底。
    private static final int LOG_OUTPUT_LIMIT = 4000; // stdout/stderr 写日志前的最大长度。
    private static final DateTimeFormatter BRANCH_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); // 分支时间戳格式。
    private static final Pattern URL_CREDENTIAL_PATTERN = Pattern.compile("(?i)(https?://)([^/@\\s]+)@"); // URL 凭据脱敏。
    private static final Pattern URL_TOKEN_QUERY_PATTERN = Pattern.compile("(?i)(token|access_token|password)=([^&\\s]+)"); // URL token 脱敏。
    private static final Pattern COMMON_SECRET_PATTERN = Pattern.compile("(?i)(ghp|glpat|claude|sk|ak)[A-Za-z0-9_\\-]{12,}"); // 常见 token 脱敏。
    private static final Pattern WINDOWS_ABSOLUTE_PATH_PATTERN = Pattern.compile("(?i)[A-Z]:[\\\\/][^\\s,;\\]\\)'\"]+"); // Windows 绝对路径脱敏。
    private static final Set<String> SKIPPED_SEGMENTS = Set.of(
            ".git", ".idea", ".vscode", ".codex-tmp", "node_modules", "target", "build", "dist",
            "out", ".gradle", ".mvn", "venv", ".venv", "coverage", ".cache", ".next", ".nuxt",
            "data", "uploads", "volumes", "logs", "log", "tmp", "temp"); // LOCAL_COPY 默认跳过目录。
    private static final Set<String> PROJECT_MARKERS = Set.of(
            ".git", "pom.xml", "package.json", "build.gradle", "settings.gradle", "mvnw", "gradlew"); // Claude 工作目录项目标识。

    private final SandboxWorkspaceProperties properties; // P9 配置。
    private final SandboxWorkspaceGuard workspaceGuard; // 路径护栏。
    private final SafeCommandExecutor commandExecutor; // 白名单 Git 执行器。
    private final DevActionLogService devActionLogService; // 开发行为日志服务。
    private final ObjectMapper objectMapper; // JSON 序列化工具。

    public SandboxWorkspaceService(SandboxWorkspaceProperties properties,
                                   SandboxWorkspaceGuard workspaceGuard,
                                   SafeCommandExecutor commandExecutor,
                                   DevActionLogService devActionLogService,
                                   ObjectMapper objectMapper) {
        this.properties = properties; // 注入配置。
        this.workspaceGuard = workspaceGuard; // 注入路径护栏。
        this.commandExecutor = commandExecutor; // 注入安全命令执行器。
        this.devActionLogService = devActionLogService; // 注入日志服务。
        this.objectMapper = objectMapper; // 注入 ObjectMapper。
    }

    public SandboxWorkspaceInfo createWorkspace(SandboxWorkspaceCreateRequest request) {
        String traceId = firstNonBlank(request == null ? null : request.getTraceId(), UUID.randomUUID().toString()); // 兜底 traceId。
        SandboxWorkspaceCreateRequest safeRequest = request == null ? new SandboxWorkspaceCreateRequest() : request; // 避免空指针。
        String workspaceId = createWorkspaceId(); // 创建唯一 workspaceId。
        String workspaceName = buildWorkspaceName(safeRequest, workspaceId); // 生成带前缀和 ID 的目录名。
        String sourceType = resolveSourceType(safeRequest); // 解析代码来源。
        String baseBranch = firstNonBlank(safeRequest.getBaseBranch(), properties.getDefaultBranch(), "main"); // 解析基线分支。
        String branchName = shouldCreateBranch(safeRequest)
                ? buildBranchName(safeRequest, workspaceId)
                : null; // 预先生成临时分支名。
        Path workspacePath = null; // 后续日志需要记录相对路径。
        try {
            workspacePath = workspaceGuard.toSafeWorkspacePath(workspaceName); // 所有写操作目标必须由 Guard 生成。
            createWorkspaceDirectory(workspacePath); // 创建独立 workspace 目录。
            CopyStats copyStats = populateWorkspace(safeRequest, sourceType, baseBranch, workspacePath); // LOCAL_COPY 或 GIT_CLONE。
            if (branchName != null) {
                createBranch(workspacePath, branchName); // 分支只在沙箱 workspace 内创建。
            }
            SandboxWorkspaceInfo info = buildInfo(workspaceId, workspaceName, workspacePath, branchName,
                    baseBranch, sourceType, STATUS_READY); // 构造返回对象。
            saveWorkspaceLog(DevActionType.SANDBOX_WORKSPACE_CREATED.name(), DevActionResult.SUCCESS,
                    traceId, safeRequest.getUserId(), safeRequest.getConversationId(),
                    "创建隔离沙箱 workspace",
                    "创建隔离沙箱 workspace，为后续 Claude Code 开发任务提供安全工作目录。",
                    "已创建 " + info.getRelativeWorkspacePath() + " workspace，后续代码操作只能在该目录内进行。",
                    info, copyStats.toLogMap(), null); // 记录成功日志。
            return info; // 返回可用 workspace。
        } catch (Exception e) {
            log.warn("[SandboxWorkspace] createWorkspace failed: {}", e.getMessage(), e); // 记录服务端 warning。
            SandboxWorkspaceInfo failedInfo = buildFailedInfo(workspaceId, workspaceName, workspacePath, branchName,
                    baseBranch, sourceType); // 构造失败日志上下文。
            saveWorkspaceLog(DevActionType.SANDBOX_WORKSPACE_CREATED.name(), DevActionResult.FAILED,
                    traceId, safeRequest.getUserId(), safeRequest.getConversationId(),
                    "创建隔离沙箱 workspace 失败",
                    "尝试创建隔离沙箱 workspace，为后续 Claude Code 开发任务提供安全工作目录。",
                    "沙箱 workspace 创建失败，未进入 Claude Code 或 patch 流程。",
                    failedInfo, Map.of("error", safeText(redactSensitive(firstNonBlank(e.getMessage(), "unknown error")), 500)), e); // 失败也写 dev_action_log。
            throw new IllegalStateException("创建沙箱 workspace 失败: " + e.getMessage(), e); // 交给调用方中断流程。
        }
    }

    public SandboxWorkspaceOperationResult cleanOldWorkspaces() {
        String traceId = UUID.randomUUID().toString(); // 清理任务生成独立 traceId。
        List<String> cleaned = new ArrayList<>(); // 已清理 workspace 相对路径。
        List<String> skipped = new ArrayList<>(); // 跳过 workspace 相对路径。
        List<String> failed = new ArrayList<>(); // 失败 workspace 相对路径。
        try {
            Path sandboxRoot = workspaceGuard.ensureSandboxRoot(); // 清理仅从 sandboxRoot 出发。
            List<Path> workspaces = listManagedWorkspaces(sandboxRoot); // 只处理 workspacePrefix 开头目录。
            Set<Path> targets = chooseCleanupTargets(workspaces); // 按保留天数和最大数量选择目标。
            for (Path workspace : targets) {
                String relative = workspaceGuard.relativeToSandbox(workspace); // 日志只记录相对路径。
                if (isWorkspaceInUse(workspace)) {
                    skipped.add(relative); // 有锁文件时视为正在使用。
                    continue; // 不删除正在使用的 workspace。
                }
                try {
                    workspaceGuard.validateDeletionTarget(workspace); // 删除前再次经过 Guard。
                    deleteRecursively(workspace); // 使用 Java API 删除，不调用 shell。
                    cleaned.add(relative); // 记录清理成功。
                } catch (Exception e) {
                    failed.add(relative + ": " + safeText(redactSensitive(e.getMessage()), 200)); // 单个失败不影响其它目录尝试。
                }
            }
            boolean success = failed.isEmpty(); // 只要有失败则整体标记失败。
            SandboxWorkspaceOperationResult result = success
                    ? SandboxWorkspaceOperationResult.success("旧 workspace 清理完成。")
                    : SandboxWorkspaceOperationResult.failed("部分旧 workspace 清理失败。"); // 构造结果。
            saveOperationLog(DevActionType.SANDBOX_WORKSPACE_CLEANED.name(),
                    success ? DevActionResult.SUCCESS : DevActionResult.FAILED,
                    traceId, null, null,
                    "清理旧沙箱 workspace",
                    "按 retention-days 和 max-workspaces 清理旧的隔离 workspace。",
                    success ? "旧沙箱 workspace 清理完成。" : "部分旧沙箱 workspace 清理失败。",
                    Map.of("cleaned", cleaned, "skipped", skipped, "failed", failed, "status", STATUS_CLEANED),
                    success ? null : new IllegalStateException(String.join("; ", failed))); // 写入清理日志。
            return result; // 返回清理结果。
        } catch (Exception e) {
            log.warn("[SandboxWorkspace] cleanOldWorkspaces failed: {}", e.getMessage(), e); // 记录 warning。
            saveOperationLog(DevActionType.SANDBOX_WORKSPACE_CLEANED.name(), DevActionResult.FAILED,
                    traceId, null, null,
                    "清理旧沙箱 workspace 失败",
                    "按 retention-days 和 max-workspaces 清理旧的隔离 workspace。",
                    "旧沙箱 workspace 清理失败。",
                    Map.of("cleaned", cleaned, "skipped", skipped, "failed", failed), e); // 写失败日志。
            return SandboxWorkspaceOperationResult.failed(e.getMessage()); // 返回失败结果。
        }
    }

    public SandboxWorkspaceOperationResult restoreCleanState(String workspaceId) {
        String traceId = UUID.randomUUID().toString(); // 恢复操作生成 traceId。
        try {
            Path workspace = resolveWorkspaceById(workspaceId); // workspaceId 只能解析到 sandboxRoot 内目录。
            workspaceGuard.validateWorkspacePath(workspace); // 恢复前必须经过 Guard。
            requireGitRepository(workspace); // 没有 .git 不能执行 reset/clean。
            SafeCommandExecutor.CommandResult reset = commandExecutor.runGitInWorkspace(workspace, List.of("reset", "--hard")); // 恢复已跟踪文件。
            if (!reset.isSuccess()) {
                throw new IllegalStateException("git reset --hard 失败: " + firstNonBlank(reset.getStderr(), reset.getStdout())); // reset 失败中断。
            }
            SafeCommandExecutor.CommandResult clean = commandExecutor.runGitInWorkspace(workspace, List.of("clean", "-fd")); // 删除未跟踪文件。
            if (!clean.isSuccess()) {
                throw new IllegalStateException("git clean -fd 失败: " + firstNonBlank(clean.getStderr(), clean.getStdout())); // clean 失败中断。
            }
            SandboxWorkspaceOperationResult result = SandboxWorkspaceOperationResult.success("workspace 已恢复干净状态。"); // 构造成功结果。
            result.setWorkspaceId(workspaceId); // 回填 workspaceId。
            result.setWorkspacePath(workspace.toString()); // 仅内部返回绝对路径。
            saveOperationLog(DevActionType.SANDBOX_WORKSPACE_RESTORED.name(), DevActionResult.SUCCESS,
                    traceId, null, null,
                    "恢复沙箱 workspace 干净状态",
                    "对指定沙箱 workspace 执行 git reset --hard 和 git clean -fd，恢复干净代码状态。",
                    "沙箱 workspace 已恢复到 Git 干净状态。",
                    Map.of("workspaceId", safeText(workspaceId, 120),
                            "relativeWorkspacePath", workspaceGuard.relativeToSandbox(workspace),
                            "status", STATUS_RESTORED,
                            "stdoutSummary", summarizeOutput(reset.getStdout() + clean.getStdout()),
                            "stderrSummary", summarizeOutput(reset.getStderr() + clean.getStderr())),
                    null); // 写恢复日志。
            return result; // 返回成功结果。
        } catch (Exception e) {
            log.warn("[SandboxWorkspace] restoreCleanState failed: {}", e.getMessage(), e); // 记录 warning。
            saveOperationLog(DevActionType.SANDBOX_WORKSPACE_RESTORED.name(), DevActionResult.FAILED,
                    traceId, null, null,
                    "恢复沙箱 workspace 干净状态失败",
                    "尝试对指定沙箱 workspace 执行 git reset --hard 和 git clean -fd。",
                    "沙箱 workspace 恢复干净状态失败。",
                    Map.of("workspaceId", safeText(firstNonBlank(workspaceId, ""), 120), "status", STATUS_FAILED), e); // 写失败日志。
            return SandboxWorkspaceOperationResult.failed(e.getMessage()); // 返回失败结果。
        }
    }

    public SandboxWorkspaceOperationResult validateWorkspaceForClaude(String workspacePath) {
        String traceId = UUID.randomUUID().toString(); // 校验行为生成 traceId。
        try {
            Path workspace = resolveWorkspacePathForValidation(workspacePath); // 支持绝对路径或 workspaceName。
            workspaceGuard.validateWorkspacePath(workspace); // Claude 工作目录必须在 sandboxRoot 内。
            if (!Files.isDirectory(workspace)) {
                throw new IllegalArgumentException("workspace 不存在或不是目录。"); // 必须已存在。
            }
            if (!containsProjectMarker(workspace)) {
                throw new IllegalArgumentException("workspace 缺少 .git 或项目标识文件。"); // 防止 Claude 指向空目录。
            }
            SandboxWorkspaceOperationResult result = SandboxWorkspaceOperationResult.success("workspace 可用于 Claude Code。"); // 构造成功结果。
            result.setWorkspacePath(workspace.toString()); // 仅内部返回绝对路径。
            saveOperationLog(DevActionType.SANDBOX_WORKSPACE_VALIDATED.name(), DevActionResult.SUCCESS,
                    traceId, null, null,
                    "校验 Claude Code 工作目录",
                    "在调用 Claude Code 前校验工作目录只能指向 sandbox workspace。",
                    "Claude Code 工作目录校验通过，未指向源码目录或线上运行目录。",
                    Map.of("relativeWorkspacePath", workspaceGuard.relativeToSandbox(workspace),
                            "status", STATUS_READY),
                    null); // 记录校验成功。
            return result; // 返回成功。
        } catch (Exception e) {
            log.warn("[SandboxWorkspace] validateWorkspaceForClaude failed: {}", e.getMessage()); // 护栏拒绝属于预期分支，只记录摘要。
            saveOperationLog(DevActionType.SANDBOX_WORKSPACE_VALIDATED.name(), DevActionResult.FAILED,
                    traceId, null, null,
                    "校验 Claude Code 工作目录失败",
                    "在调用 Claude Code 前校验工作目录只能指向 sandbox workspace。",
                    "Claude Code 工作目录校验失败，已拒绝继续执行。",
                    Map.of("requestedWorkspace", firstNonBlank(safeRelativeOrName(workspacePath), ""), "status", STATUS_FAILED), e); // 记录校验失败。
            return SandboxWorkspaceOperationResult.failed(e.getMessage()); // 返回失败。
        }
    }

    public SandboxWorkspaceInfo getWorkspaceInfo(String workspaceId) {
        try {
            Path workspace = resolveWorkspaceById(workspaceId); // 从 sandboxRoot 内查找 workspace。
            workspaceGuard.validateWorkspacePath(workspace); // 查询结果也必须经过 Guard。
            BasicFileAttributes attrs = Files.readAttributes(workspace, BasicFileAttributes.class); // 读取文件属性。
            String branchName = null; // 分支名可为空。
            if (Files.isDirectory(workspace.resolve(".git"))) {
                SafeCommandExecutor.CommandResult branch = commandExecutor.runGitInWorkspace(workspace,
                        List.of("branch", "--show-current")); // 只读查询当前分支。
                branchName = branch.isSuccess() ? branch.getStdout().trim() : null; // 成功才回填。
            }
            return buildInfo(extractWorkspaceId(workspace.getFileName().toString()), workspace.getFileName().toString(),
                    workspace, branchName, properties.getDefaultBranch(), null, STATUS_READY,
                    toLocalDateTime(attrs.creationTime().toInstant()), toLocalDateTime(attrs.lastModifiedTime().toInstant())); // 返回推断信息。
        } catch (Exception e) {
            throw new IllegalArgumentException("查询沙箱 workspace 失败: " + e.getMessage(), e); // 第一版不入库，查不到直接失败。
        }
    }

    private CopyStats populateWorkspace(SandboxWorkspaceCreateRequest request,
                                        String sourceType,
                                        String baseBranch,
                                        Path workspacePath) throws IOException {
        if (SOURCE_TYPE_GIT_CLONE.equals(sourceType)) {
            return cloneRepository(request, baseBranch, workspacePath); // Git 来源走 clone。
        }
        return copyLocalRepository(request, workspacePath); // 默认走本地只读复制。
    }

    private CopyStats copyLocalRepository(SandboxWorkspaceCreateRequest request, Path workspacePath) throws IOException {
        if (!Boolean.TRUE.equals(properties.getAllowLocalCopy())) {
            throw new IllegalStateException("allow-local-copy 未开启。"); // 配置关闭时拒绝本地复制。
        }
        Path source = workspaceGuard.validateSourceRepoForRead(request.getSourceRepoDir()); // sourceRepoDir 只能读配置目录。
        CopyStats stats = new CopyStats(); // 统计复制文件、目录、字节数。
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir); // 计算相对源码路径。
                if (shouldSkip(relative)) {
                    return FileVisitResult.SKIP_SUBTREE; // 跳过构建产物、日志、上传文件等目录。
                }
                Path target = workspacePath.resolve(relative).normalize(); // 目标路径固定在 workspace 内。
                if (!target.startsWith(workspacePath)) {
                    throw new IOException("拒绝复制到 workspace 外: " + target); // 防止路径穿越。
                }
                if (!relative.toString().isEmpty()) {
                    Files.createDirectories(target); // 创建目标目录。
                    stats.directoryCount++; // 统计目录数。
                }
                return FileVisitResult.CONTINUE; // 继续遍历。
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file); // 计算相对文件路径。
                if (shouldSkip(relative) || Files.isSymbolicLink(file)) {
                    return FileVisitResult.CONTINUE; // 跳过名单和符号链接。
                }
                Path target = workspacePath.resolve(relative).normalize(); // 拼出目标文件。
                if (!target.startsWith(workspacePath)) {
                    throw new IOException("拒绝复制到 workspace 外: " + target); // 再次防穿越。
                }
                Files.createDirectories(target.getParent()); // 确保父目录存在。
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS); // 只复制文件内容，不跟随链接。
                stats.fileCount++; // 统计文件数。
                stats.byteCount += safeSize(file); // 统计字节数。
                return FileVisitResult.CONTINUE; // 继续复制。
            }
        });
        ensureGitBaseline(workspacePath); // 跳过 .git 后，在 workspace 内初始化独立 Git 基线。
        stats.sourceType = SOURCE_TYPE_LOCAL_COPY; // 标记来源类型。
        return stats; // 返回复制统计。
    }

    private CopyStats cloneRepository(SandboxWorkspaceCreateRequest request, String baseBranch, Path workspacePath) throws IOException {
        if (!Boolean.TRUE.equals(properties.getAllowGitClone())) {
            throw new IllegalStateException("allow-git-clone 未开启。"); // 配置关闭时拒绝 clone。
        }
        String remoteUrl = firstNonBlank(request.getGitRemoteUrl(), properties.getGitRemoteUrl()); // 请求值为空时使用配置值。
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            throw new IllegalStateException("gitRemoteUrl 未配置。"); // clone 必须有远程地址。
        }
        SafeCommandExecutor.CommandResult clone = commandExecutor.runGitClone(remoteUrl, workspacePath); // clone 到沙箱 workspace。
        if (!clone.isSuccess()) {
            throw new IOException("git clone 失败: " + firstNonBlank(clone.getStderr(), clone.getStdout())); // clone 失败中断。
        }
        if (baseBranch != null && !baseBranch.isBlank()) {
            String safeBranch = workspaceGuard.sanitizeBranchName(baseBranch); // 基线分支也清洗，防止命令参数污染。
            SafeCommandExecutor.CommandResult checkout = commandExecutor.runGitInWorkspace(workspacePath,
                    List.of("checkout", safeBranch)); // 切到基线分支。
            if (!checkout.isSuccess()) {
                throw new IOException("git checkout " + safeBranch + " 失败: "
                        + firstNonBlank(checkout.getStderr(), checkout.getStdout())); // checkout 失败中断。
            }
        }
        CopyStats stats = new CopyStats(); // clone 不逐文件统计，保留命令摘要。
        stats.sourceType = SOURCE_TYPE_GIT_CLONE; // 标记来源类型。
        stats.stdoutSummary = summarizeOutput(clone.getStdout()); // 保存脱敏截断后的 stdout 摘要。
        stats.stderrSummary = summarizeOutput(clone.getStderr()); // 保存脱敏截断后的 stderr 摘要。
        return stats; // 返回 clone 结果摘要。
    }

    private void ensureGitBaseline(Path workspacePath) throws IOException {
        if (Files.isDirectory(workspacePath.resolve(".git"))) {
            return; // 已有 Git 仓库则不重复初始化。
        }
        SafeCommandExecutor.CommandResult init = commandExecutor.runGitInWorkspace(workspacePath, List.of("init")); // 初始化沙箱仓库。
        if (!init.isSuccess()) {
            throw new IOException("git init 失败: " + firstNonBlank(init.getStderr(), init.getStdout())); // init 失败中断。
        }
        SafeCommandExecutor.CommandResult add = commandExecutor.runGitInWorkspace(workspacePath, List.of("add", "-A")); // 添加复制基线。
        if (!add.isSuccess()) {
            throw new IOException("git add -A 失败: " + firstNonBlank(add.getStderr(), add.getStdout())); // add 失败中断。
        }
        SafeCommandExecutor.CommandResult commit = commandExecutor.runGitInWorkspace(workspacePath,
                List.of("-c", "user.name=Tech-Brain", "-c", "user.email=tech-brain@local",
                        "commit", "-m", "Sandbox workspace baseline")); // 提交沙箱独立基线。
        if (!commit.isSuccess() && !containsNothingToCommit(commit)) {
            throw new IOException("git commit 基线失败: " + firstNonBlank(commit.getStderr(), commit.getStdout())); // 非空失败中断。
        }
    }

    private void createBranch(Path workspacePath, String branchName) throws IOException {
        SafeCommandExecutor.CommandResult branch = commandExecutor.runGitInWorkspace(workspacePath,
                List.of("switch", "-c", branchName)); // 只在 workspace 内创建临时分支。
        if (!branch.isSuccess()) {
            throw new IOException("创建临时分支失败: " + firstNonBlank(branch.getStderr(), branch.getStdout())); // 失败明确返回。
        }
    }

    private void createWorkspaceDirectory(Path workspacePath) throws IOException {
        if (Files.exists(workspacePath) && !isDirectoryEmpty(workspacePath)) {
            throw new IllegalStateException("workspace 已存在且非空。"); // 不覆盖已有 workspace。
        }
        Files.createDirectories(workspacePath); // 创建独立 workspace 目录。
        workspaceGuard.validateWorkspacePath(workspacePath); // 创建后再次校验。
    }

    private List<Path> listManagedWorkspaces(Path sandboxRoot) throws IOException {
        List<Path> workspaces = new ArrayList<>(); // 收集受管理 workspace。
        String prefix = workspacePrefix(); // 只清理此前缀目录。
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sandboxRoot)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && child.getFileName().toString().startsWith(prefix)) {
                    workspaces.add(child.toAbsolutePath().normalize()); // 只加入 selfdev-* 目录。
                }
            }
        }
        return workspaces; // 返回候选目录。
    }

    private Set<Path> chooseCleanupTargets(List<Path> workspaces) {
        Set<Path> targets = new LinkedHashSet<>(); // 使用 Set 去重并保持顺序。
        long retentionMillis = Math.max(0, valueOrDefault(properties.getRetentionDays(), 7)) * 24L * 60L * 60L * 1000L; // 保留天数转毫秒。
        long now = System.currentTimeMillis(); // 当前时间。
        for (Path workspace : workspaces) {
            long modified = lastModifiedMillis(workspace); // 读取最后修改时间。
            if (retentionMillis > 0 && now - modified > retentionMillis) {
                targets.add(workspace); // 超过保留天数加入清理目标。
            }
        }
        int max = Math.max(1, valueOrDefault(properties.getMaxWorkspaces(), 20)); // 至少保留 1 个 workspace。
        List<Path> newestFirst = new ArrayList<>(workspaces); // 复制列表用于排序。
        newestFirst.sort(Comparator.comparingLong(this::lastModifiedMillis).reversed()); // 新的排前面。
        for (int i = max; i < newestFirst.size(); i++) {
            targets.add(newestFirst.get(i)); // 超过最大数量的旧目录加入清理目标。
        }
        return targets; // 返回最终清理目标。
    }

    private Path resolveWorkspaceById(String workspaceId) throws IOException {
        if (workspaceId == null || workspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId 不能为空。"); // 必须指定 workspace。
        }
        String id = workspaceId.trim(); // 去掉空白。
        if (id.contains("/") || id.contains("\\") || id.contains(":") || id.contains("..")) {
            throw new IllegalArgumentException("workspaceId 非法。"); // 防止路径穿越。
        }
        Path root = workspaceGuard.ensureSandboxRoot(); // 从 sandboxRoot 扫描。
        Path direct = root.resolve(id).normalize(); // 允许传 workspaceName。
        if (Files.isDirectory(direct)) {
            return workspaceGuard.validateWorkspacePath(direct); // 直接命中则返回。
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                String name = child.getFileName().toString(); // 取目录名。
                if (Files.isDirectory(child) && name.startsWith(workspacePrefix())
                        && (name.equals(id) || name.endsWith("-" + id) || name.equals(workspacePrefix() + id))) {
                    return workspaceGuard.validateWorkspacePath(child); // 匹配带唯一 ID 的 workspaceName。
                }
            }
        }
        throw new IllegalArgumentException("未找到 workspace: " + workspaceId); // 未找到。
    }

    private Path resolveWorkspacePathForValidation(String workspacePath) {
        if (workspacePath == null || workspacePath.trim().isEmpty()) {
            throw new IllegalArgumentException("workspacePath 不能为空。"); // Claude 工作目录不能为空。
        }
        Path raw = Paths.get(workspacePath.trim()); // 解析用户传入路径。
        if (raw.isAbsolute()) {
            return raw.toAbsolutePath().normalize(); // 绝对路径必须后续通过 Guard。
        }
        return workspaceGuard.toSafeWorkspacePath(workspacePath.trim()); // 相对路径只允许作为单层 workspaceName。
    }

    private boolean containsProjectMarker(Path workspace) {
        for (String marker : PROJECT_MARKERS) {
            if (Files.exists(workspace.resolve(marker))) {
                return true; // 找到任一项目标识即可。
            }
        }
        return false; // 缺少项目标识。
    }

    private void requireGitRepository(Path workspace) {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            throw new IllegalArgumentException("workspace 不是 Git 仓库，无法恢复干净状态。"); // reset/clean 必须有 .git。
        }
    }

    private boolean shouldSkip(Path relativePath) {
        if (relativePath == null || relativePath.toString().isEmpty()) {
            return false; // 根目录不跳过。
        }
        for (Path segment : relativePath) {
            String name = segment.toString(); // 取路径段。
            String lower = name.toLowerCase(Locale.ROOT); // 统一小写比较。
            if (SKIPPED_SEGMENTS.contains(name) || SKIPPED_SEGMENTS.contains(lower) || lower.contains("pycache")) {
                return true; // 命中跳过名单。
            }
        }
        return false; // 未命中则复制。
    }

    private boolean isWorkspaceInUse(Path workspace) {
        return Files.exists(workspace.resolve(".selfdev-lock")); // 第一版约定存在锁文件即视为正在使用。
    }

    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file); // 删除文件。
                return FileVisitResult.CONTINUE; // 继续遍历。
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc; // 子节点删除异常向上抛。
                }
                Files.deleteIfExists(dir); // 删除空目录。
                return FileVisitResult.CONTINUE; // 继续遍历。
            }
        });
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true; // 不存在或不是目录时视为空。
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            return !stream.iterator().hasNext(); // 没有子节点即为空。
        }
    }

    private SandboxWorkspaceInfo buildInfo(String workspaceId,
                                           String workspaceName,
                                           Path workspacePath,
                                           String branchName,
                                           String baseBranch,
                                           String sourceType,
                                           String status) {
        LocalDateTime now = LocalDateTime.now(); // 创建和更新时间使用当前时间。
        return buildInfo(workspaceId, workspaceName, workspacePath, branchName, baseBranch, sourceType, status, now, now); // 复用完整构造。
    }

    private SandboxWorkspaceInfo buildInfo(String workspaceId,
                                           String workspaceName,
                                           Path workspacePath,
                                           String branchName,
                                           String baseBranch,
                                           String sourceType,
                                           String status,
                                           LocalDateTime createdAt,
                                           LocalDateTime updatedAt) {
        SandboxWorkspaceInfo info = new SandboxWorkspaceInfo(); // 创建 DTO。
        info.setWorkspaceId(workspaceId); // 设置 workspaceId。
        info.setWorkspaceName(workspaceName); // 设置目录名。
        info.setWorkspacePath(workspacePath == null ? null : workspacePath.toString()); // 绝对路径仅内部返回。
        info.setRelativeWorkspacePath(workspacePath == null ? null : workspaceGuard.relativeToSandbox(workspacePath)); // 日志安全相对路径。
        info.setBranchName(branchName); // 设置临时分支。
        info.setBaseBranch(baseBranch); // 设置基线分支。
        info.setSourceType(sourceType); // 设置来源类型。
        info.setStatus(status); // 设置状态。
        info.setCreatedAt(createdAt); // 设置创建时间。
        info.setUpdatedAt(updatedAt); // 设置更新时间。
        return info; // 返回 DTO。
    }

    private SandboxWorkspaceInfo buildFailedInfo(String workspaceId,
                                                 String workspaceName,
                                                 Path workspacePath,
                                                 String branchName,
                                                 String baseBranch,
                                                 String sourceType) {
        return buildInfo(workspaceId, workspaceName, workspacePath, branchName, baseBranch, sourceType, STATUS_FAILED); // 构造失败信息。
    }

    private void saveWorkspaceLog(String actionType,
                                  DevActionResult result,
                                  String traceId,
                                  Long userId,
                                  Long conversationId,
                                  String title,
                                  String intent,
                                  String summary,
                                  SandboxWorkspaceInfo info,
                                  Map<String, Object> extra,
                                  Exception error) {
        Map<String, Object> payload = new LinkedHashMap<>(); // resultJson 只保存脱敏结构化信息。
        if (info != null) {
            payload.put("workspaceId", info.getWorkspaceId()); // 保存 workspaceId。
            payload.put("workspaceName", info.getWorkspaceName()); // 保存目录名。
            payload.put("relativeWorkspacePath", info.getRelativeWorkspacePath()); // 只保存相对路径。
            payload.put("branchName", info.getBranchName()); // 保存临时分支。
            payload.put("baseBranch", info.getBaseBranch()); // 保存基线分支。
            payload.put("sourceType", info.getSourceType()); // 保存来源类型。
            payload.put("status", info.getStatus()); // 保存状态。
        }
        if (extra != null) {
            payload.putAll(extra); // 合并复制/命令摘要。
        }
        saveOperationLog(actionType, result, traceId, userId, conversationId, title, intent, summary, payload, error); // 统一写日志。
    }

    private void saveOperationLog(String actionType,
                                  DevActionResult result,
                                  String traceId,
                                  Long userId,
                                  Long conversationId,
                                  String title,
                                  String intent,
                                  String summary,
                                  Map<String, Object> payload,
                                  Exception error) {
        try {
            DevActionLogCreateRequest request = new DevActionLogCreateRequest(); // 构造日志请求。
            request.setUserId(userId); // 记录用户。
            request.setConversationId(conversationId); // 记录会话。
            request.setTraceId(traceId); // 记录 traceId。
            request.setActionType(actionType); // 写入 P9 actionType。
            request.setResult(result.name()); // 写入成功/失败。
            request.setTargetType(DevTargetType.MODULE.name()); // P9 操作目标是模块级。
            request.setTargetModule(TARGET_MODULE); // 目标模块固定为 Tech-Brain。
            request.setTitle(title); // 写入标题。
            request.setIntent(intent); // 写入意图。
            request.setSummary(summary); // 写入语义摘要。
            request.setResultJson(toJson(payload)); // 写入脱敏 resultJson。
            request.setErrorMsg(error == null ? null : safeText(redactSensitive(error.getMessage()), 1000)); // 写入脱敏失败原因。
            DevActionLogSaveResult saveResult = devActionLogService.saveDevAction(request); // 调用统一日志入口。
            if (saveResult == null || !saveResult.isSaved()) {
                log.warn("[SandboxWorkspace] dev_action_log not saved, actionType: {}, reason: {}",
                        actionType, saveResult == null ? "null result" : saveResult.getErrorMessage()); // 日志失败不影响主流程。
            }
        } catch (Exception e) {
            log.warn("[SandboxWorkspace] failed to save dev_action_log: {}", e.getMessage(), e); // 保存失败只 warning。
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload); // 序列化结构化结果。
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize sandbox workspace result\"}"; // 序列化失败兜底。
        }
    }

    private String buildWorkspaceName(SandboxWorkspaceCreateRequest request, String workspaceId) {
        String prefix = workspacePrefix(); // 读取 workspace 前缀。
        String requested = request.getWorkspaceName(); // 可选自定义名称。
        String base = requested == null || requested.trim().isEmpty() ? workspaceId : request.getWorkspaceName().trim(); // 没传则用 ID。
        base = base.replaceAll("[^A-Za-z0-9._-]", "-"); // 清洗目录名。
        if (!base.startsWith(prefix)) {
            base = prefix + base; // 强制套 workspacePrefix，便于清理和识别。
        }
        if (!base.endsWith("-" + workspaceId) && !base.equals(prefix + workspaceId)) {
            base = base + "-" + workspaceId; // 保证目录名带唯一 ID。
        }
        return workspaceGuard.sanitizeWorkspaceName(base); // 最终交由 Guard 校验。
    }

    private String buildBranchName(SandboxWorkspaceCreateRequest request, String workspaceId) {
        if (request.getBranchName() != null && !request.getBranchName().trim().isEmpty()) {
            return workspaceGuard.sanitizeBranchName(request.getBranchName()); // 使用调用方指定分支名但必须清洗。
        }
        String taskPart = firstNonBlank(request.getTaskId(), workspaceId); // 优先用 taskId。
        String raw = firstNonBlank(properties.getBranchPrefix(), "selfdev/")
                + taskPart + "-" + LocalDateTime.now().format(BRANCH_TIME_FORMATTER); // 生成 selfdev/<task>-<timestamp>。
        return workspaceGuard.sanitizeBranchName(raw); // 清洗 Git 分支名。
    }

    private String resolveSourceType(SandboxWorkspaceCreateRequest request) {
        String raw = firstNonBlank(request.getSourceType(), SOURCE_TYPE_LOCAL_COPY); // 默认 LOCAL_COPY。
        String normalized = raw.trim().toUpperCase(Locale.ROOT); // 统一大写。
        if (!SOURCE_TYPE_LOCAL_COPY.equals(normalized) && !SOURCE_TYPE_GIT_CLONE.equals(normalized)) {
            throw new IllegalArgumentException("sourceType 只能是 LOCAL_COPY 或 GIT_CLONE。"); // 非法来源拒绝。
        }
        return normalized; // 返回规范值。
    }

    private boolean shouldCreateBranch(SandboxWorkspaceCreateRequest request) {
        return request.getCreateBranch() == null || Boolean.TRUE.equals(request.getCreateBranch()); // 默认创建临时分支。
    }

    private String workspacePrefix() {
        return firstNonBlank(properties.getWorkspacePrefix(), "selfdev-"); // workspace 前缀兜底。
    }

    private String createWorkspaceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12); // 短 ID 足够目录识别。
    }

    private String extractWorkspaceId(String workspaceName) {
        String name = workspaceName == null ? "" : workspaceName; // 兜底空串。
        int dash = name.lastIndexOf('-'); // workspaceName 末尾通常是 -id。
        return dash >= 0 && dash < name.length() - 1 ? name.substring(dash + 1) : name; // 提取尾部 ID。
    }

    private boolean containsNothingToCommit(SafeCommandExecutor.CommandResult result) {
        String output = (result.getStdout() + "\n" + result.getStderr()).toLowerCase(Locale.ROOT); // 合并输出。
        return output.contains("nothing to commit") || output.contains("no changes added to commit"); // 识别空提交。
    }

    private String summarizeOutput(String output) {
        String redacted = redactSensitive(output == null ? "" : output); // 先脱敏。
        return safeText(redacted, LOG_OUTPUT_LIMIT); // 再截断。
    }

    private String redactSensitive(String text) {
        if (text == null) {
            return null; // 空文本无需脱敏。
        }
        String result = URL_CREDENTIAL_PATTERN.matcher(text).replaceAll("$1***@"); // 脱敏 URL user/token。
        result = URL_TOKEN_QUERY_PATTERN.matcher(result).replaceAll("$1=***"); // 脱敏 query token。
        result = COMMON_SECRET_PATTERN.matcher(result).replaceAll("***"); // 脱敏常见长 token。
        return WINDOWS_ABSOLUTE_PATH_PATTERN.matcher(result).replaceAll("[absolute-path-redacted]"); // 脱敏服务器绝对路径。
    }

    private String safeRelativeOrName(String raw) {
        if (raw == null) {
            return null; // 空值直接返回。
        }
        String normalized = raw.trim().replace('\\', '/'); // 统一分隔符。
        if (normalized.matches("^[A-Za-z]:/.*") || normalized.startsWith("/")) {
            return "[absolute-path-redacted]"; // 绝对路径不写入日志。
        }
        return safeText(normalized, 500); // 相对名称可保存。
    }

    private String safeText(String text, int maxLength) {
        if (text == null) {
            return null; // 空值保持为空。
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength); // 超长截断。
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null; // 没有候选值。
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim(); // 返回第一个非空值。
            }
        }
        return null; // 全部为空。
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value; // Integer 兜底。
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis(); // 读取最后修改时间。
        } catch (IOException e) {
            return 0L; // 读取失败时视为最旧。
        }
    }

    private long safeSize(Path file) {
        try {
            return Files.size(file); // 读取文件大小。
        } catch (IOException e) {
            return 0L; // 读取失败按 0 处理。
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()); // 文件时间转本地时间。
    }

    private static class CopyStats {
        private String sourceType; // 来源类型。
        private long fileCount; // 复制文件数。
        private long directoryCount; // 复制目录数。
        private long byteCount; // 复制字节数。
        private String stdoutSummary; // 命令 stdout 摘要。
        private String stderrSummary; // 命令 stderr 摘要。

        private Map<String, Object> toLogMap() {
            Map<String, Object> map = new LinkedHashMap<>(); // 构造日志 payload。
            map.put("sourceType", sourceType); // 写来源。
            map.put("fileCount", fileCount); // 写文件数。
            map.put("directoryCount", directoryCount); // 写目录数。
            map.put("byteCount", byteCount); // 写字节数。
            map.put("stdoutSummary", stdoutSummary); // 写 stdout 摘要。
            map.put("stderrSummary", stderrSummary); // 写 stderr 摘要。
            return map; // 返回 payload。
        }
    }
}
