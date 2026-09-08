package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * B4/B5（SM2）：密钥对格式、C1C3C2 结构、加解密与签名验签的"生效+绕过被拒"双向用例、
 * 密钥材料脱敏 toString（红线 B10 组件侧）。
 */
class Sm2ServiceTest {

    private final Sm2Service service = new Sm2Service();

    private static byte[] data() {
        return "合约编号 HT-2026-0001 摘要数据".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keyPairFormatAndFreshness() {
        final Sm2KeyPair a = service.generateKeyPair();
        final Sm2KeyPair b = service.generateKeyPair();
        assertThat(a.publicKeyHex()).hasSize(130).startsWith("04").matches("[0-9a-f]{130}");
        assertThat(a.privateKeyHex()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a.publicKeyHex()).isNotEqualTo(b.publicKeyHex());
    }

    @Test
    void keyPairToStringNeverLeaksKeyMaterial() {
        final Sm2KeyPair pair = service.generateKeyPair();
        final String printed = pair.toString();
        assertThat(printed).doesNotContain(pair.privateKeyHex()).doesNotContain(pair.publicKeyHex());
    }

    @Test
    void encryptDecryptRoundTripAndC1C3C2Structure() {
        final Sm2KeyPair pair = service.generateKeyPair();
        final byte[] cipher = service.encrypt(data(), pair.publicKeyHex());
        assertThat(cipher[0]).isEqualTo((byte) 0x04);
        // C1(65) + C3(32) + C2(=明文长)
        assertThat(cipher).hasSize(65 + 32 + data().length);
        assertThat(service.decrypt(cipher, pair.privateKeyHex())).isEqualTo(data());
    }

    @Test
    void decryptRejectsTamperingAndForeignKeys() {
        final Sm2KeyPair pair = service.generateKeyPair();
        final Sm2KeyPair other = service.generateKeyPair();
        final byte[] cipher = service.encrypt(data(), pair.publicKeyHex());

        final byte[] flipped = cipher.clone();
        flipped[cipher.length - 3] ^= 0x08;
        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(flipped, pair.privateKeyHex()));

        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(cipher, other.privateKeyHex()));

        final byte[] truncated = Arrays.copyOf(cipher, 40);
        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(truncated, pair.privateKeyHex()));
    }

    @Test
    void malformedKeysAreInputInvalid() {
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.encrypt(data(), "04zz".repeat(32)));
        // 评审④P3-2：130 字符但含非 hex 字符（走"解码失败"路径，而非长度路径）
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.encrypt(data(), "04" + "zz".repeat(64)));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.encrypt(data(), "03" + "aa".repeat(64)));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.decrypt(new byte[CipherEnvelope.MIN_LEN + 1], "abc"));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.sign(data(), "0".repeat(64)));
    }

    @Test
    void signAndVerifyTrueThenFalseOnAllTamperPaths() {
        final Sm2KeyPair pair = service.generateKeyPair();
        final Sm2KeyPair other = service.generateKeyPair();
        final byte[] sig = service.sign(data(), pair.privateKeyHex());
        assertThat(sig[0]).isEqualTo((byte) 0x30); // DER SEQUENCE
        assertThat(service.verify(data(), sig, pair.publicKeyHex())).isTrue();

        final byte[] tamperedData = data().clone();
        tamperedData[0] ^= 0x01;
        assertThat(service.verify(tamperedData, sig, pair.publicKeyHex())).isFalse();

        final byte[] tamperedSig = sig.clone();
        tamperedSig[tamperedSig.length - 1] ^= 0x01;
        assertThat(service.verify(data(), tamperedSig, pair.publicKeyHex())).isFalse();

        assertThat(service.verify(data(), sig, other.publicKeyHex())).isFalse();
    }

    @Test
    void verifyRejectsNullAndEmptyInputs() {
        final Sm2KeyPair pair = service.generateKeyPair();
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.verify(null, new byte[]{1}, pair.publicKeyHex()));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.verify(data(), new byte[0], pair.publicKeyHex()));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.verify(data(), null, pair.publicKeyHex()));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.encrypt(new byte[0], pair.publicKeyHex()));
    }

    @Test
    void garbageSignatureReturnsFalseNotException() {
        // 评审④P3-5：任意垃圾字节验签 → false（非异常），且不影响后续正常验签
        final Sm2KeyPair pair = service.generateKeyPair();
        final byte[] garbage = "garbage-not-der".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(service.verify(data(), garbage, pair.publicKeyHex())).isFalse();
        final byte[] sig = service.sign(data(), pair.privateKeyHex());
        assertThat(service.verify(data(), sig, pair.publicKeyHex())).isTrue();
    }

    private static void assertCode(final com.ctds.common.errorcode.ErrorCode code, final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            // 评审④P1-1：拒绝用例必须同时断言"不含敏感内容"——文案必须是服务端常量之一
            assertThat(e.getMessage()).isIn("加解密输入不合法", "数据校验未通过，已拒绝",
                    "密钥服务暂不可用", "加解密操作失败");
        });
    }
}
