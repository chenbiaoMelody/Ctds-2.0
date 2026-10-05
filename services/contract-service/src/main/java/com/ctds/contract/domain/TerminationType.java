package com.ctds.contract.domain;

/**
 * 合约终止类型（hifi §4 contract.termination_type 三值封闭；status = TERMINATED 时必填）。
 */
public enum TerminationType {

    /** 协商终止（NEGOTIATING 态任一方发起——W8）。 */
    NEGOTIATION_TERMINATED,
    /** 拒签终止（未双签生效前任一方拒签/撤回——W10，Q4-A）。 */
    SIGNATURE_REFUSED,
    /** 运营方强制终止（EFFECTIVE 态平台运营方发起，理由必填——W12，行为 6 规则 4）。 */
    GOVERNANCE_FORCE_TERMINATED
}
