package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.metering.application.billing.BillingAutoService;
import com.ctds.metering.config.BillingProperties;
import com.ctds.metering.support.BillingSourceSchemas;
import com.ctds.metering.support.FixedClockTestConfiguration;
import com.ctds.metering.support.SharedMySqlContainer;
import com.ctds.metering.support.SourceSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
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
 * 自动出账与对账集成（hifi §11 TB6 / TB8 / TB9 / TB10）：
 * 周期腿逐合约出账与幂等、对账正向（四方一致）、负向（差异必报 → 冻结 fail-closed → 还原解冻）、
 * 全程不自动修数（仅状态与留痕新增）。Docker 缺失整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.billing.auto.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Import(FixedClockTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BillingAutoReconcileIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_DB = "ctds_billing_recon_contract_it";
    private static final String BILLS = "/api/v1/billing/bills";
    private static final String REQUESTER = "S-req-br";
    private static final String PROVIDER = "S-prov-br";
    private static final String ADMIN = "S-admin-br";
    private static final String OTHER = "S-other-br";
    private static final String PERIOD = "2026-10";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_billing_recon_it");
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

    @Autowired
    private BillingAutoService autoService;

    @Autowired
    private BillingProperties properties;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM billing_bill_detail");
        jdbcTemplate.update("DELETE FROM billing_bill_action_log");
        jdbcTemplate.update("DELETE FROM billing_reconciliation");
        jdbcTemplate.update("DELETE FROM billing_bill");
        jdbcTemplate.update("DELETE FROM metering_flow");
        final JdbcTemplate source = SourceSchemas.jdbcTemplateFor(CONTRACT_DB);
        source.update("DELETE FROM contract");
        source.update("DELETE FROM contract_usage_log");
        source.update("DELETE FROM contract_usage_counter");
    }

    private void seedContract(final String contractNo, final String pricingModel,
            final BigDecimal priceAmount) {
        SourceSchemas.jdbcTemplateFor(CONTRACT_DB).update(
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
            seedUsageLog(contractNo, i + 1, "2026-10-0" + (i + 1) + " 09:00:00");
        }
    }

    private void seedCounter(final String contractNo, final int usedCount) {
        SourceSchemas.jdbcTemplateFor(CONTRACT_DB).update(
                "INSERT INTO contract_usage_counter (contract_no, used_count) VALUES (?, ?)",
                contractNo, usedCount);
    }

    private void seedUsageLog(final String contractNo, final int usedCount, final String occurredAt) {
        SourceSchemas.jdbcTemplateFor(CONTRACT_DB).update(
                "INSERT INTO contract_usage_log (contract_no, requester_no, action_type, outcome, "
                        + "used_count, occurred_at) VALUES (?, ?, 'USE', 'ALLOWED', ?, ?)",
                contractNo, REQUESTER, usedCount, java.sql.Timestamp.valueOf(occurredAt));
    }

    private MvcResult perform(final String subject, final String roles, final String method,
            final String path, final String body) throws Exception {
        if ("POST".equals(method)) {
            return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(body == null ? "" : body)
                    .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
        }
        return mockMvc.perform(get(path)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
    }

    private JsonNode body(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String outbill(final String contractNo, final String period) throws Exception {
        final MvcResult result = perform(ADMIN, "admin", "POST", BILLS,
                "{\"contractNo\":\"" + contractNo + "\",\"period\":\"" + period + "\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result).path("data").path("billNo").asText();
    }

    private JsonNode reconcile(final String contractNo, final String period) throws Exception {
        final MvcResult result = perform(ADMIN, "admin", "POST",
                "/api/v1/billing/contracts/" + contractNo + "/reconciliations?period=" + period, null);
        return body(result);
    }

    private String billStatus(final String billNo) {
        return jdbcTemplate.queryForObject("SELECT status FROM billing_bill WHERE bill_no = ?",
                String.class, billNo);
    }

    /** TB6：周期腿出上月账；按次无计量 / 分成无基数跳过；重复运行零新增（幂等）。 */
    @Test
    void autoOutbillScansAndSkips() throws Exception {
        seedContract("CO910001", "PER_CALL", new BigDecimal("3.00"));
        seedContract("CO910002", "MONTHLY", new BigDecimal("50.00"));
        seedContract("CO910003", "REVENUE_SHARE", new BigDecimal("5.00"));
        seedContract("CO910004", "FREE", null);
        seedFlow("CO910001", "evt-br-11", "2026-10-05 09:00:00");
        seedFlow("CO910001", "evt-br-12", "2026-10-06 09:00:00");

        final BillingAutoService.AutoOutbillResult first =
                autoService.runForPeriod(YearMonth.of(2026, 10));

        assertThat(first.scanned()).as("扫描 = 三张付费档合约（免费档不在扫描面）").isEqualTo(3);
        assertThat(first.billed()).isEqualTo(2);
        assertThat(first.skippedNoBase()).as("分成档无基数跳过").isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(countBills("CO910001")).isEqualTo(1);
        assertThat(countBills("CO910002")).isEqualTo(1);
        assertThat(countBills("CO910004")).as("免费档零账单").isZero();

        final BillingAutoService.AutoOutbillResult second =
                autoService.runForPeriod(YearMonth.of(2026, 10));
        assertThat(second.billed()).as("重复运行零新增").isZero();
        assertThat(second.skippedDuplicate()).isEqualTo(2);
        assertThat(countBills("CO910001") + countBills("CO910002")).isEqualTo(2);
    }

    /** TB6 补充：auto.enabled=false 时调度入口不执行（测试 yml 关闭）。 */
    @Test
    void schedulerRespectsDisabledFlag() {
        seedContract("CO910005", "MONTHLY", new BigDecimal("10.00"));
        autoService.scheduledOutbill();
        assertThat(countBills("CO910005")).as("调度开关关闭时零出账").isZero();
    }

    /** TB8：正常出账后对账 = 四方一致 + 金额复算一致 → PASS，记录与留痕完整。 */
    @Test
    void reconcilePassesAfterNormalOutbill() throws Exception {
        seedContract("CO920001", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO920001", "evt-br-21", "2026-10-05 09:00:00");
        seedFlow("CO920001", "evt-br-22", "2026-10-06 09:00:00");
        final String billNo = outbill("CO920001", PERIOD);
        seedUsage("CO920001", 2);

        final JsonNode response = reconcile("CO920001", PERIOD);

        assertThat(response.path("code").asText()).isEqualTo("0");
        final JsonNode view = response.path("data");
        assertThat(view.path("conclusion").asText()).isEqualTo("PASS");
        assertThat(view.path("counterUsedCount").asInt()).isEqualTo(2);
        assertThat(view.path("usageLogAllowedCount").asInt()).isEqualTo(2);
        assertThat(view.path("meteringFlowCount").asInt()).isEqualTo(2);
        assertThat(view.path("usageLogAllowedAllTime").asInt()).isEqualTo(2);
        assertThat(view.path("meteringFlowAllTime").asInt()).isEqualTo(2);
        assertThat(view.path("billQuantity").asInt()).isEqualTo(2);
        assertThat(view.path("billTotalAmount").asText()).isEqualTo("6.00");
        assertThat(view.path("recomputedAmount").asText()).isEqualTo("6.00");
        assertThat(billStatus(billNo)).isEqualTo("ISSUED");

        final Long records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_reconciliation WHERE contract_no = 'CO920001'",
                Long.class);
        assertThat(records).as("对账记录落库").isEqualTo(1L);
        final MvcResult mine = perform(REQUESTER, "requester", "GET",
                "/api/v1/billing/contracts/CO920001/reconciliations?period=" + PERIOD, null);
        assertThat(mine.getResponse().getStatus()).as("参与方可读对账结论").isEqualTo(200);
        assertThat(body(mine).path("data").path("list")).hasSize(1);
    }

    /** TB9：构造本期差异 → 对账必报差异 → 账单冻结 → 确认被拒 → 还原后通过并解冻（恢复原状态）。 */
    @Test
    void reconcileDifferenceFreezesAndRestores() throws Exception {
        seedContract("CO920002", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO920002", "evt-br-31", "2026-10-05 09:00:00");
        seedFlow("CO920002", "evt-br-32", "2026-10-06 09:00:00");
        final String billNo = outbill("CO920002", PERIOD);
        seedUsage("CO920002", 2);
        // 先确认：冻结必须保存"原状态 = CONFIRMED"，解冻回到 CONFIRMED 而非 ISSUED
        assertThat(perform(ADMIN, "admin", "POST", BILLS + "/" + billNo + "/confirm", null)
                .getResponse().getStatus()).as("差异前确认成功").isEqualTo(200);

        // 差异构造（剧本附录 A 适配）：改一条流水的 occurred_at 移出本期（改值不删行）
        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-07-05 09:00:00' "
                + "WHERE source_no = 'evt-br-32'");

        final JsonNode difference = reconcile("CO920002", PERIOD);
        assertThat(difference.path("data").path("conclusion").asText()).isEqualTo("DIFFERENCE");
        assertThat(difference.path("data").path("differences")).isNotEmpty();
        assertThat(difference.path("data").path("differences").findValuesAsText("type"))
                .contains("BILL_QUANTITY_VS_METERING");
        assertThat(billStatus(billNo)).as("差异 → 账单冻结").isEqualTo("FROZEN");

        final MvcResult confirm = perform(ADMIN, "admin", "POST",
                BILLS + "/" + billNo + "/confirm", null);
        assertThat(confirm.getResponse().getStatus()).as("冻结态确认被拒").isEqualTo(409);
        assertThat(body(confirm).path("code").asText()).isEqualTo("1011C0011");
        final MvcResult correction = perform(ADMIN, "admin", "POST",
                BILLS + "/" + billNo + "/corrections", "{\"amount\":-3.00,\"reason\":\"越权\"}");
        assertThat(correction.getResponse().getStatus()).as("冻结态更正被拒").isEqualTo(409);

        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-10-06 09:00:00' "
                + "WHERE source_no = 'evt-br-32'");

        final JsonNode restored = reconcile("CO920002", PERIOD);
        assertThat(restored.path("data").path("conclusion").asText()).isEqualTo("PASS");
        assertThat(billStatus(billNo)).as("通过 → 解冻恢复原状态（CONFIRMED 不被降级）")
                .isEqualTo("CONFIRMED");
        final JsonNode actions = body(perform(ADMIN, "admin", "GET", BILLS + "/" + billNo, null))
                .path("data").path("actions");
        assertThat(actions.findValuesAsText("action")).as("解冻动作留痕齐全（TB14）")
                .contains("CREATE", "CONFIRM", "FREEZE", "UNFREEZE");
        assertThat(perform(ADMIN, "admin", "POST", BILLS + "/" + billNo + "/confirm", null)
                .getResponse().getStatus()).as("已确认账单重复确认被拒").isEqualTo(409);
    }

    /** TB10：对账不自动修数——计量行数、账单金额与明细在差异前后零修改，仅状态与留痕新增。 */
    @Test
    void reconcileNeverMutatesBusinessData() throws Exception {
        seedContract("CO920003", "PER_CALL", new BigDecimal("2.00"));
        seedFlow("CO920003", "evt-br-41", "2026-10-05 09:00:00");
        seedFlow("CO920003", "evt-br-42", "2026-10-06 09:00:00");
        final String billNo = outbill("CO920003", PERIOD);
        seedUsage("CO920003", 2);
        final BigDecimal amountBefore = jdbcTemplate.queryForObject(
                "SELECT amount FROM billing_bill WHERE bill_no = ?", BigDecimal.class, billNo);
        final Long detailsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill_detail WHERE bill_no = ?", Long.class, billNo);
        final Long flowsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metering_flow WHERE contract_no = 'CO920003'", Long.class);

        jdbcTemplate.update("UPDATE metering_flow SET occurred_at = '2026-08-05 09:00:00' "
                + "WHERE source_no = 'evt-br-42'");
        final JsonNode difference = reconcile("CO920003", PERIOD);
        assertThat(difference.path("data").path("conclusion").asText()).isEqualTo("DIFFERENCE");

        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM billing_bill WHERE bill_no = ?",
                BigDecimal.class, billNo)).as("账单金额未被修数").isEqualByComparingTo(amountBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill_detail WHERE bill_no = ?", Long.class, billNo))
                .as("账单明细未被增删").isEqualTo(detailsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metering_flow WHERE contract_no = 'CO920003'", Long.class))
                .as("计量流水未被增删").isEqualTo(flowsBefore);

        final MvcResult detail = perform(ADMIN, "admin", "GET", BILLS + "/" + billNo, null);
        assertThat(body(detail).path("data").path("actions").findValuesAsText("action"))
                .as("冻结动作留痕").contains("CREATE", "FREEZE");
    }

    /**
     * TB8/TB9 差异四腿（评审修复 P2-8）：计数余额 / 全量流水 / 金额复算 / 明细缺失各 1 例，
     * 逐腿断言 expected/actual（防"腿恒跳过"或"比较写反"仍绿）。
     */
    @Test
    void reconcileDetectsEachDifferenceLeg() throws Exception {
        // ① 计数余额腿：counter(3) ≠ 全量放行流水(2)；本期/全量流水与计量一致（2 == 2）
        seedContract("CO920011", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO920011", "evt-br-61", "2026-10-05 09:00:00");
        seedFlow("CO920011", "evt-br-62", "2026-10-06 09:00:00");
        final String counterBill = outbill("CO920011", PERIOD);
        seedCounter("CO920011", 3);
        seedUsageLog("CO920011", 1, "2026-10-05 09:00:00");
        seedUsageLog("CO920011", 2, "2026-10-06 09:00:00");
        final JsonNode counterView = reconcile("CO920011", PERIOD);
        assertThat(counterView.path("data").path("conclusion").asText()).isEqualTo("DIFFERENCE");
        final JsonNode counterDiff = differenceOf(counterView, "COUNTER_VS_USAGE_LOG_ALL");
        assertThat(counterDiff.path("expected").asText()).as("期望 = 全量放行流水").isEqualTo("2");
        assertThat(counterDiff.path("actual").asText()).as("实际 = 计数表余额").isEqualTo("3");
        assertThat(billStatus(counterBill)).isEqualTo("FROZEN");

        // ② 全量流水腿：期外流水（本期 2 / 全量 3）→ 只命中全量腿，本期腿不得误报
        seedContract("CO920012", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO920012", "evt-br-71", "2026-10-05 09:00:00");
        seedFlow("CO920012", "evt-br-72", "2026-10-06 09:00:00");
        outbill("CO920012", PERIOD);
        seedCounter("CO920012", 3);
        seedUsageLog("CO920012", 1, "2026-10-05 09:00:00");
        seedUsageLog("CO920012", 2, "2026-10-06 09:00:00");
        seedUsageLog("CO920012", 3, "2026-11-05 09:00:00");
        final JsonNode allTimeView = reconcile("CO920012", PERIOD);
        final JsonNode allTimeDiff = differenceOf(allTimeView, "USAGE_LOG_VS_METERING_ALL");
        assertThat(allTimeDiff.path("expected").asText()).as("期望 = 全量放行流水").isEqualTo("3");
        assertThat(allTimeDiff.path("actual").asText()).as("实际 = 全量计量流水").isEqualTo("2");
        assertThat(allTimeView.path("data").path("differences").findValuesAsText("type"))
                .as("本期流水与本期计量仍一致，不误报本期腿")
                .doesNotContain("USAGE_LOG_VS_METERING_PERIOD");

        // ③ 金额复算腿：账单金额被改（99.00）≠ 按快照复算（10.00）
        seedContract("CO920013", "MONTHLY", new BigDecimal("10.00"));
        final String amountBill = outbill("CO920013", PERIOD);
        jdbcTemplate.update("UPDATE billing_bill SET amount = 99.00 WHERE bill_no = ?", amountBill);
        final JsonNode amountDiff = differenceOf(reconcile("CO920013", PERIOD), "AMOUNT_RECOMPUTE");
        assertThat(amountDiff.path("expected").asText()).as("期望 = 快照复算金额").isEqualTo("10.00");
        assertThat(amountDiff.path("actual").asText()).as("实际 = 账单金额").isEqualTo("99.00");

        // ④ 明细缺失腿：出账后新增本期计量（3）→ 账单仍 2 行明细
        seedContract("CO920014", "PER_CALL", new BigDecimal("3.00"));
        seedFlow("CO920014", "evt-br-81", "2026-10-05 09:00:00");
        seedFlow("CO920014", "evt-br-82", "2026-10-06 09:00:00");
        final String missingBill = outbill("CO920014", PERIOD);
        seedCounter("CO920014", 3);
        seedUsageLog("CO920014", 1, "2026-10-05 09:00:00");
        seedUsageLog("CO920014", 2, "2026-10-06 09:00:00");
        seedFlow("CO920014", "evt-br-83", "2026-10-07 09:00:00");
        seedUsageLog("CO920014", 3, "2026-10-07 09:00:00");
        final JsonNode missingView = reconcile("CO920014", PERIOD);
        final JsonNode missingDiff = differenceOf(missingView, "MISSING_IN_BILL");
        assertThat(missingDiff.path("expected").asText()).as("期望 = 本期计量数").isEqualTo("3");
        assertThat(missingDiff.path("actual").asText()).as("实际 = 账单明细数").isEqualTo("2");
        assertThat(missingDiff.path("detail").asText()).as("差异条目可定位").contains("账单缺失本期计量流水");
        assertThat(missingView.path("data").path("differences").findValuesAsText("type"))
                .contains("BILL_QUANTITY_VS_METERING");
        assertThat(billStatus(missingBill)).isEqualTo("FROZEN");
    }

    /**
     * TB6 周期腿（评审修复 P2-10）：固定时钟（2026-11-02）→ 调度入口只出上月（2026-10）账，
     * 当月不出账；自动腿金额与主体 {@code system} 落库。
     */
    @Test
    void scheduledOutbillTargetsPreviousMonth() {
        seedContract("CO910010", "PER_CALL", new BigDecimal("3.00"));
        seedContract("CO910011", "MONTHLY", new BigDecimal("50.00"));
        seedFlow("CO910010", "evt-br-51", "2026-10-05 09:00:00");
        seedFlow("CO910010", "evt-br-52", "2026-10-06 09:00:00");
        seedFlow("CO910011", "evt-br-53", "2026-11-01 09:00:00");

        final boolean original = properties.getAuto().isEnabled();
        properties.getAuto().setEnabled(true);
        try {
            autoService.scheduledOutbill();
        } finally {
            properties.getAuto().setEnabled(original);
        }

        assertThat(countBills("CO910010")).as("按次自动腿出账").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM billing_bill WHERE contract_no = 'CO910010'", BigDecimal.class))
                .as("自动腿金额 = 2 × 3.00").isEqualByComparingTo("6.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT `period` FROM billing_bill WHERE contract_no = 'CO910010'", String.class))
                .as("目标周期 = 上一自然月").isEqualTo("2026-10");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by FROM billing_bill WHERE contract_no = 'CO910010'", String.class))
                .as("自动腿主体 = system").isEqualTo("system");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM billing_bill WHERE contract_no = 'CO910011'", BigDecimal.class))
                .as("包月自动腿出整月费").isEqualByComparingTo("50.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill WHERE `period` = '2026-11'", Long.class))
                .as("当月不出账").isZero();
    }

    private JsonNode differenceOf(final JsonNode view, final String type) {
        for (final JsonNode item : view.path("data").path("differences")) {
            if (type.equals(item.path("type").asText())) {
                return item;
            }
        }
        throw new AssertionError("差异明细缺少 " + type + "，实际 = "
                + view.path("data").path("differences"));
    }

    private int countBills(final String contractNo) {
        final Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill WHERE contract_no = ?", Long.class, contractNo);
        return count == null ? 0 : count.intValue();
    }
}
