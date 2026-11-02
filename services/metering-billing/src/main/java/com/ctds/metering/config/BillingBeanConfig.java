package com.ctds.metering.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计费域基础设施装配：统一时间源（对账时点 / 账单生成时点 / 周期判定；测试可注入固定时钟，
 * 沿 delivery-service DeliveryBeanConfig 先例）。
 */
@Configuration
public class BillingBeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
