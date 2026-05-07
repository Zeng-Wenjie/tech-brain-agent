package com.agent.exception;

import com.agent.entity.Result; // 换成你自己的 Result 路径 / Replace with your own Result path if needed.
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 专门拦截 @Valid 校验失败的异常
     * Handle validation failures from @Valid.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> handleValidationException(MethodArgumentNotValidException e) {
        // 拿到我们在 DTO 里写的 message（比如 "用户名不能为空"）
        // Read the validation message defined in the DTO.
        FieldError fieldError = e.getBindingResult().getFieldError();
        String errorMessage = fieldError != null ? fieldError.getDefaultMessage() : "参数错误";
        log.warn("参数校验失败: {}", errorMessage);
        return Result.error(HttpServletResponse.SC_BAD_REQUEST,errorMessage);
    }
}
