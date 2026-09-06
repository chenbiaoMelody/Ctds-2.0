package com.ctds.common.logging;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 日志/审计组件自动装配（沿用 errorcode 模块的注册模式，ADR-005 §3 第 6 项）。
 * 结构化 JSON 日志本身由 Spring Boot 内置能力承担（logging.structured.format=logstash），
 * 本装配只负责 AuditRecorder Bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
@ConditionalOnProperty(prefix = "ctds.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoggingAutoConfiguration {

    /**
     * @param serviceName 服务名（spring.application.name，写入审计 JSONL 的 service 字段）
     */
    @Bean(destroyMethod = "shutdown")
    public AuditRecorder auditRecorder(final AuditProperties properties,
            @Value("${spring.application.name:default}") final String serviceName) {
        return new AsyncFileAuditRecorder(Path.of(properties.getFileDir()), serviceName,
                properties.getQueueCapacity(), Clock.systemUTC());
    }
}
