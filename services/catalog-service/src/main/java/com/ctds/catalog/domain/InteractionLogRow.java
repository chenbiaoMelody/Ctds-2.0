package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 收藏订阅互动留痕行载体（WBS-3.3.6 R16 读面；沿行载体先例——写入仍走
 * {@link ProductInteractionLog} 领域行，本载体服务按主体分页读面的 id 携带）。
 */
public record InteractionLogRow(Long id, Long productId, String action, String outcome,
        String denyReason, LocalDateTime createdAt) {
}
