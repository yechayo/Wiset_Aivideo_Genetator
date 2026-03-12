package com.comic.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一把异常转换成 Result 格式返回，Controller 层不需要 try-catch
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验失败（@Valid 触发）
     * 例：用户名为空、密码太短等
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(message);
    }

    /**
     * 处理业务异常（BusinessException）
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理AI调用异常
     */
    @ExceptionHandler(AiCallException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleAiException(AiCallException e) {
        log.error("AI调用失败: {}", e.getMessage());
        return Result.fail(503, "AI服务暂时不可用，请稍后重试");
    }

    /**
     * 处理其他运行时异常（用户名已存在、密码错误、Token 无效等）
     * Service 层直接 throw new RuntimeException("xxx") 即可
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.warn("运行时异常: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 兜底：未预期的异常，避免把堆栈信息暴露给前端
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return Result.fail(500, "服务器内部错误，请稍后再试");
    }
}
