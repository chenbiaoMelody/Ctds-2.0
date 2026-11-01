package com.ctds.delivery.application.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ctds.delivery.domain.review.ProductReview;
import com.ctds.delivery.domain.review.ProductReviewActionLog;
import com.ctds.delivery.domain.review.ProductReviewRepository;
import com.ctds.delivery.domain.review.ReviewAction;
import com.ctds.delivery.interfaces.dto.ReviewViews.ReviewResult;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 评价并发兜底存根单测（WBS-3.7.4 评审 P1-3；沿 3.7.3 OrderCommandServiceFallbackTranslateTest 先例）：
 * uk_review_once 兜底 {@code DuplicateKeyException} → 转更新分支（hifi §3 步骤 4；评审 P2-A 口径 =
 * 与常规更新同留痕 UPDATE + updated=true，仓储锁定重查承载并发时序）。
 * 该腿在单线程集成面不可达（预查空 → INSERT 必成功），故以存根直调钉住转更新语义。
 */
@ExtendWith(MockitoExtension.class)
class ReviewCommandServiceConcurrentFallbackTest {

    @Mock
    private ProductReviewRepository repository;

    @Test
    void duplicateKeyFallbackTranslatesToUpdateBranch() {
        final LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 12, 0);
        final ProductReview concurrentRow = new ProductReview(9L, 15L, "S-req", 3, "对手事务行",
                false, null, null, createdAt, createdAt);
        final ProductReview afterUpdate = new ProductReview(9L, 15L, "S-req", 4, "更新后的评价",
                false, null, null, createdAt, createdAt.plusMinutes(5));
        // ① 预查空 → 走 INSERT；② INSERT 撞 uk（对手事务并发插入）→ 兜底；
        // ③ FOR UPDATE 锁定重查命中对手行（当前读，评审 P2-A）→ 转更新
        given(repository.hasSuccessfulDelivery("S-req", 15L)).willReturn(true);
        given(repository.findByProductAndRequester(15L, "S-req"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(afterUpdate));
        given(repository.insert(any(ProductReview.class)))
                .willThrow(new DuplicateKeyException("uk_review_once"));
        given(repository.findByProductAndRequesterForUpdate(15L, "S-req"))
                .willReturn(Optional.of(concurrentRow));

        final ReviewResult result = new ReviewCommandService(repository)
                .submit(15L, 4, "更新后的评价", "S-req");

        // 转更新语义（评审 P2-A）：updated=true + UPDATE 留痕（非 CREATE）
        assertThat(result.updated()).isTrue();
        assertThat(result.reviewId()).isEqualTo(9L);
        final ArgumentCaptor<ProductReviewActionLog> logCaptor =
                ArgumentCaptor.forClass(ProductReviewActionLog.class);
        verify(repository).insertActionLog(logCaptor.capture());
        assertThat(logCaptor.getValue().action()).isEqualTo(ReviewAction.UPDATE);
        verify(repository).updateContent(9L, 4, "更新后的评价");
    }
}
