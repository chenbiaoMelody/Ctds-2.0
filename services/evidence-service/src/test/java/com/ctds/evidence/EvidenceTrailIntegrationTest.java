package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.AuditStubServer;
import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * 全链时间线与日志调阅（WBS-3.8.4 hifi §8 T4~T11 / T19~T20 客户端面；规格 B5-2③ / B6-1~B6-3；
 * 剧本 S1-2~S1-6）：单据号命中 = 存证事件 + 跨域留痕聚合（统一脱敏）；其余条件 = 清单模式；
 * 条件与时间窗硬约束沿 3.8.2 同值；成功调阅落 1 条调阅留痕；跨域不可达 = 显式 UNAVAILABLE 降级。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(FixedClockTestConfiguration.class)
class EvidenceTrailIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String AUDIT_TRAILS = "/api/v1/evidence/audit/trails";
    private static final String ADMIN = "S-admin-evidence";
    private static final String PROVIDER_DID = "did:ctds:provider-0001";

    private static final AuditStubServer STUB;

    static {
        try {
            STUB = new AuditStubServer();
        } catch (final IOException e) {
            throw new IllegalStateException("跨域桩启动失败", e);
        }
    }

    @DynamicPropertySource
    static void stubsAndDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_audit_trail_it");
        registry.add("ctds.evidence.trail.space-base-url", STUB::baseUrl);
        registry.add("ctds.evidence.trail.contract-base-url", STUB::baseUrl);
        registry.add("ctds.evidence.trail.delivery-base-url", STUB::baseUrl);
    }

    @BeforeAll
    static void resetStub() {
        STUB.FAIL_SPACE.set(false);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        STUB.FAIL_SPACE.set(false);
        STUB.resetCalls();
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
        jdbc.update("DELETE FROM evidence_access_log");
        jdbc.update("DELETE FROM evidence_audit_report");
        jdbc.update("DELETE FROM evidence_report_no_seq");
    }

    private JsonNode create(final String stageType, final String businessNo,
            final String operationType, final String subjectDid) throws Exception {
        return create(stageType, businessNo, operationType, subjectDid, "2026-11-05T05:30:00Z");
    }

    private JsonNode create(final String stageType, final String businessNo,
            final String operationType, final String subjectDid, final String occurredAt)
            throws Exception {
        final String body = "{\"stageType\":\"" + stageType + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operationType + "\""
                + ",\"subjectDid\":\"" + subjectDid + "\""
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

    private JsonNode create(final String stageType, final String businessNo,
            final String operationType) throws Exception {
        return create(stageType, businessNo, operationType, PROVIDER_DID);
    }

    private JsonNode auditGet(final String path) throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T4 / B5-2③ / S1-2·S1-4：CO 号时间线 = 存证 + 合约域留痕内联 + DID 摘要对齐。 */
    @Test
    void contractBusinessNoReturnsAggregatedTrail() throws Exception {
        final JsonNode created = create("CONTRACT", "CO000123", "CONTRACT_EFFECTIVE")
                .path("data");
        final String storedDigest = created.path("subjectDidDigest").asText();

        final JsonNode data = auditGet(AUDIT_TRAILS + "?businessNo=CO000123").path("data");
        assertThat(data.path("mode").asText()).isEqualTo("TRAIL");
        assertThat(data.path("evidenceRecord").path("businessNo").asText()).isEqualTo("CO000123");
        assertThat(data.path("evidenceActions")).isNotEmpty();
        assertThat(data.path("timelineAvailable").asBoolean()).isTrue();

        boolean hasContractNode = false;
        for (final JsonNode node : data.path("businessTrail")) {
            if ("CONTRACT".equals(node.path("source").asText())
                    && "OK".equals(node.path("sourceStatus").asText())) {
                hasContractNode = true;
                assertThat(node.path("occurredAt").asText()).isNotBlank();
                assertThat(node.path("summary").asText()).isNotBlank();
            }
        }
        assertThat(hasContractNode).as("合约域留痕节点内联").isTrue();
        assertThat(data.toString()).as("跨域 DID 原文不出站（P1 摘要对齐）")
                .doesNotContain("did:ctds:")
                .doesNotContain("S20260922000001");
        assertThat(STUB.hits("contract")).as("按需调用合约域内部端点一次").isEqualTo(1);
        assertThat(STUB.lastRoles("contract")).as("T20 客户端以 evidence-internal 身份调用")
                .isEqualTo("evidence-internal");
    }

    /** T5 / B5-2③：DL 号时间线 = 交付域留痕内联，操作人主体号摘要化不出站。 */
    @Test
    void deliveryBusinessNoReturnsDeliveryTrail() throws Exception {
        create("DELIVERY", "DL000123", "DELIVERY_CREATED");
        final JsonNode data = auditGet(AUDIT_TRAILS + "?businessNo=DL000123").path("data");
        assertThat(data.path("mode").asText()).isEqualTo("TRAIL");
        boolean hasDeliveryNode = false;
        for (final JsonNode node : data.path("businessTrail")) {
            if ("DELIVERY".equals(node.path("source").asText())) {
                hasDeliveryNode = true;
                assertThat(node.path("sourceStatus").asText()).isEqualTo("OK");
            }
        }
        assertThat(hasDeliveryNode).as("交付域留痕节点内联").isTrue();
        assertThat(data.toString()).doesNotContain("S20260922000001");
        assertThat(STUB.hits("delivery")).isEqualTo(1);
    }

    /** T6 / B5-2③：USG 号时间线 = 使用放行留痕内联，requesterNo 摘要化。 */
    @Test
    void usageBusinessNoReturnsUsageTrail() throws Exception {
        create("USAGE", "USG000000000123", "USAGE_ALLOWED");
        final JsonNode data = auditGet(AUDIT_TRAILS + "?businessNo=USG000000000123").path("data");
        assertThat(data.path("mode").asText()).isEqualTo("TRAIL");
        boolean hasUsageNode = false;
        for (final JsonNode node : data.path("businessTrail")) {
            if ("USE".equals(node.path("action").asText())) {
                hasUsageNode = true;
                assertThat(node.path("result").asText()).isEqualTo("ALLOWED");
                assertThat(node.path("actorDigest").asText()).hasSize(64);
            }
        }
        assertThat(hasUsageNode).as("使用放行留痕节点内联").isTrue();
        assertThat(data.toString()).doesNotContain("S20260922000002");
        assertThat(STUB.hits("usage")).isEqualTo(1);
    }

    /** T7 / B5-2③：SPACE 两类锚点（ADMISSION / POLICY-AL）反解正确。 */
    @Test
    void spaceBusinessNosRouteBothAnchors() throws Exception {
        create("AUTHORIZATION", "SPACE1:ADMISSION5", "SPACE_MEMBER_ADMITTED");
        create("AUTHORIZATION", "SPACE1:POLICY-AL9", "SPACE_POLICY_INHERITED");

        final JsonNode admission = auditGet(AUDIT_TRAILS + "?businessNo=SPACE1:ADMISSION5")
                .path("data");
        assertThat(admission.path("mode").asText()).isEqualTo("TRAIL");
        assertThat(containsSpaceOkNode(admission)).isTrue();

        final JsonNode policy = auditGet(AUDIT_TRAILS + "?businessNo=SPACE1:POLICY-AL9")
                .path("data");
        assertThat(policy.path("mode").asText()).isEqualTo("TRAIL");
        assertThat(containsSpaceOkNode(policy)).isTrue();
        assertThat(STUB.hits("space")).as("两次 SPACE 锚点各调一次").isEqualTo(2);
        assertThat(admission.toString()).doesNotContain("S20260922000001");
    }

    private static boolean containsSpaceOkNode(final JsonNode data) {
        for (final JsonNode node : data.path("businessTrail")) {
            if ("SPACE".equals(node.path("source").asText())
                    && "OK".equals(node.path("sourceStatus").asText())) {
                return true;
            }
        }
        return false;
    }

    /** T8 / B5-2① / S1-3：subjectDigest 等值过滤命中 / 不命中（清单模式）。 */
    @Test
    void subjectDigestFilterMatchesExactly() throws Exception {
        create("DELIVERY", "DL000401", "DELIVERY_CREATED");
        create("DELIVERY", "DL000402", "DELIVERY_CREATED");
        create("DELIVERY", "DL000403", "DELIVERY_CREATED", "did:ctds:other-000001");

        final String digest = jdbc.queryForObject(
                "SELECT subject_did_digest FROM evidence_record WHERE business_no = 'DL000401'",
                String.class);
        final JsonNode hit = auditGet(AUDIT_TRAILS + "?subjectDigest=" + digest).path("data");
        assertThat(hit.path("mode").asText()).isEqualTo("LIST");
        assertThat(hit.path("total").asLong()).as("等值命中同主体两条").isEqualTo(2L);
        for (final JsonNode item : hit.path("list")) {
            assertThat(item.path("subjectDidDigest").asText()).isEqualTo(digest);
        }
        assertThat(hit.path("trailHint").asText()).as("清单模式附展开提示").isNotBlank();

        // 不命中 = 合法格式（64 位小写 hex）但不匹配任何记录 → 空清单（非法格式走 1012C0002 拒绝）
        final JsonNode miss = auditGet(AUDIT_TRAILS + "?subjectDigest=" + "f".repeat(64))
                .path("data");
        assertThat(miss.path("total").asLong()).as("不命中返回空清单").isZero();
    }

    /** T8 补强 / B5-2① / S1-3（P2-16）：时间窗 from 下界排除——窗前 / 窗后记录均不计入清单。 */
    @Test
    void timeWindowExcludesRecordsOutsideBounds() throws Exception {
        create("DELIVERY", "DL000501", "DELIVERY_CREATED", PROVIDER_DID, "2026-11-05T02:00:00Z");
        create("DELIVERY", "DL000502", "DELIVERY_CREATED", PROVIDER_DID, "2026-11-05T05:30:00Z");
        create("DELIVERY", "DL000503", "DELIVERY_CREATED", PROVIDER_DID, "2026-11-05T09:00:00Z");

        final JsonNode data = auditGet(AUDIT_TRAILS
                + "?stageType=DELIVERY&from=2026-11-05T04:00:00Z&to=2026-11-05T06:00:00Z"
                + "&pageNum=1&pageSize=10").path("data");
        assertThat(data.path("mode").asText()).isEqualTo("LIST");
        assertThat(data.path("total").asLong())
                .as("仅窗内一条（窗前 DL000501 / 窗后 DL000503 均排除）").isEqualTo(1L);
        assertThat(data.path("list").get(0).path("businessNo").asText()).isEqualTo("DL000502");
    }

    /** T9 / B6-2 / S1-5①：无条件调阅拒 → 1012C0002。 */
    @Test
    void trailsWithoutConditionRejected() throws Exception {
        final MvcResult result = mockMvc.perform(get(AUDIT_TRAILS)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isBadRequest()).andReturn();
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("1012C0002");
    }

    /** T10 / B6-2 / S1-5②：时间窗 >90 天拒 → 1012C0002（沿 3.8.2 同值）。 */
    @Test
    void trailsWithWideWindowRejected() throws Exception {
        final MvcResult result = mockMvc.perform(get(AUDIT_TRAILS
                        + "?from=2026-08-01T00:00:00Z&to=2026-11-05T13:30:00Z")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isBadRequest()).andReturn();
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("1012C0002");
    }

    /** T9 补强 / B6-2：审计侧分页超限（pageSize=101 > 上限 100）→ 1012C0002（清单模式新校验路径）。 */
    @Test
    void trailsWithPageSizeOverLimitRejected() throws Exception {
        final MvcResult result = mockMvc.perform(get(
                        AUDIT_TRAILS + "?stageType=DELIVERY&pageNum=1&pageSize=101")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isBadRequest()).andReturn();
        final JsonNode body = MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo("1012C0002");
    }

    /** T11 / B6-3 / S1-6：成功调阅落 1 条留痕（调阅人 / 时点 / 条件 / 返回条数）。 */
    @Test
    void successfulAccessWritesAccessLog() throws Exception {
        create("CONTRACT", "CO000123", "CONTRACT_EFFECTIVE");
        auditGet(AUDIT_TRAILS + "?businessNo=CO000123");
        create("DELIVERY", "DL000401", "DELIVERY_CREATED");
        auditGet(AUDIT_TRAILS + "?stageType=DELIVERY&pageNum=1&pageSize=10");

        final Long rows = jdbc.queryForObject(
                "SELECT COUNT(1) FROM evidence_access_log WHERE access_kind = 'TRAIL_ACCESS'",
                Long.class);
        assertThat(rows).as("两种模式各落 1 条调阅留痕").isEqualTo(2L);
        final String actor = jdbc.queryForObject(
                "SELECT accessed_by FROM evidence_access_log ORDER BY id ASC LIMIT 1", String.class);
        assertThat(actor).isEqualTo(ADMIN);
        final String conditions = jdbc.queryForObject(
                "SELECT conditions_json FROM evidence_access_log ORDER BY id ASC LIMIT 1",
                String.class);
        assertThat(conditions).as("调阅条件留痕含单据号").contains("CO000123");
        final Long resultCount = jdbc.queryForObject(
                "SELECT result_count FROM evidence_access_log ORDER BY id ASC LIMIT 1", Long.class);
        assertThat(resultCount).as("返回条数留痕").isPositive();
    }

    /** T19 / B2-1 fail-visible 同型：跨域不可达 → 显式 UNAVAILABLE，时间线其余段正常。 */
    @Test
    void unavailableDomainDegradesExplicitly() throws Exception {
        create("AUTHORIZATION", "SPACE1:ADMISSION5", "SPACE_MEMBER_ADMITTED");
        STUB.FAIL_SPACE.set(true);

        final JsonNode data = auditGet(AUDIT_TRAILS + "?businessNo=SPACE1:ADMISSION5").path("data");
        assertThat(data.path("mode").asText()).isEqualTo("TRAIL");
        assertThat(data.path("evidenceRecord").path("businessNo").asText())
                .as("存证侧正常返回").isEqualTo("SPACE1:ADMISSION5");
        assertThat(data.path("timelineAvailable").asBoolean()).as("不伪装连续").isFalse();
        boolean hasUnavailable = false;
        for (final JsonNode node : data.path("businessTrail")) {
            if ("UNAVAILABLE".equals(node.path("sourceStatus").asText())) {
                hasUnavailable = true;
                assertThat(node.path("summary").asText()).isNotBlank();
            }
        }
        assertThat(hasUnavailable).as("降级段显式标记").isTrue();
    }
}
