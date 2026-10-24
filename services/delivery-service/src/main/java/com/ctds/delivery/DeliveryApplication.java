package com.ctds.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 交付服务启动类（WBS-3.5.3）：交付记录宿主（API / 文件两类）与加密文件传输通道。
 * 文件交付与完整性校验一律经 common-crypto（ADR-006），本服务不实现密码学。
 * WBS-3.5.4 起增加沙箱子域定时到期回收（{@code @EnableScheduling}，单实例语义）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DeliveryApplication {

    public static void main(final String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
