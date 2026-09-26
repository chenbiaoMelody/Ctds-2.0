package com.ctds.space.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 空间服务基础设施 Bean 装配（沿 subject CertificationBeanConfig 先例）：
 * Clock 注入统一时间源（留痕"何时"要素与状态列 updated_at 同源，WBS-3.1.3 评审修复先例）。
 */
@Configuration
public class SpaceBeanConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
