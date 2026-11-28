package com.ctds.catalog.infrastructure;

import com.ctds.catalog.application.ObjectDenialRateLimiter;
import com.ctds.catalog.application.SubjectDenialRateLimiter;
import com.ctds.catalog.infrastructure.config.CatalogDenialProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 目录服务基础设施 Bean 装配（沿 space SpaceBeanConfig 先例）：
 * Clock 注入统一时间源（留痕"何时"要素与状态列 updated_at 同源）。
 */
@Configuration
@EnableConfigurationProperties(CatalogDenialProperties.class)
public class CatalogBeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public ObjectDenialRateLimiter objectDenialRateLimiter(final Clock clock,
            final CatalogDenialProperties properties) {
        return new ObjectDenialRateLimiter(clock, properties.getMaxRequestsPerMinute(),
                properties.getMaxTrackedKeys());
    }

    @Bean
    public SubjectDenialRateLimiter subjectDenialRateLimiter(final Clock clock,
            final CatalogDenialProperties properties) {
        return new SubjectDenialRateLimiter(clock, properties.getMaxSubjectRequestsPerMinute(),
                properties.getMaxSubjectTrackedKeys());
    }
}
