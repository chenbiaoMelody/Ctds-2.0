package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;

/**
 * 产品操作留痕仓储端口（WBS-3.3.4 建载体 + R8 读面；WBS-3.3.5 扩展写面——动作码集合随 V4 登记）。
 */
public interface ProductActionLogRepository {

    /**
     * 产品变更留痕分页（R8：按 created_at 倒序；<b>可见值域过滤</b> = 变更类六码
     * （{@link ProductActionLog#SUBSCRIBER_VISIBLE_ACTIONS}，Q5-A——DENIED_* 与 GOVERNANCE_VIEW
     * 不对订阅者暴露）。
     */
    PageResult<ProductChangeLogRow> pageByProduct(long productId, PageQuery page);

    /** 独立写一条留痕（WBS-3.3.5：DENIED 拒绝留痕与治理查看留痕；只插不改）。 */
    void insert(ProductActionLog log);
}
