package com.agent.tool.project;

import com.agent.config.ProjectWorkspaceProperties;
import com.agent.security.ProjectPathGuard;
import com.agent.toolcalling.support.AbstractAiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * listProjectTree 项目目录树读取工具。
 *
 * <p>适用场景：当用户在聊天中要求查看项目目录结构、文件树、模块结构，或查看 workspace 内某个目录下有哪些文件时，
 * 由 Tool Calling 调用本工具读取目录树元信息。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 识别模型 tool_call 或后端轻量强制路由
 * -> ToolRegistry 根据工具名获取 ListProjectTreeTool
 * -> execute(arguments) 解析 path/maxDepth/maxNodes
 * -> ProjectPathGuard 校验 workspace 边界和敏感路径
 * -> Files.list 递归读取目录和安全文件元信息
 * -> 返回结构化 JSON 给模型和 tool_call_log。</p>
 *
 * <p>边界说明：本工具位于 Tech-Brain-Agent 项目代码业务模块，不放入 Tech-Brain-Tool 公共模块；
 * 本工具不读取文件内容，不做代码语法解析，不接入 RAG/Milvus/向量化，不修改数据库，不返回服务器绝对路径。</p>
 */
@Slf4j // 输出 [ListProjectTreeTool] 前缀日志，不打印服务器绝对路径和文件内容。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动发现 listProjectTree 工具。
public class ListProjectTreeTool extends AbstractAiTool { // 项目代码业务工具，继承公共工具基类复用参数 Schema 和 JSON 辅助能力。

    private static final String TOOL_NAME = "listProjectTree"; // 工具名称必须和模型 tool_call.function.name 一致。

    private static final String RESULT_TYPE = "project_tree"; // 工具返回 JSON 类型。

    private static final int DEFAULT_MAX_DEPTH = 4; // 默认目录树最大深度。

    private static final int MAX_ALLOWED_DEPTH = 8; // 最大允许目录树深度。

    private static final int DEFAULT_MAX_NODES = 300; // 默认最多返回节点数量。

    private static final int MAX_ALLOWED_NODES = 1000; // 最大允许返回节点数量。

    private static final long DEFAULT_MAX_FILE_SIZE_KB = 512L; // ProjectWorkspaceProperties 缺失时的默认文件大小限制。

    private static final String NODE_TYPE_DIRECTORY = "DIRECTORY"; // 目录节点类型。

    private static final String NODE_TYPE_FILE = "FILE"; // 文件节点类型。

    private final ProjectPathGuard projectPathGuard; // P4.1 路径安全守卫，负责 workspace 边界和敏感路径判断。

    private final ProjectWorkspaceProperties projectWorkspaceProperties; // P4.1 工作区配置，读取文件大小限制。

    public ListProjectTreeTool(ProjectPathGuard projectPathGuard,
                               ProjectWorkspaceProperties projectWorkspaceProperties) { // 构造器注入依赖，保持与现有 Tool 风格一致。
        this.projectPathGuard = projectPathGuard; // 保存路径安全守卫。
        this.projectWorkspaceProperties = projectWorkspaceProperties; // 保存项目工作区配置。
    }

    @Override // 实现 AiTool 工具名。
    public String name() {
        return TOOL_NAME; // 固定返回 listProjectTree。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return "读取项目 workspace 内指定目录的目录树结构，仅返回安全的目录和代码/文本文件元信息，不读取文件内容。只能访问 workspace 内路径，会过滤敏感目录、敏感文件和不支持的文件类型。"; // 给模型判断调用时机。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "path", createStringProperty("相对于项目 workspace 的目录路径，可选。为空时读取 workspace 根目录。示例：demo 或 demo/src/main/java"), false); // path 可选且必须是相对路径。
        addProperty(schema, "maxDepth", createIntegerProperty("目录树最大深度，可选，默认 4，最大 8"), false); // maxDepth 可选。
        addProperty(schema, "maxNodes", createIntegerProperty("最多返回节点数量，可选，默认 300，最大 1000"), false); // maxNodes 可选。
        return schema; // 返回完整参数 Schema，required 为空。
    }

