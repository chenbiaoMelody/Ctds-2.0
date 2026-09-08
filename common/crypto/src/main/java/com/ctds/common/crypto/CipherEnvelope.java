package com.ctds.common.crypto;

/**
 * SM4 密文信封格式（hifi"接口契约"节，版本 1）：
 * {@code CTSE}(4B 魔数) + {@code 0x01}(1B 版本) + IV(12B) + 密文(N) + GCM 标签(16B)。
 * 版本字段为 2.6.3 密钥轮换/算法升级预留（hifi 观察项 2）。
 */
final class CipherEnvelope {

    static final byte[] MAGIC = {'C', 'T', 'S', 'E'};
    static final byte VERSION = 0x01;
    static final int IV_LEN = 12;
    static final int HEADER_LEN = MAGIC.length + 1 + IV_LEN;
    static final int TAG_LEN = 16;
    /** 最小合法信封长度（33 字节 = 头部 17 + 标签 16）。 */
    static final int MIN_LEN = HEADER_LEN + TAG_LEN;

    private CipherEnvelope() {
    }

    static byte[] build(final byte[] iv, final byte[] cipherTextWithTag) {
        final byte[] out = new byte[HEADER_LEN + cipherTextWithTag.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = VERSION;
        System.arraycopy(iv, 0, out, MAGIC.length + 1, iv.length);
        System.arraycopy(cipherTextWithTag, 0, out, HEADER_LEN, cipherTextWithTag.length);
        return out;
    }

    /** 信封头是否为本组件可识别格式（长度/魔数/版本）。不合法应报 CRYPTO_INPUT_INVALID（非我方格式）。 */
    static boolean headerValid(final byte[] envelope) {
        if (envelope == null || envelope.length < MIN_LEN) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (envelope[i] != MAGIC[i]) {
                return false;
            }
        }
        return envelope[MAGIC.length] == VERSION;
    }

    static byte[] ivOf(final byte[] envelope) {
        final byte[] iv = new byte[IV_LEN];
        System.arraycopy(envelope, MAGIC.length + 1, iv, 0, IV_LEN);
        return iv;
    }

    /** 密文+标签体（长度 = 信封长 - 头部 17）。 */
    static byte[] bodyOf(final byte[] envelope) {
        final byte[] body = new byte[envelope.length - HEADER_LEN];
        System.arraycopy(envelope, HEADER_LEN, body, 0, body.length);
        return body;
    }
}
