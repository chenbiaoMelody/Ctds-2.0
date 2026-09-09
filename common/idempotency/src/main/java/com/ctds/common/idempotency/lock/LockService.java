package com.ctds.common.idempotency.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * 分布式锁抽象（契约 = WBS-2.4.7-hifi）：注解切面与业务代码唯一依赖点。
 * 双实现：Redisson（默认，production 语义）/ 内存（单机语义，演示/单测/单机兜底）；
 * 业务零改动可替换（@ConditionalOnMissingBean）。
 */
public interface LockService {

    /**
     * 尝试获取互斥锁。
     *
     * @param fullKey   组装后的完整锁键（前缀 + 业务键）
     * @param waitTime  等待超时（不可为 null/负；超时未获取 → 返回 empty）
     * @param leaseTime 持锁自动释放；null/零/负 = 看门狗自动续期（Redisson 语义；内存实现忽略，
     *                  进程内 finally 释放 + 进程崩溃锁自然消失）
     * @return 获取成功 → 句柄（finally 中释放）；超时 → empty
     */
    Optional<LockHandle> tryLock(String fullKey, Duration waitTime, Duration leaseTime);

    /** 锁句柄：持锁期间返回，释放由切面 finally 保证（业务异常不泄漏）。 */
    interface LockHandle extends AutoCloseable {

        /** 释放锁（仅持有者语义；Redisson 校验持有者，误删他人锁不可能）。 */
        void unlock();

        @Override
        default void close() {
            unlock();
        }
    }
}
