package com.ctds.metering.interfaces;

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
 * 运营全量计量流水读面集成（WBS-3.7.7 hifi §1 R-M4 / §9 T16）：
 * ① 分页与 occurred_at 倒序；② 合约 / 主体 / 源类型 / 时间区间过滤；
 * ③ 非运营 403；④ 非法参数 400 1000C0001（含 from &gt; to）；⑤ 3.7.5 既有 R-M1 归属硬校验零回归。
 * Docker 缺失整类跳过（沿既有集成用例口径）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class MeteringGovernanceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DELIVERY_DB = "ctds_metering_gov_delivery_it";
    private static final String CONTRACT_DB = "ctds_metering_gov_contract_it";
    private static final String REQUESTER = "S-req-gov";
    private static final String PROVIDER = "S-prov-gov";
    private static final String OTHER = "S-other-gov";
    private static final String ADMIN = "S-admin-gov";
    private static final String PATH = "/api/v1/metering/flows/governance";

    @DynamicPropertySource
    static void meteringDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_metering_gov_it");
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

    private void seedContract(final String contractNo, final String requester, final String provider) {
        jdbcTemplate.update("INSERT INTO " + CONTRACT_DB + ".contract "
                + "(contract_no, requester_subject_no, provider_subject_no, pricing_model) "
                + "VALUES (?, ?, ?, 'PER_CALL')", contractNo, requester, provider);
    }

    private void seedFlow(final String contractNo, final String requesterNo, final String sourceType,
            final String sourceNo, final String occurredAt) {
        jdbcTemplate.update("INSERT INTO metering_flow "
                        + "(contract_no, requester_no, source_type, source_no, action, resource, occurred_at) "
                        + "VALUES (?, ?, ?, ?, 'gateway.delivery.metering', 'delivery-catalog-products GET /x', ?)",
                contractNo, requesterNo, sourceType, sourceNo, occurredAt);
    }

    private JsonNode getJson(final String subject, final String roles, final String query)
            throws Exception {
        final MvcResult result = mockMvc.perform(get(PATH + query)
                        .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(final String subject, final String roles, final String query,
            final ResultMatcher expectedStatus) throws Exception {
        final MvcResult result = mockMvc.perform(get(PATH + query)
                        .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles))
                .andExpect(expectedStatus)
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** T16① 全量分页：3 条 → total=3、occurred_at 倒序、九字段齐备。 */
    @Test
    void governanceFlowsPagedAndOrderedDesc() throws Exception {
        seedFlow("CO400001", REQUESTER, "GATEWAY_API", "gov-evt-1", "2026-10-01 09:00:00");
        seedFlow("CO400001", REQUESTER, "GATEWAY_API", "gov-evt-2", "2026-10-02 09:00:00");
        seedFlow("CO400001", REQUESTER, "GATEWAY_API", "gov-evt-3", "2026-10-03 09:00:00");

        final JsonNode body = getJson(ADMIN, "admin", "?contractNo=CO400001&pageNum=1&pageSize=10");
        assertThat(body.path("code").asText()).isEqualTo("0");
        final JsonNode data = body.path("data");
        assertThat(data.path("total").asLong()).isEqualTo(3L);
        assertThat(data.path("pageNum").asInt()).isEqualTo(1);
        assertThat(data.path("pageSize").asInt()).isEqualTo(10);
        assertThat(data.path("totalPages").asInt()).isEqualTo(1);
        final JsonNode list = data.path("list");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).path("sourceNo").asText()).as("occurred_at 倒序").isEqualTo("gov-evt-3");
        assertThat(list.get(2).path("sourceNo").asText()).isEqualTo("gov-evt-1");
        final JsonNode first = list.get(0);
        assertThat(first.path("id").asLong()).isPositive();
        assertThat(first.path("contractNo").asText()).isEqualTo("CO400001");
        assertThat(first.path("requesterNo").asText()).isEqualTo(REQUESTER);
        assertThat(first.path("sourceType").asText()).isEqualTo("GATEWAY_API");
        assertThat(first.path("action").asText()).isEqualTo("gateway.delivery.metering");
        assertThat(first.path("resource").asText()).isEqualTo("delivery-catalog-products GET /x");
        assertThat(first.path("occurredAt").asText()).contains("2026-10-03T09:00");
        assertThat(first.path("createdAt").asText()).isNotBlank();
    }

    /** T16① 分页边界：pageSize=2 → 首页 2 条、totalPages=2、第二页 1 条。 */
    @Test
    void governanceFlowsSecondPage() throws Exception {
        seedFlow("CO400002", REQUESTER, "GATEWAY_API", "gov2-evt-1", "2026-10-01 09:00:00");
        seedFlow("CO400002", REQUESTER, "GATEWAY_API", "gov2-evt-2", "2026-10-02 09:00:00");
        seedFlow("CO400002", REQUESTER, "GATEWAY_API", "gov2-evt-3", "2026-10-03 09:00:00");

        final JsonNode first = getJson(ADMIN, "admin", "?contractNo=CO400002&pageNum=1&pageSize=2").path("data");
        assertThat(first.path("list")).hasSize(2);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);

        final JsonNode second = getJson(ADMIN, "admin", "?contractNo=CO400002&pageNum=2&pageSize=2").path("data");
        assertThat(second.path("list")).hasSize(1);
        assertThat(second.path("list").get(0).path("sourceNo").asText()).isEqualTo("gov2-evt-1");
    }

    /** T16① 边界：空结果仍 200（total=0、list 空、totalPages=0），不误判为 404。 */
    @Test
    void governanceFlowsEmptyResult() throws Exception {
        final JsonNode data = getJson(ADMIN, "admin", "?contractNo=CO400009&pageNum=1&pageSize=10").path("data");
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("list")).isEmpty();
        assertThat(data.path("totalPages").asInt()).isZero();
    }

    /** T16④ 分页边界：pageSize &gt; 100 与 pageNum &gt; 10000 一律 400 1000C0001；越页返回空列表。 */
    @Test
    void governancePaginationLimits() throws Exception {
        seedFlow("CO400010", REQUESTER, "GATEWAY_API", "gov10-evt-1", "2026-10-01 09:00:00");

        for (final String query : new String[] {"?pageSize=101", "?pageNum=10001"}) {
            final JsonNode body = getJson(ADMIN, "admin", query, status().isBadRequest());
            assertThat(body.path("code").asText()).as(query).isEqualTo("1000C0001");
        }

        final JsonNode beyond = getJson(ADMIN, "admin", "?contractNo=CO400010&pageNum=999&pageSize=10").path("data");
        assertThat(beyond.path("total").asLong()).as("越页时 total 仍为真实总数").isEqualTo(1L);
        assertThat(beyond.path("list")).isEmpty();
    }

    /** T16② 主体 / 源类型过滤。 */
    @Test
    void filtersByRequesterAndSourceType() throws Exception {
        seedFlow("CO400003", REQUESTER, "GATEWAY_API", "gov3-evt-1", "2026-10-01 09:00:00");
        seedFlow("CO400003", OTHER, "FILE_DELIVERY", "gov3-evt-2", "2026-10-02 09:00:00");

        final JsonNode byRequester = getJson(ADMIN, "admin",
                "?contractNo=CO400003&requesterNo=" + OTHER).path("data");
        assertThat(byRequester.path("total").asLong()).isEqualTo(1L);
        assertThat(byRequester.path("list").get(0).path("sourceNo").asText()).isEqualTo("gov3-evt-2");

        final JsonNode bySourceType = getJson(ADMIN, "admin",
                "?contractNo=CO400003&sourceType=GATEWAY_API").path("data");
        assertThat(bySourceType.path("total").asLong()).isEqualTo(1L);
        assertThat(bySourceType.path("list").get(0).path("sourceType").asText()).isEqualTo("GATEWAY_API");
    }

    /** T16② 时间区间过滤（闭区间，含边界点）。 */
    @Test
    void filtersByTimeRange() throws Exception {
        seedFlow("CO400004", REQUESTER, "GATEWAY_API", "gov4-evt-1", "2026-10-01 09:00:00");
        seedFlow("CO400004", REQUESTER, "GATEWAY_API", "gov4-evt-2", "2026-10-15 09:00:00");
        seedFlow("CO400004", REQUESTER, "GATEWAY_API", "gov4-evt-3", "2026-10-31 09:00:00");

        final JsonNode data = getJson(ADMIN, "admin",
                "?contractNo=CO400004&from=2026-10-01 09:00:00&to=2026-10-15 09:00:00").path("data");
        assertThat(data.path("total").asLong()).isEqualTo(2L);
        assertThat(data.path("list").get(0).path("sourceNo").asText()).isEqualTo("gov4-evt-2");
        assertThat(data.path("list").get(1).path("sourceNo").asText()).isEqualTo("gov4-evt-1");
    }

    /** T16③ 权限面：本方读面角色（requester）→ 403（运营专属读面）。 */
    @Test
    void governanceRequiresMeteringGovernance() throws Exception {
        mockMvc.perform(get(PATH + "?pageNum=1&pageSize=10")
                        .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH + "?pageNum=1&pageSize=10")
                        .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "provider"))
                .andExpect(status().isForbidden());
    }

    /** T16④ 参数面：非法合约号 / 源类型 / 时间格式 / from &gt; to 一律 400 1000C0001。 */
    @Test
    void invalidParametersAreParamInvalid() throws Exception {
        final String[] queries = {
            "?contractNo=not-a-contract",
            "?sourceType=UNKNOWN_TYPE",
            "?from=2026-13-01 09:00:00",
            "?from=2026-10-31 09:00:00&to=2026-10-01 09:00:00",
        };
        for (final String query : queries) {
            final JsonNode body = getJson(ADMIN, "admin", query, status().isBadRequest());
            assertThat(body.path("code").asText()).as(query).isEqualTo("1000C0001");
        }
    }

    /** T16⑤ 零回归：3.7.5 既有 R-M1 非当事方仍 404 1011C0008（两套读面并存互不影响）。 */
    @Test
    void ownershipReadSideUnchanged() throws Exception {
        seedContract("CO400005", REQUESTER, PROVIDER);
        seedFlow("CO400005", REQUESTER, "GATEWAY_API", "gov5-evt-1", "2026-10-01 09:00:00");

        final JsonNode other = MAPPER.readTree(mockMvc.perform(
                        get("/api/v1/metering/contracts/CO400005/flows")
                                .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "requester"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(other.path("code").asText()).isEqualTo("1011C0008");

        final JsonNode admin = MAPPER.readTree(mockMvc.perform(
                        get("/api/v1/metering/contracts/CO400005/flows")
                                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(admin.path("code").asText()).as("admin 非当事方仍 404（归属硬校验）").isEqualTo("1011C0008");

        // 新读面：运营可读同一合约全量流水（两套读面并存）
        final JsonNode governance = getJson(ADMIN, "admin", "?contractNo=CO400005").path("data");
        assertThat(governance.path("total").asLong()).isEqualTo(1L);
    }
}
