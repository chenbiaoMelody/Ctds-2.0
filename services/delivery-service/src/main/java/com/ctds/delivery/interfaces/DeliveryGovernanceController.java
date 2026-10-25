package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.application.DeliveryQueryService;
import com.ctds.delivery.interfaces.dto.DeliveryViews.ActionLogView;
import com.ctds.delivery.interfaces.dto.DeliveryViews.DeliveryView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 交付治理读面（平台运营方；唯一治理例外，读即留痕）。 */
@RestController
@RequestMapping("/api/v1/deliveries/governance")
public class DeliveryGovernanceController {

    private final DeliveryQueryService queryService;

    public DeliveryGovernanceController(final DeliveryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.governance")
    public ApiResult<PageResult<DeliveryView>> list(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String deliveryNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize,
            @RequestParam(required = false) final String orderBy) {
        final PageQuery page = PageQuery.of(pageNum, pageSize, orderBy);
        return ApiResult.ok(queryService.governanceList(contractNo, deliveryNo, page,
                AuthContext.subject()));
    }

    @GetMapping(path = "/{deliveryNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.governance")
    public ApiResult<DeliveryView> get(@PathVariable final String deliveryNo) {
        return ApiResult.ok(queryService.governanceGet(deliveryNo, AuthContext.subject()));
    }

    /**
     * G7b 合约动作留痕分页（WBS-3.5.6 Q5-A 治理路径）：治理例外读全量合约留痕；
     * 读即留痕（GOVERNANCE_READ）；无该合约交付记录时为空页（治理列表语义，不防枚举拒绝）。
     */
    @GetMapping(path = "/{contractNo}/action-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.governance")
    public ApiResult<PageResult<ActionLogView>> actionLogs(
            @PathVariable final String contractNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.governanceActionLogs(contractNo, AuthContext.subject(),
                PageQuery.of(pageNum, pageSize, null)));
    }
}
