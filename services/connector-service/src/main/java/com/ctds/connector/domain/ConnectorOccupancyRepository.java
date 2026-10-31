package com.ctds.connector.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 在途作业占用仓储（注销前置检查口径）：登记幂等（同连接器 + 同 jobRef 不增行）、释放幂等。
 */
public interface ConnectorOccupancyRepository {

    /**
     * 登记在途占用（同 (connectorNo, jobRef) 幂等：复位为 OCCUPY 并刷新登记时间）。
     * WBS-3.6.8 W5 / DB-75：SQL 谓词原子化——仅 {@code connector_registration.status='REGISTERED'}
     * 时落行，返回受影响行数（0 = 连接器不存在 / 非 REGISTERED；服务层以 find 幂等重放复核）。
     */
    int occupy(String connectorNo, String jobRef, LocalDateTime now);

    /** 释放（返回受影响行数；仅 OCCUPY → RELEASED 生效）。 */
    int release(String connectorNo, String jobRef, LocalDateTime now);

    Optional<ConnectorJobOccupancy> find(String connectorNo, String jobRef);

    /** 在途计数（status = OCCUPY，注销前置检查唯一口径）。 */
    long countInProgress(String connectorNo);

    /** 占用清单（含已释放，时间倒序）。 */
    List<ConnectorJobOccupancy> list(String connectorNo);
}
