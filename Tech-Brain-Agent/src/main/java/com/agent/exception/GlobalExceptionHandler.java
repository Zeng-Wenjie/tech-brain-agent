package com.agent.exception;

import com.agent.config.FileUploadProperties;
import com.agent.entity.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @Autowired(required = false)
    private FileUploadProperties fileUploadProperties; // 读取用户文件上传大小上限，用于 multipart 解析阶段的友好错误。

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<String> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        int maxSizeMb = fileUploadProperties == null || fileUploadProperties.getMaxSizeMb() == null
                ? 20
                : fileUploadProperties.getMaxSizeMb(); // 配置缺失时使用默认 20MB。
        log.warn("[UserFile] upload rejected, file too large, maxSizeMb: {}", maxSizeMb); // 不打印文件名或路径。
        return Result.error(HttpServletResponse.SC_BAD_REQUEST, "文件大小不能超过 " + maxSizeMb + "MB"); // 返回统一文件过大错误。
    }

    @ExceptionHandler
    public Result handle(Exception e) {
        log.error("异常：", e);
        e.printStackTrace();
        return Result.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,"服务器发生异常");
    }
}
