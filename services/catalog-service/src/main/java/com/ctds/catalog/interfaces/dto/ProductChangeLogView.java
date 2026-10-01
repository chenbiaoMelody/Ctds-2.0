package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.ProductChangeLogRow;
import java.time.LocalDateTime;

/**
 * 产品变更留痕视图（WBS-3.3.4 hifi §1 R8 出参；按 created_at 倒序返回，限定本人订阅者可查——
 * 变更感知 V1.0 = 留痕可查，Q6-A；不含敏感原文）。
 */
public record ProductChangeLogView(String action, String summary, String operatorSubjectNo,
        LocalDateTime createdAt) {

    /** 行 → 视图映射。 */
    public static ProductChangeLogView from(final ProductChangeLogRow row) {
        return new ProductChangeLogView(row.action(), row.summary(), row.operatorSubjectNo(), row.createdAt());
    }
}
