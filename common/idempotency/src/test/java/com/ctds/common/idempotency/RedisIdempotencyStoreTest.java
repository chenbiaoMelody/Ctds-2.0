package com.ctds.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Redis 实现 API 映射契约测试（hifi B8）：验证实现对 StringRedisTemplate 的命令调用与参数
 * 正确（SETNX+TTL / expire+set / get / delete）。真实 Redis 语义（TTL 过期、原子性）由
 * 契约测试基类在 InMemory 上全量断言 + 开发机真实 Redis 实测覆盖（观察项，2.4.11 Testcontainers 未建）。
 * 注意：不继承契约基类——mock 无真实 TTL/原子语义，语义断言只跑 InMemory。
 */
class RedisIdempotencyStoreTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisIdempotencyStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        store = new RedisIdempotencyStore(redis);
    }

    @Test
    void 首次获取走SETNX带TTL() {
        when(valueOps.setIfAbsent(eq("p:k"), eq("1"), any(Duration.class))).thenReturn(true);
        assertTrue(store.tryAcquire("p:k", Duration.ofSeconds(5)));
        verify(valueOps).setIfAbsent("p:k", "1", Duration.ofSeconds(5));
    }

    @Test
    void 重复获取SETNX返回false() {
        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(false);
        assertFalse(store.tryAcquire("p:k", Duration.ofSeconds(5)));
    }

    @Test
    void 完成时延长执行中标记并写结果键() {
        store.complete("p:k", "json", Duration.ofSeconds(10));
        verify(redis).expire("p:k", Duration.ofSeconds(10));
        verify(valueOps).set("p:k:result", "json", Duration.ofSeconds(10));
    }

    @Test
    void 读结果走结果键() {
        when(valueOps.get("p:k:result")).thenReturn("json");
        assertEquals(Optional.of("json"), store.getResult("p:k"));
        verify(valueOps).get("p:k:result");
    }

    @Test
    void 释放删除两键() {
        store.release("p:k");
        verify(redis).delete("p:k");
        verify(redis).delete("p:k:result");
    }
}
