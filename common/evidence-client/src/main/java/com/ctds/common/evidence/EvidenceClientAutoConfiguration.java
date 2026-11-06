package com.ctds.common.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 存证客户端自动装配（沿 common/logging 范式，hifi §1.1）：
 * 三个业务服务引入本模块即获得 {@link EvidenceRecorder} Bean，业务侧不感知 HTTP 细节。
 */
@AutoConfiguration
@EnableConfigurationProperties(EvidenceClientProperties.class)
public class EvidenceClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EvidenceRecorder evidenceRecorder(final EvidenceClientProperties properties,
            @Value("${spring.application.name:unknown}") final String serviceName) {
        return new EvidenceRecorder(properties, serviceName);
    }
}
