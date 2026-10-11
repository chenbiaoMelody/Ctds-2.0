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
import java.util.ArrayList;
import java.util.List;
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
 * R13 合约统一留痕读面集成测试（WBS-3.4.7 hifi §7 T1~T2；**新测试类**——不触碰
 * {@code ContractPolicySimulatorIntegrationTest}〔跟踪-29 合规〕）：真实 MySQL 8 容器实跑
 * Flyway V1+V2+V3；资格/产品/DID 三端口 {@code @MockitoBean}（沿既有基座先例）。
 *
 * <p>留痕行全部经真实写链路产生（发起 / 提案 / 确认 / 终止协商 / 签署 / 拒签 / 存证 / 合意解除 /
 * 强制终止 / 治理查看 / 验签异常 / 越权拒绝——12 动作码零直插造数）。用例承载：T1 权限矩阵
 * （参与方 200 四要素行齐 / 非参与方与不存在同码同文 {@code 1008C0012} + DENIED_ACCESS 恰一行 /
 * 治理档 200 且零新增留痕 / 未认证 401 / 无权限 403 / 分页越界 {@code 1000C0001} / 排序稳定）；
 * T2 值域与字段白名单（12 动作码行齐备 / reasonCode = 码位尾号 / 出站键集恰九字段、无敏感原文
 * 与签名值）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractActionLogIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/contracts";
    private static final String REQ = "S-req";
    private static final String PROV = "S-prov";
    private static final String OTHER = "S-other";
    private static final String ADMIN = "S-admin";
    private static final long PRODUCT_ID = 12L;
    private static final String PROVIDER_DID = "did:ctds:" + PROV + ".1";
    private static final String REQUESTER_DID = "did:ctds:" + REQ + ".1";
    /** CT000001 公共数据授权模板（V1 迁移种子，9 必填 + 1 选填槽位）。 */
    private static final String TEMPLATE_NO = "CT000001";
    private static final String C0012 = "1008C0012";
    private static final String NOT_VISIBLE_MESSAGE = "合约不存在或不可见";
    private static final String PURPOSE_TEXT = "风控建模";
    private static final String TERRITORY_TEXT = "本市域";

    private static Path keyFile;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_actionlog_it");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        keyFile = Files.createTempFile("ctds-test-actionlog-keys", ".keys");
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
    @MockitoBean
    private SubjectAdmissionPort subjectAdmissionPort;
    @MockitoBean
    private CatalogProductPort catalogProductPort;
    @MockitoBean
    private DidPort didPort;

    @BeforeEach
    void stubPorts() {
        for (final String subject : List.of(REQ, PROV, OTHER, ADMIN)) {
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
        given(didPort.verify(anyString(), anyString(), anyString())).willReturn(
                DidPort.DidVerifyResult.of(DidPort.DidVerifyResult.Outcome.PASS, null));
    }

    // ==== t1（T1）参与方读：200 + 分页齐备 + 四要素行齐 + 排序稳定（created_at 非增、同秒 id 倒序）====

    @Test
    void t1_participantReadsActionLogsWithFullRowsAndStableOrdering() throws Exception {
        final String contractNo = toEffective("留痕读面");
        final JsonNode data = readBody(getLogs(contractNo, REQ, "provider", 200))
                .path("data");
        assertThat(data.path("total").asInt()).as("CREATE+CONFIRM×2+SIGN×2+ATTEST").isEqualTo(6);
        assertThat(data.path("pageNum").asInt()).isEqualTo(1);
        assertThat(data.path("pageSize").asInt()).isEqualTo(20);
        assertThat(data.path("totalPages").asInt()).isEqualTo(1);
        final JsonNode rows = data.path("list");
        assertThat(rows).hasSize(6);
        long previousId = Long.MAX_VALUE;
        String previousCreatedAt = null;
        for (final JsonNode row : rows) {
            assertThat(row.path("action").asText()).as("四要素·动作").isNotBlank();
            assertThat(row.path("actorSubjectNo").asText()).as("四要素·操作者").isNotBlank();
            assertThat(row.path("createdAt").asText()).as("四要素·时间").isNotBlank();
            assertThat(row.has("contractNo")).as("四要素·合约编号键存在").isTrue();
            assertThat(row.has("versionNo")).as("版本号键存在").isTrue();
            assertThat(row.has("reasonCode")).as("拒绝码键存在").isTrue();
            assertThat(row.has("fromValue")).as("从何值键存在").isTrue();
            assertThat(row.has("toValue")).as("到何值键存在").isTrue();
            final String createdAt = row.path("createdAt").asText();
            if (previousCreatedAt != null) {
                assertThat(createdAt).as("created_at 非增").isLessThanOrEqualTo(previousCreatedAt);
                if (createdAt.equals(previousCreatedAt)) {
                    assertThat(row.path("id").asLong()).as("同秒行按 id 倒序").isLessThan(previousId);
                }
            }
            previousId = row.path("id").asLong();
            previousCreatedAt = createdAt;
        }
    }

    // ==== t2（T1）非参与方与不存在同码同文 1008C0012（404 防枚举）+ DENIED_ACCESS 恰一行 ====

    @Test
    void t2_nonParticipantAndMissingContractShareSameShapeAndDeniedRowIsWrittenOnce() throws Exception {
        final String contractNo = toEffective("防枚举");
        final long deniedBefore = deniedRows(contractNo);
        final JsonNode denied = readBody(getLogs(contractNo, OTHER, "provider", 404));
        assertThat(denied.path("code").asText()).isEqualTo(C0012);
        assertThat(denied.path("message").asText()).isEqualTo(NOT_VISIBLE_MESSAGE);
        assertThat(deniedRows(contractNo)).as("非参与方命中恰写一行 DENIED_ACCESS")
                .isEqualTo(deniedBefore + 1);
        final String deniedReason = jdbcTemplate.queryForObject(
                "SELECT reason_code FROM contract_action_log WHERE contract_no = ? AND action = "
                        + "'DENIED_ACCESS'", String.class, contractNo);
        assertThat(deniedReason).as("拒绝码 = 码位尾号（DB-31 口径——hifi §7 T2）").isEqualTo("C0012");
        final JsonNode missing = readBody(getLogs("CO-BADNOTEXIST", OTHER, "provider", 404));
        assertThat(missing.path("code").asText()).as("不存在与不可见同码").isEqualTo(C0012);
        assertThat(missing.path("message").asText()).as("不存在与不可见同文（防枚举）")
                .isEqualTo(NOT_VISIBLE_MESSAGE);
    }

    // ==== t3（T1）治理档读：200 且零新增留痕（R13 治理读取不另写留痕——§1.2 口径）====

    @Test
    void t3_governanceReadSucceedsWithoutExtraLogRows() throws Exception {
        final String contractNo = toEffective("治理读");
        final long allBefore = totalLogRows();
        final JsonNode data = readBody(getLogs(contractNo, ADMIN, "admin", 200)).path("data");
        assertThat(data.path("total").asInt()).as("治理档可见全部留痕").isEqualTo(6);
        assertThat(totalLogRows()).as("治理读取零新增留痕（不产生 GOVERNANCE_VIEW）")
                .isEqualTo(allBefore);
    }

    // ==== t4（T1）未认证 401 / 无权限 403 / 分页越界 1000C0001 ====

    @Test
    void t4_authAndPaginationGuardrails() throws Exception {
        final String contractNo = toEffective("护栏");
        final int unauthenticated = mockMvc.perform(get(BASE + "/" + contractNo + "/action-logs"))
                .andReturn().getResponse().getStatus();
        assertThat(unauthenticated).as("未认证 401").isEqualTo(401);
        final JsonNode forbidden = readBody(mockMvc.perform(get(BASE + "/" + contractNo
                        + "/action-logs?pageNum=1&pageSize=20")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "reviewer"))
                .andReturn());
        assertThat(forbidden.path("code").asText()).as("无权限 403（1000C0005）")
                .isEqualTo("1000C0005");
        final JsonNode outOfRange = readBody(getLogs(contractNo, REQ, "provider", 400,
                "pageNum=10001&pageSize=20"));
        assertThat(outOfRange.path("code").asText()).as("分页越界 1000C0001")
                .isEqualTo("1000C0001");
    }

    // ==== t5（T2）12 动作码值域覆盖（全部经真实写链路产生）====

    @Test
    void t5_allTwelveActionCodeValuesServedThroughRealWritePaths() throws Exception {
        final List<String> served = new ArrayList<>();
        final String effective = toEffective("值域-生效");
        // 生效链路五码 + 验签异常 + 越权拒绝 + 治理查看
        collect(served, effective, REQ);
        given(didPort.verify(eq(PROVIDER_DID), anyString(), anyString())).willReturn(
                DidPort.DidVerifyResult.of(DidPort.DidVerifyResult.Outcome.FAIL, "签名不匹配"));
        mockMvc.perform(post(BASE + "/" + effective + "/signature-verifications")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn();
        mockMvc.perform(get(BASE + "/" + effective).header("X-Ctds-Subject", OTHER)
                .header("X-Ctds-Roles", "provider")).andReturn();
        mockMvc.perform(get(BASE + "/governance/" + effective).header("X-Ctds-Subject", ADMIN)
                .header("X-Ctds-Roles", "admin")).andReturn();
        collect(served, effective, REQ);
        // 提案 + 拒签链路
        final String refused = initiateOk("值域-拒签");
        propose(refused, PROV);
        for (final String operator : List.of(REQ, PROV)) {
            postOk(BASE + "/" + refused + "/confirmations", operator);
        }
        postOk(BASE + "/" + refused + "/signatures", PROV,
                "{\"did\":\"" + PROVIDER_DID + "\"}");
        postOk(BASE + "/" + refused + "/signature-refusals", REQ);
        collect(served, refused, REQ);
        // 协商终止链路
        final String negotiated = initiateOk("值域-协商终止");
        postOk(BASE + "/" + negotiated + "/negotiation-terminations", REQ);
        collect(served, negotiated, REQ);
        // 合意解除链路（生效后双方各确认）
        final String released = toEffective("值域-解除");
        postOk(BASE + "/" + released + "/release-consents", REQ);
        postOk(BASE + "/" + released + "/release-consents", PROV);
        collect(served, released, REQ);
        // 强制终止链路（治理档）
        final String forced = toEffective("值域-强终");
        mockMvc.perform(post(BASE + "/governance/" + forced + "/force-termination")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"治理演示：违规使用\"}")).andReturn();
        collect(served, forced, REQ);
        assertThat(served.stream().distinct().toList()).as("12 动作码行齐备（显式值域断言）")
                .containsExactlyInAnyOrder("CREATE", "PROPOSE", "CONFIRM", "TERMINATE_NEGOTIATION",
                        "SIGN", "REFUSE_SIGN", "ATTEST", "RELEASE_CONSENT", "FORCE_TERMINATE",
                        "GOVERNANCE_VIEW", "VERIFY_SIGNATURE_FAILED", "DENIED_ACCESS");
    }

    // ==== t6（T2）字段白名单：出站键集恰九字段；无签名值、无条款明文出站 ====

    @Test
    void t6_outboundFieldWhitelistContainsNoSensitiveMaterial() throws Exception {
        // marker（含唯一尾数）必须随造数写入条款值（subject_matter，经 initiateBody 嵌入），
        // 否则"条款明文零出站"恒真（评审④独立评审 P2：原实现 marker 死变量）
        final String marker = "白名单" + Long.toHexString(System.nanoTime());
        final String contractNo = toEffective(marker);
        final JsonNode rows = readBody(getLogs(contractNo, REQ, "provider", 200))
                .path("data").path("list");
        final String first = rows.get(0).toString();
        assertThat(MAPPER.readTree(first).fieldNames())
                .as("出站键集恰九字段（白名单封闭）")
                .toIterable()
                .containsExactlyInAnyOrder("id", "contractNo", "versionNo", "action",
                        "actorSubjectNo", "reasonCode", "fromValue", "toValue", "createdAt");
        // 敏感材料反查覆盖全部出站行（不只首行——评审④ P3 收紧）
        final List<String> allRows = new ArrayList<>();
        rows.forEach(row -> allRows.add(row.toString()));
        final String outbound = String.join("\n", allRows);
        assertThat(outbound).as("签名值零出站（全行）").doesNotContain("c2ln");
        assertThat(outbound).as("条款明文零出站（全行）").doesNotContain(marker);
        assertThat(outbound).as("策略文档零出站（全行）").doesNotContain("maxCount");
    }

    // ==== 造数与断言助手（全部经真实写链路）====

    private void collect(final List<String> served, final String contractNo, final String operator)
            throws Exception {
        final JsonNode rows = readBody(getLogs(contractNo, operator, "provider", 200))
                .path("data").path("list");
        rows.forEach(row -> served.add(row.path("action").asText()));
    }

    private MvcResult getLogs(final String contractNo, final String operator, final String role,
            final int expectedStatus) throws Exception {
        return getLogs(contractNo, operator, role, expectedStatus, "pageNum=1&pageSize=20");
    }

    private MvcResult getLogs(final String contractNo, final String operator, final String role,
            final int expectedStatus, final String query) throws Exception {
        final MvcResult result = mockMvc.perform(get(BASE + "/" + contractNo + "/action-logs?"
                        + query).header("X-Ctds-Subject", operator)
                .header("X-Ctds-Roles", role)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("R13 状态: %s", result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return result;
    }

    private String initiateOk(final String marker) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initiateBody(marker))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("发起应成功: %s", readBody(result)).isEqualTo(201);
        return readBody(result).path("data").path("contractNo").asText();
    }

    private void propose(final String contractNo, final String operator) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE + "/" + contractNo + "/proposals")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clauseValues\":{\"slots\":{"
                        + "\"subject_matter\":\"城市餐饮单位经营数据集（提案-" + contractNo + "）\","
                        + "\"scope\":\"内部数据分析\","
                        + "\"term\":\"2027-10-04 至 2028-10-03\","
                        + "\"purpose_and_restrictions\":\"政策研究，禁止再分发\","
                        + "\"security_confidentiality\":\"按平台安全规范执行\","
                        + "\"liability\":\"双方违约按实际损失赔付\","
                        + "\"dispute_resolution\":\"提交平台争议解决\","
                        + "\"data_format_delivery\":\"库表接口拉取\","
                        + "\"data_update_obligation\":\"按日更新并通知\""
                        + "},\"strategy\":{\"quota\":{\"enabled\":true,\"maxCount\":5},"
                        + "\"noRestrictionDeclared\":false}}}")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("提案应成功: %s", readBody(result)).isEqualTo(201);
    }

    private void postOk(final String url, final String operator) throws Exception {
        postOk(url, operator, null);
    }

    private void postOk(final String url, final String operator, final String body) throws Exception {
        final var request = post(url)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider");
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        final MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("POST %s 应成功: %s", url, result.getResponse().getContentAsString())
                .isIn(200, 201);
    }

    /** 真实链路造一份已生效合约：发起（CREATE）→ 双确认（CONFIRM）→ 双签（SIGN×2 + ATTEST）。 */
    private String toEffective(final String marker) throws Exception {
        final String contractNo = initiateOk(marker);
        for (final String operator : List.of(REQ, PROV)) {
            postOk(BASE + "/" + contractNo + "/confirmations", operator);
        }
        postOk(BASE + "/" + contractNo + "/signatures", PROV,
                "{\"did\":\"" + PROVIDER_DID + "\"}");
        postOk(BASE + "/" + contractNo + "/signatures", REQ,
                "{\"did\":\"" + REQUESTER_DID + "\"}");
        return contractNo;
    }

    private static String initiateBody(final String marker) {
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + TEMPLATE_NO
                + "\",\"templateVersionNo\":1,\"clauseValues\":{\"slots\":{"
                + "\"subject_matter\":\"城市餐饮单位经营数据集（" + marker + "）\","
                + "\"scope\":\"内部数据分析\","
                + "\"term\":\"2027-10-04 至 2028-10-03\","
                + "\"purpose_and_restrictions\":\"政策研究，禁止再分发\","
                + "\"security_confidentiality\":\"按平台安全规范执行\","
                + "\"liability\":\"双方违约按实际损失赔付\","
                + "\"dispute_resolution\":\"提交平台争议解决\","
                + "\"data_format_delivery\":\"库表接口拉取\","
                + "\"data_update_obligation\":\"按日更新并通知\""
                + "},\"strategy\":{\"quota\":{\"enabled\":true,\"maxCount\":5},"
                + "\"noRestrictionDeclared\":false}}}";
    }

    private long deniedRows(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? AND action = "
                        + "'DENIED_ACCESS'", Long.class, contractNo);
    }

    private long totalLogRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log", Long.class);
    }

    private JsonNode readBody(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
