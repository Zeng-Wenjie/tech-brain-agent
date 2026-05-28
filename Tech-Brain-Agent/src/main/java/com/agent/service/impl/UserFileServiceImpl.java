package com.agent.service.impl;

import com.agent.entity.UserFile;
import com.agent.mapper.UserFileMapper;
import com.agent.service.UserFileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 用户文件服务实现。
 *
 * <p>适用场景：负责 user_file 表的基础文件元数据保存、单条查询和列表查询，为后续文件上传接口提供持久化底座。</p>
 * <p>调用链：后续文件上传 Controller 构造 UserFile -> saveUserFile 写入 user_file；后续文件详情或列表接口
 * -> getByIdAndUserId/listByUserId -> UserFileMapper -> user_file 表，并始终叠加 userId 与 status=1 做用户隔离。</p>
 * <p>边界说明：本实现只访问已经存在的 user_file 表，不创建或修改数据库结构，不执行 SQL 脚本，不处理文件流，
 * 不提供上传/下载接口，不接入 AI、RAG 或 Tool Calling。</p>
 */
@Slf4j // 输出 [UserFile] 前缀日志，避免打印完整 storagePath 等过长敏感内容。
@Service // 注册为 Spring Bean，供后续文件业务注入使用。
public class UserFileServiceImpl extends ServiceImpl<UserFileMapper, UserFile> implements UserFileService { // 用户文件基础持久化服务实现。

    private static final int NORMAL_STATUS = 1; // 正常文件状态，查询时只返回 status=1 的记录。

    private static final String DEFAULT_STORAGE_TYPE = "LOCAL"; // 默认本地存储类型。

    private static final String DEFAULT_UPLOAD_SOURCE = "USER_UPLOAD"; // 默认用户上传来源。

    @Override // 保存用户文件基础元数据。
    public Long saveUserFile(UserFile userFile) {
        validateForSave(userFile); // 保存前校验必要字段，避免写入无法使用的文件记录。
        fillDefaults(userFile); // 填充默认状态、存储类型、上传来源和时间字段。

        log.info("[UserFile] save file record, userId: {}, originalName: {}",
                userFile.getUserId(), userFile.getOriginalName()); // 只打印用户 ID 和原始文件名，不打印完整 storagePath。
        save(userFile); // 使用 MyBatis-Plus 保存 user_file 记录，并回填数据库自增 ID。
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
}
