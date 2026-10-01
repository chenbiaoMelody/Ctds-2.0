package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.ProductInteractionLog;
import com.ctds.catalog.domain.ProductInteractionLogRepository;
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
}
