package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.WebFilter;

/**
 * 网关装配契约（hifi §9 T1）：真实 REACTIVE 应用上下文（无 HTTP 服务器，仅装配面）——
 * 链内 401 出口生效、开关关闭时整链不激活（负向）、JWT 密钥来源二选一强校验沿用（含启动失败用例）。
 */
class GatewayAuthWiringTest {

    private static final String STRONG_SECRET = TestTokens.TEST_SECRET;

    private final ReactiveWebApplicationContextRunner runner =
            new ReactiveWebApplicationContextRunner()
                    .withUserConfiguration(GatewayApplication.class)
                    .withPropertyValues("ctds.audit.enabled=false",
                            "ctds.gateway.rate-limit.enabled=false");

    @Test
    void enabledWiresAuthChainWithGatewayEntryPoint() {
        runner.withPropertyValues("ctds.auth.gateway.enabled=true",
                        "ctds.auth.jwt.secret=" + STRONG_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecurityWebFilterChain.class);
                    assertThat(context.getBean(ServerAuthenticationEntryPoint.class))
                            .as("网关自建 401 出口（带真实 traceId）")
                            .isInstanceOf(com.ctds.gateway.security.GatewayUnauthorizedEntryPoint.class);
                    assertThat(context).hasBean("headerStripFilter");
                    assertThat(context).hasBean("contextHeaderInjectFilter");
                    assertThat(context.getBean("headerStripFilter", Ordered.class).getOrder())
                            .as("入站伪造身份头剥离先于安全链（hifi §1.4：order -200）")
                            .isEqualTo(-200);
                    assertThat(context.getBean("contextHeaderInjectFilter", Ordered.class).getOrder())
                            .as("权威身份头注入（hifi §1.4：order -90）")
                            .isEqualTo(-90);
                    assertThat(context.getBeansOfType(WebFilter.class)).isNotEmpty();
                });
    }

    @Test
    void disabledDoesNotWireAuthChain() {
        runner.withPropertyValues("ctds.auth.gateway.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("gatewayAuthSecurityWebFilterChain");
                    assertThat(context).doesNotHaveBean("headerStripFilter");
                    assertThat(context).doesNotHaveBean("contextHeaderInjectFilter");
                });
    }

    @Test
    void bothKeySourcesFailFast() {
        runner.withPropertyValues("ctds.auth.gateway.enabled=true",
                        "ctds.auth.jwt.secret=" + STRONG_SECRET,
                        "ctds.auth.jwt.jwk-set-uri=https://example.invalid/jwks")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("必须二选一配置");
                });
    }

    @Test
    void missingKeySourcesFailFast() {
        runner.withPropertyValues("ctds.auth.gateway.enabled=true",
                        "ctds.auth.jwt.secret=",
                        "ctds.auth.jwt.jwk-set-uri=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("必须二选一配置");
                });
    }

    @Test
    void weakSecretFailsFast() {
        runner.withPropertyValues("ctds.auth.gateway.enabled=true",
                        "ctds.auth.jwt.secret=too-short")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("长度不足");
                });
    }
}
