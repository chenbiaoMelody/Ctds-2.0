package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 连接器版本变更行（规格行为 9）：一行 = 一次升级登记，同时是版本历史链的一节。
 * 兼容升级登记即 APPLIED；不兼容升级先 PENDING_CONFIRM，运营方确认后转 APPLIED。
 * 不含任何秘密材料。
 */
public record ConnectorVersionChange(
        Long id,
        String upgradeNo,
        String connectorNo,
        String subjectNo,
        String fromVersion,
        String toVersion,
        Compatibility compatibility,
        VersionChangeStatus status,
        String requestedBy,
        String confirmedBy,
        LocalDateTime appliedAt,
        LocalDateTime createdAt) {
}
