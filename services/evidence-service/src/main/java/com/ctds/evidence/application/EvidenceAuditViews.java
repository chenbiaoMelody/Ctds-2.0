package com.ctds.evidence.application;

import com.ctds.evidence.domain.EvidenceAuditQueryRepository.AnomalyRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * 监管审计读面出站视图（WBS-3.8.4 hifi §1 E1~E4；白名单出站，零原文零个人信息——
 * 主体侧一律 DID 摘要 / 摘要化操作人，见 EvidenceTrailService 聚合层）。
 */
public final class EvidenceAuditViews {

    /** E1 总览：四环节 × 三状态矩阵 + 异常三计数 + 快照时点。 */
    public record OverviewView(Map<String, StageStatusView> matrix,
            AnomalyCountsView anomalies, String snapshotAt) {
    }

    /** 单环节三状态计数（键 = 状态枚举名大写，与 hifi E1 契约逐字一致；缺省补零，矩阵形状恒定）。 */
    public record StageStatusView(@JsonProperty("PENDING") long pending,
            @JsonProperty("ON_CHAIN") long onChain, @JsonProperty("FAILED") long failed) {
    }

    /** 异常三计数（B5-2②）。 */
    public record AnomalyCountsView(long pendingCount, long failedCount, long mismatchCount) {
    }

    /** E2 异常项行：存证记录白名单字段 + 异常类型 + 最新核验结论与核验人时点。 */
    public record AnomalyItemView(String evidenceNo, String stageType, String businessNo,
            String operationType, String subjectDidDigest, String occurredAt,
            String contentDigest, String status, String chainTxId, Long chainBlockNo,
            int retryCount, String lastErrorSummary, String createdAt, String updatedAt,
            String anomalyType, String verifyConclusion, String verifiedBy, String verifiedAt) {

        public static AnomalyItemView of(final EvidenceRecordView view, final AnomalyRow row) {
            return new AnomalyItemView(view.evidenceNo(), view.stageType(), view.businessNo(),
                    view.operationType(), view.subjectDidDigest(), view.occurredAt(),
                    view.contentDigest(), view.status(), view.chainTxId(), view.chainBlockNo(),
                    view.retryCount(), view.lastErrorSummary(), view.createdAt(), view.updatedAt(),
                    row.anomalyType(), row.verifyConclusion(), row.verifiedBy(),
                    row.verifiedAt() == null ? null : row.verifiedAt().toString());
        }
    }

    /**
     * E3 调阅双模视图：mode = TRAIL（单笔全链时间线：evidenceRecord + evidenceActions +
     * businessTrail + timelineAvailable）或 LIST（条件清单分页 + 展开提示 trailHint）。
     * 两模字段互斥出现（非本模字段为 null）。
     */
    public record TrailView(String mode, EvidenceRecordView evidenceRecord,
            List<TrailActionView> evidenceActions, List<TrailNodeView> businessTrail,
            Boolean timelineAvailable, Long total, Integer pageNum, Integer pageSize,
            Integer totalPages, List<EvidenceRecordView> list, String trailHint) {
    }

    /** 存证侧留痕行（操作人摘要化出站——actorDigest = SM3 原文口径见 EvidenceTrailService）。 */
    public record TrailActionView(String action, String fromStatus, String toStatus,
            String actorDigest, String reasonCode, String detail, String occurredAt) {
    }

    /** 跨域留痕节点（sourceStatus = OK | UNAVAILABLE；降级不伪装连续，B2-1 fail-visible 同型）。 */
    public record TrailNodeView(String source, String occurredAt, String actorDigest,
            String action, String result, String summary, String sourceStatus) {
    }

    /** E4 报告生成返回：编号唯一 + 范围条数 + 结论分布 + 异常条数 + 内容摘要 + 生成要素。 */
    public record ReportView(String reportNo, int recordCount,
            Map<String, Integer> conclusionSummary, int anomalyCount, String contentDigest,
            String remark, String generatedBy, String generatedAt) {
    }

    private EvidenceAuditViews() {
    }
}
