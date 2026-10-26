package com.ctds.connector.domain;

import java.util.Optional;

/**
 * 绑定仓储：建立（核验通过事务内）与按连接器读取生效绑定。
 */
public interface ConnectorBindingRepository {

    void insert(ConnectorBinding binding);

    /** 生效绑定（status = ACTIVE）。 */
    Optional<ConnectorBinding> findActiveByConnectorNo(String connectorNo);
}
