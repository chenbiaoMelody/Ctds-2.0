package com.ctds.contract.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 合约服务基础设施 Bean 装配（沿 catalog CatalogBeanConfig 先例）：
 * Clock 注入统一时间源（留痕"何时"要素与状态列 updated_at 同源）。
 */
@Configuration
public class ContractBeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
