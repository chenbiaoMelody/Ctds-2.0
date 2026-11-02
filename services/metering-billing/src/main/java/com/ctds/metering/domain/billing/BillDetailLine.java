package com.ctds.metering.domain.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账单明细行（hifi §2 billing_bill_detail）：按次账单逐笔映射计量流水（metering_flow_id 可溯）；
 * 包月 / 分成账单各一行。行类型：PER_CALL_EVENT / PERIOD_FEE / REVENUE_SHARE_BASE。
 */
public record BillDetailLine(
        long id,
        String billNo,
        int lineNo,
        String lineType,
        String description,
        Long meteringFlowId,
        String sourceType,
        String sourceNo,
        LocalDateTime occurredAt,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal amount) {

    /** 按次事件行（逐笔映射计量流水）。 */
    public static final String LINE_PER_CALL_EVENT = "PER_CALL_EVENT";
    /** 包月周期费行。 */
    public static final String LINE_PERIOD_FEE = "PERIOD_FEE";
    /** 交易额分成行（基数 × 费率，数值在账单头快照）。 */
    public static final String LINE_REVENUE_SHARE_BASE = "REVENUE_SHARE_BASE";
}
