package com.ctds.metering.domain.billing;

/**
 * 账单号取号端口（hifi §2）：全局唯一、终身不复用（B10-2）。
 * 实现依赖同一事务连接上的原子取号（调用方必须在事务内使用）。
 */
public interface BillNoGenerator {

    /** 取下一个账单号（BL + 6 位序号）。 */
    String nextBillNo();
}
