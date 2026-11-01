package com.ctds.delivery.application.review;

import com.ctds.delivery.domain.review.ProductReview;
import com.ctds.delivery.domain.review.ProductReviewActionLog;
import com.ctds.delivery.domain.review.ProductReviewRepository;
import com.ctds.delivery.domain.review.ReviewAction;
import com.ctds.delivery.domain.review.ReviewBizException;
import com.ctds.delivery.domain.review.ReviewErrorCodes;
import com.ctds.delivery.interfaces.dto.ReviewViews.HideResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评价运营治理应用服务（hifi §3 G-EV1）：隐藏留痕不物理删除（B5-4）；
 * 已隐藏再隐藏 = 幂等成功且不重复留痕（Q8-A）。
 */
@Service
public class ReviewGovernanceService {

    private final ProductReviewRepository repository;

    public ReviewGovernanceService(final ProductReviewRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public HideResult hide(final long reviewId, final String operatorNo) {
        final ProductReview review = repository.findById(reviewId)
                .orElseThrow(() -> new ReviewBizException(ReviewErrorCodes.REVIEW_NOT_FOUND,
                        ReviewErrorCodes.REVIEW_NOT_FOUND_MESSAGE));
        if (review.hidden()) {
            // 幂等：已隐藏直接成功，不追加留痕
            return new HideResult(review.id(), true);
        }
        repository.markHidden(review.id(), operatorNo);
        repository.insertActionLog(new ProductReviewActionLog(
                null, review.id(), review.productId(), review.requesterNo(),
                ReviewAction.HIDE, operatorNo, null));
        return new HideResult(review.id(), true);
    }
}
