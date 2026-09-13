package com.ctds.kms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 密钥管理服务启动入口（WBS-2.6.3）。 */
@SpringBootApplication
public class KmsServiceApplication {

    public static void main(final String[] args) {
        SpringApplication.run(KmsServiceApplication.class, args);
    }
}
