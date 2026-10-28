package com.ctds.connector.domain;

/**
 * 源凭据保护端口（domain 表达能力；实现落 infrastructure）。
 *
 * <p>唯一入口 = {@code common} 国密组件（ADR-006 / ADR-015，禁自实现密码学）；
 * 密钥不可用一律 fail-closed（503 {@code 1010S0004}），禁止明文降级。</p>
 */
public interface CredentialProtector {

    /** 凭据明文 → 信封密文。 */
    String encrypt(String plaintext);

    /** 信封密文 → 凭据明文（仅适配器调用期使用，不入日志 / 响应）。 */
    String decrypt(String ciphertext);

    /** 凭据掩码（展示 / 留痕唯一形态）。 */
    String mask(String plaintext);
}
