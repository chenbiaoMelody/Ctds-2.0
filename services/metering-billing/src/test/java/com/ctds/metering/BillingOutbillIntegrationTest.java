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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 出账与账单动作集成（hifi §11 TB1~TB5 / TB7 / TB12 / TB14）：
 * 三模式金额与明细可溯、快照不可变与不追溯、同合约同周期幂等（含并发）、免费档不出账、
 * 编号唯一、更正单不改原单、确认状态机与动作留痕。Docker 缺失整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.billing.auto.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Import(FixedClockTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BillingOutbillIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_DB = "ctds_billing_outbill_contract_it";
    private static final String BILLS = "/api/v1/billing/bills";
    private static final String REQUESTER = "S-req-bo";
    private static final String PROVIDER = "S-prov-bo";
    private static final String ADMIN = "S-admin-bo";
    private static final String PERIOD = "2026-10";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_billing_outbill_it");
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
        jdbcTemplate.update("DELETE FROM billing_bill");
        jdbcTemplate.update("DELETE FROM metering_flow");
        final JdbcTemplate source = SourceSchemas.jdbcTemplateFor(CONTRACT_DB);
        source.update("DELETE FROM contract");
        source.update("DELETE FROM contract_usage_log");
        source.update("DELETE FROM contract_usage_counter");
    }

    /** 造合约（product_id / 产品名 / 双方 / 定价档与数值 / 生效终止时点）。 */
    private void seedContract(final String contractNo, final String pricingModel,
            final BigDecimal priceAmount, final String effectiveAt, final String endedAt) {
        SourceSchemas.jdbcTemplateFor(CONTRACT_DB).update(
                "INSERT INTO contract (contract_no, product_id, product_name, provider_subject_no, "
                        + "requester_subject_no, pricing_model, price_amount, status, effective_at, "
                        + "ended_at) VALUES (?, 101, '测试数据集', ?, ?, ?, ?, 'EFFECTIVE', ?, ?)",
                contractNo, PROVIDER, REQUESTER, pricingModel, priceAmount,
                effectiveAt == null ? null : java.sql.Timestamp.valueOf(effectiveAt),
                endedAt == null ? null : java.sql.Timestamp.valueOf(endedAt));
    }

    private void seedFlow(final String contractNo, final String sourceNo, final String occurredAt) {
        jdbcTemplate.update("INSERT INTO metering_flow (contract_no, requester_no, source_type, "
                        + "source_no, action, resource, occurred_at) "
                        + "VALUES (?, ?, 'GATEWAY_API', ?, 'gateway.delivery.metering', "
                        + "'delivery-catalog-products GET /api/v1/data-products/**', ?)",
                contractNo, REQUESTER, sourceNo, java.sql.Timestamp.valueOf(occurredAt));
    }

    private ApiResponse postJson(final String subject, final String roles, final String path,
            final String body) throws Exception {
        final MvcResult result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles))
                .andReturn();
        return new ApiResponse(result.getResponse().getStatus(),
                MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    private ApiResponse getJson(final String subject, final String roles, final String path)
            throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles))
                .andReturn();
        return new ApiResponse(result.getResponse().getStatus(),
                MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    private JsonNode outbill(final String contractNo, final String period) throws Exception {
        final String body = "{\"contractNo\":\"" + contractNo + "\",\"period\":\"" + period + "\"}";
        final ApiResponse response = postJson(ADMIN, "admin", BILLS, body);
        assertThat(response.status()).as("手动出账成功（201）").isEqualTo(201);
        return response.body().path("data");
    }

    /** TB1：按次 = 本期事件数 × 次单价；明细逐笔可溯；明细求和 = 账单金额（可复算）。 */
    @Test
    void perCallBillTracesEachEvent() throws Exception {
        seedContract("CO900001", "PER_CALL", new BigDecimal("3.00"), "2026-01-01 00:00:00", null);
        seedFlow("CO900001", "evt-bo-1", "2026-10-05 09:00:00");
        seedFlow("CO900001", "evt-bo-2", "2026-10-06 09:00:00");
        seedFlow("CO900001", "evt-bo-3", "2026-10-07 09:00:00");

        final JsonNode bill = outbill("CO900001", PERIOD);

        assertThat(bill.path("amount").asText()).isEqualTo("9.00");
        assertThat(bill.path("quantity").asInt()).isEqualTo(3);
        assertThat(bill.path("unitPrice").asText()).isEqualTo("3.00");
        assertThat(bill.path("pricingModel").asText()).isEqualTo("PER_CALL");
        assertThat(bill.path("status").asText()).isEqualTo("ISSUED");
        assertThat(bill.path("details")).hasSize(3);
        BigDecimal recomputed = BigDecimal.ZERO;
        final List<Long> detailFlowIds = new ArrayList<>();
        final List<Integer> lineNos = new ArrayList<>();
        for (final JsonNode line : bill.path("details")) {
            assertThat(line.path("meteringFlowId").isNull()).as("按次明细可溯至计量流水").isFalse();
            assertThat(line.path("lineType").asText()).isEqualTo("PER_CALL_EVENT");
            assertThat(line.path("unitPrice").asText()).as("明细单价 = 快照单价").isEqualTo("3.00");
            detailFlowIds.add(line.path("meteringFlowId").asLong());
            lineNos.add(line.path("lineNo").asInt());
            recomputed = recomputed.add(new BigDecimal(line.path("amount").asText()));
        }
        assertThat(lineNos).as("明细行号从 1 连续递增（可定位顺序）").containsExactly(1, 2, 3);
        assertThat(recomputed).as("按明细独立复算 = 账单金额").isEqualByComparingTo("9.00");
        final List<Long> periodFlowIds = jdbcTemplate.queryForList(
                "SELECT id FROM metering_flow WHERE contract_no = 'CO900001'", Long.class);
        assertThat(detailFlowIds).as("明细逐笔 id 集合 == 本期计量流水 id 集合（防填同一条）")
                .containsExactlyInAnyOrderElementsOf(periodFlowIds);
    }

    /** TB2：包月 = 整月周期费，与调用次数无关（两期次数 1 vs 5，金额相同）。 */
    @Test
    void monthlyBillIgnoresCallCount() throws Exception {
        seedContract("CO900002", "MONTHLY", new BigDecimal("100.00"), "2026-01-01 00:00:00", null);
        seedFlow("CO900002", "evt-bo-21", "2026-10-05 09:00:00");
        final JsonNode october = outbill("CO900002", "2026-10");
        for (int i = 0; i < 5; i++) {
            seedFlow("CO900002", "evt-bo-22-" + i, "2026-09-0" + (i + 1) + " 09:00:00");
        }
        final JsonNode september = outbill("CO900002", "2026-09");

        assertThat(october.path("amount").asText()).isEqualTo("100.00");
        assertThat(october.path("unitPrice").asText()).as("包月单价快照").isEqualTo("100.00");
        assertThat(september.path("amount").asText()).isEqualTo("100.00");
        assertThat(september.path("unitPrice").asText()).as("包月单价快照与次数无关").isEqualTo("100.00");
        assertThat(september.path("quantity").isNull()).as("包月无计费数量").isTrue();
        assertThat(september.path("details")).hasSize(1);
        assertThat(september.path("details").get(0).path("lineType").asText()).isEqualTo("PERIOD_FEE");
        assertThat(september.path("details").get(0).path("lineNo").asInt()).as("单行明细行号 = 1")
                .isEqualTo(1);
        assertThat(september.path("details").get(0).path("meteringFlowId").isNull())
                .as("包月明细不挂计量流水（非逐笔）").isTrue();
    }

    /** TB3：交易额分成 = 出账参数约定交易额 × 合约快照费率；基数与费率落账单快照。 */
    @Test
    void revenueShareBillUsesAgreedAmountAndRate() throws Exception {
        seedContract("CO900003", "REVENUE_SHARE", new BigDecimal("5.00"), "2026-01-01 00:00:00", null);

        final ApiResponse response = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900003\",\"period\":\"" + PERIOD
                        + "\",\"agreedTransactionAmount\":10000}");
        assertThat(response.status()).isEqualTo(201);
        final JsonNode bill = response.body().path("data");
        assertThat(bill.path("amount").asText()).isEqualTo("500.00");
        assertThat(bill.path("baseAmount").asText()).isEqualTo("10000.00");
        assertThat(bill.path("shareRate").asText()).isEqualTo("5.00");
        assertThat(bill.path("quantity").isNull()).as("分成无计费数量").isTrue();
        assertThat(bill.path("details")).as("分成单行明细").hasSize(1);
        assertThat(bill.path("details").get(0).path("lineType").asText()).isEqualTo("REVENUE_SHARE_BASE");
        assertThat(bill.path("details").get(0).path("lineNo").asInt()).isEqualTo(1);
        final BigDecimal recomputedShare = new BigDecimal(bill.path("baseAmount").asText())
                .multiply(new BigDecimal(bill.path("shareRate").asText()))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        assertThat(recomputedShare).as("按快照基数 × 费率独立复算 = 账单金额")
                .isEqualByComparingTo("500.00");

        final ApiResponse missingBase = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900003\",\"period\":\"2026-09\"}");
        assertThat(missingBase.status()).as("缺约定交易额 400").isEqualTo(400);
        assertThat(missingBase.body().path("code").asText()).isEqualTo("1011C0013");

        final ApiResponse zeroBase = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900003\",\"period\":\"2026-09\",\"agreedTransactionAmount\":0}");
        assertThat(zeroBase.body().path("code").asText()).isEqualTo("1011C0013");

        // 基数超出 DECIMAL(12,2) 上限 → 400 1000C0001（原为落库溢出 500）
        final ApiResponse hugeBase = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900003\",\"period\":\"2026-08\","
                        + "\"agreedTransactionAmount\":10000000000}");
        assertThat(hugeBase.status()).as("约定交易额超出精度上限 400").isEqualTo(400);
        assertThat(hugeBase.body().path("code").asText()).isEqualTo("1000C0001");
    }

    /** TB4：定价快照变更不影响已出账单；新周期按新价出账（变更只向后生效）。 */
    @Test
    void existingBillUnaffectedByPricingChange() throws Exception {
        seedContract("CO900004", "PER_CALL", new BigDecimal("3.00"), "2026-01-01 00:00:00", null);
        seedFlow("CO900004", "evt-bo-41", "2026-10-05 09:00:00");
        final JsonNode bill = outbill("CO900004", PERIOD);
        final String billNo = bill.path("billNo").asText();

        SourceSchemas.jdbcTemplateFor(CONTRACT_DB).update(
                "UPDATE contract SET price_amount = 5.00 WHERE contract_no = 'CO900004'");

        final JsonNode reread = getJson(ADMIN, "admin", BILLS + "/" + billNo).body().path("data");
        assertThat(reread.path("amount").asText()).as("已出账单金额逐字不变").isEqualTo("3.00");
        assertThat(reread.path("details").get(0).path("unitPrice").asText()).isEqualTo("3.00");

        seedFlow("CO900004", "evt-bo-42", "2026-09-05 09:00:00");
        final JsonNode next = outbill("CO900004", "2026-09");
        assertThat(next.path("amount").asText()).as("新周期按新快照出账").isEqualTo("5.00");
    }

    /** TB5：同合约同周期重复出账被拒（409 1011C0010）；并发两请求恰一成功；账单仅一行。 */
    @Test
    void duplicateOutbillRejectedIncludingConcurrent() throws Exception {
        seedContract("CO900005", "MONTHLY", new BigDecimal("20.00"), "2026-01-01 00:00:00", null);
        outbill("CO900005", PERIOD);
        final ApiResponse duplicate = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900005\",\"period\":\"" + PERIOD + "\"}");
        assertThat(duplicate.status()).as("重复出账 409").isEqualTo(409);
        assertThat(duplicate.body().path("code").asText()).isEqualTo("1011C0010");

        seedContract("CO900006", "MONTHLY", new BigDecimal("30.00"), "2026-01-01 00:00:00", null);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final Callable<Integer> task = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return postJson(ADMIN, "admin", BILLS,
                    "{\"contractNo\":\"CO900006\",\"period\":\"" + PERIOD + "\"}").status();
        };
        final List<Integer> statuses = new ArrayList<>();
        try {
            final Future<Integer> first = pool.submit(task);
            final Future<Integer> second = pool.submit(task);
            // 超时兜底：DDL 锁等待等场景必须失败而非挂死
            statuses.add(first.get(30, TimeUnit.SECONDS));
            statuses.add(second.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
        }
        assertThat(statuses).as("并发出账恰一成功一 409").containsExactlyInAnyOrder(201, 409);
        assertThat(countBills("CO900006")).as("并发下仅一行账单").isEqualTo(1);
    }

    /** TB12：免费档不出账单（手动拒绝 1011C0015，零账单新增；计量流水不受影响）。 */
    @Test
    void freeContractProducesNoBill() throws Exception {
        seedContract("CO900007", "FREE", null, "2026-01-01 00:00:00", null);
        seedFlow("CO900007", "evt-bo-71", "2026-10-05 09:00:00");

        final ApiResponse response = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900007\",\"period\":\"" + PERIOD + "\"}");
        assertThat(response.status()).as("免费档出账 409").isEqualTo(409);
        assertThat(response.body().path("code").asText()).isEqualTo("1011C0015");
        assertThat(countBills("CO900007")).as("免费档零账单").isZero();
        final Long flows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metering_flow WHERE contract_no = 'CO900007'", Long.class);
        assertThat(flows).as("免费档调用事件照常留痕").isEqualTo(1L);
    }

    /** TB7：更正单另出编号、原单逐字不变、双向可溯；更正单不可再更正；非法参数 400。 */
    @Test
    void correctionKeepsOriginalImmutable() throws Exception {
        seedContract("CO900008", "PER_CALL", new BigDecimal("3.00"), "2026-01-01 00:00:00", null);
        seedFlow("CO900008", "evt-bo-81", "2026-10-05 09:00:00");
        final JsonNode original = outbill("CO900008", PERIOD);
        final String originalNo = original.path("billNo").asText();

        final ApiResponse correction = postJson(ADMIN, "admin",
                BILLS + "/" + originalNo + "/corrections",
                "{\"amount\":-3.00,\"reason\":\"红字冲正：计量口径修正\"}");
        assertThat(correction.status()).as("更正单 201").isEqualTo(201);
        final JsonNode correctionBill = correction.body().path("data");
        assertThat(correctionBill.path("billType").asText()).isEqualTo("CORRECTION");
        assertThat(correctionBill.path("correctionOfBillNo").asText()).isEqualTo(originalNo);
        assertThat(correctionBill.path("amount").asText()).isEqualTo("-3.00");
        assertThat(correctionBill.path("billNo").asText()).isNotEqualTo(originalNo);

        final JsonNode reread = getJson(ADMIN, "admin", BILLS + "/" + originalNo).body().path("data");
        assertThat(reread.path("amount").asText()).as("原单金额逐字不变").isEqualTo("3.00");
        assertThat(reread.path("details")).hasSize(1);
        assertThat(reread.path("correctionBillNos")).hasSize(1);
        assertThat(reread.path("correctionBillNos").get(0).asText())
                .isEqualTo(correctionBill.path("billNo").asText());

        final ApiResponse nested = postJson(ADMIN, "admin",
                BILLS + "/" + correctionBill.path("billNo").asText() + "/corrections",
                "{\"amount\":1.00,\"reason\":\"不允许\"}");
        assertThat(nested.status()).as("更正单不可再更正 409").isEqualTo(409);
        assertThat(nested.body().path("code").asText()).isEqualTo("1011C0012");

        assertThat(postJson(ADMIN, "admin", BILLS + "/" + originalNo + "/corrections",
                "{\"amount\":0,\"reason\":\"零金额\"}").status()).as("更正金额 0 → 400").isEqualTo(400);
        assertThat(postJson(ADMIN, "admin", BILLS + "/" + originalNo + "/corrections",
                "{\"amount\":1.00,\"reason\":\" \"}").status()).as("原因空 → 400").isEqualTo(400);

        // 留痕原因按"前缀 + 原单号 + 输入"拼装后校验列宽（VARCHAR(512)＝前缀 17＋账单号 8）
        final ApiResponse atLimit = postJson(ADMIN, "admin",
                BILLS + "/" + originalNo + "/corrections",
                "{\"amount\":1.00,\"reason\":\"" + "x".repeat(495) + "\"}");
        assertThat(atLimit.status()).as("原因拼装后恰满 512 → 201").isEqualTo(201);
        final ApiResponse overflow = postJson(ADMIN, "admin",
                BILLS + "/" + originalNo + "/corrections",
                "{\"amount\":1.00,\"reason\":\"" + "x".repeat(496) + "\"}");
        assertThat(overflow.status()).as("原因拼装后溢出列宽 → 400（不再 500）").isEqualTo(400);
        assertThat(overflow.body().path("code").asText()).isEqualTo("1000C0001");
    }

    /** TB7/B10-2：账单号严格递增、取号行同步自增（不回绕复用）。 */
    @Test
    void billNumbersStrictlyIncrease() throws Exception {
        seedContract("CO900020", "MONTHLY", new BigDecimal("10.00"), "2026-01-01 00:00:00", null);
        final long before = nextNo();

        final String first = outbill("CO900020", PERIOD).path("billNo").asText();
        final String second = outbill("CO900020", "2026-09").path("billNo").asText();

        assertThat(first).as("账单号形态 BL+6 位").matches("BL\\d{6}");
        assertThat(Long.parseLong(second.substring(2))).as("后取号严格大于前号")
                .isGreaterThan(Long.parseLong(first.substring(2)));
        assertThat(nextNo()).as("取号行随两次出账各加 1").isEqualTo(before + 2);
    }

    /** 服务级异常映射回归（P2-7）：请求体不可读 / 必填 query 缺失 → 400 1000C0001；计量读面同码零漂移。 */
    @Test
    void malformedRequestMapsToParamInvalid() throws Exception {
        final ApiResponse unreadable = postJson(ADMIN, "admin", BILLS, "{not-a-json");
        assertThat(unreadable.status()).as("请求体不可读 400").isEqualTo(400);
        assertThat(unreadable.body().path("code").asText()).isEqualTo("1000C0001");
        assertThat(unreadable.body().path("message").asText()).isEqualTo("参数格式不合法");

        final ApiResponse missingParam = postJson(ADMIN, "admin",
                "/api/v1/billing/contracts/CO900001/reconciliations", "");
        assertThat(missingParam.status()).as("必填 query 缺失 400").isEqualTo(400);
        assertThat(missingParam.body().path("code").asText()).isEqualTo("1000C0001");

        // 计量侧共用同一服务级 advice：验证其"参数非法 → 400"类型默认映射未漂移
        // （计量 4 端点无请求体与必填 query，无法触发上面两组异常分支，属端点结构使然）
        final ApiResponse metering = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/not-a-contract/flows");
        assertThat(metering.status()).as("计量侧合约号非法 400（沿 3.7.5 口径）").isEqualTo(400);
        assertThat(metering.body().path("code").asText()).isEqualTo("1000C0001");
    }

    /** TB14：确认状态机 ISSUED → CONFIRMED；重复确认 1011C0012；动作留痕齐全。 */
    @Test
    void confirmStateMachineAndActionLog() throws Exception {
        seedContract("CO900009", "MONTHLY", new BigDecimal("12.00"), "2026-01-01 00:00:00", null);
        final String billNo = outbill("CO900009", PERIOD).path("billNo").asText();

        final ApiResponse confirmed = postJson(ADMIN, "admin", BILLS + "/" + billNo + "/confirm", "");
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body().path("data").path("status").asText()).isEqualTo("CONFIRMED");

        final ApiResponse again = postJson(ADMIN, "admin", BILLS + "/" + billNo + "/confirm", "");
        assertThat(again.status()).as("重复确认 409").isEqualTo(409);
        assertThat(again.body().path("code").asText()).isEqualTo("1011C0012");

        final JsonNode actions = getJson(ADMIN, "admin", BILLS + "/" + billNo).body()
                .path("data").path("actions");
        assertThat(actions.findValuesAsText("action")).contains("CREATE", "CONFIRM");
    }

    /** 出账条件与参数边界：未生效 / 已终止 / 无计量 / 合约不存在 / 周期非法。 */
    @Test
    void unbillableContractsAndInvalidParams() throws Exception {
        seedContract("CO900010", "PER_CALL", new BigDecimal("3.00"), "2026-11-15 00:00:00", null);
        final ApiResponse notEffective = postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900010\",\"period\":\"" + PERIOD + "\"}");
        assertThat(notEffective.status()).as("未生效 409").isEqualTo(409);
        assertThat(notEffective.body().path("code").asText()).isEqualTo("1011C0015");

        seedContract("CO900011", "MONTHLY", new BigDecimal("8.00"), "2026-01-01 00:00:00",
                "2026-09-30 00:00:00");
        assertThat(postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900011\",\"period\":\"" + PERIOD + "\"}").status())
                .as("已终止 409").isEqualTo(409);

        seedContract("CO900012", "PER_CALL", new BigDecimal("3.00"), "2026-01-01 00:00:00", null);
        assertThat(postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900012\",\"period\":\"" + PERIOD + "\"}").status())
                .as("按次无计量 409").isEqualTo(409);

        assertThat(postJson(ADMIN, "admin", BILLS, "{\"contractNo\":\"CO999999\"}").status())
                .as("合约不存在 404").isEqualTo(404);
        assertThat(postJson(ADMIN, "admin", BILLS, "{\"contractNo\":\"CO999999\"}")
                .body().path("code").asText()).isEqualTo("1011C0014");

        assertThat(postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900012\",\"period\":\"2026-13\"}").status())
                .as("周期非法 400").isEqualTo(400);
        assertThat(postJson(ADMIN, "admin", BILLS,
                "{\"contractNo\":\"CO900012\",\"period\":\"2026-12\"}").status())
                .as("未来周期 400").isEqualTo(400);
        assertThat(postJson(ADMIN, "admin", BILLS, "{\"contractNo\":\"bad\"}").status())
                .as("合约号格式非法 400").isEqualTo(400);
    }

    private long nextNo() {
        final Long nextNo = jdbcTemplate.queryForObject(
                "SELECT next_no FROM billing_bill_no_seq WHERE id = 1", Long.class);
        return nextNo == null ? 0L : nextNo;
    }

    private int countBills(final String contractNo) {
        final Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_bill WHERE contract_no = ?", Long.class, contractNo);
        return count == null ? 0 : count.intValue();
    }

    /** HTTP 状态 + 响应体（测试断言载体）。 */
    private record ApiResponse(int status, JsonNode body) {
    }
}
