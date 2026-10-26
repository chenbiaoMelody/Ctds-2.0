package com.ctds.connector.sdk.spi;

/**
 * 源接入登记模型（规格行为 12）：凭据<b>引用制</b>——访问凭据经平台侧
 * {@code common} 国密组件加密存储（ADR-006 / ADR-015，禁明文），本模型仅携带
 * 凭据引用标识，<b>不存在任何明文凭据字段</b>（编译期即阻断明文路径）。
 *
 * <p>{@code credentialRef} 必须为平台侧签发的<b>不透明引用</b>——禁止把口令 / 密钥原文
 * 放入本字段（toString 已对引用掩码，防日志二次泄露）。</p>
 *
 * @param sourceType       源类型（四类之一）
 * @param endpoint         源地址
 * @param credentialRef    凭据引用（平台侧加密存储的凭据标识，不透明值）
 * @param ownerConnectorNo 所属连接器编号（3.6.3 签发后注入）
 */
public record SourceRegistration(SourceType sourceType, String endpoint,
        String credentialRef, String ownerConnectorNo) {

    /** 掩码输出：凭据引用不随日志 / 异常打印原值。 */
    @Override
    public String toString() {
        return "SourceRegistration[sourceType=" + sourceType + ", endpoint=" + endpoint
                + ", credentialRef=***, ownerConnectorNo=" + ownerConnectorNo + "]";
    }
}
