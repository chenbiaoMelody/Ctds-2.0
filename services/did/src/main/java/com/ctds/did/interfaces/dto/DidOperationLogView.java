package com.ctds.did.interfaces.dto;

import com.ctds.did.domain.DidOperationLog;
import java.time.LocalDateTime;

/**
 * 操作留痕行视图（WBS-3.1.11 hifi §2.1；规格行为 4 规则 2 吊销五要素）：
 * 操作类型（签发/重签/吊销）/ 操作人 / 理由（仅吊销非空）/ 密钥引用（仅签发非空）/ 状态变更 / 时间。
 */
public record DidOperationLogView(
        String operation,
        String operator,
        String reason,
        String keyRef,
        String statusFrom,
        String statusTo,
        LocalDateTime occurredAt) {

    public static DidOperationLogView from(final DidOperationLog log) {
        return new DidOperationLogView(log.operation(), log.operator(), log.reason(), log.keyRef(),
                log.statusFrom(), log.statusTo(), log.occurredAt());
    }
}
