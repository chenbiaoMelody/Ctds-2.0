package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.contract.application.InternalUsageCheckService;
import com.ctds.contract.interfaces.dto.InternalUsageCheckViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部判定端点（WBS-3.5.2 hifi §4.1；沿 {@code /api/v1/{域}/internal/...} 惯例与
 * subject/catalog 内部只读端点先例）：供网关交付链在<b>转发前</b>获取判定结论与合约参与方摘要。
 *
 * <p>权限点 {@code contract.internal.read} 仅 {@code gateway-internal} 服务身份持有
 * （provider / admin 业务角色均不持有——判定面只对交付链服务身份开放，防按编号枚举判定）；
 * 出站映射沿 {@link ContractExceptionHandler} 既有映射（C0020→403 / C0013→409 / C0012→404 /
 * C0008→400），网关侧原码透传。</p>
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class InternalUsageCheckController {

    private final InternalUsageCheckService internalUsageCheckService;

    public InternalUsageCheckController(final InternalUsageCheckService internalUsageCheckService) {
        this.internalUsageCheckService = internalUsageCheckService;
    }

    /** 交付链判定（判定主体由请求体显式给出；幂等语义沿 ADR-020 §2.7：按调用计数，重试 = 新一次调用）。 */
    @PostMapping(path = "/internal/usage-checks", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.internal.read")
    public ApiResult<InternalUsageCheckViews.Result> check(
            @RequestBody final InternalUsageCheckViews.Request request) {
        final InternalUsageCheckViews.Result result = internalUsageCheckService.check(
                new InternalUsageCheckService.Command(request.contractNo(), request.requesterNo(),
                        request.actionType(), request.purpose(), request.territory()));
        return ApiResult.ok(result);
    }
}
