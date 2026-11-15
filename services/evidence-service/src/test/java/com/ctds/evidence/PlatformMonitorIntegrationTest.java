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
 * WBS-3.9.4 T2 / T4（B5-2 / B5-4，S1-1 / S1-3）：sysadmin 读四区块齐备（桩 Prometheus：
 * 存活 / 堆内存与 GC / 速率 / 5xx + P95；同源口径断言 = 读面值与数据源单值一致）；
 * 未接入服务显式标注（note 文字 + 值恒 null，不显示 0 / 绿）。负向腿：
 * 无权限 403 + `1000C0005`、未认证 401 + `1000C0002`（B3-2 同码同文）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class PlatformMonitorIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String METRICS_PATH = "/api/v1/platform/metrics";
    private static final MonitorStubServer STUB = createStub();

    private static MonitorStubServer createStub() {
        try {
            return new MonitorStubServer();
        } catch (final IOException failure) {
            throw new IllegalStateException("monitor stub start failed", failure);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_monitor_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_monitor_it");
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
    void sysadminReadsFourBlocksForIntegratedService() throws Exception {
        final MvcResult result = mockMvc.perform(get(METRICS_PATH)
                        .header("X-Ctds-Subject", "S-admin-monitor")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        final JsonNode data = MAPPER.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("services").isArray()).isTrue();
        // service-jobs 缺省 12 项（Q2-A）；example（job=ctds-backend）= 桩 up=1 已接入样例
        assertThat(data.path("services").size()).isEqualTo(12);
        final JsonNode example = data.path("services").get(0);
        assertThat(example.path("service").asText()).isEqualTo("example");
        assertThat(example.path("job").asText()).isEqualTo("ctds-backend");
        assertThat(example.path("integrated").asText()).isEqualTo("INTEGRATED");
        assertThat(example.path("alive").asText()).isEqualTo("UP");
        // 四区块值 = 桩单值（同源口径：读面值 == Prometheus 数据源值，B5-2）
        assertThat(example.path("heapUsedBytes").asLong()).isEqualTo(28311552L);
        assertThat(example.path("heapMaxBytes").asLong()).isEqualTo(402653184L);
        assertThat(example.path("gcPauseSecondsRate5m").asDouble()).isEqualTo(0.003d);
        assertThat(example.path("httpRatePerSec1m").asDouble()).isEqualTo(12.5d);
        assertThat(example.path("errorRate5xx5m").asDouble()).isEqualTo(2.5d);
        assertThat(example.path("p95LatencyMs5m").asDouble()).isEqualTo(123.0d);
        assertThat(example.path("note").isNull()).isTrue();
    }

    @Test
    void notIntegratedServiceIsExplicitlyAnnotatedNeverZeroOrGreen() throws Exception {
        final MvcResult result = mockMvc.perform(get(METRICS_PATH)
                        .header("X-Ctds-Subject", "S-admin-monitor")
                        .header("X-Ctds-Roles", "platform.sysadmin"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode data = MAPPER.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
        // subject（集群外部署，up 无 series）= NOT_INTEGRATED + note 显式标注 + 值恒 null（B5-4）
        final JsonNode subject = data.path("services").get(9);
        assertThat(subject.path("service").asText()).isEqualTo("subject");
        assertThat(subject.path("integrated").asText()).isEqualTo("NOT_INTEGRATED");
        assertThat(subject.path("alive").isNull()).isTrue();
        assertThat(subject.path("heapUsedBytes").isNull()).isTrue();
        assertThat(subject.path("heapMaxBytes").isNull()).isTrue();
        assertThat(subject.path("gcPauseSecondsRate5m").isNull()).isTrue();
        assertThat(subject.path("httpRatePerSec1m").isNull()).isTrue();
        assertThat(subject.path("errorRate5xx5m").isNull()).isTrue();
        assertThat(subject.path("p95LatencyMs5m").isNull()).isTrue();
        assertThat(subject.path("note").asText()).isEqualTo("未接入指标采集，读数不可用");
    }

    @Test
    void roleWithoutPointIsForbiddenWithCommonCode() throws Exception {
        mockMvc.perform(get(METRICS_PATH)
                        .header("X-Ctds-Subject", "S-ops-monitor")
                        .header("X-Ctds-Roles", "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));
    }

    @Test
    void unauthenticatedIsUnauthorizedWithCommonCode() throws Exception {
        mockMvc.perform(get(METRICS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }
}
