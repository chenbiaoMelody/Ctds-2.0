package com.ctds.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;

/**
 * API 网关交付服务启动类（WBS-3.5.2，Q1-A：新建反应式网关模块，端口 8086，无库无迁移）。
 *
 * <p>承载 WBS 行 289 四件：鉴权（复用 common-auth 网关侧链，兑现 DB-03 长期解除条件之一）、
 * 限流（主体 + 接口令牌桶）、策略执行点（转发前经合约域内部判定端点判定，越界不触达数据源）、
 * 计量埋点（放行产生 1 条计量事件，拒绝 0 条）。</p>
 *
 * <p>{@code @EnableWebFluxSecurity} 为 common-auth 网关链装配前提（缺它即 fail-fast，见
 * {@code GatewayAuthAutoConfiguration#gatewayAuthSecurityWebFilterChain}）。</p>
 */
@SpringBootApplication
@EnableWebFluxSecurity
public class GatewayApplication {

    public static void main(final String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
