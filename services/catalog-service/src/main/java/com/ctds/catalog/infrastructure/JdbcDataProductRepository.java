package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.DatasetNameNormalizer;
import com.ctds.catalog.domain.PricingModel;
import com.ctds.catalog.domain.ProductActionLog;
import com.ctds.catalog.domain.ProductStatus;
import com.ctds.catalog.domain.ProviderProductRow;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    // ==== WBS-3.3.5 写面与管理/治理读面 ====

    /** 管理/治理行 select 列（join category_node 取类目名；含 price_amount/dataset_id——Q2-A 增列）。 */
    private static final String PROVIDER_COLUMNS = "p.id, p.product_name, p.intro, p.product_type, "
            + "p.pricing_model, p.price_amount, p.status, p.provider_subject_no, p.dataset_id, "
            + "p.category_code, c.category_name, p.listed_at, p.created_at";

    @Override
    public Optional<ProviderProductRow> findById(final long productId) {
        return jdbc.sql("SELECT " + PROVIDER_COLUMNS + " " + PRODUCT_FROM + " WHERE p.id = ?")
                .param(productId)
                .query((rs, rowNum) -> mapProviderRow(rs))
                .optional();
    }

    @Override
    public PageResult<ProviderProductRow> pageByProvider(final String providerSubjectNo,
            final PageQuery page) {
        final long total = jdbc.sql("SELECT COUNT(*) FROM data_product WHERE provider_subject_no = ?")
                .param(providerSubjectNo)
                .query(Long.class)
                .single();
        final List<ProviderProductRow> list = jdbc.sql("SELECT " + PROVIDER_COLUMNS + " " + PRODUCT_FROM
                        + " WHERE p.provider_subject_no = ? ORDER BY p.created_at DESC, p.id DESC "
                        + "LIMIT ? OFFSET ?")
                .param(providerSubjectNo)
                .param(page.pageSize())
                .param(page.offset())
                .query((rs, rowNum) -> mapProviderRow(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    public boolean existsByProviderAndNormalizedName(final String providerSubjectNo,
            final String normalizedName) {
        return jdbc.sql("SELECT COUNT(*) FROM data_product WHERE provider_subject_no = ? "
                        + "AND normalized_product_name = ?")
                .param(providerSubjectNo)
                .param(normalizedName)
                .query(Long.class)
                .single() > 0;
    }

    @Override
    @Transactional
    public long create(final ProviderProductRow product, final ProductActionLog log) {
        try {
            jdbc.sql("INSERT INTO data_product (product_name, normalized_product_name, intro, product_type, "
                            + "pricing_model, price_amount, status, provider_subject_no, dataset_id, "
                            + "category_code, listed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(product.productName(), normalizedOrNull(product), product.intro(),
                            product.productType(), product.pricingModel(), product.priceAmount(),
                            product.status().name(), product.providerSubjectNo(), product.datasetId(),
                            product.categoryCode(), null)
                    .update();
        } catch (final DuplicateKeyException e) {
            // uk_provider_product_name / uk_provider_norm_name 兜底并发封装窗口（与领域服务层
            // 归一化预检构成双保险，沿 dataset uk_space_norm_name 先例）→ 1007C0017 业务码
            throw new CatalogBizException(CatalogErrorCodes.PRODUCT_NAME_DUPLICATED,
                    CatalogErrorCodes.PRODUCT_NAME_DUPLICATED_MESSAGE);
        }
        final long id = jdbc.sql("SELECT id FROM data_product WHERE provider_subject_no = ? "
                        + "AND product_name = ? ORDER BY id DESC LIMIT 1")
                .param(product.providerSubjectNo())
                .param(product.productName())
                .query(Long.class)
                .single();
        insertLogWithinTransaction(new ProductActionLog(null, id, log.action(), log.operatorSubjectNo(),
                log.summary(), log.createdAt()));
        return id;
    }

    @Override
    @Transactional
    public void updateFields(final long productId, final ProductUpdate update, final ProductActionLog log) {
        final List<String> sets = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (update.intro() != null) {
            sets.add("intro = ?");
            params.add(update.intro());
        }
        if (update.productType() != null) {
            sets.add("product_type = ?");
            params.add(update.productType());
        }
        if (update.pricingModel() != null) {
            sets.add("pricing_model = ?");
            params.add(update.pricingModel());
        }
        if (update.priceAmount() != null || PricingModel.FREE.name().equals(update.pricingModel())) {
            // 定价数值覆写：显式携值覆写；档位为 FREE 时应用层保证携值为 null（免费档恒 NULL——Q2-A）
            sets.add("price_amount = ?");
            params.add(update.priceAmount());
        }
        if (update.categoryCode() != null) {
            sets.add("category_code = ?");
            params.add(update.categoryCode());
        }
        jdbc.sql("UPDATE data_product SET " + String.join(", ", sets) + " WHERE id = ?")
                .params(params)
                .param(productId)
                .update();
        insertLogWithinTransaction(log);
    }

    @Override
    @Transactional
    public boolean transitionStatus(final long productId, final ProductStatus fromStatus,
            final ProductStatus toStatus, final LocalDateTime listedAt, final ProductActionLog log) {
        final int updated = jdbc.sql("UPDATE data_product SET status = ?, listed_at = ? "
                        + "WHERE id = ? AND status = ?")
                .param(toStatus.name())
                .param(listedAt == null ? null : Timestamp.valueOf(listedAt))
                .param(productId)
                .param(fromStatus.name())
                .update();
        if (updated == 0) {
            return false;
        }
        insertLogWithinTransaction(log);
        return true;
    }

    @Override
    public void insertLog(final ProductActionLog log) {
        insertLogWithinTransaction(log);
    }

    // ==== 内部 ====

    /** 归一化名取值（直造行语义不适用写面——封装链恒已归一化，此处防御性兜底为 null）。 */
    private static String normalizedOrNull(final ProviderProductRow product) {
        return product.productName() == null ? null
                : DatasetNameNormalizer.normalize(product.productName());
    }

    /** 留痕行写入（insertLog 独立提交——无外层事务；事务内调用时随外层提交）。 */
    private void insertLogWithinTransaction(final ProductActionLog log) {
        jdbc.sql("INSERT INTO product_action_log (product_id, action, operator_subject_no, summary, "
                        + "created_at) VALUES (?, ?, ?, ?, ?)")
                .params(log.productId(), log.action(), log.operatorSubjectNo(), log.summary(),
                        log.createdAt() == null ? null : Timestamp.valueOf(log.createdAt()))
                .update();
    }

    private static ProviderProductRow mapProviderRow(final ResultSet rs) throws SQLException {
        return new ProviderProductRow(rs.getLong("id"), rs.getString("product_name"),
                rs.getString("intro"), rs.getString("product_type"), rs.getString("pricing_model"),
                rs.getBigDecimal("price_amount"), ProductStatus.valueOf(rs.getString("status")),
                rs.getString("provider_subject_no"), rs.getLong("dataset_id"),
                rs.getString("category_code"), rs.getString("category_name"),
                rs.getTimestamp("listed_at") == null ? null : rs.getTimestamp("listed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static CatalogProductRow mapRow(final ResultSet rs, final LocalDateTime interactedAt)
            throws SQLException {
        return new CatalogProductRow(rs.getLong("id"), rs.getString("product_name"), rs.getString("intro"),
                rs.getString("product_type"), rs.getString("pricing_model"),
                ProductStatus.valueOf(rs.getString("status")), rs.getString("provider_subject_no"),
                rs.getString("category_code"), rs.getString("category_name"),
                rs.getTimestamp("listed_at") == null ? null : rs.getTimestamp("listed_at").toLocalDateTime(),
                interactedAt);
    }
}
