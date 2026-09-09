package com.ctds.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等组件装配契约测试（hifi B1/B8）：模式切换、开关关闭、Redis 模式缺基础设施安全失败。
 */
class IdempotencyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class));

    @Test
    void 内存模式注册内存实现() {
        runner.withPropertyValues("ctds.idempotency.mode=memory")
                .run(ctx -> assertThat(ctx).hasSingleBean(InMemoryIdempotencyStore.class)
                        .doesNotHaveBean(RedisIdempotencyStore.class)
                        .hasSingleBean(IdempotencyAdvice.class));
    }

    @Test
    void 开关关闭全部不注册() {
        runner.withPropertyValues("ctds.idempotency.enabled=false", "ctds.idempotency.mode=memory")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IdempotencyStore.class)
                        .doesNotHaveBean(IdempotencyAdvice.class));
    }

    @Test
    void redis模式无Redis基础设施安全失败() {
        // 默认 mode=redis，但测试上下文无 StringRedisTemplate Bean → 启动失败（显式提示，不静默降级内存）
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 契约配置键名绑定生效() {
        // 评审①P1-1：验证契约键（processing-ttl-seconds 等）经 Spring 绑定到属性对象，防"键名失效默认值兜底"陷阱
        runner.withPropertyValues("ctds.idempotency.mode=memory",
                        "ctds.idempotency.processing-ttl-seconds=5s",
                        "ctds.idempotency.key-prefix=pfx:",
                        "ctds.idempotency.default-expire-seconds=30")
                .run(ctx -> {
                    final IdempotencyProperties props = ctx.getBean(IdempotencyProperties.class);
                    assertThat(props.getProcessingTtlSeconds()).isEqualTo(java.time.Duration.ofSeconds(5));
                    assertThat(props.getKeyPrefix()).isEqualTo("pfx:");
                    assertThat(props.getDefaultExpireSeconds()).isEqualTo(30);
                });
    }

    @Test
    void 纯数字TTL按秒解析() {
        // 评审③P1：@DurationUnit(SECONDS) 保证纯数字契约值按秒解析（防 Spring 默认毫秒语义静默生效）
        runner.withPropertyValues("ctds.idempotency.mode=memory", "ctds.idempotency.processing-ttl-seconds=60")
                .run(ctx -> assertThat(ctx.getBean(IdempotencyProperties.class).getProcessingTtlSeconds())
                        .isEqualTo(java.time.Duration.ofSeconds(60)));
    }

    @Test
    void redis模式注册Redis实现() {
        // 评审④P2-4：生产主模式正向装配（提供 StringRedisTemplate → Redis 实现注册）
        runner.withPropertyValues("ctds.idempotency.mode=redis")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(RedisIdempotencyStore.class)
                        .doesNotHaveBean(InMemoryIdempotencyStore.class));
    }
}
