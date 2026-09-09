package com.ctds.example.application;

import com.ctds.common.idempotency.Idempotent;
import com.ctds.example.domain.DemoOrder;
import com.ctds.example.domain.DemoOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 应用服务：订单提交（幂等演示，WBS 2.4.7 B11）——@Idempotent 保证同一订单号只创建一次，
 * 重复提交返回首次创建结果（模式 B 结果复用），业务不重复执行。
 */
@Service
public class DemoOrderService {

    private final DemoOrderRepository repository;

    public DemoOrderService(final DemoOrderRepository repository) {
        this.repository = repository;
    }

    /** 提交订单：同一 orderNo 重复提交 → 只创建一次、返回首次结果。 */
    @Idempotent(key = "#orderNo")
    public DemoOrder submit(final String orderNo) {
        final DemoOrder order = DemoOrder.of(orderNo);
        repository.save(order);
        return order;
    }

    /** 已创建订单数（演示/测试断言用）。 */
    public long orderCount() {
        return repository.count();
    }
}
