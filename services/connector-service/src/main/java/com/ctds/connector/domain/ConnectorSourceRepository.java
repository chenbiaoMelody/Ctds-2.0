package com.ctds.connector.domain;

import java.util.List;
import java.util.Optional;

/**
 * 源接入登记仓储（domain 只表达业务语义，分页以 offset/limit 表达——domain 不依赖 common-pagination）。
 *
 * <p>自检结论不落独立表：{@link #findLastCheck} 从动作留痕取最近一条 {@code source.check} 派生。</p>
 */
public interface ConnectorSourceRepository {

    /** 源编号取号（SR+6 位，单行原子）。 */
    String nextSourceNo();

    /** 建档（仅业务列；时间列由库默认值填充）。 */
    void insert(ConnectorSource source);

    /** 变更源地址 / 凭据（返回受影响行数）。 */
    int update(String sourceNo, String endpoint, String credentialRef, String credentialCiphertext,
            String credentialMasked);

    Optional<ConnectorSource> findBySourceNo(String sourceNo);

    /** 按凭据引用定位（适配器调用期解析凭据，引用为不透明唯一值）。 */
    Optional<ConnectorSource> findByCredentialRef(String credentialRef);

    /** 单连接器源清单（登记时间倒序）。 */
    List<ConnectorSource> listByConnector(String connectorNo, long offset, int limit);

    long countByConnector(String connectorNo);

    /** 同连接器同类型同地址防重登记。 */
    boolean existsDuplicate(String connectorNo, String sourceType, String endpoint);

    /** 最近一条自检结论（无则 empty；单查询，无 N+1）。 */
    Optional<ConnectorSourceCheck> findLastCheck(String connectorNo, String sourceNo);
}
