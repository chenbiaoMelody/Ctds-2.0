package com.ctds.evidence.domain;

import java.time.Instant;

/**
 * 账本条目（核验读面：交易标识 / 块号 / 载荷摘要 / 载荷 / 上链时点）。
 */
public record ChainEntry(String chainTxId, long blockNo, String payloadDigest,
        String payloadJson, Instant createdAt) {
}
