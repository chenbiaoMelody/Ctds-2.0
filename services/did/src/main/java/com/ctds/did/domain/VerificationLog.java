package com.ctds.did.domain;

import java.time.LocalDateTime;

/**
 * 验证留痕（WBS-3.1.9 行为 3 规则 3）：时间 / DID / 结果（失败或不可用另含原因）；
 * 不保存业务数据原文（验证通道不是数据存储通道）。
 */
public record VerificationLog(
        String did,
        VerificationOutcome result,
        VerificationReason reason,
        LocalDateTime occurredAt) {
}