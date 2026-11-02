package com.ctds.metering.domain;

import java.time.LocalDateTime;

/**
 * 运营全量流水检索条件（WBS-3.7.7 hifi §1 R-M4）：五维过滤均为可选，
 * 时间区间按事件时点 occurred_at 闭区间（from &lt;= occurred_at &lt;= to）。
 */
public record FlowSearchCriteria(
        String contractNo,
        String requesterNo,
        MeteringSourceType sourceType,
        LocalDateTime from,
        LocalDateTime to) {
}
