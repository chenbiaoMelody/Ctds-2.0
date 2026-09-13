package com.ctds.kms.domain;

import java.time.LocalDateTime;

/** 密钥操作审计记录（规格行为 2：操作者/时间/编号/新旧版本四要素）。 */
public record KeyAuditRecord(String action, String keyRef, Integer oldVersion, Integer newVersion,
                             String operator, LocalDateTime occurredAt) {
}
