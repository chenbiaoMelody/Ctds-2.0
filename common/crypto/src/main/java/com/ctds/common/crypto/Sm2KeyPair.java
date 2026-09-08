package com.ctds.common.crypto;

/**
 * SM2 密钥对（hifi 接口契约）：均为十六进制文本——
 * publicKeyHex = 国标非压缩点 04||X||Y（130 字符）；privateKeyHex = D 值（64 字符）。
 * toString 显式脱敏（红线：密钥材料禁止经日志/异常消息泄露），比较与取值不受影响。
 */
public record Sm2KeyPair(String publicKeyHex, String privateKeyHex) {

    @Override
    public String toString() {
        return "Sm2KeyPair[publicKeyHex=***" + (publicKeyHex == null ? 0 : publicKeyHex.length())
                + " chars, privateKeyHex=***]";
    }
}
