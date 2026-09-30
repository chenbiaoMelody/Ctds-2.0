package com.ctds.catalog.interfaces;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 目录业务异常出站（独立 advice 沿 space SpaceExceptionHandler 先例；common 组件零改动）：
 * 1007 段错误码精确映射 HTTP（hifi §2 状态列）——403 越权、409 名称占用/状态门槛/重要数据拒收/
 * 级别放宽/终态、404 不存在或无权、400 名称非法、503 依赖服务不可用（保留业务文案，不走全局 S 型脱敏）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class CatalogExceptionHandler {

    /** 允许精确映射的 1007 段码值（CatalogErrorCodes 定稿集；新码须同步登记——一致性由
     * interfaces 包测试锚定，码表↔处理器集合不得漂移）。 */
    static final Set<String> MAPPED_CODES = Set.of("1007C0001", "1007C0002", "1007C0003", "1007C0004",
            "1007C0005", "1007C0006", "1007C0007", "1007C0008", "1007C0009", "1007C0010",
            "1007S0001", "1007S0002");

    @ExceptionHandler(CatalogBizException.class)
    public ResponseEntity<ApiResult<Void>> onCatalogBizException(final CatalogBizException ex) {
        final String code = ex.getErrorCode().value();
        // 1007 段码表已定稿（hifi §2）；越集码值 = 编码缺陷，交全局处理器按默认映射兜底并暴露问题
        if (!MAPPED_CODES.contains(code)) {
            throw new BizException(ex.getErrorCode(), ex.getMessage(), ex);
        }
        return ResponseEntity.status(statusOf(code))
                .body(new ApiResult<>(code, ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    private static HttpStatus statusOf(final String code) {
        return switch (code) {
            case "1007C0006" -> HttpStatus.FORBIDDEN;
            case "1007C0001", "1007C0002", "1007C0003", "1007C0004", "1007C0007" -> HttpStatus.CONFLICT;
            case "1007C0005", "1007C0010" -> HttpStatus.NOT_FOUND;
            case "1007C0008", "1007C0009" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;   // 1007S0001 / 1007S0002
        };
    }
}
