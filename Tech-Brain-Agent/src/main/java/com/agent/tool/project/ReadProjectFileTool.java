package com.agent.tool.project;

import com.agent.config.ProjectWorkspaceProperties;
import com.agent.security.ProjectPathGuard;
import com.agent.toolcalling.project.language.CodeLanguage;
import com.agent.toolcalling.project.language.CodeLanguageRegistry;
import com.agent.toolcalling.support.AbstractAiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * readProjectFile 项目代码文件读取工具。
 *
 * <p>适用场景：当用户在聊天中要求读取、打开、查看某个项目 workspace 内的代码文件或文本文件时，
 * Tool Calling 调用本工具按相对 workspace 路径读取文件内容，并把截断后的内容返回给模型生成最终回答。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 识别模型 tool_call 或后端强制 readProjectFile 路由
 * -> ToolRegistry 根据工具名获取 ReadProjectFileTool
 * -> execute(arguments) 解析 path/maxChars/readMode
 * -> ProjectPathGuard 统一执行 workspace 边界、路径穿越、敏感目录、敏感文件、扩展名和文件大小校验
 * -> BufferedReader 按 UTF-8 限制读取内容
 * -> 返回 project_file_read JSON 给模型和 tool_call_log。</p>
 *
 * <p>边界说明：本工具属于 Tech-Brain-Agent 项目代码业务工具，不放入 Tech-Brain-Tool 公共模块；
 * 本工具不修改项目文件、不生成 patch、不应用 patch、不做 AST 解析、不接入 RAG/Milvus/向量化，
 * 不读取 workspace 外路径，不返回服务器绝对路径，不读取密钥、证书、二进制或压缩包。</p>
 */
@Slf4j // 输出 [ReadProjectFileTool] 前缀日志，不打印文件正文和服务器绝对路径。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动发现 readProjectFile 工具。
public class ReadProjectFileTool extends AbstractAiTool { // 项目代码业务工具，继承公共工具基类复用参数 Schema 和 JSON 辅助能力。
    private static final String TOOL_NAME = "readProjectFile"; // 工具名称必须和模型 tool_call.function.name 一致。
    private static final String RESULT_TYPE = "project_file_read"; // 工具返回 JSON 类型。
    private static final int DEFAULT_MAX_CHARS = 50000; // 配置缺失时默认最多返回 50000 字符。
    private static final int MAX_ALLOWED_CHARS = 200000; // 单次最多允许返回 200000 字符，仍受文件大小限制保护。
    private static final int MAX_FILENAME_MATCHES = 20; // 只按文件名定位时最多返回20个候选，避免大项目扫描结果过多。
    private static final String READ_MODE_SUMMARY = "SUMMARY"; // 默认分析模式，读取内容后交给模型总结。
    private static final String READ_MODE_FULL = "FULL"; // 完整输出模式，后端最终回答直接输出代码块。

    private final ProjectPathGuard projectPathGuard; // P4.1 workspace 路径安全守卫。
    private final ProjectWorkspaceProperties projectWorkspaceProperties; // P4.1 项目工作区配置，用于读取 maxReadChars 默认值。

    public ReadProjectFileTool(ProjectPathGuard projectPathGuard,
                               ProjectWorkspaceProperties projectWorkspaceProperties) { // 构造器注入依赖，保持和现有项目 Tool 风格一致。
        this.projectPathGuard = projectPathGuard; // 保存路径安全守卫。
        this.projectWorkspaceProperties = projectWorkspaceProperties; // 保存项目工作区配置。
    }

    @Override // 实现 AiTool 工具名。
    public String name() {
        return TOOL_NAME; // 固定返回 readProjectFile。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return "读取项目 workspace 内指定代码或文本文件内容。必须提供相对于 workspace 的文件路径。只能读取安全的代码/文本文件，禁止读取敏感配置、密钥、证书、二进制文件和 workspace 外路径。readMode=FULL 时用于完整代码输出，大文件会按字符数截断。"; // 给模型判断调用时机。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "path", createStringProperty("相对于项目 workspace 的文件路径，例如 Tech-Brain-Agent/src/main/java/com/xxx/AgentController.java"), true); // path 必填且必须是相对路径。
        addProperty(schema, "maxChars", createIntegerProperty("最多读取的字符数，可选，默认 50000，最大 200000"), false); // maxChars 可选。
        addProperty(schema, "readMode", createStringProperty("读取模式，可选：SUMMARY 或 FULL。默认 SUMMARY；用户要求完整代码时使用 FULL"), false); // readMode 可选，便于日志追踪完整输出场景。
        return schema; // 返回完整参数 Schema。
    }

