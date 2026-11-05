package com.ctds.evidence.application;

/**
 * 异常概览（hifi §1.2 / 规格 B4-2 最小只读承载）：三状态计数 + 最近失败时点。
 */
public record EvidenceOverviewView(
        long onChainCount,
        long pendingCount,
        long failedCount,
        String lastFailedAt) {
}
