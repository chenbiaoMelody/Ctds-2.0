package com.ctds.delivery.domain.review;

import java.time.LocalDateTime;

/**
 * 产品评价（DB 行映射；hifi §2）。一人一产品一评（uk_review_once 兜底）；
 * hidden=1 为运营隐藏（留痕不物理删除，B5-4）；created_at 恒为首次提交时点。
 */
public record ProductReview(
        Long id,
        long productId,
        String requesterNo,
        int score,
        String comment,
        boolean hidden,
        String hiddenBy,
        LocalDateTime hiddenAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
