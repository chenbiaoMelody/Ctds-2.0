package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 连接器注册申请 / 档案行（唯一事实源）。
 * 待核验态 connectorNo 为空；核验通过时在同一事务内签发编号并转 REGISTERED。
 * 不含任何秘密材料（无凭据、无密钥引用）。
 */
public record ConnectorRegistration(
        Long id,
        String applicationNo,
        String connectorNo,
        String subjectNo,
        String connectorName,
        String connectorType,
        String connectorVersion,
        ConnectorStatus status,
        String lastRejectionCode,
        String lastRejectionReason,
        LocalDateTime lastHeartbeatAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
