package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 链不可达集成（hifi §9 T5 / T16 / T7-NOT_ON_CHAIN；规格 B2-1 / B4-1）：
 * 演示构造 = 环境变量关闭存证网关（Q7-A 无运行期后门）——本类以
 * {@code ctds.evidence.chain.enabled=false} 常驻构造，验证：
 * ① 上链失败不回滚、不静默：记录仍存在、状态 FAILED、原因摘要可读；
 * ② 业务侧仍 200（fail-visible，不 5xx 到业务方）；
 * ③ 重试仍不可达 → 503 1012S0001、记录保持 FAILED、留痕追加；
 * ④ 失败态核验结论 = NOT_ON_CHAIN。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.evidence.chain.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceWriteFailureIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_fail_it");
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

    private JsonNode create(final String businessNo) throws Exception {
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
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T5 / T16 / B2-1 / B4-1：链不可达 → 业务仍 200，记录 FAILED，原因可读，不产生账本块。 */
    @Test
    void chainDisabledKeepsRecordFailedAndVisible() throws Exception {
        final JsonNode body = create("DL000071");
        final JsonNode data = body.path("data");

        assertThat(body.path("code").asText()).as("业务侧仍成功（存证与业务解耦）").isEqualTo("0");
        assertThat(data.path("status").asText()).isEqualTo("FAILED");
        assertThat(data.path("chainTxId").isNull()).as("链上标识只由适配器产生").isTrue();

        final String lastError = jdbc.queryForObject(
                "SELECT last_error_summary FROM evidence_record WHERE evidence_no = ?",
                String.class, data.path("evidenceNo").asText());
        assertThat(lastError).as("原因摘要为固定业务文案（P2-1：不含异常类名 / 内部实现）")
                .isEqualTo("存证链暂不可达").doesNotContain("Exception");

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .as("链不可达零账本块").isEqualTo(0L);
        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log ORDER BY id", String.class);
        assertThat(actions).containsExactly("CREATED", "CHAIN_FAILED");
    }

    /** T16 / B4-3：重试仍不可达 → 503 1012S0001，记录保持 FAILED、retry_count 递增、留痕追加。 */
    @Test
    void retryWhenChainStillUnavailableReturns503AndStaysFailed() throws Exception {
        final String evidenceNo = create("DL000072").path("data").path("evidenceNo").asText();

        final MvcResult result = mockMvc.perform(
                        post("/api/v1/evidence/records/" + evidenceNo + "/retry")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.path("code").asText()).isEqualTo("1012S0001");
        assertThat(jdbc.queryForObject("SELECT status FROM evidence_record WHERE evidence_no = ?",
                String.class, evidenceNo)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM evidence_record WHERE evidence_no = ?",
                Integer.class, evidenceNo)).isEqualTo(1);
        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log WHERE evidence_no = ? ORDER BY id",
                String.class, evidenceNo);
        assertThat(actions).containsExactly("CREATED", "CHAIN_FAILED", "RETRY_FAILED");
    }

    /** T7 / B3-1：未上链记录核验结论 = NOT_ON_CHAIN，且 actualDigest 为空。 */
    @Test
    void verifyFailedRecordReportsNotOnChain() throws Exception {
        final String evidenceNo = create("DL000073").path("data").path("evidenceNo").asText();

        final MvcResult result = mockMvc.perform(
                        post("/api/v1/evidence/records/" + evidenceNo + "/verify")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode data = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");

        assertThat(data.path("conclusion").asText()).isEqualTo("NOT_ON_CHAIN");
        assertThat(data.path("status").asText()).isEqualTo("FAILED");
        assertThat(data.path("actualDigest").isNull()).isTrue();
        assertThat(data.path("expectedDigest").asText()).hasSize(64);
        assertThat(data.path("localDigest").asText()).hasSize(64);
        assertThat(data.path("verifiedBy").asText()).isEqualTo(ADMIN);
    }
}
