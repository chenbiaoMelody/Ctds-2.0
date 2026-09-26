package com.ctds.space.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.space.domain.SpaceBizException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 空间业务异常出站（独立 advice 沿 subject CertificationExceptionHandler 先例；common 组件零改动）：
 * 1006 段错误码精确映射 HTTP（hifi §2 状态列）——403 资格/越权、409 状态门槛/名称占用、
 * 404 不存在、400 要素/确认缺失、503 主体服务不可用（保留业务文案，不走全局 S 型脱敏）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SpaceExceptionHandler {

    /** 允许精确映射的 1006 段码值（SpaceErrorCodes 定稿集；新码须同步登记）。 */
    private static final Set<String> MAPPED_CODES = Set.of("1006C0001", "1006C0002", "1006C0003", "1006C0004",
            "1006C0005", "1006C0006", "1006C0007", "1006S0001");

    @ExceptionHandler(SpaceBizException.class)
    public ResponseEntity<ApiResult<Void>> onSpaceBizException(final SpaceBizException ex) {
        final String code = ex.getErrorCode().value();
        // 1006 段码表已定稿（hifi §2）；越集码值 = 编码缺陷，交全局处理器按默认映射兜底并暴露问题
        if (!MAPPED_CODES.contains(code)) {
            throw new BizException(ex.getErrorCode(), ex.getMessage(), ex);
        }
        return ResponseEntity.status(statusOf(code))
                .body(new ApiResult<>(code, ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    private static HttpStatus statusOf(final String code) {
        return switch (code) {
            case "1006C0001", "1006C0007" -> HttpStatus.FORBIDDEN;
            case "1006C0002", "1006C0003" -> HttpStatus.CONFLICT;
            case "1006C0004" -> HttpStatus.NOT_FOUND;
            case "1006C0005", "1006C0006" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;   // 1006S0001
        };
    }
}
