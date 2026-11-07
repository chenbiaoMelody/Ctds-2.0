package com.ctds.evidence.application;

/**
 * E4 报告生成请求体（WBS-3.8.4 hifi §1）：条件 ≥1（stageType / businessNo / subjectDigest /
 * from / to 任一）、时间窗 ≤90 天；remark 为生成备注（可空，不参与条件判定）。
 */
public record EvidenceAuditReportRequest(String stageType, String businessNo,
        String subjectDigest, String from, String to, String remark) {
}
