package com.ctds.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 计量采集服务启动入口（WBS-3.7.5）。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MeteringBillingApplication {

    public static void main(final String[] args) {
        SpringApplication.run(MeteringBillingApplication.class, args);
    }
}