    @Override // 执行 listProjectTree 工具。
    public String execute(JsonNode arguments) {
        String requestedPath = getOptionalText(arguments, "path", ""); // 读取相对 workspace 的目录路径。
        int maxDepth = resolveMaxDepth(arguments); // 解析目录树最大深度。
        int maxNodes = resolveMaxNodes(arguments); // 解析最多返回节点数量。
        log.info("[ListProjectTreeTool] list project tree, path: {}, maxDepth: {}, maxNodes: {}",
                safeRelativePath(requestedPath), maxDepth, maxNodes); // 只打印相对路径和限制参数。
        try {
            Path targetPath = resolveTargetPath(requestedPath); // 解析并校验目标路径必须位于 workspace 内。
            validateDirectoryTarget(targetPath); // 校验目标必须存在且是普通目录。
            ScanState scanState = new ScanState(maxNodes); // 初始化扫描状态，用于节点数量截断。
            ProjectTreeNode tree = buildDirectoryNode(targetPath, 0, maxDepth, scanState); // 递归构造目录树节点。
            return buildSuccessResult(requestedPath, maxDepth, maxNodes, scanState, tree); // 返回结构化成功 JSON。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[ListProjectTreeTool] list failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // 受控路径错误不打印堆栈。
            return buildFailureResult(requestedPath, normalizeFailureMessage(e.getMessage())); // 返回结构化失败 JSON。
        } catch (Exception e) {
            log.error("[ListProjectTreeTool] list failed, path: {}, reason: {}", safeRelativePath(requestedPath), "目录树读取失败", e); // 系统错误记录堆栈但不打印绝对路径。
            return buildFailureResult(requestedPath, "目录树读取失败，请稍后重试。"); // 返回友好失败 JSON。
        }
    }

    private Path resolveTargetPath(String requestedPath) { // 解析工具入参 path。
        String normalizedPath = trimToNull(requestedPath); // 空字符串视为 workspace 根目录。
        if (normalizedPath == null) { // 未指定 path 时读取 workspace 根目录。
            return projectPathGuard.getWorkspaceRoot(); // 返回 workspace 根目录绝对归一化路径。
        }
        return projectPathGuard.resolveProjectPath(normalizedPath); // 非空 path 必须走 P4.1 安全解析。
    }

