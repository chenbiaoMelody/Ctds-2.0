package com.ctds.metering.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.errorcode.ErrorType;
import com.ctds.metering.domain.MeteringErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 计量域出站映射（hifi §4）：1011C0008 精确映射 404（同码同文防枚举）；1000C0005/1000C0002
 * 沿 common-auth 精确 HTTP 语义（403/401）；参数不合法 → 1000C0001；其余沿全局处理器口径
 * （C/B → 400 常量文案，S → 500 通用文案）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class MeteringExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MeteringExceptionHandler.class);
    private static final String SYSTEM_MESSAGE = "系统繁忙，请稍后重试";
    private static final String PARAM_MESSAGE = "参数格式不合法";

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> onBizException(final BizException ex) {
        final String code = ex.getErrorCode().value();
        // ADR-005 §3 第 7 项：精确语义码按精确 HTTP 状态出站（404/403/401），其余沿类型默认映射
        final HttpStatus status = switch (code) {
            case "1011C0008" -> HttpStatus.NOT_FOUND;
            case "1000C0005" -> HttpStatus.FORBIDDEN;
            case "1000C0002" -> HttpStatus.UNAUTHORIZED;
            default -> null;
        };
        if (status != null) {
            final String message = "1011C0008".equals(code)
                    ? MeteringErrorCodes.METERING_NOT_VISIBLE_MESSAGE : ex.getMessage();
            return ResponseEntity.status(status)
                    .body(new ApiResult<>(code, message, ApiResult.currentTraceId(), null));
        }
        final ErrorType type = ex.getErrorCode().type();
        if (type == ErrorType.SYSTEM) {
            log.error("system error: {}", code, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResult<>(code, SYSTEM_MESSAGE, ApiResult.currentTraceId(), null));
        }
        return ResponseEntity.status(type.httpStatus())
                .body(new ApiResult<>(code, ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    /** 查询参数/路径变量类型不匹配 → 1000C0001（沿 common 口径）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> onArgumentTypeMismatch(
            final MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), PARAM_MESSAGE,
                        ApiResult.currentTraceId(), null));
    }
}
