package com.ctds.evidence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.SharedMySqlContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.4 T2 负向腿（hifi §4 边界 1 / B5-4 同精神）：Prometheus base-url 指向不可达端口 →
 * 读面 fail-closed 503 + `1013S0003`（不伪造空数据 / 零值，不降级 500）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false",
                "ctds.evidence.monitor.prometheus.base-url=http://127.0.0.1:1/",
                "ctds.evidence.monitor.service-jobs=example:ctds-backend"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class PlatformMonitorSourceUnavailableIntegrationTest {

    private static final String METRICS_PATH = "/api/v1/platform/metrics";

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_monsrc_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_monsrc_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unreachablePrometheusFailsClosedWith503AndDomainCode() throws Exception {
        mockMvc.perform(get(METRICS_PATH)
                        .header("X-Ctds-Subject", "S-admin-monitor")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1013S0003"))
                .andExpect(jsonPath("$.message").value("监控数据源暂不可用，请稍后重试"));
    }

    @Test
    void alertsFaceFailsClosedLikely() throws Exception {
        mockMvc.perform(get("/api/v1/platform/alerts")
                        .header("X-Ctds-Subject", "S-admin-monitor")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("1013S0003"));
    }
}
