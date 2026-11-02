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
 * 计费读面集成（hifi §11 TB11，B12）：本方按合约 / 周期 / 产品过滤且仅含本方数据；
 * 非本方与不存在同码同文 404（1011C0009 防枚举）；运营全量检索；分页参数沿 common 口径。
 * Docker 缺失整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false", "ctds.billing.auto.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Import(FixedClockTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BillingReadIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTRACT_DB = "ctds_billing_read_contract_it";
    private static final String BILLS = "/api/v1/billing/bills";
    private static final String REQUESTER = "S-req-brd";
    private static final String PROVIDER = "S-prov-brd";
    private static final String OTHER = "S-other-brd";
    private static final String ADMIN = "S-admin-brd";
    private static final String PERIOD = "2026-10";

    @DynamicPropertySource
    static void datasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_billing_read_it");
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
        final JdbcTemplate source = SourceSchemas.jdbcTemplateFor(CONTRACT_DB);
        source.update("DELETE FROM contract");
        source.update("DELETE FROM contract_usage_log");
        source.update("DELETE FROM contract_usage_counter");
    }

    private void seedContract(final String contractNo, final long productId,
            final String requester, final String provider, final String pricingModel,
            final BigDecimal priceAmount) {
        SourceSchemas.jdbcTemplateFor(CONTRACT_DB).update(
                "INSERT INTO contract (contract_no, product_id, product_name, provider_subject_no, "
                        + "requester_subject_no, pricing_model, price_amount, status, effective_at) "
                        + "VALUES (?, ?, '测试数据集', ?, ?, ?, ?, 'EFFECTIVE', '2026-01-01 00:00:00')",
                contractNo, productId, provider, requester, pricingModel, priceAmount);
    }

    private String outbill(final String contractNo) throws Exception {
        final MvcResult result = mockMvc.perform(post(BILLS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractNo\":\"" + contractNo + "\",\"period\":\"" + PERIOD + "\"}")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("billNo").asText();
    }

    private MvcResult getJson(final String subject, final String roles, final String path)
            throws Exception {
        return mockMvc.perform(get(path)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
    }

    private JsonNode body(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 本方读面：需方 / 供方各见本方（myRole 正确），非参与方空集；过滤条件生效。 */
    @Test
    void mineScopedToOwnContracts() throws Exception {
        seedContract("CO930001", 201L, REQUESTER, PROVIDER, "MONTHLY", new BigDecimal("10.00"));
        seedContract("CO930002", 202L, OTHER, PROVIDER, "MONTHLY", new BigDecimal("20.00"));
        outbill("CO930001");
        outbill("CO930002");

        final MvcResult requesterMine = getJson(REQUESTER, "requester", BILLS + "/mine?pageSize=10");
        assertThat(requesterMine.getResponse().getStatus()).isEqualTo(200);
        final JsonNode requesterList = body(requesterMine).path("data").path("list");
        assertThat(requesterList).hasSize(1);
        assertThat(requesterList.get(0).path("contractNo").asText()).isEqualTo("CO930001");
        assertThat(requesterList.get(0).path("myRole").asText()).isEqualTo("REQUESTER");

        final JsonNode providerList = body(getJson(PROVIDER, "provider", BILLS + "/mine")).path("data")
                .path("list");
        assertThat(providerList).hasSize(2);
        assertThat(providerList.findValuesAsText("myRole")).containsOnly("PROVIDER");

        final JsonNode otherList = body(getJson(OTHER, "requester", BILLS + "/mine")).path("data")
                .path("list");
        assertThat(otherList).hasSize(1);
        assertThat(otherList.get(0).path("contractNo").asText()).isEqualTo("CO930002");

        final JsonNode filtered = body(getJson(REQUESTER, "requester",
                BILLS + "/mine?productId=202")).path("data").path("list");
        assertThat(filtered).as("按产品过滤后本方零数据").isEmpty();

        // 过滤正例（B12-1）：命中而非恒空 / 恒全
        final JsonNode byContract = body(getJson(REQUESTER, "requester",
                BILLS + "/mine?contractNo=CO930001")).path("data").path("list");
        assertThat(byContract).as("按合约过滤命中本方账单").hasSize(1);
        assertThat(byContract.get(0).path("contractNo").asText()).isEqualTo("CO930001");
        final JsonNode byPeriod = body(getJson(REQUESTER, "requester",
                BILLS + "/mine?period=" + PERIOD)).path("data").path("list");
        assertThat(byPeriod).as("按周期过滤命中本方账单").hasSize(1);
        assertThat(byPeriod.get(0).path("period").asText()).isEqualTo(PERIOD);
        final JsonNode byOtherPeriod = body(getJson(REQUESTER, "requester",
                BILLS + "/mine?period=2026-09")).path("data").path("list");
        assertThat(byOtherPeriod).as("非本期周期不命中").isEmpty();
    }

    /** 详情防枚举：非本方与不存在同码同文 404；运营可读全部；参与方可读。 */
    @Test
    void detailRejectsOutsidersWithSameShape() throws Exception {
        seedContract("CO930003", 203L, REQUESTER, PROVIDER, "MONTHLY", new BigDecimal("15.00"));
        final String billNo = outbill("CO930003");

        final MvcResult outsider = getJson(OTHER, "requester", BILLS + "/" + billNo);
        assertThat(outsider.getResponse().getStatus()).as("非本方 404").isEqualTo(404);
        final MvcResult missing = getJson(OTHER, "requester", BILLS + "/BL999999");
        assertThat(missing.getResponse().getStatus()).as("不存在 404").isEqualTo(404);
        assertThat(body(outsider).path("code").asText())
                .as("同码同文防枚举")
                .isEqualTo(body(missing).path("code").asText())
                .isEqualTo("1011C0009");
        assertThat(body(outsider).path("message").asText())
                .isEqualTo(body(missing).path("message").asText());

        assertThat(getJson(REQUESTER, "requester", BILLS + "/" + billNo).getResponse().getStatus())
                .as("需方可读").isEqualTo(200);
        assertThat(getJson(ADMIN, "admin", BILLS + "/" + billNo).getResponse().getStatus())
                .as("运营可读").isEqualTo(200);
    }

    /** 运营全量检索：状态与合约过滤生效；非运营调治理面 403（权限层）。 */
    @Test
    void governanceListsAllAndFilters() throws Exception {
        seedContract("CO930004", 204L, REQUESTER, PROVIDER, "MONTHLY", new BigDecimal("11.00"));
        seedContract("CO930005", 205L, OTHER, PROVIDER, "MONTHLY", new BigDecimal("22.00"));
        final String billNo = outbill("CO930004");
        outbill("CO930005");
        assertThat(mockMvc.perform(post(BILLS + "/" + billNo + "/confirm")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn().getResponse().getStatus()).as("确认动作生效").isEqualTo(200);

        final MvcResult all = getJson(ADMIN, "admin", BILLS + "/governance?pageSize=10");
        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(all).path("data").path("total").asLong()).isEqualTo(2L);

        final JsonNode confirmed = body(getJson(ADMIN, "admin",
                BILLS + "/governance?status=CONFIRMED")).path("data").path("list");
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.get(0).path("billNo").asText()).isEqualTo(billNo);

        final JsonNode byContract = body(getJson(ADMIN, "admin",
                BILLS + "/governance?contractNo=CO930005")).path("data").path("list");
        assertThat(byContract).hasSize(1);
        assertThat(byContract.get(0).path("contractNo").asText()).isEqualTo("CO930005");

        assertThat(getJson(REQUESTER, "requester", BILLS + "/governance").getResponse().getStatus())
                .as("非运营访问治理面 403").isEqualTo(403);
    }

    /** 分页与过滤参数边界：pageSize 越界 / 状态非法 / 周期非法 → 400。 */
    @Test
    void invalidQueryParamsRejected() throws Exception {
        seedContract("CO930006", 206L, REQUESTER, PROVIDER, "MONTHLY", new BigDecimal("9.00"));
        outbill("CO930006");

        assertThat(getJson(REQUESTER, "requester", BILLS + "/mine?pageSize=0").getResponse().getStatus())
                .as("pageSize 越界 400").isEqualTo(400);
        assertThat(getJson(REQUESTER, "requester", BILLS + "/mine?pageSize=101")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson(ADMIN, "admin", BILLS + "/governance?status=UNKNOWN")
                .getResponse().getStatus()).as("状态非法 400").isEqualTo(400);
        assertThat(getJson(REQUESTER, "requester", BILLS + "/mine?period=2026-13")
                .getResponse().getStatus()).as("周期非法 400").isEqualTo(400);
        assertThat(getJson(REQUESTER, "requester", BILLS + "/mine?contractNo=bad")
                .getResponse().getStatus()).as("合约号形态非法 400").isEqualTo(400);
        assertThat(getJson(ADMIN, "admin", BILLS + "/governance?contractNo=bad")
                .getResponse().getStatus()).as("治理面合约号形态非法 400").isEqualTo(400);
        assertThat(getJson(REQUESTER, "requester", BILLS + "/bad")
                .getResponse().getStatus()).as("账单号形态非法 400（非 404）").isEqualTo(400);
    }

    /** 对账结论读面：非参与方 404；合约不存在 404（1011C0014）。 */
    @Test
    void reconciliationReadScopedToParticipants() throws Exception {
        seedContract("CO930007", 207L, REQUESTER, PROVIDER, "MONTHLY", new BigDecimal("13.00"));
        outbill("CO930007");

        assertThat(getJson(OTHER, "requester",
                "/api/v1/billing/contracts/CO930007/reconciliations").getResponse().getStatus())
                .as("非参与方 404").isEqualTo(404);
        assertThat(getJson(REQUESTER, "requester",
                "/api/v1/billing/contracts/CO930007/reconciliations").getResponse().getStatus())
                .as("参与方可读").isEqualTo(200);
        final MvcResult missing = getJson(REQUESTER, "requester",
                "/api/v1/billing/contracts/CO999999/reconciliations");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(missing).path("code").asText()).isEqualTo("1011C0014");
    }
}
