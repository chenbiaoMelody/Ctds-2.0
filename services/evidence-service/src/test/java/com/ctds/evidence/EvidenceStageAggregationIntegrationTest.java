package com.ctds.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.evidence.support.FailingStateWriterConfiguration;
import com.ctds.evidence.support.FixedClockTestConfiguration;
import com.ctds.evidence.support.FlakyChainGatewayConfiguration;
import com.ctds.evidence.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 四环节聚合与幂等读面（WBS-3.8.6 hifi §1 T1 / T2 / T3 / T6；hifi §9 E1 / E2 勘误后口径）：
 *
 * <ol>
 *   <li><b>T1</b>：混合四环节多状态数据 → {@code /api/v1/evidence/audit/overview} 矩阵逐格与
 *       {@code evidence_record} 按 {@code (stage_type, status)} 分组计数**逐项相等**（非硬编码期望）；</li>
 *   <li><b>T2</b>：正常预检命中路径重复写 → {@code duplicate=true} + 读面矩阵与异常计数零变化；</li>
 *   <li><b>T3</b>：库内零记录 → 四环节键存在且三状态均为 0（不出现 null / 缺键，矩阵形状恒定）；</li>
 *   <li><b>T6</b>：{@code chain_tx_id IS NULL} 的记录（PENDING）→ 矩阵 PENDING 列 +1、
 *       {@code anomalies.pendingCount} +1、**{@code mismatchCount} 不变**（不被误判为核验不一致）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import({FixedClockTestConfiguration.class, FlakyChainGatewayConfiguration.class,
        FailingStateWriterConfiguration.class})
class EvidenceStageAggregationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTERNAL_PATH = "/api/v1/evidence/internal/records";
    private static final String AUDIT_OVERVIEW = "/api/v1/evidence/audit/overview";
    private static final String AUDIT_ANOMALIES = "/api/v1/evidence/audit/anomalies";
    private static final String ADMIN = "S-admin-evidence";
    private static final List<String> STAGES =
            List.of("AUTHORIZATION", "CONTRACT", "DELIVERY", "USAGE");
    private static final List<String> STATUSES = List.of("PENDING", "ON_CHAIN", "FAILED");

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_evidence_stage_agg_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(false);
        FailingStateWriterConfiguration.FAIL_NEXT.set(false);
        jdbc.update("DELETE FROM evidence_record_action_log");
        jdbc.update("DELETE FROM evidence_record");
        jdbc.update("DELETE FROM evidence_chain_block");
        jdbc.update("DELETE FROM evidence_no_seq");
        jdbc.update("DELETE FROM evidence_access_log");
        jdbc.update("DELETE FROM evidence_audit_report");
        jdbc.update("DELETE FROM evidence_report_no_seq");
    }

    /** T1 / E1：四环节混合状态 → 矩阵与库内分组计数逐项对账（矩阵键恰四环节）。 */
    @Test
    void overviewMatrixMatchesDatabaseGroupByCounts() throws Exception {
        create("AUTHORIZATION", "SPACE7001:ADMISSION1", "SPACE_MEMBER_ADMITTED");
        create("CONTRACT", "CO000701", "CONTRACT_EFFECTIVE");
        create("DELIVERY", "DL000701", "DELIVERY_CREATED");
        create("DELIVERY", "DL000702", "DELIVERY_CREATED");
        FlakyChainGatewayConfiguration.FAIL_NEXT.set(true);
        create("USAGE", "USG000000000701", "USAGE_ALLOWED");
        createPendingRecord("USAGE", "USG000000000702", "USAGE_ALLOWED");
        create("USAGE", "USG000000000703", "USAGE_ALLOWED");

        final Map<String, Long> expected = new LinkedHashMap<>();
        for (final Map<String, Object> row : jdbc.queryForList(
                "SELECT stage_type, status, COUNT(1) AS cnt FROM evidence_record "
                        + "GROUP BY stage_type, status")) {
            expected.put(row.get("stage_type") + "/" + row.get("status"),
                    ((Number) row.get("cnt")).longValue());
        }
        assertThat(expected).as("造数分布前置确认").hasSize(6);

        final JsonNode matrix = getJson(AUDIT_OVERVIEW).path("data").path("matrix");
        final List<String> stages = new ArrayList<>();
        matrix.fieldNames().forEachRemaining(stages::add);
        assertThat(stages).as("矩阵键恰为四环节（形状恒定）")
                .containsExactlyInAnyOrderElementsOf(STAGES);

        for (final String stage : STAGES) {
            for (final String status : STATUSES) {
                assertThat(matrix.path(stage).has(status))
                        .as("矩阵格 %s/%s 键存在（缺键即红）", stage, status).isTrue();
                assertThat(matrix.path(stage).path(status).asLong())
                        .as("矩阵格 %s/%s 与库内分组计数逐项相等", stage, status)
                        .isEqualTo(expected.getOrDefault(stage + "/" + status, 0L));
            }
        }
        assertThat(matrix.path("DELIVERY").path("ON_CHAIN").asLong()).isEqualTo(2L);
        assertThat(matrix.path("USAGE").path("FAILED").asLong()).isEqualTo(1L);
        assertThat(matrix.path("USAGE").path("PENDING").asLong()).isEqualTo(1L);
    }

    /** T2 / E5：正常预检命中路径重复写 → duplicate=true 且读面矩阵与异常计数零变化。 */
    @Test
    void duplicateWriteKeepsReadFaceCountsUnchanged() throws Exception {
        final JsonNode first = create("DELIVERY", "DL000711", "DELIVERY_CREATED").path("data");
        assertThat(first.path("duplicate").asBoolean()).isFalse();
        final JsonNode before = getJson(AUDIT_OVERVIEW).path("data");

        final JsonNode second = create("DELIVERY", "DL000711", "DELIVERY_CREATED").path("data");
        assertThat(second.path("duplicate").asBoolean()).as("正常预检命中 = 幂等返回").isTrue();
        assertThat(second.path("evidenceNo").asText())
                .as("返回既有记录编号").isEqualTo(first.path("evidenceNo").asText());

        final JsonNode after = getJson(AUDIT_OVERVIEW).path("data");
        assertThat(after.path("matrix")).as("重复写矩阵零变化（计数不 +1）")
                .isEqualTo(before.path("matrix"));
        assertThat(after.path("anomalies")).as("重复写异常计数零变化")
                .isEqualTo(before.path("anomalies"));
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM evidence_record", Long.class))
                .as("库内仍 1 条").isEqualTo(1L);
    }

    /** T3：零记录 → 四环节键全部存在且三状态均为 0（不出现 null / 缺键）。 */
    @Test
    void emptyDatabaseExposesFourStageKeysWithZeroCounts() throws Exception {
        final JsonNode data = getJson(AUDIT_OVERVIEW).path("data");
        final JsonNode matrix = data.path("matrix");

        final List<String> stages = new ArrayList<>();
        matrix.fieldNames().forEachRemaining(stages::add);
        assertThat(stages).as("零记录时四环节键仍存在").containsExactlyInAnyOrderElementsOf(STAGES);
        for (final String stage : STAGES) {
            for (final String status : STATUSES) {
                assertThat(matrix.path(stage).has(status))
                        .as("矩阵格 %s/%s 键存在（缺键即红，非 MissingNode 假通过）", stage, status)
                        .isTrue();
                assertThat(matrix.path(stage).path(status).isNull())
                        .as("矩阵格 %s/%s 不出现 null", stage, status).isFalse();
                assertThat(matrix.path(stage).path(status).asLong())
                        .as("矩阵格 %s/%s 零记录为 0", stage, status).isZero();
            }
        }
        final JsonNode anomalies = data.path("anomalies");
        assertThat(anomalies.path("pendingCount").asLong()).isZero();
        assertThat(anomalies.path("failedCount").asLong()).isZero();
        assertThat(anomalies.path("mismatchCount").asLong()).isZero();
        assertThat(data.path("snapshotAt").asText()).as("快照时点可读").isNotBlank();
    }

    /** T6 / E2：txId 为空的 PENDING 记录 → 待上链呈现，不计为核验不一致。
     * P2-15 修复：PENDING 经真实写面确定性构造（FailingStateWriterConfiguration：
     * 上链成功后状态写入失败 → 记录停 PENDING、chain_tx_id 空），非直插夹具。 */
    @Test
    void pendingRecordWithNullTxIdCountsAsPendingNotMismatch() throws Exception {
        createPendingRecord("USAGE", "USG000000000721", "USAGE_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT status FROM evidence_record", String.class))
                .as("真实写面构造的记录停 PENDING").isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT chain_tx_id FROM evidence_record", String.class))
                .as("状态写失败 → chain_tx_id 空").isNull();

        final JsonNode data = getJson(AUDIT_OVERVIEW).path("data");
        final JsonNode matrix = data.path("matrix");
        assertThat(matrix.path("USAGE").path("PENDING").asLong()).isEqualTo(1L);
        assertThat(matrix.path("USAGE").path("ON_CHAIN").asLong()).isZero();
        assertThat(matrix.path("USAGE").path("FAILED").asLong()).isZero();

        final JsonNode anomalies = data.path("anomalies");
        assertThat(anomalies.path("pendingCount").asLong()).as("待上链计入异常计数").isEqualTo(1L);
        assertThat(anomalies.path("failedCount").asLong()).isZero();
        assertThat(anomalies.path("mismatchCount").asLong())
                .as("txId 为空不等于核验不一致（不计 mismatch）").isZero();

        final JsonNode list = getJson(AUDIT_ANOMALIES + "?pageSize=10").path("data");
        assertThat(list.path("total").asLong()).isEqualTo(1L);
        final JsonNode item = list.path("list").get(0);
        assertThat(item.path("anomalyType").asText()).isEqualTo("PENDING");
        assertThat(item.path("status").asText()).isEqualTo("PENDING");
        assertThat(item.path("chainTxId").isNull()).as("待上链记录无链上标识").isTrue();
    }

    private static String writeBody(final String stageType, final String businessNo,
            final String operationType) {
        return "{\"stageType\":\"" + stageType + "\",\"businessNo\":\"" + businessNo
                + "\",\"operationType\":\"" + operationType + "\""
                + ",\"subjectDid\":\"did:ctds:provider-0001\""
                + ",\"occurredAt\":\"2026-11-05T05:30:00Z\""
                + ",\"actorSubjectNo\":\"S20260922000001\"}";
    }

    private JsonNode create(final String stageType, final String businessNo,
            final String operationType) throws Exception {
        final MvcResult result = mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeBody(stageType, businessNo, operationType))
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** P2-15：经真实写面确定性构造 PENDING（上链成功后状态写入失败 → 500 1012S0002，
     * 记录停 PENDING、chain_tx_id 空；FailingStateWriterConfiguration 消费一次即复位）。 */
    private void createPendingRecord(final String stageType, final String businessNo,
            final String operationType) throws Exception {
        FailingStateWriterConfiguration.FAIL_NEXT.set(true);
        mockMvc.perform(post(INTERNAL_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeBody(stageType, businessNo, operationType))
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", "evidence-internal"))
                .andExpect(status().isInternalServerError());
    }

    private JsonNode getJson(final String path) throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
