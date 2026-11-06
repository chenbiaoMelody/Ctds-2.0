package com.ctds.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
import com.ctds.space.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 空间授权环节存证埋点集成测试（WBS-3.8.3 hifi §2 E1/E2、§8 T9~T14；规格 B1-1 / B1-3 / B2-1 / Q10）：
 * 成员准入 / 策略生效各一条真实业务触发；拒绝 / 同值 / 重复确认零事件；DID 不可用与存证服务不可达时
 * 业务仍成功。桩 = JDK HttpServer（存证写面 + did 内部端点，零新依赖）。
 * 本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SpaceEvidenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/data-spaces";
    private static final String DID = "did:ctds:abc123def456";
    private static final EvidenceStub STUB;

    static {
        try {
            STUB = new EvidenceStub();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_space_evidence");
        registry.add("ctds.evidence.client.base-url", STUB::baseUrl);
        registry.add("ctds.evidence.client.connect-timeout", () -> "2s");
        registry.add("ctds.evidence.client.read-timeout", () -> "3s");
        registry.add("ctds.space.did.base-url", STUB::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @BeforeEach
    void setUp() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        STUB.reset();
        resetPlatformBaseline();
    }

    // ==== T9 成员准入 → 1 条 AUTHORIZATION/SPACE_MEMBER_ADMITTED ====

    @Test
    void memberAdmittedEmitsSingleAuthorizationEvent() throws Exception {
        final long id = enabledSpace("owner-e1", "存证空间E1", "INVITE", "PRIVATE");
        final long admissionId = invite(id, "owner-e1", "invitee-e1");
        confirm(id, admissionId, "invitee-e1");

        final List<JsonNode> events = STUB.events();
        assertThat(events).hasSize(1);
        final JsonNode event = events.get(0);
        assertThat(event.path("stageType").asText()).isEqualTo("AUTHORIZATION");
        assertThat(event.path("operationType").asText()).isEqualTo("SPACE_MEMBER_ADMITTED");
        assertThat(event.path("businessNo").asText())
                .isEqualTo("SPACE" + id + ":ADMISSION" + admissionId);
        assertThat(event.path("subjectDid").asText()).isEqualTo(DID);
        assertThat(event.path("actorSubjectNo").asText()).isEqualTo("invitee-e1");
        assertThat(event.path("occurredAt").asText()).isNotBlank();
    }

    // ==== T10 重复确认被状态门槛拦住 → 0 新事件 ====

    @Test
    void repeatConfirmationEmitsNoEvent() throws Exception {
        final long id = enabledSpace("owner-e2", "存证空间E2", "INVITE", "PRIVATE");
        final long admissionId = invite(id, "owner-e2", "invitee-e2");
        confirm(id, admissionId, "invitee-e2");
        STUB.resetEvidence();

        mockMvc.perform(auth(post(BASE + "/" + id + "/admissions/" + admissionId + "/confirmation"),
                        "invitee-e2", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isConflict());

        assertThat(STUB.events()).isEmpty();
    }

    // ==== T11 策略覆盖（首覆盖 / 再覆盖）各 1 条，businessNo 互异（取自留痕 id）====

    @Test
    void policyOverridesEmitDistinctBusinessNos() throws Exception {
        final long id = enabledSpace("owner-e3", "存证空间E3", "INVITE", "PRIVATE");
        override(id, "owner-e3", "member.data_export", "APPROVAL_REQUIRED");
        override(id, "owner-e3", "member.data_export", "FORBIDDEN");

        final List<JsonNode> events = STUB.events();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(event -> event.path("operationType").asText())
                .containsOnly("SPACE_POLICY_INHERITED");
        assertThat(events).extracting(event -> event.path("businessNo").asText())
                .allMatch(no -> no.startsWith("SPACE" + id + ":POLICY-AL"))
                .doesNotHaveDuplicates();
        assertThat(events).extracting(event -> event.path("subjectDid").asText())
                .containsOnly(DID);
    }

    // ==== T12 红线放宽拒绝 / 同值幂等 → 0 事件（Q10）====

    @Test
    void redlineRejectionAndSameValueEmitNoEvent() throws Exception {
        final long id = enabledSpace("owner-e4", "存证空间E4", "INVITE", "PRIVATE");
        mockMvc.perform(auth(post(BASE + "/" + id + "/policies/overrides"), "owner-e4", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"data.visibility\",\"entryValue\":\"ALL_PLATFORM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("1006C0013"));
        // 同值幂等：提交值与当前生效值一致 → 无操作无留痕无事件
        override(id, "owner-e4", "data.retention", "D90");

        assertThat(STUB.events()).isEmpty();
    }

    // ==== T13 主体无 ACTIVE DID → 业务成功、0 事件（Q6，不伪造主体标识）====

    @Test
    void didNotActiveSkipsEvidenceButBusinessSucceeds() throws Exception {
        STUB.respondDidStatus("REVOKED");
        final long id = enabledSpace("owner-e5", "存证空间E5", "INVITE", "PRIVATE");
        final long admissionId = invite(id, "owner-e5", "invitee-e5");
        confirm(id, admissionId, "invitee-e5");

        assertThat(STUB.events()).isEmpty();
        assertThat(STUB.didQueries()).isPositive();
    }

    // ==== T14 存证服务 500 → 业务成功、不抛出（fail-open，B2-1）====

    @Test
    void evidenceUnavailableKeepsBusinessSuccess() throws Exception {
        STUB.respondEvidence(500, "{\"code\":\"1012S0001\",\"message\":\"unavailable\"}");
        final long id = enabledSpace("owner-e6", "存证空间E6", "INVITE", "PRIVATE");
        final long admissionId = invite(id, "owner-e6", "invitee-e6");
        confirm(id, admissionId, "invitee-e6");

        assertThat(STUB.evidenceRequests()).isEqualTo(1);
        final Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM space_member WHERE space_id = ? AND status = 'ACTIVE'",
                Integer.class, id);
        assertThat(memberCount).isEqualTo(2);
    }

    // ==== helper ====

    private void resetPlatformBaseline() {
        jdbc.update("DELETE FROM space_policy WHERE scope = 'PLATFORM'");
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, "
                + "entry_value, is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'data.visibility', "
                + "'SPACE_MEMBER', 1, 'ACTIVE')");
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, "
                + "entry_value, is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'data.retention', "
                + "'D90', 1, 'ACTIVE')");
        jdbc.update("INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, "
                + "entry_value, is_redline, status) VALUES ('PLATFORM', NULL, NULL, 'member.data_export', "
                + "'ALLOWED', 0, 'ACTIVE')");
    }

    private long createSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final MvcResult result = mockMvc.perform(auth(post(BASE), owner, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sceneType\":\"OTHER\",\"accessMode\":\""
                                + accessMode + "\",\"visibility\":\"" + visibility + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long enabledSpace(final String owner, final String name, final String accessMode,
            final String visibility) throws Exception {
        final long id = createSpace(owner, name, accessMode, visibility);
        mockMvc.perform(auth(post(BASE + "/" + id + "/enablement"), owner, "user"))
                .andExpect(status().isOk());
        return id;
    }

    private long invite(final long spaceId, final String operator, final String invitee)
            throws Exception {
        final MvcResult result = mockMvc.perform(
                        auth(post(BASE + "/" + spaceId + "/admissions/invitations"), operator, "user")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectNo\":\"" + invitee + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyMember").value(false))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .path("data").path("admission").path("id").asLong();
    }

    private void confirm(final long spaceId, final long admissionId, final String invitee)
            throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/admissions/" + admissionId + "/confirmation"),
                        invitee, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    private void override(final long spaceId, final String operator, final String entryKey,
            final String entryValue) throws Exception {
        mockMvc.perform(auth(post(BASE + "/" + spaceId + "/policies/overrides"), operator, "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryKey\":\"" + entryKey + "\",\"entryValue\":\"" + entryValue + "\"}"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    /** 存证写面 + did 内部端点桩（记录请求次数与载荷，可切换响应）。 */
    private static final class EvidenceStub {

        private final HttpServer server;
        private final List<String> evidenceBodies = new CopyOnWriteArrayList<>();
        private final List<String> didSubjects = new CopyOnWriteArrayList<>();
        private volatile int evidenceStatus = 200;
        private volatile String evidenceResponse = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";
        private volatile String didStatus = "ACTIVE";

        EvidenceStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/evidence/internal/records", this::handleEvidence);
            server.createContext("/api/v1/did/internal/subjects/", this::handleDid);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            resetEvidence();
            didSubjects.clear();
            evidenceStatus = 200;
            evidenceResponse = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";
            didStatus = "ACTIVE";
        }

        void resetEvidence() {
            evidenceBodies.clear();
        }

        void respondEvidence(final int status, final String body) {
            this.evidenceStatus = status;
            this.evidenceResponse = body;
        }

        void respondDidStatus(final String status) {
            this.didStatus = status;
        }

        int evidenceRequests() {
            return evidenceBodies.size();
        }

        int didQueries() {
            return didSubjects.size();
        }

        List<JsonNode> events() {
            return evidenceBodies.stream().map(body -> {
                try {
                    return MAPPER.readTree(body);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        private void handleEvidence(final HttpExchange exchange) throws IOException {
            evidenceBodies.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            write(exchange, evidenceStatus, evidenceResponse);
        }

        private void handleDid(final HttpExchange exchange) throws IOException {
            final String path = exchange.getRequestURI().getPath();
            final String subjectNo = path.substring("/api/v1/did/internal/subjects/".length(),
                    path.length() - "/current".length());
            didSubjects.add(subjectNo);
            final String didJson = "ACTIVE".equals(didStatus)
                    ? "\"" + DID + "\"" : "null";
            write(exchange, 200, "{\"code\":\"0\",\"message\":\"success\",\"data\":"
                    + "{\"subjectNo\":\"" + subjectNo + "\",\"did\":" + didJson
                    + ",\"status\":\"" + didStatus + "\"}}");
        }

        private static void write(final HttpExchange exchange, final int status, final String body)
                throws IOException {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
