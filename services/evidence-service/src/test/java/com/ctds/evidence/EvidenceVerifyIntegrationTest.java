package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 链上核验集成（hifi §9 T7 / T8；规格 B3-1~B3-4）：
 * ① 通过：重算 / 账本 / 本地三摘要逐字相等；
 * ② 不一致：受控改动账本摘要后核验 = MISMATCH 且差异项可读，还原后复验 = PASS（双向）；
 * ③ 核验留痕：人 / 时点 / 对象 / 结论落库；
 * ④ 核验不改数据：记录全列核验前后逐字一致。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceVerifyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_verify_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
    }

    private String create(final String businessNo) throws Exception {
        final String body = "{\"stageType\":\"DELIVERY\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"DELIVERY_CREATED\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T13:30:00+08:00\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("evidenceNo").asText();
    }

    private JsonNode verify(final String evidenceNo) throws Exception {
        final MvcResult result = mockMvc.perform(
                        post("/api/v1/evidence/records/" + evidenceNo + "/verify")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    /** T7① / T8③：通过态三摘要相等 + 核验留痕（人 / 时点 / 结论）。 */
    @Test
    void verifyPassReturnsThreeDigestsAndAppendsTrace() throws Exception {
        final String evidenceNo = create("DL000151");
        final JsonNode data = verify(evidenceNo);

        assertThat(data.path("conclusion").asText()).isEqualTo("PASS");
        assertThat(data.path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(data.path("expectedDigest").asText())
                .as("重算摘要 = 账本摘要 = 本地摘要").isEqualTo(data.path("actualDigest").asText())
                .isEqualTo(data.path("localDigest").asText())
                .hasSize(64);
        assertThat(data.path("verifiedBy").asText()).isEqualTo(ADMIN);
        assertThat(data.path("verifiedAt").asText()).isEqualTo("2026-11-05T13:30:00.000Z");

        final Map<String, Object> trace = jdbc.queryForMap(
                "SELECT action, actor_subject_no, detail, reason_code, created_at "
                        + "FROM evidence_record_action_log WHERE evidence_no = ? AND action = 'VERIFIED'",
                evidenceNo);
        assertThat(trace.get("actor_subject_no")).isEqualTo(ADMIN);
        assertThat(trace.get("detail")).isEqualTo("PASS");
        assertThat(trace.get("reason_code")).isEqualTo("NONE");
        assertThat(trace.get("created_at")).isNotNull();
    }

    /** T7② / B3-2：改账本摘要 → MISMATCH（差异可读）；还原后复验 → PASS（双向）。 */
    @Test
    void verifyDetectsLedgerTamperAndRestores() throws Exception {
        final String evidenceNo = create("DL000161");
        final String originalDigest = jdbc.queryForObject(
                "SELECT payload_digest FROM evidence_chain_block", String.class);
        final String tampered = "a".repeat(64);
        jdbc.update("UPDATE evidence_chain_block SET payload_digest = ?", tampered);

        final JsonNode mismatch = verify(evidenceNo);
        assertThat(mismatch.path("conclusion").asText()).isEqualTo("MISMATCH");
        assertThat(mismatch.path("actualDigest").asText()).as("账本摘要（差异项可读）").isEqualTo(tampered);
        assertThat(mismatch.path("localDigest").asText()).isEqualTo(originalDigest);
        assertThat(mismatch.path("expectedDigest").asText())
                .as("重算摘要与本地摘要一致，差异定位在账本").isEqualTo(originalDigest);

        // 受控还原（剧本附录 A：改一行 + 还原复验）
        jdbc.update("UPDATE evidence_chain_block SET payload_digest = ?", originalDigest);
        assertThat(verify(evidenceNo).path("conclusion").asText()).isEqualTo("PASS");
    }

    /** T8 / B3-4：核验只读——记录全列前后逐字一致，仅追加 VERIFIED 留痕。 */
    @Test
    void verifyDoesNotChangeRecord() throws Exception {
        final String evidenceNo = create("DL000171");
        final Map<String, Object> before = jdbc.queryForMap(
                "SELECT * FROM evidence_record WHERE evidence_no = ?", evidenceNo);
        final long actionsBefore = jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_record_action_log", Long.class);

        verify(evidenceNo);

        final Map<String, Object> after = jdbc.queryForMap(
                "SELECT * FROM evidence_record WHERE evidence_no = ?", evidenceNo);
        assertThat(after).as("核验不改存证记录").isEqualTo(before);
        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log ORDER BY id", String.class);
        assertThat(actions).hasSize((int) actionsBefore + 1);
        assertThat(actions.get(actions.size() - 1)).isEqualTo("VERIFIED");
    }

    /** T7：已上链记录详情可读六要素 + 状态流转（CREATED / CHAIN_SUCCEEDED / VERIFIED 正序）。 */
    @Test
    void detailReturnsRecordAndChronologicalActions() throws Exception {
        final String evidenceNo = create("DL000181");
        verify(evidenceNo);

        final MvcResult result = mockMvc.perform(
                        get("/api/v1/evidence/records/" + evidenceNo)
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode data = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");

        assertThat(data.path("record").path("evidenceNo").asText()).isEqualTo(evidenceNo);
        assertThat(data.path("record").path("businessNo").asText()).isEqualTo("DL000181");
        assertThat(data.path("record").path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(data.path("actions").get(0).path("action").asText()).isEqualTo("CREATED");
        assertThat(data.path("actions").get(1).path("action").asText()).isEqualTo("CHAIN_SUCCEEDED");
        assertThat(data.path("actions").get(2).path("action").asText()).isEqualTo("VERIFIED");
    }
}
