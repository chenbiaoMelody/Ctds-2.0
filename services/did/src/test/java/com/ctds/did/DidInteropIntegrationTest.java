package com.ctds.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ctds.did.support.SharedMySqlContainer;
import com.ctds.std.did.DidInteropStandardApi;
import com.ctds.std.did.InteropSample;
import com.ctds.std.did.InteropScenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 互认端点集成测试（WBS-3.1.10 hifi §7 T1~T9 / §6 边界表，ADR-010 容器化基座）：
 * 来访三态结论 + 留痕四要素、未预置 DID、输入类拒绝零留痕、出向回放（有效/已吊销/未登记/取数异常不冒充不通过）、
 * 样例清单出口、留痕与日志原文零命中（反向探针）。
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DidInteropIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INBOUND = "/api/v1/did-interop/inbound-verifications";
    private static final String OUTBOUND = "/api/v1/did-interop/outbound-verifications";
    private static final String SAMPLES = "/api/v1/did-interop/samples";
    private static final String PEER_SPACE = "linjiang";

    /** DB-25：模块共享容器 + 本类独立库名（ADR-010 §8 形态 B）。 */
    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_did_interop");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DidInteropStandardApi didInteropStandardApi;

    @Test
    void 来访有效样例通过并留痕四要素() throws Exception {
        final InteropSample sample = sample(InteropScenario.VALID);

        final JsonNode data = postOk(INBOUND, inboundBody(sample));

        assertThat(data.path("peerSpace").asText()).isEqualTo(PEER_SPACE);
        assertThat(data.path("did").asText()).isEqualTo(sample.did());
        assertThat(data.path("result").asText()).isEqualTo("PASS");
        assertNoReason(data);
        assertThat(data.path("verifiedAt").asText()).isNotBlank();

        final List<Map<String, Object>> rows = logsOf(sample.did());
        assertThat(rows).hasSize(1);
        final Map<String, Object> row = rows.get(0);
        assertThat(row.get("direction")).isEqualTo("INBOUND");
        assertThat(row.get("peer_space")).isEqualTo(PEER_SPACE);
        assertThat(row.get("result")).isEqualTo("PASS");
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("occurred_at")).isNotNull();
    }

    @Test
    void 对端已吊销样例状态核验失败并留痕() throws Exception {
        final InteropSample sample = sample(InteropScenario.PEER_REVOKED);

        final JsonNode data = postOk(INBOUND, inboundBody(sample));

        assertThat(data.path("result").asText()).isEqualTo("FAIL");
        assertThat(data.path("reason").asText()).isEqualTo("REVOKED");
        assertThat(logsOf(sample.did())).singleElement()
                .satisfies(row -> assertThat(row.get("reason")).isEqualTo("REVOKED"));
    }

    @Test
    void 签名被篡改样例签名核验失败并留痕() throws Exception {
        final InteropSample sample = sample(InteropScenario.TAMPERED);

        final JsonNode data = postOk(INBOUND, inboundBody(sample));

        assertThat(data.path("result").asText()).isEqualTo("FAIL");
        assertThat(data.path("reason").asText()).isEqualTo("SIGNATURE_INVALID");
        assertThat(logsOf(sample.did())).singleElement()
                .satisfies(row -> assertThat(row.get("reason")).isEqualTo("SIGNATURE_INVALID"));
    }

    @Test
    void 未预置DID的来访不成立并留痕() throws Exception {
        final String did = "did:linjiang:peer-9999";

        final JsonNode data = postOk(INBOUND, body(PEER_SPACE, did, Base64.getEncoder()
                .encodeToString("任意原文".getBytes(StandardCharsets.UTF_8)), signature(InteropScenario.VALID)));

        assertThat(data.path("result").asText()).isEqualTo("FAIL");
        assertThat(data.path("reason").asText()).isEqualTo("NOT_REGISTERED");
        assertThat(logsOf(did)).hasSize(1);
    }

    @Test
    void 来访入参非法返回1005C0004且不留痕() throws Exception {
        final String did = "did:linjiang:peer-8888";
        final String validData = Base64.getEncoder().encodeToString("原文".getBytes(StandardCharsets.UTF_8));
        final String validSignature = signature(InteropScenario.VALID);

        assertThat(postCode(INBOUND, body(PEER_SPACE, did, "", validSignature))).isEqualTo("1005C0004");
        assertThat(postCode(INBOUND, body(PEER_SPACE, did, "不是Base64!!", validSignature)))
                .isEqualTo("1005C0004");
        assertThat(postCode(INBOUND, body(" ", did, validData, validSignature))).isEqualTo("1005C0004");
        assertThat(postCode(INBOUND, body(PEER_SPACE, did,
                Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]), validSignature)))
                .isEqualTo("1005C0004");
        assertThat(postCode(OUTBOUND, "{\"did\":\"peer-0001\"}")).isEqualTo("1005C0004");

        assertThat(logsOf(did)).as("输入类拒绝不留痕").isEmpty();
    }

    @Test
    void 出向验证回放本空间状态三态() throws Exception {
        final String active = "did:ctds:interop-active";
        final String revoked = "did:ctds:interop-revoked";
        final String unregistered = "did:ctds:interop-unregistered";
        insertLocalDid(active, "ACTIVE", "{\"did\":\"" + active + "\"}");
        insertLocalDid(revoked, "REVOKED", "{\"did\":\"" + revoked + "\"}");

        final JsonNode activeResult = postOk(OUTBOUND, didBody(active));
        assertThat(activeResult.path("peerSpace").asText()).isEqualTo(PEER_SPACE);
        assertThat(activeResult.path("result").asText()).isEqualTo("PASS");
        assertNoReason(activeResult);

        final JsonNode revokedResult = postOk(OUTBOUND, didBody(revoked));
        assertThat(revokedResult.path("result").asText()).isEqualTo("FAIL");
        assertThat(revokedResult.path("reason").asText()).isEqualTo("REVOKED");

        final JsonNode unregisteredResult = postOk(OUTBOUND, didBody(unregistered));
        assertThat(unregisteredResult.path("result").asText()).isEqualTo("FAIL");
        assertThat(unregisteredResult.path("reason").asText()).isEqualTo("NOT_REGISTERED");

        assertThat(logsOf(active)).singleElement().satisfies(row -> {
            assertThat(row.get("direction")).isEqualTo("OUTBOUND");
            assertThat(row.get("peer_space")).isEqualTo(PEER_SPACE);
            assertThat(row.get("result")).isEqualTo("PASS");
        });
        assertThat(logsOf(revoked)).singleElement()
                .satisfies(row -> assertThat(row.get("reason")).isEqualTo("REVOKED"));
    }

    @Test
    void 出向取数异常返回不可用而非不通过() throws Exception {
        final String broken = "did:ctds:interop-broken-document";
        insertLocalDid(broken, "ACTIVE", "{不是合法JSON");

        final JsonNode data = postOk(OUTBOUND, didBody(broken));

        assertThat(data.path("result").asText()).isEqualTo("UNAVAILABLE");
        assertThat(data.path("reason").asText()).isEqualTo("BINDING_UNAVAILABLE");
        assertThat(logsOf(broken)).singleElement().satisfies(row -> {
            assertThat(row.get("result")).isEqualTo("UNAVAILABLE");
            assertThat(row.get("reason")).isEqualTo("BINDING_UNAVAILABLE");
        });
    }

    @Test
    void 样例清单端点返回三态预置样例() throws Exception {
        final JsonNode list = getOk(SAMPLES);

        assertThat(list.size()).isEqualTo(3);
        assertThat(scenarios(list)).containsExactlyInAnyOrder("VALID", "PEER_REVOKED", "TAMPERED");
        for (final JsonNode node : list) {
            assertThat(node.path("sampleId").asText()).isNotBlank();
            assertThat(node.path("peerSpace").asText()).isEqualTo(PEER_SPACE);
            assertThat(node.path("peerSpaceName").asText()).isEqualTo("临江数据空间");
            assertThat(node.path("did").asText()).startsWith("did:linjiang:");
            assertThat(node.path("data").asText()).isNotBlank();
            assertThat(node.path("signature").asText()).isNotBlank();
            assertThat(node.path("expectedResult").asText()).isNotBlank();
        }
    }

    @Test
    void 留痕与日志零命中业务数据原文() throws Exception {
        final ListAppender<ILoggingEvent> appender = attachRootAppender();
        final InteropSample sample = sample(InteropScenario.VALID);
        final String plaintext = new String(Base64.getDecoder().decode(sample.data()), StandardCharsets.UTF_8);
        final String marker = "CTDS-INTEROP-PLAINTEXT-MARKER-7f4c9a";

        try {
            postOk(INBOUND, inboundBody(sample));
            final String markerData = Base64.getEncoder().encodeToString(marker.getBytes(StandardCharsets.UTF_8));
            final JsonNode tampered = postOk(INBOUND, body(PEER_SPACE, sample.did(), markerData,
                    signature(InteropScenario.VALID)));
            assertThat(tampered.path("reason").asText()).isEqualTo("SIGNATURE_INVALID");

            final List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM did_interop_log");
            assertThat(rows).isNotEmpty();
            for (final Map<String, Object> row : rows) {
                assertThat(String.valueOf(row)).doesNotContain(marker, plaintext);
            }

            final String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertThat(logged).doesNotContain(marker, plaintext);
        } finally {
            detachRootAppender(appender);
        }
    }

    private JsonNode postOk(final String url, final String body) throws Exception {
        final MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String postCode(final String url, final String body) throws Exception {
        final MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").asText();
    }

    private JsonNode getOk(final String url) throws Exception {
        final MvcResult result = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private static void assertNoReason(final JsonNode data) {
        assertThat(data.path("reason").isMissingNode() || data.path("reason").isNull()).isTrue();
    }

    private static List<String> scenarios(final JsonNode list) {
        final List<String> scenarios = new java.util.ArrayList<>();
        for (final JsonNode node : list) {
            scenarios.add(node.path("scenario").asText());
        }
        return scenarios;
    }

    private InteropSample sample(final InteropScenario scenario) {
        return didInteropStandardApi.samples().samples().stream()
                .filter(sample -> sample.scenario() == scenario)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少样例：" + scenario));
    }

    private String signature(final InteropScenario scenario) {
        return sample(scenario).signature();
    }

    private static String inboundBody(final InteropSample sample) {
        return body(PEER_SPACE, sample.did(), sample.data(), sample.signature());
    }

    private static String body(final String peerSpace, final String did, final String data,
            final String signature) {
        return "{\"peerSpace\":\"" + peerSpace + "\",\"did\":\"" + did + "\",\"data\":\"" + data
                + "\",\"signature\":\"" + signature + "\"}";
    }

    private static String didBody(final String did) {
        return "{\"did\":\"" + did + "\"}";
    }

    private List<Map<String, Object>> logsOf(final String did) {
        return jdbcTemplate.queryForList(
                "SELECT direction, peer_space, did, result, reason, occurred_at FROM did_interop_log "
                        + "WHERE did = ? ORDER BY id", did);
    }

    private void insertLocalDid(final String did, final String status, final String documentJson) {
        final LocalDateTime now = LocalDateTime.now().withNano(0);
        final String subjectNo = "interop-" + did.substring(did.lastIndexOf(':') + 1);
        jdbcTemplate.update("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                        + "key_ref, document_json, guard_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectNo, 1, did, status, "04" + "ab".repeat(64), "kms-ref-demo", documentJson,
                "REVOKED".equals(status) ? null : subjectNo, Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private static ListAppender<ILoggingEvent> attachRootAppender() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
        return appender;
    }

    private static void detachRootAppender(final ListAppender<ILoggingEvent> appender) {
        rootLogger().detachAppender(appender);
        appender.stop();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
