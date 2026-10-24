package com.ctds.delivery.domain;

import com.ctds.common.pagination.PageQuery;
import java.util.List;
import java.util.Optional;

/** 交付记录与动作留痕仓储端口（JdbcClient 实现见 infrastructure）。 */
public interface DeliveryRepository {

    Optional<Delivery> findByIdempotencyKey(String requesterNo, String idempotencyKey);

    Optional<Delivery> findByDeliveryNo(String deliveryNo);

    /** 原子取下一个 DL+6 位编号（序号表；调用方保证单实例/事务语义，缺口可接受）。 */
    String nextDeliveryNo();

    void insert(Delivery delivery);

    List<Delivery> listForParticipant(String callerNo, String contractNo, DeliveryType type,
            PageQuery page);

    long countForParticipant(String callerNo, String contractNo, DeliveryType type);

    List<Delivery> listGovernance(String contractNo, String deliveryNo, PageQuery page);

    long countGovernance(String contractNo, String deliveryNo);

    /** 沙箱导出记录列表（SANDBOX_EXPORT 行按沙箱任务过滤；WBS-3.5.5）。 */
    List<Delivery> listBySandbox(String sandboxNo, DeliveryType type, PageQuery page);

    long countBySandbox(String sandboxNo, DeliveryType type);

    void insertActionLog(DeliveryActionLog log);
}
