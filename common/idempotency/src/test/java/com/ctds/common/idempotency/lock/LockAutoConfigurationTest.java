package com.ctds.common.idempotency.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
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

    @Test
    void 契约配置键名绑定生效() {
        // 评审①P1-1：验证契约键（default-wait-seconds/default-lease-seconds 等）经 Spring 绑定到属性对象
        runner.withPropertyValues("ctds.lock.mode=memory",
                        "ctds.lock.default-wait-seconds=7s",
                        "ctds.lock.default-lease-seconds=15s")
                .run(ctx -> {
                    final LockProperties props = ctx.getBean(LockProperties.class);
                    assertThat(props.getDefaultWaitSeconds()).isEqualTo(java.time.Duration.ofSeconds(7));
                    assertThat(props.getDefaultLeaseSeconds()).isEqualTo(java.time.Duration.ofSeconds(15));
                });
    }

    @Test
    void 纯数字时长按秒解析() {
        // 评审③P1：@DurationUnit(SECONDS) 保证纯数字契约值按秒解析（防 Spring 默认毫秒语义静默生效）
        runner.withPropertyValues("ctds.lock.mode=memory",
                        "ctds.lock.default-wait-seconds=3",
                        "ctds.lock.default-lease-seconds=10")
                .run(ctx -> {
                    final LockProperties props = ctx.getBean(LockProperties.class);
                    assertThat(props.getDefaultWaitSeconds()).isEqualTo(java.time.Duration.ofSeconds(3));
                    assertThat(props.getDefaultLeaseSeconds()).isEqualTo(java.time.Duration.ofSeconds(10));
                });
    }

    @Test
    void redis模式注册Redisson实现() {
        // 评审④P2-4：生产主模式正向装配（提供 RedissonClient → Redisson 实现注册）
        runner.withPropertyValues("ctds.lock.mode=redis")
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(RedissonLockService.class)
                        .doesNotHaveBean(InMemoryLockService.class));
    }
}
