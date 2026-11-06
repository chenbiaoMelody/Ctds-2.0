package com.ctds.evidence.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 存证仓储端口（hifi §2 结构性保证）：只暴露追加与状态推进能力——
 * {@code insert}（新建）/ {@code updateStatus}（仅状态列 + 重试计数 + 原因摘要 + updated_at）/
 * {@code appendAction}（追加留痕）/ 只读查询。
 *
 * <p><b>无改内容入口、无删除入口</b>（规格 B2-4 / B4-4）：内容列一经写入只能由数据库层面
 * 运维处置，业务与运营侧都没有方法可调用。</p>
 */
public interface EvidenceRepository {

    /** 新建存证记录（内容列一次性写入）。并发重复三元组由数据库唯一键兜底。 */
    void insert(EvidenceRecord record);

    /** 状态推进（只更新 status / chain_tx_id / chain_block_no / retry_count /
     *  last_error_summary / updated_at 六列）。 */
    void updateStatus(EvidenceRecord record);

    /** 追加状态流转或核验留痕（append-only）。 */
    void appendAction(EvidenceActionLog action);

    /** 按存证编号查询。 */
    Optional<EvidenceRecord> findByNo(String evidenceNo);

    /** 按幂等三元组查询（环节 + 业务单据号 + 操作类型）。 */
    Optional<EvidenceRecord> findByIdempotentKey(EvidenceStageType stageType, String businessNo,
            EvidenceOperationType operationType);

    /** 分页查询（occurred_at 倒序、id 倒序），返回切片与总数。 */
    EvidencePage page(EvidenceSearchCriteria criteria, int offset, int limit);

    /** 按状态计数（异常概览）。 */
    long countByStatus(EvidenceStatus status);

    /** 最近一次失败时点（异常概览 lastFailedAt）；无失败记录 = 空。 */
    Optional<Instant> lastFailedAt();

    /** 某笔存证的全部留痕（按 id 正序）。 */
    List<EvidenceActionLog> findActions(String evidenceNo);
}
