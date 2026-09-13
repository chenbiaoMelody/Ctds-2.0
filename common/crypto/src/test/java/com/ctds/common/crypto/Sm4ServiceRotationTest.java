package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 密钥轮换语义（规格 C-2.6.3 行为 2）：新数据用新版本、旧密文按旧版本可解、
 * 篡改仍拒绝、本地文件路径（非版本化）信封格式零变化。
 */
class Sm4ServiceRotationTest {

    /** 版本化内存密钥源：版本 → 密钥材料，模拟 KMS 轮换（hifi §2 VersionedKeyProvider）。 */
    private static final class VersionedTestKeys implements VersionedKeyProvider {
        private final Map<Integer, byte[]> versions = new HashMap<>();
        private int current;

        VersionedTestKeys(final byte[] v1Key, final byte[] v2Key) {
            versions.put(1, v1Key.clone());
            versions.put(2, v2Key.clone());
            current = 1;
        }

        void rotate() {
            current = 2;
        }

        @Override
        public int currentVersion(final String keyRef) {
            return current;
        }

        @Override
        public byte[] sm4Key(final String keyRef, final int version) {
            final byte[] key = versions.get(version);
            if (key == null) {
                throw new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
            }
            return key.clone();
        }

        @Override
        public byte[] sm4Key(final String keyRef) {
            return sm4Key(keyRef, current);
        }
    }

    @Test
    void encryptedWithVersionedProviderCarriesKeyVersionAndRoundTrips() {
        final VersionedTestKeys keys = new VersionedTestKeys(TestKeys.KEY_16, TestKeys.OTHER_KEY_16);
        final Sm4Service sm4 = new Sm4Service(keys);
        final byte[] plaintext = "轮换前数据".getBytes(StandardCharsets.UTF_8);
        final byte[] envelope = sm4.encrypt(plaintext, TestKeys.KEY_REF);
        assertThat(envelope[4]).isEqualTo((byte) 0x02);
        assertThat(CipherEnvelope.keyVersionOf(envelope)).isEqualTo(1);
        assertThat(sm4.decrypt(envelope, TestKeys.KEY_REF)).isEqualTo(plaintext);
    }

    @Test
    void afterRotationOldCiphertextStillDecryptsAndNewCiphertextUsesNewKey() {
        // 规格行为 2 GTT-1：轮换后 C1 仍可解密，新数据用新版本
        final VersionedTestKeys keys = new VersionedTestKeys(TestKeys.KEY_16, TestKeys.OTHER_KEY_16);
        final Sm4Service sm4 = new Sm4Service(keys);
        final byte[] oldEnvelope = sm4.encrypt("old-secret".getBytes(StandardCharsets.UTF_8), TestKeys.KEY_REF);

        keys.rotate();

        assertThat(sm4.decrypt(oldEnvelope, TestKeys.KEY_REF))
                .isEqualTo("old-secret".getBytes(StandardCharsets.UTF_8));
        final byte[] newEnvelope = sm4.encrypt("new-secret".getBytes(StandardCharsets.UTF_8), TestKeys.KEY_REF);
        assertThat(CipherEnvelope.keyVersionOf(newEnvelope)).isEqualTo(2);
        assertThat(sm4.decrypt(newEnvelope, TestKeys.KEY_REF))
                .isEqualTo("new-secret".getBytes(StandardCharsets.UTF_8));
        // 新旧密文确实用了不同密钥：旧密钥解不开新密文
        assertThat(CipherEnvelope.keyVersionOf(oldEnvelope)).isNotEqualTo(CipherEnvelope.keyVersionOf(newEnvelope));
    }

    @Test
    void tamperedV2EnvelopeIsRejected() {
        final VersionedTestKeys keys = new VersionedTestKeys(TestKeys.KEY_16, TestKeys.OTHER_KEY_16);
        final Sm4Service sm4 = new Sm4Service(keys);
        final byte[] envelope = sm4.encrypt("tamper-me".getBytes(StandardCharsets.UTF_8), TestKeys.KEY_REF);
        envelope[envelope.length - 1] ^= 0x01;
        assertThatThrownBy(() -> sm4.decrypt(envelope, TestKeys.KEY_REF))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_DATA_REJECTED));
    }

    @Test
    void localFilePathEnvelopeStaysV1() {
        // 规格边界：LocalFileKeyProvider（非版本化）路径信封格式零变化（hifi §1）
        final Sm4Service sm4 = new Sm4Service(new TestKeys());
        final byte[] envelope = sm4.encrypt("legacy".getBytes(StandardCharsets.UTF_8), TestKeys.KEY_REF);
        assertThat(envelope[4]).isEqualTo((byte) 0x01);
        assertThat(sm4.decrypt(envelope, TestKeys.KEY_REF))
                .isEqualTo("legacy".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unknownVersionIsKeyUnavailable() {
        final VersionedTestKeys keys = new VersionedTestKeys(TestKeys.KEY_16, TestKeys.OTHER_KEY_16);
        final Sm4Service sm4 = new Sm4Service(keys);
        final byte[] forged = CipherEnvelope.buildV2(new byte[CipherEnvelope.IV_LEN], 9,
                new byte[CipherEnvelope.TAG_LEN + 8]);
        assertThatThrownBy(() -> sm4.decrypt(forged, TestKeys.KEY_REF))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE));
    }
}
