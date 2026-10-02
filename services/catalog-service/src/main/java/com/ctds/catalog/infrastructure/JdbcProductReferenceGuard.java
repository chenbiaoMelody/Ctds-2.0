package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.ProductReferenceGuard;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 注销引用保护实体检查实现（WBS-3.3.5 hifi §4——兑现 3.3.2 移交义务，替换恒放行的
 * {@code NoopProductReferenceGuard}〔已删除〕）：存在未注销产品（status ≠ CANCELLED）引用该资源
 * → true，W3 注销前置检查（{@code DatasetCommandService} 既有接线）拒绝并落 1007C0021
 * （C-3.1 剧本 S3 步骤 4 判定面）。已注销产品的引用不阻断资源注销（终态产品不构成活跃引用）。
 */
@Component
public class JdbcProductReferenceGuard implements ProductReferenceGuard {

    private final JdbcClient jdbc;

    public JdbcProductReferenceGuard(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasActiveProductReferences(final long datasetId) {
        return jdbc.sql("SELECT COUNT(*) FROM data_product WHERE dataset_id = ? "
                        + "AND status <> 'CANCELLED'")
                .param(datasetId)
                .query(Long.class)
                .single() > 0;
    }
}
