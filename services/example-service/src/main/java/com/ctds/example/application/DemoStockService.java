package com.ctds.example.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.idempotency.lock.Locked;
import com.ctds.example.domain.DemoStockRepository;
import org.springframework.stereotype.Service;

/**
 * 应用服务：库存扣减（分布式锁演示，WBS 2.4.7 B11）——@Locked 按商品互斥；
 * 仓储读-改-写刻意非原子（模拟 DB 竞态），无锁并发会超卖，锁保护后并发扣减不超卖。
 */
@Service
public class DemoStockService {

    private final DemoStockRepository repository;

    public DemoStockService(final DemoStockRepository repository) {
        this.repository = repository;
    }

    /** 扣减库存：同一商品的并发扣减串行执行（不超卖）；库存不足拒绝。 */
    @Locked(key = "#productId")
    public int deduct(final String productId, final int quantity) {
        final int current = repository.current(productId);
        sleepQuietly(10); // 放大读-改-写竞态窗口：无锁时多线程读到同一 current → 超卖
        if (current < quantity) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "库存不足");
        }
        final int remaining = current - quantity;
        repository.update(productId, remaining);
        return remaining;
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
