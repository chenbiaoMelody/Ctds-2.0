package com.ctds.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 幂等存储契约测试（hifi B8）：全部实现（Redis/内存）必须通过本基类断言。
 * 子类负责提供 store 实例（InMemory = 真实语义全量；Redis = mock API 映射，真实 Redis 实测在开发机）。
 */
abstract class IdempotencyStoreContractTest {

    protected abstract IdempotencyStore store();

    private static final Duration SHORT_TTL = Duration.ofMillis(50);
    private static final Duration TTL = Duration.ofSeconds(5);

    @Test
    void 首次获取执行权成功() {
        assertTrue(store().tryAcquire("k1", TTL));
    }

    @Test
    void 同键第二次获取失败() {
        final IdempotencyStore s = store();
        assertTrue(s.tryAcquire("k2", TTL));
        assertFalse(s.tryAcquire("k2", TTL));
    }

    @Test
    void 不同键互不影响() {
        final IdempotencyStore s = store();
        assertTrue(s.tryAcquire("ka", TTL));
        assertTrue(s.tryAcquire("kb", TTL));
    }

    @Test
    void 执行中标记过期后可重新获取() throws InterruptedException {
        final IdempotencyStore s = store();
        assertTrue(s.tryAcquire("k3", SHORT_TTL));
        Thread.sleep(SHORT_TTL.toMillis() + 30);
        assertTrue(s.tryAcquire("k3", TTL));
    }

    @Test
    void 未完成时读结果为空() {
        final IdempotencyStore s = store();
        s.tryAcquire("k4", TTL);
        assertTrue(s.getResult("k4").isEmpty());
    }

    @Test
    void 完成后结果可取且值一致() {
        final IdempotencyStore s = store();
        s.tryAcquire("k5", TTL);
        s.complete("k5", "{\"orderNo\":\"A\"}", TTL);
        assertEquals("{\"orderNo\":\"A\"}", s.getResult("k5").orElseThrow());
    }

    @Test
    void 结果窗口内同键仍被占用() {
        final IdempotencyStore s = store();
        s.tryAcquire("k6", TTL);
        s.complete("k6", "r", TTL);
        // complete 将执行中标记延长到结果 TTL：结果有效期内同键不得重复执行
        assertFalse(s.tryAcquire("k6", TTL));
    }

    @Test
    void 释放后可重新获取且结果清空() {
        final IdempotencyStore s = store();
        s.tryAcquire("k7", TTL);
        s.complete("k7", "r", TTL);
        s.release("k7");
        assertTrue(s.tryAcquire("k7", TTL));
        assertTrue(s.getResult("k7").isEmpty());
    }

    @Test
    void 并发同键恰一个获取成功() throws InterruptedException {
        final IdempotencyStore s = store();
        final int threads = 20;
        final AtomicInteger success = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (s.tryAcquire("k8", TTL)) {
                        success.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get());
    }

    @Test
    void 结果过期后读结果为空() throws InterruptedException {
        final IdempotencyStore s = store();
        s.tryAcquire("k9", SHORT_TTL);
        s.complete("k9", "r", SHORT_TTL);
        Thread.sleep(SHORT_TTL.toMillis() + 30);
        assertTrue(s.getResult("k9").isEmpty());
    }
}
