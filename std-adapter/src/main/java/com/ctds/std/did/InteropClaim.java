package com.ctds.std.did;

import java.util.Arrays;

/**
 * 来访身份主张（业务语言字段，非协议报文）：对端空间标识 + 对端 DID + 原文 + 签名。
 * <p>原文与签名为<b>原始字节</b>（传输层 Base64 编解码由调用方完成）；构造时复制入参数组，防止外部改写。</p>
 */
public record InteropClaim(String peerSpace, String did, byte[] data, byte[] signature) {

    public InteropClaim {
        data = data == null ? null : Arrays.copyOf(data, data.length);
        signature = signature == null ? null : Arrays.copyOf(signature, signature.length);
    }
}
