package com.ctds.delivery.interfaces.dto;

import com.ctds.delivery.domain.review.ProductReview;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评价域出站视图与请求体（hifi §1 契约表；沿 DeliveryViews 嵌套 record 先例）。
 * createdAt/updatedAt 沿统一 ISO 序列化口径；评价人展示 = requesterNo 原样（零 subject 依赖）。
 */
public final class ReviewViews {

    /** W-EV1 请求体（requestId 客户端生成 UUID，透传字段——Q8-A 零幂等组件，服务端不消费）。 */
    public record EvaluationSubmitRequest(Long productId, Integer score, String comment,
            String requestId) {
    }

    /** W-EV1 响应（updated: 首次 false / 更新 true）。 */
    public record ReviewResult(long reviewId, long productId, int score, String comment,
            LocalDateTime createdAt, boolean updated) {

        public static ReviewResult of(final ProductReview review, final boolean updated) {
            return new ReviewResult(review.id(), review.productId(), review.score(),
                    review.comment(), review.createdAt(), updated);
        }
    }

    /** R-EV1 统计响应（仅计未隐藏；averageScore 1 位小数 HALF_UP，无评价 = null + 0）。 */
    public record EvaluationStats(long productId, BigDecimal averageScore, int reviewCount) {
    }

    /** R-EV2/R-EV3 条目（hidden 如实回传；列表面恒 false——隐藏项不出列）。 */
    public record EvaluationItem(long reviewId, String requesterNo, int score, String comment,
            LocalDateTime createdAt, boolean hidden) {

        public static EvaluationItem from(final ProductReview review) {
            return new EvaluationItem(review.id(), review.requesterNo(), review.score(),
                    review.comment(), review.createdAt(), review.hidden());
        }
    }

    /** R-EV2 列表响应（时间倒序全列，Q3-A 最小口径）。 */
    public record EvaluationList(long productId, java.util.List<EvaluationItem> items) {
    }

    /** G-EV1 隐藏响应。 */
    public record HideResult(long reviewId, boolean hidden) {
    }

    private ReviewViews() {
    }
}
