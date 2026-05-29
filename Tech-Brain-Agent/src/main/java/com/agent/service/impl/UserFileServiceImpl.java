package com.agent.service.impl;

import com.agent.config.FileUploadProperties;
import com.agent.entity.UserFile;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.UserFilePageRequest;
import com.agent.entity.vo.UserFileVO;
import com.agent.mapper.UserFileMapper;
import com.agent.service.UserFileService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户文件服务实现。
 *
 * <p>适用场景：负责用户文件上传的基础后端流程，包括参数校验、本地目录创建、安全文件名生成、物理文件保存、
 * MD5 计算、user_file 表写入、单条查询和列表查询。</p>
 * <p>调用链：UserFileController -> uploadFile -> 本地文件系统 + saveUserFile -> UserFileMapper -> user_file 表；
 * 后续文件详情或列表接口 -> getByIdAndUserId/listByUserId，并始终叠加 userId 与 status=1 做用户隔离。</p>
 * <p>边界说明：本实现只保存文件本体和元数据，并提供下载/预览前的权限查询；不创建或修改数据库结构，不执行 SQL 脚本，
 * 不解析文件内容，不接入 AI、RAG、Tool Calling、向量化或 Milvus。</p>
 */
@Slf4j // 输出 [UserFile] 前缀日志，避免打印完整 storagePath 等过长敏感内容。
@Service // 注册为 Spring Bean，供后续文件业务注入使用。
public class UserFileServiceImpl extends ServiceImpl<UserFileMapper, UserFile> implements UserFileService { // 用户文件基础持久化服务实现。

    private static final int DEFAULT_MAX_SIZE_MB = 20; // 默认单文件最大 20MB。

    private static final String DEFAULT_UPLOAD_DIR = "./data/uploads"; // 默认本地上传根目录。

    private static final int DEFAULT_PAGE_NUM = 1; // 默认第一页。

    private static final int DEFAULT_PAGE_SIZE = 10; // 默认每页 10 条。

    private static final int MAX_PAGE_SIZE = 100; // 文件分页最大每页 100 条。

    private static final int NORMAL_STATUS = 1; // 正常文件状态，查询时只返回 status=1 的记录。

    private static final String DEFAULT_STORAGE_TYPE = "LOCAL"; // 默认本地存储类型。

    private static final String DEFAULT_UPLOAD_SOURCE = "USER_UPLOAD"; // 默认用户上传来源。

