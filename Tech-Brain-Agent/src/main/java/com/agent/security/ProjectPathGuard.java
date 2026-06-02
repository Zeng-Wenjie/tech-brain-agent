package com.agent.security;

import com.agent.config.ProjectWorkspaceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 项目代码工作区路径安全校验组件。
 *
 * <p>适用场景：后续项目代码读取、项目树展示、代码搜索等能力在访问 workspace 下的项目文件前，
 * 必须先通过本组件校验路径是否仍位于 workspace 内、是否为普通文件、是否命中敏感目录或敏感文件、
 * 扩展名是否允许、文件大小是否超过限制。</p>
 *
 * <p>调用链：后续业务 Tool 或 Service 接收用户传入的相对路径
 * -> 调用 resolveProjectPath 将相对路径解析到 techbrain.project.workspace-dir 内
 * -> 调用 validateReadableCodeFile 执行路径穿越、敏感文件、扩展名和大小校验
 * -> 校验通过后才允许后续工具读取、搜索或展示项目代码文件。</p>
 *
 * <p>边界说明：本组件只做安全校验，不读取文件内容，不解析代码语法，不创建 Tool，
 * 不接入 RAG / Milvus / 向量化，不暴露服务器真实绝对路径。</p>
 */
@Slf4j // 输出项目 workspace 安全校验日志，不打印文件内容。
@Component // 注册为 Spring Bean，供后续项目代码读取相关 Service 或 Tool 注入。
public class ProjectPathGuard { // 项目代码 workspace 路径安全守卫。
    private static final String DEFAULT_WORKSPACE_DIR = "./workspace/projects"; // 配置缺失时使用的默认项目工作区。
    private static final long DEFAULT_MAX_FILE_SIZE_KB = 512L; // 配置缺失时使用的默认单文件大小上限。
    private static final Set<String> SENSITIVE_DIRECTORIES = Set.of( // 默认禁止遍历和读取的敏感目录名。
            ".git", ".svn", ".hg",
            ".codex-tmp",
            ".idea", ".vscode",
            "node_modules", "target", "build", "dist", "out",
            ".gradle", ".mvn",
            "data", "uploads", "volumes",
            "logs", "log", "tmp", "temp",
            "coverage", ".cache", ".next", ".nuxt",
            "__pycache__", "pycache",
            "venv", ".venv"
    );

    private final ProjectWorkspaceProperties properties; // 项目 workspace 安全配置。

    public ProjectPathGuard(ProjectWorkspaceProperties properties) { // 构造器注入配置，避免手动读取 yml。
        this.properties = properties; // 保存配置引用供所有校验方法复用。
    }

    public Path getWorkspaceRoot() { // 获取 workspace 绝对归一化根路径。
        String workspaceDir = properties == null ? null : properties.getWorkspaceDir(); // 读取 techbrain.project.workspace-dir。
        if (workspaceDir == null || workspaceDir.trim().isEmpty()) { // 配置为空时使用默认目录。
            workspaceDir = DEFAULT_WORKSPACE_DIR; // 默认限制到 ./workspace/projects。
        }
        return Paths.get(workspaceDir).toAbsolutePath().normalize(); // 返回绝对路径并 normalize，作为 startsWith 校验基准。
    }

    public Path resolveProjectPath(String relativePath) { // 将用户传入的相对路径解析为 workspace 内安全路径。
        String normalizedInput = trimToNull(relativePath); // 去掉首尾空白，空字符串按 null 处理。
        log.info("[ProjectWorkspace] validate file path, relativePath: {}", normalizedInput); // 只打印相对路径，不打印绝对路径。
        if (normalizedInput == null) { // 路径为空时直接拒绝。
            throw new ProjectPathAccessException("路径不能为空。"); // 返回友好错误，不继续解析。
        }

        Path inputPath = Paths.get(normalizedInput); // 将外部输入转换为 Path。
        if (inputPath.isAbsolute()) { // 绝对路径可能绕过 workspace，必须拒绝。
            throw new ProjectPathAccessException("不允许访问 workspace 外的文件。"); // 不暴露真实服务器路径。
        }

        Path workspaceRoot = getWorkspaceRoot(); // 获取 workspace 根目录。
        Path resolvedPath = workspaceRoot.resolve(inputPath).normalize(); // 先拼到 workspace 下再 normalize，消除 ../。
        if (!resolvedPath.startsWith(workspaceRoot)) { // normalize 后仍必须位于 workspace 内。
            throw new ProjectPathAccessException("不允许访问 workspace 外的文件。"); // 防止路径穿越。
        }
        return resolvedPath; // 返回经过 workspace 约束的路径。
    }

