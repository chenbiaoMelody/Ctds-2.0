package com.ctds.subject.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.subject.application.CertChannelUnavailableException;
import com.ctds.subject.domain.SubjectErrorCodes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 认证端点异常出站（独立 advice，沿 3.1.2 ValidationExceptionHandler 先例；common 组件零改动）：
 * ① 渠道不可用精确映射 503 并保留业务文案"认证服务暂不可用，请稍后重试"
 * （规格行为 3 第 5 条；全局 S 型脱敏会吞掉该文案，本地高优先级处理器先行）；
 * ② 容器级 multipart 超限（6MB）映射业务文案（评审视角 2 修复：不落 500"系统繁忙"）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class CertificationExceptionHandler {

    @ExceptionHandler(CertChannelUnavailableException.class)
    public ResponseEntity<ApiResult<Void>> onChannelUnavailable(final CertChannelUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResult<>(SubjectErrorCodes.CERT_CHANNEL_UNAVAILABLE.value(), ex.getMessage(),
                        ApiResult.currentTraceId(), null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> onUploadTooLarge(final MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResult<>(ErrorCodes.PARAM_INVALID.value(), "证照影像大小超出上限（≤5MB）",
                        ApiResult.currentTraceId(), null));
    }
}
