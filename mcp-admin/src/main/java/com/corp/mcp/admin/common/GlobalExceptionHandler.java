package com.corp.mcp.admin.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一转换为 {@link Result} 响应体。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("biz exception: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return Result.error(40001, "参数校验失败: " + detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return Result.error(40001, "请求体格式错误: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public Result<Void> handleBadParam(Exception e) {
        return Result.error(40001, "请求参数错误: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("internal error", e);
        return Result.error(50000, "服务器内部错误");
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + " " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
    }
}
