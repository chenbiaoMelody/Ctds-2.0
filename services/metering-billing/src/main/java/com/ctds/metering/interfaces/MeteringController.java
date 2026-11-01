package com.ctds.metering.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.metering.application.CollectOutcome;
import com.ctds.metering.application.ContractFlow;
import com.ctds.metering.application.ContractSummary;
import com.ctds.metering.application.MeteringCollectService;
import com.ctds.metering.application.MeteringQueryService;
import com.ctds.metering.application.ReconcileSnapshot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计量读面端点（WBS-3.7.5 hifi §1，全部 /api/v1/metering 前缀，ADR-005 统一响应）：
 * R-M0 触发采集（运营）/ R-M1 本方流水 / R-M2 本方聚合 / R-M3 四方勾稽读数（运营）。
 * 身份取 AuthContext（网关注入/演示期身份头）；本方读面可见性判定在服务端（B8-4）。
 */
@RestController
@RequestMapping("/api/v1/metering")
public class MeteringController {

    private final MeteringCollectService collectService;
    private final MeteringQueryService queryService;

    public MeteringController(final MeteringCollectService collectService,
            final MeteringQueryService queryService) {
        this.collectService = collectService;
        this.queryService = queryService;
    }

    /** R-M0 触发通道 2 增量采集（运营；可重复触发，重复 collected = 0 幂等）。 */
    @PostMapping(path = "/collect", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.governance")
    public ApiResult<CollectOutcome> collect() {
        return ApiResult.ok(collectService.collect());
    }

    /** R-M1 本方合约计量流水（B7-1 读面 + B8-4 归属校验服务端强制）。 */
    @GetMapping(path = "/contracts/{contractNo}/flows", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.read")
    public ApiResult<ContractFlow> flows(@PathVariable final String contractNo) {
        return ApiResult.ok(queryService.flows(contractNo, AuthContext.subject()));
    }

    /** R-M2 本方合约按自然月聚合（B8-1 查询承载，Q2-A/Q3-A）。 */
    @GetMapping(path = "/contracts/{contractNo}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.read")
    public ApiResult<ContractSummary> summary(@PathVariable final String contractNo) {
        return ApiResult.ok(queryService.summary(contractNo, AuthContext.subject()));
    }

    /** R-M3 四方勾稽读数（运营治理读面；纯读数展示，不做归属校验）。 */
    @GetMapping(path = "/contracts/{contractNo}/reconcile",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("metering.governance")
    public ApiResult<ReconcileSnapshot> reconcile(@PathVariable final String contractNo) {
        return ApiResult.ok(queryService.reconcile(contractNo));
    }
}
