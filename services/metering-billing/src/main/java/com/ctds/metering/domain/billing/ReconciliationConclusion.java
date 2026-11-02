package com.ctds.metering.domain.billing;

/**
 * 对账结论（B11-1/B11-2）：PASS = 四方数值与复算金额全部一致；
 * DIFFERENCE = 任一腿不一致（差异必报，不允许静默通过）。
 */
public enum ReconciliationConclusion {

    /** 通过。 */
    PASS,
    /** 差异（fail-closed：相关账单冻结）。 */
    DIFFERENCE
}
