package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.ProductActionLogRepository;
import com.ctds.catalog.domain.ProductChangeLogRow;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 产品变更留痕仓储（JdbcClient，ADR-009 迁移规范建表 V3；只读——写入归 3.3.5，hifi §10.4）。
 * R8 读面对空表天然返回空页（非错误）。
 */
@Repository
public class JdbcProductActionLogRepository implements ProductActionLogRepository {

    private final JdbcClient jdbc;

    public JdbcProductActionLogRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<ProductChangeLogRow> pageByProduct(final long productId, final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM product_action_log WHERE product_id = ?")
                .param(productId)
                .query(Long.class)
                .single();
        final List<ProductChangeLogRow> list = jdbc.sql("SELECT action, summary, operator_subject_no, "
                        + "created_at FROM product_action_log WHERE product_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .param(productId)
                .param(page.pageSize())
                .param(page.offset())
                .query((rs, rowNum) -> mapRow(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private static ProductChangeLogRow mapRow(final ResultSet rs) throws SQLException {
        return new ProductChangeLogRow(rs.getString("action"), rs.getString("summary"),
                rs.getString("operator_subject_no"), rs.getTimestamp("created_at").toLocalDateTime());
    }
}
