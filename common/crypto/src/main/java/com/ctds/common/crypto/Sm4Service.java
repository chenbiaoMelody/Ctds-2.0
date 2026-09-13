package com.ctds.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * SM4 对称加解密统一入口（hifi B1-B3，Q5 确认）。
 * SM4/GCM/NoPadding（AEAD 自带完整性标记，篡改必拒）；每次加密 12 字节随机 IV → 同明文密文互异。
 * 直接调用 BouncyCastle 轻量 API，不注册全局 JCE Provider（零全局副作用，hifi B10）。
 * 无状态：每次运算现场构造引擎，实例可安全共享多线程。
 */
public class Sm4Service {

    private static final int MAC_BITS = CipherEnvelope.TAG_LEN * 8;

    private final KeyProvider keyProvider;
    private final SecureRandom random = new SecureRandom();

    public Sm4Service(final KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /** 明文 → 密文信封（B1；版本化密钥源产出 v2 信封并写入当前密钥版本，本地路径产出 v1 不变）。 */
    public byte[] encrypt(final byte[] plaintext, final String keyRef) {
        Inputs.requirePlaintext(plaintext);
        Inputs.requireKeyRef(keyRef);
        final byte[] iv = new byte[CipherEnvelope.IV_LEN];
        random.nextBytes(iv);
        // 取密钥在 try 外：密钥源不可用须保持 1001S0001 原语义，不得被收敛为 1001S0002
        final Integer keyVersion;
        final byte[] key;
        if (keyProvider instanceof VersionedKeyProvider versioned) {
            keyVersion = versioned.currentVersion(keyRef);
            key = versioned.sm4Key(keyRef, keyVersion);
        } else {
            keyVersion = null;
            key = keyProvider.sm4Key(keyRef);
        }
        try {
            final GCMModeCipher cipher = GCMBlockCipher.newInstance(new SM4Engine());
            cipher.init(true, new AEADParameters(new KeyParameter(key), MAC_BITS, iv, null));
            final byte[] body = new byte[cipher.getOutputSize(plaintext.length)];
            final int processed = cipher.processBytes(plaintext, 0, plaintext.length, body, 0);
            final int total = processed + cipher.doFinal(body, processed);
            return keyVersion == null
                    ? CipherEnvelope.build(iv, Arrays.copyOf(body, total))
                    : CipherEnvelope.buildV2(iv, keyVersion, Arrays.copyOf(body, total));
        } catch (final InvalidCipherTextException | RuntimeException e) {
            // 评审①P3-4：加密侧输入已校验、无"结构坏"语义，任何异常均属未预期 → 收敛 1001S0002
            throw Inputs.operationFailed(e);
        }
    }

    /** 密文信封 → 明文（B2/B3；v1 走当前密钥、v2 按信封内版本取历史密钥，轮换后旧密文不失效）。 */
    public byte[] decrypt(final byte[] envelope, final String keyRef) {
        Inputs.requireSm4EnvelopeLength(envelope);
        Inputs.requireKeyRef(keyRef);
        if (!CipherEnvelope.headerValid(envelope)) {
            throw Inputs.invalid();
        }
        final byte[] key;
        if (envelope[CipherEnvelope.MAGIC.length] == CipherEnvelope.VERSION_V2) {
            if (!(keyProvider instanceof VersionedKeyProvider versioned)) {
                // v2 信封只能由版本化密钥源解（本地路径不产 v2 也不解 v2，hifi §1）
                throw Inputs.keyUnavailable();
            }
            key = versioned.sm4Key(keyRef, CipherEnvelope.keyVersionOf(envelope));
        } else {
            key = keyProvider.sm4Key(keyRef);
        }
        final byte[] body = CipherEnvelope.bodyOf(envelope);
        try {
            final GCMModeCipher cipher = GCMBlockCipher.newInstance(new SM4Engine());
            final byte[] iv = CipherEnvelope.ivOf(envelope);
            cipher.init(false, new AEADParameters(new KeyParameter(key), MAC_BITS, iv, null));
            final byte[] out = new byte[cipher.getOutputSize(body.length)];
            final int processed = cipher.processBytes(body, 0, body.length, out, 0);
            final int total = processed + cipher.doFinal(out, processed);
            return Arrays.copyOf(out, total);
        } catch (final InvalidCipherTextException e) {
            throw Inputs.rejected();
        } catch (final RuntimeException e) {
            // 评审①P3-4：篡改/错密钥已由 GCM 标签异常覆盖；其余未预期异常收敛 1001S0002
            throw Inputs.operationFailed(e);
        }
    }

    /** 字符串便捷形（B1）：UTF-8 明文 → Base64 信封（评审②P2-1：长度校验先于转字节分配）。 */
    public String encryptText(final String utf8Text, final String keyRef) {
        Inputs.requirePlainText(utf8Text);
        return Base64.getEncoder().encodeToString(encrypt(utf8Text.getBytes(StandardCharsets.UTF_8), keyRef));
    }

    /** Base64 信封 → UTF-8 明文（评审②P2-1：长度校验先于 Base64 解码分配）。 */
    public String decryptText(final String base64Envelope, final String keyRef) {
        Inputs.requireEnvelopeText(base64Envelope);
        final byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(base64Envelope);
        } catch (final IllegalArgumentException e) {
            throw Inputs.invalid();
        }
        return new String(decrypt(envelope, keyRef), StandardCharsets.UTF_8);
    }
}
