package com.ctds.connector.sdk;

/**
 * 连接器身份锚（规格行为 1 规则 2 / §7 Q2-A）：以<b>所属主体 DID</b> 为身份锚、
 * 平台侧唯一连接器编号作实例标识——连接器不另设独立身份。
 *
 * <p>{@code connectorNo} 由 3.6.3 平台侧核验通过时签发；本骨架期（3.6.3 未开工）
 * 允许占位（null），编号签发后注入即可用。</p>
 *
 * @param subjectNo   属主主体编号（必填，已入驻主体）
 * @param did         属主主体 DID（必填，作身份锚）
 * @param connectorNo 连接器编号（占位可空，3.6.3 签发后注入）
 */
public record ConnectorContext(String subjectNo, String did, String connectorNo) {

    /** 构建期校验：身份锚两要素必填（fail-fast，CONFIG 分类）。 */
    public ConnectorContext {
        if (subjectNo == null || subjectNo.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "subjectNo 必填（属主主体编号）", null);
        }
        if (did == null || did.isBlank()) {
            throw new ConnectorException(SdkErrorType.CONFIG, "did 必填（身份锚 = 所属主体 DID）", null);
        }
    }
}
