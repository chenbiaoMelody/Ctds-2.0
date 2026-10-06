package com.ctds.contract.domain;

/**
 * 合约域文本加解密端口（WBS-3.4.3 hifi §6.1，CAT-04 L3 存储保密硬约束）：条款值 / 变更明细 /
 * 规范化原文 / 签名值四类密文列的加解密只发生在应用层，经 common-crypto Sm4Service
 * （ADR-006 唯一入口，keyRef = ctds.contract.deal-key-ref）。加解密失败按组件原语义上抛
 * （1001S0001/S0002，fail-closed 不降级明文）。
 */
public interface DealTextCipher {

    /** UTF-8 文本 → SM4 密文信封字节（LONGBLOB 列直存）。 */
    byte[] encrypt(String utf8Text);

    /** SM4 密文信封字节 → UTF-8 文本。 */
    String decrypt(byte[] cipher);
}
