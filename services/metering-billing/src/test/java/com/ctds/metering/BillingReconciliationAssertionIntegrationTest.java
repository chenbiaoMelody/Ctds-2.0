package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.metering.support.BillingSourceSchemas;
import com.ctds.metering.support.FixedClockTestConfiguration;
import com.ctds.metering.support.SharedMySqlContainer;
import com.ctds.metering.support.SourceSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 对账专项断言（WBS-3.7.8 hifi §1-B；规格 B11 硬约束四条验收逐条建锚）：
 * ① 正向四方数值逐项一致 + 留痕；② 七类差异逐类可定位（含 {@code EXTRA_IN_BILL} 补覆盖腿）；
 * ③ 差异 fail-closed 冻结 → 确认 409 → 还原解冻 → 确认 200；④ 对账零自动改数（前后快照对照）；
 * ⑤ 差异未澄清重复对账不叠加（E11 幂等口径）。
 *
 * <p>差异构造一律只在测试容器库内完成（演示库零触碰）：每条负向用例都构造成"仅目标腿命中"，
 * 断言 {@code differences} 恰 1 项，防"腿恒跳过"或"比较写反"仍绿。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.billing.auto.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Import(FixedClockTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BillingReconciliationAssertionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_DB = "ctds_billing_recon_assert_contract_it";
    private static final String BILLS = "/api/v1/billing/bills";
    private static final String REQUESTER = "S-req-ra";
    private static final String PROVIDER = "S-prov-ra";
    private static final String ADMIN = "S-admin-ra";
    private static final String PERIOD = "2026-10";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_billing_recon_assert_it");
        registry.add("ctds.metering.source-schema.contract", () -> CONTRACT_DB);
    }

    @BeforeAll
    static void prepareSourceSchemas() {
        BillingSourceSchemas.createContractTables(SourceSchemas.jdbcTemplateFor(CONTRACT_DB));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM billing_bill_detail");
        jdbcTemplate.update("DELETE FROM billing_bill_action_log");
        jdbcTemplate.update("DELETE FROM billing_reconciliation");
        jdbcTemplate.update("DELETE FROM billing_bill");
        jdbcTemplate.update("DELETE FROM metering_flow");
        final JdbcTemplate source = sourceJdbc();
        source.update("DELETE FROM contract");
        source.update("DELETE FROM contract_usage_log");
        source.update("DELETE FROM contract_usage_counter");
    }

    // ==== B11-1 正向：四方数值逐项一致 + 留痕 ====

    @Test
    void positiveQuadripartiteValuesAgree() throws Exception {
        seedContract("CO940001", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940001", "ra-evt-1", "2026-10-05 09:00:00");
        seedFlow("CO940001", "ra-evt-2", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940001", PERIOD);
        seedUsage("CO940001", 2);

        final JsonNode view = reconcile("CO940001", PERIOD).path("data");

        assertThat(view.path("conclusion").asText()).as("四方一致 → PASS").isEqualTo("PASS");
        assertThat(view.path("differences")).as("通过时差异清单为空").isEmpty();
        assertThat(view.path("counterUsedCount").asInt()).as("计数余额腿").isEqualTo(2);
        assertThat(view.path("usageLogAllowedCount").asInt()).as("本期放行流水腿").isEqualTo(2);
        assertThat(view.path("meteringFlowCount").asInt()).as("本期计量流水腿").isEqualTo(2);
        assertThat(view.path("usageLogAllowedAllTime").asInt()).as("全量放行流水腿").isEqualTo(2);
        assertThat(view.path("meteringFlowAllTime").asInt()).as("全量计量流水腿").isEqualTo(2);
        assertThat(view.path("billQuantity").asInt()).as("按次账单数量腿").isEqualTo(2);
        assertThat(view.path("billTotalAmount").asText()).isEqualTo("6.00");
        assertThat(view.path("recomputedAmount").asText()).as("按快照独立复算").isEqualTo("6.00");
        assertThat(view.path("createdBy").asText()).as("留痕执行人").isEqualTo(ADMIN);
        assertThat(view.path("createdAt").asText()).as("留痕时点").isNotBlank();
        assertThat(billStatus(billNo)).as("通过不改账单状态").isEqualTo("ISSUED");

        final Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT conclusion, details_json, created_by, created_at FROM billing_reconciliation "
                        + "WHERE contract_no = 'CO940001'");
        assertThat(record.get("conclusion")).isEqualTo("PASS");
        assertThat(record.get("details_json")).as("通过时不写差异明细").isNull();
        assertThat(record.get("created_by")).isEqualTo(ADMIN);
        assertThat(record.get("created_at")).isNotNull();
    }

    // ==== B11-2 负向：七类差异逐类可定位（每例仅命中目标腿） ====

    /** ① 计数余额 vs 全量放行流水。 */
    @Test
    void negativeCounterVsUsageLogAllLeg() throws Exception {
        seedContract("CO940002", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940002", "ra-evt-11", "2026-10-05 09:00:00");
        seedFlow("CO940002", "ra-evt-12", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940002", PERIOD);
        seedCounter("CO940002", 3);
        seedUsageLog("CO940002", 1, "2026-10-05 09:00:00");
        seedUsageLog("CO940002", 2, "2026-10-06 09:00:00");

        final JsonNode item = singleDifference("CO940002",
                "COUNTER_VS_USAGE_LOG_ALL", "COUNTER");
        assertThat(item.path("expected").asText()).as("期望 = 全量放行流水").isEqualTo("2");
        assertThat(item.path("actual").asText()).as("实际 = 计数表余额").isEqualTo("3");
        assertThat(billStatus(billNo)).isEqualTo("FROZEN");
    }

    /** ② 全量放行流水 vs 全量计量流水（本期两腿不误报）。 */
    @Test
    void negativeUsageLogVsMeteringAllLeg() throws Exception {
        seedContract("CO940003", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940003", "ra-evt-21", "2026-10-05 09:00:00");
        seedFlow("CO940003", "ra-evt-22", "2026-10-06 09:00:00");
        outbill("CO940003", PERIOD);
        seedCounter("CO940003", 3);
        seedUsageLog("CO940003", 1, "2026-10-05 09:00:00");
        seedUsageLog("CO940003", 2, "2026-10-06 09:00:00");
        seedUsageLog("CO940003", 3, "2026-11-05 09:00:00");

        final JsonNode item = singleDifference("CO940003",
                "USAGE_LOG_VS_METERING_ALL", "ALL_TIME");
        assertThat(item.path("expected").asText()).isEqualTo("3");
        assertThat(item.path("actual").asText()).isEqualTo("2");
    }

    /** ③ 本期放行流水 vs 本期计量流水（全量两腿对齐，只命中本期腿）。 */
    @Test
    void negativeUsageLogVsMeteringPeriodLeg() throws Exception {
        seedContract("CO940004", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940004", "ra-evt-31", "2026-10-05 09:00:00");
        seedFlow("CO940004", "ra-evt-32", "2026-10-06 09:00:00");
        seedFlow("CO940004", "ra-evt-33", "2026-10-07 09:00:00");
        outbill("CO940004", PERIOD);
        seedCounter("CO940004", 3);
        seedUsageLog("CO940004", 1, "2026-10-05 09:00:00");
        seedUsageLog("CO940004", 2, "2026-10-06 09:00:00");
        seedUsageLog("CO940004", 3, "2026-11-05 09:00:00");

        final JsonNode item = singleDifference("CO940004",
                "USAGE_LOG_VS_METERING_PERIOD", "PERIOD");
        assertThat(item.path("expected").asText()).as("期望 = 本期放行流水").isEqualTo("2");
        assertThat(item.path("actual").asText()).as("实际 = 本期计量流水").isEqualTo("3");
    }

    /** ④ 按次账单数量 vs 本期计量（金额按数量同步复算一致，只命中数量腿）。 */
    @Test
    void negativeBillQuantityVsMeteringLeg() throws Exception {
        seedContract("CO940005", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940005", "ra-evt-41", "2026-10-05 09:00:00");
        seedFlow("CO940005", "ra-evt-42", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940005", PERIOD);
        seedUsage("CO940005", 2);
        jdbcTemplate.update("UPDATE billing_bill SET quantity = 3, amount = 9.00 WHERE bill_no = ?",
                billNo);

        final JsonNode item = singleDifference("CO940005",
                "BILL_QUANTITY_VS_METERING", "BILL_QUANTITY");
        assertThat(item.path("expected").asText()).as("期望 = 本期计量流水").isEqualTo("2");
        assertThat(item.path("actual").asText()).as("实际 = 账单数量").isEqualTo("3");
    }

    /** ⑤ 账单金额 vs 按快照独立复算（包月档，不涉及明细腿）。 */
    @Test
    void negativeAmountRecomputeLeg() throws Exception {
        seedContract("CO940006", "MONTHLY", new BigDecimal("10.00"));
        final String billNo = outbill("CO940006", PERIOD);
        jdbcTemplate.update("UPDATE billing_bill SET amount = 99.00 WHERE bill_no = ?", billNo);

        final JsonNode item = singleDifference("CO940006", "AMOUNT_RECOMPUTE", "AMOUNT");
        assertThat(item.path("expected").asText()).as("期望 = 快照复算金额").isEqualTo("10.00");
        assertThat(item.path("actual").asText()).as("实际 = 账单金额").isEqualTo("99.00");
    }

    /** ⑥ 计量明细缺失于账单（数量与金额同步对齐后仅命中缺失腿）。 */
    @Test
    void negativeMissingInBillLeg() throws Exception {
        seedContract("CO940007", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940007", "ra-evt-51", "2026-10-05 09:00:00");
        seedFlow("CO940007", "ra-evt-52", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940007", PERIOD);
        seedFlow("CO940007", "ra-evt-53", "2026-10-07 09:00:00");
        seedUsage("CO940007", 3);
        jdbcTemplate.update("UPDATE billing_bill SET quantity = 3, amount = 9.00 WHERE bill_no = ?",
                billNo);

        final JsonNode item = singleDifference("CO940007", "MISSING_IN_BILL", "BILL_DETAIL");
        assertThat(item.path("expected").asText()).as("期望 = 本期计量数").isEqualTo("3");
        assertThat(item.path("actual").asText()).as("实际 = 账单明细数").isEqualTo("2");
        assertThat(item.path("detail").asText()).as("缺失条目可定位")
                .contains("账单缺失本期计量流水");
    }

    /**
     * ⑦ 账单明细多出（补覆盖腿，B11-2"多出可定位"）：账单明细引用他合约流水 id，
     * 本期三方计数与金额均一致 → 仅命中多出腿。
     */
    @Test
    void negativeExtraInBillLeg() throws Exception {
        seedContract("CO940008", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940008", "ra-evt-61", "2026-10-05 09:00:00");
        seedFlow("CO940008", "ra-evt-62", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940008", PERIOD);
        seedUsage("CO940008", 2);
        seedFlow("CO949999", "ra-evt-foreign", "2026-10-09 09:00:00");
        final Long foreignId = jdbcTemplate.queryForObject(
                "SELECT id FROM metering_flow WHERE source_no = 'ra-evt-foreign'", Long.class);
        jdbcTemplate.update("INSERT INTO billing_bill_detail (bill_no, line_no, line_type, "
                        + "description, metering_flow_id, source_type, source_no, occurred_at, "
                        + "quantity, unit_price, amount) VALUES (?, 99, 'PER_CALL_EVENT', "
                        + "'他合约流水（构造多出腿）', ?, 'GATEWAY_API', 'ra-evt-foreign', "
                        + "'2026-10-09 09:00:00', 1, 3.00, 3.00)", billNo, foreignId);

        final JsonNode item = singleDifference("CO940008", "EXTRA_IN_BILL", "BILL_DETAIL");
        assertThat(item.path("expected").asText()).as("期望 = 本期计量数").isEqualTo("2");
        assertThat(item.path("actual").asText()).as("实际 = 账单明细数").isEqualTo("3");
        assertThat(item.path("detail").asText()).as("多出条目可定位（含流水 id）")
                .contains("账单多出非本期计量流水").contains(String.valueOf(foreignId));
    }

    // ==== B11-3 fail-closed：差异冻结 → 确认被拒 → 还原解冻 → 确认成功 ====

    @Test
    void failClosedFreezeAndRestore() throws Exception {
        seedContract("CO940009", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940009", "ra-evt-71", "2026-10-05 09:00:00");
        seedFlow("CO940009", "ra-evt-72", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940009", PERIOD);
        seedUsage("CO940009", 2);

        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-07-06 09:00:00' "
                + "WHERE source_no = 'ra-evt-72'");
        assertThat(reconcile("CO940009", PERIOD).path("data").path("conclusion").asText())
                .isEqualTo("DIFFERENCE");
        assertThat(billStatus(billNo)).as("差异 → fail-closed 冻结").isEqualTo("FROZEN");

        final MvcResult confirm = perform(ADMIN, "admin", "POST",
                BILLS + "/" + billNo + "/confirm", null);
        assertThat(confirm.getResponse().getStatus()).as("冻结态确认被拒").isEqualTo(409);
        assertThat(body(confirm).path("code").asText()).isEqualTo("1011C0011");

        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-10-06 09:00:00' "
                + "WHERE source_no = 'ra-evt-72'");
        final JsonNode restored = reconcile("CO940009", PERIOD).path("data");
        assertThat(restored.path("conclusion").asText()).as("还原后再次对账 PASS").isEqualTo("PASS");
        assertThat(billStatus(billNo)).as("通过 → 解冻恢复原状态").isEqualTo("ISSUED");
        assertThat(perform(ADMIN, "admin", "POST", BILLS + "/" + billNo + "/confirm", null)
                .getResponse().getStatus()).as("解冻后确认 200").isEqualTo(200);
        assertThat(billStatus(billNo)).isEqualTo("CONFIRMED");

        assertThat(actionLog(billNo)).as("冻结 / 解冻动作留痕齐全")
                .contains("CREATE", "FREEZE", "UNFREEZE", "CONFIRM");
    }

    // ==== B11-4 零自动改数（前后快照对照） ====

    @Test
    void reconcileNeverMutatesBusinessData() throws Exception {
        seedContract("CO940010", "PER_CALL", new BigDecimal("2.00"));
        seedFlow("CO940010", "ra-evt-81", "2026-10-05 09:00:00");
        seedFlow("CO940010", "ra-evt-82", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940010", PERIOD);
        seedUsage("CO940010", 2);
        // 差异构造先于快照（对账前后唯一动作 = 对账本身，快照差异只能由对账引起）
        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-08-06 09:00:00' "
                + "WHERE source_no = 'ra-evt-82'");

        final List<Map<String, Object>> billBefore = jdbcTemplate.queryForList(
                "SELECT bill_no, quantity, amount FROM billing_bill ORDER BY id");
        final List<Map<String, Object>> detailsBefore = jdbcTemplate.queryForList(
                "SELECT * FROM billing_bill_detail ORDER BY id");
        final List<Map<String, Object>> flowsBefore = jdbcTemplate.queryForList(
                "SELECT * FROM metering_flow ORDER BY id");
        final List<Map<String, Object>> usageBefore = sourceJdbc().queryForList(
                "SELECT * FROM contract_usage_log ORDER BY id");
        final List<Map<String, Object>> counterBefore = sourceJdbc().queryForList(
                "SELECT * FROM contract_usage_counter ORDER BY contract_no");
        final Long reconciliationBefore = count("billing_reconciliation");

        final JsonNode view = reconcile("CO940010", PERIOD).path("data");
        assertThat(view.path("conclusion").asText()).isEqualTo("DIFFERENCE");

        assertThat(jdbcTemplate.queryForList(
                "SELECT bill_no, quantity, amount FROM billing_bill ORDER BY id"))
                .as("账单金额 / 数量 / 编号逐字不变（仅状态流转见下行）")
                .isEqualTo(billBefore);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM billing_bill_detail ORDER BY id"))
                .as("账单明细零增删改").isEqualTo(detailsBefore);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM metering_flow ORDER BY id"))
                .as("计量流水行数与关键列不变").isEqualTo(flowsBefore);
        assertThat(sourceJdbc().queryForList("SELECT * FROM contract_usage_log ORDER BY id"))
                .as("上游放行流水零写入").isEqualTo(usageBefore);
        assertThat(sourceJdbc().queryForList(
                "SELECT * FROM contract_usage_counter ORDER BY contract_no"))
                .as("上游计数表零写入").isEqualTo(counterBefore);
        assertThat(count("billing_reconciliation")).as("对账记录 +1（留痕）")
                .isEqualTo(reconciliationBefore + 1);
        assertThat(actionLog(billNo)).as("仅状态流转留痕（FREEZE）")
                .containsExactly("CREATE", "FREEZE");
        assertThat(billStatus(billNo)).isEqualTo("FROZEN");
    }

    // ==== E11 差异未澄清重复对账：结论不变、差异不叠加、冻结留痕不重复 ====

    @Test
    void repeatedReconcileKeepsSingleDifferenceSet() throws Exception {
        seedContract("CO940011", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO940011", "ra-evt-91", "2026-10-05 09:00:00");
        seedFlow("CO940011", "ra-evt-92", "2026-10-06 09:00:00");
        final String billNo = outbill("CO940011", PERIOD);
        seedUsage("CO940011", 2);
        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-07-06 09:00:00' "
                + "WHERE source_no = 'ra-evt-92'");

        final JsonNode first = reconcile("CO940011", PERIOD).path("data");
        final JsonNode second = reconcile("CO940011", PERIOD).path("data");

        assertThat(second.path("conclusion").asText()).isEqualTo("DIFFERENCE");
        assertThat(first.path("differences")).as("首次对账差异非空（否则幂等结论空洞）").isNotEmpty();
        assertThat(second.path("differences")).hasSameSizeAs(first.path("differences"));
        final List<String> types = new ArrayList<>();
        second.path("differences").forEach(item -> types.add(item.path("type").asText()));
        assertThat(types).as("重复对账差异项不叠加重复").doesNotHaveDuplicates();
        assertThat(actionLog(billNo)).as("已冻结账单不重复写冻结留痕")
                .containsExactly("CREATE", "FREEZE");
        assertThat(count("billing_reconciliation")).as("每次对账各留一行").isEqualTo(2L);
    }

    /** E9（hifi §3）：该合约该期无账单 → 404 同码同文（不暴露"无账单"与"不可读"差异）。 */
    @Test
    void reconcileWithoutBillIsNotVisible() throws Exception {
        seedContract("CO940012", "PER_CALL", new BigDecimal("3.00"));

        final MvcResult result = perform(ADMIN, "admin", "POST",
                "/api/v1/billing/contracts/CO940012/reconciliations?period=" + PERIOD, null);

        assertThat(result.getResponse().getStatus()).as("无账单 → 404").isEqualTo(404);
        assertThat(body(result).path("code").asText()).isEqualTo("1011C0009");
    }

    // ==== 助手 ====

    /** 执行对账并断言"仅命中目标类型且恰 1 项"，返回该差异条目（含五字段齐备校验）。 */
    private JsonNode singleDifference(final String contractNo, final String type,
            final String leg) throws Exception {
        final JsonNode view = reconcile(contractNo, PERIOD).path("data");
        assertThat(view.path("conclusion").asText()).as("差异必报，不静默通过")
                .isEqualTo("DIFFERENCE");
        assertThat(view.path("differences")).as("仅命中目标腿（[%s] 无假绿 / 无误报）", type)
                .hasSize(1);
        final JsonNode item = view.path("differences").get(0);
        assertThat(item.path("type").asText()).isEqualTo(type);
        assertThat(item.path("leg").asText()).as("差异腿可定位").isEqualTo(leg);
        assertThat(item.path("expected").asText()).as("期望值非空").isNotBlank();
        assertThat(item.path("actual").asText()).as("实际值非空").isNotBlank();
        assertThat(item.path("detail").asText()).as("业务可读说明非空").isNotBlank();
        final Map<String, Object> record = jdbcTemplate.queryForMap(
                "SELECT details_json FROM billing_reconciliation WHERE contract_no = ? "
                        + "ORDER BY id DESC LIMIT 1", contractNo);
        assertThat((String) record.get("details_json")).as("差异明细落库").contains(type);
        return item;
    }

    private JdbcTemplate sourceJdbc() {
        return SourceSchemas.jdbcTemplateFor(CONTRACT_DB);
    }

    private void seedContract(final String contractNo, final String pricingModel,
            final BigDecimal priceAmount) {
        sourceJdbc().update(
                "INSERT INTO contract (contract_no, product_id, product_name, provider_subject_no, "
                        + "requester_subject_no, pricing_model, price_amount, status, effective_at) "
                        + "VALUES (?, 101, '测试数据集', ?, ?, ?, ?, 'EFFECTIVE', '2026-01-01 00:00:00')",
                contractNo, PROVIDER, REQUESTER, pricingModel, priceAmount);
    }

    private void seedFlow(final String contractNo, final String sourceNo, final String occurredAt) {
        jdbcTemplate.update("INSERT INTO metering_flow (contract_no, requester_no, source_type, "
                        + "source_no, action, resource, occurred_at) "
                        + "VALUES (?, ?, 'GATEWAY_API', ?, 'gateway.delivery.metering', NULL, ?)",
                contractNo, REQUESTER, sourceNo, java.sql.Timestamp.valueOf(occurredAt));
    }

    private void seedUsage(final String contractNo, final int allowedCount) {
        seedCounter(contractNo, allowedCount);
        for (int i = 0; i < allowedCount; i++) {
            seedUsageLog(contractNo, i + 1, String.format("2026-10-%02d 09:00:00", i + 1));
        }
    }

    private void seedCounter(final String contractNo, final int usedCount) {
        sourceJdbc().update(
                "INSERT INTO contract_usage_counter (contract_no, used_count) VALUES (?, ?)",
                contractNo, usedCount);
    }

    private void seedUsageLog(final String contractNo, final int usedCount, final String occurredAt) {
        sourceJdbc().update(
                "INSERT INTO contract_usage_log (contract_no, requester_no, action_type, outcome, "
                        + "used_count, occurred_at) VALUES (?, ?, 'USE', 'ALLOWED', ?, ?)",
                contractNo, REQUESTER, usedCount, java.sql.Timestamp.valueOf(occurredAt));
    }

    private MvcResult perform(final String subject, final String roles, final String method,
            final String path, final String payload) throws Exception {
        final MockHttpServletRequestBuilder builder = "POST".equals(method) ? post(path) : get(path);
        if ("POST".equals(method)) {
            builder.contentType(MediaType.APPLICATION_JSON).content(payload == null ? "" : payload);
        }
        return mockMvc.perform(builder
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
    }

    private JsonNode body(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String outbill(final String contractNo, final String period) throws Exception {
        final MvcResult result = perform(ADMIN, "admin", "POST", BILLS,
                "{\"contractNo\":\"" + contractNo + "\",\"period\":\"" + period + "\"}");
        assertThat(result.getResponse().getStatus()).as("出账成功").isEqualTo(201);
        return body(result).path("data").path("billNo").asText();
    }

    private JsonNode reconcile(final String contractNo, final String period) throws Exception {
        final MvcResult result = perform(ADMIN, "admin", "POST",
                "/api/v1/billing/contracts/" + contractNo + "/reconciliations?period=" + period,
                null);
        assertThat(result.getResponse().getStatus()).as("对账端点成功").isEqualTo(200);
        return body(result);
    }

    private String billStatus(final String billNo) {
        return jdbcTemplate.queryForObject("SELECT status FROM billing_bill WHERE bill_no = ?",
                String.class, billNo);
    }

    private List<String> actionLog(final String billNo) {
        return jdbcTemplate.queryForList(
                "SELECT action FROM billing_bill_action_log WHERE bill_no = ? ORDER BY id",
                String.class, billNo);
    }

    private Long count(final String table) {
        final Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return total == null ? 0L : total;
    }
}
