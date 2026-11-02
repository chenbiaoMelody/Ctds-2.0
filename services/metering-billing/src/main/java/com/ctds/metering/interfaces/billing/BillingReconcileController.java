package com.ctds.metering.interfaces.billing;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.metering.application.billing.BillingPeriods;
import com.ctds.metering.application.billing.BillingQueryService;
import com.ctds.metering.application.billing.BillingReconcileService;
import com.ctds.metering.application.billing.ReconciliationView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账端点（hifi §1）：B-W4 执行对账（运营；差异 → 冻结 fail-closed）/
 * B-R4 本方合约对账结论（参与方或运营；非本方 1011C0009）。
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingReconcileController {

    /** 运营角色标识（服务级 yml 角色映射口径，沿合约 / 连接器域先例）。 */
    private static final String ADMIN_ROLE = "admin";

    private final BillingReconcileService reconcileService;
    private final BillingQueryService queryService;

    public BillingReconcileController(final BillingReconcileService reconcileService,
            final BillingQueryService queryService) {
        this.reconcileService = reconcileService;
        this.queryService = queryService;
    }

    /** B-W4 执行对账（period 必填；差异 → 该合约该周期账单冻结）。 */
    @PostMapping(path = "/contracts/{contractNo}/reconciliations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("billing.governance")
    public ApiResult<ReconciliationView> reconcile(@PathVariable final String contractNo,
            @RequestParam final String period) {
        return ApiResult.ok(reconcileService.reconcile(contractNo, period, AuthContext.subject()));
    }

    /** B-R4 本方合约对账结论分页（B12-1 对账结论与差异明细可读）。 */
    @GetMapping(path = "/contracts/{contractNo}/reconciliations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("billing.read")
    public ApiResult<PageResult<ReconciliationView>> list(@PathVariable final String contractNo,
            @RequestParam(required = false) final String period,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        final String normalizedPeriod = (period == null || period.isBlank()) ? null
                : BillingPeriods.text(BillingPeriods.parse(period.trim()));
        return ApiResult.ok(queryService.reconciliations(contractNo, normalizedPeriod,
                AuthContext.subject(), AuthContext.roles().contains(ADMIN_ROLE),
                PageQuery.of(pageNum, pageSize, null)));
    }
}
