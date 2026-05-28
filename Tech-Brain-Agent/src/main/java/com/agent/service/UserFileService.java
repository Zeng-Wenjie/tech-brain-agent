package com.agent.service;

import com.agent.entity.UserFile;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 用户文件服务接口。
 *
 * <p>适用场景：为后续文件上传接口提供 user_file 表的基础保存和按用户隔离查询能力，屏蔽 Mapper 访问细节。</p>
 * <p>调用链：后续 Controller 或业务服务 -> UserFileService -> UserFileServiceImpl -> UserFileMapper -> user_file 表。</p>
 * <p>边界说明：本接口只定义用户文件元数据的基础持久化能力，不处理 MultipartFile，不执行上传、下载、解析、AI、RAG 或 Tool Calling。</p>
 */
public interface UserFileService extends IService<UserFile> { // 继承 MyBatis-Plus 通用 Service 能力。

    Long saveUserFile(UserFile userFile); // 保存用户文件基础元数据并返回数据库自增 ID。

    UserFile getByIdAndUserId(Long id, Long userId); // 按文件 ID 和用户 ID 查询正常状态文件，防止越权读取。

    List<UserFile> listByUserId(Long userId); // 查询指定用户的正常状态文件列表，按创建时间倒序返回。
}
