package com.ctds.did.domain;

import java.time.LocalDateTime;

/**
 * DID 操作留痕（WBS-3.1.8 hifi §2.2）：一表承载签发（四要素）/ 吊销（五要素），nullable 列区分。
 * keyRef 仅签发/重签时填，吊销不填；reason 仅吊销填。
 */
public record DidOperationLog(
        String did,
        String subjectNo,
        String operation,
        String operator,
        String reason,
        String keyRef,
        String statusFrom,
        String statusTo,
        LocalDateTime occurredAt) {
}