    private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd"); // 日期目录格式。

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp"); // 图片类扩展名集合。

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "md"); // 文档类扩展名集合。

    @Autowired
    private FileUploadProperties fileUploadProperties; // 注入本地文件上传配置。

    @Override // 上传用户文件并写入 user_file。
    public UserFileVO uploadFile(MultipartFile file) {
        Long userId = UserContext.getUserId(); // 文件归属必须来自登录上下文。
        String originalName = safeOriginalName(file); // 提前取安全展示名，用于日志和后续校验。
        try {
            validateLoginUser(userId); // 未登录用户不允许上传文件。
            validateBasicFile(file, originalName); // 校验文件非空和原始文件名。
            validateFileSize(file); // 校验文件大小不超过配置上限。

            String fileExt = extractAndValidateExtension(originalName); // 提取并校验扩展名。
            String mimeType = normalizeMimeType(file.getContentType()); // 标准化 MIME 类型，空值允许只依赖扩展名。
            validateMimeType(mimeType); // contentType 非空时必须在允许列表内。

            log.info("[UserFile] upload file, userId: {}, originalName: {}, size: {}",
                    userId, originalName, file.getSize()); // 记录上传开始，不打印真实路径和文件内容。

            Path basePath = resolveBasePath(); // 解析并规范化上传根目录。
            Path userDateDir = resolveUserDateDir(basePath, userId); // 构造用户隔离目录和日期目录。
            String storedName = buildStoredName(fileExt); // 使用后端 UUID 生成真实存储文件名。
            Path targetPath = resolveTargetPath(basePath, userDateDir, storedName); // 构造并校验最终文件路径。
            SavedLocalFile savedLocalFile = saveFileAndCalculateMd5(file, userDateDir, targetPath); // 保存物理文件并计算 MD5。

            UserFile userFile = buildUserFile(userId, originalName, storedName, fileExt, mimeType,
                    file.getSize(), savedLocalFile); // 构造 user_file 入库对象。
            try {
                saveUserFile(userFile); // 写入 user_file 表，失败时进入清理逻辑。
            } catch (Exception dbException) {
                cleanupPhysicalFile(savedLocalFile.getPath()); // 数据库保存失败时尽量删除已保存物理文件。
                throw new IllegalStateException("文件保存失败，请稍后重试", dbException); // 不向前端暴露数据库或路径细节。
            }

            log.info("[UserFile] file saved, userId: {}, fileId: {}", userId, userFile.getId()); // 保存成功日志只打印用户和文件 ID。
            return toVO(userFile); // 返回不含 storagePath 的安全文件信息。
        } catch (IllegalArgumentException e) {
            log.warn("[UserFile] upload failed, userId: {}, originalName: {}, error: {}",
                    userId, originalName, e.getMessage()); // 参数类失败只记录原因，不打印堆栈。
            throw e; // 交给 Controller 转换为统一错误格式。
        } catch (IllegalStateException e) {
            log.error("[UserFile] upload failed, userId: {}, originalName: {}",
                    userId, originalName, e); // 文件系统或数据库失败记录堆栈，便于排查。
            throw e; // 交给 Controller 转换为统一错误格式。
        }
    }

    @Override // 分页查询当前登录用户自己的文件。
    public PageDTO<UserFileVO> pageMyFiles(UserFilePageRequest request) {
        Long currentUserId = UserContext.getUserId(); // 用户隔离只信任登录上下文。
        if (currentUserId == null) { // 没有登录用户时不允许查询文件列表。
            throw new IllegalArgumentException("未登录或登录状态失效"); // 交给 Controller 返回统一未登录错误。
        }

        UserFilePageRequest safeRequest = request == null ? new UserFilePageRequest() : request; // 请求为空时使用默认查询参数。
        log.info("[UserFile] page my files, userId: {}", currentUserId); // 只打印用户 ID，不打印筛选详情或路径。
        Page<UserFile> page = new Page<>(resolvePageNum(safeRequest.getPageNum()), resolvePageSize(safeRequest.getPageSize())); // 构造 MP 分页对象。
        Page<UserFile> resultPage = page(page, buildMyFilePageWrapper(safeRequest, currentUserId)); // 执行分页查询。

        PageDTO<UserFileVO> pageDTO = new PageDTO<>(); // 封装项目统一分页结果。
        pageDTO.setTotal(resultPage.getTotal()); // 设置总记录数。
        pageDTO.setPages(resultPage.getPages()); // 设置总页数。
        pageDTO.setList(resultPage.getRecords().stream().map(this::toVO).collect(Collectors.toList())); // 转为不含 storagePath 的 VO。
        return pageDTO; // 返回分页结果。
    }

    @Override // 查询当前登录用户自己的文件详情。
    public UserFileVO getMyFileDetail(Long id) {
        Long currentUserId = UserContext.getUserId(); // 用户隔离只信任登录上下文。
        if (currentUserId == null) { // 没有登录用户时不允许查询详情。
            throw new IllegalArgumentException("未登录或登录状态失效"); // 交给 Controller 返回统一未登录错误。
        }
        if (id == null) { // 文件 ID 为空时无法定位文件。
            throw new IllegalArgumentException("文件不存在或无权访问"); // 统一隐藏不存在和越权细节。
        }

        log.info("[UserFile] get file detail, userId: {}, fileId: {}", currentUserId, id); // 只打印用户 ID 和文件 ID。
        UserFile userFile = getOne(new LambdaQueryWrapper<UserFile>() // 按当前用户和文件 ID 查询。
                .eq(UserFile::getId, id) // 限定文件 ID。
                .eq(UserFile::getUserId, currentUserId) // 强制当前用户隔离。
                .eq(UserFile::getStatus, NORMAL_STATUS) // 只查询正常状态文件。
                .last("LIMIT 1")); // 明确只取一条。
        if (userFile == null) { // 查不到表示文件不存在或不属于当前用户。
            throw new IllegalArgumentException("文件不存在或无权访问"); // 不泄露其它用户文件是否存在。
        }
        return toVO(userFile); // 查到时返回不包含 storagePath 的安全 VO。
    }

    @Override // 下载当前登录用户自己的原始文件。
    public void downloadFile(Long id, HttpServletResponse response) {
        writeFileResponse(id, response, false); // attachment 方式写入文件响应。
    }

    @Override // 预览当前登录用户自己的原始文件。
    public void previewFile(Long id, HttpServletResponse response) {
        writeFileResponse(id, response, true); // inline 方式写入文件响应。
    }

    @Override // 下载或预览前查询当前用户可访问的文件实体。
    public UserFile getFileForAccess(Long id) {
        Long currentUserId = UserContext.getUserId(); // 文件访问必须绑定当前登录用户。
        if (currentUserId == null) { // 没有登录用户时不允许访问文件。
            throw new IllegalArgumentException("未登录或登录状态失效"); // 由 Controller 写入统一错误响应。
        }
        if (id == null) { // 文件 ID 为空无法定位文件。
            throw new IllegalArgumentException("文件ID不能为空"); // 由 Controller 写入统一错误响应。
        }
        UserFile userFile = getOne(new LambdaQueryWrapper<UserFile>() // 按访问权限查询文件实体。
                .eq(UserFile::getId, id) // 限定文件 ID。
                .eq(UserFile::getUserId, currentUserId) // 强制当前用户隔离。
                .eq(UserFile::getStatus, NORMAL_STATUS) // 只允许访问正常状态文件。
                .last("LIMIT 1")); // 明确只取一条。
        if (userFile == null) { // 查不到表示不存在或无权访问。
            throw new IllegalArgumentException("文件不存在或无权访问"); // 不泄露其它用户文件是否存在。
        }
        return userFile; // 后端内部使用，可读取 storagePath；不会直接返回给前端 JSON。
    }

    private void writeFileResponse(Long id, HttpServletResponse response, boolean inline) {
        Long currentUserId = UserContext.getUserId(); // 文件访问用户必须来自登录上下文。
        if (currentUserId == null) { // 未登录时不进入文件读取流程。
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录状态失效"); // 返回统一未登录错误。
            return; // 结束响应。
        }

        if (inline) { // inline 表示预览。
            log.info("[UserFile] preview file, userId: {}, fileId: {}", currentUserId, id); // 不打印 storagePath。
        } else { // attachment 表示下载。
            log.info("[UserFile] download file, userId: {}, fileId: {}", currentUserId, id); // 不打印 storagePath。
        }

        try {
            UserFile userFile = getFileForAccess(id); // 按 id + userId + status=1 查询可访问文件实体。
            Path filePath = resolveSafeFilePath(userFile); // 校验 storagePath 在 uploadDir 下且物理文件存在。
            String contentType = resolveContentType(userFile); // 根据 MIME 或扩展名确定响应 Content-Type。
            String originalName = resolveResponseFileName(userFile); // 下载/预览文件名使用 originalName。

            response.reset(); // 清理可能已有的默认响应头。
            response.setContentType(contentType); // 设置文件原始类型，不转换文件内容。
            response.setHeader("Content-Disposition", buildContentDisposition(inline, originalName)); // inline 或 attachment 区分预览/下载。
            response.setHeader("Cache-Control", "no-cache"); // 不缓存受权限保护的文件响应。
            response.setContentLengthLong(Files.size(filePath)); // 设置响应文件大小。
            Files.copy(filePath, response.getOutputStream()); // 流式输出文件，避免一次性读入内存。
            response.getOutputStream().flush(); // 刷新响应流。
        } catch (IllegalArgumentException e) {
            writeJsonError(response, resolveAccessErrorStatus(e.getMessage()), e.getMessage()); // 权限或参数错误转换为 JSON。
        } catch (FileAccessException e) {
            writeJsonError(response, e.getStatus(), e.getMessage()); // 文件路径或物理文件错误转换为 JSON。
        } catch (Exception e) {
            log.error("[UserFile] write file response failed, userId: {}, fileId: {}", currentUserId, id, e); // 记录异常，不打印路径。
            writeJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "文件读取失败，请稍后重试"); // 返回友好错误。
        }
    }

    private Path resolveSafeFilePath(UserFile userFile) {
        if (userFile == null) { // 权限查询未返回文件。
            throw new FileAccessException(HttpServletResponse.SC_NOT_FOUND, "文件不存在或无权访问"); // 不泄露其它用户文件。
        }
        if (isBlank(userFile.getStoragePath())) { // 数据库记录缺少物理路径。
            throw new FileAccessException(HttpServletResponse.SC_NOT_FOUND, "文件不存在"); // 不暴露服务器路径细节。
        }

        Path basePath = resolveBasePath(); // 上传根目录绝对路径。
        Path filePath = Paths.get(userFile.getStoragePath()).toAbsolutePath().normalize(); // 数据库存储路径绝对化并归一化。
        if (!filePath.startsWith(basePath)) { // 文件路径必须仍位于 uploadDir 下。
            throw new FileAccessException(HttpServletResponse.SC_FORBIDDEN, "文件不存在或无权访问"); // 拒绝路径穿越或非法路径。
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) { // 文件不存在或不是普通文件。
            throw new FileAccessException(HttpServletResponse.SC_NOT_FOUND, "文件不存在"); // 不暴露真实路径。
        }
        return filePath; // 返回可安全读取的物理文件路径。
    }

    private String resolveContentType(UserFile userFile) {
        String mimeType = trimToNull(userFile == null ? null : userFile.getMimeType()); // 优先读取数据库 MIME 类型。
        if (mimeType != null && !"application/octet-stream".equalsIgnoreCase(mimeType)) { // 有明确 MIME 时直接使用。
            return mimeType; // 保持上传时记录的原始类型。
        }

        String fileExt = normalizeFileExt(userFile == null ? null : userFile.getFileExt()); // MIME 缺失或宽泛时按扩展名兜底。
        return switch (fileExt) {
            case "pdf" -> "application/pdf"; // PDF 预览/下载类型。
            case "png" -> "image/png"; // PNG 图片类型。
            case "jpg", "jpeg" -> "image/jpeg"; // JPEG 图片类型。
            case "webp" -> "image/webp"; // WebP 图片类型。
            case "txt" -> "text/plain;charset=UTF-8"; // 文本类型。
            case "md" -> "text/markdown;charset=UTF-8"; // Markdown 类型。
            case "py" -> "text/x-python;charset=UTF-8"; // Python 代码类型。
            case "java" -> "text/x-java-source;charset=UTF-8"; // Java 代码类型。
            case "js" -> "application/javascript;charset=UTF-8"; // JavaScript 代码类型。
            case "json" -> "application/json;charset=UTF-8"; // JSON 类型。
            case "xml" -> "application/xml;charset=UTF-8"; // XML 类型。
            case "html" -> "text/html;charset=UTF-8"; // HTML 类型。
            case "css" -> "text/css;charset=UTF-8"; // CSS 类型。
            case "doc" -> "application/msword"; // Word 旧格式。
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; // Word OOXML 格式。
            default -> "application/octet-stream"; // 未知类型按二进制流返回，不拒绝访问。
        };
    }

    private String buildContentDisposition(boolean inline, String originalName) {
        String dispositionType = inline ? "inline" : "attachment"; // preview 使用 inline，download 使用 attachment。
        return dispositionType + "; filename*=UTF-8''" + encodeFileName(originalName); // 使用 RFC 5987 兼容中文文件名。
    }

    private String encodeFileName(String fileName) {
        String safeFileName = isBlank(fileName) ? "file" : fileName.trim(); // 文件名缺失时兜底。
        return URLEncoder.encode(safeFileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20"); // 中文和空格按 UTF-8 百分号编码。
    }

    private String resolveResponseFileName(UserFile userFile) {
        if (userFile == null || isBlank(userFile.getOriginalName())) { // 原始文件名缺失时兜底。
            return "file"; // 返回安全默认文件名。
        }
        String normalizedName = userFile.getOriginalName().replace('\\', '/').trim(); // 兼容历史文件名中的路径分隔符。
        int lastSlashIndex = normalizedName.lastIndexOf('/'); // 只取最后一级文件名。
        String fileName = lastSlashIndex >= 0 ? normalizedName.substring(lastSlashIndex + 1) : normalizedName; // 去掉潜在路径片段。
        return isBlank(fileName) ? "file" : fileName.trim(); // 使用 originalName 的安全文件名作为下载/预览文件名。
    }

    private int resolveAccessErrorStatus(String message) {
        if ("未登录或登录状态失效".equals(message)) { // 未登录错误。
            return HttpServletResponse.SC_UNAUTHORIZED; // 返回 401。
        }
        if ("文件不存在或无权访问".equals(message)) { // 文件不存在或越权。
            return HttpServletResponse.SC_NOT_FOUND; // 返回 404，避免暴露越权信息。
        }
        return HttpServletResponse.SC_BAD_REQUEST; // 其它参数错误返回 400。
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) { // 响应已提交时无法再写 JSON。
            return; // 直接返回。
        }
        try {
            response.reset(); // 清理已有响应头。
            response.setStatus(status); // 设置 HTTP 状态码。
            response.setCharacterEncoding(StandardCharsets.UTF_8.name()); // 使用 UTF-8 输出中文。
            response.setContentType("application/json; charset=UTF-8"); // 错误响应统一 JSON。
            response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + escapeJson(message) + "\"}"); // 写入项目统一结构。
            response.getWriter().flush(); // 刷新错误响应。
        } catch (IOException e) {
            log.warn("[UserFile] write error response failed"); // 不打印路径或文件内容。
        }
    }

    private String escapeJson(String value) {
        if (value == null) { // 空错误信息兜底。
            return ""; // 返回空字符串。
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\""); // 转义 JSON 字符串中的反斜杠和双引号。
    }

    @Override // 保存用户文件基础元数据。
    public Long saveUserFile(UserFile userFile) {
        validateForSave(userFile); // 保存前校验必要字段，避免写入无法使用的文件记录。
        fillDefaults(userFile); // 填充默认状态、存储类型、上传来源和时间字段。

        log.info("[UserFile] save file record, userId: {}, originalName: {}",
                userFile.getUserId(), userFile.getOriginalName()); // 只打印用户 ID 和原始文件名，不打印完整 storagePath。
        boolean saved = save(userFile); // 使用 MyBatis-Plus 保存 user_file 记录，并回填数据库自增 ID。
        if (!saved || userFile.getId() == null) { // 保存失败或未回填 ID 都视为入库失败。
            throw new IllegalStateException("文件记录保存失败"); // 由上传流程负责清理物理文件。
        }
        log.info("[UserFile] save success, id: {}", userFile.getId()); // 保存成功后打印自增 ID，便于后续链路排查。
        return userFile.getId(); // 返回自增主键给调用方。
    }

    @Override // 按 ID 和用户 ID 查询正常状态文件。
    public UserFile getByIdAndUserId(Long id, Long userId) {
        if (id == null || userId == null) { // id 或 userId 为空时无法安全定位文件归属。
            return null; // 参数不足直接返回 null，不查询全表。
        }
        return getOne(new LambdaQueryWrapper<UserFile>() // 使用 MyBatis-Plus 条件构造器查询单条记录。
                .eq(UserFile::getId, id) // 限定文件 ID。
                .eq(UserFile::getUserId, userId) // 限定用户 ID，防止越权读取。
                .eq(UserFile::getStatus, NORMAL_STATUS) // 只查询正常状态文件。
                .last("LIMIT 1")); // 明确只取一条记录。
    }

    @Override // 查询指定用户的正常状态文件列表。
    public List<UserFile> listByUserId(Long userId) {
        if (userId == null) { // userId 为空时不能查询用户文件列表。
            return Collections.emptyList(); // 返回空列表，避免误查全部文件。
        }
        return list(new LambdaQueryWrapper<UserFile>() // 使用 MyBatis-Plus 条件构造器查询列表。
                .eq(UserFile::getUserId, userId) // 限定用户 ID，保证用户隔离。
                .eq(UserFile::getStatus, NORMAL_STATUS) // 只返回正常状态文件。
                .orderByDesc(UserFile::getCreateTime)); // 按 create_time 倒序返回最新文件。
    }

    private void validateForSave(UserFile userFile) {
        if (userFile == null) { // 保存文件记录必须有实体对象。
            throw new IllegalArgumentException("userFile不能为空"); // 参数非法直接抛出，避免空指针或脏数据。
        }
        if (userFile.getUserId() == null) { // 用户 ID 是数据隔离边界。
            throw new IllegalArgumentException("userId不能为空"); // 缺少用户归属不允许保存。
        }
        if (isBlank(userFile.getOriginalName())) { // 原始文件名用于展示和追踪。
            throw new IllegalArgumentException("originalName不能为空"); // 缺少原始文件名不允许保存。
        }
        if (isBlank(userFile.getStoredName())) { // 存储文件名用于定位实际文件。
            throw new IllegalArgumentException("storedName不能为空"); // 缺少存储文件名不允许保存。
        }
        if (isBlank(userFile.getStoragePath())) { // 存储路径是后续下载或解析的基础。
            throw new IllegalArgumentException("storagePath不能为空"); // 缺少存储路径不允许保存。
        }
        if (userFile.getFileSize() == null) { // 文件大小是基础元数据。
            throw new IllegalArgumentException("fileSize不能为空"); // 缺少文件大小不允许保存。
        }
    }

    private void fillDefaults(UserFile userFile) {
        if (userFile.getStatus() == null) { // 调用方未指定状态时使用默认正常状态。
            userFile.setStatus(NORMAL_STATUS); // 默认 status=1。
        }
        if (isBlank(userFile.getStorageType())) { // 调用方未指定存储类型时使用本地存储。
            userFile.setStorageType(DEFAULT_STORAGE_TYPE); // 默认 storage_type=LOCAL。
        }
        if (isBlank(userFile.getUploadSource())) { // 调用方未指定上传来源时使用用户上传。
            userFile.setUploadSource(DEFAULT_UPLOAD_SOURCE); // 默认 upload_source=USER_UPLOAD。
        }
        LocalDateTime now = LocalDateTime.now(); // 创建和更新时间默认使用同一个当前时间点。
        if (userFile.getCreateTime() == null) { // 调用方未指定创建时间时补齐。
            userFile.setCreateTime(now); // 设置 create_time。
        }
        if (userFile.getUpdateTime() == null) { // 调用方未指定更新时间时补齐。
            userFile.setUpdateTime(now); // 设置 update_time。
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty(); // 统一判断 null、空字符串和纯空白字符串。
    }

    private void validateLoginUser(Long userId) {
        if (userId == null) { // 用户 ID 为空说明当前请求没有有效登录上下文。
            throw new IllegalArgumentException("未登录或登录状态失效"); // 返回统一未登录提示。
        }
    }

    private void validateBasicFile(MultipartFile file, String originalName) {
        if (file == null) { // 请求中没有 file 参数。
            throw new IllegalArgumentException("未选择文件"); // 明确提示用户选择文件。
        }
        if (file.isEmpty() || file.getSize() <= 0) { // 空文件不允许入库或保存。
            throw new IllegalArgumentException("文件为空"); // 明确提示文件为空。
        }
        if (isBlank(originalName)) { // 原始文件名缺失时无法判断扩展名。
            throw new IllegalArgumentException("原始文件名不能为空"); // 明确提示文件名问题。
        }
    }

    private void validateFileSize(MultipartFile file) {
        int maxSizeMb = resolveMaxSizeMb(); // 读取配置中的最大 MB 数。
        long maxSizeBytes = maxSizeMb * 1024L * 1024L; // 转为字节，避免整数溢出。
        if (file.getSize() > maxSizeBytes) { // 后端强制校验文件大小，不能只依赖前端。
            throw new IllegalArgumentException("文件大小不能超过 " + maxSizeMb + "MB"); // 返回友好错误。
        }
    }

    private String extractAndValidateExtension(String originalName) {
        int lastDotIndex = originalName.lastIndexOf('.'); // 从展示文件名中提取最后一个点后的扩展名。
        if (lastDotIndex < 0 || lastDotIndex == originalName.length() - 1) { // 没有扩展名或扩展名为空。
            throw new IllegalArgumentException("不支持该文件类型"); // 没有扩展名时按不支持处理。
        }
        String fileExt = originalName.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT); // 扩展名统一小写且不带点。
        if (!allowedExtensions().contains(fileExt)) { // 扩展名必须在后端允许列表内。
            throw new IllegalArgumentException("不支持该文件类型"); // 返回统一文件类型错误。
        }
        return fileExt; // 返回安全扩展名，用于生成 storedName 和 file_type。
    }

    private void validateMimeType(String mimeType) {
        if (isBlank(mimeType)) { // contentType 为空时只依赖扩展名校验。
            return; // 允许继续上传。
        }
        if (!allowedMimeTypes().contains(mimeType)) { // contentType 非空时必须在后端允许列表内。
            throw new IllegalArgumentException("不支持该文件类型"); // 返回统一文件类型错误。
        }
    }

    private Path resolveBasePath() {
        String uploadDir = fileUploadProperties == null ? null : fileUploadProperties.getUploadDir(); // 读取配置上传目录。
        if (isBlank(uploadDir)) { // 配置为空时使用默认目录。
            uploadDir = DEFAULT_UPLOAD_DIR; // 默认写到 ./data/uploads。
        }
        return Paths.get(uploadDir).toAbsolutePath().normalize(); // 转为绝对路径并归一化。
    }

    private Path resolveUserDateDir(Path basePath, Long userId) {
        Path userDateDir = basePath.resolve(String.valueOf(userId)) // 每个用户一个一级目录。
                .resolve(LocalDate.now().format(DATE_DIR_FORMATTER)) // 日期目录用于后续管理。
                .normalize(); // 归一化，消除潜在的 .. 片段。
        ensurePathUnderBase(basePath, userDateDir); // 确保用户目录仍在上传根目录内。
        return userDateDir; // 返回用户日期目录。
    }

    private Path resolveTargetPath(Path basePath, Path userDateDir, String storedName) {
        Path targetPath = userDateDir.resolve(storedName).normalize(); // 拼接最终文件路径。
        ensurePathUnderBase(basePath, targetPath); // 确保最终路径仍在上传根目录内。
        if (!targetPath.startsWith(userDateDir)) { // 额外确保文件落在当前用户日期目录内。
            throw new IllegalStateException("文件保存路径非法"); // 不暴露真实路径。
        }
        return targetPath; // 返回最终保存路径。
    }

    private SavedLocalFile saveFileAndCalculateMd5(MultipartFile file, Path userDateDir, Path targetPath) {
        try {
            Files.createDirectories(userDateDir); // 目录不存在时自动创建。
        } catch (IOException e) {
            throw new IllegalStateException("文件保存目录创建失败，请稍后重试", e); // 不暴露服务器目录。
        }

        MessageDigest messageDigest = newMd5Digest(); // 初始化 MD5 摘要器。
        try (InputStream inputStream = new DigestInputStream(file.getInputStream(), messageDigest); // 读取时同步更新 MD5。
             OutputStream outputStream = Files.newOutputStream(targetPath,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { // 使用 CREATE_NEW 防止覆盖已有文件。
            inputStream.transferTo(outputStream); // 流式保存文件，不把文件内容写入数据库。
            String md5 = HexFormat.of().formatHex(messageDigest.digest()); // 生成 32 位小写 hex MD5。
            return new SavedLocalFile(targetPath, md5); // 返回保存路径和 MD5。
        } catch (IOException e) {
            cleanupPhysicalFile(targetPath); // 保存失败时尽量删除半残文件。
            throw new IllegalStateException("文件保存失败，请稍后重试", e); // 返回友好错误，不暴露路径。
        }
    }

    private MessageDigest newMd5Digest() {
        try {
            return MessageDigest.getInstance("MD5"); // 使用标准 MD5 算法。
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("文件保存失败，请稍后重试", e); // JDK 缺少 MD5 时按保存失败处理。
        }
    }

    private UserFile buildUserFile(Long userId,
                                   String originalName,
                                   String storedName,
                                   String fileExt,
                                   String mimeType,
                                   Long fileSize,
                                   SavedLocalFile savedLocalFile) {
        UserFile userFile = new UserFile(); // 构造 user_file 入库对象。
        userFile.setUserId(userId); // 绑定当前登录用户 ID。
        userFile.setOriginalName(originalName); // 保存安全展示用原始文件名。
        userFile.setStoredName(storedName); // 保存后端生成的真实文件名。
        userFile.setFileExt(fileExt); // 保存小写扩展名。
        userFile.setMimeType(mimeType); // 保存标准化 MIME 类型，可能为空。
        userFile.setFileType(resolveFileType(fileExt)); // 按扩展名写入 IMAGE/DOCUMENT/OTHER。
        userFile.setFileSize(fileSize); // 保存文件大小。
        userFile.setStorageType(DEFAULT_STORAGE_TYPE); // 当前固定本地存储。
        userFile.setStoragePath(savedLocalFile.getPath().toString()); // 保存真实路径到数据库，不返回给前端。
        userFile.setAccessUrl(null); // 下载接口未实现前访问地址为空。
        userFile.setMd5(savedLocalFile.getMd5()); // 保存 32 位小写 MD5。
        userFile.setStatus(NORMAL_STATUS); // 默认正常状态。
        userFile.setUploadSource(DEFAULT_UPLOAD_SOURCE); // 默认用户主动上传。
        return userFile; // 返回待保存实体。
    }

    private UserFileVO toVO(UserFile userFile) {
        if (userFile == null) { // 查询不到记录时直接返回空。
            return null; // Controller 会转换成统一错误提示。
        }
        UserFileVO vo = new UserFileVO(); // 构造安全返回对象。
        vo.setId(userFile.getId()); // 返回文件 ID。
        vo.setOriginalName(userFile.getOriginalName()); // 返回原始文件名。
        vo.setFileExt(userFile.getFileExt()); // 返回扩展名。
        vo.setMimeType(userFile.getMimeType()); // 返回 MIME 类型。
        vo.setFileType(userFile.getFileType()); // 返回文件分类。
        vo.setFileSize(userFile.getFileSize()); // 返回文件大小。
        vo.setStorageType(userFile.getStorageType()); // 返回存储类型。
        vo.setAccessUrl(userFile.getAccessUrl()); // 当前可为空。
        vo.setMd5(userFile.getMd5()); // 返回文件 MD5，便于前端展示或去重提示。
        vo.setStatus(userFile.getStatus()); // 返回文件状态。
        vo.setUploadSource(userFile.getUploadSource()); // 返回上传来源。
        vo.setCreateTime(userFile.getCreateTime()); // 返回创建时间。
        vo.setUpdateTime(userFile.getUpdateTime()); // 返回更新时间。
        return vo; // 不返回 storagePath。
    }

    private LambdaQueryWrapper<UserFile> buildMyFilePageWrapper(UserFilePageRequest request, Long currentUserId) {
        LambdaQueryWrapper<UserFile> wrapper = new LambdaQueryWrapper<>(); // 构造文件分页查询条件。
        wrapper.eq(UserFile::getUserId, currentUserId); // 强制只查当前用户文件。
        wrapper.eq(UserFile::getStatus, NORMAL_STATUS); // 强制只查正常状态文件。

        String keyword = trimToNull(request.getKeyword()); // 标准化文件名关键词。
        if (keyword != null) { // keyword 非空且不是 Swagger 默认 string 时参与筛选。
            wrapper.like(UserFile::getOriginalName, keyword); // 按 original_name 模糊查询。
        }
        String fileType = trimToNull(request.getFileType()); // 标准化文件分类。
        if (fileType != null) { // fileType 有效时参与筛选。
            wrapper.eq(UserFile::getFileType, fileType.toUpperCase(Locale.ROOT)); // 文件分类统一按大写匹配。
        }
        String fileExt = trimToNull(request.getFileExt()); // 标准化扩展名。
        if (fileExt != null) { // fileExt 有效时参与筛选。
            wrapper.eq(UserFile::getFileExt, normalizeFileExt(fileExt)); // 扩展名统一小写且去掉点。
        }
        if (request.getStartTime() != null) { // 上传开始时间有效时参与筛选。
            wrapper.ge(UserFile::getCreateTime, request.getStartTime()); // create_time >= startTime。
        }
        if (request.getEndTime() != null) { // 上传结束时间有效时参与筛选。
            wrapper.le(UserFile::getCreateTime, request.getEndTime()); // create_time <= endTime。
        }
        wrapper.orderByDesc(UserFile::getCreateTime); // 默认按上传时间倒序。
        return wrapper; // 返回完整查询条件。
    }

    private long resolvePageNum(Integer pageNum) {
        if (pageNum == null || pageNum < 1) { // 页码为空或小于 1 时使用默认值。
            return DEFAULT_PAGE_NUM; // 默认第一页。
        }
        return pageNum; // 返回合法页码。
    }

    private long resolvePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) { // 每页数量为空或非法时使用默认值。
            return DEFAULT_PAGE_SIZE; // 默认每页 10 条。
        }
        return Math.min(pageSize, MAX_PAGE_SIZE); // 限制最大每页 100 条。
    }

    private String trimToNull(String value) {
        if (isBlank(value)) { // null、空字符串和纯空白都不参与筛选。
            return null; // 返回空表示忽略该条件。
        }
        String trimmedValue = value.trim(); // 去掉首尾空白。
        if ("string".equalsIgnoreCase(trimmedValue)) { // Swagger 默认 string 不参与筛选。
            return null; // 返回空表示忽略该条件。
        }
        return trimmedValue; // 返回有效筛选值。
    }

    private String normalizeFileExt(String fileExt) {
        String normalizedExt = trimToNull(fileExt); // 标准化扩展名，空值直接走兜底逻辑。
        if (normalizedExt == null) { // 缺少扩展名时返回空字符串。
            return ""; // 下载/预览时会按 application/octet-stream 处理。
        }
        normalizedExt = normalizedExt.toLowerCase(Locale.ROOT); // 扩展名统一小写。
        return normalizedExt.startsWith(".") ? normalizedExt.substring(1) : normalizedExt; // 兼容前端传 .pdf。
    }

    private String safeOriginalName(MultipartFile file) {
        if (file == null || isBlank(file.getOriginalFilename())) { // 文件或原始名称为空时返回空。
            return null; // 由后续校验给出明确错误。
        }
        String normalizedName = file.getOriginalFilename().replace('\\', '/').trim(); // 统一 Windows 和 Unix 分隔符。
        int lastSlashIndex = normalizedName.lastIndexOf('/'); // 只保留最后一级文件名。
        String fileName = lastSlashIndex >= 0 ? normalizedName.substring(lastSlashIndex + 1) : normalizedName; // 去掉潜在路径片段。
        return isBlank(fileName) ? null : fileName.trim(); // 返回安全展示名，不参与真实路径。
    }

    private String normalizeMimeType(String contentType) {
        if (isBlank(contentType)) { // contentType 可为空。
            return null; // 空 MIME 后续只按扩展名校验。
        }
        String normalizedMimeType = contentType.trim().toLowerCase(Locale.ROOT); // MIME 类型统一小写。
        int separatorIndex = normalizedMimeType.indexOf(';'); // 兼容带 charset 的 contentType。
        return separatorIndex >= 0 ? normalizedMimeType.substring(0, separatorIndex).trim() : normalizedMimeType; // 去掉参数部分。
    }

    private Set<String> allowedExtensions() {
        return normalizeValues(fileUploadProperties == null ? null : fileUploadProperties.getAllowedExtensions(), true); // 标准化允许扩展名。
    }

    private Set<String> allowedMimeTypes() {
        return normalizeValues(fileUploadProperties == null ? null : fileUploadProperties.getAllowedMimeTypes(), false); // 标准化允许 MIME。
    }

    private Set<String> normalizeValues(List<String> values, boolean removeLeadingDot) {
        if (values == null) { // 配置为空时返回空集合。
            return Collections.emptySet(); // 空集合会拒绝所有对应校验项。
        }
        return values.stream()
                .filter(value -> !isBlank(value)) // 过滤空配置项。
                .map(value -> value.trim().toLowerCase(Locale.ROOT)) // 统一小写。
                .map(value -> removeLeadingDot && value.startsWith(".") ? value.substring(1) : value) // 扩展名兼容带点配置。
                .collect(Collectors.toSet()); // 转成集合便于快速判断。
    }

    private int resolveMaxSizeMb() {
        Integer maxSizeMb = fileUploadProperties == null ? null : fileUploadProperties.getMaxSizeMb(); // 读取最大大小配置。
        if (maxSizeMb == null || maxSizeMb <= 0) { // 配置缺失或非法时使用默认值。
            return DEFAULT_MAX_SIZE_MB; // 默认 20MB。
        }
        return maxSizeMb; // 返回有效配置值。
    }

    private String buildStoredName(String fileExt) {
        return UUID.randomUUID().toString().replace("-", "") + "." + fileExt; // UUID 无横线文件名，避免使用原始文件名。
    }

    private String resolveFileType(String fileExt) {
        if (IMAGE_EXTENSIONS.contains(fileExt)) { // 图片扩展名归类为 IMAGE。
            return "IMAGE"; // 返回图片类型。
        }
        if (DOCUMENT_EXTENSIONS.contains(fileExt)) { // 文档扩展名归类为 DOCUMENT。
            return "DOCUMENT"; // 返回文档类型。
        }
        return "OTHER"; // 其它允许类型归类为 OTHER。
    }

    private void ensurePathUnderBase(Path basePath, Path candidatePath) {
        if (!candidatePath.startsWith(basePath)) { // 路径归一化后必须仍在上传根目录内。
            throw new IllegalStateException("文件保存路径非法"); // 防止路径穿越。
        }
    }

    private void cleanupPhysicalFile(Path path) {
        if (path == null) { // 没有路径时无需清理。
            return; // 直接返回。
        }
        try {
            Files.deleteIfExists(path); // 尽量删除半残文件或数据库失败后的物理文件。
        } catch (IOException cleanupException) {
            log.warn("[UserFile] cleanup physical file failed"); // 不打印真实路径，避免泄露服务器目录。
        }
    }

    private static class FileAccessException extends RuntimeException { // 文件响应阶段的受控异常。

        private final int status; // 要写入响应的 HTTP 状态码。

        private FileAccessException(int status, String message) {
            super(message); // 保存错误提示。
            this.status = status; // 保存状态码。
        }

        private int getStatus() {
            return status; // 返回 HTTP 状态码。
        }
    }

    private static class SavedLocalFile { // 本地保存结果，只在 Service 内部使用。
        private final Path path; // 已保存文件路径。
        private final String md5; // 已保存文件 MD5。

        private SavedLocalFile(Path path, String md5) {
            this.path = path; // 保存路径。
            this.md5 = md5; // 保存 MD5。
        }

        private Path getPath() {
            return path; // 返回保存路径。
        }

        private String getMd5() {
            return md5; // 返回文件 MD5。
        }
    }
}
