package com.ctds.connector.domain;

import java.util.List;

/**
 * 动作留痕仓储（只插不改，唯一例外 = 核验通过时把该申请的 connector_no 回填，保证按连接器可读全链）。
 */
public interface ConnectorLogRepository {

    void insert(ConnectorActionLog log);

    /** 核验通过时回填：该申请单下 connector_no 为空的历史留痕补上连接器编号。 */
    void attachConnectorNo(String applicationNo, String connectorNo);

    /** 单连接器留痕（时间正序，便于读"申请 → 核验 → 绑定"链）。 */
    List<ConnectorActionLog> findByConnectorNo(String connectorNo, long offset, int limit);

    long countByConnectorNo(String connectorNo);
}
