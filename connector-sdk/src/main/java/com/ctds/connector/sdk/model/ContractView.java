package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 合约视图（平台字段并集宽松映射——未出现的字段为 null）：
 * 创建磋商返回 {@code DealViews.Initiated}（contractNo / status / productId）；
 * 详情返回 {@code DealViews.Detail}（含 productName / requesterSubjectNo / providerSubjectNo）；
 * 我的合约行返回 {@code DealViews.Mine}（含 counterpartySubjectNo / myRole）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractView(String contractNo, String status, Long productId, String productName,
        String requesterSubjectNo, String providerSubjectNo, String counterpartySubjectNo,
        String myRole) {
}
