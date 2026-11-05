package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.evidence.support.FailingStateWriterConfiguration;
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
 * 状态写入失败（P2-3 修复批；hifi §4 事务口径）：上链成功后第三段事务失败，
 * 不得把"链已成功"伪造成 {@code FAILED}——直接按 500 + 1012S0002 上抛，
 * 记录停在旧状态（可重试恢复），且不追加 {@code CHAIN_FAILED} 留痕。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import({FixedClockTestConfiguration.class, FailingStateWriterConfiguration.class})
class EvidenceStateWriteFailureIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_state_failure_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        FailingStateWriterConfiguration.FAIL_NEXT.set(false);
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
    }

    private MvcResult create(final String businessNo) throws Exception {
        final String body = "{\"stageType\":\"DELIVERY\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"DELIVERY_CREATED\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T13:30:00+08:00\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        return mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andReturn();
    }

    /** P2-3：链成功但状态写失败 → 500 1012S0002；记录不得被写成 FAILED，无 CHAIN_FAILED 留痕。 */
    @Test
    void stateWriteFailureDoesNotFakeChainFailure() throws Exception {
        FailingStateWriterConfiguration.FAIL_NEXT.set(true);

        final MvcResult result = create("DL000201");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("1012S0002");
        assertThat(body.path("message").asText()).isEqualTo("存证服务暂不可用，请稍后重试");

        final List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT status, last_error_summary, chain_tx_id FROM evidence_record");
        assertThat(records).as("记录仍存在（第一段事务已提交）").hasSize(1);
        assertThat(records.get(0).get("status")).as("不得伪报上链失败").isEqualTo("PENDING");
        assertThat(records.get(0).get("last_error_summary")).as("不得写失败原因").isNull();

        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log ORDER BY id", String.class);
        assertThat(actions).as("只有 CREATED；无 CHAIN_SUCCEEDED / CHAIN_FAILED")
                .containsExactly("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .as("链其实已成功写入一块（后续重试命中 uk_chain_payload 复用回执）").isEqualTo(1L);
    }
}
