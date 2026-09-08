package com.ctds.common.crypto;

import com.ctds.common.errorcode.BizException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** 测试用内存密钥源（未知编号抛 KEY_UNAVAILABLE，与 LocalFileKeyProvider 行为一致）。 */
final class TestKeys implements KeyProvider {

    static final String KEY_REF = "test-key";
    static final byte[] KEY_16 = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static final byte[] OTHER_KEY_16 = "fedcba9876543210".getBytes(StandardCharsets.US_ASCII);

    private final Map<String, byte[]> map = new HashMap<>();

    TestKeys() {
        map.put(KEY_REF, KEY_16.clone());
        map.put("other", OTHER_KEY_16.clone());
    }

    @Override
    public byte[] sm4Key(final String keyRef) {
        final byte[] key = map.get(keyRef);
        if (key == null) {
            throw new BizException(CryptoErrorCodes.CRYPTO_KEY_UNAVAILABLE, "密钥服务暂不可用");
        }
        return key.clone();
    }
}
