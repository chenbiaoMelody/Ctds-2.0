package com.ctds.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例服务启动类（WBS 2.4.1 工程骨架：验证编译/测试/风格/分层门禁全链路）。
 */
@SpringBootApplication
public final class ExampleServiceApplication {

    private ExampleServiceApplication() {
    }

    public static void main(final String[] args) {
        SpringApplication.run(ExampleServiceApplication.class, args);
    }
}
