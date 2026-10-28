package com.ctds.connector.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.connector.domain.ConnectorAdapterProbeException;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorUnavailableException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorExceptionHandler.class);

    @ExceptionHandler(ConnectorUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> onUnavailable(final ConnectorUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResult<>(ex.getErrorCode().value(), ex.getMessage(),
                        ApiResult.currentTraceId(), null));
    }

    /**
     * 适配器非预期失败（hifi §7-③ / ADR-029 §2.5）：502 + 中性文案，不归为三类业务原因、不暴露内部实现。
     *
     * <p>码位复用平台通用 {@code 1000S9999}（S 型语义 = 系统侧不可用），状态码按"上游适配器失败"语义
     * 覆写为 502；该覆写为 ADR-029 §2.5 明列的既有约定，不新增 1010 段码（零突破"+2 码"契约）。
     * 根因只记异常类名（不记 message，避免地址 / 凭据进入日志）。</p>
     */
    @ExceptionHandler(ConnectorAdapterProbeException.class)
    public ResponseEntity<ApiResult<Void>> onProbeFailed(final ConnectorAdapterProbeException ex) {
        LOG.error("源适配器探测失败（出站 502）: {}",
                ex.getCause() == null ? "unknown" : ex.getCause().getClass().getName());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiResult<>(ErrorCodes.INTERNAL_ERROR.value(), ex.getMessage(),
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
                .body(new ApiResult<>(ConnectorErrorCodes.PARAM_INVALID.value(), message,
                        ApiResult.currentTraceId(), null));
    }
}
