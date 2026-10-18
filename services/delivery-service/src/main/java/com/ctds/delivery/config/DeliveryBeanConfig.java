package com.ctds.delivery.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 交付服务基础设施装配：统一时间源（留痕/测试可控）。 */
@Configuration
public class DeliveryBeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
