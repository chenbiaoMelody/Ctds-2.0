package com.ctds.contract.domain.policy;

import java.time.LocalDateTime;

/**
 * 使用放行留痕审计回读行（WBS-3.8.4 hifi §1；Q1-A 跨域留痕锚）：
 * 与 {@link UsageLogEntry} 的差异 = 携带技术主键 id（USG 业务单据号锚点）且为纯只读传输形态，
 * 不参与任何写入路径。字段白名单逐列对应 contract_usage_log，不含敏感原文。
 *
 * @param id 使用留痕行主键（USG{12 位} 的锚点）
 */
public record UsageLogAuditRow(long id, String contractNo, String requesterNo, String actionType,
        String outcome, String reasonCode, String violations, Integer usedCount,
        LocalDateTime occurredAt) {
}
