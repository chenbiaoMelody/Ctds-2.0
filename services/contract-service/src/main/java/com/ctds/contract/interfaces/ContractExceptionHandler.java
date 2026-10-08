package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.errorcode.BizException;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 合约业务异常出站（独立 advice 沿 catalog CatalogExceptionHandler 先例；common 组件零改动）：
 * 1008 段错误码精确映射 HTTP（hifi §3 状态列）——403 越权、404 不存在/不可用/未入驻防枚举、
 * 409 名称占用/状态门槛/并发、400 框架与参数、503 依赖服务不可用（保留业务文案，不走全局
 * S 型脱敏）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ContractExceptionHandler {

    /** 允许精确映射的 1008 段码值（ContractErrorCodes 定稿集；新码须同步登记——一致性由
     * interfaces 包测试锚定，码表↔处理器集合不得漂移，沿 catalog 先例）。
     * WBS-3.4.3 续延：C0010~C0019（协商与签署域）+ S0002/S0003（did/目录不可达）。 */
    static final Set<String> MAPPED_CODES = Set.of("1008C0001", "1008C0002", "1008C0003", "1008C0004",
            "1008C0005", "1008C0006", "1008C0007", "1008C0008", "1008C0009",
            "1008C0010", "1008C0011", "1008C0012", "1008C0013", "1008C0014", "1008C0015",
            "1008C0016", "1008C0017", "1008C0018", "1008C0019", "1008C0020",
            "1008S0001", "1008S0002", "1008S0003");

    @ExceptionHandler(ContractBizException.class)
    public ResponseEntity<ApiResult<Void>> onContractBizException(final ContractBizException ex) {
        final String code = ex.getErrorCode().value();
        // 1008 段码表已定稿（hifi §3）；越集码值 = 编码缺陷，交全局处理器按默认映射兜底并暴露问题
        if (!MAPPED_CODES.contains(code)) {
            throw new BizException(ex.getErrorCode(), ex.getMessage(), ex);
        }
        return ResponseEntity.status(statusOf(code))
                .body(new ApiResult<>(code, ex.getMessage(), ApiResult.currentTraceId(), null));
    }

    /**
     * 请求体不可读（JSON 语法错误 / 枚举绑定失败如非法 type）→ 1008C0008（hifi §3
     * "缺 name/type"承载，V1.2 补正⑥）；文案为服务端常量，不回显解析细节（章程 4.3）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> onUnreadableRequestBody(
            final HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiResult<>(ContractErrorCodes.TEMPLATE_PARAM_INVALID.value(),
                        ContractErrorCodes.TEMPLATE_PARAM_INVALID_MESSAGE,
                        ApiResult.currentTraceId(), null));
    }

    private static HttpStatus statusOf(final String code) {
        return switch (code) {
            case "1008C0002", "1008C0011", "1008C0020" -> HttpStatus.FORBIDDEN;
            case "1008C0001", "1008C0003", "1008C0006", "1008C0010", "1008C0012" -> HttpStatus.NOT_FOUND;
            case "1008C0005", "1008C0007", "1008C0009", "1008C0013", "1008C0017",
                 "1008C0019" -> HttpStatus.CONFLICT;
            case "1008C0004", "1008C0008", "1008C0014", "1008C0015", "1008C0016",
                 "1008C0018" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;   // 1008S0001 / 1008S0002 / 1008S0003
        };
    }
}
