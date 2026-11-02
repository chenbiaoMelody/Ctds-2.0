package com.ctds.metering.domain.billing;

/**
 * 账单状态（hifi §6 状态机）：ISSUED 已出账 → CONFIRMED 已确认；对账差异时置 FROZEN 并保存原状态，
 * 对账通过后恢复原状态。冻结态禁止确认与更正（B11-3 fail-closed）。
 */
public enum BillStatus {

    /** 已出账（待确认）。 */
    ISSUED,
    /** 已确认。 */
    CONFIRMED,
    /** 已冻结（对账差异，fail-closed）。 */
    FROZEN
}
