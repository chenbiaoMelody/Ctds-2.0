package com.ctds.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 连接器注册与核验服务启动类（WBS-3.6.3）：平台侧注册申请 / 核验三查 / 编号签发 / DID 绑定。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConnectorApplication {

    public static void main(final String[] args) {
        SpringApplication.run(ConnectorApplication.class, args);
    }
}
