package com.ctds.common.crypto;

import com.ctds.common.errorcode.BizException;

/**
 * 参数校验与错误工厂（hifi"边界值与异常行为"表的代码化）：
 * 全部对外文案为服务端常量，禁止拼接用户输入；异常与日志不得含明文/密文/密钥材料（红线）。
 */
final class Inputs {

    /** 输入上限 64 MiB（超限防内存打爆；大文件分块加密归 3.5.3——hifi 观察项 1）。 */
    static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    /** 密钥编号长度上限（字符）。 */
    static final int MAX_KEY_REF_CHARS = 64;

    private Inputs() {
    }

    static BizException invalid() {
        return new BizException(CryptoErrorCodes.CRYPTO_INPUT_INVALID, "加解密输入不合法");
    }

    static BizException rejected() {
        return new BizException(CryptoErrorCodes.CRYPTO_DATA_REJECTED, "数据校验未通过，已拒绝");
    }

    static BizException keyUnavailable() {
        return new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
    }

    /** 底层算法库未预期异常：cause 仅内部保留（出站被 GlobalExceptionHandler 屏蔽），日志不带内容。 */
    static BizException operationFailed(final Throwable cause) {
        return new BizException(CryptoErrorCodes.CRYPTO_OPERATION_FAILED, "加解密操作失败", cause);
    }

    static void requirePlaintext(final byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0 || plaintext.length > MAX_INPUT_BYTES) {
            throw invalid();
        }
    }

    /**
     * SM4 信封长度上界 = 明文上限 + 信封开销 33（评审①P2-1：明文恰为 64MiB 时信封长 = 64MiB+33，
     * 上界必须按信封口径放宽，否则组件自产数据读不回）。
     */
    static void requireSm4EnvelopeLength(final byte[] envelope) {
        if (envelope == null || envelope.length == 0
                || envelope.length > MAX_INPUT_BYTES + CipherEnvelope.MIN_LEN) {
            throw invalid();
        }
    }

    /** SM2 密文长度上界 = 明文上限 + C1(65)+C3(32) 开销。 */
    static void requireSm2CipherLength(final byte[] cipherText) {
        if (cipherText == null || cipherText.length == 0
                || cipherText.length > MAX_INPUT_BYTES + 97) {
            throw invalid();
        }
    }

    /** 密钥编号允许的字符集（评审②P3-1/评审③P3-1：单一来源，禁止多点拷贝正则）。 */
    static final String KEY_REF_PATTERN = "[A-Za-z0-9._-]+";

    static void requireKeyRef(final String keyRef) {
        if (keyRef == null || keyRef.isBlank() || keyRef.length() > MAX_KEY_REF_CHARS) {
            throw invalid();
        }
        if (!keyRef.matches(KEY_REF_PATTERN)) {
            // 评审②P3-1：限制字符集，防止业务把用户可控值直作 keyRef 造成日志伪造/注入
            throw invalid();
        }
    }

    /**
     * 字符串便捷入口的文本长度上界（评审②P2-1/评审④P2-2）：
     * UTF-8 明文最坏 3 字节/字符预算——字符数 ≤ 上限/3 可保证"转字节后必然 ≤ 明文上限"，
     * 使长度校验在内存分配之前生效（防多字节文本绕过分配预检）。
     */
    static void requirePlainText(final String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_INPUT_BYTES / 3) {
            throw invalid();
        }
    }

    /** Base64 信封文本上界（评审④P2-2）：解码后由 {@link #requireSm4EnvelopeLength} 精确校验，此处仅防分配旁路。 */
    static void requireEnvelopeText(final String base64Envelope) {
        final int maxEnvelopeChars = (MAX_INPUT_BYTES + CipherEnvelope.MIN_LEN) / 3 * 4 + 4;
        if (base64Envelope == null || base64Envelope.isEmpty() || base64Envelope.length() > maxEnvelopeChars) {
            throw invalid();
        }
    }
}
