package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 信封格式 v2（WBS-2.6.3 hifi §1，密钥轮换载体；ADR-006 版本位预留的兑现）：
 * v2 布局逐字节断言 + v1 既有布局回归 + 非法版本拒绝。
 */
class CipherEnvelopeV2Test {

    private static final byte[] IV_12 = new byte[CipherEnvelope.IV_LEN];

    @Test
    void v2LayoutIsMagicVersionKeyVersionIvBodyTag() {
        final byte[] body = new byte[20];
        final byte[] envelope = CipherEnvelope.buildV2(IV_12, 7, body);
        assertThat(envelope.length).isEqualTo(CipherEnvelope.HEADER_LEN_V2 + body.length);
        assertThat(new String(envelope, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("CTSE");
        assertThat(envelope[4]).isEqualTo((byte) 0x02);
        // 4 字节大端密钥版本号
        assertThat(envelope[5]).isZero();
        assertThat(envelope[6]).isZero();
        assertThat(envelope[7]).isZero();
        assertThat(envelope[8]).isEqualTo((byte) 7);
        assertThat(CipherEnvelope.keyVersionOf(envelope)).isEqualTo(7);
        assertThat(CipherEnvelope.headerValid(envelope)).isTrue();
        assertThat(CipherEnvelope.headerLenOf(envelope)).isEqualTo(CipherEnvelope.HEADER_LEN_V2);
    }

    @Test
    void v2RejectsTruncatedEnvelope() {
        final byte[] envelope = CipherEnvelope.buildV2(IV_12, 1, new byte[4]);
        assertThat(CipherEnvelope.headerValid(java.util.Arrays.copyOf(envelope, CipherEnvelope.MIN_LEN_V2 - 1)))
                .isFalse();
    }

    @Test
    void v1LayoutUnchanged() {
        final byte[] body = new byte[20];
        final byte[] envelope = CipherEnvelope.build(IV_12, body);
        assertThat(envelope[4]).isEqualTo((byte) 0x01);
        assertThat(envelope.length).isEqualTo(CipherEnvelope.HEADER_LEN + body.length);
        assertThat(CipherEnvelope.headerValid(envelope)).isTrue();
        assertThat(CipherEnvelope.headerLenOf(envelope)).isEqualTo(CipherEnvelope.HEADER_LEN);
        assertThat(CipherEnvelope.keyVersionOf(envelope)).isEqualTo(-1);
    }

    @Test
    void unknownVersionByteIsInvalid() {
        final byte[] envelope = CipherEnvelope.build(IV_12, new byte[20]);
        envelope[4] = 0x03;
        assertThat(CipherEnvelope.headerValid(envelope)).isFalse();
        final byte[] shortV2 = new byte[10];
        shortV2[4] = 0x02;
        assertThat(CipherEnvelope.headerValid(shortV2)).isFalse();
    }

    @Test
    void hugeKeyVersionRoundTripsThroughFourBytes() {
        final byte[] envelope = CipherEnvelope.buildV2(IV_12, 42, new byte[4]);
        assertThat(CipherEnvelope.keyVersionOf(envelope)).isEqualTo(42);
        final int big = 300;
        assertThat(CipherEnvelope.keyVersionOf(CipherEnvelope.buildV2(IV_12, big, new byte[4]))).isEqualTo(big);
    }

    @Test
    void v2EnvelopeWithNonVersionedProviderIsKeyUnavailable() {
        // v2 信封只能由版本化密钥源解（hifi §1：本地文件实现不产 v2 也不解 v2）
        final Sm4Service sm4 = new Sm4Service(new TestKeys());
        final byte[] v2 = CipherEnvelope.buildV2(IV_12, 1, new byte[20]);
        assertThatThrownBy(() -> sm4.decrypt(v2, TestKeys.KEY_REF))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }
}
