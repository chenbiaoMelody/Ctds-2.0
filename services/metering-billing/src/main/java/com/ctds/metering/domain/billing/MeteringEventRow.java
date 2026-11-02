package com.ctds.metering.domain.billing;

import java.time.LocalDateTime;

/**
 * 计量事件行（计费按次明细的逐笔溯源载体，hifi §3.2）：id = metering_flow.id，
 * sourceType/sourceNo 为事件源单号快照，occurredAt 为事件时点（周期归属依据）。
 */
public record MeteringEventRow(
        long id,
        String sourceType,
        String sourceNo,
        LocalDateTime occurredAt) {
}
