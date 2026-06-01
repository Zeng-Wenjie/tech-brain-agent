package com.agent.tool.file;

import com.agent.config.FileUploadProperties;
import com.agent.entity.UserFile;
import com.agent.service.UserFileService;
import com.agent.toolcalling.context.ConversationFocusService;
import com.agent.toolcalling.context.ToolCallingContextHolder;
import com.agent.toolcalling.context.ToolCallingRequestContext;
import com.agent.toolcalling.support.AbstractAiTool;
import com.agent.utils.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

/**
 * readFile 用户文件读取工具。
 *
 * <p>适用场景：当用户在聊天中要求读取、查看或分析当前已上传的文本/代码类文件时，由 Tool Calling 调用本工具，
 * 按 fileId 读取当前登录用户自己的文件内容。</p>
 * <p>调用链：ToolCallingChatServiceImpl 解析模型 tool_call -> ToolRegistry 获取 readFile
 * -> ReadFileTool.execute(arguments) -> UserFileService.getByIdAndUserId(fileId, userId)
 * -> 本地文件系统按 UTF-8 限制读取 -> 结构化 JSON 返回给模型和 tool_call_log。</p>
 * <p>边界说明：本工具位于 Tech-Brain-Agent 文件业务模块，不放入 Tech-Brain-Tool 公共模块；
 * 本工具不修改数据库，不执行 SQL，不解析 PDF/Word/图片，不接入 RAG/Milvus/向量化，不返回 storagePath 或 storedName。</p>
 */
@Slf4j // 输出 [ReadFileTool] 前缀日志，不打印文件内容和真实路径。
@Component // 注册为 Spring Bean，让 ToolRegistry 自动发现 readFile 工具。
public class ReadFileTool extends AbstractAiTool { // 文件业务工具，继承公共工具基类复用参数 Schema 和 JSON 辅助能力。

    private static final String TOOL_NAME = "readFile"; // 工具名称必须和模型 tool_call.function.name 一致。

    private static final String RESULT_TYPE = "file_read"; // 工具返回 JSON 类型。

    private static final int DEFAULT_MAX_CHARS = 8000; // 默认最多返回 8000 字符。

    private static final int MAX_ALLOWED_CHARS = 20000; // 最大最多返回 20000 字符。

    private static final String DEFAULT_UPLOAD_DIR = "./data/uploads"; // FileUploadProperties 缺失时的上传根目录。

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of( // 第一版只支持文本和代码类文件。
            "txt",
            "md",
            "java",
            "vue",
            "sql",
            "py",
            "js",
            "ts",
            "json",
            "xml",
            "html",
            "css",
            "yml",
            "yaml",
            "properties"
    );

    private final UserFileService userFileService; // 用户文件服务，用于按 userId + fileId 校验权限。

    private final FileUploadProperties fileUploadProperties; // 文件上传配置，用于读取 uploadDir 做路径安全校验。
    private final ConversationFocusService conversationFocusService; // 会话焦点服务，用于保存最近成功读取的文件焦点。

    public ReadFileTool(UserFileService userFileService,
                        FileUploadProperties fileUploadProperties,
                        ConversationFocusService conversationFocusService) { // 构造器注入业务依赖，避免字段注入。
        this.userFileService = userFileService; // 保存用户文件服务。
        this.fileUploadProperties = fileUploadProperties; // 保存文件上传配置。
        this.conversationFocusService = conversationFocusService; // 保存会话焦点服务。
    }

    @Override // 实现 AiTool 工具名。
    public String name() {
        return TOOL_NAME; // 固定返回 readFile。
    }

    @Override // 实现 AiTool 工具描述。
    public String description() {
        return "读取当前用户已上传的文本或代码文件内容。必须提供 fileId。仅支持 txt、md、java、vue、sql、py、js、ts、json、xml、html、css、yml、yaml、properties 等文本类文件。不支持 PDF、Word、图片等二进制文件解析。"; // 给模型判断调用时机。
    }

    @Override // 实现 AiTool 参数 Schema。
    public ObjectNode parametersSchema() {
        ObjectNode schema = createObjectSchema(); // 创建顶层 object schema。
        addProperty(schema, "fileId", createIntegerProperty("要读取的用户文件ID"), true); // fileId 必填，不能从参数传 userId。
        addProperty(schema, "maxChars", createIntegerProperty("最多读取的字符数，可选，默认8000，最大20000"), false); // maxChars 可选。
        return schema; // 返回完整参数 Schema。
    }

