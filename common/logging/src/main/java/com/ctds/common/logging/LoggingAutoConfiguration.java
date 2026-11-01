package com.ctds.common.logging;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 日志/审计组件自动装配（沿用 errorcode 模块的注册模式，ADR-005 §3 第 6 项）。
 * 结构化 JSON 日志本身由 Spring Boot 内置能力承担（logging.structured.format=logstash），
 * 本装配只负责 AuditRecorder Bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class LoggingAutoConfiguration {

    /**
     * @param serviceName 服务名（spring.application.name，写入审计 JSONL 的 service 字段）
     *
     *     <p>WBS-3.7.5 Q1-A（规格 B7-3 字面路径）：sink="metering-db" 时组装 file+jdbc 双写
     *     （文件存证职能不中断——ADR-022 §2.6-1 出口语义保持）；默认/其他值返回纯文件实现
     *     （现状路径，其他服务零影响）。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "ctds.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AuditRecorder auditRecorder(final AuditProperties properties,
            @Value("${spring.application.name:default}") final String serviceName) {
        final AsyncFileAuditRecorder file = new AsyncFileAuditRecorder(
                Path.of(properties.getFileDir()), serviceName,
                properties.getQueueCapacity(), Clock.systemUTC());
        if ("metering-db".equals(properties.getSink())) {
            return new CompositeAuditRecorder(file,
                    new JdbcMeteringAuditRecorder(properties, Clock.systemUTC()));
        }
        return file;
    }

    /** Web 环境：注册 LogContext 清理过滤器（评审②P1 修复，按请求语义防 MDC 泄漏）。
     *  仅受 servlet 存在性守卫、不受 ctds.audit.enabled 开关约束——业务代码写 MDC 不受开关控制
     *  （评审②P2-1：否则开关关闭时线程复用 MDC 泄漏会静默复活）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class LogContextWebConfiguration {

        @Bean
        FilterRegistrationBean<LogContextCleanupFilter> logContextCleanupFilter() {
            return new FilterRegistrationBean<>(new LogContextCleanupFilter());
        }
    }
}
