package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * 数据产品读面仓储端口（WBS-3.3.4 hifi §8；实现 = infrastructure.JdbcDataProductRepository）。
 * 写面（封装/上下架/注销）归 3.3.5——本接口只承载目录读面（R6/R7/R8）与交互链状态门槛（W4/W6）。
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
}
