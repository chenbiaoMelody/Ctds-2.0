package com.ctds.connector.infrastructure;

import com.ctds.common.crypto.Sm4Service;
import com.ctds.connector.domain.ConnectorErrorCodes;
import com.ctds.connector.domain.ConnectorUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 源凭据保护（规格行为 12 规则 2；Q3-A 三列并存）：唯一入口 = {@code common} 国密组件
 * （ADR-006 / ADR-015，禁自实现密码学）；keyRef = {@code ctds.connector.source.credential-key-ref}。
 *
 * <p><b>fail-closed</b>：密钥不可用 / 密文不可解一律 503 {@code 1010S0004}，绝不降级为明文存储；
 * 异常不含凭据材料（对外统一业务文案）。掩码为展示 / 留痕唯一形态（明文 ≤4 全掩，否则留末 4 位）。</p>
 */
@Component
public class CredentialCipher {

    private static final int MASK_KEEP = 4;
    private static final String FULL_MASK = "****";

    private final Sm4Service sm4Service;
    private final String keyRef;

    public CredentialCipher(final Sm4Service sm4Service,
            @Value("${ctds.connector.source.credential-key-ref:connector-source-credential}")
            final String keyRef) {
        this.sm4Service = sm4Service;
        this.keyRef = keyRef;
    }

    /** 凭据明文 → SM4 信封密文（Base64）。 */
    public String encrypt(final String plaintext) {
        try {
            return sm4Service.encryptText(plaintext, keyRef);
        } catch (final RuntimeException e) {
            throw unavailable();
        }
    }

    /** 信封密文 → 凭据明文（仅适配器调用期使用，不入日志 / 响应）。 */
    public String decrypt(final String ciphertext) {
        try {
            return sm4Service.decryptText(ciphertext, keyRef);
        } catch (final RuntimeException e) {
            throw unavailable();
        }
    }

    /** 掩码规则（hifi §7-⑥）：≤4 全掩；>4 显示末 4 位。 */
    public static String mask(final String plaintext) {
        if (plaintext == null || plaintext.length() <= MASK_KEEP) {
            return FULL_MASK;
        }
        return FULL_MASK + plaintext.substring(plaintext.length() - MASK_KEEP);
    }

    private static ConnectorUnavailableException unavailable() {
        return new ConnectorUnavailableException(ConnectorErrorCodes.SOURCE_CREDENTIAL_UNAVAILABLE,
                ConnectorErrorCodes.SOURCE_CREDENTIAL_UNAVAILABLE_MESSAGE);
    }
}
