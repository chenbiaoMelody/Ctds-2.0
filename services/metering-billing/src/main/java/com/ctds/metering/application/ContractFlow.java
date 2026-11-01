package com.ctds.metering.application;

import java.util.List;

/** 本方合约计量流水（R-M1 响应；requesterNo/providerNo 来自 contract 实时读——展示归属）。 */
public record ContractFlow(String contractNo, String requesterNo, String providerNo,
        List<FlowEntry> items) {
}