    @Override // 执行 readProjectFile 工具。
    public String execute(JsonNode arguments) {
        String requestedPath = trimToNull(getOptionalText(arguments, "path", null)); // 读取相对 workspace 的文件路径。
        if (requestedPath == null) { // path 缺失时直接失败。
            log.warn("[ReadProjectFileTool] read failed, path: {}, reason: {}", null, "请提供要读取的项目文件路径。"); // 不打印完整参数。
            return buildFailureResult("", "请提供要读取的项目文件路径。"); // 返回结构化失败 JSON。
        }

        int maxChars = resolveMaxChars(arguments); // 解析并限制 maxChars。
        String readMode = resolveReadMode(arguments); // 解析读取模式，FULL 用于后端确定性输出完整代码块。
        log.info("[ReadProjectFileTool] read project file, path: {}, maxChars: {}, readMode: {}", safeRelativePath(requestedPath), maxChars, readMode); // 只打印相对路径和模式。
        try {
            Path filePath = resolveReadableProjectFilePath(requestedPath); // 解析明确路径，或在文件名/类名场景下安全唯一定位项目文件。
            projectPathGuard.validateReadableCodeFile(filePath); // 统一校验 workspace、敏感路径、扩展名、普通文件和大小限制。
            ProjectFileReadContent readContent = readTextWithLimit(filePath, maxChars); // 按 UTF-8 限制读取内容。
            log.info("[ReadProjectFileTool] read success, path: {}, returnedChars: {}, truncated: {}",
                    toWorkspaceRelativePath(filePath), readContent.returnedChars(), readContent.truncated()); // 成功日志不打印正文。
            return buildSuccessResult(filePath, readContent, maxChars, readMode); // 返回结构化成功 JSON。
        } catch (ProjectPathGuard.ProjectPathAccessException e) {
            log.warn("[ReadProjectFileTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // 安全校验失败不打印堆栈。
            return buildFailureResult(requestedPath, normalizeFailureMessage(e.getMessage())); // 返回友好失败 JSON。
        } catch (ProjectFileReadException e) {
            log.warn("[ReadProjectFileTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), e.getMessage()); // 受控读取失败不打印堆栈。
            return buildFailureResult(requestedPath, e.getMessage()); // 返回结构化失败 JSON。
        } catch (Exception e) {
            log.error("[ReadProjectFileTool] read failed, path: {}, reason: {}", safeRelativePath(requestedPath), "项目文件读取失败", e); // 系统错误保留堆栈但不打印正文。
            return buildFailureResult(requestedPath, "项目文件读取失败，请稍后重试。"); // 返回友好失败 JSON。
        }
    }

    private Path resolveReadableProjectFilePath(String requestedPath) { // 解析用户传入的路径，支持文件名/Java类名唯一定位。
        Path directPath = projectPathGuard.resolveProjectPath(requestedPath); // 必须先走 P4.1 路径解析，防止绝对路径和路径穿越。
        if (Files.exists(directPath, LinkOption.NOFOLLOW_LINKS) || !isFilenameOnly(requestedPath)) { // 明确路径或根目录文件存在时直接返回。
            return directPath; // 后续交给 validateReadableCodeFile 做完整安全校验。
        }

        String fileName = normalizeFileNameCandidate(requestedPath); // 文件名直传时允许 ChatMessageServiceImpl 自动补成 ChatMessageServiceImpl.java。
        String extension = projectPathGuard.getExtension(fileName); // 提取候选扩展名，先拦截敏感或非代码类型。
        if (projectPathGuard.isBlockedFilename(fileName) || projectPathGuard.isBlockedExtension(extension)) { // 敏感文件名或敏感扩展名不能进入全 workspace 搜索。
            throw new ProjectPathGuard.ProjectPathAccessException("该文件属于敏感文件，禁止读取。"); // 返回统一敏感文件文案。
        }
        if (!extension.isEmpty() && !projectPathGuard.isAllowedExtension(extension)) { // 明确扩展名但不在白名单。
            throw new ProjectPathGuard.ProjectPathAccessException("不支持读取该文件类型。"); // 返回统一不支持类型文案。
        }
        List<Path> matches = findProjectFilesByName(fileName); // 在 workspace 内安全搜索同名项目文件。
        if (matches.isEmpty()) { // 未找到任何安全候选。
            throw new ProjectPathGuard.ProjectPathAccessException("未在 workspace 中找到 " + fileName + "，请先使用 searchCode 定位文件位置或提供完整相对路径。"); // 友好提示先定位。
        }
        if (matches.size() > 1) { // 同名文件不止一个时不能乱读。
            throw new ProjectPathGuard.ProjectPathAccessException(buildAmbiguousFileMessage(fileName, matches)); // 返回相对路径候选，不暴露绝对路径。
        }
        return matches.get(0); // 唯一匹配时进入读取流程。
    }

    private String normalizeFileNameCandidate(String requestedPath) { // 将文件名或类名候选标准化为具体文件名。
        String fileName = trimToNull(requestedPath); // 去掉自然语言路由传入的空白。
        if (fileName == null) { // 理论上外层已校验，兜底。
            return ""; // 返回空字符串。
        }
        if (projectPathGuard.isBlockedFilename(fileName)) { // 敏感文件名不能被自动补后缀绕过。
            return fileName; // 原样交给候选搜索和后续 Guard 拒绝。
        }
        if (!fileName.contains(".") && fileName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { // 类名直传且没有扩展名。
            return fileName + ".java"; // 默认按 Java 类名补 .java。
        }
        return fileName; // 其它文件名原样使用。
    }

    private List<Path> findProjectFilesByName(String fileName) { // 在 workspace 内按文件名搜索安全候选。
        List<Path> matches = new ArrayList<>(); // 保存最多20个相对安全候选。
        String normalizedFileName = trimToNull(fileName); // 标准化文件名。
        if (normalizedFileName == null) { // 空文件名无法搜索。
            return matches; // 返回空列表。
        }
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // 获取 workspace 根路径。
        if (!Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) { // workspace 不存在或不是目录。
            return matches; // 返回空列表，让上层给出未找到提示。
        }
        collectProjectFilesByName(workspaceRoot, normalizedFileName, matches); // 递归收集安全候选。
        matches.sort(Comparator.comparing(this::toWorkspaceRelativePath, String.CASE_INSENSITIVE_ORDER)); // 返回前按相对路径排序，结果稳定。
        return matches; // 返回安全候选。
    }

    private void collectProjectFilesByName(Path directory,
                                           String fileName,
                                           List<Path> matches) { // 递归扫描目录，跳过敏感目录和非代码文件。
        if (matches.size() >= MAX_FILENAME_MATCHES || !isSafeDirectory(directory)) { // 达到上限或目录不安全时停止。
            return; // 不进入递归。
        }
        try (Stream<Path> stream = Files.list(directory)) { // 只列当前目录，递归由本方法控制。
            List<Path> children = stream
                    .sorted(Comparator.comparing(path -> path.getFileName() == null ? "" : path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList(); // 目录项稳定排序，便于候选顺序稳定。
            for (Path child : children) { // 遍历当前目录子项。
                if (matches.size() >= MAX_FILENAME_MATCHES) { // 达到候选上限。
                    return; // 停止扫描。
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) { // 子目录。
                    collectProjectFilesByName(child, fileName, matches); // 安全目录继续递归。
                    continue; // 处理下一个子项。
                }
                if (isSafeFileNameMatch(child, fileName)) { // 安全文件且文件名匹配。
                    matches.add(child.toAbsolutePath().normalize()); // 保存标准化路径，后续仍会完整 Guard 校验。
                }
            }
        } catch (IOException e) {
            log.debug("[ReadProjectFileTool] skip unreadable directory while resolving file name"); // 不打印目录绝对路径。
        }
    }

    private boolean isSafeDirectory(Path directory) { // 判断目录是否可进入扫描。
        if (directory == null) { // 空目录不安全。
            return false; // 返回 false。
        }
        Path normalizedPath = directory.toAbsolutePath().normalize(); // 标准化目录路径。
        return projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && Files.isDirectory(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 必须在 workspace 内、非软链、非敏感目录且是目录。
    }

    private boolean isSafeFileNameMatch(Path filePath, String fileName) { // 判断文件是否安全且文件名精确匹配。
        if (filePath == null || filePath.getFileName() == null) { // 空路径或无文件名不匹配。
            return false; // 返回 false。
        }
        Path normalizedPath = filePath.toAbsolutePath().normalize(); // 标准化文件路径。
        String candidateName = filePath.getFileName().toString(); // 只取文件名，不使用路径正文。
        String extension = projectPathGuard.getExtension(candidateName); // 提取扩展名。
        return candidateName.equalsIgnoreCase(fileName)
                && projectPathGuard.isInsideWorkspace(normalizedPath)
                && !Files.isSymbolicLink(normalizedPath)
                && !projectPathGuard.isSensitivePath(normalizedPath)
                && !projectPathGuard.isBlockedFilename(candidateName)
                && !projectPathGuard.isBlockedExtension(extension)
                && projectPathGuard.isAllowedExtension(extension)
                && Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS); // 只允许安全白名单文本/代码文件。
    }

    private String buildAmbiguousFileMessage(String fileName, List<Path> matches) { // 构造同名文件多匹配提示。
        StringBuilder builder = new StringBuilder("我找到了多个 ").append(fileName).append("，请指定要读取哪一个："); // 不自动选择，避免读错文件。
        int index = 1; // 候选序号。
        for (Path match : matches) { // 输出最多20个候选。
            builder.append('\n').append(index++).append(". ").append(toWorkspaceRelativePath(match)); // 只输出相对 workspace 路径。
        }
        return builder.toString(); // 返回友好提示。
    }

    private int resolveMaxChars(JsonNode arguments) { // 解析 maxChars 参数。
        int defaultMaxChars = resolveDefaultMaxChars(); // 默认值优先来自 techbrain.project.max-read-chars。
        int maxChars = getOptionalInt(arguments, "maxChars", defaultMaxChars); // 未传时使用配置默认值。
        if (maxChars <= 0) { // 非法值使用默认值。
            return defaultMaxChars; // 返回默认上限。
        }
        return Math.min(maxChars, MAX_ALLOWED_CHARS); // 超过最大值时强制限制到 200000。
    }

    private String resolveReadMode(JsonNode arguments) { // 解析 readMode 参数。
        String readMode = getOptionalText(arguments, "readMode", READ_MODE_SUMMARY); // 未传时默认 SUMMARY。
        if (readMode == null || readMode.isBlank()) { // 空值兜底。
            return READ_MODE_SUMMARY; // 返回默认分析模式。
        }
        String normalizedMode = readMode.trim().toUpperCase(Locale.ROOT); // 模式统一大写。
        return READ_MODE_FULL.equals(normalizedMode) ? READ_MODE_FULL : READ_MODE_SUMMARY; // 只接受 FULL，其它全部按 SUMMARY。
    }

    private int resolveDefaultMaxChars() { // 解析配置默认读取字符数。
        Integer configuredValue = projectWorkspaceProperties == null ? null : projectWorkspaceProperties.getMaxReadChars(); // 读取 techbrain.project.max-read-chars。
        if (configuredValue == null || configuredValue <= 0) { // 配置缺失或非法时兜底。
            return DEFAULT_MAX_CHARS; // 返回 50000。
        }
        return Math.min(configuredValue, MAX_ALLOWED_CHARS); // 配置值也不能超过硬上限。
    }

    private ProjectFileReadContent readTextWithLimit(Path filePath, int maxChars) { // 按字符限制读取 UTF-8 文本。
        StringBuilder builder = new StringBuilder(Math.min(maxChars, DEFAULT_MAX_CHARS)); // 只缓存要返回的内容。
        int readChars = 0; // 已扫描字符数。
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) { // 第一版只支持 UTF-8。
            int value; // 当前字符。
            while ((value = reader.read()) != -1) { // 逐字符读取，避免一次性读完整文件。
                readChars++; // 记录已读取字符。
                if (readChars <= maxChars) { // 未超过返回上限。
                    builder.append((char) value); // 加入返回内容。
                    continue; // 继续读取下一个字符。
                }
                return new ProjectFileReadContent(builder.toString(), true, builder.length(), -1); // 超过上限立即停止读取。
            }
            return new ProjectFileReadContent(builder.toString(), false, builder.length(), builder.length()); // 未截断时 totalChars 准确。
        } catch (MalformedInputException e) {
            throw new ProjectFileReadException("文件编码暂不支持，请确认文件为 UTF-8 文本。", e); // 编码错误返回友好提示。
        } catch (IOException e) {
            throw new ProjectFileReadException("项目文件读取失败，请稍后重试。", e); // IO 错误返回友好提示。
        }
    }

    private String buildSuccessResult(Path filePath,
                                      ProjectFileReadContent readContent,
                                      int maxChars,
                                      String readMode) { // 构造成功 JSON。
        String fileName = filePath.getFileName() == null ? "" : filePath.getFileName().toString(); // 获取文件名。
        String extension = projectPathGuard.getExtension(fileName); // 获取小写扩展名。
        CodeLanguage codeLanguage = CodeLanguageRegistry.resolveByFileName(fileName); // 统一识别文件语言和 Markdown 名称。
        ObjectNode result = objectMapper.createObjectNode(); // 创建 JSON 对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", true); // 标记成功。
        result.put("path", toWorkspaceRelativePath(filePath)); // 只返回相对 workspace 路径。
        result.put("fileName", fileName); // 返回文件名。
        result.put("extension", extension); // 返回扩展名。
        result.put("language", codeLanguage.getDisplayName()); // 返回注册表展示语言名。
        result.put("markdownName", codeLanguage.getMarkdownName()); // 返回 Markdown code fence 语言名。
        result.put("fileSize", readFileSize(filePath)); // 返回文件字节大小。
        result.put("charset", StandardCharsets.UTF_8.name()); // 返回读取编码。
        result.put("readMode", readMode == null || readMode.isBlank() ? READ_MODE_SUMMARY : readMode); // 返回读取模式，便于 Tool 日志追踪。
        result.put("truncated", readContent.truncated()); // 标记是否截断。
        result.put("returnedChars", readContent.returnedChars()); // 返回实际字符数。
        result.put("content", readContent.content()); // 返回截断后的内容片段。
        if (readContent.truncated()) { // 截断时补充说明。
            result.put("message", "文件内容较长，已截断返回前 " + maxChars + " 个字符。"); // 提醒模型内容不是完整文件。
        }
        return result.toString(); // 返回结构化 JSON 字符串。
    }

    private String buildFailureResult(String requestedPath, String message) { // 构造失败 JSON。
        ObjectNode result = objectMapper.createObjectNode(); // 创建 JSON 对象。
        result.put("type", RESULT_TYPE); // 写入结果类型。
        result.put("success", false); // 标记失败。
        result.put("path", safeRelativePath(requestedPath)); // 只返回用户传入的相对路径形态。
        result.put("message", message == null || message.isBlank() ? "项目文件读取失败，请稍后重试。" : message); // 写入友好错误。
        return result.toString(); // 返回结构化 JSON 字符串。
    }

    private long readFileSize(Path filePath) { // 读取文件大小元信息。
        try {
            return Files.size(filePath); // 只读取元信息，不读取正文。
        } catch (IOException e) {
            return -1L; // 读取失败时用 -1 兜底。
        }
    }

    private String toWorkspaceRelativePath(Path path) { // 将绝对路径转换为相对 workspace 路径。
        if (path == null) { // 空路径兜底。
            return ""; // 返回空字符串。
        }
        Path workspaceRoot = projectPathGuard.getWorkspaceRoot(); // 获取 workspace 根目录。
        Path normalizedPath = path.toAbsolutePath().normalize(); // 标准化目标路径。
        if (!normalizedPath.startsWith(workspaceRoot)) { // 理论上已由 Guard 校验，兜底避免泄漏。
            return ""; // 不返回绝对路径。
        }
        return workspaceRoot.relativize(normalizedPath).toString().replace('\\', '/'); // 返回统一斜杠相对路径。
    }

    private String safeRelativePath(String path) { // 标准化日志和 JSON 中的路径。
        String normalizedPath = trimToNull(path); // 去除首尾空白。
        return normalizedPath == null ? "" : normalizedPath.replace('\\', '/'); // 不做绝对路径展开。
    }

    private String normalizeFailureMessage(String message) { // 规范化失败文案。
        if (message == null || message.isBlank()) { // 错误文案缺失时兜底。
            return "项目文件读取失败，请稍后重试。"; // 返回友好错误。
        }
        if (message.contains("workspace 外")) { // 路径穿越或绝对路径统一文案。
            return "不允许访问 workspace 外的路径。"; // 不暴露真实路径。
        }
        return message; // 其它受控错误保持原文案。
    }

    private boolean isFilenameOnly(String path) { // 判断用户是否只传了文件名。
        String normalizedPath = trimToNull(path); // 标准化输入。
        if (normalizedPath == null) { // 空路径不是文件名。
            return false; // 返回 false。
        }
        return !normalizedPath.contains("/") && !normalizedPath.contains("\\"); // 不含任何路径分隔符即视为文件名。
    }

    private String trimToNull(String value) { // 将空白字符串统一转换为 null。
        if (value == null || value.trim().isEmpty()) { // null 或空白。
            return null; // 返回 null。
        }
        return value.trim(); // 返回去除首尾空白后的文本。
    }

    private record ProjectFileReadContent(String content,
                                          boolean truncated,
                                          int returnedChars,
                                          long totalChars) { // 项目文件读取结果，保留可返回内容和截断状态。
    }

    private static class ProjectFileReadException extends RuntimeException { // 受控项目文件读取异常。
        private ProjectFileReadException(String message, Throwable cause) { // 携带底层异常的构造器。
            super(message, cause); // 保存友好错误和原始异常。
        }
    }
}
