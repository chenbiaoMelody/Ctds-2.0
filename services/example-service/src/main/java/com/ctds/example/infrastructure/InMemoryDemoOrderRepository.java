package com.ctds.example.infrastructure;

import com.ctds.example.domain.DemoOrder;
import com.ctds.example.domain.DemoOrderRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 基础设施层：订单内存仓储（演示，同 SecretNote 仓储模式）。
 */
@Repository
public class InMemoryDemoOrderRepository implements DemoOrderRepository {

    private final Map<String, DemoOrder> store = new ConcurrentHashMap<>();

    @Override
    public void save(final DemoOrder order) {
        store.put(order.orderNo(), order);
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }
}
