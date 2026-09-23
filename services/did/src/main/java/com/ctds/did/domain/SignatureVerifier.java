package com.ctds.did.domain;

/**
 * 验签端口（WBS-3.1.9 hifi B5）：基础设施经 common-crypto 唯一入口实现（红线：不自研密码学）。
 */
public interface SignatureVerifier {

    /**
     * SM2 验签：签名不匹配 / 数据被篡改 / 签名结构非法 → false；
     * 公钥或数据异常（库内数据损坏等非输入类问题）抛 RuntimeException，由调用方收敛为 1005S0002。
     */
    boolean verify(byte[] data, byte[] signature, String publicKeyHex);
}