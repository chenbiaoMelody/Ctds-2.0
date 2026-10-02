package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;

/**
 * 产品变更留痕仓储端口（WBS-3.3.4 hifi §8；实现 = infrastructure.JdbcProductActionLogRepository）。
 * 载体 = product_action_log：本卡建载体 + 只读（R8 订阅感知读面），写入动作码集合随 3.3.5 登记。
 */
public interface ProductActionLogRepository {

    /** 产品变更留痕分页（R8：按 created_at 倒序）。 */
    PageResult<ProductChangeLogRow> pageByProduct(long productId, PageQuery page);
}
