package com.ctds.evidence.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.errorcode.ErrorType;
import com.ctds.evidence.domain.EvidenceChainUnavailableException;
import com.ctds.evidence.domain.EvidenceErrorCodes;
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
 * 存证域出站映射（hifi §5）：1012 五码按精确 HTTP 状态出站（404 同码同文防枚举 / 400 参数 /
 * 409 状态冲突 / 503 链不可达 / 500 取号落库失败）；1000C0005 / 1000C0002 沿 common-auth
 * 精确 HTTP 语义（403 / 401）；请求体不可读与参数类型不匹配沿全局口径 → 1000C0001。
 * 对外文案一律常量（不暴露异常内部拼装细节）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class EvidenceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExceptionHandler.class);
    private static final String SYSTEM_MESSAGE = "系统繁忙，请稍后重试";
    private static final String PARAM_MESSAGE = "参数格式不合法";

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> onBizException(final BizException ex) {
        final String code = ex.getErrorCode().value();
        final HttpStatus status = switch (code) {
            case "1012C0001" -> HttpStatus.NOT_FOUND;
            case "1012C0002" -> HttpStatus.BAD_REQUEST;
            case "1012C0003" -> HttpStatus.CONFLICT;
            case "1012S0001" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "1012S0002" -> HttpStatus.INTERNAL_SERVER_ERROR;
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

    /**
     * 链不可达（核验取链上记录失败）→ 503 + 1012S0001（hifi §10 E2）：核验必须如实报
     * "链暂不可用"，不得伪报 MISMATCH / NOT_ON_CHAIN，也不得降级为 Boot 默认 500。
     * 此时尚未追加 VERIFIED 留痕，记录保持不变。
     */
    @ExceptionHandler(EvidenceChainUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> onChainUnavailable(
            final EvidenceChainUnavailableException ex) {
        log.error("evidence chain unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResult<>(EvidenceErrorCodes.CHAIN_UNAVAILABLE.value(),
                        EvidenceErrorCodes.CHAIN_UNAVAILABLE_MESSAGE,
                        ApiResult.currentTraceId(), null));
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

    private static String messageOf(final String code, final BizException ex) {
        return switch (code) {
            case "1000C0001" -> PARAM_MESSAGE;
            case "1012C0001" -> EvidenceErrorCodes.RECORD_NOT_FOUND_MESSAGE;
            case "1012C0002" -> EvidenceErrorCodes.PARAM_INVALID_MESSAGE;
            case "1012C0003" -> EvidenceErrorCodes.STATE_CONFLICT_MESSAGE;
            case "1012S0001" -> EvidenceErrorCodes.CHAIN_UNAVAILABLE_MESSAGE;
            case "1012S0002" -> EvidenceErrorCodes.STORAGE_FAILED_MESSAGE;
            default -> ex.getMessage();
        };
    }
}
