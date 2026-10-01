package com.ctds.catalog.domain;

/**
 * 目录域收藏订阅动作留痕仓储端口（WBS-3.3.4 hifi §8；实现 = infrastructure.JdbcProductInteractionLogRepository）。
 * 留痕与条目写入同事务（沿 dataset_action_log 先例）；幂等重放不新增留痕（ADR-007 零新增副作用）。
 */
public interface ProductInteractionLogRepository {

    /** 插入留痕（成功与 DENIED 拒绝共用；四要素齐备）。 */
    void insert(ProductInteractionLog log);
}
