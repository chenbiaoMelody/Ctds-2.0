package com.ctds.connector.domain;

import java.util.List;
import java.util.Optional;

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

    /**
     * 按动作 + detail 前缀取最近一条留痕（DB-90 通用读面；自检结论等派生视图复用，避免各仓储自造 SQL）。
     *
     * <p>只取 {@code SUCCESS / FAILURE} 结论行；返回完整留痕记录（含 createdAt，供派生视图还原结论时间）。</p>
     */
    Optional<ConnectorActionLog> findLatestByDetailPrefix(String connectorNo, String action,
            String detailPrefix);
}
