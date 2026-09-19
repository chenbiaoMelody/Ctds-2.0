package com.ctds.subject;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-20（卡 3 评审④重点 3）：演示 profile 回环绑定的机器守卫。
 *
 * <p>AUD-03 演示期安全边界 = 网络隔离（ADR-016 §2.7）：mysql profile 必须保持
 * {@code server.address=127.0.0.1}（服务仅本机可达）。此前该配置全靠人工 netstat/curl
 * 验证——端口类测试的客户端永远打 localhost，无法断言绑定地址；谁改掉这行配置或换姿势
 * 启动，不会有任何警报。本测试在 Environment 绑定层补齐自动断言：不起真实服务器、
 * 不连数据库，加载真实 application.yml + mysql profile 后断言 ServerProperties 的绑定地址。</p>
 */
class LoopbackBindingGuardTest {

    @Configuration
    @EnableConfigurationProperties(ServerProperties.class)
    static class ServerPropertiesConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ServerPropertiesConfig.class);

    @Test
    void mysqlProfileMustBindLoopbackOnly() {
        runner.withPropertyValues("spring.profiles.active=mysql").run(context -> {
            assertThat(context).hasSingleBean(ServerProperties.class);
            ServerProperties props = context.getBean(ServerProperties.class);
            assertThat(props.getAddress())
                    .as("mysql profile 必须配置 server.address（ADR-016 §2.7 演示期网络隔离）")
                    .isNotNull();
            assertThat(props.getAddress().isLoopbackAddress())
                    .as("server.address 必须为回环地址（127.0.0.1）——演示服务不得对外暴露端口")
                    .isTrue();
        });
    }

    @Test
    void defaultProfileDoesNotBindLoopback() {
        // 对照组：默认 profile 未配置 server.address（绑定全部接口）——证明上方断言真正由
        // mysql profile 的配置驱动，而非 ServerProperties 默认值巧合通过。
        runner.run(context -> {
            ServerProperties props = context.getBean(ServerProperties.class);
            assertThat(props.getAddress()).as("默认 profile 不应配置 server.address").isNull();
        });
    }
}
