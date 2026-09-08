package com.ctds.common.auth;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.ErrorCodes;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 鉴权错误的 HTTP 精确映射（ADR-005 §3 第 7 项）：UNAUTHORIZED → 401、FORBIDDEN → 403，
 * 对外文案为服务端常量（不回显输入、不暴露内部实现）。只处理 AuthException，
 * 其余 BizException 原样由 GlobalExceptionHandler（无 @Order，最后序）按默认映射处理。
 */
@Order(100)
@RestControllerAdvice
public class AuthAdvice {

    public static final String UNAUTHORIZED_MESSAGE = "认证失败或身份已失效";
    public static final String FORBIDDEN_MESSAGE = "无权限执行该操作";

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResult<Void>> onAuthException(final AuthException ex) {
        // 二值映射：AuthException 构造已限定两码（评审①P3-2），不存在第三种映射分支
        final HttpStatus status = ErrorCodes.UNAUTHORIZED.equals(ex.getErrorCode())
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status)
                .body(new ApiResult<>(ex.getErrorCode().value(), ex.getMessage(), currentTraceId(), null));
    }

    private String currentTraceId() {
        return ApiResult.currentTraceId();
    }
}
