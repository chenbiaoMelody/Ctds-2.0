package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.delivery.application.SandboxExportCommand;
import com.ctds.delivery.application.SandboxExportService;
import com.ctds.delivery.application.SandboxQueryService;
import com.ctds.delivery.application.SandboxResultCommand;
import com.ctds.delivery.application.SandboxReviewService;
import com.ctds.delivery.interfaces.dto.DeliveryViews.ExportView;
import com.ctds.delivery.interfaces.dto.SandboxRequest.ExportRequest;
import com.ctds.delivery.interfaces.dto.SandboxViews.ResultView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱结果物端点（WBS-3.5.5 hifi §6.1）：需求方提交审核 / 审批后导出 / 导出记录列表。
 * 加密包领取复用交付包下载端点（零新下载代码）；一切判定在服务端。
 */
@RestController
@RequestMapping("/api/v1/sandboxes")
public class SandboxResultController {

    private final SandboxReviewService reviewService;
    private final SandboxExportService exportService;
    private final SandboxQueryService queryService;

    public SandboxResultController(final SandboxReviewService reviewService,
            final SandboxExportService exportService, final SandboxQueryService queryService) {
        this.reviewService = reviewService;
        this.exportService = exportService;
        this.queryService = queryService;
    }

    /** 需求方提交审核（幂等键必填；缺失 / 非法由服务层收敛 1009C0005）。 */
    @PostMapping(path = "/{sandboxNo}/results/{fileName}/actions/submit-review",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.use")
    public ApiResult<ResultView> submitReview(@PathVariable final String sandboxNo,
            @PathVariable final String fileName,
            @RequestHeader(value = "X-Idempotency-Key", required = false)
            final String idempotencyKey) {
        return ApiResult.ok(reviewService.submit(new SandboxResultCommand(sandboxNo, fileName,
                AuthContext.subject(), idempotencyKey)));
    }

    /** 需求方导出（幂等键必填；body 携带可选策略要素；返回导出记录视图）。 */
    @PostMapping(path = "/{sandboxNo}/results/{fileName}/actions/export",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.use")
    public ApiResult<ExportView> export(@PathVariable final String sandboxNo,
            @PathVariable final String fileName,
            @RequestHeader(value = "X-Idempotency-Key", required = false)
            final String idempotencyKey,
            @RequestBody(required = false) final ExportRequest request) {
        return ApiResult.ok(exportService.export(new SandboxExportCommand(sandboxNo, fileName,
                request == null ? null : request.purpose(),
                request == null ? null : request.territory(), AuthContext.subject(),
                idempotencyKey)));
    }

    /** 导出记录列表（参与者 = 需求方 / 提供方；分页；pageSize 上限 100）。 */
    @GetMapping(path = "/{sandboxNo}/exports", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("sandbox.read")
    public ApiResult<PageResult<ExportView>> exports(@PathVariable final String sandboxNo,
            @RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(queryService.exports(sandboxNo, AuthContext.subject(),
                PageQuery.of(pageNum, pageSize, null)));
    }
}
