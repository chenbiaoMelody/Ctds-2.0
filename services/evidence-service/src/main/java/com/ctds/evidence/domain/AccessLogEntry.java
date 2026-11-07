package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 审计调阅 / 导出动作留痕（WBS-3.8.4 hifi §2；B6-3 / B7-3 可查可核验）：
 * append-only——只插不改不删；conditions_json 仅存脱敏后条件键值（键 ≤20、值 ≤512，
 * 沿 common 审计事件契约口径）。
 *
 * @param accessKind     TRAIL_ACCESS（调阅）/ REPORT_EXPORT（导出）两值封闭
 * @param accessedBy     调阅人 / 导出人（主体编号）
 * @param conditionsJson 调阅 / 导出条件（脱敏键值 JSON；无原文与个人信息）
 * @param resultCount    返回条数（时间线节点数或清单 / 导出行数）
 * @param purpose        结论用途（可空；V1.0 端点不传即 null）
 */
public record AccessLogEntry(String accessKind, String accessedBy, String conditionsJson,
        int resultCount, String purpose, Instant createdAt) {

    /** 调阅留痕 kind。 */
    public static final String KIND_TRAIL_ACCESS = "TRAIL_ACCESS";

    /** 导出留痕 kind。 */
    public static final String KIND_REPORT_EXPORT = "REPORT_EXPORT";
}
