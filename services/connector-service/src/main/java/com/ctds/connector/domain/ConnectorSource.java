package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 源接入登记行（唯一事实源，hifi §2 / 规格行为 12）。
 *
 * <p>凭据三列并存：{@code credentialRef} 不透明引用（对外唯一凭据标识）、{@code credentialCiphertext}
 * SM4 信封密文（明文永不落库）、{@code credentialMasked} 展示掩码。<b>本模型不进日志明文</b>——
 * {@link #toString()} 只输出编号与掩码，不输出密文。</p>
 */
public record ConnectorSource(
        Long id,
        String sourceNo,
        String connectorNo,
        String subjectNo,
        String sourceType,
        String endpoint,
        String credentialRef,
        String credentialCiphertext,
        String credentialMasked,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 留痕 / 日志安全形态（不含密文、不含引用原值）。 */
    @Override
    public String toString() {
        return "ConnectorSource[sourceNo=" + sourceNo + ", connectorNo=" + connectorNo
                + ", sourceType=" + sourceType + ", endpoint=" + endpoint
                + ", credentialMasked=" + credentialMasked + "]";
    }
}
