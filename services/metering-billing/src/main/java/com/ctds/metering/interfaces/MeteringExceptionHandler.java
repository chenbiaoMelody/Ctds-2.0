package com.ctds.metering.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.errorcode.ErrorType;
import com.ctds.metering.domain.MeteringErrorCodes;
import com.ctds.metering.domain.billing.BillingErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 计量与计费域出站映射（WBS-3.7.5 hifi §4；WBS-3.7.6 hifi §7 续用同一服务级处理器）：
 * 精确语义码按精确 HTTP 状态出站（404 报文同码同文防枚举 / 409 幂等与 fail-closed 冲突 /
 * 400 参数与分成基数非法）；1000C0005/1000C0002 沿 common-auth 精确 HTTP 语义（403/401）；
 * 请求体不可读 / 参数类型不匹配 / 必填参数缺失 → 1000C0001；其余沿全局处理器口径
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
        // ADR-005 §3 第 7 项：精确语义码按精确 HTTP 状态出站（404/409/403/401），其余沿类型默认映射
        final HttpStatus status = switch (code) {
            case "1011C0008", "1011C0009", "1011C0014" -> HttpStatus.NOT_FOUND;
            case "1011C0010", "1011C0011", "1011C0012", "1011C0015" -> HttpStatus.CONFLICT;
            case "1000C0005" -> HttpStatus.FORBIDDEN;
            case "1000C0002" -> HttpStatus.UNAUTHORIZED;
            default -> null;
        };
        if (status != null) {
            return ResponseEntity.status(status)
                    .body(new ApiResult<>(code, messageOf(code, ex), ApiResult.currentTraceId(), null));
        }
        final ErrorType type = ex.getErrorCode().type();
        if (type == ErrorType.SYSTEM) {
            log.error("system error: {}", code, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResult<>(code, SYSTEM_MESSAGE, ApiResult.currentTraceId(), null));
        }
        return ResponseEntity.status(type.httpStatus())
                .body(new ApiResult<>(code, messageOf(code, ex), ApiResult.currentTraceId(), null));
    }

    /** 请求体不可读 / 必填参数缺失 / 查询参数类型不匹配 → 1000C0001（沿 common 口径）。 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResult<Void>> onArgumentError(final Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), PARAM_MESSAGE,
                        ApiResult.currentTraceId(), null));
    }

    /** 对外文案以常量兜底（同码同文；不向调用方暴露异常内部拼装细节）。 */
    private static String messageOf(final String code, final BizException ex) {
        return switch (code) {
            case "1011C0008" -> MeteringErrorCodes.METERING_NOT_VISIBLE_MESSAGE;
            case "1011C0009" -> BillingErrorCodes.BILL_NOT_VISIBLE_MESSAGE;
            case "1011C0010" -> BillingErrorCodes.BILL_DUPLICATED_MESSAGE;
            case "1011C0011" -> BillingErrorCodes.BILL_FROZEN_MESSAGE;
            case "1011C0012" -> BillingErrorCodes.BILL_STATUS_CONFLICT_MESSAGE;
            case "1011C0013" -> BillingErrorCodes.BILL_SHARE_BASE_INVALID_MESSAGE;
            case "1011C0014" -> BillingErrorCodes.CONTRACT_NOT_FOUND_MESSAGE;
            case "1011C0015" -> BillingErrorCodes.CONTRACT_NOT_BILLABLE_MESSAGE;
            default -> ex.getMessage();
        };
    }
}
