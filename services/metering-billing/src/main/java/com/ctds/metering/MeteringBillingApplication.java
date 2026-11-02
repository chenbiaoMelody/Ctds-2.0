package com.ctds.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 计量采集与计费服务启动入口（WBS-3.7.5 计量采集；WBS-3.7.6 计费与账单同域扩展）。
 * WBS-3.7.6 起启用 Spring 内置定时调度（自然月自动出账，单实例语义；沿 delivery-service 先例）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MeteringBillingApplication {

    public static void main(final String[] args) {
        SpringApplication.run(MeteringBillingApplication.class, args);
    }
}
