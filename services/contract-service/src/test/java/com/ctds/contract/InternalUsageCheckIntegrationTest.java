package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 合约域内部判定端点集成测试（WBS-3.5.2 hifi §9 T9）：真实 MySQL 8 容器实跑 Flyway；
 * 资格 / 产品 / DID 三端口 {@code @MockitoBean}（沿既有基座先例）。
 *
 * <p>用例承载：权限正反（gateway-internal 可调 / 业务角色不可调）、参与方可见性防枚举同码同文、
 * 越界与状态失效原码、参数非法、放行计数与流水对账（放行 1 次 ≡ 计数 1）；断言"不含条款原文与
 * 数据本体"（响应字段集 = 判定摘要 + 参与方摘要）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class InternalUsageCheckIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/contracts";
    private static final String INTERNAL = BASE + "/internal/usage-checks";
    private static final String GATEWAY_SUBJECT = "gateway-service";
    private static final String GATEWAY_ROLES = "gateway-internal";
    private static final String REQ = "S-req";
    private static final String PROV = "S-prov";
    private static final String OTHER = "S-other";
    private static final long PRODUCT_ID = 12L;
    private static final String PROVIDER_DID = "did:ctds:" + PROV + ".1";
    private static final String REQUESTER_DID = "did:ctds:" + REQ + ".1";
    private static final String TEMPLATE_NO = "CT000001";
    private static final String C0008 = "1008C0008";
    private static final String C0012 = "1008C0012";
    private static final String C0013 = "1008C0013";
    private static final String C0020 = "1008C0020";
    private static final LocalDate FIXED_TODAY = LocalDate.of(2027, 6, 15);

    private static Path keyFile;

    /** 测试固定钟（沿策略执行集成测试先例：判定日与造数同源，消除跨午夜竞态）。 */
    @TestConfiguration
    static class FixedEngineClockConfig {

        @Bean
        @Primary
        Clock fixedEngineClock() {
            return Clock.fixed(FIXED_TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault());
        }
    }

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_internal_it");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        keyFile = Files.createTempFile("ctds-test-internal-keys", ".keys");
        Files.writeString(keyFile, "contract-deal-text=MDEyMzQ1Njc4OWFiY2RlZg==\n",
                StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteKeyFile() throws Exception {
        Files.deleteIfExists(keyFile);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubjectAdmissionPort subjectAdmissionPort;
    @MockitoBean
    private CatalogProductPort catalogProductPort;
    @MockitoBean
    private DidPort didPort;

    @BeforeEach
    void stubPorts() {
        for (final String subject : List.of(REQ, PROV, OTHER)) {
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

    // ==== 权限正反（yml 授权真链） ====

    @Test
    void gatewayInternalIdentityReceivesVerdictAndContractSummary() throws Exception {
        final String contractNo = toEffective(unique("I1"), quotaStrategy(3));

        final MvcResult result = internalCheck("{\"contractNo\":\"" + contractNo
                + "\",\"requesterNo\":\"" + REQ + "\",\"actionType\":\"USE\",\"purpose\":\"风控建模\"}",
                GATEWAY_SUBJECT, GATEWAY_ROLES);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = readBody(result).path("data");
        assertThat(data.path("verdict").path("allowed").asBoolean()).isTrue();
        assertThat(data.path("verdict").path("usedCount").asInt()).as("放行即计数").isEqualTo(1);
        assertThat(data.path("contract").path("applicantNo").asText()).as("需求方（发起人唯一校验依据）")
                .isEqualTo(REQ);
        assertThat(data.path("contract").path("providerNo").asText()).isEqualTo(PROV);
        assertThat(data.path("contract").path("status").asText()).isEqualTo("EFFECTIVE");
        assertThat(data.toString()).as("出站不含条款原文与数据本体")
                .doesNotContain("slots").doesNotContain("subject_matter").doesNotContain("scope");
    }

    @Test
    void businessRolesCannotCallInternalJudge() throws Exception {
        final String contractNo = toEffective(unique("I2"), quotaStrategy(3));
        final String body = "{\"contractNo\":\"" + contractNo + "\",\"requesterNo\":\"" + REQ
                + "\",\"actionType\":\"USE\"}";

        assertThat(statusOf(requireBody(REQ, "provider", body))).isEqualTo(403);
        assertThat(statusOf(requireBody(PROV, "provider", body))).isEqualTo(403);
        // 未认证（无身份头）→ 401
        assertThat(statusOf(mockMvc.perform(post(INTERNAL)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn())).isEqualTo(401);
        assertThat(usageCount(contractNo)).as("权限拒绝不得计数").isZero();
    }

    // ==== 内部只读摘要（WBS-3.5.3 文件交付准入；不计数） ====

    @Test
    void internalSummaryReturnsProductAndParticipantsWithoutCounting() throws Exception {
        final String contractNo = toEffective(unique("S1"), quotaStrategy(3));
        final long before = usageCount(contractNo);
        final MvcResult result = internalSummary(contractNo, REQ, "delivery-service",
                "delivery-internal");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = readBody(result).path("data");
        assertThat(data.path("contractNo").asText()).isEqualTo(contractNo);
        assertThat(data.path("productId").asLong()).isEqualTo(PRODUCT_ID);
        assertThat(data.path("productName").asText()).isNotBlank();
        assertThat(data.path("applicantNo").asText()).isEqualTo(REQ);
        assertThat(data.path("providerNo").asText()).isEqualTo(PROV);
        assertThat(data.path("status").asText()).isEqualTo("EFFECTIVE");
        assertThat(data.path("effectiveAt").isNull()).isFalse();
        assertThat(usageCount(contractNo)).as("只读摘要不计数").isEqualTo(before);
        assertThat(data.toString()).doesNotContain("slots").doesNotContain("subject_matter");
    }

    @Test
    void internalSummaryRejectsNonParticipantSameShape() throws Exception {
        final String contractNo = toEffective(unique("S2"), quotaStrategy(3));
        final MvcResult other = internalSummary(contractNo, OTHER, "delivery-service",
                "delivery-internal");
        final MvcResult missing = internalSummary("CO999999", OTHER, "delivery-service",
                "delivery-internal");
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(readBody(other).path("code").asText()).isEqualTo(C0012);
        assertThat(readBody(missing).path("code").asText()).isEqualTo(C0012);
        assertThat(readBody(other).path("message").asText())
                .isEqualTo(readBody(missing).path("message").asText());
    }

    @Test
    void internalSummaryRejectsNonEffectiveContract() throws Exception {
        final String contractNo = initiateOk(unique("S3"), quotaStrategy(3));
        final MvcResult result = internalSummary(contractNo, REQ, "delivery-service",
                "delivery-internal");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(readBody(result).path("code").asText()).isEqualTo(C0013);
    }

    @Test
    void internalSummaryRequiresInternalPermission() throws Exception {
        final String contractNo = toEffective(unique("S4"), quotaStrategy(3));
        assertThat(statusOf(internalSummary(contractNo, REQ, "S-req", "provider"))).isEqualTo(403);
        assertThat(statusOf(mockMvc.perform(get(BASE + "/internal/contract-summaries/" + contractNo
                + "?requesterNo=" + REQ)).andReturn())).isEqualTo(401);
    }

    private MvcResult internalSummary(final String contractNo, final String requesterNo,
            final String subject, final String roles) throws Exception {
        return mockMvc.perform(get(BASE + "/internal/contract-summaries/" + contractNo)
                .queryParam("requesterNo", requesterNo)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)).andReturn();
    }

    // ==== 判定原码（越界 / 状态失效 / 参与方防枚举） ====

    @Test
    void outOfScopeUsageKeepsContractDomainCode() throws Exception {
        final String contractNo = toEffective(unique("I3"), quotaStrategy(1));

        assertThat(statusOf(internalCheck(useBody(contractNo, REQ), GATEWAY_SUBJECT, GATEWAY_ROLES)))
                .isEqualTo(200);
        final MvcResult denied =
                internalCheck(useBody(contractNo, REQ), GATEWAY_SUBJECT, GATEWAY_ROLES);

        assertThat(statusOf(denied)).as("配额耗尽 → 403").isEqualTo(403);
        assertThat(readBody(denied).path("code").asText()).as("越界原码（网关侧透传依据）")
                .isEqualTo(C0020);
    }

    @Test
    void notEffectiveContractReturnsStateForbiddenCode() throws Exception {
        final String contractNo = initiateOk(unique("I4"), quotaStrategy(3));

        final MvcResult result =
                internalCheck(useBody(contractNo, REQ), GATEWAY_SUBJECT, GATEWAY_ROLES);

        assertThat(statusOf(result)).isEqualTo(409);
        assertThat(readBody(result).path("code").asText()).isEqualTo(C0013);
    }

    @Test
    void nonParticipantAndMissingContractShareCodeAndMessage() throws Exception {
        final String contractNo = toEffective(unique("I5"), quotaStrategy(3));

        final MvcResult nonParticipant =
                internalCheck(useBody(contractNo, OTHER), GATEWAY_SUBJECT, GATEWAY_ROLES);
        final MvcResult missing = internalCheck(useBody("HT-2099-9999", REQ), GATEWAY_SUBJECT,
                GATEWAY_ROLES);

        assertThat(statusOf(nonParticipant)).isEqualTo(404);
        assertThat(statusOf(missing)).isEqualTo(404);
        assertThat(readBody(nonParticipant).path("code").asText()).isEqualTo(C0012);
        assertThat(readBody(missing).path("code").asText()).as("防枚举：同码").isEqualTo(C0012);
        assertThat(readBody(missing).path("message").asText()).as("防枚举：同文")
                .isEqualTo(readBody(nonParticipant).path("message").asText());
        assertThat(usageCount(contractNo)).as("非参与方判定不计数").isZero();
    }

    @Test
    void malformedRequestIsParameterInvalid() throws Exception {
        final String contractNo = toEffective(unique("I6"), quotaStrategy(3));

        final MvcResult missingRequester = internalCheck(
                "{\"contractNo\":\"" + contractNo + "\",\"actionType\":\"USE\"}", GATEWAY_SUBJECT,
                GATEWAY_ROLES);
        final MvcResult badAction = internalCheck(
                "{\"contractNo\":\"" + contractNo + "\",\"requesterNo\":\"" + REQ
                        + "\",\"actionType\":\"TRANSFER\"}", GATEWAY_SUBJECT, GATEWAY_ROLES);

        assertThat(statusOf(missingRequester)).isEqualTo(400);
        assertThat(readBody(missingRequester).path("code").asText()).isEqualTo(C0008);
        assertThat(statusOf(badAction)).isEqualTo(400);
        assertThat(readBody(badAction).path("code").asText()).isEqualTo(C0008);
        assertThat(usageCount(contractNo)).isZero();
    }

    // ==== 放行计数与流水对账 ====

    @Test
    void allowedDeliveryReconcilesWithUsageSummary() throws Exception {
        final String contractNo = toEffective(unique("I7"), quotaStrategy(2));

        for (int i = 0; i < 2; i++) {
            assertThat(statusOf(internalCheck(useBody(contractNo, REQ), GATEWAY_SUBJECT, GATEWAY_ROLES)))
                    .isEqualTo(200);
        }

        assertThat(usageCount(contractNo)).as("放行 2 次 ≡ 计量流水 2 行").isEqualTo(2);
    }

    // ==== 辅助 ====

    private String useBody(final String contractNo, final String requesterNo) {
        return "{\"contractNo\":\"" + contractNo + "\",\"requesterNo\":\"" + requesterNo
                + "\",\"actionType\":\"USE\",\"purpose\":\"风控建模\"}";
    }

    private MvcResult internalCheck(final String body, final String subject, final String roles)
            throws Exception {
        return requireBody(subject, roles, body);
    }

    private MvcResult requireBody(final String subject, final String roles, final String body)
            throws Exception {
        return mockMvc.perform(post(INTERNAL)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private int statusOf(final MvcResult result) {
        return result.getResponse().getStatus();
    }

    /**
     * 对账锚（R12 使用摘要）：放行流水行数 ≡ 配额计数余额（两者同源同事务），返回放行行数；
     * 顺带断言"拒绝不烧次数"（拒绝行不计数）。
     */
    private long usageCount(final String contractNo) throws Exception {
        final MvcResult summary = mockMvc.perform(get(BASE + "/" + contractNo + "/usage-summary")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn();
        assertThat(summary.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = readBody(summary).path("data");
        final long allowed = data.path("allowedCount").asLong();
        assertThat(data.path("quota").path("used").asLong())
                .as("放行流水行数 ≡ 配额计数余额").isEqualTo(allowed);
        return allowed;
    }

    private String toEffective(final String marker, final String strategyJson) throws Exception {
        final String contractNo = initiateOk(marker, strategyJson);
        for (final String operator : List.of(REQ, PROV)) {
            assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/confirmations")
                            .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                    .andReturn().getResponse().getStatus()).as("确认应成功（%s）", operator)
                    .isEqualTo(200);
        }
        for (final String[] signing : List.of(new String[] {PROV, PROVIDER_DID},
                new String[] {REQ, REQUESTER_DID})) {
            assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/signatures")
                            .header("X-Ctds-Subject", signing[0]).header("X-Ctds-Roles", "provider")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"did\":\"" + signing[1] + "\"}")).andReturn()
                    .getResponse().getStatus()).as("签署应成功（%s）", signing[0]).isEqualTo(201);
        }
        return contractNo;
    }

    private String initiateOk(final String marker, final String strategyJson) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initiateBody(marker, strategyJson))).andReturn();
        assertThat(result.getResponse().getStatus()).as("发起应成功: %s", readBody(result).toString())
                .isEqualTo(201);
        return readBody(result).path("data").path("contractNo").asText();
    }

    private static String initiateBody(final String marker, final String strategyJson) {
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + TEMPLATE_NO
                + "\",\"templateVersionNo\":1,\"clauseValues\":"
                + clauseValuesJson(marker, strategyJson) + "}";
    }

    private static String clauseValuesJson(final String marker, final String strategyJson) {
        return "{\"slots\":{"
                + "\"subject_matter\":\"城市餐饮单位经营数据集（" + marker + "）\","
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

    private static String quotaStrategy(final int maxCount) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static JsonNode readBody(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String unique(final String label) {
        return label + "-" + Long.toHexString(System.nanoTime());
    }
}
