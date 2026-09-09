package com.ctds.common.idempotency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 锁组件装配契约测试（hifi B1/B8）：模式切换、开关关闭、Redis 模式缺基础设施安全失败。
 */
class LockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LockAutoConfiguration.class));

    @Test
    void 内存模式注册内存实现() {
        runner.withPropertyValues("ctds.lock.mode=memory")
                .run(ctx -> assertThat(ctx).hasSingleBean(InMemoryLockService.class)
                        .doesNotHaveBean(RedissonLockService.class)
                        .hasSingleBean(LockAdvice.class));
    }

    @Test
    void 开关关闭全部不注册() {
        runner.withPropertyValues("ctds.lock.enabled=false", "ctds.lock.mode=memory")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LockService.class)
                        .doesNotHaveBean(LockAdvice.class));
    }

    @Test
    void redis模式无Redisson客户端安全失败() {
        // 默认 mode=redis，但测试上下文无 RedissonClient Bean → 启动失败（显式提示，不静默降级内存）
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }
}
