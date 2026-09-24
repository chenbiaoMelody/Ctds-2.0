package com.ctds.did.domain;

import java.time.LocalDateTime;

/**
 * 互认留痕（WBS-3.1.10 行为 5 规则 2 + hifi §3）：方向 / 对端空间标识 / DID / 结果（失败或不可用另含原因）/ 时间；
 * <b>不保存业务数据原文</b>（互认通道不是数据存储通道）。
 */
public record InteropLog(
        InteropDirection direction,
        String peerSpace,
        String did,
        VerificationOutcome result,
        VerificationReason reason,
        LocalDateTime occurredAt) {
}
