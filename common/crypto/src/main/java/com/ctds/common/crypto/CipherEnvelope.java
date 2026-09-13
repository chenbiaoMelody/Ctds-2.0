package com.ctds.common.crypto;

/**
 * SM4 密文信封格式（hifi"接口契约"节；v2 版式 = WBS-2.6.3 密钥轮换载体，ADR-006 版本位预留的兑现）：
 * v1: {@code CTSE}(4B) + {@code 0x01}(1B) + IV(12B) + 密文(N) + GCM 标签(16B)——本地密钥路径，行为不变；
 * v2: {@code CTSE}(4B) + {@code 0x02}(1B) + 密钥版本(4B 大端) + IV(12B) + 密文(N) + GCM 标签(16B)——KMS 托管密钥。
 * 轮换后旧密文按信封内版本号取历史密钥解密（不失效）；密钥版本号是密钥管理元数据、非密钥材料（可随密文存储）。
 */
final class CipherEnvelope {

    static final byte[] MAGIC = {'C', 'T', 'S', 'E'};
    static final byte VERSION_V1 = 0x01;
    static final byte VERSION_V2 = 0x02;
    static final int IV_LEN = 12;
    static final int KEY_VERSION_LEN = 4;
    static final int HEADER_LEN = MAGIC.length + 1 + IV_LEN;
    static final int HEADER_LEN_V2 = HEADER_LEN + KEY_VERSION_LEN;
    static final int TAG_LEN = 16;
    /** v1 最小合法信封长度（33 字节 = 头部 17 + 标签 16）。 */
    static final int MIN_LEN = HEADER_LEN + TAG_LEN;
    /** v2 最小合法信封长度（37 字节 = 头部 21 + 标签 16）。 */
    static final int MIN_LEN_V2 = HEADER_LEN_V2 + TAG_LEN;

    private CipherEnvelope() {
    }

    /** v1 信封（本地密钥路径，既有产出逐字节不变）。 */
    static byte[] build(final byte[] iv, final byte[] cipherTextWithTag) {
        return assemble(VERSION_V1, null, iv, cipherTextWithTag);
    }

    /** v2 信封（版本化密钥路径：写入密钥版本号，供轮换后按版本取密钥解密）。 */
    static byte[] buildV2(final byte[] iv, final int keyVersion, final byte[] cipherTextWithTag) {
        return assemble(VERSION_V2, keyVersion, iv, cipherTextWithTag);
    }

    private static byte[] assemble(final byte version, final Integer keyVersion, final byte[] iv,
                                   final byte[] cipherTextWithTag) {
        final int headerLen = keyVersion == null ? HEADER_LEN : HEADER_LEN_V2;
        final byte[] out = new byte[headerLen + cipherTextWithTag.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = version;
        int offset = MAGIC.length + 1;
        if (keyVersion != null) {
            out[offset] = (byte) (keyVersion >>> 24);
            out[offset + 1] = (byte) (keyVersion >>> 16);
            out[offset + 2] = (byte) (keyVersion >>> 8);
            out[offset + 3] = (byte) keyVersion.intValue();
            offset += KEY_VERSION_LEN;
        }
        System.arraycopy(iv, 0, out, offset, iv.length);
        System.arraycopy(cipherTextWithTag, 0, out, headerLen, cipherTextWithTag.length);
        return out;
    }

    /** 信封头是否为本组件可识别格式（长度/魔数/版本，长度按版本各自下限）。不合法应报 CRYPTO_INPUT_INVALID。 */
    static boolean headerValid(final byte[] envelope) {
        if (envelope == null || envelope.length < MAGIC.length + 1) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (envelope[i] != MAGIC[i]) {
                return false;
            }
        }
        final byte version = envelope[MAGIC.length];
        if (version == VERSION_V1) {
            return envelope.length >= MIN_LEN;
        }
        if (version == VERSION_V2) {
            return envelope.length >= MIN_LEN_V2;
        }
        return false;
    }

    /** 头部长度（须先过 {@link #headerValid}）：v1 = 17，v2 = 21。 */
    static int headerLenOf(final byte[] envelope) {
        return envelope[MAGIC.length] == VERSION_V2 ? HEADER_LEN_V2 : HEADER_LEN;
    }

    /** 信封内的密钥版本号（仅 v2 携带；v1 无版本概念返回 -1）。 */
    static int keyVersionOf(final byte[] envelope) {
        if (envelope.length < HEADER_LEN_V2 || envelope[MAGIC.length] != VERSION_V2) {
            return -1;
        }
        final int base = MAGIC.length + 1;
        return ((envelope[base] & 0xFF) << 24) | ((envelope[base + 1] & 0xFF) << 16)
                | ((envelope[base + 2] & 0xFF) << 8) | (envelope[base + 3] & 0xFF);
    }

    static byte[] ivOf(final byte[] envelope) {
        final int offset = headerLenOf(envelope) - IV_LEN;
        final byte[] iv = new byte[IV_LEN];
        System.arraycopy(envelope, offset, iv, 0, IV_LEN);
        return iv;
    }

    /** 密文+标签体（长度 = 信封长 - 头部）。 */
    static byte[] bodyOf(final byte[] envelope) {
        final int headerLen = headerLenOf(envelope);
        final byte[] body = new byte[envelope.length - headerLen];
        System.arraycopy(envelope, headerLen, body, 0, body.length);
        return body;
    }
}
