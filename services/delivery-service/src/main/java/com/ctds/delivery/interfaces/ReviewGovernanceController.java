package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.delivery.application.review.ReviewGovernanceService;
import com.ctds.delivery.interfaces.dto.ReviewViews.HideResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价运营治理端点（WBS-3.7.4 hifi §1 G-EV1）：隐藏留痕不物理删除（B5-4）；
 * 权限点复用 delivery.governance（仅 admin，Q4-A/Q5-A——详情页条目内嵌，零新页面）。
 */
@RestController
@RequestMapping("/api/v1/evaluations")
public class ReviewGovernanceController {

    private final ReviewGovernanceService governanceService;

    public ReviewGovernanceController(final ReviewGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    /** G-EV1 隐藏评价（已隐藏再隐藏 = 200 幂等成功且不重复留痕；目标不存在 1011C0007）。 */
    @PostMapping(path = "/{id}/hide", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.governance")
    public ApiResult<HideResult> hide(@PathVariable final long id) {
        return ApiResult.ok(governanceService.hide(id, AuthContext.subject()));
    }
}
