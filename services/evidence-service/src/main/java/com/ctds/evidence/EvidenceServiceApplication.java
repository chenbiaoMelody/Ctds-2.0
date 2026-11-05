package com.ctds.evidence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 存证服务启动入口（WBS-3.8.2；规格 C-8.1-8.2 B1~B4）：
 * 存证写入 / 查询 / 核验 / 重试 / 异常概览 + {@code EvidenceGateway} 抽象（默认本地不可变账本）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EvidenceServiceApplication {

    public static void main(final String[] args) {
        SpringApplication.run(EvidenceServiceApplication.class, args);
    }
}
