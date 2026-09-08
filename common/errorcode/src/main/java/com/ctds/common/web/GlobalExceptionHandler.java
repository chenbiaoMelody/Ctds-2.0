package com.ctds.common.web;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.errorcode.ErrorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：C/B 型业务异常按错误码映射 400 并透出业务文案；S 型与未知异常一律 500 + 通用文案，
 * 真实堆栈只进服务端日志，不对外暴露（章程 4.3）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String SYSTEM_MESSAGE = "系统繁忙，请稍后重试";

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> onBizException(final BizException ex) {
        final ErrorType type = ex.getErrorCode().type();
        if (type == ErrorType.SYSTEM) {
            log.error("system error: {}", ex.getErrorCode().value(), ex);
        }
        final String message = (type == ErrorType.SYSTEM) ? SYSTEM_MESSAGE : ex.getMessage();
        return ResponseEntity.status(type.httpStatus())
                .body(new ApiResult<>(ex.getErrorCode().value(), message, currentTraceId(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> onUnreadable(final HttpMessageNotReadableException ex) {
        log.warn("unreadable request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), "请求体格式不合法", currentTraceId(), null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> onMethodNotSupported(final HttpRequestMethodNotSupportedException ex) {
        log.warn("method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiResult<>(ErrorCodes.METHOD_NOT_ALLOWED.value(), "请求方法不支持", currentTraceId(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> onNoResource(final NoResourceFoundException ex) {
        log.warn("resource not found: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiResult<>(ErrorCodes.RESOURCE_NOT_FOUND.value(), "资源不存在", currentTraceId(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> onUnknownException(final Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(ErrorType.SYSTEM.httpStatus())
                .body(new ApiResult<>(ErrorCodes.INTERNAL_ERROR.value(), SYSTEM_MESSAGE, currentTraceId(), null));
    }

    private String currentTraceId() {
        return ApiResult.currentTraceId();
    }
}
