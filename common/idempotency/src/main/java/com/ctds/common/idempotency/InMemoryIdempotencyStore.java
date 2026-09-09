package com.ctds.common.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等状态内存实现（单机语义，无中间件依赖）：演示/单测/单机部署兜底（hifi B8）。
 * 惰性过期（读写时检查 TTL），无后台清理线程；行为契约与 Redis 实现一致（契约测试同跑）。
 * 注意：多实例部署必须使用 Redis 实现（mode=redis），内存实现不跨实例。
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final String RESULT_SUFFIX = ":result";

    private final ConcurrentHashMap<String, Long> acquiring = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExpiringValue> results = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(final String fullKey, final Duration ttl) {
        final long now = System.currentTimeMillis();
        final long expireAt = now + ttl.toMillis();
        final Long existing = acquiring.putIfAbsent(fullKey, expireAt);
        if (existing == null) {
            return true;
        }
        if (existing <= now) {
            // 执行中标记已过期：尝试覆盖获取（并发下恰一个成功）
            return acquiring.replace(fullKey, existing, expireAt);
        }
        return false;
    }

    @Override
    public void complete(final String fullKey, final String resultJson, final Duration ttl) {
        final long expireAt = System.currentTimeMillis() + ttl.toMillis();
        // 执行中标记延长到结果 TTL：结果有效期内同键保持"已占用"，防重复执行（契约语义）
        acquiring.computeIfPresent(fullKey, (key, old) -> expireAt);
        results.put(fullKey + RESULT_SUFFIX, new ExpiringValue(resultJson, expireAt));
    }

    @Override
    public Optional<String> getResult(final String fullKey) {
        final ExpiringValue value = results.get(fullKey + RESULT_SUFFIX);
        if (value == null) {
            return Optional.empty();
        }
        if (value.expireAt <= System.currentTimeMillis()) {
            results.remove(fullKey + RESULT_SUFFIX, value);
            return Optional.empty();
        }
        return Optional.ofNullable(value.value);
    }

    @Override
    public void release(final String fullKey) {
        acquiring.remove(fullKey);
        results.remove(fullKey + RESULT_SUFFIX);
    }

    private record ExpiringValue(String value, long expireAt) {
    }
}
