package com.ctds.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 交付服务启动类（WBS-3.5.3）：交付记录宿主（API / 文件两类）与加密文件传输通道。
 * 文件交付与完整性校验一律经 common-crypto（ADR-006），本服务不实现密码学。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DeliveryApplication {

    public static void main(final String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
