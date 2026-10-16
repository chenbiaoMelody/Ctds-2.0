package com.ctds.gateway.ratelimit;

/**
 * 进程内令牌桶（hifi §3-2）：容量 + 恒定补充速率，按纳秒时钟惰性补充。
 *
 * <p>边界（诚实）：进程内实现 = 单实例语义；多实例部署前须换分布式实现（登记演进项，
 * 沿 common-idempotency "memory 模式"先例）。正式吞吐承诺归 4.2.3（≥2000 TPS / P99&lt;500ms），
 * 本卡只做正确性验证与轻量基准留痕。</p>
 */
final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final int capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(final int capacity, final double refillPerSecond, final long nowNanos) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    /** 取一个令牌：成功 true；不足 false（调用方据此拒绝且不触达上游）。 */
    synchronized boolean tryAcquire(final long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1d) {
            tokens -= 1d;
            return true;
        }
        return false;
    }

    /** 建议重试间隔（秒）：满速补充 1 个令牌所需时间，向上取整且不小于 1。 */
    synchronized long retryAfterSeconds() {
        if (refillPerSecond <= 0d) {
            return 1L;
        }
        return Math.max(1L, (long) Math.ceil(1d / refillPerSecond));
    }

    private void refill(final long nowNanos) {
        final long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0L) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed / NANOS_PER_SECOND * refillPerSecond);
        lastRefillNanos = nowNanos;
    }
}
