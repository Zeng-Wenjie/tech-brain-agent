package com.agent.service.impl;

import com.agent.config.FileUploadProperties;
import com.agent.entity.UserFile;
import com.agent.entity.vo.UserFileVO;
import com.agent.mapper.UserFileMapper;
import com.agent.service.UserFileService;
import com.agent.utils.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * <p>边界说明：本实现只保存文件本体和元数据，不创建或修改数据库结构，不执行 SQL 脚本，不解析文件内容，
 * 不提供下载接口，不接入 AI、RAG、Tool Calling、向量化或 Milvus。</p>
 */
@Slf4j // 输出 [UserFile] 前缀日志，避免打印完整 storagePath 等过长敏感内容。
@Service // 注册为 Spring Bean，供后续文件业务注入使用。
public class UserFileServiceImpl extends ServiceImpl<UserFileMapper, UserFile> implements UserFileService { // 用户文件基础持久化服务实现。

    private static final int DEFAULT_MAX_SIZE_MB = 20; // 默认单文件最大 20MB。

    private static final String DEFAULT_UPLOAD_DIR = "./data/uploads"; // 默认本地上传根目录。

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
        UserFileVO vo = new UserFileVO(); // 构造安全返回对象。
        vo.setId(userFile.getId()); // 返回文件 ID。
        vo.setOriginalName(userFile.getOriginalName()); // 返回原始文件名。
        vo.setFileExt(userFile.getFileExt()); // 返回扩展名。
        vo.setMimeType(userFile.getMimeType()); // 返回 MIME 类型。
        vo.setFileType(userFile.getFileType()); // 返回文件分类。
        vo.setFileSize(userFile.getFileSize()); // 返回文件大小。
        vo.setStorageType(userFile.getStorageType()); // 返回存储类型。
        vo.setAccessUrl(userFile.getAccessUrl()); // 当前可为空。
        vo.setCreateTime(userFile.getCreateTime()); // 返回创建时间。
        return vo; // 不返回 storagePath。
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
