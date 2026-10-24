package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.application.SandboxQueryService;
import com.ctds.delivery.application.SandboxReviewService;
import com.ctds.delivery.interfaces.dto.SandboxRequest.ReviewRequest;
import com.ctds.delivery.interfaces.dto.SandboxViews.ResultView;
import com.ctds.delivery.interfaces.dto.SandboxViews.ReviewView;
import com.ctds.delivery.interfaces.dto.SandboxViews.SandboxView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱治理端点（hifi §7.1 / WBS-3.5.5 hifi §4·§6.1）：运营方例外查看（读即留痕）+
 * 结果物审核（治理唯一写面，sandbox.review 读写分权）。不含内容、路径、容器、镜像。
 */
@RestController
@RequestMapping("/api/v1/sandboxes/governance")
public class SandboxGovernanceController {

    private final SandboxQueryService queryService;
    private final SandboxReviewService reviewService;

    public SandboxGovernanceController(final SandboxQueryService queryService,
            final SandboxReviewService reviewService) {
        this.queryService = queryService;
        this.reviewService = reviewService;
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

    /** 结果物审核（治理唯一写面；结论一经作出不可修改）。 */
    @PostMapping(path = "/{sandboxNo}/results/{fileName}/actions/review",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.review")
    public ApiResult<ResultView> review(@PathVariable final String sandboxNo,
            @PathVariable final String fileName,
            @RequestBody(required = false) final ReviewRequest request) {
        return ApiResult.ok(reviewService.review(sandboxNo, fileName,
                request == null ? null : request.conclusion(),
                request == null ? null : request.reason(), AuthContext.subject()));
    }

    /** 审核留痕列表（读即留痕；按文件 + 轮次返回）。 */
    @GetMapping(path = "/{sandboxNo}/reviews", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.governance")
    public ApiResult<PageResult<ReviewView>> reviews(@PathVariable final String sandboxNo,
            @RequestParam(required = false) final String fileName,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.governanceReviews(sandboxNo, fileName,
                PageQuery.of(pageNum, pageSize, null), AuthContext.subject()));
    }
}
