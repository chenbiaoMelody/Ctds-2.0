package com.ctds.metering.domain;

import java.time.LocalDateTime;

/** 事件源采集行（跨 schema 只读 SELECT 结果投影，hifi §2 采集查询契约）。 */
public record SourceRow(String sourceNo, String contractNo, String requesterNo,
        MeteringSourceType sourceType, LocalDateTime occurredAt) {
}
