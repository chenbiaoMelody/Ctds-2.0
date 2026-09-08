package com.ctds.common.crypto;

import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.util.encoders.Hex;

/**
 * SM3 国密摘要统一入口（hifi B6，Q2 确认纳入；供存证 ADR-004/文件完整性 3.5.3 使用）。
 * 输出 64 位小写十六进制。
 * 空输入语义（评审③P1 统一）：两个重载均放行空输入——SM3 对空消息有标准定义
 * （GB/T 32905 空消息摘要），摘要与加解密不同，不适用 hifi 边界表"空数组拒绝"规则（留痕见 hifi 评审修复记录）。
 */
public class Sm3Service {

    /** 字节摘要（null 拒绝；空数组合法——空消息摘要，标准定义）。 */
    public String digestHex(final byte[] data) {
        if (data == null) {
            throw Inputs.invalid();
        }
        final SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        final byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return Hex.toHexString(out);
    }

    /** UTF-8 字符串摘要（null 拒绝；空串合法，同字节重载语义）。 */
    public String digestHex(final String text) {
        if (text == null) {
            throw Inputs.invalid();
        }
        return digestHex(text.getBytes(StandardCharsets.UTF_8));
    }
}
