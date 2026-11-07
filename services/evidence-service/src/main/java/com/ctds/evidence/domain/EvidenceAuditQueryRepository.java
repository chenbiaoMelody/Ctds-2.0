package com.ctds.evidence.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 监管审计读面查询端口（WBS-3.8.4 hifi §2；只读——无任何写入方法）：
 * 总览矩阵计数 / 核验不一致跨笔计数 / 异常项分页 / 审计条件分页（含主体摘要过滤）/
 * 按单据号取最新记录 / 最新核验结论批量与单条查询。MISMATCH 判定 = 每条存证**最新一条**
 * action=VERIFIED 留痕 detail='MISMATCH'（时间线口径与核验写面一致）。
 */
public interface EvidenceAuditQueryRepository {

    /** 环节 × 状态计数（时间窗过滤 occurred_at；空窗 = 全量）。 */
    List<StageStatusCount> countByStageAndStatus(Instant from, Instant to);

    /** 核验不一致跨笔计数（最新 VERIFIED 结论 = MISMATCH；时间窗过滤记录 occurred_at）。 */
    long countMismatch(Instant from, Instant to);

    /** 异常项分页（PENDING / FAILED / MISMATCH 三类并集，一类一行；occurred_at DESC 稳定序）。 */
    AnomalyPage pageAnomalies(AuditQueryCriteria criteria, int offset, int limit);

    /** 审计条件分页（存证清单模式；occurred_at DESC, id DESC 稳定序，沿 3.8.2 读面口径）。 */
    EvidencePage pageByCriteria(AuditQueryCriteria criteria, int offset, int limit);

    /** 按业务单据号取最新记录（同单号多记录取 occurred_at 最新；查无 = empty）。 */
    Optional<EvidenceRecord> findLatestByBusinessNo(String businessNo);

    /** 条件内各记录的最新核验结论（无 VERIFIED 留痕 = 未核验）。 */
    List<VerdictRow> latestVerdicts(AuditQueryCriteria criteria);

    /** 单条记录的最新核验结论。 */
    Optional<VerdictRow> latestVerdict(String evidenceNo);

    /** 环节 × 状态计数行。 */
    record StageStatusCount(String stageType, String status, long count) {
    }

    /** 异常项行（record + 类型 + 最新核验结论与核验人时点；PENDING / FAILED 行核验字段为空）。 */
    record AnomalyRow(EvidenceRecord record, String anomalyType, String verifyConclusion,
            String verifiedBy, Instant verifiedAt) {
    }

    /** 异常项分页载体（沿 EvidencePage 形态）。 */
    record AnomalyPage(List<AnomalyRow> list, long total) {
    }

    /** 最新核验结论行（conclusion = PASS / MISMATCH / NOT_ON_CHAIN；无核验 = empty 载体不返回）。 */
    record VerdictRow(String evidenceNo, String conclusion, String verifiedBy, Instant verifiedAt) {
    }
}
