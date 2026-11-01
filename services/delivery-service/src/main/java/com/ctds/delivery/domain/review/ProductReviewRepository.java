package com.ctds.delivery.domain.review;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 评价域仓储接口（hifi §2）：评价行读写 + 动作留痕 + 统计（仅计未隐藏）+ 交付资格只读判定。
 * 资格判定 = delivery 表同库 EXISTS（requester_no + product_id + result='SUCCESS'，B5-1 直译）——
 * 只读方法，交付既有仓储/端点零改动（N1）。
 */
public interface ProductReviewRepository {

    /** 按一人一评业务键查本人既有评价。 */
    Optional<ProductReview> findByProductAndRequester(long productId, String requesterNo);

    /** 并发兜底用锁定重查（FOR UPDATE 当前读，绕过快照——hifi §3 步骤 4 同事务重查）。 */
    Optional<ProductReview> findByProductAndRequesterForUpdate(long productId, String requesterNo);

    /** 按主键查（运营隐藏目标定位）。 */
    Optional<ProductReview> findById(long id);

    /** 插入新评价（并发撞 uk_review_once 抛 DuplicateKeyException，由应用层捕获转更新）。 */
    ProductReview insert(ProductReview review);

    /** 更新本人评价内容（仅评分/文字；created_at 不变，updated_at 随行变更）。 */
    void updateContent(long id, int score, String comment);

    /** 运营隐藏置位（hidden/hidden_by/hidden_at；updated_at 由 DDL ON UPDATE 机制自动变更、无出参消费——hifi 勘误 E1）。 */
    void markHidden(long id, String hiddenBy);

    /** 追加动作留痕行。 */
    void insertActionLog(ProductReviewActionLog actionLog);

    /** 未隐藏评价数（B5-3 统计口径）。 */
    int countVisible(long productId);

    /** 未隐藏评分均值（1 位小数 HALF_UP 由应用层收敛；无评价返回 null）。 */
    BigDecimal averageVisible(long productId);

    /** 未隐藏文字评价列表（按提交时间倒序全列，Q3-A 最小口径）。 */
    List<ProductReview> listVisible(long productId);

    /** 交付资格判定：本人对该产品存在 result='SUCCESS' 的交付记录（B5-1）。 */
    boolean hasSuccessfulDelivery(String requesterNo, long productId);
}
