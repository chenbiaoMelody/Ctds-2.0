package com.ctds.connector.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 版本变更仓储（升级登记 + 版本历史链，单表承双职责）。
 * domain 只表达业务语义，分页以 offset/limit 表达（不依赖 common-pagination）。
 */
public interface ConnectorVersionChangeRepository {

    /** 升级单号取号（UC+6 位，单行原子）。 */
    String nextUpgradeNo();

    /** 登记一行变更（状态由调用方决定）。 */
    void insert(ConnectorVersionChange change);

    Optional<ConnectorVersionChange> findByUpgradeNo(String upgradeNo);

    /** 该连接器当前待确认升级（至多 1 条，唯一索引兜底）。 */
    Optional<ConnectorVersionChange> findPendingByConnectorNo(String connectorNo);

    /** 版本历史链（时间倒序）。 */
    List<ConnectorVersionChange> findByConnectorNo(String connectorNo, long offset, int limit);

    long countByConnectorNo(String connectorNo);

    /** 待确认队列（提交时间升序——先到先处置）。 */
    List<ConnectorVersionChange> findPendingQueue(long offset, int limit);

    long countPending();

    /** 显式确认：PENDING_CONFIRM → APPLIED（带状态乐观门槛，返回受影响行数）。 */
    int apply(String upgradeNo, String confirmedBy, LocalDateTime appliedAt);

    /** 注销作废：该连接器全部 PENDING_CONFIRM → VOIDED（返回受影响行数）。 */
    int voidPending(String connectorNo);
}
