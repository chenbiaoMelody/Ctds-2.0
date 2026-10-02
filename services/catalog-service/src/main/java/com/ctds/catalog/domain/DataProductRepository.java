package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 数据产品仓储端口（WBS-3.3.4 读面 + WBS-3.3.5 写面；实现 = infrastructure.JdbcDataProductRepository）。
 * 3.3.5 在读面端口上扩展封装/上下架/注销写面与提供方管理/治理读面（hifi §3.1 授权增列迁移 V4）。
 */
public interface DataProductRepository {

    /**
     * 目录检索（R6，hifi §1 检索链 ⑥）：status='LISTED' 硬过滤 + category_code IN (子树集，可选) +
     * keyword LIKE（名称/简介两字段 OR，实现侧经既有 escapeLike 转义——通配符字面化）+
     * ORDER BY listed_at DESC, id DESC。
     *
     * @param categoryCodes 子树码集（空 = 不过滤）
     * @param keyword       关键词原文（null/空 = 不过滤；转义由实现侧统一落）
     */
    PageResult<CatalogProductRow> searchListed(List<String> categoryCodes, String keyword, PageQuery page);

    /** 产品详情（R7）：仅 LISTED 行（未上架/已下架/已注销/不存在一律空——同形拒绝由应用层落定）。 */
    Optional<CatalogProductRow> findListedDetail(long productId);

    /** 产品行存在性（R8：产品行不存在与非订阅者同形拒绝的前置判定；不限状态）。 */
    boolean existsById(long productId);

    /** 产品当前状态（W4/W6 新发起状态门槛；不存在 = 空，同按 1007C0011 同形处置）。 */
    Optional<ProductStatus> findStatusById(long productId);

    // ==== WBS-3.3.5 写面与管理/治理读面 ====

    /** 按主键取产品全量行（R11 定位/W9~W13 命令定位/R12 治理读面；不限状态）。 */
    Optional<ProviderProductRow> findById(long productId);

    /** 提供方本人全状态产品分页（R11 管理视图；createdAt 倒序 + id 倒序稳定排序）。 */
    PageResult<ProviderProductRow> pageByProvider(String providerSubjectNo, PageQuery page);

    /**
     * 同提供方归一化产品名存在性（W8 判重预检，hifi §4 步骤 ⑧——归一化值已在应用层产出；
     * 唯一键含已注销行：注销后同名不可复用，行为 3 规则 5 沿 dataset 名称锁同源口径）。
     */
    boolean existsByProviderAndNormalizedName(String providerSubjectNo, String normalizedName);

    /**
     * 封装两写（W8，hifi §4）：data_product 行（status=DRAFT）+ CREATE 留痕同事务；
     * uk_provider_product_name 与 uk_provider_norm_name 双唯一键兜底并发窗口
     * （DuplicateKeyException → 1007C0017，沿 dataset uk_space_norm_name 先例）。
     *
     * @return 新产品技术主键
     */
    long create(ProviderProductRow product, ProductActionLog log);

    /**
     * 变更（W9）：可变字段动态 SET + UPDATE 留痕（summary 含 field:from→to）同事务；
     * 乐观门槛 WHERE id（属主已在应用层判定；已注销已在应用层拒绝——终态行不可变更）。
     */
    void updateFields(long productId, ProductUpdate update, ProductActionLog log);

    /**
     * 状态转换（W10 上架/W11 下架/W12 强制下架/W13 注销共用）：乐观门槛
     * WHERE id AND status = fromStatus（0 行 = 状态已被并发改变 → 1007C0019），
     * 命中则 SET status = toStatus（上架时同步写 listed_at、下架/注销时置 NULL——V3 注释
     * "非在架态为 NULL" 口径）+ 动作留痕同事务。
     *
     * @return true = 转换成功；false = 并发状态漂移（应用层转 1007C0019）
     */
    boolean transitionStatus(long productId, ProductStatus fromStatus, ProductStatus toStatus,
            LocalDateTime listedAt, ProductActionLog log);

    /** 独立写一条产品留痕（DENIED 拒绝留痕等；只插不改，独立提交不随异常回滚）。 */
    void insertLog(ProductActionLog log);

    /**
     * 产品变更载荷（W9 可变字段集 = 简介/形态/定价模型/定价数值/类目——名称不可变，
     * 沿 dataset 名称不可变先例；null = 不变更该项）。
     */
    record ProductUpdate(String intro, String productType, String pricingModel,
            java.math.BigDecimal priceAmount, String categoryCode) {
    }
}
