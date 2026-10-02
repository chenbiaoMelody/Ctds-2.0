package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 产品订阅关系仓储端口（WBS-3.3.4 hifi §8；实现 = infrastructure.JdbcProductSubscriptionRepository）。
 * 订阅幂等与条目口径沿 {@link ProductFavoriteRepository} 同款（uk_subject_product 兜底 + 重放首次结果）；
 * 事务边界口径同款（条目与留痕两写同事务在仓储方法内，DENIED 留痕单独落库不被回滚）。
 */
public interface ProductSubscriptionRepository {

    /** 本人条目（幂等判定与 W7 退订前置查、R8 订阅者判定；空 = 无条目）。 */
    Optional<LocalDateTime> findSubscribedAt(String subjectNo, long productId);

    /** 插入订阅条目 + SUCCEEDED 留痕（幂等链第 ⑥ 步，两写同事务）。 */
    void insertWithLog(String subjectNo, long productId, LocalDateTime createdAt, ProductInteractionLog log);

    /** 删除本人条目 + SUCCEEDED 留痕（W7，两写同事务；返回是否确有删除）。 */
    boolean deleteWithLog(String subjectNo, long productId, ProductInteractionLog log);

    /** 本人订阅分页（R10：join 产品取当前状态与目录元数据，条目本身不删——Q5-A 读时计算）。 */
    PageResult<CatalogProductRow> pageBySubject(String subjectNo, PageQuery page);
}
