package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.FlakyChainGatewayConfiguration;
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
 * 失败重试集成（hifi §9 T6 / T15；规格 B4-3 / B2-3）：
 * ① 首次失败 → FAILED；重试成功 → ON_CHAIN，失败与重试两条留痕都在（双留痕）；
 * ② 崩溃窗口重放：账本已写、状态未更 → 重试命中 uk_chain_payload 返回原回执，不产生第二块；
 * ③ 已上链再重试 → 409 1012C0003 且零副作用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import({FixedClockTestConfiguration.class, FlakyChainGatewayConfiguration.class})
class EvidenceRetryIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_retry_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(false);
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

    private JsonNode retry(final String evidenceNo, final int expectedStatus) throws Exception {
        final MvcResult result = mockMvc.perform(
                        post("/api/v1/evidence/records/" + evidenceNo + "/retry")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T6 / B4-3：失败 → 重试成功，CHAIN_FAILED 与 RETRY_SUCCEEDED 两条留痕都在。 */
    @Test
    void retryAfterFailureSucceedsAndKeepsBothTraces() throws Exception {
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(true);
        final String evidenceNo = create("DL000121").path("data").path("evidenceNo").asText();
        assertThat(jdbc.queryForObject("SELECT status FROM evidence_record WHERE evidence_no = ?",
                String.class, evidenceNo)).isEqualTo("FAILED");

        final JsonNode body = retry(evidenceNo, 200);
        assertThat(body.path("code").asText()).isEqualTo("0");
        assertThat(body.path("data").path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(body.path("data").path("retryCount").asInt()).isEqualTo(1);
        assertThat(body.path("data").path("chainTxId").asText()).startsWith("LTX");

        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log WHERE evidence_no = ? ORDER BY id",
                String.class, evidenceNo);
        assertThat(actions).as("失败留痕保留 + 重试成功追加")
                .containsExactly("CREATED", "CHAIN_FAILED", "RETRY_SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .isEqualTo(1L);
    }

    /** T15 / B2-2：崩溃窗口（账本已写、状态未更）重试 → 复用原回执，不产生第二块。 */
    @Test
    void crashWindowReplayReusesExistingBlock() throws Exception {
        final JsonNode created = create("DL000131").path("data");
        final String evidenceNo = created.path("evidenceNo").asText();
        final String originalTxId = created.path("chainTxId").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .isEqualTo(1L);

        // 构造"② 账本已提交 / ③ 状态未提交"的崩溃窗口
        jdbc.update("UPDATE evidence_record SET status = 'PENDING', chain_tx_id = NULL, "
                + "chain_block_no = NULL WHERE evidence_no = ?", evidenceNo);

        final JsonNode body = retry(evidenceNo, 200);
        assertThat(body.path("data").path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(body.path("data").path("chainTxId").asText())
                .as("重放命中 uk_chain_payload，返回原回执").isEqualTo(originalTxId);
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .as("不产生第二块").isEqualTo(1L);
    }

    /** B4-4 / B2-3：已上链再重试 → 409 1012C0003 且零副作用。 */
    @Test
    void retryOnChainRecordIsConflictWithoutSideEffects() throws Exception {
        final String evidenceNo = create("DL000141").path("data").path("evidenceNo").asText();

        final JsonNode body = retry(evidenceNo, 409);
        assertThat(body.path("code").asText()).isEqualTo("1012C0003");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM evidence_record WHERE evidence_no = ?",
                Integer.class, evidenceNo)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_record_action_log WHERE evidence_no = ?",
                Long.class, evidenceNo)).isEqualTo(2L);
    }
}
