package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.InteractionLogRow;
import java.time.LocalDateTime;

/**
 * 互动留痕视图（WBS-3.3.6 hifi §1.2 R16 出参；恒仅本人——收藏/订阅/取消/退订与拒绝记录，
 * denyReason = 错误码尾号；不含敏感原文）。
 */
public record InteractionLogView(Long id, Long productId, String action, String outcome,
        String denyReason, LocalDateTime createdAt) {

    /** 行 → 视图映射（subjectNo = 本人，不重复外露）。 */
    public static InteractionLogView from(final InteractionLogRow row) {
        return new InteractionLogView(row.id(), row.productId(), row.action(), row.outcome(),
                row.denyReason(), row.createdAt());
    }
}
