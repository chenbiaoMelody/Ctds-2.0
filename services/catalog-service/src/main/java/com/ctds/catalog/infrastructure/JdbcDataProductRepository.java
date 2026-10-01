package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 数据产品仓储（JdbcClient，ADR-009 迁移规范建表 V3；沿 {@code JdbcDatasetRepository} 先例）。
 * 目录检索 = status='LISTED' 硬过滤（行为 5 规则 1）+ 子树 IN + keyword 双字段 LIKE（已转义）+
 * listed_at DESC, id DESC 稳定排序（Q4-A）；详情仅 LISTED 行（未上架/已下架/已注销/不存在一律空，
 * 同形拒绝由应用层落定——行为 7 规则 2 防枚举）。
 */
@Repository
public class JdbcDataProductRepository implements DataProductRepository {

    /** 目录行 select 列（join category_node 取类目名；interacted_at 由读面端点按需补，恒 null）。 */
    private static final String PRODUCT_COLUMNS = "p.id, p.product_name, p.intro, p.product_type, "
            + "p.pricing_model, p.status, p.provider_subject_no, p.category_code, c.category_name, "
            + "p.listed_at";
    /** 基础 FROM/JOIN 段。 */
    private static final String PRODUCT_FROM = "FROM data_product p "
            + "LEFT JOIN category_node c ON c.category_code = p.category_code";

    private final JdbcClient jdbc;

    public JdbcDataProductRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<CatalogProductRow> searchListed(final List<String> categoryCodes,
            final String keyword, final PageQuery page) {
        final StringBuilder where = new StringBuilder("p.status = 'LISTED'");
        final List<Object> params = new ArrayList<>();
        if (categoryCodes != null && !categoryCodes.isEmpty()) {
            where.append(" AND p.category_code IN (")
                    .append(String.join(", ", categoryCodes.stream().map(c -> "?").toList()))
                    .append(")");
            params.addAll(categoryCodes);
        }
        if (keyword != null && !keyword.isBlank()) {
            // keyword 转义复用既有 escapeLike（DB-33 收敛方向：同一套转义符，不新增第二副本）
            final String likeKeyword = "%" + JdbcTagTermRepository.escapeLike(keyword) + "%";
            where.append(" AND (p.product_name LIKE ? ESCAPE '!' OR p.intro LIKE ? ESCAPE '!')");
            params.add(likeKeyword);
            params.add(likeKeyword);
        }
        final long total = jdbc.sql("SELECT COUNT(*) " + PRODUCT_FROM + " WHERE " + where)
                .params(params)
                .query(Long.class)
                .single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<CatalogProductRow> list = jdbc.sql("SELECT " + PRODUCT_COLUMNS + " " + PRODUCT_FROM
                        + " WHERE " + where + " ORDER BY p.listed_at DESC, p.id DESC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapRow(rs, null))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    public Optional<CatalogProductRow> findListedDetail(final long productId) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + " " + PRODUCT_FROM
                        + " WHERE p.id = ? AND p.status = 'LISTED'")
                .param(productId)
                .query((rs, rowNum) -> mapRow(rs, null))
                .optional();
    }

    @Override
    public boolean existsById(final long productId) {
        return jdbc.sql("SELECT COUNT(*) FROM data_product WHERE id = ?")
                .param(productId)
                .query(Long.class)
                .single() > 0;
    }

    @Override
    public Optional<ProductStatus> findStatusById(final long productId) {
        return jdbc.sql("SELECT status FROM data_product WHERE id = ?")
                .param(productId)
                .query(String.class)
                .optional()
                .map(ProductStatus::valueOf);
    }

    private static CatalogProductRow mapRow(final java.sql.ResultSet rs,
            final java.time.LocalDateTime interactedAt) throws java.sql.SQLException {
        return new CatalogProductRow(rs.getLong("id"), rs.getString("product_name"), rs.getString("intro"),
                rs.getString("product_type"), rs.getString("pricing_model"),
                ProductStatus.valueOf(rs.getString("status")), rs.getString("provider_subject_no"),
                rs.getString("category_code"), rs.getString("category_name"),
                rs.getTimestamp("listed_at") == null ? null : rs.getTimestamp("listed_at").toLocalDateTime(),
                interactedAt);
    }
}
