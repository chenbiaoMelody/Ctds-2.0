package com.ctds.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.metering.support.SharedMySqlContainer;
import com.ctds.metering.support.SourceSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 本方读面集成（hifi §8 T1/T5/T6/T8）：流水逐条可溯（倒序）、自然月聚合（period 降序 +
 * lastEventAt）、免费档事件留痕完整（B8-2）、归属校验同码同文 404 防枚举（B8-4/1011C0008）。
 * 计量流水以事件行造数隔离（通道 1 网关事件落库语义由 common-logging 单测承载——hifi §8
 * 测试边界声明）；Docker 缺失整类跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class MeteringFlowReadIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DELIVERY_DB = "ctds_metering_r1_delivery_it";
    private static final String CONTRACT_DB = "ctds_metering_r1_contract_it";
    private static final String REQUESTER = "S-req-r1";
    private static final String PROVIDER = "S-prov-r1";
    private static final String OTHER = "S-other-r1";
    private static final String ADMIN = "S-admin-r1";

    @DynamicPropertySource
    static void meteringDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_read_it");
        registry.add("ctds.metering.source-schema.delivery", () -> DELIVERY_DB);
        registry.add("ctds.metering.source-schema.contract", () -> CONTRACT_DB);
    }

    @BeforeAll
    static void prepareSourceSchemas() {
        SourceSchemas.createDeliveryTables(SourceSchemas.jdbcTemplateFor(DELIVERY_DB));
        SourceSchemas.createContractTables(SourceSchemas.jdbcTemplateFor(CONTRACT_DB));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedContract(final String contractNo, final String requester,
            final String provider, final String pricingModel) {
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract "
                        + "(contract_no, requester_subject_no, provider_subject_no, pricing_model) "
                        + "VALUES (?, ?, ?, ?)", contractNo, requester, provider, pricingModel);
    }

    private void seedFlow(final String contractNo, final String sourceType, final String sourceNo,
            final String action, final String occurredAt) {
        jdbcTemplate.update("INSERT INTO metering_flow "
                        + "(contract_no, requester_no, source_type, source_no, action, resource, "
                        + "occurred_at) VALUES (?, 'S-req-r1', ?, ?, ?, 'delivery-catalog-products GET /x', ?)",
                contractNo, sourceType, sourceNo, action, occurredAt);
    }

    private JsonNode getJson(final String subject, final String roles, final String path)
            throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 负向读数变体（评审修复轮 F2）：同 getJson 并锚定 HTTP 状态（hifi §8 T8 404 同形 / 1000C0001 → 400）。 */
    private JsonNode getJson(final String subject, final String roles, final String path,
            final ResultMatcher expectedStatus) throws Exception {
        final MvcResult result = mockMvc.perform(get(path)
                        .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles))
                .andExpect(expectedStatus)
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T1 正向（hifi §8）：3 条网关事件行 → 流水 = 3 条逐条可溯（时点/动作/合约/主体，倒序）。 */
    @Test
    void flowsListedDescWithOwnership() throws Exception {
        seedContract("CO300002", REQUESTER, PROVIDER, "PER_CALL");
        seedFlow("CO300002", "GATEWAY_API", "evt-1", "gateway.delivery.metering",
                "2026-10-01 09:00:00");
        seedFlow("CO300002", "GATEWAY_API", "evt-2", "gateway.delivery.metering",
                "2026-10-02 09:00:00");
        seedFlow("CO300002", "GATEWAY_API", "evt-3", "gateway.delivery.metering",
                "2026-10-03 09:00:00");

        final JsonNode body = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/CO300002/flows");
        assertThat(body.path("code").asText()).isEqualTo("0");
        final JsonNode data = body.path("data");
        assertThat(data.path("contractNo").asText()).isEqualTo("CO300002");
        assertThat(data.path("requesterNo").asText()).isEqualTo(REQUESTER);
        assertThat(data.path("providerNo").asText()).isEqualTo(PROVIDER);
        final JsonNode items = data.path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("sourceNo").asText()).as("occurred_at 倒序").isEqualTo("evt-3");
        assertThat(items.get(2).path("sourceNo").asText()).isEqualTo("evt-1");
        assertThat(items.get(0).path("sourceType").asText()).isEqualTo("GATEWAY_API");
        assertThat(items.get(0).path("action").asText()).isEqualTo("gateway.delivery.metering");
    }

    /** T5 聚合（hifi §8）：跨两个自然月 → 两期聚合（period 降序、lastEventAt 准确）。 */
    @Test
    void summaryAggregatesByNaturalMonthDesc() throws Exception {
        seedContract("CO300003", REQUESTER, PROVIDER, "PER_CALL");
        seedFlow("CO300003", "GATEWAY_API", "evt-m1", "gateway.delivery.metering",
                "2026-09-05 10:00:00");
        seedFlow("CO300003", "GATEWAY_API", "evt-m2", "gateway.delivery.metering",
                "2026-10-11 08:00:00");
        seedFlow("CO300003", "GATEWAY_API", "evt-m3", "gateway.delivery.metering",
                "2026-10-11 09:00:00");

        final JsonNode data = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/CO300003/summary").path("data");
        assertThat(data.path("contractNo").asText()).isEqualTo("CO300003");
        final JsonNode periods = data.path("periods");
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).path("period").asText()).as("自然月降序").isEqualTo("2026-10");
        assertThat(periods.get(0).path("flowCount").asLong()).isEqualTo(2);
        assertThat(periods.get(0).path("lastEventAt").asText()).contains("2026-10-11T09:00");
        assertThat(periods.get(1).path("period").asText()).isEqualTo("2026-09");
        assertThat(periods.get(1).path("flowCount").asLong()).isEqualTo(1);
    }

    /** T6 免费档（hifi §8）：FREE 合约调用事件 → 流水照常落、读面可查完整（B8-2）。 */
    @Test
    void freeContractEventsFullyVisible() throws Exception {
        seedContract("CO300004", REQUESTER, PROVIDER, "FREE");
        seedFlow("CO300004", "GATEWAY_API", "evt-free-1", "gateway.delivery.metering",
                "2026-10-06 12:00:00");

        final JsonNode flows = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/CO300004/flows").path("data");
        assertThat(flows.path("items")).hasSize(1);
        final JsonNode periods = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/CO300004/summary").path("data").path("periods");
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).path("flowCount").asLong()).isEqualTo(1);
    }

    /** T8 负向（hifi §8）：非本方 → 404 + 1011C0008；合约不存在 → 同码同文（防枚举）。 */
    @Test
    void nonMemberAndMissingContractShareSameCodeAndMessage() throws Exception {
        seedContract("CO300005", REQUESTER, PROVIDER, "PER_CALL");
        seedFlow("CO300005", "GATEWAY_API", "evt-5", "gateway.delivery.metering",
                "2026-10-05 09:00:00");

        final JsonNode other = getJson(OTHER, "requester",
                "/api/v1/metering/contracts/CO300005/flows", status().isNotFound());
        assertThat(other.path("code").asText()).isEqualTo("1011C0008");
        assertThat(other.path("message").asText()).isEqualTo("计量数据不存在或不可读");

        final JsonNode missing = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/CO999999/flows", status().isNotFound());
        assertThat(missing.path("code").asText())
                .as("同码同文防枚举：不存在与非本方同形").isEqualTo("1011C0008");
        assertThat(missing.path("message").asText()).isEqualTo("计量数据不存在或不可读");
    }

    /** T8 双方可读：需求方与提供方均为本方（B8-4 归属校验按双方字段实时判定）。 */
    @Test
    void bothContractPartiesCanRead() throws Exception {
        seedContract("CO300006", REQUESTER, PROVIDER, "PER_CALL");
        seedFlow("CO300006", "GATEWAY_API", "evt-6", "gateway.delivery.metering",
                "2026-10-06 09:00:00");

        assertThat(getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/CO300006/flows").path("code").asText()).isEqualTo("0");
        assertThat(getJson(PROVIDER, "provider",
                "/api/v1/metering/contracts/CO300006/flows").path("code").asText()).isEqualTo("0");
    }

    /** T8 交叉钉：admin 持 metering.read 但非合约双方 → 仍 404（归属校验硬于权限，hifi §6）。 */
    @Test
    void adminOutsideOwnershipStillDenied() throws Exception {
        seedContract("CO300007", REQUESTER, PROVIDER, "PER_CALL");
        seedFlow("CO300007", "GATEWAY_API", "evt-7", "gateway.delivery.metering",
                "2026-10-07 09:00:00");

        final JsonNode body = getJson(ADMIN, "admin",
                "/api/v1/metering/contracts/CO300007/flows", status().isNotFound());
        assertThat(body.path("code").asText()).isEqualTo("1011C0008");
    }

    /** 参数面（hifi §1 R-M1）：合约号非法格式 → 1000C0001（参数通用码）。 */
    @Test
    void invalidContractNoIsParamInvalid() throws Exception {
        final JsonNode body = getJson(REQUESTER, "requester",
                "/api/v1/metering/contracts/not-a-contract/flows", status().isBadRequest());
        assertThat(body.path("code").asText()).isEqualTo("1000C0001");
    }

    /** 权限面（hifi §6）：本方读面 = metering.read，未映射角色 403。 */
    @Test
    void readRequiresMeteringReadPermission() throws Exception {
        mockMvc.perform(get("/api/v1/metering/contracts/CO300001/flows")
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "guest"))
                .andExpect(status().isForbidden());
    }

    /** 权限面（hifi §6）：勾稽读数 = metering.governance（仅 admin），普通档 403。 */
    @Test
    void reconcileRequiresGovernancePermission() throws Exception {
        mockMvc.perform(get("/api/v1/metering/contracts/CO300001/reconcile")
                        .header("X-Ctds-Subject", REQUESTER)
                        .header("X-Ctds-Roles", "requester"))
                .andExpect(status().isForbidden());
    }
}