    public boolean isInsideWorkspace(Path path) { // 判断任意路径是否位于 workspace 内。
        if (path == null) { // 空路径不允许访问。
            return false; // 返回 false。
        }
        Path workspaceRoot = getWorkspaceRoot(); // 获取 workspace 根目录。
        Path normalizedPath = path.toAbsolutePath().normalize(); // 将待校验路径转成绝对归一化路径。
        return normalizedPath.startsWith(workspaceRoot); // 通过 startsWith 判断是否仍在 workspace 内。
    }

    public String getExtension(String fileName) { // 获取文件扩展名，小写且不含点。
        String normalizedName = trimToNull(fileName); // 去掉文件名首尾空白。
        if (normalizedName == null) { // 文件名为空时没有扩展名。
            return ""; // 返回空扩展名。
        }
        String lowerName = normalizedName.toLowerCase(Locale.ROOT); // 扩展名统一按小写处理。
        if (lowerName.startsWith(".") && lowerName.indexOf('.', 1) > 0) { // 兼容 .env.example 这类点开头文件。
            return lowerName.substring(1); // 返回 env.example，供白名单精确判断。
        }
        int dotIndex = lowerName.lastIndexOf('.'); // 普通文件取最后一个点之后的后缀。
        if (dotIndex < 0 || dotIndex == lowerName.length() - 1) { // 没有点或点在末尾时没有有效扩展名。
            return ""; // 返回空扩展名。
        }
        return lowerName.substring(dotIndex + 1); // 返回最后一级扩展名。
    }

    public boolean isAllowedExtension(String ext) { // 判断扩展名是否在允许读取白名单中。
        String normalizedExt = normalizeExtension(ext); // 去点、去空白并转小写。
        if (normalizedExt.isEmpty()) { // 空扩展名默认不允许。
            return false; // 返回 false。
        }
        return normalizeExtensionSet(properties == null ? null : properties.getAllowedExtensions()).contains(normalizedExt); // 与配置白名单匹配。
    }

    public boolean isBlockedFilename(String fileName) { // 判断完整文件名是否命中敏感文件名黑名单。
        String normalizedName = trimToNull(fileName); // 去掉文件名首尾空白。
        if (normalizedName == null) { // 空文件名不命中。
            return false; // 返回 false。
        }
        return normalizeFilenameSet(properties == null ? null : properties.getBlockedFilenames())
                .contains(normalizedName.toLowerCase(Locale.ROOT)); // 文件名大小写不敏感匹配。
    }

    public boolean isBlockedExtension(String ext) { // 判断扩展名是否命中敏感或二进制扩展名黑名单。
        String normalizedExt = normalizeExtension(ext); // 去点、去空白并转小写。
        if (normalizedExt.isEmpty()) { // 空扩展名不按黑名单处理。
            return false; // 返回 false。
        }
        return normalizeExtensionSet(properties == null ? null : properties.getBlockedExtensions()).contains(normalizedExt); // 与配置黑名单匹配。
    }

    public boolean isSensitivePath(Path path) { // 判断路径是否包含敏感目录、敏感文件名或敏感扩展名。
        if (path == null) { // 空路径视为敏感。
            return true; // 返回 true。
        }
        Path normalizedPath = path.normalize(); // 先归一化路径，避免 ./ 和 ../ 干扰目录判断。
        if (isDockerRuntimePath(normalizedPath)) { // docker 配置目录保留，但 docker 运行数据目录过滤。
            return true; // docker/volumes、docker/redis/data、docker/milvus、docker/minio 不允许出现在目录树。
        }
        for (Path namePart : normalizedPath) { // 遍历路径每一段目录名。
            String part = namePart.toString().toLowerCase(Locale.ROOT); // 路径片段统一转小写。
            if (SENSITIVE_DIRECTORIES.contains(part)) { // 命中 .git、node_modules、target 等目录。
                return true; // 敏感路径禁止读取。
            }
        }

        Path fileNamePath = path.getFileName(); // 获取最后一级文件名。
        if (fileNamePath == null) { // 没有文件名时不按文件黑名单处理。
            return false; // 返回 false。
        }
        String fileName = fileNamePath.toString(); // 转成字符串用于文件名和扩展名判断。
        return isBlockedFilename(fileName) || isBlockedExtension(getExtension(fileName)); // 文件名或扩展名任一命中即视为敏感。
    }

