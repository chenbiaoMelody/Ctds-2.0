package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.ProductInteractionLog;
import com.ctds.catalog.domain.ProductInteractionLogRepository;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProductSubscriptionRepository;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品订阅关系仓储（JdbcClient，ADR-009 迁移规范建表 V3；沿 {@code JdbcProductFavoriteRepository} 同款）。
 * R10 列表 join data_product 读时计算产品当前状态（Q5-A）；订阅者判定（R8）复用 findSubscribedAt；
 * 条目 + 留痕两写同事务的边界在本仓储方法内；留痕写入复用 {@code ProductInteractionLogRepository.insert}
 * （REQUIRED join 同事务——留痕 INSERT 单一实现，防多副本演进漏改）。
 */
@Repository
public class JdbcProductSubscriptionRepository implements ProductSubscriptionRepository {

    /** 订阅列表行 select 列（s.created_at = 交互时间 interactedAt）。 */
    private static final String SUBSCRIPTION_COLUMNS = "p.id, p.product_name, p.intro, p.product_type, "
            + "p.pricing_model, p.status, p.provider_subject_no, p.category_code, c.category_name, "
            + "p.listed_at, s.created_at";
    private static final String SUBSCRIPTION_FROM = "FROM product_subscription s "
            + "JOIN data_product p ON p.id = s.product_id "
            + "LEFT JOIN category_node c ON c.category_code = p.category_code";

    private final JdbcClient jdbc;
    private final ProductInteractionLogRepository interactionLogRepository;

    public JdbcProductSubscriptionRepository(final JdbcClient jdbc,
            final ProductInteractionLogRepository interactionLogRepository) {
        this.jdbc = jdbc;
        this.interactionLogRepository = interactionLogRepository;
    }

    @Override
    public Optional<LocalDateTime> findSubscribedAt(final String subjectNo, final long productId) {
        return jdbc.sql("SELECT created_at FROM product_subscription WHERE subject_no = ? AND product_id = ?")
                .param(subjectNo)
                .param(productId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toLocalDateTime);
    }

    @Override
    @Transactional
    public void insertWithLog(final String subjectNo, final long productId, final LocalDateTime createdAt,
            final ProductInteractionLog log) {
        jdbc.sql("INSERT INTO product_subscription (subject_no, product_id, created_at) VALUES (?, ?, ?)")
                .param(subjectNo)
                .param(productId)
                .param(createdAt)
                .update();
        interactionLogRepository.insert(log);
    }

    @Override
    @Transactional
    public boolean deleteWithLog(final String subjectNo, final long productId,
            final ProductInteractionLog log) {
        final boolean deleted = jdbc
                .sql("DELETE FROM product_subscription WHERE subject_no = ? AND product_id = ?")
                .param(subjectNo)
                .param(productId)
                .update() > 0;
        interactionLogRepository.insert(log);
        return deleted;
    }

    @Override
    public PageResult<CatalogProductRow> pageBySubject(final String subjectNo, final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM product_subscription WHERE subject_no = ?")
                .param(subjectNo)
                .query(Long.class)
                .single();
        final List<CatalogProductRow> list = jdbc.sql("SELECT " + SUBSCRIPTION_COLUMNS + " "
                        + SUBSCRIPTION_FROM + " WHERE s.subject_no = ? "
                        + "ORDER BY s.created_at DESC, s.id DESC LIMIT ? OFFSET ?")
                .param(subjectNo)
                .param(page.pageSize())
                .param(page.offset())
                .query((rs, rowNum) -> mapRow(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private static CatalogProductRow mapRow(final ResultSet rs) throws SQLException {
        return new CatalogProductRow(rs.getLong("id"), rs.getString("product_name"), rs.getString("intro"),
                rs.getString("product_type"), rs.getString("pricing_model"),
                ProductStatus.valueOf(rs.getString("status")), rs.getString("provider_subject_no"),
                rs.getString("category_code"), rs.getString("category_name"),
                rs.getTimestamp("listed_at") == null ? null : rs.getTimestamp("listed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
