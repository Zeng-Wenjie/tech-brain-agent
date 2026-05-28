package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.vo.UserFileVO;
import com.agent.service.UserFileService;
import com.agent.utils.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户文件上传接口。
 *
 * <p>适用场景：接收当前登录用户上传的文件，完成后端大小校验、类型校验、本地保存、MD5 计算和 user_file 记录写入。</p>
 * <p>调用链：前端或接口调试工具 -> POST /api/files/upload -> UserFileController -> UserFileService.uploadFile
 * -> UserFileServiceImpl -> 本地文件系统 + UserFileMapper -> user_file 表。</p>
 * <p>边界说明：本 Controller 只提供文件上传入口，不提供下载接口，不解析文件内容，不修改数据库结构，不接入 AI、RAG 或 Tool Calling。</p>
 */
@Slf4j // 仅记录接口层必要失败信息，核心上传日志由 Service 输出。
@RestController // 注册为 Spring MVC 控制器。
@RequestMapping("/api/files") // 用户文件接口统一前缀。
@Tag(name = "用户文件接口")
public class UserFileController { // 用户文件上传 Controller。

    @Autowired
    private UserFileService userFileService; // 注入用户文件服务。

    @Operation(summary = "上传用户文件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) // 上传接口路径：POST /api/files/upload。
    public Result<UserFileVO> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
        Long currentUserId = UserContext.getUserId(); // 从登录上下文读取当前用户 ID。
        if (currentUserId == null) { // 未登录时不进入 Service 保存流程。
            return Result.error(HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录状态失效"); // 返回统一未登录错误。
        }
        try {
            return Result.success(userFileService.uploadFile(file)); // 调用 Service 完成保存和入库。
        } catch (IllegalArgumentException e) {
            return Result.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage()); // 参数、大小、类型错误返回 400。
        } catch (IllegalStateException e) {
            log.error("[UserFile] upload endpoint failed, userId: {}", currentUserId, e); // 服务端错误记录堆栈。
            return Result.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage()); // 文件系统或数据库错误返回友好提示。
        }
    }
}
