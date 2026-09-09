package com.ctds.example.domain;

/**
 * 订单仓储接口（幂等演示，WBS 2.4.7 B11）：内存实现（同 SecretNote 仓储模式）。
 */
public interface DemoOrderRepository {

    /** 保存订单；同 orderNo 覆盖（幂等语义由应用层 @Idempotent 保证只执行一次）。 */
    void save(DemoOrder order);

    /** 已保存订单数（演示/测试断言用）。 */
    long count();

    /** 清空全部订单（演示/测试隔离用）。 */
    void clear();
}
