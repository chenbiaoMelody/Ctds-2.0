package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.MonitorStubServer;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * WBS-3.9.4 T10 / T11（B8-1 / B8-3，S2-6 / S2-7）：处置三动作经平台处置服务唯一入口
 * （静默转 Alertmanager /api/v2/silences：matchers=alertname + silenceId 回显；确认/升级 =
 * 平台留痕型不调告警引擎）；未知对象 400 + `1013C0005`；静默时长越界 400 + `1013C0001`；
 * Alertmanager 不可达 503 + `1013S0003` 且不产生静默副作用；处置写面双向（sysadmin 成功 /
 * secadmin+audadmin+admin+业务角色 403 + `1000C0005` 同码同文；未认证 401）。
 * 留痕四要素（处置人/时点/对象/结论）由 AlertDisposeServiceTest 审计事件断言承载。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class PlatformAlertDisposeIntegrationTest {

    private static final String ACTIONS_PATH = "/api/v1/platform/alerts/actions";
    private static final MonitorStubServer STUB = createStub();

    private static MonitorStubServer createStub() {
        try {
            return new MonitorStubServer();
        } catch (final java.io.IOException failure) {
            throw new IllegalStateException("monitor stub start failed", failure);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_dispose_it");
        registry.add("ctds.auth.rbac.source", () -> "jdbc");
        registry.add("ctds.auth.rbac.schema", () -> "ctds_evidence_dispose_it");
        registry.add("ctds.evidence.monitor.prometheus.base-url", STUB::baseUrl);
        registry.add("ctds.evidence.monitor.alertmanager.base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void resetStubCalls() {
        STUB.resetCalls();
    }

    @Test
    void sysadminSilenceForwardsToAlertmanagerWithMatcherAndEchoesSilenceId() throws Exception {
        final String body = """
                {"alertName":"CtdsBackendDown","action":"SILENCE",
                 "note":"变更窗口静默演示","durationMinutes":90}""";
        final long before = Instant.now().toEpochMilli();
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.alertName").value("CtdsBackendDown"))
                .andExpect(jsonPath("$.data.action").value("SILENCE"))
                .andExpect(jsonPath("$.data.result").value("OK"))
                .andExpect(jsonPath("$.data.silenceId").value(MonitorStubServer.SILENCE_ID));
        final JsonNode silence = STUB.lastSilenceJson();
        assertThat(STUB.silencePosts()).isEqualTo(1);
        assertThat(silence.path("matchers").size()).isEqualTo(1);
        assertThat(silence.path("matchers").get(0).path("name").asText()).isEqualTo("alertname");
        assertThat(silence.path("matchers").get(0).path("value").asText())
                .isEqualTo("CtdsBackendDown");
        assertThat(silence.path("matchers").get(0).path("isRegex").asBoolean()).isFalse();
        assertThat(silence.path("createdBy").asText()).isEqualTo("S-admin-dispose");
        assertThat(silence.path("comment").asText()).isEqualTo("变更窗口静默演示");
        final long minutes = Duration.between(
                Instant.parse(silence.path("startsAt").asText()),
                Instant.parse(silence.path("endsAt").asText())).toMinutes();
        assertThat(minutes).as("endsAt = startsAt + durationMinutes").isEqualTo(90);
        assertThat(minutes).isLessThanOrEqualTo(1440);
    }

    @Test
    void acknowledgeAndEscalateAreRecordOnlyWithoutAlertmanagerCall() throws Exception {
        final int postsBefore = STUB.silencePosts();
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"ACKNOWLEDGE\","
                                + "\"note\":\"已知悉，处置中\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("ACKNOWLEDGE"))
                .andExpect(jsonPath("$.data.result").value("OK"))
                .andExpect(jsonPath("$.data.silenceId").doesNotExist())
                .andExpect(jsonPath("$.data.recordedAt").isNotEmpty());
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"CtdsBackendHighErrorRate\",\"action\":\"ESCALATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("ESCALATE"))
                .andExpect(jsonPath("$.data.result").value("OK"))
                .andExpect(jsonPath("$.data.silenceId").doesNotExist());
        assertThat(STUB.silencePosts()).as("留痕型动作不调告警引擎（诚实边界）")
                .isEqualTo(postsBefore);
    }

    @Test
    void unknownAlertNameRejectedWithDomainCode() throws Exception {
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"SomeUnknownAlert\",\"action\":\"ACKNOWLEDGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0005"))
                .andExpect(jsonPath("$.message").value("告警对象未定义"));
    }

    @Test
    void unknownActionAndOutOfRangeSilenceDurationRejectedWithParamCode() throws Exception {
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"DESTROY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0001"));
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"SILENCE\","
                                + "\"durationMinutes\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0001"));
        mockMvc.perform(post(ACTIONS_PATH)
                        .header("X-Ctds-Subject", "S-admin-dispose")
                        .header("X-Ctds-Roles", "platform.sysadmin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"SILENCE\","
                                + "\"durationMinutes\":1441}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1013C0001"));
    }

    @Test
    void failedAlertmanagerProduces503WithoutSilenceSideEffect() throws Exception {
        STUB.failSilences();
        try {
            mockMvc.perform(post(ACTIONS_PATH)
                            .header("X-Ctds-Subject", "S-admin-dispose")
                            .header("X-Ctds-Roles", "platform.sysadmin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"SILENCE\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("1013S0003"));
        } finally {
            STUB.resetSilenceFailure();
        }
        // 失败的静默不落"OK"留痕——响应即失败，无 silenceId（审计侧由单测类承载）
        assertThat(STUB.silencePosts()).as("失败请求仍到达桩（但响应 500）").isGreaterThanOrEqualTo(1);
    }

    @Test
    void writeFaceIsDeniedForAllOtherRolesWithSameCodeAndMessage() throws Exception {
        final String[][] deniedRoles = {
                {"S-sec-dispose", "platform.secadmin"},
                {"S-aud-dispose", "platform.audadmin"},
                {"S-gov-dispose", "admin"},
                {"S-user-dispose", "applicant"}};
        for (final String[] role : deniedRoles) {
            mockMvc.perform(post(ACTIONS_PATH)
                            .header("X-Ctds-Subject", role[0])
                            .header("X-Ctds-Roles", role[1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"ACKNOWLEDGE\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("1000C0005"))
                    .andExpect(jsonPath("$.message").value("无权限执行该操作"));
        }
        mockMvc.perform(post(ACTIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertName\":\"CtdsBackendDown\",\"action\":\"ACKNOWLEDGE\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }
}
