package com.ctds.contract.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.contract.application.UsageQueryService;
import com.ctds.contract.interfaces.dto.UsageViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 使用摘要读端点（WBS-3.4.5 hifi §6 R12）。注解权限点复用 {@code contract.read}
 * （合约域既有读面权限点，不新增权限点面）；可见性判定在应用服务单点——参与方（提供方/
 * 需求方）+ 治理（admin 角色头），非参与方与"不存在"同码同文逐字（1008C0012 防枚举）+
 * DENIED_ACCESS 留痕（沿 R6~R11 口径）。
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractUsageController {

    private final UsageQueryService usageQueryService;

    public ContractUsageController(final UsageQueryService usageQueryService) {
        this.usageQueryService = usageQueryService;
    }

    /** R12 本合约使用摘要（已用次数/上限 + 放行/拒绝统计 + 记录分页）。 */
    @GetMapping(path = "/{contractNo}/usage-summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("contract.read")
    public ApiResult<UsageViews.Summary> usageSummary(@PathVariable final String contractNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(UsageViews.of(usageQueryService.summary(contractNo,
                AuthContext.subject(), PageQuery.of(pageNum, pageSize, null))));
    }
}