    @Override // 执行 readFile 工具。
    public String execute(JsonNode arguments) {
        Long fileId = readFileId(arguments); // 从模型参数读取 fileId。
        if (fileId == null) { // fileId 缺失或非法。
            log.warn("[ReadFileTool] read failed, fileId: {}, reason: {}", null, "请提供要读取的文件ID。"); // 不打印参数全文。
            return buildFailureResult(null, "请提供要读取的文件ID。"); // 返回结构化失败 JSON。
        }

        int maxChars = resolveMaxChars(arguments); // 读取并限制 maxChars。
        Long userId = resolveCurrentUserId(); // 用户身份只来自后端上下文。
        log.info("[ReadFileTool] read file, userId: {}, fileId: {}", userId, fileId); // 读取开始日志。
        try {
            if (userId == null) { // 缺少登录用户。
                throw new FileReadException("未登录或登录状态失效。"); // 返回友好失败。
            }

            UserFile userFile = userFileService.getByIdAndUserId(fileId, userId); // 强制 id + user_id + status=1 查询。
            if (userFile == null) { // 文件不存在、已失效或越权。
                throw new FileReadException("文件不存在或无权访问。"); // 不泄露其它用户文件是否存在。
            }

            String fileExt = normalizeFileExt(userFile.getFileExt()); // 标准化扩展名。
            if (!SUPPORTED_EXTENSIONS.contains(fileExt)) { // 只允许文本/代码文件直接读取。
                throw new FileReadException("该文件类型暂不支持直接读取，请后续使用文件解析功能。"); // PDF/Word/图片走后续解析能力。
            }

            Path filePath = resolveSafeFilePath(userFile); // 校验 storagePath 在 uploadDir 下且物理文件存在。
            FileReadContent readContent = readTextWithLimit(filePath, maxChars); // 按 UTF-8 限制读取内容。
            log.info("[ReadFileTool] read success, fileId: {}, returnedChars: {}, truncated: {}",
                    fileId, readContent.returnedChars(), readContent.truncated()); // 成功日志不打印正文。
            saveActiveFileFocus(userFile, userId); // readFile 成功后保存当前会话 activeFileFocus。
            return buildSuccessResult(userFile, fileExt, readContent, maxChars); // 返回结构化 JSON。
        } catch (FileReadException e) {
            log.warn("[ReadFileTool] read failed, fileId: {}, reason: {}", fileId, e.getMessage()); // 受控错误不打印堆栈。
            return buildFailureResult(fileId, e.getMessage()); // 返回结构化失败 JSON。
        } catch (Exception e) {
            log.error("[ReadFileTool] read failed, fileId: {}, reason: {}", fileId, "文件读取失败", e); // 系统错误记录堆栈但不打印路径。
            return buildFailureResult(fileId, "文件读取失败，请稍后重试。"); // 返回友好失败 JSON。
        }
    }

