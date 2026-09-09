package com.ctds.common.idempotency.lock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ctds.common.idempotency.lock.LockService.LockHandle;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * Redisson 实现 API 映射契约测试（hifi B8）：验证 tryLock 三参/两参（看门狗）路由、
 * 释放持有者校验。真实 Redis 语义（看门狗续期/跨实例互斥）由开发机真实 Redis 实测覆盖（观察项）。
 */
class RedissonLockServiceTest {

    private RedissonClient redisson;
    private RLock lock;
    private RedissonLockService service;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redisson.getLock("p:k")).thenReturn(lock);
        service = new RedissonLockService(redisson);
    }

    @Test
    void 负leaseTime走看门狗两参tryLock() throws InterruptedException {
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        final Optional<LockHandle> handle = service.tryLock("p:k", Duration.ofMillis(300), Duration.ofSeconds(-1));
        assertTrue(handle.isPresent());
        verify(lock).tryLock(300L, TimeUnit.MILLISECONDS);
        handle.orElseThrow().unlock();
        verify(lock).unlock();
    }

    @Test
    void 正leaseTime走三参tryLock() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        final Optional<LockHandle> handle = service.tryLock("p:k", Duration.ofMillis(300), Duration.ofSeconds(10));
        assertTrue(handle.isPresent());
        verify(lock).tryLock(300L, 10_000L, TimeUnit.MILLISECONDS);
        handle.orElseThrow().unlock();
    }

    @Test
    void 超时返回empty() throws InterruptedException {
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);
        assertFalse(service.tryLock("p:k", Duration.ofMillis(300), Duration.ofSeconds(-1)).isPresent());
        verify(lock, never()).unlock();
    }

    @Test
    void 释放校验持有者() throws InterruptedException {
        when(lock.tryLock(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false); // 非持有线程（模拟误释放防护）
        service.tryLock("p:k", Duration.ofMillis(300), Duration.ofSeconds(-1)).orElseThrow().unlock();
        verify(lock, times(0)).unlock();
    }

    @Test
    void 锁键映射到Redisson() {
        service.tryLock("p:k", Duration.ofMillis(300), Duration.ofSeconds(-1));
        verify(redisson).getLock("p:k");
    }
}
