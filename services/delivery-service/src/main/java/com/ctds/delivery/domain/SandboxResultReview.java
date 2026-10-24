package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 沙箱结果审核留痕（hifi §2.2）：一轮审核的不可变记录（谁审 / 何时 / 结论 / 理由 / 轮次）。
 */
public record SandboxResultReview(Long id, String sandboxNo, String fileName, int reviewRound,
        SandboxResultStatus conclusion, String reason, String reviewedBy, LocalDateTime createdAt) {
}
