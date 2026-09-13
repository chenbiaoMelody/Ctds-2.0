package com.ctds.subject.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.subject.application.ReviewActionResult;
import com.ctds.subject.application.ReviewQueueItem;
import com.ctds.subject.application.ReviewService;
import com.ctds.subject.interfaces.dto.ReviewRejectionRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台人工审核端点（WBS-3.1.5 hifi 接口契约，规格 C-1.1 行为 5）：
 * 待审核清单 / 通过 / 驳回。全部端点仅 subject.review 授权角色可执行（行为 5 第 3 条，
 * 无权限 403 + 拒绝审计由 common-auth RBAC 强制）；申请人侧端点归属断言口径不变。
 */
@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(final ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 待审核主体清单（行为 5 第 1 条前半；固定 PENDING_REVIEW 过滤，申请时间升序）。 */
    @GetMapping(path = "/api/v1/subject/review/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.review")
    public ApiResult<PageResult<ReviewQueueItem>> queue(@RequestParam(required = false) final Integer pageNum,
            @RequestParam(required = false) final Integer pageSize) {
        return ApiResult.ok(reviewService.queue(PageQuery.of(pageNum, pageSize, null)));
    }

    /** 审核通过（行为 5 第 2 条）：待审核 → 已入驻；入驻后续 DID 签发归 C-1.2（规格边界）。 */
    @PostMapping(path = "/api/v1/subject/registrations/{subjectNo}/review/approval",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.review")
    public ApiResult<ReviewActionResult> approve(@PathVariable final String subjectNo) {
        return ApiResult.ok(reviewService.approve(subjectNo));
    }

    /** 审核驳回（行为 5 第 2 条）：理由必填，理由随流转留痕落库，申请人可修改后重新申请。 */
    @PostMapping(path = "/api/v1/subject/registrations/{subjectNo}/review/rejection",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("subject.review")
    public ApiResult<ReviewActionResult> reject(@PathVariable final String subjectNo,
            @Valid @RequestBody final ReviewRejectionRequest request) {
        return ApiResult.ok(reviewService.reject(subjectNo, request.reason()));
    }
}
