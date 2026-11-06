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
 * 核验遇链不可达（hifi §10 E2；规格 B3-1 / B4-1）：链关闭时，本地状态为 {@code ON_CHAIN}
 * 的记录核验必须映射 503 + 1012S0001（不伪报 MISMATCH / NOT_ON_CHAIN，不吞异常）。
 *
 * <p>构造口径（Q7-A 无运行期后门）：以 {@code ctds.evidence.chain.enabled=false} 常驻 +
 * 受控 SQL 把记录置为 {@code ON_CHAIN}（模拟"此前上链成功、当前链不可达"的演示场景）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.evidence.chain.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceVerifyChainUnavailableIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String ADMIN = "S-admin-evidence";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_verify_unavailable_it");
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

    /** 链关闭下创建 → FAILED；受控 SQL 置 ON_CHAIN 并填交易标识（模拟历史上链成功）。 */
    private String createOnChainRecordByControlledSql(final String businessNo) throws Exception {
        final String body = "{\"stageType\":\"DELIVERY\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"DELIVERY_CREATED\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T13:30:00+08:00\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        final MvcResult created = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk())
                .andReturn();
        final String evidenceNo = MAPPER.readTree(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("evidenceNo").asText();
        jdbc.update("UPDATE evidence_record SET status = 'ON_CHAIN', chain_tx_id = ?, "
                + "chain_block_no = 1 WHERE evidence_no = ?", "LTX000000000001", evidenceNo);
        return evidenceNo;
    }

    /** P1-1 / B3-1：ON_CHAIN 记录 + 链不可达 → 503 + 1012S0001 + 常量文案。 */
    @Test
    void verifyOnChainRecordWhenChainUnavailableReturns503() throws Exception {
        final String evidenceNo = createOnChainRecordByControlledSql("DL000191");
        final Map<String, Object> before = jdbc.queryForMap(
                "SELECT * FROM evidence_record WHERE evidence_no = ?", evidenceNo);

        final MvcResult result = mockMvc.perform(
                        post("/api/v1/evidence/records/" + evidenceNo + "/verify")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(body.path("code").asText()).as("错误码体系不被绕过").isEqualTo("1012S0001");
        assertThat(body.path("message").asText()).isEqualTo("存证链暂不可用，请稍后重试");

        final Map<String, Object> after = jdbc.queryForMap(
                "SELECT * FROM evidence_record WHERE evidence_no = ?", evidenceNo);
        assertThat(after).as("核验失败不改记录").isEqualTo(before);

        final List<String> actions = jdbc.queryForList(
                "SELECT action FROM evidence_record_action_log WHERE evidence_no = ? ORDER BY id",
                String.class, evidenceNo);
        assertThat(actions).as("核验失败不留 VERIFIED 留痕（hifi §10 E2）")
                .containsExactly("CREATED", "CHAIN_FAILED");
    }
}
