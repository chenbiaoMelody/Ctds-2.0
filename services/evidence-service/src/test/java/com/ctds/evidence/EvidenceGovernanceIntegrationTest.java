package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.FlakyChainGatewayConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
 * 治理读面与异常概览集成（hifi §9 T10 / T11；规格 B4-2 / B5-4 口径）：
 * ① 清单分页 + 过滤 + occurred_at 倒序；② 异常概览三计数与最近失败时点；
 * ③ 非治理档 → 403；④ 不存在编号 → 404 同码同文（防枚举）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import({FixedClockTestConfiguration.class, FlakyChainGatewayConfiguration.class})
class EvidenceGovernanceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String ADMIN = "S-admin-evidence";
    private static final String NOT_FOUND_MESSAGE = "存证记录不存在";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_gov_it");
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

    private JsonNode create(final String businessNo, final String occurredAt) throws Exception {
        return create("DELIVERY", businessNo, "DELIVERY_CREATED", occurredAt);
    }

    private JsonNode create(final String stageType, final String businessNo, final String operationType,
            final String occurredAt) throws Exception {
        final String body = "{\"stageType\":\"" + stageType + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operationType + "\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"" + occurredAt + "\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(final String path) throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T11 / B4-2：概览三计数与记录状态逐项一致，最近失败时点可读。 */
    @Test
    void overviewCountsMatchRecordStatuses() throws Exception {
        create("DL000191", "2026-11-05T05:30:00Z");
        create("DL000192", "2026-11-05T06:30:00Z");
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(true);
        create("DL000193", "2026-11-05T07:30:00Z");

        final JsonNode data = getJson("/api/v1/evidence/overview").path("data");
        assertThat(data.path("onChainCount").asLong()).isEqualTo(2L);
        assertThat(data.path("pendingCount").asLong()).isZero();
        assertThat(data.path("failedCount").asLong()).isEqualTo(1L);
        assertThat(data.path("lastFailedAt").asText()).as("最近失败时点可读")
                .isEqualTo("2026-11-05T13:30:00.000Z");

        final long actualFailed = jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_record WHERE status = 'FAILED'", Long.class);
        assertThat(data.path("failedCount").asLong()).as("与库内实际状态一致").isEqualTo(actualFailed);
    }

    /** T10 / B5-2 口径：清单按过滤条件分页、occurred_at 倒序、字段齐备。 */
    @Test
    void listFiltersPagesAndOrdersDesc() throws Exception {
        create("DL000201", "2026-11-05T05:30:00Z");
        create("DL000202", "2026-11-05T06:30:00Z");
        create("CONTRACT", "CO000203", "CONTRACT_EFFECTIVE", "2026-11-05T07:30:00Z");

        final JsonNode data = getJson(
                "/api/v1/evidence/records?stageType=DELIVERY&pageNum=1&pageSize=10").path("data");
        assertThat(data.path("total").asLong()).isEqualTo(2L);
        assertThat(data.path("list")).hasSize(2);
        assertThat(data.path("list").get(0).path("businessNo").asText())
                .as("occurred_at 倒序").isEqualTo("DL000202");
        assertThat(data.path("list").get(0).path("status").asText()).isEqualTo("ON_CHAIN");
        assertThat(data.path("list").get(0).path("contentDigest").asText()).hasSize(64);

        final JsonNode byBusinessNo = getJson(
                "/api/v1/evidence/records?businessNo=CO000203").path("data");
        assertThat(byBusinessNo.path("total").asLong()).isEqualTo(1L);
        assertThat(byBusinessNo.path("list").get(0).path("stageType").asText()).isEqualTo("CONTRACT");
    }

    /** T10 / B5-4 口径：非治理档 → 403（五个治理端点一致）。 */
    @Test
    void nonGovernanceRoleIsForbidden() throws Exception {
        final String evidenceNo = create("DL000211", "2026-11-05T05:30:00Z")
                .path("data").path("evidenceNo").asText();
        final String[] paths = {
            "/api/v1/evidence/records?stageType=DELIVERY",
            "/api/v1/evidence/records/" + evidenceNo,
            "/api/v1/evidence/overview",
        };
        for (final String path : paths) {
            mockMvc.perform(get(path)
                            .header("X-Ctds-Subject", "S-req-evidence")
                            .header("X-Ctds-Roles", "requester"))
                    .andExpect(status().isForbidden());
        }
        for (final String action : new String[] {"verify", "retry"}) {
            mockMvc.perform(post("/api/v1/evidence/records/" + evidenceNo + "/" + action)
                            .header("X-Ctds-Subject", "S-req-evidence")
                            .header("X-Ctds-Roles", "requester"))
                    .andExpect(status().isForbidden());
        }
    }

    /** T10 / B5-4：不存在编号 → 404 同码同文（详情 / 核验 / 重试三处一致）。 */
    @Test
    void unknownEvidenceNoIsNotFoundWithSameCodeAndMessage() throws Exception {
        final MvcResult detail = mockMvc.perform(get("/api/v1/evidence/records/EV20261105999999")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isNotFound()).andReturn();
        final MvcResult verify = mockMvc.perform(
                        post("/api/v1/evidence/records/EV20261105999999/verify")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isNotFound()).andReturn();
        final MvcResult retry = mockMvc.perform(
                        post("/api/v1/evidence/records/EV20261105999999/retry")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isNotFound()).andReturn();

        for (final MvcResult result : java.util.List.of(detail, verify, retry)) {
            final JsonNode body = MAPPER.readTree(
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("code").asText()).isEqualTo("1012C0001");
            assertThat(body.path("message").asText()).as("同码同文防枚举").isEqualTo(NOT_FOUND_MESSAGE);
        }
    }
}
