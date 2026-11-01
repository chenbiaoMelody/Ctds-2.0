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
        if (repository.findByProductAndRequester(productId, requesterNo).isPresent()) {
            return updateExisting(productId, requesterNo, score, normalizedComment);
        }
        try {
            final ProductReview inserted = repository.insert(new ProductReview(null, productId,
                    requesterNo, score, normalizedComment, false, null, null, null, null));
            repository.insertActionLog(actionLog(inserted, ReviewAction.CREATE, requesterNo));
            return ReviewResult.of(inserted, false);
        } catch (final DuplicateKeyException e) {
            // 并发兜底（hifi §3 步骤 4）：同事务转更新分支——留痕与返回与常规更新同口径（评审 P2-A）
            return updateExisting(productId, requesterNo, score, normalizedComment);
        }
    }

    /** 更新本人既有评价（常规更新与并发兜底共用；UPDATE 留痕，updated=true）。 */
    private ReviewResult updateExisting(final long productId, final String requesterNo,
            final int score, final String comment) {
        // FOR UPDATE 当前读：绕过 REPEATABLE READ 快照，兜底重查必见并发已提交行（评审 P2-A）
        final ProductReview current = repository
                .findByProductAndRequesterForUpdate(productId, requesterNo).orElseThrow();
        repository.updateContent(current.id(), score, comment);
        final ProductReview updated =
                repository.findByProductAndRequester(productId, requesterNo).orElseThrow();
        repository.insertActionLog(actionLog(updated, ReviewAction.UPDATE, requesterNo));
        return ReviewResult.of(updated, true);
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
