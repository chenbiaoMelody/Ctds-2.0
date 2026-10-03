package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.InteractionLogRow;
import com.ctds.catalog.domain.ProductInteractionLog;
import com.ctds.catalog.domain.ProductInteractionLogRepository;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 目录域收藏订阅动作留痕仓储（JdbcClient，ADR-009 迁移规范建表 V3；沿 dataset_action_log 先例）。
 * 调用方保证与条目写入同事务（@Transactional 边界在仓储方法 + 服务层两写同事务）。
 */
@Repository
public class JdbcProductInteractionLogRepository implements ProductInteractionLogRepository {

    private final JdbcClient jdbc;

    public JdbcProductInteractionLogRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insert(final ProductInteractionLog log) {
        jdbc.sql("INSERT INTO product_interaction_log (subject_no, product_id, action, outcome, "
                        + "deny_reason, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .param(log.subjectNo())
                .param(log.productId())
                .param(log.action())
                .param(log.outcome())
                .param(log.denyReason())
                .param(log.createdAt())
                .update();
    }

    @Override
    public PageResult<InteractionLogRow> pageBySubject(final String subjectNo, final PageQuery page) {
        // 只读既有 product_interaction_log（WBS-3.3.6 R16；恒仅本人行，按 created_at DESC, id DESC）
        final long total = jdbc.sql("SELECT COUNT(*) FROM product_interaction_log WHERE subject_no = ?")
                .param(subjectNo)
                .query(Long.class)
                .single();
        final List<InteractionLogRow> list = jdbc.sql("SELECT id, product_id, action, outcome, deny_reason, "
                        + "created_at FROM product_interaction_log WHERE subject_no = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .param(subjectNo)
                .param(page.pageSize())
                .param(page.offset())
                .query((rs, rowNum) -> mapRow(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    private static InteractionLogRow mapRow(final ResultSet rs) throws SQLException {
        return new InteractionLogRow(rs.getLong("id"), rs.getLong("product_id"), rs.getString("action"),
                rs.getString("outcome"), rs.getString("deny_reason"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
