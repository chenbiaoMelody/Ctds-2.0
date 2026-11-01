package com.ctds.metering.domain;

import java.time.LocalDateTime;

/** 计量流水值对象（metering_flow 行映射；uk(source_type, source_no) 幂等锚）。 */
public record MeteringFlow(
        String contractNo,
        String requesterNo,
        MeteringSourceType sourceType,
        String sourceNo,
        String action,
        String resource,
        LocalDateTime occurredAt) {
}
