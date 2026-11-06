package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 存证清单过滤条件（hifi §1.2：环节 / 单据号 / 状态 / 时间窗）。
 * 全空 = 全量导出风险，由应用层拒绝（规格 B6-2 口径）。
 */
public record EvidenceSearchCriteria(
        EvidenceStageType stageType,
        String businessNo,
        EvidenceStatus status,
        Instant from,
        Instant to) {

    public boolean isEmpty() {
        return stageType == null && businessNo == null && status == null && from == null && to == null;
    }
}
