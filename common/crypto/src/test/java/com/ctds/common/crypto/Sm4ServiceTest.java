package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * B1/B2/B3 + 边界表（hifi"边界值与异常行为"逐行钉死）：
 * 往返一致、随机 IV、信封格式、篡改/截断/错密钥拒绝、非我方格式区分、64MiB 上限、并发安全。
 */
class Sm4ServiceTest {

    private final Sm4Service service = new Sm4Service(new TestKeys());

    private static byte[] plain() {
        return "城市数据空间·敏感备注-001".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripBytesAndText() {
        final byte[] envelope = service.encrypt(plain(), TestKeys.KEY_REF);
        assertThat(service.decrypt(envelope, TestKeys.KEY_REF)).isEqualTo(plain());

        final String text = "数据要素×智能 001";
        final String b64 = service.encryptText(text, TestKeys.KEY_REF);
        assertThat(service.decryptText(b64, TestKeys.KEY_REF)).isEqualTo(text);
    }

    @Test
    void samePlaintextProducesDifferentEnvelopesAndBothDecrypt() {
        final byte[] a = service.encrypt(plain(), TestKeys.KEY_REF);
        final byte[] b = service.encrypt(plain(), TestKeys.KEY_REF);
        assertThat(a).isNotEqualTo(b);
        assertThat(service.decrypt(a, TestKeys.KEY_REF)).isEqualTo(service.decrypt(b, TestKeys.KEY_REF));
    }

    @Test
    void envelopeFormatPinned() {
        final byte[] plaintext = plain();
        final byte[] envelope = service.encrypt(plaintext, TestKeys.KEY_REF);
        assertThat(new String(envelope, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("CTSE");
        assertThat(envelope[4]).isEqualTo(CipherEnvelope.VERSION_V1);
        // 评审④P3-3：用硬编码 33（=魔数4+版本1+IV12+标签16）独立钉桩，不依赖生产常量同源
        assertThat(envelope).hasSize(33 + plaintext.length);
        // IV 段（5..16）不得全零（随机 IV 生效）
        boolean ivHasNonZero = false;
        for (int i = 5; i < 17; i++) {
            if (envelope[i] != 0) {
                ivHasNonZero = true;
                break;
            }
        }
        assertThat(ivHasNonZero).isTrue();
    }

    @Test
    void tamperedTruncatedAndWrongKeyAllRejected() {
        final byte[] envelope = service.encrypt(plain(), TestKeys.KEY_REF);

        final byte[] flipped = envelope.clone();
        flipped[envelope.length - 1] ^= 0x01;
        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(flipped, TestKeys.KEY_REF));

        final byte[] middle = envelope.clone();
        middle[20] ^= 0x40;
        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(middle, TestKeys.KEY_REF));

        final byte[] truncated = Arrays.copyOf(envelope, envelope.length - 5);
        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(truncated, TestKeys.KEY_REF));

        assertCode(CryptoErrorCodes.CRYPTO_DATA_REJECTED, () -> service.decrypt(envelope, "other"));
    }

    @Test
    void foreignFormatYieldsInputInvalidNotRejected() {
        final byte[] foreign = new byte[CipherEnvelope.MIN_LEN + 3];
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.decrypt(foreign, TestKeys.KEY_REF));

        final byte[] badMagic = service.encrypt(plain(), TestKeys.KEY_REF);
        badMagic[0] = (byte) 'X';
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.decrypt(badMagic, TestKeys.KEY_REF));

        // 未知版本字节（0x02 自 2.6.3 起为合法 v2 信封，见 CipherEnvelopeV2Test）
        final byte[] badVersion = service.encrypt(plain(), TestKeys.KEY_REF);
        badVersion[4] = 0x7F;
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.decrypt(badVersion, TestKeys.KEY_REF));

        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.decrypt(new byte[32], TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.decryptText("not-base64-!!", TestKeys.KEY_REF));
    }

    @Test
    void boundaryInputsRejected() {
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encrypt(null, TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encrypt(new byte[0], TestKeys.KEY_REF));
        assertThatThrownBy(() -> Inputs.requirePlaintext(new byte[Inputs.MAX_INPUT_BYTES + 1]))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CryptoErrorCodes.CRYPTO_INPUT_INVALID));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encrypt(plain(), null));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encrypt(plain(), "  "));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encrypt(plain(), "k".repeat(65)));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encryptText("", TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.decryptText("", TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, () -> service.encrypt(plain(), "missing-ref"));
    }

    @Test
    void stringEntryRejectsOversizedTextBeforeDecoding() {
        // 评审②P2-1/④P2-2：长度校验必须先于 Base64 解码/转字节（防内存打爆旁路），含多字节文本
        final String huge = "A".repeat(Inputs.MAX_INPUT_BYTES / 3 + 1);
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encryptText(huge, TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.decryptText(huge, TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID,
                () -> service.encryptText("正常文本", "非法 keyRef#"));
        // 多字节字符（UTF-8 3 字节/字符）同样在转字节分配前被拒（评审④P2-2）
        final String cjkHuge = "城".repeat(Inputs.MAX_INPUT_BYTES / 3 + 1);
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.encryptText(cjkHuge, TestKeys.KEY_REF));
        assertCode(CryptoErrorCodes.CRYPTO_INPUT_INVALID, () -> service.decryptText(cjkHuge, TestKeys.KEY_REF));
    }

    @Test
    @Timeout(60)
    void roundTripAtExactMaxBoundary() {
        // 评审①P2-1 修复验证：明文恰为上限时信封长 = 64MiB+33，解密上界必须按信封口径放宽
        final byte[] maxPlain = new byte[Inputs.MAX_INPUT_BYTES];
        new java.util.Random(42).nextBytes(maxPlain);
        final byte[] envelope = service.encrypt(maxPlain, TestKeys.KEY_REF);
        assertThat(service.decrypt(envelope, TestKeys.KEY_REF)).isEqualTo(maxPlain);
    }

    @Test
    @Timeout(30)
    void concurrentRoundTripsAreIsolated() throws Exception {
        final int threads = 8;
        final int perThread = 50;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        final String text = "note-" + id + "-" + i + "-并发";
                        final String env = service.encryptText(text, TestKeys.KEY_REF);
                        if (!text.equals(service.decryptText(env, TestKeys.KEY_REF))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (final Future<Boolean> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertCode(final com.ctds.common.errorcode.ErrorCode code, final Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            // 评审④P1-1：拒绝用例必须同时断言"不含敏感内容"——文案必须是服务端常量之一
            // （常量 = 不含任何明文/密文/密钥内容，回显输入即与常量不等 → 测试红）
            assertThat(e.getMessage()).isIn("加解密输入不合法", "数据校验未通过，已拒绝",
                    "密钥服务暂不可用", "加解密操作失败");
        });
    }
}
