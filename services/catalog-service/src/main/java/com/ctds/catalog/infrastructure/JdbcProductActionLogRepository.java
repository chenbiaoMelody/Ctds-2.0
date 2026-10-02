package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.ProductActionLog;
import com.ctds.catalog.domain.ProductActionLogRepository;
import com.ctds.catalog.domain.ProductChangeLogRow;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 产品变更留痕仓储（JdbcClient，V3 建表 + V4 动作码值域登记）。
 * R8 读面 = 可见值域过滤（变更类六码，Q5-A——DENIED_ 前缀拒绝码与 GOVERNANCE_VIEW 不对订阅者
 * 暴露）；写入统一走 {@code JdbcDataProductRepository} 单通道（C3 收敛，本类只读）。
 */
@Repository
public class JdbcProductActionLogRepository implements ProductActionLogRepository {

    private final JdbcClient jdbc;

    public JdbcProductActionLogRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<ProductChangeLogRow> pageByProduct(final long productId, final PageQuery page) {
        // 可见值域封闭集合（Q5-A）：占位符按常量集展开，值域漂移由 T10 探针锚定
        final String placeholders = String.join(", ",
                ProductActionLog.SUBSCRIBER_VISIBLE_ACTIONS.stream().map(a -> "?").toList());
        final String visibleClause = "product_id = ? AND action IN (" + placeholders + ")";
        final List<Object> params = new ArrayList<>();
        params.add(productId);
        params.addAll(ProductActionLog.SUBSCRIBER_VISIBLE_ACTIONS);
        final long total = jdbc.sql("SELECT COUNT(*) FROM product_action_log WHERE " + visibleClause)
                .params(params)
                .query(Long.class)
                .single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<ProductChangeLogRow> list = jdbc.sql("SELECT action, summary, operator_subject_no, "
                        + "created_at FROM product_action_log WHERE " + visibleClause + " "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapRow(rs))
                .list();
        return PageResult.of(list, total, page);
    }


    private static ProductChangeLogRow mapRow(final ResultSet rs) throws SQLException {
        return new ProductChangeLogRow(rs.getString("action"), rs.getString("summary"),
                rs.getString("operator_subject_no"), rs.getTimestamp("created_at").toLocalDateTime());
    }
}
