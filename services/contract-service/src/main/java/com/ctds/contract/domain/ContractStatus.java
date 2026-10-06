package com.ctds.contract.domain;

/**
 * 合约状态机六态（WBS-3.4.3 hifi §4/lofi §3，Q2-A：发起即"协商中"，"草案"为瞬态不落地；
 * 行为 6 规则 1）。终态（已完结/已终止）零出边不可逆（行为 6 规则 2，{@link
 * ContractTransitions} 单点承载全转移表）。
 */
public enum ContractStatus {

    /** 协商中（发起成功即进入；交替提案/逐版本确认/协商终止均限此态）。 */
    NEGOTIATING,
    /** 待签署（双方对当前版本确认齐 → 条款锁定，规范化原文与内容哈希已固化）。 */
    PENDING_SIGNATURE,
    /** 部分签署（一方已签；未双签生效前任一方可拒签终止——Q4-A）。 */
    PARTIALLY_SIGNED,
    /** 已生效（双签生效，生效时间 = 最后一签时间；存证事件随生效事务登记）。 */
    EFFECTIVE,
    /** 已完结（双方合意解除后的终态；留痕保留可追溯）。 */
    COMPLETED,
    /** 已终止（协商终止/拒签终止/运营方强制终止的终态，终止类型与理由见 contract 列）。 */
    TERMINATED;

    /** 终态判定（行为 6 规则 2：终态不可逆，零出边）。 */
    public boolean isFinal() {
        return this == COMPLETED || this == TERMINATED;
    }
}
