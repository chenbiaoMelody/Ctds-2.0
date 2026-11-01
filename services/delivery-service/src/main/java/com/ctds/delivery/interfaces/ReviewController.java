package com.ctds.delivery.interfaces;

import com.ctds.common.api.ApiResult;
import com.ctds.common.auth.AuthContext;
import com.ctds.common.auth.RequirePermission;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.delivery.application.review.ReviewCommandService;
import com.ctds.delivery.application.review.ReviewQueryService;
import com.ctds.delivery.domain.review.ReviewBizException;
import com.ctds.delivery.domain.review.ReviewErrorCodes;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationItem;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationList;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationStats;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationSubmitRequest;
import com.ctds.delivery.interfaces.dto.ReviewViews.ReviewResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品评价端点（WBS-3.7.4 hifi §1；W-EV1 / R-EV1 / R-EV2 / R-EV3）：
 * 提交与更新同端点（upsert，Q8-A）；防刷评 = 服务端交付资格硬校验（B5-1）；
 * 权限点复用 delivery.read（Q4-A 零新权限点）；身份取 AuthContext（演示期身份头）。
 */
@RestController
@RequestMapping("/api/v1/evaluations")
public class ReviewController {

    private final ReviewCommandService commandService;
    private final ReviewQueryService queryService;

    public ReviewController(final ReviewCommandService commandService,
            final ReviewQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /** W-EV1 提交/更新评价（一人一评 upsert；重复提交 = 更新，总数不变——B5-2）。 */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<ReviewResult> submit(@RequestBody final EvaluationSubmitRequest request) {
        // 必填字段前置判空（hifi §1 score 必填；沿 3.7.3 OrderCommandController 判空先例，
        // 评审 P1-1：缺失落 400 业务码，防拆箱 NPE 落 500 系统错误）
        if (request.productId() == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
        if (request.score() == null) {
            throw new ReviewBizException(ReviewErrorCodes.REVIEW_SCORE_INVALID,
                    ReviewErrorCodes.REVIEW_SCORE_INVALID_MESSAGE);
        }
        return ApiResult.ok(commandService.submit(request.productId(), request.score(),
                request.comment(), AuthContext.subject()));
    }

    /** R-EV1 产品评价统计（仅计未隐藏；均分 1 位小数 HALF_UP）。 */
    @GetMapping(path = "/products/{productId}/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<EvaluationStats> stats(@PathVariable final long productId) {
        return ApiResult.ok(queryService.stats(productId));
    }

    /** R-EV2 文字评价列表（时间倒序全列不分页，Q3-A 最小口径；hidden 恒 false）。 */
    @GetMapping(path = "/products/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<EvaluationList> list(@PathVariable final long productId) {
        return ApiResult.ok(queryService.list(productId));
    }

    /** R-EV3 本人评价回显（未评价 data=null；前端"修改评价"回显用）。 */
    @GetMapping(path = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("delivery.read")
    public ApiResult<EvaluationItem> mine(@RequestParam final long productId) {
        return ApiResult.ok(queryService.mine(productId, AuthContext.subject()));
    }
}
