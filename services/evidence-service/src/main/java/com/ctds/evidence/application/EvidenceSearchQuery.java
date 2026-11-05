package com.ctds.evidence.application;

/**
 * 存证清单查询参数（hifi §1.2：stageType / businessNo / status / from / to / pageNum / pageSize）。
 */
public record EvidenceSearchQuery(
        String stageType,
        String businessNo,
        String status,
        String from,
        String to,
        Integer pageNum,
        Integer pageSize) {
}
