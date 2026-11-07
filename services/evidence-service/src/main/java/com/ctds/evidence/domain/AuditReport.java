package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 审计报告登记（WBS-3.8.4 hifi §2；B7-1 / B7-2）：生成时快照整行落库——内容 JSON 仅存证要素 /
 * 摘要 / 元数据（零原文与个人信息）、SM3 内容摘要随行登记；append-only 只插不改，
 * 重复生成 = 新编号不覆盖（uk_report_no 唯一锚）。
 */
public record AuditReport(long id, String reportNo, String scopeJson, int recordCount,
        String conclusionSummaryJson, int anomalyCount, String reportContentJson,
        String contentDigest, String remark, String generatedBy, Instant generatedAt) {

    /** 取号后携带编号生成登记行（id 由库生成，占位 0）。 */
    public static AuditReport draft(String reportNo, String scopeJson, int recordCount,
            String conclusionSummaryJson, int anomalyCount, String reportContentJson,
            String contentDigest, String remark, String generatedBy, Instant generatedAt) {
        return new AuditReport(0L, reportNo, scopeJson, recordCount, conclusionSummaryJson,
                anomalyCount, reportContentJson, contentDigest, remark, generatedBy, generatedAt);
    }
}
