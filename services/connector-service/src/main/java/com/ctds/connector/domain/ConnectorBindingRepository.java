package com.ctds.connector.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 绑定仓储：建立（核验通过事务内）与按连接器读取生效绑定。
 */
public interface ConnectorBindingRepository {

    void insert(ConnectorBinding binding);

    /** 生效绑定（status = ACTIVE）。 */
    Optional<ConnectorBinding> findActiveByConnectorNo(String connectorNo);

    /** 任意状态绑定（含已解除；生命周期读面用，记录保留可查）。 */
    Optional<ConnectorBinding> findByConnectorNo(String connectorNo);

    /** 解除绑定（ACTIVE → RELEASED + released_at，记录保留；返回受影响行数）。 */
    int release(String connectorNo, LocalDateTime now);
}
