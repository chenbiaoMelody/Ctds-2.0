package com.ctds.common.idempotency;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等状态 Redis 实现（默认，production 语义；hifi B8）：执行权 = SETNX 带 TTL，
 * 结果缓存 = 独立键带 TTL；complete 时将执行中标记 TTL 延长为结果 TTL（结果有效期内同键保持"已占用"）。
 * Redis 连接异常由切面包装为 1002S0001（fail-closed，防重复执行）。
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String RESULT_SUFFIX = ":result";

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(final StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(final String fullKey, final Duration ttl) {
        final Boolean acquired = redis.opsForValue().setIfAbsent(fullKey, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void complete(final String fullKey, final String resultJson, final Duration ttl) {
        redis.expire(fullKey, ttl);
        redis.opsForValue().set(fullKey + RESULT_SUFFIX, resultJson, ttl);
    }

    @Override
    public Optional<String> getResult(final String fullKey) {
        return Optional.ofNullable(redis.opsForValue().get(fullKey + RESULT_SUFFIX));
    }

    @Override
    public void release(final String fullKey) {
        redis.delete(fullKey);
        redis.delete(fullKey + RESULT_SUFFIX);
    }
}
