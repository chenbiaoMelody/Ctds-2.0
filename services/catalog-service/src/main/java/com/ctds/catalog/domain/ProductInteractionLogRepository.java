package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;

/**
 * 目录域收藏订阅动作留痕仓储端口（WBS-3.3.4 hifi §8；实现 = infrastructure.JdbcProductInteractionLogRepository）。
 * 留痕与条目写入同事务（沿 dataset_action_log 先例）；幂等重放不新增留痕（ADR-007 零新增副作用）。
 */
public interface ProductInteractionLogRepository {

    /** 插入留痕（成功与 DENIED 拒绝共用；四要素齐备）。 */
    void insert(ProductInteractionLog log);

    /**
     * 按主体分页读互动留痕（WBS-3.3.6 R16 只读读面；恒仅本人行、按 created_at DESC, id DESC——
     * 只读既有 product_interaction_log，零写入面；空列表 = 正常空页）。
     */
    PageResult<InteractionLogRow> pageBySubject(String subjectNo, PageQuery page);
}
