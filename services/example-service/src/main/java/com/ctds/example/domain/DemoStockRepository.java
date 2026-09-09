package com.ctds.example.domain;

/**
 * 库存仓储接口（分布式锁演示，WBS 2.4.7 B11）：current/update 刻意设计为非原子
 * 读-改-写（模拟 DB 读改写竞态）——无锁并发时多线程读到同一库存值导致超卖，
 * 锁保护后串行扣减不超卖（演示锁的互斥价值）。
 */
public interface DemoStockRepository {

    /** 当前库存（非原子读）。 */
    int current(String productId);

    /** 覆写库存（非原子写；模拟读-改-写窗口）。 */
    void update(String productId, int newValue);
}
