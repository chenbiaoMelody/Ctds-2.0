package com.ctds.evidence.infrastructure;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.evidence.domain.ChainEntry;
import com.ctds.evidence.domain.ChainPayload;
import com.ctds.evidence.domain.ChainReceipt;
import com.ctds.evidence.domain.EvidenceChainUnavailableException;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 本地不可变账本适配器（Q1-A 默认实现；hifi §6）：同库写 {@code evidence_chain_block}，
 * append-only + 前序哈希链 + {@code uk_chain_payload} 幂等（崩溃窗口重放返回原回执）。
 *
 * <p>结构约束：本类只有 INSERT 与按 txId / 载荷摘要查询，没有任何改写或清除语句
 * （T9 源码断言）。真实 FISCO BCOS 适配器为条件项，未就绪不写假实现。</p>
 */
@Component
public class LocalLedgerEvidenceGateway implements EvidenceGateway {

    private static final int MAX_WRITE_ATTEMPTS = 3;
    private static final String SELECT_COLUMNS =
            "chain_tx_id, block_no, payload_digest, payload_json, created_at";

    private final JdbcClient jdbc;
    private final Sm3Service sm3;
    private final Clock clock;
    private final EvidenceChainProperties properties;

    public LocalLedgerEvidenceGateway(final JdbcClient jdbc, final Sm3Service sm3,
            final Clock clock, final EvidenceChainProperties properties) {
        if (!"local".equals(properties.getProvider())) {
            throw new IllegalStateException("不支持的存证网关 provider: " + properties.getProvider()
                    + "（本卡仅支持 local；真实链适配器须先过依赖审批，禁止静默降级）");
        }
        this.jdbc = jdbc;
        this.sm3 = sm3;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public ChainReceipt submit(final ChainPayload payload) {
        if (!properties.isEnabled()) {
            throw new EvidenceChainUnavailableException("存证链已关闭（演示构造或部署未启用）");
        }
        final Optional<ChainReceipt> replayed = findByPayloadDigest(payload.payloadDigest());
        if (replayed.isPresent()) {
            return replayed.get();
        }
        for (int attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                return append(payload);
            } catch (final DuplicateKeyException e) {
                final Optional<ChainReceipt> existing = findByPayloadDigest(payload.payloadDigest());
                if (existing.isPresent()) {
                    return existing.get();
                }
                // 块号竞争：重新计算块号与前序哈希后重试
            }
        }
        throw new EvidenceChainUnavailableException("账本写入竞争重试耗尽");
    }

    @Override
    public Optional<ChainEntry> findByTxId(final String chainTxId) {
        if (!properties.isEnabled()) {
            throw new EvidenceChainUnavailableException("存证链已关闭，无法读取链上记录");
        }
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM evidence_chain_block WHERE chain_tx_id = ?")
                .param(chainTxId)
                .query((rs, rowNum) -> new ChainEntry(
                        rs.getString("chain_tx_id"),
                        rs.getLong("block_no"),
                        rs.getString("payload_digest"),
                        rs.getString("payload_json"),
                        toInstant(rs.getObject("created_at", LocalDateTime.class))))
                .optional();
    }

    private ChainReceipt append(final ChainPayload payload) {
        final long blockNo = nextBlockNo();
        final String prevHash = previousBlockHash();
        final Instant createdAt = clock.instant();
        final String blockHash = EvidenceDigests.blockHash(sm3, blockNo, payload.payloadDigest(),
                payload.payloadJson(), prevHash, createdAt);
        jdbc.sql("INSERT INTO evidence_chain_block (block_no, chain_tx_id, payload_digest, "
                        + "payload_json, prev_block_hash, block_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(blockNo, EvidenceDigests.chainTxId(blockNo), payload.payloadDigest(),
                        payload.payloadJson(), prevHash, blockHash, toLocal(createdAt))
                .update();
        return new ChainReceipt(EvidenceDigests.chainTxId(blockNo), blockNo);
    }

    private Optional<ChainReceipt> findByPayloadDigest(final String payloadDigest) {
        return jdbc.sql("SELECT chain_tx_id, block_no FROM evidence_chain_block WHERE payload_digest = ?")
                .param(payloadDigest)
                .query((rs, rowNum) -> new ChainReceipt(
                        rs.getString("chain_tx_id"), rs.getLong("block_no")))
                .optional();
    }

    private long nextBlockNo() {
        final List<Long> values = jdbc.sql(
                        "SELECT COALESCE(MAX(block_no), 0) + 1 FROM evidence_chain_block")
                .query(Long.class).list();
        return (values.isEmpty() || values.get(0) == null) ? 1L : values.get(0);
    }

    private String previousBlockHash() {
        return jdbc.sql("SELECT block_hash FROM evidence_chain_block ORDER BY block_no DESC LIMIT 1")
                .query(String.class)
                .optional()
                .orElse(EvidenceDigests.GENESIS_PREV_HASH);
    }

    private static LocalDateTime toLocal(final Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(final LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
