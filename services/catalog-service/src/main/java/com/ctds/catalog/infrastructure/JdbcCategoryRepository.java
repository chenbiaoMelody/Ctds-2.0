package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.CategoryNode;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.DatasetNameNormalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 受控类目树仓储（JdbcClient，ADR-009 迁移规范建表 V3；沿 {@code JdbcTagTermRepository} 先例）。
 *
 * <p>成员校验 = 申报原文经 {@link DatasetNameNormalizer} 归一化后与 {@code category_node.normalized_name}
 * 的 <b>DB 侧一次比对</b>（WBS-3.3.4 hifi §3；单值申报无需差集，同一请求内只有这一道口径，沿 3.3.3
 * "差集必须同库算"教训——不在 Java 侧做第二次判定）。归一化单点在本实现内（沿词表通道 findUnmatched
 * 同款），调用方不做第二次归一化。大小写折叠由列排序规则 0900_ai_ci 承担。</p>
 */
@Repository
public class JdbcCategoryRepository implements CategoryPort {

    private static final String NODE_COLUMNS = "id, category_code, category_name, normalized_name, "
            + "parent_code, sort_order";

    private final JdbcClient jdbc;

    public JdbcCategoryRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategoryNode> listAll() {
        return jdbc.sql("SELECT " + NODE_COLUMNS + " FROM category_node ORDER BY id ASC")
                .query((rs, rowNum) -> mapNode(rs))
                .list();
    }

    @Override
    public boolean existsByCode(final String categoryCode) {
        return jdbc.sql("SELECT COUNT(*) FROM category_node WHERE category_code = ?")
                .param(categoryCode)
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public List<String> selfAndDescendantCodes(final String categoryCode) {
        // 2 级树：该节点 + 直接子级，一次查询（hifi §1 R6 子树展开）
        return jdbc.sql("SELECT category_code FROM category_node "
                        + "WHERE category_code = ? OR parent_code = ?")
                .param(categoryCode)
                .param(categoryCode)
                .query((rs, rowNum) -> rs.getString(1))
                .list();
    }

    @Override
    public boolean existsByNormalizedName(final String normalizedName) {
        return jdbc.sql("SELECT COUNT(*) FROM category_node WHERE normalized_name = ?")
                .param(DatasetNameNormalizer.normalize(normalizedName))
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public String findCodeByNormalizedName(final String declareCategory) {
        return jdbc.sql("SELECT category_code FROM category_node WHERE normalized_name = ? "
                        + "ORDER BY id ASC LIMIT 1")
                .param(DatasetNameNormalizer.normalize(declareCategory))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static CategoryNode mapNode(final ResultSet rs) throws SQLException {
        return new CategoryNode(rs.getLong("id"), rs.getString("category_code"),
                rs.getString("category_name"), rs.getString("normalized_name"), rs.getString("parent_code"),
                rs.getInt("sort_order"));
    }
}
