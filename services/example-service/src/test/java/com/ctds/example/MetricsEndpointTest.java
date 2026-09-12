package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * WBS-2.5.3 B1 指标暴露契约（hifi §2，先写失败测试）：
 * Prometheus 抓取端点 200 且含 JVM/HTTP 指标文本；health 200；未暴露的管理端点 404（最小暴露面）。
 * 用真实监听端口（RANDOM_PORT + TestRestTemplate）走与 Prometheus 抓取一致的真实 HTTP 栈，
 * 不用 MockMvc（actuator scrape 端点在 MOCK 环境不映射，实测 404 假阴性）。
 * @AutoConfigureObservability：@SpringBootTest 默认禁用全部指标导出器（实测 simpleMeterRegistry
 * 兜底、scrape 端点不装配），本测试验证真实导出链路，须显式开启。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class MetricsEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void prometheusEndpointShouldExposeJvmAndHttpMetrics() {
        // 先产生一次已计量的请求，保证 http_server_requests 序列存在
        restTemplate.getForEntity("/actuator/health", String.class);
        final ResponseEntity<String> result = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertEquals(200, result.getStatusCode().value(), "prometheus 抓取端点应 200");
        final String body = result.getBody();
        assertTrue(body != null && body.contains("jvm_memory_used_bytes"), "应包含 JVM 内存指标");
        assertTrue(body != null && body.contains("http_server_requests"), "应包含 HTTP 请求指标");
    }

    @Test
    void healthEndpointShouldStayExposed() {
        final ResponseEntity<String> result = restTemplate.getForEntity("/actuator/health", String.class);
        assertEquals(200, result.getStatusCode().value());
    }

    @Test
    void unexcludedActuatorEndpointsShouldStayHidden() {
        final ResponseEntity<String> result = restTemplate.getForEntity("/actuator/env", String.class);
        assertEquals(404, result.getStatusCode().value());
    }
}
