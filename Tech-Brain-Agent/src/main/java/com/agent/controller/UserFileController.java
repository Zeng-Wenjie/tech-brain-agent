package com.agent.controller;

import com.agent.entity.Result;
import com.agent.entity.dto.PageDTO;
import com.agent.entity.dto.UserFilePageRequest;
import com.agent.entity.vo.UserFileVO;
import com.agent.service.UserFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户文件接口。
 *
 * <p>适用场景：接收当前登录用户上传文件，并提供当前用户文件分页列表、文件详情、原文件下载和原文件预览响应。</p>
 * <p>调用链：前端或接口调试工具 -> UserFileController -> UserFileService
 * -> UserFileServiceImpl -> 本地文件系统或 UserFileMapper -> user_file 表。</p>
 * <p>边界说明：本 Controller 只负责 HTTP 参数接收和统一返回包装，文件存储、查询、下载、预览等业务逻辑全部下沉到 UserFileServiceImpl；
 * 不返回 storagePath，不解析文件内容，不转换文件格式，不修改数据库结构，不接入 AI、RAG 或 Tool Calling。</p>
 */
@Slf4j // 仅记录接口层必要失败信息，核心上传日志由 Service 输出。
@RestController // 注册为 Spring MVC 控制器。
@RequestMapping("/api/files") // 用户文件接口统一前缀。
@Tag(name = "用户文件接口")
public class UserFileController { // 用户文件 Controller。

    @Autowired
    private UserFileService userFileService; // 注入用户文件服务。

    @Operation(summary = "分页查询当前用户文件列表")
    @GetMapping("/page") // 分页查询接口路径：GET /api/files/page。
    public Result<PageDTO<UserFileVO>> page(@ParameterObject UserFilePageRequest request) {
        try {
            return Result.success(userFileService.pageMyFiles(request)); // 文件分页业务由 Service 处理。
        } catch (IllegalArgumentException e) {
            return Result.error(resolveRequestErrorStatus(e.getMessage()), e.getMessage()); // 将 Service 参数或权限错误包装为统一返回。
        }
    }

    @Operation(summary = "查询当前用户文件详情")
    @GetMapping("/{id}") // 文件详情接口路径：GET /api/files/{id}。
    public Result<UserFileVO> detail(@PathVariable("id") Long id) {
        try {
            return Result.success(userFileService.getMyFileDetail(id)); // 文件详情业务由 Service 处理。
        } catch (IllegalArgumentException e) {
            return Result.error(resolveRequestErrorStatus(e.getMessage()), e.getMessage()); // 将 Service 参数或权限错误包装为统一返回。
        }
    }

    @Operation(summary = "下载当前用户文件")
    @GetMapping("/{id}/download") // 下载接口路径：GET /api/files/{id}/download。
    public void download(@PathVariable("id") Long id, HttpServletResponse response) {
        userFileService.downloadFile(id, response); // 业务逻辑下沉到 Service，Controller 只转发 HTTP 请求。
    }

    @Operation(summary = "预览当前用户文件")
    @GetMapping("/{id}/preview") // 预览接口路径：GET /api/files/{id}/preview。
    public void preview(@PathVariable("id") Long id, HttpServletResponse response) {
        userFileService.previewFile(id, response); // 业务逻辑下沉到 Service，Controller 只转发 HTTP 请求。
    }

    @Operation(summary = "上传用户文件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) // 上传接口路径：POST /api/files/upload。
    public Result<UserFileVO> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            return Result.success(userFileService.uploadFile(file)); // 文件上传业务由 Service 处理。
        } catch (IllegalArgumentException e) {
            return Result.error(resolveRequestErrorStatus(e.getMessage()), e.getMessage()); // 将 Service 参数或权限错误包装为统一返回。
        } catch (IllegalStateException e) {
            log.error("[UserFile] upload endpoint failed", e); // 服务端错误记录堆栈。
            return Result.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage()); // 文件系统或数据库错误返回友好提示。
        }
    }

    private int resolveRequestErrorStatus(String message) {
        if ("未登录或登录状态失效".equals(message)) { // Service 判断为未登录。
            return HttpServletResponse.SC_UNAUTHORIZED; // 返回 401。
        }
        if ("文件不存在或无权访问".equals(message)) { // Service 判断为文件不存在或越权。
            return HttpServletResponse.SC_NOT_FOUND; // 返回 404，避免暴露越权细节。
        }
        return HttpServletResponse.SC_BAD_REQUEST; // 其它业务参数错误返回 400。
    }
}
