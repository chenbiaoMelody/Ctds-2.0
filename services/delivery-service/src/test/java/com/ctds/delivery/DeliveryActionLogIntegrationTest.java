package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
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
 * 交付域动作留痕读面集成测试（WBS-3.5.6 hifi §7 T16 / Q5-A G7 端点）：
 * 参与方分页读取（含他人越权探测 denied 行——C-5.1 S3-7 承载）、非参与方与"合约不存在"
 * 同码同文 1009C0007 防枚举、治理路径读即留痕、参与方路径读取零新增留痕（沿 3.4.7 R13
 * 防递归口径）、权限层先拦形态、分页边界；零新表零新码零迁移（delivery_action_log 既有表）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.delivery.file.max-bytes=1024"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DeliveryActionLogIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final Path SOURCE_ROOT;
    private static final Path STORAGE_ROOT;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String OTHER = "S-other";
    private static final String OPERATOR = "S-op";
    private static final String CONTRACT = "CO000001";

    static {
        try {
            STUB = new StubDependencyServer();
            SOURCE_ROOT = Files.createTempDirectory("w356-logs-source-");
            STORAGE_ROOT = Files.createTempDirectory("w356-logs-storage-");
            KEY_FILE = Files.createTempFile("w356-logs-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, "delivery-file=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_delivery_logs_it");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.source-root", SOURCE_ROOT::toString);
        registry.add("ctds.delivery.storage-root", STORAGE_ROOT::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM delivery");
        jdbc.update("DELETE FROM delivery_action_log");
        insertDelivery("DL950001", CONTRACT);
        insertDelivery("DL950002", "CO000099");
        insertLog(null, CONTRACT, "S-evil", "delivery.denied", "DENIED", "1009C0003");
        insertLog("DL950001", CONTRACT, REQUESTER, "delivery.create", "SUCCESS", null);
        insertLog("DL950001", CONTRACT, PROVIDER, "delivery.read", "SUCCESS", null);
        insertLog("DL950002", "CO000099", REQUESTER, "delivery.create", "SUCCESS", null);
    }

    private void insertDelivery(final String deliveryNo, final String contractNo) {
        jdbc.update("INSERT INTO delivery(delivery_no, contract_no, product_id, product_name,"
                + " delivery_type, requester_no, provider_no, result, idempotency_key)"
                + " VALUES (?, ?, 13, '演示数据集', 'FILE', ?, ?, 'SUCCESS', ?)",
                deliveryNo, contractNo, REQUESTER, PROVIDER, "idem-" + deliveryNo);
    }

    private void insertLog(final String deliveryNo, final String contractNo, final String operatorNo,
            final String action, final String result, final String reasonCode) {
        jdbc.update("INSERT INTO delivery_action_log(delivery_no, contract_no, operator_no,"
                + " action, result, reason_code) VALUES (?, ?, ?, ?, ?, ?)",
                deliveryNo, contractNo, operatorNo, action, result, reasonCode);
    }

    private MvcResult getLogs(final String path, final String subject, final String roles,
            final String query) throws Exception {
        return mockMvc.perform(get(path + query)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
    }

    private JsonNode dataOf(final MvcResult result) throws Exception {
        final JsonNode root = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("code").asText()).isEqualTo("0");
        return root.path("data");
    }

    @Test
    void participantReadsPagedLogsIncludingDeniedProbe() throws Exception {
        final MvcResult paged = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=1&pageSize=2");
        final JsonNode paging = dataOf(paged);
        assertThat(paging.path("total").asLong()).isEqualTo(3L);
        assertThat(paging.path("list").size()).isEqualTo(2);
        final MvcResult result = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=1&pageSize=10");
        final JsonNode data = dataOf(result);
        assertThat(data.path("list").size()).isEqualTo(3);
        boolean deniedProbeVisible = false;
        for (final JsonNode row : data.path("list")) {
            assertThat(row.has("id")).isTrue();
            assertThat(row.has("operatorNo")).isTrue();
            assertThat(row.has("action")).isTrue();
            assertThat(row.has("result")).isTrue();
            assertThat(row.has("createdAt")).isTrue();
            assertThat(row.path("contractNo").asText()).isEqualTo(CONTRACT);
            if ("S-evil".equals(row.path("operatorNo").asText())) {
                deniedProbeVisible = true;
                assertThat(row.path("action").asText()).isEqualTo("delivery.denied");
                assertThat(row.path("result").asText()).isEqualTo("DENIED");
                assertThat(row.path("reasonCode").asText()).isEqualTo("1009C0003");
            }
        }
        assertThat(deniedProbeVisible)
                .as("参与方视图必须可见他人对本合约的越权探测 denied 行（C-5.1 S3-7）")
                .isTrue();
    }

    @Test
    void providerParticipantAlsoReads() throws Exception {
        final MvcResult result = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                PROVIDER, "provider", "?pageNum=1&pageSize=10");
        assertThat(dataOf(result).path("total").asLong()).isEqualTo(3L);
    }

    @Test
    void nonParticipantAndUnknownContractSameShape() throws Exception {
        final MvcResult other = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                OTHER, "requester", "?pageNum=1&pageSize=10");
        final MvcResult unknown = getLogs("/api/v1/deliveries/CO-ZZZ-999/action-logs",
                REQUESTER, "requester", "?pageNum=1&pageSize=10");
        final String otherBody = other.getResponse().getContentAsString();
        final String unknownBody = unknown.getResponse().getContentAsString();
        assertThat(otherBody).contains("1009C0007").contains("交付记录不存在或无权查看");
        assertThat(otherBody).isEqualTo(unknownBody);
    }

    @Test
    void governanceReadsAllContractsAndTraces() throws Exception {
        final long before = countLogs();
        final MvcResult result = getLogs("/api/v1/deliveries/governance/" + CONTRACT
                + "/action-logs", OPERATOR, "admin", "?pageNum=1&pageSize=10");
        assertThat(dataOf(result).path("total").asLong()).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_action_log"
                + " WHERE contract_no = ? AND operator_no = ? AND action = 'delivery.governance.read'",
                Long.class, CONTRACT, OPERATOR))
                .as("治理路径读即留痕（GOVERNANCE_READ 行）").isGreaterThanOrEqualTo(1L);
        assertThat(countLogs()).isEqualTo(before + 1);
    }

    @Test
    void governanceUnknownContractReturnsEmptyPageWithoutDenial() throws Exception {
        final MvcResult result = getLogs("/api/v1/deliveries/governance/CO-ZZZ-999/action-logs",
                OPERATOR, "admin", "?pageNum=1&pageSize=10");
        final JsonNode data = dataOf(result);
        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("list").size()).isZero();
    }

    @Test
    void participantReadAddsNoNewLogRows() throws Exception {
        final long before = countLogs();
        getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=1&pageSize=10");
        assertThat(countLogs()).as("参与方路径读取零新增留痕（沿 R13 防递归口径）").isEqualTo(before);
    }

    @Test
    void permissionLayerBlocksParticipantRolesFromGovernancePath() throws Exception {
        final MvcResult requesterCall = getLogs("/api/v1/deliveries/governance/" + CONTRACT
                + "/action-logs", REQUESTER, "requester", "?pageNum=1&pageSize=10");
        assertThat(requesterCall.getResponse().getContentAsString())
                .contains("1000C0005").contains("无权限执行该操作");
        final MvcResult providerCall = getLogs("/api/v1/deliveries/governance/" + CONTRACT
                + "/action-logs", PROVIDER, "provider", "?pageNum=1&pageSize=10");
        assertThat(providerCall.getResponse().getContentAsString()).contains("1000C0005");
    }

    @Test
    void pageSizeBoundsRejected() throws Exception {
        final MvcResult zero = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=1&pageSize=0");
        assertThat(zero.getResponse().getContentAsString()).contains("\"code\"");
        assertThat(zero.getResponse().getContentAsString()).doesNotContain("\"code\":\"0\"");
        final MvcResult over = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=1&pageSize=101");
        assertThat(over.getResponse().getContentAsString()).doesNotContain("\"code\":\"0\"");
    }

    /** DB-53④：pageNum 非数字 → 类型错配 400 + 1000C0001（沿 common-pagination 口径同码同文）。 */
    @Test
    void nonNumericPageNumRejectedAsParamInvalid() throws Exception {
        final MvcResult result = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=abc&pageSize=10");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        final String body = result.getResponse().getContentAsString();
        assertThat(body).contains("1000C0001").contains("参数格式不合法");
        assertThat(body).doesNotContain("\"code\":\"0\"");
    }

    @Test
    void beyondRangePageReturnsEmptyList() throws Exception {
        final MvcResult result = getLogs("/api/v1/deliveries/" + CONTRACT + "/action-logs",
                REQUESTER, "requester", "?pageNum=999&pageSize=10");
        final JsonNode data = dataOf(result);
        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asLong()).isEqualTo(3L);
    }

    private long countLogs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM delivery_action_log", Long.class);
    }
}
