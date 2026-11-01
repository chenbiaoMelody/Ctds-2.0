package com.ctds.delivery.domain.review;

import java.time.LocalDateTime;

/** 评价域动作留痕（DB 行；hifi §2——提交/更新/隐藏全程留痕，B5-1/B5-4）。 */
public record ProductReviewActionLog(
        Long id,
        long reviewId,
        long productId,
        String requesterNo,
        ReviewAction action,
        String actorNo,
        LocalDateTime createdAt) {
}
