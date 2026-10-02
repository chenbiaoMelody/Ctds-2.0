package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;

/**
 * 产品变更留痕仓储端口（WBS-3.3.4 建载体 + R8 读面；WBS-3.3.5 动作码值域经 V4 登记——
 * 写入统一走 {@link DataProductRepository#insertLog} 单通道，本端口只承载订阅者读面）。
 */
public interface ProductActionLogRepository {

    /**
     * 产品变更留痕分页（R8：按 created_at 倒序；<b>可见值域过滤</b> = 变更类六码
     * （{@link ProductActionLog#SUBSCRIBER_VISIBLE_ACTIONS}，Q5-A——DENIED_ 前缀拒绝码与
     * GOVERNANCE_VIEW 不对订阅者暴露）。
     */
    PageResult<ProductChangeLogRow> pageByProduct(long productId, PageQuery page);
}
