package com.ctds.common.idempotency;

import com.ctds.common.logging.AuditRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等组件自动装配（契约 = WBS-2.4.7-hifi；注册模式沿前五组件）。
 * ctds.idempotency.enabled=false → 全部 Bean 不注册（fail-fast）；mode 切换双实现：
 * memory=内存实现（无中间件依赖）；redis（默认）= Redis 实现（内嵌配置类 + 字符串 @ConditionalOnClass
 * 守卫——引入方无 spring-data-redis 时整个内嵌类不加载，方法签名不会被反射解析，避免 NoClassDefFoundError；
 * 配合模式配置实现安全失败——不静默降级内存，防单机语义误用）。
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties.class)
@ConditionalOnProperty(prefix = "ctds.idempotency", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnProperty(prefix = "ctds.idempotency", name = "mode", havingValue = "memory")
    public InMemoryIdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyAdvice.class)
    public IdempotencyAdvice idempotencyAdvice(final IdempotencyStore store, final IdempotencyProperties properties,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        return new IdempotencyAdvice(store, properties, auditRecorder.getIfAvailable());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    @ConditionalOnProperty(prefix = "ctds.idempotency", name = "mode", havingValue = "redis", matchIfMissing = true)
    static class RedisConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        public RedisIdempotencyStore redisIdempotencyStore(final StringRedisTemplate redis) {
            return new RedisIdempotencyStore(redis);
        }
    }
}
