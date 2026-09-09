package com.ctds.common.idempotency.lock;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.idempotency.IdempotencyErrorCodes;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分布式锁内存实现（单机语义，无中间件依赖）：演示/单测/单机部署兜底（hifi B8）。
 * ReentrantLock 与 RLock 可重入语义一致；leaseTime 忽略（进程内 finally 释放保证、进程崩溃锁自然消失）。
 * 注意：多实例部署必须使用 Redis 模式，内存实现不跨实例。
 */
public class InMemoryLockService implements LockService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<LockHandle> tryLock(final String fullKey, final Duration waitTime, final Duration leaseTime) {
        final ReentrantLock lock = locks.computeIfAbsent(fullKey, key -> new ReentrantLock());
        final boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE, "锁服务不可用", e);
        }
        if (!acquired) {
            return Optional.empty();
        }
        return Optional.of(lock::unlock);
    }
}
