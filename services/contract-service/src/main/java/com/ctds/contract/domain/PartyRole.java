package com.ctds.contract.domain;

/**
 * 合约双方角色（hifi §4：provider = 提供方（产品属主快照）/ requester = 需求方（发起人））。
 * 参与方判定 = 操作者主体编号命中两列之一（应用服务单点）。
 */
public enum PartyRole {

    /** 提供方（合约对象产品的属主，发起时快照锁定）。 */
    PROVIDER,
    /** 需求方（合约发起人）。 */
    REQUESTER;

    /** 对手方角色（读面"交易对手"承载）。 */
    public PartyRole counterparty() {
        return this == PROVIDER ? REQUESTER : PROVIDER;
    }
}
