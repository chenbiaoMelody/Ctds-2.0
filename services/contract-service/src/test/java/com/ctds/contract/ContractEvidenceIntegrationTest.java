package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.contract.application.PolicyExecutionService;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 合约 / 使用环节存证埋点集成测试（WBS-3.8.3 hifi §2 E3~E6、§8 T15~T20；规格 B1-1 / B1-2 / B1-3 /
 * B2-1 / B2-2 / Q5 / Q10）：公共数据授权类合约双写（授权 + 合约）且同单号同时点；非授权类单条；
 * 首签 / 重复签署零事件；放行两分支各一条 {@code USG...}；拒绝零事件；存证不可达业务仍成功。
 * 桩 = JDK HttpServer（存证内部写面，零新依赖）；资格 / 产品 / DID 三端口 {@code @MockitoBean}。
 * 本机 Docker 未运行时整类跳过（门禁不红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractEvidenceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/contracts";
    private static final String REQ = "S-req";
    private static final String PROV = "S-prov";
    private static final long PRODUCT_ID = 12L;
    private static final String PROVIDER_DID = "did:ctds:" + PROV + ".1";
    private static final String REQUESTER_DID = "did:ctds:" + REQ + ".1";
    /** CT000001 = 公共数据授权模板（V1 迁移种子；非授权类用例临时改 template_type）。 */
    private static final String TEMPLATE_NO = "CT000001";

    private static final EvidenceStub STUB;

    static {
        try {
            STUB = new EvidenceStub();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path keyFile;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_evidence");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
        registry.add("ctds.evidence.client.base-url", STUB::baseUrl);
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        keyFile = Files.createTempFile("ctds-test-evidence-keys", ".keys");
        Files.writeString(keyFile, "contract-deal-text=MDEyMzQ1Njc4OWFiY2RlZg==\n",
                StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteKeyFile() throws Exception {
        Files.deleteIfExists(keyFile);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PolicyExecutionService policyExecutionService;

    @MockitoBean
    private SubjectAdmissionPort subjectAdmissionPort;

    @MockitoBean
    private CatalogProductPort catalogProductPort;

    @MockitoBean
    private DidPort didPort;

    @BeforeEach
    void setUp() {
        STUB.reset();
        jdbcTemplate.update("UPDATE contract_template SET template_type = 'PUBLIC_DATA_AUTHORIZATION' "
                + "WHERE template_no = ?", TEMPLATE_NO);
        for (final String subject : List.of(REQ, PROV)) {
            given(subjectAdmissionPort.check(subject)).willReturn(SubjectAdmission.ADMITTED);
        }
        given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                CatalogProductPort.CatalogProductResult.found(
                        new CatalogProductPort.CatalogProduct(PRODUCT_ID, "城市餐饮单位经营数据集",
                                "LISTED", PROV, "PER_CALL", new BigDecimal("1.50"))));
        given(didPort.resolve(PROVIDER_DID)).willReturn(DidPort.DidBinding.found(PROV, "ACTIVE"));
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.found(REQ, "ACTIVE"));
        given(didPort.sign(eq(PROVIDER_DID), anyString()))
                .willReturn(DidPort.DidSignResult.signed("c2lnLXByb3ZpZGVy"));
        given(didPort.sign(eq(REQUESTER_DID), anyString()))
                .willReturn(DidPort.DidSignResult.signed("c2lnLXJlcXVlc3Rlcg"));
    }

    // ==== T15 公共数据授权类合约双签 → 2 条（授权 + 合约），同单号同时点 ====

    @Test
    void publicDataContractEmitsTwoEventsWithSameNoAndOccurredAt() throws Exception {
        final String contractNo = toEffective("双写", noRestriction());

        final List<JsonNode> events = STUB.events();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(event -> event.path("stageType").asText())
                .containsExactlyInAnyOrder("AUTHORIZATION", "CONTRACT");
        assertThat(events).extracting(event -> event.path("operationType").asText())
                .containsExactlyInAnyOrder("DATA_AUTH_CONTRACT_EFFECTIVE", "CONTRACT_EFFECTIVE");
        assertThat(events).extracting(event -> event.path("businessNo").asText())
                .containsOnly(contractNo);
        assertThat(events).extracting(event -> event.path("subjectDid").asText())
                .containsOnly(REQUESTER_DID);
        assertThat(events).extracting(event -> event.path("actorSubjectNo").asText())
                .containsOnly(REQ);
        // 双写两条同 occurredAt（均取 contract_attestation.attested_at）
        final String occurredAt = events.get(0).path("occurredAt").asText();
        assertThat(events).extracting(event -> event.path("occurredAt").asText())
                .hasSize(2).containsOnly(occurredAt);
    }

    // ==== T16 非公共数据授权类合约 → 1 条 CONTRACT_EFFECTIVE ====

    @Test
    void nonPublicDataContractEmitsSingleContractEvent() throws Exception {
        jdbcTemplate.update("UPDATE contract_template SET template_type = 'API_CALL' "
                + "WHERE template_no = ?", TEMPLATE_NO);
        toEffective("单条", noRestriction());

        final List<JsonNode> events = STUB.events();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("stageType").asText()).isEqualTo("CONTRACT");
        assertThat(events.get(0).path("operationType").asText()).isEqualTo("CONTRACT_EFFECTIVE");
    }

    // ==== T17 首签零事件；重复签署零新事件（Q10）====

    @Test
    void firstSignAndRepeatedSignEmitNoEvent() throws Exception {
        final String contractNo = initiateOk("首签");
        confirm(contractNo, REQ);
        confirm(contractNo, PROV);
        sign(contractNo, PROV, PROVIDER_DID);

        assertThat(STUB.events()).isEmpty();
        signExpectConflict(contractNo, PROV, PROVIDER_DID);
        assertThat(STUB.events()).isEmpty();
    }

    // ==== T18 放行两分支（空策略 / 配额）各 1 条 USAGE_ALLOWED ====

    @Test
    void usageAllowedEmitsEventForBothBranches() throws Exception {
        final String noStrategyContract = toEffective("空策略", noRestriction());
        assertThat(policyExecutionService.check(noStrategyContract, use()).allowed()).isTrue();
        final String quotaContract = toEffective("配额", quotaStrategy(2));
        assertThat(policyExecutionService.check(quotaContract, use()).allowed()).isTrue();

        final List<JsonNode> usage = STUB.events().stream()
                .filter(event -> "USAGE".equals(event.path("stageType").asText())).toList();
        assertThat(usage).hasSize(2);
        assertThat(usage).extracting(event -> event.path("operationType").asText())
                .containsOnly("USAGE_ALLOWED");
        assertThat(usage).extracting(event -> event.path("businessNo").asText())
                .allMatch(no -> no.matches("USG\\d{12}")).doesNotHaveDuplicates();
        assertThat(usage).extracting(event -> event.path("subjectDid").asText())
                .containsOnly(REQUESTER_DID);
        assertThat(usage).extracting(event -> event.path("actorSubjectNo").asText())
                .containsOnly(REQ);
    }

    // ==== T19 使用拒绝 → 0 事件（Q10；拒绝留痕仍存在）====

    @Test
    void usageDeniedEmitsNoEvent() throws Exception {
        final String contractNo = toEffective("拒绝零事件", quotaStrategy(1));
        assertThat(policyExecutionService.check(contractNo, use()).allowed()).isTrue();
        STUB.resetEvidence();

        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use()))
                .isInstanceOf(ContractBizException.class);
        assertThat(STUB.events()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_usage_log WHERE contract_no = ? AND outcome = 'DENIED'",
                Integer.class, contractNo)).isEqualTo(1);
    }

    // ==== T20 存证服务 500 → 签署 / 放行仍成功（fail-open，B2-1）====

    @Test
    void evidenceUnavailableKeepsSignAndUsageSuccessful() throws Exception {
        STUB.respond(500, "{\"code\":\"1012S0001\",\"message\":\"unavailable\"}");
        final String contractNo = toEffective("不可达", quotaStrategy(1));

        assertThat(policyExecutionService.check(contractNo, use()).allowed()).isTrue();
        assertThat(STUB.requests()).isPositive();
    }

    // ==== helper ====

    private String initiateOk(final String marker) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initiateBody(marker, noRestriction()))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("发起应成功: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return readBody(result).path("data").path("contractNo").asText();
    }

    private String toEffective(final String marker, final String strategyJson) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initiateBody(marker, strategyJson))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("发起应成功: %s", result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        final String contractNo = readBody(result).path("data").path("contractNo").asText();
        confirm(contractNo, REQ);
        confirm(contractNo, PROV);
        sign(contractNo, PROV, PROVIDER_DID);
        sign(contractNo, REQ, REQUESTER_DID);
        return contractNo;
    }

    private void confirm(final String contractNo, final String operator) throws Exception {
        assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/confirmations")
                        .header("X-Ctds-Subject", operator)
                        .header("X-Ctds-Roles", "provider")).andReturn()
                .getResponse().getStatus()).as("确认应成功（%s）", operator).isEqualTo(200);
    }

    private void sign(final String contractNo, final String operator, final String did)
            throws Exception {
        assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/signatures")
                        .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"did\":\"" + did + "\"}")).andReturn()
                .getResponse().getStatus()).as("签署应成功（%s）", operator).isEqualTo(201);
    }

    private void signExpectConflict(final String contractNo, final String operator, final String did)
            throws Exception {
        mockMvc.perform(post(BASE + "/" + contractNo + "/signatures")
                        .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"did\":\"" + did + "\"}"))
                .andExpect(status().isConflict());
    }

    private static UsageRequest use() {
        return new UsageRequest(REQ, UsageActionType.USE, "风控建模", "本市域");
    }

    private static String noRestriction() {
        return "{\"noRestrictionDeclared\":true}";
    }

    private static String quotaStrategy(final int maxCount) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static String initiateBody(final String marker, final String strategyJson) {
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + TEMPLATE_NO
                + "\",\"templateVersionNo\":1,\"clauseValues\":" + clauseValuesJson(marker,
                strategyJson) + "}";
    }

    private static String clauseValuesJson(final String marker, final String strategyJson) {
        return "{\"slots\":{"
                + "\"subject_matter\":\"城市餐饮单位经营数据集（" + unique(marker) + "）\","
                + "\"scope\":\"内部数据分析\","
                + "\"term\":\"2027-10-04 至 2028-10-03\","
                + "\"purpose_and_restrictions\":\"政策研究，禁止再分发\","
                + "\"security_confidentiality\":\"按平台安全规范执行\","
                + "\"liability\":\"双方违约按实际损失赔付\","
                + "\"dispute_resolution\":\"提交平台争议解决\","
                + "\"data_format_delivery\":\"库表接口拉取\","
                + "\"data_update_obligation\":\"按日更新并通知\""
                + "},\"strategy\":" + strategyJson + "}";
    }

    private static JsonNode readBody(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String unique(final String label) {
        return label + "-" + Long.toHexString(System.nanoTime());
    }

    /** 存证内部写面桩（记录载荷；可切换响应）。 */
    private static final class EvidenceStub {

        private final HttpServer server;
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        private volatile int status = 200;
        private volatile String response = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";

        EvidenceStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/evidence/internal/records", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset() {
            resetEvidence();
            status = 200;
            response = "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";
        }

        void resetEvidence() {
            bodies.clear();
        }

        void respond(final int responseStatus, final String body) {
            this.status = responseStatus;
            this.response = body;
        }

        int requests() {
            return bodies.size();
        }

        List<JsonNode> events() {
            return bodies.stream().map(body -> {
                try {
                    return MAPPER.readTree(body);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        private void handle(final HttpExchange exchange) throws IOException {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
