package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.contract.application.InternalContractSummaryService;
import com.ctds.contract.interfaces.dto.InternalContractSummaryViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约域内部只读摘要端点（WBS-3.5.3 hifi §4.1）：交付服务准入/形态校验专用；
 * 权限点 {@code contract.internal.read} 仅 gateway-internal / delivery-internal 持有。
 */
@RestController
@RequestMapping("/api/v1/contracts/internal/contract-summaries")
public class InternalContractSummaryController {

    private final InternalContractSummaryService summaryService;

    public InternalContractSummaryController(final InternalContractSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping(path = "/{contractNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.internal.read")
    public ApiResult<InternalContractSummaryViews.Summary> summary(
            @PathVariable final String contractNo,
            @RequestParam final String requesterNo) {
        return ApiResult.ok(summaryService.summary(contractNo, requesterNo));
    }
}
