package com.ctds.metering.domain;

import java.time.LocalDateTime;

/**
 * 运营全量流水读面行（WBS-3.7.7 hifi §1 R-M4）：metering_flow 九字段最小投影——
 * 逐条可溯（规格 B12-2），零请求体 / 响应体 / 原文列（ADR-022 §2.6-2）。
 */
public record MeteringFlowRow(
        long id,
        String contractNo,
        String requesterNo,
        MeteringSourceType sourceType,
        String sourceNo,
        String action,
        String resource,
        LocalDateTime occurredAt,
        LocalDateTime createdAt) {
}
