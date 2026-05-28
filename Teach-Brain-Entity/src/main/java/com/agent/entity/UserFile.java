package com.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户文件持久化实体。
 *
 * <p>适用场景：用于映射数据库中已经存在的 user_file 表，保存用户上传文件的原始名称、存储名称、文件类型、大小、
 * 存储位置、访问地址、MD5、状态、上传来源以及创建/更新时间等基础元数据。</p>
 * <p>调用链：后续文件上传接口构造 UserFile -> UserFileService.saveUserFile -> UserFileServiceImpl
 * -> UserFileMapper -> user_file 表；文件列表或详情查询通过 UserFileService 按 userId 做隔离读取。</p>
 * <p>边界说明：本实体只描述 user_file 表字段映射，不执行 SQL，不创建表，不修改数据库结构，不接入上传、下载、解析、AI、RAG 或 Tool Calling。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持项目实体类风格一致。
@TableName("user_file") // 映射数据库已经存在的 user_file 表。
public class UserFile { // 用户文件基础持久化对象。

    @TableId(value = "id", type = IdType.AUTO) // 主键使用数据库自增 ID，禁止使用雪花算法。
    private Long id; // user_file.id。

    private Long userId; // 文件所属用户 ID，对应 user_id，用于用户数据隔离。

    private String originalName; // 用户上传时的原始文件名，对应 original_name。

    private String storedName; // 服务端存储文件名，对应 stored_name。

    private String fileExt; // 文件扩展名，对应 file_ext。

    private String mimeType; // 文件 MIME 类型，对应 mime_type。

    private String fileType; // 业务文件类型，对应 file_type。

    private Long fileSize; // 文件大小，单位字节，对应 file_size。

    private String storageType; // 存储类型，对应 storage_type，例如 LOCAL。

    private String storagePath; // 文件实际存储路径，对应 storage_path。

    private String accessUrl; // 文件访问地址，对应 access_url。

    private String md5; // 文件 MD5 值，对应 md5。

    private Integer status; // 文件状态，对应 status，1 表示正常。

    private String uploadSource; // 上传来源，对应 upload_source，例如 USER_UPLOAD。

    @TableField(fill = FieldFill.INSERT) // 插入时由 MyBatis-Plus 自动填充创建时间。
    private LocalDateTime createTime; // 记录创建时间，对应 create_time。

    @TableField(fill = FieldFill.INSERT_UPDATE) // 插入和更新时由 MyBatis-Plus 自动填充更新时间。
    private LocalDateTime updateTime; // 记录更新时间，对应 update_time。
}
