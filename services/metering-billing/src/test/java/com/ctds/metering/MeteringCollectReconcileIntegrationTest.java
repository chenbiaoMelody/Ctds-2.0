package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.metering.support.SharedMySqlContainer;
import com.ctds.metering.support.SourceSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 采集与四方勾稽集成（hifi §8 T3/T4/T7/T10）：交付腿排除 API 行（B7-3 不双计）、
 * 沙箱作业与导出各计一次（B7-4）、拒绝零计量结构性（B8-3 负向核对）、uk 重放零重计（Q8）。
 * 事件源表落同一容器实例的独立库（跨 schema 只读 SELECT 同实例语义）；Docker 缺失整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class MeteringCollectReconcileIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DELIVERY_DB = "ctds_metering_c1_delivery_it";
    private static final String CONTRACT_DB = "ctds_metering_c1_contract_it";
    private static final String ADMIN = "S-admin-m1";
    private static final String ADMIN_ROLES = "admin";
    private static final String REQUESTER = "S-req-m1";

    @DynamicPropertySource
    static void meteringDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_collect_it");
        registry.add("ctds.metering.source-schema.delivery", () -> DELIVERY_DB);
        registry.add("ctds.metering.source-schema.contract", () -> CONTRACT_DB);
    }

    @BeforeAll
    static void prepareSourceSchemas() {
        SourceSchemas.createDeliveryTables(SourceSchemas.jdbcTemplateFor(DELIVERY_DB));
        SourceSchemas.createContractTables(SourceSchemas.jdbcTemplateFor(CONTRACT_DB));
    }

    /** 用例隔离：采集为全量重扫语义，事件源行与流水行须逐用例清场（否则计数跨用例污染）。 */
    @BeforeEach
    void resetRows() {
        jdbcTemplate.update("DELETE FROM metering_flow");
        jdbcTemplate.update("DELETE FROM " + DELIVERY_DB + ".delivery");
        jdbcTemplate.update("DELETE FROM " + DELIVERY_DB + ".sandbox_job");
        jdbcTemplate.update("DELETE FROM " + DELIVERY_DB + ".sandbox_task");
        jdbcTemplate.update("DELETE FROM " + CONTRACT_DB + ".contract");
        jdbcTemplate.update("DELETE FROM " + CONTRACT_DB + ".contract_usage_log");
        jdbcTemplate.update("DELETE FROM " + CONTRACT_DB + ".contract_usage_counter");
    }

    @Autowired
    private MockMvc mockMvc;

    /** 应用数据源模板（连 ctds_metering 测试库）：种子写入经测试授权跨 schema 直达事件源库。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedContract(final String contractNo, final String requester,
            final String provider, final String pricingModel) {
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract "
                        + "(contract_no, requester_subject_no, provider_subject_no, pricing_model) "
                        + "VALUES (?, ?, ?, ?)", contractNo, requester, provider, pricingModel);
    }

    private void seedDelivery(final String deliveryNo, final String contractNo, final String type,
            final String result) {
        jdbcTemplate.update("INSERT INTO " + DELIVERY_DB + ".delivery "
                + "(delivery_no, contract_no, requester_no, delivery_type, result) "
                + "VALUES (?, ?, 'S-req-m1', ?, ?)", deliveryNo, contractNo, type, result);
    }

    private void seedSandboxJob(final String jobNo, final String sandboxNo, final String status) {
        jdbcTemplate.update("INSERT INTO " + DELIVERY_DB + ".sandbox_job "
                + "(job_no, sandbox_no, status) VALUES (?, ?, ?)", jobNo, sandboxNo, status);
        jdbcTemplate.update("INSERT INTO " + DELIVERY_DB + ".sandbox_task "
                + "(sandbox_no, contract_no, requester_no) VALUES (?, ?, 'S-req-m1')",
                sandboxNo, "CO200002");
    }

    private JsonNode postCollect() throws Exception {
        final MvcResult result = mockMvc.perform(post("/api/v1/metering/collect")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", ADMIN_ROLES))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private JsonNode getReconcile(final String contractNo) throws Exception {
        final MvcResult result = mockMvc.perform(
                        get("/api/v1/metering/contracts/" + contractNo + "/reconcile")
                                .header("X-Ctds-Subject", ADMIN)
                                .header("X-Ctds-Roles", ADMIN_ROLES))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private long flowCount(final String contractNo) {
        final Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metering_flow WHERE contract_no = ?", Long.class, contractNo);
        return count == null ? 0L : count;
    }

    /** T3 三方勾稽（hifi §8）：文件交付动作 ①=1 ②=1 ④=1（③ counter 同步）——各恰好 1 条不双计。 */
    @Test
    void fileDeliveryLegsExactlyOneEach() throws Exception {
        seedContract("CO200001", REQUESTER, "S-prov-m1", "PER_CALL");
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract_usage_log "
                + "(contract_no, outcome) VALUES ('CO200001', 'ALLOWED')");
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract_usage_counter "
                + "(contract_no, used_count) VALUES ('CO200001', 1)");
        seedDelivery("DL200001", "CO200001", "FILE", "SUCCESS");

        final JsonNode outcome = postCollect();
        assertThat(outcome.path("collectedTotal").asLong()).isEqualTo(1);
        assertThat(outcome.path("bySource").path("fileDelivery").path("scanned").asLong())
                .isEqualTo(1);
        assertThat(outcome.path("bySource").path("fileDelivery").path("collected").asLong())
                .isEqualTo(1);
        assertThat(outcome.path("bySource").path("sandboxExport").path("collected").asLong())
                .isEqualTo(0);
        assertThat(outcome.path("bySource").path("sandboxJob").path("collected").asLong())
                .isEqualTo(0);

        final JsonNode reconcile = getReconcile("CO200001");
        assertThat(reconcile.path("deliveryFileCount").asLong()).as("①交付文件腿").isEqualTo(1);
        assertThat(reconcile.path("usageLogAllowedCount").asLong()).as("②策略判定腿").isEqualTo(1);
        assertThat(reconcile.path("usageCounterUsedCount").asLong()).as("③余额口径").isEqualTo(1);
        assertThat(reconcile.path("meteringFlowCount").asLong()).as("④计量流水").isEqualTo(1);
    }

    /** B7-3 不双计：delivery_type=API 行被通道 2 排除（API 计量源唯一 = 网关事件）。 */
    @Test
    void apiDeliveryRowsExcludedFromChannelTwo() throws Exception {
        seedContract("CO200010", REQUESTER, "S-prov-m1", "PER_CALL");
        seedDelivery("DL200010", "CO200010", "API", "SUCCESS");

        final JsonNode outcome = postCollect();
        assertThat(outcome.path("bySource").path("fileDelivery").path("scanned").asLong())
                .as("API 行不进交付腿扫描").isEqualTo(0);
        assertThat(flowCount("CO200010")).isEqualTo(0);
    }

    /** T4 沙箱（hifi §8）：作业 1 + 导出 1 → 各计 1 条不叠加（ADR-025 单一导出源）。 */
    @Test
    void sandboxJobAndExportEachCountedOnce() throws Exception {
        seedContract("CO200002", REQUESTER, "S-prov-m1", "PER_CALL");
        seedSandboxJob("SJ200001", "SB200001", "SUCCEEDED");
        seedDelivery("DL200002", "CO200002", "SANDBOX_EXPORT", "SUCCESS");

        final JsonNode outcome = postCollect();
        assertThat(outcome.path("bySource").path("sandboxJob").path("collected").asLong())
                .isEqualTo(1);
        assertThat(outcome.path("bySource").path("sandboxExport").path("collected").asLong())
                .isEqualTo(1);
        assertThat(flowCount("CO200002")).as("两腿合计恰好 2 条").isEqualTo(2);
    }

    /** T4/T10 幂等：重复触发全量重扫、uk 兜底零重计（collected=0）。 */
    @Test
    void repeatCollectYieldsZeroNewRows() throws Exception {
        seedContract("CO200003", REQUESTER, "S-prov-m1", "PER_CALL");
        seedDelivery("DL200003", "CO200003", "FILE", "SUCCESS");

        assertThat(postCollect().path("collectedTotal").asLong()).isEqualTo(1);
        final JsonNode second = postCollect();
        assertThat(second.path("collectedTotal").asLong()).as("重复触发零新增").isEqualTo(0);
        assertThat(second.path("scannedTotal").asLong()).as("全量重扫").isEqualTo(1);
        assertThat(flowCount("CO200003")).isEqualTo(1);
    }

    /** T7 负向核对（hifi §8）：N 次放行 + 1 次越界 → ③=N ④=N（拒绝未触碰任何一侧）。
     *  ④ 腿 = 通道 1 网关事件行造数（API 计量源唯一 = 网关事件，采集通道 2 只管交付/沙箱腿）。 */
    @Test
    void deniedOutcomeTouchesNeitherCounterNorFlow() throws Exception {
        seedContract("CO200004", REQUESTER, "S-prov-m1", "PER_CALL");
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract_usage_counter "
                + "(contract_no, used_count) VALUES ('CO200004', 3)");
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract_usage_log "
                + "(contract_no, outcome) VALUES ('CO200004', 'ALLOWED'), ('CO200004', 'ALLOWED'),"
                + " ('CO200004', 'ALLOWED'), ('CO200004', 'DENIED')");
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("INSERT INTO metering_flow (contract_no, requester_no, "
                            + "source_type, source_no, action, occurred_at) "
                            + "VALUES ('CO200004', 'S-req-m1', 'GATEWAY_API', ?, "
                            + "'gateway.delivery.metering', '2026-10-10 09:00:00')",
                    "evt-t7-" + i);
        }

        postCollect();
        final JsonNode reconcile = getReconcile("CO200004");
        assertThat(reconcile.path("usageLogAllowedCount").asLong())
                .as("② 只数 ALLOWED（DENIED 留痕独立不混算）").isEqualTo(3);
        assertThat(reconcile.path("usageCounterUsedCount").asLong()).as("③ 余额口径").isEqualTo(3);
        assertThat(reconcile.path("meteringFlowCount").asLong()).as("④ 计量流水").isEqualTo(3);
        assertThat(reconcile.path("deliveryFileCount").asLong()).isEqualTo(0);
    }

    /** 拒绝零计量结构性（B7-2/B8-3）：非 SUCCESS 侧源行结构性不进采集查询。 */
    @Test
    void nonSuccessSourceRowsExcludedStructurally() throws Exception {
        seedContract("CO200005", REQUESTER, "S-prov-m1", "PER_CALL");
        seedDelivery("DL200005", "CO200005", "FILE", "FAILED");
        seedSandboxJob("SJ200005", "SB200005", "FAILED");

        final JsonNode outcome = postCollect();
        assertThat(outcome.path("bySource").path("fileDelivery").path("scanned").asLong())
                .isEqualTo(0);
        assertThat(outcome.path("bySource").path("sandboxJob").path("scanned").asLong())
                .isEqualTo(0);
        assertThat(flowCount("CO200005")).isEqualTo(0);
    }

    /** 权限面（hifi §6）：采集 = metering.governance（仅 admin），普通档 403。 */
    @Test
    void collectRequiresGovernancePermission() throws Exception {
        mockMvc.perform(post("/api/v1/metering/collect")
                        .header("X-Ctds-Subject", REQUESTER)
                        .header("X-Ctds-Roles", "requester"))
                .andExpect(status().isForbidden());
    }
}
