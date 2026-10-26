package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorUnavailableException;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 连接器端点异常出站（独立 advice，沿 subject CertificationExceptionHandler 先例；common 组件零改动）：
 * ① 跨域依赖不可用精确映射 503 并保留业务文案（全局 S 型处理器会脱敏为"系统繁忙"，会丢掉"哪一项不可用"）；
 * ② 请求体校验失败映射 400 + 平台参数码（全局处理器未覆盖 MethodArgumentNotValidException）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ConnectorExceptionHandler {

    @ExceptionHandler(ConnectorUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> onUnavailable(final ConnectorUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResult<>(ex.getErrorCode().value(), ex.getMessage(),
                        ApiResult.currentTraceId(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> onInvalid(final MethodArgumentNotValidException ex) {
        final String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(ConnectorErrorCodes.PARAM_INVALID_MESSAGE);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), message,
                        ApiResult.currentTraceId(), null));
    }
}
