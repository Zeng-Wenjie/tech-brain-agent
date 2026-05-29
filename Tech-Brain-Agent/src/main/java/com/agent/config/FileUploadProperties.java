package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地文件上传配置属性。
 *
 * <p>适用场景：绑定 application.yml 中 techbrain.file 配置，为 UserFileServiceImpl 提供上传根目录、
 * 单文件大小上限、允许扩展名和允许 MIME 类型。</p>
 * <p>调用链：Spring Boot 启动时绑定 techbrain.file -> FileUploadProperties -> UserFileServiceImpl
 * -> uploadFile 校验并保存用户文件。</p>
 * <p>边界说明：本配置只服务用户文件本地上传，不修改头像 OSS 上传配置，不创建数据库表，不接入 AI、RAG 或 Tool Calling。</p>
 */
@Data // 使用 Lombok 生成配置字段 getter/setter。
@Component // 注册为 Spring Bean，供 UserFileServiceImpl 注入使用。
@ConfigurationProperties(prefix = "techbrain.file") // 绑定 techbrain.file.* 配置项。
public class FileUploadProperties { // 用户文件本地上传配置对象。

    private String uploadDir = "./data/uploads"; // 本地上传根目录，默认写入项目运行目录下的 data/uploads。

    private Integer maxSizeMb = 20; // 单文件最大大小，单位 MB，默认 20MB。

    private List<String> allowedExtensions = new ArrayList<>(List.of( // 允许上传的文件扩展名。
            "pdf",
            "doc",
            "docx",
            "txt",
            "md",
            "png",
            "jpg",
            "jpeg",
            "webp",
            "py",
            "java",
            "js",
            "json",
            "xml",
            "html",
            "css"
    ));

    private List<String> allowedMimeTypes = new ArrayList<>(List.of( // 允许上传的 MIME 类型。
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "image/png",
            "image/jpeg",
            "image/webp",
            "text/x-python",
            "text/x-java-source",
            "application/javascript",
            "text/javascript",
            "application/json",
            "application/xml",
            "text/xml",
            "text/html",
            "text/css"
    ));
}
