package com.ctds.common.idempotency.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.idempotency.IdempotencyErrorCodes;
import com.ctds.common.idempotency.SpelKeyResolver;
import com.ctds.common.idempotency.lock.LockService.LockHandle;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 锁切面契约测试（hifi B6-B7）：Spring AOP 代理 + InMemory 实现，断言
 * 并发互斥（临界区恰 1）/等待超时 1002C0002/业务异常后锁释放/空键拒绝。
 */
@SpringJUnitConfig(LockAdviceTest.AdviceTestConfig.class)
@EnableAspectJAutoProxy
class LockAdviceTest {

    @Autowired
    private DemoStockService service;

    @Autowired
    private InMemoryLockService lockService;

    @Configuration
    static class AdviceTestConfig {

        @Bean
        DemoStockService demoStockService() {
            return new DemoStockService();
        }

        @Bean
        InMemoryLockService inMemoryLockService() {
            return new InMemoryLockService();
        }

        @Bean
        LockProperties lockProperties() {
            final LockProperties props = new LockProperties();
            props.setMode("memory");
            props.setAuditEnabled(false);
            return props;
        }

        @Bean
        LockAdvice lockAdvice(final InMemoryLockService lockService, final LockProperties properties) {
            return new LockAdvice(lockService, properties, null);
        }
    }

    /** 演示业务：库存扣减（锁保护）+ 异常演示。 */
    static class DemoStockService {

        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger stock = new AtomicInteger(100);

        @Locked(key = "#productId")
        public void deduct(final String productId, final int quantity) {
            final int cur = inside.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stock.addAndGet(-quantity);
            inside.decrementAndGet();
        }

        @Locked(key = "#key", waitSeconds = 1)
        public String waiting(final String key) {
            return "ok-" + key;
        }

        @Locked(key = "#key")
        public String explode(final String key) {
            throw new IllegalStateException("business failure");
        }

        int maxConcurrent() {
            return maxConcurrent.get();
        }

        int stock() {
            return stock.get();
        }

        void reset() {
            stock.set(100);
            maxConcurrent.set(0);
            inside.set(0);
        }
    }

    @BeforeEach
    void setUp() {
        service.reset();
    }

    @Test
    void 并发扣库存临界区互斥() throws InterruptedException {
        final int threads = 20;
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
                    service.deduct("p1", 1);
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, service.maxConcurrent());
        assertEquals(100 - threads, service.stock());
    }

    @Test
    void 等待超时返回操作繁忙() throws Exception {
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 锁由另一线程持有 2 秒（可重入锁同线程会重入成功，必须跨线程制造互斥）；
            // 用 latch 握手确认锁已持有（评审④P2-5：替代 sleep 消除慢机调度竞态）
            final CountDownLatch lockHeld = new CountDownLatch(1);
            final Future<?> holder = pool.submit(() -> {
                final Optional<LockHandle> h = lockService.tryLock("ctds:lock:busy",
                        Duration.ofMillis(100), Duration.ofSeconds(-1));
                assertTrue(h.isPresent());
                lockHeld.countDown();
                sleepQuietly(2000);
                h.orElseThrow().unlock();
            });
            if (!lockHeld.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("holder did not acquire lock");
            }
            final BizException ex = assertThrows(BizException.class, () -> service.waiting("busy"));
            assertEquals(IdempotencyErrorCodes.LOCK_ACQUIRE_TIMEOUT.value(), ex.getErrorCode().value());
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void 业务异常后锁已释放() {
        assertThrows(IllegalStateException.class, () -> service.explode("k"));
        final Optional<LockHandle> handle = lockService.tryLock("ctds:lock:k", Duration.ofMillis(500),
                Duration.ofSeconds(-1));
        assertTrue(handle.isPresent());
        handle.orElseThrow().unlock();
    }

    @Test
    void 空键拒绝快速失败() {
        final BizException ex = assertThrows(BizException.class, () -> service.waiting(null));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
    }

    @Test
    void 超长键拒绝快速失败() {
        // 评审④P2-7：锁侧与幂等侧同路径——键长超限 → 1000C0001
        final BizException ex = assertThrows(BizException.class,
                () -> service.waiting("K".repeat(SpelKeyResolver.MAX_KEY_LENGTH + 1)));
        assertEquals(ErrorCodes.PARAM_INVALID.value(), ex.getErrorCode().value());
    }
}
