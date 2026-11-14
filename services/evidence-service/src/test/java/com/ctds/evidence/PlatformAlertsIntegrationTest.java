package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.MonitorStubServer;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.4 T8 / T9（B7-1 / B7-2，S2-1 / S2-3~S2-5）：告警列表要素七项齐备（告警名 / 级别 /
 * 对象 / 触发时点 / 当前值 / 阈值 / 状态）+ 状态映射双向（Q4-A：rules 权威"规则即告警项"，
 * firing → 已触发含 activeAt；inactive → 已恢复 triggeredAt=null；pending → 已恢复 + raw
 * 透传 stateNote）。桩错误率规则态在用例间切换构造双向（同一上下文，读时映射即时生效）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class PlatformAlertsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALERTS_PATH = "/api/v1/platform/alerts";
    private static final MonitorStubServer STUB = createStub();

    private static MonitorStubServer createStub() {
        try {
            final MonitorStubServer stub = new MonitorStubServer();
            stub.setUpValue("0");
            return stub;
        } catch (final IOException failure) {
            throw new IllegalStateException("monitor stub start failed", failure);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_alerts_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_alerts_it");
        registry.add("ctds.evidence.monitor.prometheus.base-url", STUB::baseUrl);
        registry.add("ctds.evidence.monitor.alertmanager.base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void alertItemsCarryAllSevenElementsWithFiringAndResolvedStates() throws Exception {
        final MvcResult result = mockMvc.perform(get(ALERTS_PATH)
                        .header("X-Ctds-Subject", "S-admin-alerts")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        final JsonNode alerts = MAPPER.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data").path("alerts");
        assertThat(alerts.size()).as("封闭两规则固定入列（规则即告警项）").isEqualTo(2);
        final JsonNode down = alerts.get(0);
        assertThat(down.path("alertName").asText()).isEqualTo("CtdsBackendDown");
        assertThat(down.path("severity").asText()).isEqualTo("critical");
        assertThat(down.path("target").asText()).isEqualTo("ctds-backend");
        assertThat(down.path("triggeredAt").asText()).isEqualTo("2026-11-14T15:31:00.000000Z");
        assertThat(down.path("currentValue").asDouble()).isEqualTo(0.0d);
        assertThat(down.path("threshold").asText()).isEqualTo("up == 0（服务不可达/副本下线）");
        assertThat(down.path("state").asText()).isEqualTo("firing");
        final JsonNode errorRate = alerts.get(1);
        assertThat(errorRate.path("alertName").asText()).isEqualTo("CtdsBackendHighErrorRate");
        assertThat(errorRate.path("severity").asText()).isEqualTo("warning");
        assertThat(errorRate.path("triggeredAt").isNull()).isTrue();
        assertThat(errorRate.path("currentValue").asDouble()).isEqualTo(0.025d);
        assertThat(errorRate.path("threshold").asText()).isEqualTo("5xx 占比 > 5%（5m 窗口）");
        assertThat(errorRate.path("state").asText()).isEqualTo("resolved");
    }

    @Test
    void pendingRuleStateMapsToResolvedWithRawTransparency() throws Exception {
        STUB.usePendingErrorRate();
        try {
            final MvcResult result = mockMvc.perform(get(ALERTS_PATH)
                            .header("X-Ctds-Subject", "S-admin-alerts")
                            .header("X-Ctds-Roles", "platform.sysadmin"))
                    .andExpect(status().isOk())
                    .andReturn();
            final JsonNode errorRate = MAPPER.readTree(result.getResponse()
                    .getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("alerts").get(1);
            assertThat(errorRate.path("state").asText()).isEqualTo("resolved");
            assertThat(errorRate.path("stateNote").asText())
                    .isEqualTo("条件满足等待窗口，未正式触发");
            assertThat(errorRate.path("triggeredAt").isNull()).isTrue();
        } finally {
            STUB.resetErrorRateState();
        }
    }

    @Test
    void firingStateKeepsTriggeredAtFromActiveInstance() throws Exception {
        final MvcResult result = mockMvc.perform(get(ALERTS_PATH)
                        .header("X-Ctds-Subject", "S-admin-alerts")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode down = MAPPER.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data").path("alerts").get(0);
        assertThat(down.path("state").asText()).isEqualTo("firing");
        assertThat(down.path("triggeredAt").asText()).isNotBlank();
    }

    @Test
    void roleWithoutPointIsForbiddenWithCommonCode() throws Exception {
        mockMvc.perform(get(ALERTS_PATH)
                        .header("X-Ctds-Subject", "S-ops-alerts")
                        .header("X-Ctds-Roles", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }
}
