package com.ctds.did.domain;

import java.time.LocalDateTime;
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

    /** 转有效 + 落签发/重签留痕（同事务，保证留痕四要素与状态变更原子）。 */
    void completeIssuance(long identityId, String subjectNo, String operation, String did,
                          String publicKeyHex, String keyRef, String documentJson,
                          String operator, LocalDateTime occurredAt);

    /** 主体历史最大签发序号 + 1（重签用）。 */
    int nextIssuanceSeq(String subjectNo);

    /** 吊销：ACTIVE → REVOKED + 释放 guard_key + 落吊销留痕（同事务）。 */
    void revoke(long identityId, String subjectNo, String did, String operator, String reason,
                LocalDateTime occurredAt);
}
