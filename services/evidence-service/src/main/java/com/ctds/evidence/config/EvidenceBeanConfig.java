package com.ctds.evidence.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存证域基础设施装配：统一时间源（取号日期 / 记录时点 / 账本块时点；测试注入固定时钟，
 * 沿 metering-billing BillingBeanConfig 先例）。
 */
@Configuration
public class EvidenceBeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
