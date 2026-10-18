package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.DeliveryBizException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 交付域出站映射（hifi §7.2）：1009 段码值精确映射 HTTP；合约域 1008C 结论码原码透传。
 * S 型文案为服务端常量（不含内部细节）；未知码交全局处理器兜底。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class DeliveryExceptionHandler {

    @ExceptionHandler(DeliveryBizException.class)
    public ResponseEntity<ApiResult<Void>> onDeliveryBizException(final DeliveryBizException ex) {
        final String code = ex.getErrorCode().value();
        return ResponseEntity.status(statusOf(code))
                .body(new ApiResult<>(code, ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    @ExceptionHandler(ContractPassThroughException.class)
    public ResponseEntity<ApiResult<Void>> onContractPassThrough(
            final ContractPassThroughException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiResult<>(ex.getCode(), ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> onValidation(final MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>("1009C0005", "交付请求参数不合法",
                        ApiResult.currentTraceId(), null));
    }

    private static HttpStatus statusOf(final String code) {
        return switch (code) {
            case "1009C0003", "1009C0009" -> HttpStatus.FORBIDDEN;
            case "1009C0005", "1009C0006", "1009C0008" -> HttpStatus.BAD_REQUEST;
            case "1009C0007" -> HttpStatus.NOT_FOUND;
            case "1009S0002" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
