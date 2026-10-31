package com.ctds.contract.domain;

/**
 * 订单状态三态（WBS-3.7.3 hifi §2.1；规格 B3-4）。订单 = 合约业务外壳，状态只映射规格
 * B3-4 两条联动（合约生效→COMPLETED、生效前终止→CLOSED）；合约生效后的合意完结 /
 * 强制终止不改订单状态（Q7-A：已完成订单的关联合约状态经合约域可见）。
 */
public enum OrderStatus {
    /** 进行中（订购登记 → 合约协商/签署中）。 */
    IN_PROGRESS,
    /** 已完成（关联合约双签生效——B3-4 联动一）。 */
    COMPLETED,
    /** 已关闭（关联合约生效前终止：协商终止 / 拒签终止——B3-4 联动二）。 */
    CLOSED
}