    private void validateDirectoryTarget(Path targetPath) { // 校验目标目录是否允许读取目录树。
        if (targetPath == null) { // 理论上不会为空，兜底保护。
            throw new ProjectPathGuard.ProjectPathAccessException("路径不能为空。"); // 返回路径为空错误。
        }
        Path normalizedPath = targetPath.toAbsolutePath().normalize(); // 归一化目标路径。
        if (!projectPathGuard.isInsideWorkspace(normalizedPath)) { // 必须位于 workspace 内。
            throw new ProjectPathGuard.ProjectPathAccessException("不允许访问 workspace 外的路径。"); // 防止路径穿越。
        }
        if (projectPathGuard.isSensitivePath(normalizedPath)) { // 目标目录本身不能是敏感目录或敏感路径。
            throw new ProjectPathGuard.ProjectPathAccessException("目录属于敏感目录，禁止读取。"); // 敏感目录直接拒绝。
        }
        if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) { // 目标必须存在。
            throw new ProjectPathGuard.ProjectPathAccessException("路径不存在。"); // 不暴露真实路径。
        }
        if (Files.isSymbolicLink(normalizedPath)) { // 符号链接可能绕过 workspace，必须拒绝。
            throw new ProjectPathGuard.ProjectPathAccessException("目标不是目录。"); // 统一按非法目录处理。
        }
        if (!Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS)) { // listProjectTree 只处理目录。
            throw new ProjectPathGuard.ProjectPathAccessException("listProjectTree 只支持目录，请使用后续 readProjectFile 读取文件。"); // 文件读取留给后续工具。
        }
    }

    private ProjectTreeNode buildDirectoryNode(Path directoryPath,
                                               int currentDepth,
                                               int maxDepth,
                                               ScanState scanState) { // 递归构造目录节点。
        if (!scanState.tryAddNode()) { // 节点数超过上限时停止扫描。
            return null; // 返回 null 表示该节点不再加入树。
        }
        ProjectTreeNode node = new ProjectTreeNode(); // 创建目录节点。
        node.name = resolveNodeName(directoryPath); // 写入目录名，不包含服务器绝对路径。
        node.path = toWorkspaceRelativePath(directoryPath); // 写入相对 workspace 路径。
        node.type = NODE_TYPE_DIRECTORY; // 标记为目录。
        node.children = new ArrayList<>(); // 初始化子节点列表。
        if (currentDepth >= maxDepth) { // 达到目录深度上限时不继续递归。
            node.truncated = Boolean.TRUE; // 标记该目录因为深度限制未展开。
            node.reason = "已达到最大目录深度"; // 给模型和日志可读原因。
            return node; // 返回当前目录节点。
        }

        List<Path> childPaths = listSafeChildren(directoryPath); // 获取已过滤和排序后的安全子路径。
        for (Path childPath : childPaths) { // 遍历子目录和文件。
            if (scanState.isLimitReached()) { // 达到节点数量上限时停止扫描。
                scanState.markTruncated(); // 标记顶层结果被截断。
                break; // 停止继续遍历。
            }
            ProjectTreeNode childNode = buildChildNode(childPath, currentDepth + 1, maxDepth, scanState); // 构造子节点。
            if (childNode != null) { // 子节点通过过滤和数量限制时加入。
                node.children.add(childNode); // 添加到当前目录 children。
            }
        }
        return node; // 返回目录节点。
    }

    private ProjectTreeNode buildChildNode(Path childPath,
                                           int childDepth,
                                           int maxDepth,
                                           ScanState scanState) { // 根据路径类型构造子节点。
        if (isSafeDirectory(childPath)) { // 安全目录继续递归。
            return buildDirectoryNode(childPath, childDepth, maxDepth, scanState); // 构造目录节点。
        }
        if (isSafeReturnableFile(childPath)) { // 安全项目代码/文本文件返回元信息。
            return buildFileNode(childPath, scanState); // 构造文件节点。
        }
        return null; // 不支持文件、敏感路径和其它类型直接过滤。
    }

    private ProjectTreeNode buildFileNode(Path filePath, ScanState scanState) { // 构造文件节点，只返回元信息。
        if (!scanState.tryAddNode()) { // 节点数超过上限时不加入。
            return null; // 返回 null。
        }
        ProjectTreeNode node = new ProjectTreeNode(); // 创建文件节点。
        String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 获取文件名。
        long fileSize = readFileSize(filePath); // 读取文件大小，不读取文件内容。
        node.name = fileName; // 写入文件名。
        node.path = toWorkspaceRelativePath(filePath); // 写入相对 workspace 路径。
        node.type = NODE_TYPE_FILE; // 标记为文件。
        node.extension = projectPathGuard.getExtension(fileName); // 写入小写扩展名。
        node.size = fileSize >= 0 ? fileSize : null; // 写入文件大小，读取失败时为空。
        node.readable = fileSize >= 0 && fileSize <= resolveMaxFileSizeBytes(); // 文件过大时标记不可读。
        if (Boolean.FALSE.equals(node.readable)) { // 不可读时写入原因。
            node.reason = fileSize < 0 ? "文件大小读取失败" : "文件过大"; // 区分大小读取失败和超过限制。
        }
        return node; // 返回文件元信息节点。
    }

    private List<Path> listSafeChildren(Path directoryPath) { // 列出目录下可返回的安全子路径。
        try (Stream<Path> stream = Files.list(directoryPath)) { // 只读取目录项，不读取文件内容。
            return stream
                    .filter(this::isSafeTreeEntry) // 过滤敏感目录、敏感文件和不支持类型。
                    .sorted(pathComparator()) // 目录在前、文件在后、按名称排序。
                    .toList(); // Java 17 返回不可变列表。
        } catch (IOException e) {
            log.warn("[ListProjectTreeTool] list directory failed, relativePath: {}, reason: {}",
                    toWorkspaceRelativePath(directoryPath), e.getMessage()); // 只打印相对路径。
            return List.of(); // 子目录读取失败时返回空 children，避免泄露绝对路径。
        }
    }

    private boolean isSafeTreeEntry(Path path) { // 判断目录项是否允许出现在目录树中。
        if (path == null || !projectPathGuard.isInsideWorkspace(path)) { // 路径为空或不在 workspace 内直接过滤。
            return false; // 不返回。
        }
        if (Files.isSymbolicLink(path) || projectPathGuard.isSensitivePath(path)) { // 符号链接和敏感路径直接过滤。
            return false; // 不返回。
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { // 安全目录允许返回。
            return true; // 目录后续递归。
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { // 普通文件继续判断扩展名。
            return isSafeReturnableFile(path); // 只返回安全代码/文本文件。
        }
        return false; // 其它特殊文件不返回。
    }

    private boolean isSafeDirectory(Path path) { // 判断是否为允许递归的安全目录。
        return path != null
                && projectPathGuard.isInsideWorkspace(path)
                && !Files.isSymbolicLink(path)
                && !projectPathGuard.isSensitivePath(path)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS); // 目录必须在 workspace 内、非敏感、非符号链接。
    }

    private boolean isSafeReturnableFile(Path path) { // 判断文件是否允许作为目录树文件节点返回。
        if (path == null || !projectPathGuard.isInsideWorkspace(path)) { // 文件必须在 workspace 内。
            return false; // 不返回。
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { // 只允许普通文件。
            return false; // 不返回。
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString(); // 获取文件名。
        String extension = projectPathGuard.getExtension(fileName); // 获取扩展名。
        if (projectPathGuard.isBlockedFilename(fileName) || projectPathGuard.isBlockedExtension(extension)) { // 命中敏感文件名或扩展名直接过滤。
            return false; // 不返回。
        }
        if (!projectPathGuard.isAllowedExtension(extension)) { // 不在代码/文本白名单内直接过滤。
            return false; // 不返回。
        }
        return !projectPathGuard.isSensitivePath(path); // 最后复用敏感路径规则兜底。
    }

    private Comparator<Path> pathComparator() { // 构造目录树排序规则。
        return (left, right) -> {
            boolean leftDirectory = Files.isDirectory(left, LinkOption.NOFOLLOW_LINKS); // 判断左侧是否目录。
            boolean rightDirectory = Files.isDirectory(right, LinkOption.NOFOLLOW_LINKS); // 判断右侧是否目录。
            if (leftDirectory != rightDirectory) { // 目录和文件混排时目录优先。
                return leftDirectory ? -1 : 1; // 目录在前。
            }
            String leftName = left.getFileName() == null ? "" : left.getFileName().toString().toLowerCase(Locale.ROOT); // 左侧名称小写。
            String rightName = right.getFileName() == null ? "" : right.getFileName().toString().toLowerCase(Locale.ROOT); // 右侧名称小写。
            return leftName.compareTo(rightName); // 同类型按名称字母序排序。
        };
    }

    private int resolveMaxDepth(JsonNode arguments) { // 解析 maxDepth 参数。
        int maxDepth = getOptionalInt(arguments, "maxDepth", DEFAULT_MAX_DEPTH); // 缺省为 4。
        if (maxDepth <= 0) { // 非法值使用默认值。
            return DEFAULT_MAX_DEPTH; // 返回默认深度。
        }
        return Math.min(maxDepth, MAX_ALLOWED_DEPTH); // 超过最大值时限制到 8。
    }

    private int resolveMaxNodes(JsonNode arguments) { // 解析 maxNodes 参数。
        int maxNodes = getOptionalInt(arguments, "maxNodes", DEFAULT_MAX_NODES); // 缺省为 300。
        if (maxNodes <= 0) { // 非法值使用默认值。
            return DEFAULT_MAX_NODES; // 返回默认节点数。
        }
        return Math.min(maxNodes, MAX_ALLOWED_NODES); // 超过最大值时限制到 1000。
    }

    private long resolveMaxFileSizeBytes() { // 解析配置中的文件大小上限。
        Integer maxFileSizeKb = projectWorkspaceProperties == null ? null : projectWorkspaceProperties.getMaxFileSizeKb(); // 读取配置。
        long safeMaxFileSizeKb = maxFileSizeKb == null || maxFileSizeKb <= 0 ? DEFAULT_MAX_FILE_SIZE_KB : maxFileSizeKb; // 配置缺失时兜底。
        return safeMaxFileSizeKb * 1024L; // KB 转为字节。
    }

    private long readFileSize(Path filePath) { // 读取文件大小。
        try {
            return Files.size(filePath); // 只读取文件元信息，不读取内容。
        } catch (IOException e) {
            log.warn("[ListProjectTreeTool] read file size failed, relativePath: {}, reason: {}",
                    toWorkspaceRelativePath(filePath), e.getMessage()); // 只打印相对路径。
            return -1L; // 读取失败时用 -1 兜底。
        }
    }

    private String resolveNodeName(Path path) { // 解析节点名称。
        Path fileName = path == null ? null : path.getFileName(); // 取路径最后一级。
        return fileName == null ? "." : fileName.toString(); // workspace 根目录名称缺失时用点号。
    }

    private String toWorkspaceRelativePath(Path path) { // 将绝对路径转换为相对 workspace 路径。
        if (path == null) { // 空路径兜底。
            return ""; // 返回空字符串。
        }
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // 获取 workspace 根目录。
        Path normalizedPath = path.toAbsolutePath().normalize(); // 归一化待返回路径。
        if (workspaceRoot.equals(normalizedPath)) { // workspace 根目录不返回绝对路径。
            return "."; // 用点号表示根目录。
        }
        if (!normalizedPath.startsWith(workspaceRoot)) { // 理论上已被过滤，兜底避免泄露绝对路径。
            return ""; // 返回空字符串。
        }
        return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/'); // 返回统一斜杠的相对路径。
    }

    private String safeRelativePath(String path) { // 标准化日志和失败 JSON 中的路径。
        String normalizedPath = trimToNull(path); // 去掉空白。
        return normalizedPath == null ? "." : normalizedPath.replace('\\', '/'); // 空路径用点号表示 workspace 根目录。
    }

    private String trimToNull(String value) { // 将空白字符串统一转换为 null。
        if (value == null || value.trim().isEmpty()) { // null 或空白字符串。
            return null; // 返回 null。
        }
        return value.trim(); // 返回去除首尾空白后的字符串。
    }

    private String normalizeFailureMessage(String message) { // 规范化失败文案。
        if (message == null || message.isBlank()) { // 错误文案缺失时兜底。
            return "目录树读取失败，请稍后重试。"; // 返回友好错误。
        }
        if (message.contains("workspace 外")) { // 路径穿越和绝对路径统一文案。
            return "不允许访问 workspace 外的路径。"; // 不暴露文件或路径细节。
        }
        return message; // 其它受控错误保持原文案。
    }

    private String buildSuccessResult(String requestedPath,
                                      int maxDepth,
                                      int maxNodes,
                                      ScanState scanState,
                                      ProjectTreeNode tree) { // 构造成功 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 创建顶层结果。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", true); // 标记成功。
        result.put("rootPath", safeRelativePath(requestedPath)); // 写入请求根路径，空路径为点号。
        result.put("maxDepth", maxDepth); // 写入实际使用的最大深度。
        result.put("maxNodes", maxNodes); // 写入实际使用的最大节点数。
        result.put("nodeCount", scanState.nodeCount); // 写入实际返回节点数量。
        result.put("truncated", scanState.truncated); // 写入是否因 maxNodes 截断。
        if (scanState.truncated) { // 节点数截断时给出提示。
            result.put("message", "目录树较大，已截断返回前 " + maxNodes + " 个节点。"); // 截断说明。
        }
        result.set("tree", toJsonNode(tree)); // 写入目录树节点。
        return result.toString(); // 返回结构化 JSON 字符串。
    }

    private String buildFailureResult(String requestedPath, String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 创建顶层结果。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", false); // 标记失败。
        result.put("path", safeRelativePath(requestedPath)); // 返回相对路径，不暴露绝对路径。
        result.put("message", message); // 写入友好错误。
        return result.toString(); // 返回结构化 JSON 字符串。
    }

    private ObjectNode toJsonNode(ProjectTreeNode node) { // 将内部节点对象转换为 JSON。
        ObjectNode jsonNode = objectMapper.createObjectNode(); // 创建节点 JSON。
        if (node == null) { // 节点为空时返回空对象。
            return jsonNode; // 返回空 JSON。
        }
        jsonNode.put("name", node.name); // 写入节点名称。
        jsonNode.put("path", node.path); // 写入相对 workspace 路径。
        jsonNode.put("type", node.type); // 写入 DIRECTORY / FILE。
        if (node.extension != null) { // 文件节点才有扩展名。
            jsonNode.put("extension", node.extension); // 写入扩展名。
        }
        if (node.size != null) { // 文件节点才有大小。
            jsonNode.put("size", node.size); // 写入文件字节数。
        }
        if (node.readable != null) { // 文件节点写入是否可读。
            jsonNode.put("readable", node.readable); // 写入 readable。
        }
        if (node.truncated != null) { // 目录节点达到深度限制时写入。
            jsonNode.put("truncated", node.truncated); // 写入节点级截断状态。
        }
        if (node.reason != null && !node.reason.isBlank()) { // 有不可读或截断原因时写入。
            jsonNode.put("reason", node.reason); // 写入原因。
        }
        if (NODE_TYPE_DIRECTORY.equals(node.type)) { // 目录节点写入 children。
            ArrayNode children = objectMapper.createArrayNode(); // 创建 children 数组。
            for (ProjectTreeNode child : node.children == null ? List.<ProjectTreeNode>of() : node.children) { // 遍历子节点。
                children.add(toJsonNode(child)); // 递归转换子节点。
            }
            jsonNode.set("children", children); // 写入 children。
        }
        return jsonNode; // 返回节点 JSON。
    }

    private static class ProjectTreeNode { // 目录树内部节点，只用于构造工具返回 JSON。
        private String name; // 节点名称。
        private String path; // 相对 workspace 路径。
        private String type; // DIRECTORY 或 FILE。
        private String extension; // 文件扩展名。
        private Long size; // 文件大小，单位字节。
        private Boolean readable; // 文件是否允许后续读取。
        private Boolean truncated; // 目录节点是否因深度限制未展开。
        private String reason; // 不可读或截断原因。
        private List<ProjectTreeNode> children; // 目录子节点。
    }

    private static class ScanState { // 扫描状态，用于控制 maxNodes。
        private final int maxNodes; // 最大节点数量。
        private int nodeCount; // 已返回节点数量。
        private boolean truncated; // 是否因节点数量达到上限而截断。

        private ScanState(int maxNodes) { // 构造扫描状态。
            this.maxNodes = maxNodes; // 保存节点上限。
        }

        private boolean tryAddNode() { // 尝试登记一个节点。
            if (nodeCount >= maxNodes) { // 已达到节点上限。
                truncated = true; // 标记顶层截断。
                return false; // 不再允许添加节点。
            }
            nodeCount++; // 记录一个返回节点。
            return true; // 允许添加节点。
        }

        private boolean isLimitReached() { // 判断是否达到节点上限。
            return nodeCount >= maxNodes; // 返回限制状态。
        }

        private void markTruncated() { // 手动标记截断。
            truncated = true; // 设置截断标记。
        }
    }
}
