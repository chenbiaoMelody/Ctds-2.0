package com.ctds.metering.domain.billing;

/**
 * 账单类型（hifi §2/§6）：普通账单 = 合约 × 周期唯一一份；更正单 = 对原单的另出冲正/补差单。
 * 唯一键 uk_active_normal_key 仅约束普通账单，更正单可多份。
 */
public enum BillType {

    /** 普通账单（同一合约同一周期唯一）。 */
    NORMAL,
    /** 更正单（红字冲正 = 负金额 / 补差 = 正金额；原单逐字不变）。 */
    CORRECTION
}
