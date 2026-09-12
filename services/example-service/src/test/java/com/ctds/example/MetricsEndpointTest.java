package com.ctds.example;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * WBS-2.5.3 B1 指标暴露契约（hifi §2，先写失败测试）：
 * Prometheus 抓取端点 200 且含 JVM/HTTP 指标文本；health 200；未暴露的管理端点 404（最小暴露面）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class MetricsEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointShouldExposeJvmAndHttpMetrics() throws Exception {
        // 先产生一次已计量的请求，保证 http_server_requests 序列存在
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        final MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        final String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("jvm_memory_used_bytes"), "应包含 JVM 内存指标");
        assertTrue(body.contains("http_server_requests"), "应包含 HTTP 请求指标");
    }

    @Test
    void healthEndpointShouldStayExposed() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void unexcludedActuatorEndpointsShouldStayHidden() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