    private Long readFileId(JsonNode arguments) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) { // arguments 为空时无法读取。
            return null; // 返回 null。
        }
        JsonNode fileIdNode = arguments.path("fileId"); // 参数名必须是 fileId。
        if (fileIdNode.isMissingNode() || fileIdNode.isNull()) { // 未传 fileId。
            return null; // 返回 null。
        }
        if (fileIdNode.isNumber()) { // 模型按 integer 输出。
            long value = fileIdNode.asLong(); // 读取 long。
            return value > 0 ? value : null; // fileId 必须为正数。
        }
        String fileIdText = fileIdNode.asText(); // 兼容模型把 fileId 输出为字符串。
        if (fileIdText == null || fileIdText.trim().isEmpty()) { // 空字符串非法。
            return null; // 返回 null。
        }
        try {
            long value = Long.parseLong(fileIdText.trim()); // 解析字符串 ID。
            return value > 0 ? value : null; // fileId 必须为正数。
        } catch (NumberFormatException e) {
            return null; // 非数字字符串非法。
        }
    }

    private int resolveMaxChars(JsonNode arguments) {
        int maxChars = getOptionalInt(arguments, "maxChars", DEFAULT_MAX_CHARS); // 缺省 8000。
        if (maxChars <= 0) { // 非法值使用默认值。
            return DEFAULT_MAX_CHARS; // 返回默认上限。
        }
        return Math.min(maxChars, MAX_ALLOWED_CHARS); // 超过最大值时强制截断到 20000。
    }

    private Long resolveCurrentUserId() {
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 优先读取 Tool Calling 上下文。
        if (context != null && context.getUserId() != null) { // 上下文中有用户 ID。
            return context.getUserId(); // 返回上下文用户 ID。
        }
        return UserContext.getUserId(); // 兜底读取当前线程登录用户。
    }

    private Path resolveSafeFilePath(UserFile userFile) {
        String storagePath = trimToNull(userFile == null ? null : userFile.getStoragePath()); // 读取数据库中的物理路径。
        if (storagePath == null) { // storagePath 缺失。
            throw new FileReadException("文件不存在。"); // 不暴露服务器路径。
        }

        Path basePath = resolveBasePath(); // 上传根目录绝对路径。
        Path storedPath = Paths.get(storagePath); // 数据库存储路径。
        Path filePath = storedPath.toAbsolutePath().normalize(); // 先按当前运行目录绝对化。
        if (!filePath.startsWith(basePath) && !storedPath.isAbsolute()) { // 兼容 storagePath 只存相对用户目录的情况。
            filePath = basePath.resolve(storedPath).normalize(); // 相对路径按 uploadDir 解析。
        }
        if (!filePath.startsWith(basePath)) { // 文件路径必须位于 uploadDir 下。
            throw new FileReadException("非法文件路径。"); // 拒绝路径穿越，不暴露真实路径。
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) { // 文件不存在或不是普通文件。
            throw new FileReadException("文件不存在。"); // 不暴露真实路径。
        }
        return filePath; // 返回安全物理路径。
    }

    private Path resolveBasePath() {
        String uploadDir = fileUploadProperties == null ? null : fileUploadProperties.getUploadDir(); // 读取 techbrain.file.upload-dir。
        if (uploadDir == null || uploadDir.trim().isEmpty()) { // 配置缺失时使用默认值。
            uploadDir = DEFAULT_UPLOAD_DIR; // 默认上传目录。
        }
        return Paths.get(uploadDir).toAbsolutePath().normalize(); // 返回绝对归一化路径。
    }

    private FileReadContent readTextWithLimit(Path filePath, int maxChars) {
        StringBuilder builder = new StringBuilder(Math.min(maxChars, DEFAULT_MAX_CHARS)); // 只缓存需要返回的字符数。
        int readChars = 0; // 已读取字符计数。
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) { // 第一版只支持 UTF-8。
            int value; // 当前字符。
            while ((value = reader.read()) != -1) { // 按字符读取，避免一次性读完整文件。
                readChars++; // 统计已扫描字符数。
                if (readChars <= maxChars) { // 未超过返回上限。
                    builder.append((char) value); // 加入返回内容。
                    continue; // 继续读取。
                }
                return new FileReadContent(builder.toString(), true, builder.length(), -1); // 超过上限立即停止，避免读完整大文件。
            }
            return new FileReadContent(builder.toString(), false, builder.length(), builder.length()); // 未截断时总字符数准确。
        } catch (MalformedInputException e) {
            throw new FileReadException("文件编码暂不支持，请确认文件为 UTF-8 文本。", e); // 编码错误返回友好提示。
        } catch (IOException e) {
            throw new FileReadException("文件读取失败，请稍后重试。", e); // 其它 IO 错误返回友好提示。
        }
    }

    private void saveActiveFileFocus(UserFile userFile, Long userId) {
        ToolCallingRequestContext context = ToolCallingContextHolder.get(); // 从Tool Calling上下文读取conversationId。
        if (userFile == null || userId == null || context == null || context.getConversationId() == null) { // 缺少必要上下文时不保存焦点。
            return; // readFile本身已经成功，不因焦点保存缺失失败。
        }
        try {
            conversationFocusService.saveActiveFileFocus(
                    userId,
                    context.getConversationId(),
                    userFile.getId(),
                    userFile.getOriginalName(),
                    userFile.getFileExt(),
                    userFile.getFileType(),
                    userFile.getMimeType(),
                    userFile.getFileSize(),
                    TOOL_NAME); // 只保存元信息，不保存storagePath或文件内容。
        } catch (Exception e) {
            log.warn("[ReadFileTool] save active file focus failed, fileId: {}, reason: {}",
                    userFile.getId(), e.getMessage(), e); // 焦点保存失败不影响文件读取结果。
        }
    }

    private String buildSuccessResult(UserFile userFile,
                                      String fileExt,
                                      FileReadContent readContent,
                                      int maxChars) {
        ObjectNode result = objectMapper.createObjectNode(); // 使用 ObjectNode 构造 JSON，避免手拼转义错误。
        result.put("type", RESULT_TYPE); // 写入工具结果类型。
        result.put("success", true); // 标记成功。
        result.put("fileId", userFile.getId()); // 返回文件 ID。
        result.put("fileName", safeText(userFile.getOriginalName())); // 返回原始文件名，不返回 storedName。
        result.put("fileExt", fileExt); // 返回扩展名。
        result.put("fileType", safeText(userFile.getFileType())); // 返回业务文件类型。
        result.put("mimeType", safeText(userFile.getMimeType())); // 返回 MIME 类型。
        putLong(result, "fileSize", userFile.getFileSize()); // 返回文件大小。
        result.put("charset", StandardCharsets.UTF_8.name()); // 当前读取编码。
        result.put("truncated", readContent.truncated()); // 是否截断。
        result.put("totalChars", readContent.totalChars()); // 总字符数，截断时为 -1。
        result.put("returnedChars", readContent.returnedChars()); // 实际返回字符数。
        result.put("content", readContent.content()); // 返回截断后的文件内容。
        if (readContent.truncated()) { // 截断时补充说明。
            result.put("message", "文件内容较长，已截断返回前 " + maxChars + " 个字符。"); // 提示模型内容已截断。
        }
        return result.toString(); // 返回结构化 JSON 字符串，会进入 tool_call_log。
    }

    private String buildFailureResult(Long fileId, String message) {
        ObjectNode result = objectMapper.createObjectNode(); // 构造失败 JSON。
        result.put("type", RESULT_TYPE); // 写入工具结果类型。
        result.put("success", false); // 标记失败。
        if (fileId == null) { // fileId 缺失。
            result.putNull("fileId"); // 保留字段稳定性。
        } else {
            result.put("fileId", fileId); // 写入请求的文件 ID。
        }
        result.put("message", message); // 写入友好错误。
        return result.toString(); // 返回结构化失败 JSON。
    }

    private void putLong(ObjectNode node, String fieldName, Long value) {
        if (value == null) { // Long 缺失时写 null。
            node.putNull(fieldName); // 保持字段存在。
            return; // 结束。
        }
        node.put(fieldName, value); // 写入 long 值。
    }

    private String normalizeFileExt(String fileExt) {
        String normalizedExt = trimToNull(fileExt); // 去掉空白。
        if (normalizedExt == null) { // 扩展名缺失。
            return ""; // 返回空字符串。
        }
        normalizedExt = normalizedExt.toLowerCase(Locale.ROOT); // 统一小写。
        return normalizedExt.startsWith(".") ? normalizedExt.substring(1) : normalizedExt; // 兼容带点扩展名。
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) { // null 或空白视为空。
            return null; // 返回 null。
        }
        return value.trim(); // 返回去掉首尾空白后的文本。
    }

    private String safeText(String value) {
        String normalizedValue = trimToNull(value); // 标准化展示字段。
        return normalizedValue == null ? "" : normalizedValue; // JSON 字段用空字符串兜底。
    }

    private record FileReadContent(String content,
                                   boolean truncated,
                                   int returnedChars,
                                   long totalChars) { // 文件读取结果，只保留可返回内容和截断状态。
    }

    private static class FileReadException extends RuntimeException { // 受控文件读取异常。

        private FileReadException(String message) {
            super(message); // 保存友好错误。
        }

        private FileReadException(String message, Throwable cause) {
            super(message, cause); // 保存友好错误和原始异常。
        }
    }
}
