package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 合约视图（状态 = 交付凭据查验入口：已生效方可发起交付——行为 4 规则 1）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractView(String contractNo, String applicantNo, String providerNo, String status) {
}
