package com.ctds.subject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 主体服务启动入口（WBS-3.1.2；实名认证集成/政务 CA/审核后台随 3.1.3~3.1.5 在本服务扩展）。 */
@SpringBootApplication
public class SubjectServiceApplication {

    public static void main(final String[] args) {
        SpringApplication.run(SubjectServiceApplication.class, args);
    }
}
