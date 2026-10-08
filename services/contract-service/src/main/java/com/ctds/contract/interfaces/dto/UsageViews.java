package com.ctds.contract.interfaces.dto;

import com.ctds.common.pagination.PageResult;
import com.ctds.contract.application.UsageQueryService;
import com.ctds.contract.domain.policy.UsageLogEntry;
import java.time.LocalDateTime;

/**
 * R12 使用摘要出站视图（WBS-3.4.5 hifi §6 响应契约）：计数视图 + 放行/拒绝统计 + 记录分页。
 * 记录行只含要素码与结果（不含条款原文与请求文本——留痕四要素纪律）。
 */
public final class UsageViews {

    private UsageViews() {
    }

    /** 应用层摘要 → 出站视图。 */
    public static Summary of(final UsageQueryService.UsageSummary summary) {
        return new Summary(summary.contractNo(),
                summary.quota() == null ? null
                        : new Quota(summary.quota().limit(), summary.quota().used()),
                summary.allowedCount(), summary.deniedCount(),
                PageViews.page(summary.records(), UsageViews::rowOf));
    }

    private static Row rowOf(final UsageLogEntry entry) {
        return new Row(entry.requesterNo(), entry.actionType().name(), entry.outcome().name(),
                entry.reasonCode(), entry.violations(), entry.usedCount(), entry.occurredAt());
    }

    /** R12 摘要（quota 为 null = 无生效配额口径）。 */
    public record Summary(String contractNo, Quota quota, long allowedCount, long deniedCount,
            PageResult<Row> records) {
    }

    /** 配额视图（limit = 策略上限，used = 已用次数）。 */
    public record Quota(int limit, int used) {
    }

    /** 执行记录行（触发要素码逗号分隔；放行行 reasonCode/violations 为 null）。 */
    public record Row(String requesterNo, String actionType, String outcome, String reasonCode,
            String violations, Integer usedCount, LocalDateTime occurredAt) {
    }
}
