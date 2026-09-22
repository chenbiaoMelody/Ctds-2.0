package com.ctds.did.domain;

/**
 * DID 记录状态（WBS-3.1.8 hifi §2.1）：PENDING_ISSUE 为签发记录中间态，不属 DID 状态；
 * 对外 DID 状态只映射 ACTIVE（有效）/ REVOKED（已吊销）两值（规格行为 2 规则 1）。
 */
public enum DidStatus {
    PENDING_ISSUE,
    ACTIVE,
    REVOKED
}
