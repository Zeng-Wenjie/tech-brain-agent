package com.agent.exception;

import com.agent.entity.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler
    public Result handle(Exception e) {
        log.error("异常：", e);
        e.printStackTrace();
        return Result.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,"服务器发生异常");
    }
}
