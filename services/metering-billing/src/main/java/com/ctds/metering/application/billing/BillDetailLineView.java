package com.ctds.metering.application.billing;

import java.time.LocalDateTime;

/**
 * 账单明细行出站视图（hifi §3.4）：按次行给出 meteringFlowId / 源单号 / 事件时点（逐笔可溯）。
 */
public record BillDetailLineView(
        int lineNo,
        String lineType,
        String description,
        Long meteringFlowId,
        String sourceType,
        String sourceNo,
        LocalDateTime occurredAt,
        int quantity,
        String unitPrice,
        String amount) {
}
