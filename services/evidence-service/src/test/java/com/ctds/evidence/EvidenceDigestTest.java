package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceOperationType;
import com.ctds.evidence.domain.EvidenceRules;
import com.ctds.evidence.domain.EvidenceStageType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    /**
     * DB-118：入参事件时点统一截断到毫秒——摘要输入（.SSS）／落库（datetime(3)）／出参同一口径，
     * 亚毫秒精度不得因四舍五入造成"未篡改却报不一致"（缺陷卡行为清单 1~5）。
     */
    @Test
    void occurredAtIsTruncatedToMillisBeforeDigestAndStorage() {
        final Clock clock = Clock.fixed(Instant.parse("2026-11-05T13:30:00Z"), ZoneOffset.UTC);

        final Instant micros = EvidenceRules.occurredAt("2026-11-05T21:02:42.866512+08:00", clock);
        assertThat(micros).as("行为 1：亚毫秒截断为 .866（非四舍五入）")
                .isEqualTo(Instant.parse("2026-11-05T13:02:42.866Z"));
        assertThat(EvidenceDigests.occurredAtIso(micros)).as("行为 1：摘要 / 出参形态 .SSS")
                .isEqualTo("2026-11-05T13:02:42.866Z");
        assertThat(EvidenceRules.occurredAt("2026-11-05T21:02:42.999999+08:00", clock))
                .as("行为 3：.999999 截断为 .999，不向下一秒进位")
                .isEqualTo(Instant.parse("2026-11-05T13:02:42.999Z"));
        assertThat(EvidenceRules.occurredAt("2026-11-05T21:02:42.000499+08:00", clock))
                .as("行为 4：.000499 截断为 .000")
                .isEqualTo(Instant.parse("2026-11-05T13:02:42.000Z"));
        assertThat(EvidenceRules.occurredAt("2026-11-05T21:02:42.866+08:00", clock))
                .as("行为 2：整毫秒输入行为不变")
                .isEqualTo(Instant.parse("2026-11-05T13:02:42.866Z"));

        assertThat(digest(BUSINESS_NO, micros)).as("截断后摘要 = 按 .866 复算的摘要")
                .isEqualTo(digest(BUSINESS_NO, Instant.parse("2026-11-05T13:02:42.866Z")));
        assertThat(digest(BUSINESS_NO, micros))
                .as("库中按四舍五入存 .867 会得到不同摘要（缺陷复算证据）")
                .isNotEqualTo(digest(BUSINESS_NO, Instant.parse("2026-11-05T13:02:42.867Z")));

        assertThatThrownBy(() -> EvidenceRules.occurredAt("2026-11-05T21:35:00.0004+08:00", clock))
                .as("行为 5：守卫先于截断判定（now+5min+0.4ms 仍拒绝；若先截断则被放行）")
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().value())
                        .isEqualTo("1012C0002"));
        assertThatThrownBy(() -> EvidenceRules.occurredAt("2019-12-31T23:59:59.999999Z", clock))
                .as("行为 5：早于允许下限仍拒绝（亚毫秒边界）")
                .isInstanceOf(BizException.class);
    }
}
