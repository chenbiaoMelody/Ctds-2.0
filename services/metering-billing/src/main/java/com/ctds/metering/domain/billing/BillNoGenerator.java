package com.ctds.metering.domain.billing;

/**
 * 账单号取号端口（hifi §2）：全局唯一、终身不复用（B10-2）。
 * 取号 = 同一连接上的原子自增 + 读回（{@code LAST_INSERT_ID}），实现侧自带事务边界
 * （{@code @Transactional}）；调用方若已开启事务则加入同一事务，**不得在事务外自行拆分两步调用**。
 */
public interface BillNoGenerator {

    /** 取下一个账单号（BL + 6 位序号）；失败抛运行时异常，不返回空号。 */
    String nextBillNo();
}
