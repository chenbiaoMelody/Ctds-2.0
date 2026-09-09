package com.ctds.common.idempotency.lock;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.idempotency.IdempotencyErrorCodes;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 分布式锁 Redisson 实现（默认，production 语义；hifi B6-B7）：
 * RLock 看门狗自动续期（leaseTime 负值时）防持锁方崩溃死锁；释放校验持有者（误删他人锁不可能）；
 * 连接/中断异常 → 1002S0002（fail-closed：宁可拒绝不放行）。
 */
public class RedissonLockService implements LockService {

    private final RedissonClient redisson;

    public RedissonLockService(final RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Optional<LockHandle> tryLock(final String fullKey, final Duration waitTime, final Duration leaseTime) {
        final RLock lock = redisson.getLock(fullKey);
        final boolean acquired;
        try {
            if (leaseTime == null || leaseTime.isZero() || leaseTime.isNegative()) {
                acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE, "锁服务不可用", e);
        } catch (RuntimeException e) {
            throw new BizException(IdempotencyErrorCodes.LOCK_SERVICE_UNAVAILABLE, "锁服务不可用", e);
        }
        if (!acquired) {
            return Optional.empty();
        }
        return Optional.of(() -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        });
    }
}