    private boolean isDockerRuntimePath(Path normalizedPath) { // 判断是否为 docker 下的运行数据目录。
        if (normalizedPath == null) { // 空路径不属于 docker 运行数据目录。
            return false; // 返回 false。
        }
        String previousPart = ""; // 保存上一段路径名。
        String previousPreviousPart = ""; // 保存上上段路径名，用于识别 docker/redis/data。
        for (Path namePart : normalizedPath) { // 遍历路径片段，避免依赖服务器绝对路径字符串。
            String part = namePart.toString().toLowerCase(Locale.ROOT); // 路径片段统一小写。
            if ("docker".equals(previousPart)
                    && ("volumes".equals(part) || "milvus".equals(part) || "minio".equals(part))) { // docker 下这些目录是运行数据。
                return true; // 过滤 docker/volumes、docker/milvus、docker/minio。
            }
            if ("docker".equals(previousPreviousPart) && "redis".equals(previousPart) && "data".equals(part)) { // docker/redis/data 是 Redis 持久化数据。
                return true; // 过滤 docker/redis/data。
            }
            previousPreviousPart = previousPart; // 推进上上段路径。
            previousPart = part; // 推进上一段路径。
        }
        return false; // 未命中 docker 运行数据目录。
    }

    public void validateReadableCodeFile(Path path) { // 校验某个项目文件是否允许后续读取。
        if (path == null) { // 路径为空时拒绝。
            throw new ProjectPathAccessException("路径不能为空。"); // 返回友好错误。
        }

        Path normalizedPath = path.toAbsolutePath().normalize(); // 转成绝对归一化路径。
        if (!isInsideWorkspace(normalizedPath)) { // 必须位于 workspace 内。
            throw new ProjectPathAccessException("不允许访问 workspace 外的文件。"); // 防止越权访问任意服务器文件。
        }
        if (isSensitivePath(normalizedPath)) { // 检查敏感目录、敏感文件名和敏感扩展名。
            throw new ProjectPathAccessException("该文件属于敏感文件，禁止读取。"); // 敏感文件统一拒绝。
        }

        Path fileNamePath = normalizedPath.getFileName(); // 获取文件名。
        String fileName = fileNamePath == null ? "" : fileNamePath.toString(); // 文件名为空时兜底为空字符串。
        String extension = getExtension(fileName); // 提取小写扩展名。
        if (!isAllowedExtension(extension)) { // 扩展名必须在项目代码白名单内。
            throw new ProjectPathAccessException("不支持读取该文件类型。"); // 不支持类型直接拒绝。
        }
        if (isBlockedExtension(extension)) { // 再次检查黑名单扩展名，避免配置冲突时放行。
            throw new ProjectPathAccessException("该文件属于敏感文件，禁止读取。"); // 黑名单优先级高于白名单。
        }
        if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) { // 不跟随符号链接检查文件是否存在。
            throw new ProjectPathAccessException("文件不存在。"); // 不暴露真实绝对路径。
        }
        if (Files.isSymbolicLink(normalizedPath) || !Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) { // 拒绝符号链接和目录。
            throw new ProjectPathAccessException("目标不是普通文件。"); // 只允许普通文件。
        }

