package com.agent.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户文件上传返回对象。
 *
 * <p>适用场景：文件上传成功、分页查询和详情查询时返回给前端的安全文件基础信息，包含文件 ID、原始文件名、
 * 扩展名、MIME 类型、文件分类、大小、存储类型、访问地址、MD5、状态、上传来源以及创建/更新时间。</p>
 * <p>调用链：UserFileServiceImpl 保存或查询 user_file 记录 -> 转换为 UserFileVO
 * -> UserFileController 通过 Result 返回给前端。</p>
 * <p>边界说明：本 VO 不返回 storedName 和 storagePath，避免暴露后端真实存储文件名和服务器真实存储路径；
 * 当前 accessUrl 可以为空，后续下载接口再补。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目 VO 风格一致。
public class UserFileVO { // 用户文件安全返回视图。

    private Long id; // 文件记录 ID。

    private String originalName; // 原始文件名，仅用于展示。

    private String fileExt; // 文件扩展名。

    private String mimeType; // 文件 MIME 类型。

    private String fileType; // 文件分类：IMAGE、DOCUMENT 或 OTHER。

    private Long fileSize; // 文件大小，单位字节。

    private String storageType; // 存储类型，当前为 LOCAL。

    private String accessUrl; // 文件访问地址，当前可为空。

    private String md5; // 文件 MD5 值。

    private Integer status; // 文件状态，1 表示正常。

    private String uploadSource; // 上传来源，例如 USER_UPLOAD。

    private LocalDateTime createTime; // 文件记录创建时间。

    private LocalDateTime updateTime; // 文件记录更新时间。
}
