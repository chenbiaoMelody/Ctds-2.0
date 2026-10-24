package com.ctds.delivery.domain;

/** 结果物审核状态（hifi §3.1）：待审核 → 已通过 / 已驳回；驳回可重新提交（G2-A，轮次 +1）。 */
public enum SandboxResultStatus {
    PENDING,
    APPROVED,
    REJECTED
}
