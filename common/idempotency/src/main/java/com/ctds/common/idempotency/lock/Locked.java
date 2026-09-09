package com.ctds.common.idempotency.lock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁方法注解（契约 = docs/designs/WBS-2.4.7-hifi.md / ADR-007）：同一互斥键的并发调用
 * 只有一个进入，其余等待 waitSeconds，超时 → 1002C0002"操作繁忙，请稍后重试"。
 * 持锁协议由 Redisson RLock 保证（看门狗自动续期/可重入/释放校验持有者，不自研锁协议）。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Locked {

    /**
     * 互斥键 SpEL 表达式（方法参数上下文，如 "#productId"）；必填，求值结果不能为 null/空串
     * （违反 → 1000C0001，快速失败，不执行业务）。
     */
    String key();

    /**
     * 获取锁等待超时（秒）；&lt;=0 时取配置 ctds.lock.default-wait-seconds（默认 3）。超时 → 1002C0002。
     */
    long waitSeconds() default -1;

    /**
     * 持锁自动释放（秒）；&lt;=0 时取配置 ctds.lock.default-lease-seconds（默认 -1 = 看门狗自动续期，
     * 持锁线程存活期间锁不过期，崩溃后自动释放——防死锁）。
     */
    long leaseSeconds() default -1;
}
