package com.ctds.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * B8 合规性算法正确性测试（hifi 引文核对义务的执行与留痕）：
 *
 * 【数值来源声明——2026-09-08 本编码会话】
 * 本机网络无法访问标准原文（GB/T 文档站与代码托管站均不可达），为防"凭记忆臆造密码学数值"，
 * 采用三重独立实现交叉计算并全部一致后锁定：
 *   ① OpenSSL 3.5.5（C 实现，WSL 命令行）
 *   ② BouncyCastle 1.85.2（Java 实现，SMCheck 独立计算）
 *   ③ Python gmssl（纯 Python 实现，venv 独立计算）
 * 单块示例与 ①②③ 及公开广泛记载的 GB/T 32907 例1 / GB/T 32905 例1、例2 一致；
 * 1e6 迭代链值以三实现一致计算值为准（AI 记忆值与计算值不符时，信计算不信记忆——教训登记日志）。
 * 【遗留义务】标准原文逐字核对由 4.3.3（国密合规验证）补齐；SM2 固定 k 标准示例向量同因原文不可达
 * 未凭记忆写入，SM2 正确性以 B4/B5 双向用例 + C1C3C2/DER 结构断言 + 外部测评兜底。
 */
class ComplianceVectorTest {

    private static final byte[] GB_KEY = hex("0123456789abcdeffedcba9876543210");

    @Test
    void sm3MatchesGbt32905Examples() {
        // GB/T 32905-2016 附录A 示例1：消息 "abc"
        assertThat(new Sm3Service().digestHex("abc"))
                .isEqualTo("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0");
        // 示例2：64 字节消息（"abcd"×16）
        assertThat(new Sm3Service().digestHex("abcd".repeat(16)))
                .isEqualTo("debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732");
    }

    @Test
    void sm3DigestOfEmptyInputIsDefinedAndStable() {
        // 空输入行为为本组件定义的输入契约（B6 任意字节语义），非 GB/T 32905 附录公开示例；
        // 钉"有定义且稳定"，不宣称标准出处（评审①P3-1 注释纠正）
        final SM3Digest digest = new SM3Digest();
        final byte[] out = new byte[32];
        digest.doFinal(out, 0);
        assertThat(toHex(out)).hasSize(64);
        assertThat(new Sm3Service().digestHex(new byte[0])).isEqualTo(toHex(out));
    }

    @Test
    void sm4SingleBlockMatchesGbt32907Example1() {
        final byte[] cipher = sm4EncryptBlock(GB_KEY, GB_KEY);
        // GB/T 32907-2016 附录A 示例1：密钥=明文=0123456789abcdef fedcba9876543210
        assertThat(toHex(cipher)).isEqualTo("681edf34d206965e86b3e94f536e4246");
    }

    @Test
    @Timeout(120)
    void sm4OneMillionIterationChainMatchesComputedValue() {
        // GB/T 32907-2016 附录A 示例2：X(i+1)=ENCRYPT(X(i))，X(0)=明文，迭代 1,000,000 次
        byte[] x = GB_KEY.clone();
        for (int i = 0; i < 1_000_000; i++) {
            x = sm4EncryptBlock(GB_KEY, x);
        }
        assertThat(toHex(x)).isEqualTo("595298c7c6fd271f0402f804c33d3f66");
    }

    private static byte[] sm4EncryptBlock(final byte[] key, final byte[] block) {
        final SM4Engine engine = new SM4Engine();
        engine.init(true, new KeyParameter(key));
        final byte[] out = new byte[16];
        engine.processBlock(block, 0, out, 0);
        return out;
    }

    private static byte[] hex(final String s) {
        final byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static String toHex(final byte[] b) {
        final StringBuilder s = new StringBuilder();
        for (final byte v : b) {
            s.append(String.format("%02x", v));
        }
        return s.toString();
    }

    @Test
    void sm4ComponentSelfConsistencyCheck() {
        // 评审④P3-1：仅钉组件自洽（密文可读回），非算法合规证据——合规正确性由上列标准向量用例提供
        final Sm4Service sm4 = new Sm4Service(new TestKeys());
        final byte[] envelope = sm4.encrypt(GB_KEY, TestKeys.KEY_REF);
        assertThat(sm4.decrypt(envelope, TestKeys.KEY_REF)).isEqualTo(GB_KEY.clone());
    }
}
