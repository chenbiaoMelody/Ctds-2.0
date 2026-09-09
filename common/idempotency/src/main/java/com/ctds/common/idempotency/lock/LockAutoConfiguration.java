package com.ctds.common.idempotency.lock;

import com.ctds.common.logging.AuditRecorder;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 锁组件自动装配（契约 = WBS-2.4.7-hifi；注册模式沿幂等组件）。
 * ctds.lock.enabled=false → 全部 Bean 不注册（fail-fast）；mode 切换双实现：
 * memory=内存实现（无中间件依赖）；redis（默认）= Redisson 实现（内嵌配置类 + 字符串 @ConditionalOnClass
 * 守卫——引入方无 redisson 依赖时整个内嵌类不加载，方法签名不会被反射解析，避免 NoClassDefFoundError；
 * 配合模式配置实现安全失败——不静默降级内存，防单机语义误用）。
 */
@AutoConfiguration
@EnableConfigurationProperties(LockProperties.class)
@ConditionalOnProperty(prefix = "ctds.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LockService.class)
    @ConditionalOnProperty(prefix = "ctds.lock", name = "mode", havingValue = "memory")
    public InMemoryLockService inMemoryLockService() {
        return new InMemoryLockService();
    }

    @Bean
    @ConditionalOnMissingBean(LockAdvice.class)
    public LockAdvice lockAdvice(final LockService lockService, final LockProperties properties,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        return new LockAdvice(lockService, properties, auditRecorder.getIfAvailable());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.redisson.api.RedissonClient")
    @ConditionalOnProperty(prefix = "ctds.lock", name = "mode", havingValue = "redis", matchIfMissing = true)
    static class RedissonConfiguration {

        @Bean
        @ConditionalOnMissingBean(LockService.class)
        public RedissonLockService redissonLockService(final RedissonClient redisson) {
            return new RedissonLockService(redisson);
        }
    }
}
