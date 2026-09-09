package com.ctds.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
