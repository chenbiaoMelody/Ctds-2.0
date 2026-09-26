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
 *
 * <p>DB-24（清债卡3，2026-09-26）口径演进：受守不变量从"mysql profile 配置级回环"升级为
 * <b>"任意 profile 均配置级回环"</b>（ADR-016 §2.7 DB-24 补记）——三服务默认 profile 同批补
 * 防御性 {@code server.address=127.0.0.1}。原对照组"默认 profile 不应配置 server.address"
 * （归因对照，防 ServerProperties 默认值巧合通过）随新口径改为正向断言"默认 profile 亦回环"；
 * 原"mysql profile 独占提供回环"的归因诉求在新不变量下无安全意义（mysql 配置项被删时由默认
 * profile 兜底回环，绑定行为不变），守卫对象由"特定文件的特定行"改为"回环不变量"本身。</p>
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
    void defaultProfileMustBindLoopbackToo() {
        // DB-24 新口径：默认 profile 补防御性回环（ADR-016 §2.7 DB-24 补记）——任意 profile 均配置级
        // 绑定回环，回环绑定不再依赖 profile 选择或启动方式注入。
        runner.run(context -> {
            assertThat(context).hasSingleBean(ServerProperties.class);
            ServerProperties props = context.getBean(ServerProperties.class);
            assertThat(props.getAddress())
                    .as("默认 profile 也必须配置 server.address（DB-24 防御性回环，ADR-016 §2.7）")
                    .isNotNull();
            assertThat(props.getAddress().isLoopbackAddress())
                    .as("默认 profile 的 server.address 必须为回环地址（127.0.0.1）——任意 profile 均不得对外暴露端口")
                    .isTrue();
        });
    }
}
