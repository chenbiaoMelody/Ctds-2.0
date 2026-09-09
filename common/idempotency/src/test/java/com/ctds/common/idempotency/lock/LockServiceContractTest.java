package com.ctds.common.idempotency.lock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.idempotency.lock.LockService.LockHandle;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 锁服务契约测试（hifi B8）：全部实现（Redisson/内存）必须通过本基类断言。
 * 子类负责提供 LockService 实例（InMemory = 真实语义全量；Redisson = mock API 映射，真实 Redis 实测在开发机）。
 */
abstract class LockServiceContractTest {

    protected abstract LockService lockService();

    private static final Duration WAIT = Duration.ofMillis(200);
    private static final Duration WATCHDOG = Duration.ofSeconds(-1);

    @Test
    void 获取成功并释放后可再获取() {
        final LockService s = lockService();
        final Optional<LockHandle> first = s.tryLock("k1", WAIT, WATCHDOG);
        assertTrue(first.isPresent());
        first.orElseThrow().unlock();
        assertTrue(s.tryLock("k1", WAIT, WATCHDOG).isPresent());
    }

    @Test
    void 持锁期间他人获取失败() throws Exception {
        final LockService s = lockService();
        final Optional<LockHandle> holder = s.tryLock("k2", WAIT, WATCHDOG);
        assertTrue(holder.isPresent());
        // "他人"= 另一线程：可重入锁同线程会重入成功，必须跨线程断言互斥
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            final Future<Optional<LockHandle>> other =
                    pool.submit(() -> s.tryLock("k2", Duration.ofMillis(100), WATCHDOG));
            assertFalse(other.get(2, TimeUnit.SECONDS).isPresent());
        } finally {
            pool.shutdownNow();
        }
        holder.orElseThrow().unlock();
        assertTrue(s.tryLock("k2", WAIT, WATCHDOG).isPresent());
    }

    @Test
    void 同线程可重入() {
        final LockService s = lockService();
        final Optional<LockHandle> first = s.tryLock("k3", WAIT, WATCHDOG);
        final Optional<LockHandle> second = s.tryLock("k3", WAIT, WATCHDOG);
        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        second.orElseThrow().unlock();
        first.orElseThrow().unlock();
        assertTrue(s.tryLock("k3", WAIT, WATCHDOG).isPresent());
    }

    @Test
    void 并发同键临界区互斥() throws InterruptedException {
        final LockService s = lockService();
        final int threads = 20;
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
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
                    final Optional<LockHandle> handle = s.tryLock("k4", Duration.ofSeconds(3), WATCHDOG);
                    if (handle.isPresent()) {
                        final int cur = inside.incrementAndGet();
                        maxConcurrent.accumulateAndGet(cur, Math::max);
                        sleepQuietly(10);
                        inside.decrementAndGet();
                        handle.orElseThrow().unlock();
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
        assertEquals(1, maxConcurrent.get());
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
