package com.ctds.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 判定调用 WebClient（hifi §4.2）：基址 = {@code ctds.gateway.policy.base-url}（环境变量注入，
 * 沿既有 {@code CTDS_<服务>_<目标>_BASEURL} 惯例）。未配置时调用即失败 → fail-closed 503，
 * 不冒充"已放行"（不做启动期强校验，避免网关因下游未就绪而不可启动）。
 */
@Configuration
public class GatewayClientConfig {

    @Bean
    public WebClient policyWebClient(final WebClient.Builder builder, final GatewayProperties properties) {
        return builder.baseUrl(properties.getPolicy().getBaseUrl()).build();
    }
}
