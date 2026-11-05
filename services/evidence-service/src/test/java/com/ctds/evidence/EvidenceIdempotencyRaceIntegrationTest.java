package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.RacingRepositoryConfiguration;
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
 * 幂等双保险的并发兜底分支（P2-2 修复批；规格 B2-2 / hifi §4）：
 * 预检未命中 + 唯一键冲突 → 回查既有记录返回 {@code duplicate=true}，零新行 / 零新留痕 / 零新账本块。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import({FixedClockTestConfiguration.class, RacingRepositoryConfiguration.class})
class EvidenceIdempotencyRaceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_race_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        RacingRepositoryConfiguration.PRECHECK_MISS_ONCE.set(false);
        RacingRepositoryConfiguration.DUPLICATE_INSERT_ONCE.set(false);
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

    /** P2-2 / B2-2：预检漏过 + insert 唯一键冲突 → 回查原记录，duplicate=true，零新增。 */
    @Test
    void duplicateKeyFallbackReturnsExistingRecord() throws Exception {
        final JsonNode first = create("DL000211").path("data");
        final String evidenceNo = first.path("evidenceNo").asText();
        final String chainTxId = first.path("chainTxId").asText();
        assertThat(first.path("duplicate").asBoolean()).isFalse();

        RacingRepositoryConfiguration.PRECHECK_MISS_ONCE.set(true);
        RacingRepositoryConfiguration.DUPLICATE_INSERT_ONCE.set(true);

        final JsonNode second = create("DL000211");
        assertThat(second.path("code").asText()).isEqualTo("0");
        assertThat(second.path("data").path("duplicate").asBoolean())
                .as("并发兜底返回既有记录").isTrue();
        assertThat(second.path("data").path("evidenceNo").asText()).isEqualTo(evidenceNo);
        assertThat(second.path("data").path("chainTxId").asText()).isEqualTo(chainTxId);
        assertThat(second.path("data").path("status").asText()).isEqualTo("ON_CHAIN");

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_record", Long.class))
                .as("唯一键兜底零新行").isEqualTo(1L);
        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log ORDER BY id", String.class);
        assertThat(actions).as("零新留痕").containsExactly("CREATED", "CHAIN_SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_chain_block", Long.class))
                .as("零新账本块").isEqualTo(1L);
    }
}
