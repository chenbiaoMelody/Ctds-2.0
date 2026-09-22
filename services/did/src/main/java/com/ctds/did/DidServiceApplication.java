package com.ctds.did;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** DID 密钥管理与签发服务启动入口（WBS-3.1.8）。 */
@SpringBootApplication
public class DidServiceApplication {

    public static void main(final String[] args) {
        SpringApplication.run(DidServiceApplication.class, args);
    }
}
