package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.application.SandboxQueryService;
import com.ctds.delivery.interfaces.dto.SandboxViews.SandboxView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱治理读面（hifi §7.1，行为 7 规则 2）：运营方例外查看，<b>读即留痕</b>
 * （sandbox.governance.read）；不含内容、路径、容器、镜像。
 */
@RestController
@RequestMapping("/api/v1/sandboxes/governance")
public class SandboxGovernanceController {

    private final SandboxQueryService queryService;

    public SandboxGovernanceController(final SandboxQueryService queryService) {
        this.queryService = queryService;
    }

    /** 治理列表（读即留痕）。 */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.governance")
    public ApiResult<PageResult<SandboxView>> list(
            @RequestParam(required = false) final String contractNo,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.governanceList(contractNo, status,
                PageQuery.of(pageNum, pageSize, null), AuthContext.subject()));
    }

    /** 治理详情（读即留痕）。 */
    @GetMapping(path = "/{sandboxNo}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.governance")
    public ApiResult<SandboxView> get(@PathVariable final String sandboxNo) {
        return ApiResult.ok(queryService.governanceGet(sandboxNo, AuthContext.subject()));
    }
}
