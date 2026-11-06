package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceOperationType;
import com.ctds.evidence.domain.EvidenceStageType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * T3 摘要可复算（规格 B1-1 / §2 术语"内容摘要"）：
 * 同输入同摘要、任一字段变化摘要必变（双向）、规范化串与上链载荷逐字可复算。
 */
class EvidenceDigestTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-05T05:30:00Z");
    private static final String BUSINESS_NO = "DL000031";
    private static final String DID = "did:ctds:provider-0001";

    private final Sm3Service sm3 = new Sm3Service();

    private String digest(final String businessNo, final Instant occurredAt) {
        final String didDigest = EvidenceDigests.subjectDidDigest(sm3, DID);
        return EvidenceDigests.contentDigest(sm3, EvidenceStageType.DELIVERY, businessNo,
                EvidenceOperationType.DELIVERY_CREATED, occurredAt, didDigest);
    }

    @Test
    void sameInputYieldsSameDigest() {
        assertThat(digest(BUSINESS_NO, OCCURRED_AT))
                .as("同输入同摘要（第三方可独立复算）")
                .isEqualTo(digest(BUSINESS_NO, OCCURRED_AT))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void everyFieldChangeChangesDigest() {
        final String baseline = digest(BUSINESS_NO, OCCURRED_AT);
        assertThat(digest("DL000032", OCCURRED_AT)).as("业务单据号变化").isNotEqualTo(baseline);
        assertThat(digest(BUSINESS_NO, OCCURRED_AT.plusMillis(1))).as("事件时点变化").isNotEqualTo(baseline);

        final String didDigest = EvidenceDigests.subjectDidDigest(sm3, DID);
        assertThat(EvidenceDigests.contentDigest(sm3, EvidenceStageType.USAGE, BUSINESS_NO,
                EvidenceOperationType.USAGE_ALLOWED, OCCURRED_AT, didDigest))
                .as("环节与操作类型变化").isNotEqualTo(baseline);
        assertThat(EvidenceDigests.subjectDidDigest(sm3, DID + "x"))
                .as("主体 DID 变化").isNotEqualTo(didDigest);
    }

    @Test
    void canonicalInputIsFixedOrderAndUtcMillis() {
        final String didDigest = EvidenceDigests.subjectDidDigest(sm3, DID);
        assertThat(EvidenceDigests.contentDigestInput(EvidenceStageType.DELIVERY, BUSINESS_NO,
                EvidenceOperationType.DELIVERY_CREATED, OCCURRED_AT, didDigest))
                .isEqualTo("CTDS-EV-1|DELIVERY|DL000031|DELIVERY_CREATED|2026-11-05T05:30:00.000Z|"
                        + didDigest);
        assertThat(EvidenceDigests.occurredAtIso(OCCURRED_AT))
                .as("UTC 固定三位毫秒形态").isEqualTo("2026-11-05T05:30:00.000Z");
    }

    @Test
    void payloadHasFiveFixedKeysWithoutWhitespace() {
        final String didDigest = EvidenceDigests.subjectDidDigest(sm3, DID);
        final String contentDigest = EvidenceDigests.contentDigest(sm3, EvidenceStageType.DELIVERY,
                BUSINESS_NO, EvidenceOperationType.DELIVERY_CREATED, OCCURRED_AT, didDigest);
        assertThat(EvidenceDigests.payloadJson(BUSINESS_NO, OCCURRED_AT,
                EvidenceOperationType.DELIVERY_CREATED, didDigest, contentDigest))
                .isEqualTo("{\"businessNo\":\"DL000031\",\"occurredAt\":\"2026-11-05T05:30:00.000Z\","
                        + "\"operationType\":\"DELIVERY_CREATED\",\"subjectDidDigest\":\"" + didDigest
                        + "\",\"contentDigest\":\"" + contentDigest + "\"}");
    }

    @Test
    void chainTxIdAndGenesisBlockFollowLedgerContract() {
        assertThat(EvidenceDigests.chainTxId(1L)).isEqualTo("LTX000000000001");
        assertThat(EvidenceDigests.chainTxId(123456789012L)).isEqualTo("LTX123456789012");
        assertThat(EvidenceDigests.GENESIS_PREV_HASH).isEqualTo("0".repeat(64));

        final String blockHash = EvidenceDigests.blockHash(sm3, 1L, "a".repeat(64),
                "{\"k\":\"v\"}", EvidenceDigests.GENESIS_PREV_HASH, OCCURRED_AT);
        assertThat(blockHash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(EvidenceDigests.blockHash(sm3, 2L, "a".repeat(64), "{\"k\":\"v\"}",
                EvidenceDigests.GENESIS_PREV_HASH, OCCURRED_AT))
                .as("块号参与哈希，同载荷不同块哈希不同").isNotEqualTo(blockHash);
    }
}
