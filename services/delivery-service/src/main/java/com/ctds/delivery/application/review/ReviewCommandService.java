package com.ctds.delivery.application.review;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.delivery.domain.review.ProductReview;
import com.ctds.delivery.domain.review.ProductReviewActionLog;
import com.ctds.delivery.domain.review.ProductReviewRepository;
import com.ctds.delivery.domain.review.ReviewAction;
import com.ctds.delivery.domain.review.ReviewBizException;
import com.ctds.delivery.domain.review.ReviewErrorCodes;
import com.ctds.delivery.interfaces.dto.ReviewViews.ReviewResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评价提交/更新应用服务（hifi §3 upsert 事务；W-EV1）：
 * 校验评分 → 资格校验（同库 EXISTS）→ upsert（一人一评）→ 全程留痕。
 * 幂等 = upsert 天然语义（重复提交即更新，B5-2 / Q8-A），并发双击由 uk_review_once 兜底
 * （DuplicateKey 捕获转更新，不向客户端抛错）；零新增幂等组件接入。
 */
@Service
public class ReviewCommandService {

    private static final int SCORE_MIN = 1;
    private static final int SCORE_MAX = 5;
    private static final int COMMENT_MAX = 500;

    private final ProductReviewRepository repository;

    public ReviewCommandService(final ProductReviewRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReviewResult submit(final long productId, final int score, final String comment,
            final String requesterNo) {
        validateParams(productId, score, comment);
        if (!repository.hasSuccessfulDelivery(requesterNo, productId)) {
            throw new ReviewBizException(ReviewErrorCodes.REVIEW_ELIGIBILITY_DENIED,
                    ReviewErrorCodes.REVIEW_ELIGIBILITY_DENIED_MESSAGE);
        }
        final String normalizedComment = comment == null ? null : comment.trim();
        final ProductReview existing =
                repository.findByProductAndRequester(productId, requesterNo).orElse(null);
        if (existing == null) {
            final ProductReview inserted = insertWithConcurrentFallback(productId, requesterNo,
                    score, normalizedComment);
            repository.insertActionLog(actionLog(inserted, ReviewAction.CREATE, requesterNo));
            return ReviewResult.of(inserted, false);
        }
        repository.updateContent(existing.id(), score, normalizedComment);
        final ProductReview updated =
                repository.findByProductAndRequester(productId, requesterNo).orElseThrow();
        repository.insertActionLog(actionLog(updated, ReviewAction.UPDATE, requesterNo));
        return ReviewResult.of(updated, true);
    }

    /** 并发兜底：INSERT 撞 uk_review_once → 同事务转更新分支（Q8-A，不抛错）。 */
    private ProductReview insertWithConcurrentFallback(final long productId, final String requesterNo,
            final int score, final String comment) {
        try {
            return repository.insert(new ProductReview(null, productId, requesterNo, score,
                    comment, false, null, null, null, null));
        } catch (final DuplicateKeyException e) {
            final long existingId = repository.findByProductAndRequester(productId, requesterNo)
                    .orElseThrow(() -> e).id();
            repository.updateContent(existingId, score, comment);
            return repository.findById(existingId).orElseThrow();
        }
    }

    private ProductReviewActionLog actionLog(final ProductReview review, final ReviewAction action,
            final String actorNo) {
        return new ProductReviewActionLog(null, review.id(), review.productId(),
                review.requesterNo(), action, actorNo, null);
    }

    private void validateParams(final long productId, final int score, final String comment) {
        if (score < SCORE_MIN || score > SCORE_MAX) {
            throw new ReviewBizException(ReviewErrorCodes.REVIEW_SCORE_INVALID,
                    ReviewErrorCodes.REVIEW_SCORE_INVALID_MESSAGE);
        }
        if (comment != null && comment.trim().length() > COMMENT_MAX) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
        if (productId <= 0) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
    }
}
