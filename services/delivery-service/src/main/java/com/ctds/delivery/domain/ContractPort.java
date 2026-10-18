package com.ctds.delivery.domain;

/** 合约域内部只读衔接（只消费，不复制判定语义）。 */
public interface ContractPort {

    /** 参与方 + 生效门槛 + 产品摘要（不计数）。业务拒绝原码透传。 */
    ContractSummary summarize(String contractNo, String requesterNo);

    /** 既有内部判定（USE；放行即计数）。业务拒绝原码透传。 */
    void check(String contractNo, String requesterNo, String purpose, String territory);
}
