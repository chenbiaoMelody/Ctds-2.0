package com.ctds.evidence.application;

import java.time.Duration;

/**
 * 监管审计读面硬约束常量（WBS-3.8.4 hifi §6 / Q10-A：全沿 3.8.2 既有值同口径 + 报告清单上限，
 * V1.0 常量不配置化，避免配置漂移）。
 */
public final class EvidenceAuditLimits {

    /** 分页页码上限（沿 3.8.2 读面）。 */
    public static final int MAX_PAGE_NUM = 10_000;

    /** 分页每页上限（沿 3.8.2 读面）。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 时间窗上限（沿 3.8.2 读面：≤90 天）。 */
    public static final Duration MAX_WINDOW = Duration.ofDays(90);

    /** 报告清单条数上限（V1.0 常量；超过拒生成）。 */
    public static final int MAX_REPORT_RECORDS = 10_000;

    /** 超限文案（与 hifi E4 契约逐字一致）。 */
    public static final String OVER_LIMIT_MESSAGE = "清单条数超过导出上限 10000";

    private EvidenceAuditLimits() {
    }
}
