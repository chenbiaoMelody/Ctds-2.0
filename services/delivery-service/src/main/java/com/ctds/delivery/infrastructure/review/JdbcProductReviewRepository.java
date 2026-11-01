package com.ctds.delivery.infrastructure.review;

import com.ctds.delivery.domain.review.ProductReview;
import com.ctds.delivery.domain.review.ProductReviewActionLog;
import com.ctds.delivery.domain.review.ProductReviewRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 评价域 JdbcClient 仓储（hifi §2；沿 JdbcDeliveryRepository 先例）。
 * 资格判定为 delivery 表同库只读 EXISTS——既有索引 idx_requester(requester_no, created_at)
 * 前缀可覆盖（演示量级不加新索引）；交付表结构零改动（N1）。
 */
@Repository
public class JdbcProductReviewRepository implements ProductReviewRepository {

    private static final String COLUMNS = "id, product_id, requester_no, score, comment, hidden, "
            + "hidden_by, hidden_at, created_at, updated_at";

    private final JdbcClient jdbc;

    public JdbcProductReviewRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProductReview> findByProductAndRequester(final long productId, final String requesterNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review "
                        + "WHERE product_id = ? AND requester_no = ?")
                .param(productId).param(requesterNo)
                .query(this::mapRow).optional();
    }

    @Override
    public Optional<ProductReview> findById(final long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review WHERE id = ?")
                .param(id)
                .query(this::mapRow).optional();
    }

    @Override
    public ProductReview insert(final ProductReview review) {
        jdbc.sql("INSERT INTO product_review "
                        + "(product_id, requester_no, score, comment) VALUES (?, ?, ?, ?)")
                .param(review.productId()).param(review.requesterNo())
                .param(review.score()).param(review.comment())
                .update();
        return findByProductAndRequester(review.productId(), review.requesterNo())
                .orElseThrow(() -> new IllegalStateException("评价插入后回查失败"));
    }

    @Override
    public void updateContent(final long id, final int score, final String comment) {
        jdbc.sql("UPDATE product_review SET score = ?, comment = ? WHERE id = ?")
                .param(score).param(comment).param(id)
                .update();
    }

    @Override
    public void markHidden(final long id, final String hiddenBy) {
        jdbc.sql("UPDATE product_review SET hidden = 1, hidden_by = ?, hidden_at = NOW() WHERE id = ?")
                .param(hiddenBy).param(id)
                .update();
    }

    @Override
    public void insertActionLog(final ProductReviewActionLog actionLog) {
        jdbc.sql("INSERT INTO product_review_action_log "
                        + "(review_id, product_id, requester_no, action, actor_no) VALUES (?, ?, ?, ?, ?)")
                .param(actionLog.reviewId()).param(actionLog.productId())
                .param(actionLog.requesterNo()).param(actionLog.action().name())
                .param(actionLog.actorNo())
                .update();
    }

    @Override
    public int countVisible(final long productId) {
        final Integer count = jdbc.sql(
                        "SELECT COUNT(*) FROM product_review WHERE product_id = ? AND hidden = 0")
                .param(productId)
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public BigDecimal averageVisible(final long productId) {
        return jdbc.sql("SELECT AVG(score) FROM product_review WHERE product_id = ? AND hidden = 0")
                .param(productId)
                .query(BigDecimal.class).single();
    }

    @Override
    public List<ProductReview> listVisible(final long productId) {
        // 时间倒序全列（Q3-A）；同秒提交以 id 倒序稳定补充（id 单调递增 = 提交先后）
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review "
                        + "WHERE product_id = ? AND hidden = 0 ORDER BY created_at DESC, id DESC")
                .param(productId)
                .query(this::mapRow).list();
    }

    @Override
    public boolean hasSuccessfulDelivery(final String requesterNo, final long productId) {
        final Boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM delivery "
                        + "WHERE requester_no = ? AND product_id = ? AND result = 'SUCCESS')")
                .param(requesterNo).param(productId)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private ProductReview mapRow(final java.sql.ResultSet rs, final int rowNum) throws java.sql.SQLException {
        final Timestamp hiddenAt = rs.getTimestamp("hidden_at");
        final Timestamp createdAt = rs.getTimestamp("created_at");
        final Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ProductReview(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("requester_no"),
                rs.getInt("score"),
                rs.getString("comment"),
                rs.getBoolean("hidden"),
                rs.getString("hidden_by"),
                hiddenAt == null ? null : hiddenAt.toLocalDateTime(),
                createdAt.toLocalDateTime(),
                updatedAt.toLocalDateTime());
    }
}