        validateRealPathInsideWorkspace(normalizedPath); // 校验真实路径仍在 workspace 内，防止链接绕过。
        validateFileSize(normalizedPath); // 校验单文件大小不超过配置限制。
    }

    private void validateRealPathInsideWorkspace(Path normalizedPath) { // 校验文件真实路径没有跳出 workspace。
        try {
            Path workspaceRealPath = getWorkspaceRoot().toRealPath(LinkOption.NOFOLLOW_LINKS); // 获取 workspace 真实路径。
            Path fileRealPath = normalizedPath.toRealPath(LinkOption.NOFOLLOW_LINKS); // 获取目标文件真实路径。
            if (!fileRealPath.startsWith(workspaceRealPath)) { // 真实路径必须仍在 workspace 内。
                throw new ProjectPathAccessException("不允许访问 workspace 外的文件。"); // 防止路径绕过。
            }
        } catch (ProjectPathAccessException e) { // 业务拒绝异常保持原文案。
            throw e; // 继续抛出。
        } catch (IOException e) { // 文件不存在或真实路径读取失败。
            throw new ProjectPathAccessException("文件不存在。", e); // 返回统一友好错误。
        }
    }

    private void validateFileSize(Path normalizedPath) { // 校验文件大小是否超过限制。
        long maxSizeBytes = resolveMaxFileSizeKb() * 1024L; // 将 KB 配置转换为字节。
        try {
            long fileSize = Files.size(normalizedPath); // 获取文件大小，不读取文件内容。
            if (fileSize > maxSizeBytes) { // 超过配置上限时拒绝。
                throw new ProjectPathAccessException("文件过大，超过限制。"); // 避免后续大文件进入模型上下文。
            }
        } catch (ProjectPathAccessException e) { // 业务拒绝异常保持原文案。
            throw e; // 继续抛出。
        } catch (IOException e) { // 文件大小读取失败。
            throw new ProjectPathAccessException("文件不存在。", e); // 返回统一友好错误。
        }
    }

    private long resolveMaxFileSizeKb() { // 解析单文件大小上限。
        Integer configuredValue = properties == null ? null : properties.getMaxFileSizeKb(); // 读取配置值。
        if (configuredValue == null || configuredValue <= 0) { // 配置缺失或非法时使用默认值。
            return DEFAULT_MAX_FILE_SIZE_KB; // 默认 512KB。
        }
        return configuredValue; // 返回有效配置值。
    }

    private Set<String> normalizeExtensionSet(Iterable<String> values) { // 将扩展名集合统一归一化。
        Set<String> normalizedValues = new HashSet<>(); // 使用 Set 提高匹配效率。
        if (values == null) { // 配置集合为空时返回空集合。
            return normalizedValues; // 返回空 Set。
        }
        for (String value : values) { // 遍历配置项。
            String normalizedValue = normalizeExtension(value); // 去点、去空白并转小写。
            if (!normalizedValue.isEmpty()) { // 跳过空配置。
                normalizedValues.add(normalizedValue); // 加入归一化结果。
            }
        }
        return normalizedValues; // 返回归一化集合。
    }

    private Set<String> normalizeFilenameSet(Iterable<String> values) { // 将敏感文件名集合统一归一化。
        Set<String> normalizedValues = new HashSet<>(); // 使用 Set 提高匹配效率。
        if (values == null) { // 配置集合为空时返回空集合。
            return normalizedValues; // 返回空 Set。
        }
        for (String value : values) { // 遍历配置项。
            String normalizedValue = trimToNull(value); // 去掉首尾空白。
            if (normalizedValue != null) { // 跳过空配置。
                normalizedValues.add(normalizedValue.toLowerCase(Locale.ROOT)); // 文件名按小写保存，做大小写不敏感匹配。
            }
        }
        return normalizedValues; // 返回归一化集合。
    }

    private String normalizeExtension(String ext) { // 归一化扩展名。
        String normalizedExt = trimToNull(ext); // 去掉首尾空白。
        if (normalizedExt == null) { // 扩展名为空。
            return ""; // 返回空字符串。
        }
        normalizedExt = normalizedExt.toLowerCase(Locale.ROOT); // 统一转小写。
        return normalizedExt.startsWith(".") ? normalizedExt.substring(1) : normalizedExt; // 去掉开头的点。
    }

    private String trimToNull(String value) { // 将空白字符串统一转换为 null。
        if (value == null || value.trim().isEmpty()) { // null 或空白字符串。
            return null; // 返回 null。
        }
        return value.trim(); // 返回去除首尾空白后的字符串。
    }

    /**
     * 项目工作区路径访问异常。
     *
     * <p>适用场景：ProjectPathGuard 在发现路径为空、路径穿越、绝对路径、敏感文件、
     * 非普通文件、不支持扩展名或文件过大时抛出该异常。后续 Controller 或 Tool 可以捕获它并转换为统一错误响应。</p>
     */
    public static class ProjectPathAccessException extends RuntimeException { // 项目路径安全校验失败异常。
        public ProjectPathAccessException(String message) { // 只携带友好错误文案的构造器。
            super(message); // 保存错误文案。
        }

        public ProjectPathAccessException(String message, Throwable cause) { // 携带底层异常的构造器。
            super(message, cause); // 保存错误文案和原因。
        }
    }
}
