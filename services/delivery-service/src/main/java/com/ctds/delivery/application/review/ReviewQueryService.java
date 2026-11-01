package com.ctds.delivery.application.review;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.delivery.domain.review.ProductReviewRepository;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationItem;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationList;
import com.ctds.delivery.interfaces.dto.ReviewViews.EvaluationStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评价查询应用服务（hifi §1 R-EV1/R-EV2/R-EV3）：统计（仅计未隐藏，均分 1 位小数 HALF_UP）、
 * 文字评价列表（时间倒序全列不分页，Q3-A 最小口径）、本人评价回显（未评价 data=null）。
 * 展示面只读零留痕（B6：主动查询，无推送/轮询）。
 */
@Service
public class ReviewQueryService {

    private static final int SCORE_SCALE = 1;

    private final ProductReviewRepository repository;

    public ReviewQueryService(final ProductReviewRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public EvaluationStats stats(final long productId) {
        requireValidProductId(productId);
        final BigDecimal average = repository.averageVisible(productId);
        return new EvaluationStats(productId,
                average == null ? null : average.setScale(SCORE_SCALE, RoundingMode.HALF_UP),
                repository.countVisible(productId));
    }

    @Transactional(readOnly = true)
    public EvaluationList list(final long productId) {
        requireValidProductId(productId);
        final List<EvaluationItem> items = repository.listVisible(productId).stream()
                .map(EvaluationItem::from).toList();
        return new EvaluationList(productId, items);
    }

    @Transactional(readOnly = true)
    public EvaluationItem mine(final long productId, final String requesterNo) {
        requireValidProductId(productId);
        return repository.findByProductAndRequester(productId, requesterNo)
                .map(EvaluationItem::from).orElse(null);
    }

    private void requireValidProductId(final long productId) {
        if (productId <= 0) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "参数格式不合法");
        }
    }
}
