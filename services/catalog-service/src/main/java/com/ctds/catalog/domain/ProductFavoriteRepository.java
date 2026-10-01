package com.ctds.catalog.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 产品收藏关系仓储端口（WBS-3.3.4 hifi §8；实现 = infrastructure.JdbcProductFavoriteRepository）。
 * 收藏幂等 = uk_subject_product 唯一键兜底 + 先查后插（命中即重放首次结果，ADR-007 重放语义）。
 * 条目写入与留痕写入同事务的边界在本仓储方法内（沿 {@code JdbcDatasetRepository.create} 两写先例）——
 * DENIED 拒绝留痕单独落库（无条目写入，须在调用方抛出业务异常前已提交，不可被回滚）。
 */
public interface ProductFavoriteRepository {

    /** 本人条目（幂等判定与 W5 取消前置查；空 = 无条目）。 */
    Optional<LocalDateTime> findFavoritedAt(String subjectNo, long productId);

    /** 插入收藏条目 + SUCCEEDED 留痕（幂等链第 ⑥ 步，两写同事务）。 */
    void insertWithLog(String subjectNo, long productId, LocalDateTime createdAt, ProductInteractionLog log);

    /** 删除本人条目 + SUCCEEDED 留痕（W5，两写同事务；返回是否确有删除）。 */
    boolean deleteWithLog(String subjectNo, long productId, ProductInteractionLog log);

    /** 本人收藏分页（R9：join 产品取当前状态与目录元数据，条目本身不删——Q5-A 读时计算）。 */
    PageResult<CatalogProductRow> pageBySubject(String subjectNo, PageQuery page);
}
