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

    /** 合约参与方存在性（WBS-3.5.6 Q5-A G7 可见性守卫：该合约下调用者为需求方或提供方）。 */
    boolean existsByContractParticipant(String contractNo, String callerNo);

    /** 合约动作留痕分页（WBS-3.5.6 Q5-A G7；id 倒序最新在前）。 */
    List<DeliveryActionLog> pageActionLogs(String contractNo, PageQuery page);

    long countActionLogs(String contractNo);
}
