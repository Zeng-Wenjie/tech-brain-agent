package com.agent.service;

import com.agent.entity.UserFile;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.UserFilePageRequest;
import com.agent.entity.vo.UserFileVO;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 用户文件服务接口。
 *
 * <p>适用场景：为用户文件上传接口提供本地文件保存、user_file 表基础保存和按用户隔离查询能力，屏蔽 Mapper 与存储路径细节。</p>
 * <p>调用链：UserFileController -> UserFileService.uploadFile -> UserFileServiceImpl 保存物理文件并写入 user_file；
 * 后续文件详情或列表能力继续通过 getByIdAndUserId/listByUserId 按 userId 做隔离读取。</p>
 * <p>边界说明：本接口只处理用户文件元数据、本地存储和文件访问前的权限校验，不解析文件内容，不接入 AI、RAG 或 Tool Calling。</p>
 */
public interface UserFileService extends IService<UserFile> { // 继承 MyBatis-Plus 通用 Service 能力。

    UserFileVO uploadFile(MultipartFile file); // 上传用户文件到本地目录，写入 user_file 表并返回安全基础信息。

    PageDTO<UserFileVO> pageMyFiles(UserFilePageRequest request); // 分页查询当前登录用户自己的正常文件。

    UserFileVO getMyFileDetail(Long id); // 查询当前登录用户自己的文件详情。

    void downloadFile(Long id, HttpServletResponse response); // 下载当前登录用户自己的原始文件，并由 Service 写入 attachment 响应。

    void previewFile(Long id, HttpServletResponse response); // 预览当前登录用户自己的原始文件，并由 Service 写入 inline 响应。

    UserFile getFileForAccess(Long id); // 下载或预览前按当前用户权限查询文件实体，供后端内部读取 storagePath。

    Long saveUserFile(UserFile userFile); // 保存用户文件基础元数据并返回数据库自增 ID。

    UserFile getByIdAndUserId(Long id, Long userId); // 按文件 ID 和用户 ID 查询正常状态文件，防止越权读取。

    List<UserFile> listByUserId(Long userId); // 查询指定用户的正常状态文件列表，按创建时间倒序返回。
}
