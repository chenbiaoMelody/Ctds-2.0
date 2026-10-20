package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.delivery.domain.ContractPassThroughException;
import com.ctds.delivery.domain.DeliveryBizException;
import com.ctds.delivery.domain.DeliveryErrorCodes;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 交付域出站映射（hifi §7.2）：1009 段码值精确映射 HTTP；合约域 1008C 结论码原码透传。
 * S 型文案为服务端常量（不含内部细节）；未知码交全局处理器兜底。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class DeliveryExceptionHandler {

    /** 允许精确映射的 1009 段码值（DeliveryErrorCodes 定稿集；新码须同步登记，
     * 一致性由 interfaces 包测试锚定，码表↔处理器集合不得漂移）。 */
    static final Set<String> MAPPED_CODES = Set.of(
            DeliveryErrorCodes.REQUESTER_NOT_ALLOWED.value(),
            DeliveryErrorCodes.DELIVERY_PARAM_INVALID.value(),
            DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH.value(),
            DeliveryErrorCodes.DELIVERY_NOT_VISIBLE.value(),
            DeliveryErrorCodes.FILE_TOO_LARGE.value(),
            DeliveryErrorCodes.PACKAGE_NOT_ALLOWED.value(),
            DeliveryErrorCodes.DELIVERY_DEPENDENCY_UNAVAILABLE.value(),
            DeliveryErrorCodes.PACKAGE_OPERATION_FAILED.value());

    @ExceptionHandler(DeliveryBizException.class)
    public ResponseEntity<ApiResult<Void>> onDeliveryBizException(final DeliveryBizException ex) {
        final String code = ex.getErrorCode().value();
        // 1009 段码表已定稿；越集码值 = 编码缺陷，交全局处理器兜底并暴露问题
        if (!MAPPED_CODES.contains(code)) {
            throw new BizException(ex.getErrorCode(), ex.getMessage(), ex);
        }
        return ResponseEntity.status(statusOf(code))
                .body(new ApiResult<>(code, ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    @ExceptionHandler(ContractPassThroughException.class)
    public ResponseEntity<ApiResult<Void>> onContractPassThrough(
            final ContractPassThroughException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiResult<>(ex.getCode(), ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    /** 请求体不可读（JSON 语法错误 / 枚举绑定失败）→ 1009C0005（文案为服务端常量）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> onUnreadableRequestBody(
            final HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(DeliveryErrorCodes.DELIVERY_PARAM_INVALID.value(),
                        DeliveryErrorCodes.DELIVERY_PARAM_INVALID_MESSAGE,
                        ApiResult.currentTraceId(), null));
    }

    /** 查询参数类型不匹配（pageSize/pageNum 非数字）→ 1000C0001（沿 common-pagination 口径）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> onArgumentTypeMismatch(
            final MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), "参数格式不合法",
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
