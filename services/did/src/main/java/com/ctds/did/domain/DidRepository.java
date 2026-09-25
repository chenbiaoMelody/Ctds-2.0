package com.ctds.did.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * DID 身份注册表仓储端口（infrastructure 实现；domain 不依赖存储细节）。
 * 时间戳一律经应用时钟（方法参数 now/occurredAt）写入，不走 DB NOW()（DB-22"两把钟"教训）。
 */
public interface DidRepository {

    /** 主体当前非吊销行（待签发或有效），用于幂等守卫。 */
    Optional<DidIdentity> findActiveOrPending(String subjectNo);

    /** 主体待签发行（重试对象）。 */
    Optional<DidIdentity> findPending(String subjectNo);

    /** 主体最新已吊销行（重签对象存在性判定）。 */
    Optional<DidIdentity> findLatestRevoked(String subjectNo);

    /** 按 DID 标识查询（吊销对象）。 */
    Optional<DidIdentity> findByDid(String did);

    /**
     * 建待签发记录（issuance_seq 由调用方给定，1 或重签递增序号）。
     * 并发重复触发时 uk_guard 拒绝 → 收敛为返回既有非吊销行（幂等）。
     */
    DidIdentity createPending(String subjectNo, int issuanceSeq, LocalDateTime now);

    /**
     * 转有效 + 落签发/重签留痕（同事务，保证留痕四要素与状态变更原子；带 status 乐观门槛，
     * 与 revoke 对称——并发双签发恰一人成功）。
     * 返回 true=本次完成并落留痕；false=行已被并发完成（ACTIVE，幂等场景，服务层收敛返回既有结果）。
     */
    boolean completeIssuance(long identityId, String publicKeyHex, String documentJson,
                             DidOperationLog operationLog);

    /** 主体历史最大签发序号 + 1（重签用）。 */
    int nextIssuanceSeq(String subjectNo);

    /** 吊销：ACTIVE → REVOKED + 释放 guard_key + 落吊销留痕（同事务；带 status 乐观门槛）。 */
    void revoke(long identityId, DidOperationLog operationLog);

    /** 验证留痕落库（WBS-3.1.9 行为 3 规则 3：时间/DID/结果+原因；不保存业务数据原文）。 */
    void insertVerificationLog(VerificationLog log);

    /**
     * 管理面读数：签发记录列表（WBS-3.1.11 B1；最新在前）。subjectNo/status 为 null = 不筛选；
     * 分页以 offset/limit 表达——domain 不依赖 common-pagination（分层规则 LayerRulesTest）。
     */
    List<DidIdentity> findRecords(String subjectNo, String status, int offset, int limit);

    /** 管理面读数：签发记录总数（与 {@link #findRecords} 同过滤口径）。 */
    long countRecords(String subjectNo, String status);

    /** 单 DID 操作留痕（B7：签发/重签/吊销，时间正序便于读"先签发后吊销"）。 */
    List<DidOperationLog> findOperationLogs(String did);

    /** 验证留痕列表（B14；did 为 null = 不筛选，最新在前）。 */
    List<VerificationLog> findVerificationLogs(String did, int offset, int limit);

    /** 验证留痕总数（与 {@link #findVerificationLogs} 同过滤口径）。 */
    long countVerificationLogs(String did);
}
