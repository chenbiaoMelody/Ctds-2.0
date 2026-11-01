package com.ctds.delivery.infrastructure.review;

import com.ctds.delivery.domain.review.ProductReview;
import com.ctds.delivery.domain.review.ProductReviewActionLog;
import com.ctds.delivery.domain.review.ProductReviewRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 评价域 JdbcClient 仓储（hifi §2；沿 JdbcDeliveryRepository 先例：命名参数 + getObject 行映射）。
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
    public Optional<ProductReview> findByProductAndRequester(final long productId,
            final String requesterNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review "
                        + "WHERE product_id = :productId AND requester_no = :requesterNo")
                .param("productId", productId).param("requesterNo", requesterNo)
                .query(this::mapRow).optional();
    }

    @Override
    public Optional<ProductReview> findByProductAndRequesterForUpdate(final long productId,
            final String requesterNo) {
        // FOR UPDATE 当前读（评审 P2-A：绕过 REPEATABLE READ 快照，兜底重查必见并发已提交行）
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review "
                        + "WHERE product_id = :productId AND requester_no = :requesterNo FOR UPDATE")
                .param("productId", productId).param("requesterNo", requesterNo)
                .query(this::mapRow).optional();
    }

    @Override
    public Optional<ProductReview> findById(final long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review WHERE id = :id")
                .param("id", id)
                .query(this::mapRow).optional();
    }

    @Override
    public ProductReview insert(final ProductReview review) {
        jdbc.sql("INSERT INTO product_review "
                        + "(product_id, requester_no, score, comment) "
                        + "VALUES (:productId, :requesterNo, :score, :comment)")
                .param("productId", review.productId()).param("requesterNo", review.requesterNo())
                .param("score", review.score()).param("comment", review.comment())
                .update();
        return findByProductAndRequester(review.productId(), review.requesterNo())
                .orElseThrow(() -> new IllegalStateException("评价插入后回查失败"));
    }

    @Override
    public void updateContent(final long id, final int score, final String comment) {
        jdbc.sql("UPDATE product_review SET score = :score, comment = :comment WHERE id = :id")
                .param("score", score).param("comment", comment).param("id", id)
                .update();
    }

    @Override
    public void markHidden(final long id, final String hiddenBy) {
        jdbc.sql("UPDATE product_review SET hidden = 1, hidden_by = :hiddenBy, "
                        + "hidden_at = NOW() WHERE id = :id")
                .param("hiddenBy", hiddenBy).param("id", id)
                .update();
    }

    @Override
    public void insertActionLog(final ProductReviewActionLog actionLog) {
        jdbc.sql("INSERT INTO product_review_action_log "
                        + "(review_id, product_id, requester_no, action, actor_no) "
                        + "VALUES (:reviewId, :productId, :requesterNo, :action, :actorNo)")
                .param("reviewId", actionLog.reviewId()).param("productId", actionLog.productId())
                .param("requesterNo", actionLog.requesterNo())
                .param("action", actionLog.action().name()).param("actorNo", actionLog.actorNo())
                .update();
    }

    @Override
    public int countVisible(final long productId) {
        final Integer count = jdbc.sql("SELECT COUNT(*) FROM product_review "
                        + "WHERE product_id = :productId AND hidden = 0")
                .param("productId", productId)
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public BigDecimal averageVisible(final long productId) {
        return jdbc.sql("SELECT AVG(score) FROM product_review "
                        + "WHERE product_id = :productId AND hidden = 0")
                .param("productId", productId)
                .query(BigDecimal.class).single();
    }

    @Override
    public List<ProductReview> listVisible(final long productId) {
        // 时间倒序全列（Q3-A）；同秒提交以 id 倒序稳定补充（id 单调递增 = 提交先后）
        return jdbc.sql("SELECT " + COLUMNS + " FROM product_review "
                        + "WHERE product_id = :productId AND hidden = 0 "
                        + "ORDER BY created_at DESC, id DESC")
                .param("productId", productId)
                .query(this::mapRow).list();
    }

    @Override
    public boolean hasSuccessfulDelivery(final String requesterNo, final long productId) {
        // result='SUCCESS' 与 DeliveryCommandService.RESULT_SUCCESS 同源（交付落行即成功，B5-1）
        final Boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM delivery "
                        + "WHERE requester_no = :requesterNo AND product_id = :productId "
                        + "AND result = 'SUCCESS')")
                .param("requesterNo", requesterNo).param("productId", productId)
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(exists);
    }

    private ProductReview mapRow(final java.sql.ResultSet rs, final int rowNum)
            throws java.sql.SQLException {
        return new ProductReview(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("requester_no"),
                rs.getInt("score"),
                rs.getString("comment"),
                rs.getBoolean("hidden"),
                rs.getString("hidden_by"),
                rs.getObject("hidden_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
