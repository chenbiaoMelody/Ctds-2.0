package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 连接器 ↔ 所属主体 DID 绑定行（规格行为 8）。
 * 只记绑定关系与时间，**不冗余 DID 状态**（单一事实源在 DID 域，查验时实时读取）。
 */
public record ConnectorBinding(
        Long id,
        String connectorNo,
        String subjectNo,
        String did,
        BindingStatus status,
        LocalDateTime boundAt,
        LocalDateTime releasedAt) {
}
