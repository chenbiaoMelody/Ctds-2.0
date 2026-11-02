package com.ctds.metering.domain.billing;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 计量与计数只读端口（hifi §5-2）：本域 metering_flow + 跨 schema 只读
 * ctds_contract.contract_usage_counter / contract_usage_log；全部只读，不写上游任何表。
 */
public interface BillingUsageReader {

    /** 对账四方数值（本期按 occurred_at 落自然月）。 */
    BillingUsageMetrics metrics(String contractNo, LocalDateTime periodStart, LocalDateTime periodEnd);

    /** 本期计量事件明细（按次账单逐笔明细与对账条目比对的数据源）。 */
    List<MeteringEventRow> events(String contractNo, LocalDateTime periodStart, LocalDateTime periodEnd);
}
