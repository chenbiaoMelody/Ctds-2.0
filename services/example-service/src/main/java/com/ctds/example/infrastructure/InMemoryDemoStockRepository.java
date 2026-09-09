package com.ctds.example.infrastructure;

import com.ctds.example.domain.DemoStockRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 基础设施层：库存内存仓储（锁演示）——current/update 非原子（模拟 DB 读改写竞态），
 * 锁保护由应用层 @Locked 负责；预置演示商品 P001 初始库存 100。
 */
@Repository
public class InMemoryDemoStockRepository implements DemoStockRepository {

    private final Map<String, Integer> store = new ConcurrentHashMap<>();

    public InMemoryDemoStockRepository() {
        store.put("P001", 100);
    }

    @Override
    public int current(final String productId) {
        return store.getOrDefault(productId, 0);
    }

    @Override
    public void update(final String productId, final int newValue) {
        store.put(productId, newValue);
    }
}
