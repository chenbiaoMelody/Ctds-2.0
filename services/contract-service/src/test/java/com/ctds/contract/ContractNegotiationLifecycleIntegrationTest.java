package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.contract.application.ContractQueryService;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.domain.DealTextCipher;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 合约协商与电子签署生命周期集成测试（WBS-3.4.3 hifi §7 T2~T12；规格行为 2/3/6/7 + 行为 4
 * 本卡承载面；C-4.2 剧本 S1-1~8 / S2-1~5 / S3-1~6 判定点 + C-4.1 S2-3/S2-5 承接兑现 +
 * C-4.3 S1-2/3/4/5 锚）。真实 MySQL 8 容器实跑 Flyway V1+V2；资格/产品/DID 三端口
 * {@code @MockitoBean}（沿 catalog 先例——client 三态另有桩单测）；L3 密钥源 = 临时密钥文件
 * 注入（沿 subject 测试先例，密钥零入库）。本机 Docker 未运行时整类跳过（门禁不红）。
 *
 * <p>各用例条款值带唯一标记（subject_matter 值内嵌）——发起幂等键跨用例零串扰（沿模板
 * 生命周期测试先例）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractNegotiationLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/contracts";
    private static final String TPL = "/api/v1/contract-templates";
    private static final String REQ = "S-req";
    private static final String PROV = "S-prov";
    private static final String OTHER = "S-other";
    private static final String ADMIN = "S-admin";
    private static final long PRODUCT_ID = 12L;
    private static final String PROVIDER_DID = "did:ctds:" + PROV + ".1";
    private static final String REQUESTER_DID = "did:ctds:" + REQ + ".1";
    /** CT000001 公共数据授权模板（V1 迁移种子，9 必填 + 1 选填槽位）。 */
    private static final String TEMPLATE_NO = "CT000001";
    private static final String DEFAULT_STRATEGY = "{\"noRestrictionDeclared\":true}";
    /** 演示签名桩返回的 SM2 签名（Base64——密文探针反查标记）。 */
    private static final String PROVIDER_SIGNATURE = "c2lnLXByb3ZpZGVyLXN0dWI=";

    private static Path keyFile;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_deal_it");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        // 16 字节测试密钥（仅测试用；文件命名 *.keys——.gitignore 拦截口径，密钥零入库）
        keyFile = Files.createTempFile("ctds-test-deal-keys", ".keys");
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
    private DealTextCipher dealTextCipher;
    @Autowired
    private Sm3Service sm3Service;
    @Autowired
    private ContractQueryService queryService;
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
        given(didPort.sign(eq(PROVIDER_DID), anyString())).willReturn(
                DidPort.DidSignResult.signed(PROVIDER_SIGNATURE));
        given(didPort.sign(eq(REQUESTER_DID), anyString())).willReturn(
                DidPort.DidSignResult.signed("c2lnLXJlcXVlc3Rlci1zdHVi"));
        given(didPort.verify(anyString(), anyString(), anyString())).willReturn(
                DidPort.DidVerifyResult.of(DidPort.DidVerifyResult.Outcome.PASS, null));
    }

    // ==== T2 发起门槛与幂等（行为 2 规则 1/2/3；剧本 S1-1/4/5/6/7）====

    @Test
    void t2_initiateEntersNegotiatingWithSnapshotsAndLog() throws Exception {
        final String contractNo = initiateOk(unique("发起成功"));
        final JsonNode data = readBody(getDetail(contractNo, REQ)).path("data");
        assertThat(data.path("status").asText()).isEqualTo("NEGOTIATING");
        assertThat(data.path("currentClauseVersion").asInt()).isEqualTo(1);
        assertThat(data.path("templateNo").asText()).isEqualTo(TEMPLATE_NO);
        assertThat(data.path("templateVersionNo").asInt()).isEqualTo(1);
        assertThat(data.path("providerSubjectNo").asText()).isEqualTo(PROV);
        assertThat(data.path("requesterSubjectNo").asText()).isEqualTo(REQ);
        // 定价快照（目录域产品事实——PER_CALL 1.50）
        assertThat(data.path("pricingModel").asText()).isEqualTo("PER_CALL");
        assertThat(data.path("priceAmount").decimalValue()).isEqualByComparingTo("1.5");
        // 留痕 CREATE（四要素）
        final Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT actor_subject_no, version_no, to_value FROM contract_action_log "
                        + "WHERE contract_no = ? AND action = 'CREATE'", contractNo);
        assertThat(logRow.get("actor_subject_no")).isEqualTo(REQ);
        assertThat(logRow.get("version_no")).isEqualTo(1);
        assertThat(logRow.get("to_value")).isEqualTo("1");
    }

    @Test
    void t2_admissionGateUnifiedTextAndUnavailableNotMasquerading() throws Exception {
        given(subjectAdmissionPort.check(REQ)).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult denied = initiate(REQ, initiateBody(unique("未入驻")));
        assertThat(denied.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(denied)).isEqualTo("1008C0003");
        assertThat(readBody(denied).path("message").asText())
                .isEqualTo("主体未入驻或不存在，无法发起合约");
        // UNAVAILABLE 不冒充资格拒绝（S0001 503）
        given(subjectAdmissionPort.check(REQ)).willReturn(SubjectAdmission.UNAVAILABLE);
        final MvcResult unavailable = initiate(REQ, initiateBody(unique("资格不可用")));
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unavailable)).isEqualTo("1008S0001");
    }

    @Test
    void t2_productGateFourStatesSameCodeSameTextAndUnavailable() throws Exception {
        final String unifiedText = "产品不存在或未在架，无法发起合约";
        // 不存在（客户端三态 NOT_FOUND）与 未上架/已下架/已注销（状态原值非 LISTED）同码同文——防枚举
        given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                CatalogProductPort.CatalogProductResult.notFound());
        assertProductGate(unifiedText);
        for (final String status : List.of("DRAFT", "DELISTED", "CANCELLED")) {
            given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                    CatalogProductPort.CatalogProductResult.found(
                            new CatalogProductPort.CatalogProduct(PRODUCT_ID, "产品", status,
                                    PROV, "FREE", null)));
            assertProductGate(unifiedText);
        }
        // 目录不可达 → S0003（不冒充产品状态）
        given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                CatalogProductPort.CatalogProductResult.unavailable());
        final MvcResult unavailable = initiate(REQ, initiateBody(unique("目录不可用")));
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unavailable)).isEqualTo("1008S0003");
    }

    private void assertProductGate(final String expectedText) throws Exception {
        final MvcResult result = initiate(REQ, initiateBody(unique("产品门槛")));
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1008C0010");
        assertThat(readBody(result).path("message").asText()).isEqualTo(expectedText);
    }

    @Test
    void t2_providerSelfDealRejected() throws Exception {
        final MvcResult result = initiate(PROV, initiateBody(unique("自发起")));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1008C0016");
    }

    @Test
    void t2_disabledOrMissingTemplateRejectedC0017() throws Exception {
        // 停用模板新发起被拒（承接-1：C-4.1 S2-5 兑现）
        mockMvc.perform(post(TPL + "/" + TEMPLATE_NO + "/disable")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn();
        final MvcResult disabled = initiate(REQ, initiateBody(unique("停用模板")));
        assertThat(disabled.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(disabled)).isEqualTo("1008C0017");
        // 恢复启用 + 版本无效 / 模板不存在同样出站统一 C0017
        mockMvc.perform(post(TPL + "/" + TEMPLATE_NO + "/enable")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")).andReturn();
        assertInitiateConflict(initiateBody(unique("版本无效"), TEMPLATE_NO, 99));
        assertInitiateConflict(initiateBody(unique("模板不存在"), "CT999999", 1));
    }

    private void assertInitiateConflict(final String body) throws Exception {
        final MvcResult result = initiate(REQ, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1008C0017");
    }

    @Test
    void t2_clauseValuesFrameworkViolationsC0014() throws Exception {
        // 未知槽位键 / 必填缺失 / 槽位值非字符串（明细入服务端日志、响应仅常量文案）
        final ObjectNode unknownSlot = (ObjectNode) parseNode(
                clauseValuesJson(unique("未知槽位"), DEFAULT_STRATEGY));
        ((ObjectNode) unknownSlot.path("slots")).put("extra_slot", "x");
        assertInitiateC0014(unknownSlot.toString());
        final ObjectNode missingRequired = (ObjectNode) parseNode(
                clauseValuesJson(unique("缺必填"), DEFAULT_STRATEGY));
        ((ObjectNode) missingRequired.path("slots")).remove("liability");
        assertInitiateC0014(missingRequired.toString());
        final ObjectNode nonTextual = (ObjectNode) parseNode(
                clauseValuesJson(unique("非字符串"), DEFAULT_STRATEGY));
        ((ObjectNode) nonTextual.path("slots")).putPOJO("scope", 123);
        assertInitiateC0014(nonTextual.toString());
    }

    private void assertInitiateC0014(final String clauseValuesJson) throws Exception {
        assertInitiateC0014(clauseValuesJson, TEMPLATE_NO, 1);
    }

    private void assertInitiateC0014(final String clauseValuesJson, final String templateNo,
            final int versionNo) throws Exception {
        final MvcResult result = initiate(REQ,
                initiateBodyFromClauseValues(clauseValuesJson, templateNo, versionNo));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1008C0014");
        assertThat(readBody(result).path("message").asText()).isEqualTo("条款值与模板框架不符");
    }

    @Test
    void t2_policyBasicValueViolationsC0015() throws Exception {
        // 次数非正整数（-5）→ 1008C0015（剧本 C-4.3 S1-3 锚）；期限起止倒置同理
        assertInitiateC0015(clauseValuesJson(unique("负次数"), quotaStrategy(-5)));
        assertInitiateC0015(clauseValuesJson(unique("倒置"),
                termStrategy("2027-01-01", "2026-01-01")));
    }

    private void assertInitiateC0015(final String clauseValuesJson) throws Exception {
        final MvcResult result = initiate(REQ, initiateBodyFromClauseValues(clauseValuesJson));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1008C0015");
        assertThat(readBody(result).path("message").asText()).isEqualTo("策略条款不符合使用控制约定");
    }

    @Test
    void t2_idempotentReplayReturnsFirstContract() throws Exception {
        final String body = initiateBody(unique("幂等重放"));
        final MvcResult first = initiate(REQ, body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        final String firstNo = readBody(first).path("data").path("contractNo").asText();
        final Integer before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?", Integer.class, REQ);
        final MvcResult replay = initiate(REQ, body);
        final Integer after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?", Integer.class, REQ);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(readBody(replay).path("data").path("contractNo").asText()).isEqualTo(firstNo);
        assertThat(before).isEqualTo(after);
    }

    // ==== T3 快照稳定性（承接-2：C-4.1 S2-3/S2-5）====

    @Test
    void t3_templateRevisionDoesNotAffectExistingDraftAndNewUsesNewFramework() throws Exception {
        final String contractNo = initiateOk(unique("快照稳定"));
        // 直调 3.4.2 修订端点出新版本 V2（框架键集封闭——将选填槽 data_quality_commitment 升为必填）
        final JsonNode frameworkV1 = readBody(mockMvc.perform(get(TPL + "/" + TEMPLATE_NO)
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn()).path("data").path("clauseFramework");
        final ObjectNode frameworkV2 = frameworkV1.deepCopy();
        for (final JsonNode slot : frameworkV2.path("slots")) {
            if ("data_quality_commitment".equals(slot.path("key").asText())) {
                ((ObjectNode) slot).put("required", true);
            }
        }
        final MvcResult revised = mockMvc.perform(post(TPL + "/" + TEMPLATE_NO + "/revisions")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.createObjectNode().set("clauseFramework", frameworkV2)
                                .toString()))
                .andReturn();
        assertThat(revised.getResponse().getStatus())
                .as("模板修订应成功: %s", readBody(revised)).isEqualTo(201);
        // 草案详情仍按锁定版本 V1（版本号不变 + 提案按 V1 槽位框架成功——S2-3 兑现）
        assertThat(readBody(getDetail(contractNo, REQ)).path("data")
                .path("templateVersionNo").asInt()).isEqualTo(1);
        assertThat(propose(contractNo, PROV, slotsJson(unique("快照稳定·反提案")))
                .getResponse().getStatus()).isEqualTo(201);
        // 新发起按"请求版本决定框架"：显式锁定 V1（版本存在仍有效——QV1 语义）→ 成功；
        // 显式锁定 V2 而 V1 槽位集（缺新必填槽）→ C0014；V2 槽位集（补齐）→ 成功
        assertThat(initiate(REQ, initiateBody(unique("快照后显式V1"), TEMPLATE_NO, 1))
                .getResponse().getStatus()).isEqualTo(201);
        assertInitiateC0014(clauseValuesJson(unique("快照后显式V2"), DEFAULT_STRATEGY),
                TEMPLATE_NO, 2);
        final ObjectNode withNewRequired = (ObjectNode) parseNode(
                clauseValuesJson(unique("快照后新发起V2"), DEFAULT_STRATEGY));
        ((ObjectNode) withNewRequired.path("slots")).put("data_quality_commitment", "数据质量承诺值");
        assertThat(initiate(REQ, initiateBodyFromClauseValues(withNewRequired.toString(),
                        TEMPLATE_NO, 2)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void t3_disabledTemplateDraftContinuesButNewInitiationRejected() throws Exception {
        final String contractNo = initiateOk(unique("停用后续协"));
        mockMvc.perform(post(TPL + "/" + TEMPLATE_NO + "/disable")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")).andReturn();
        // 既有草案可继续协商（提案不受模板停用影响——快照归合约）
        assertThat(propose(contractNo, PROV, slotsJson(unique("停用后续协·反提案")))
                .getResponse().getStatus()).isEqualTo(201);
        // 新发起被拒（承接-1 复证）
        assertInitiateConflict(initiateBody(unique("停用后新发起")));
        mockMvc.perform(post(TPL + "/" + TEMPLATE_NO + "/enable")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")).andReturn();
    }

    // ==== T4 协商链（行为 2 规则 4/6；剧本 S1-2/3/8）====

    @Test
    void t4_proposeAddsVersionWithChangeDetailAndLog() throws Exception {
        final String contractNo = initiateOk(unique("协商链"));
        final MvcResult proposed = propose(contractNo, PROV, slotsJson(unique("协商链·反提案")));
        assertThat(proposed.getResponse().getStatus()).isEqualTo(201);
        assertThat(readBody(proposed).path("data").path("clauseVersionNo").asInt()).isEqualTo(2);
        assertThat(readBody(proposed).path("data").path("previousVersionNo").asInt()).isEqualTo(1);
        // 变更明细"从何值→到何值"落版本行（密文；R8 读面可读）
        final List<DealVersionView> versions = clauseVersions(contractNo, REQ);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1).changes().toString()).contains("subject_matter")
                .contains("from").contains("to");
        // 留痕 PROPOSE from=1→to=2（成功行 reason_code 为空）
        final Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT from_value, to_value FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'PROPOSE' AND reason_code IS NULL", contractNo);
        assertThat(logRow.get("from_value")).isEqualTo("1");
        assertThat(logRow.get("to_value")).isEqualTo("2");
    }

    @Test
    void t4_alternationViolationRejectedWithLog() throws Exception {
        final String contractNo = initiateOk(unique("交替"));
        // 当前版本提案方（需求方）连续提案 → C0013 + 留痕（reason C0013）
        final MvcResult repeated = propose(contractNo, REQ, slotsJson(unique("交替·连续")));
        assertThat(repeated.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(repeated)).isEqualTo("1008C0013");
        final Integer denialLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'PROPOSE' AND reason_code = 'C0013'", Integer.class, contractNo);
        assertThat(denialLogs).isEqualTo(1);
    }

    @Test
    void t4_doubleConfirmLocksToPendingSignatureExactlyOnce() throws Exception {
        final String contractNo = initiateOk(unique("确认锁定"));
        // 需求方先确认（状态仍在协商中——S1-3）
        final MvcResult first = confirm(contractNo, REQ);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(first).path("data").path("status").asText()).isEqualTo("NEGOTIATING");
        assertThat(readBody(first).path("data").path("confirmations").path("requester").asBoolean())
                .isTrue();
        // 重复确认 → C0013 + 留痕
        final MvcResult repeated = confirm(contractNo, REQ);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(repeated)).isEqualTo("1008C0013");
        // 提供方确认 → 双方齐锁定：规范化原文与内容哈希固化、状态转待签署
        final MvcResult second = confirm(contractNo, PROV);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(second).path("data").path("status").asText())
                .isEqualTo("PENDING_SIGNATURE");
        final Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT canonical_cipher, content_hash FROM contract_clause_version "
                        + "WHERE contract_id = (SELECT id FROM contract WHERE contract_no = ?) "
                        + "AND version_no = 1", contractNo);
        assertThat((byte[]) version.get("canonical_cipher")).isNotEmpty();
        assertThat(String.valueOf(version.get("content_hash"))).hasSize(64);
    }

    @Test
    void t4_negotiationTerminationIsTerminalWithLog() throws Exception {
        final String contractNo = initiateOk(unique("协商终止"));
        final MvcResult terminated = mockMvc.perform(post(
                        BASE + "/" + contractNo + "/negotiation-terminations")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(terminated.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(terminated).path("data").path("status").asText())
                .isEqualTo("TERMINATED");
        assertThat(readBody(terminated).path("data").path("terminationType").asText())
                .isEqualTo("NEGOTIATION_TERMINATED");
        // 终态零出边：再提案 → C0013（剧本 S1-8 终态语义）
        final MvcResult proposeOnTerminal = propose(contractNo, PROV,
                slotsJson(unique("协商终止·再提案")));
        assertThat(proposeOnTerminal.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(proposeOnTerminal)).isEqualTo("1008C0013");
        final Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT from_value, actor_subject_no FROM contract_action_log "
                        + "WHERE contract_no = ? AND action = 'TERMINATE_NEGOTIATION'", contractNo);
        assertThat(logRow.get("from_value")).isEqualTo("NEGOTIATING");
        assertThat(logRow.get("actor_subject_no")).isEqualTo(REQ);
    }

    // ==== T5 签署与存证（行为 3 规则 1/2/5/6；剧本 S2-1/2）====

    @Test
    void t5_didIdentityGates() throws Exception {
        final String contractNo = toPending(unique("签署身份"));
        // 未登记 / 已吊销 / 非签署方归属 → C0018（不冒充签署）
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.notRegistered());
        assertSignRejected(contractNo, REQ, REQUESTER_DID, 400, "1008C0018");
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.found(REQ, "REVOKED"));
        assertSignRejected(contractNo, REQ, REQUESTER_DID, 400, "1008C0018");
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.found(PROV, "ACTIVE"));
        assertSignRejected(contractNo, REQ, REQUESTER_DID, 400, "1008C0018");
        // 解析不可达 / 代签不可达 → S0002（不冒充签署能力）
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.unavailable());
        assertSignRejected(contractNo, REQ, REQUESTER_DID, 503, "1008S0002");
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.found(REQ, "ACTIVE"));
        given(didPort.sign(eq(REQUESTER_DID), anyString()))
                .willReturn(DidPort.DidSignResult.unavailable());
        assertSignRejected(contractNo, REQ, REQUESTER_DID, 503, "1008S0002");
        // 零签署行（全部身份腿拒绝——未产生签署事实）
        assertThat(signCount(contractNo)).isEqualTo(0);
    }

    private void assertSignRejected(final String contractNo, final String operator,
            final String did, final int expectedStatus, final String expectedCode) throws Exception {
        final MvcResult result = sign(contractNo, operator, did);
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(codeOf(result)).isEqualTo(expectedCode);
    }

    @Test
    void t5_doubleSignBecomesEffectiveWithAttestation() throws Exception {
        final String contractNo = toPending(unique("双签生效"));
        // 先签 → 部分签署 + 签名密文 + 留痕
        final MvcResult first = sign(contractNo, PROV, PROVIDER_DID);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(readBody(first).path("data").path("status").asText())
                .isEqualTo("PARTIALLY_SIGNED");
        assertThat(readBody(first).path("data").path("effectiveAt").isNull()).isTrue();
        assertThat(signatureCipher(contractNo, "PROVIDER")).isNotEmpty();
        // 后签 → 已生效：生效时间 = 最后一签 + 存证事件（哈希/双方要素）+ ATTEST 留痕
        final MvcResult second = sign(contractNo, REQ, REQUESTER_DID);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(readBody(second).path("data").path("status").asText()).isEqualTo("EFFECTIVE");
        final Map<String, Object> contract = jdbcTemplate.queryForMap(
                "SELECT effective_at FROM contract WHERE contract_no = ?", contractNo);
        final Map<String, Object> requesterSignature = jdbcTemplate.queryForMap(
                "SELECT signed_at FROM contract_signature WHERE contract_no = ? "
                        + "AND party_role = 'REQUESTER'", contractNo);
        assertThat(contract.get("effective_at")).isEqualTo(requesterSignature.get("signed_at"));
        final Map<String, Object> attestation = jdbcTemplate.queryForMap(
                "SELECT content_hash, provider_did, requester_did FROM contract_attestation "
                        + "WHERE contract_no = ?", contractNo);
        assertThat(attestation.get("provider_did")).isEqualTo(PROVIDER_DID);
        assertThat(attestation.get("requester_did")).isEqualTo(REQUESTER_DID);
        assertThat(attestation.get("content_hash")).isEqualTo(contentHash(contractNo));
        final Integer attestLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'ATTEST'", Integer.class, contractNo);
        assertThat(attestLogs).isEqualTo(1);
    }

    @Test
    void t5_decryptedCanonicalRecomputesStoredHash() throws Exception {
        final String contractNo = toEffective(unique("哈希重算"));
        // T5 一致性断言：解密规范化原文重算 SM3 == 落库内容哈希（hifi §6.2）
        final byte[] canonicalCipher = jdbcTemplate.queryForObject(
                "SELECT canonical_cipher FROM contract_clause_version "
                        + "WHERE contract_id = (SELECT id FROM contract WHERE contract_no = ?) "
                        + "AND version_no = 1", byte[].class, contractNo);
        final String recomputed = sm3Service.digestHex(dealTextCipher.decrypt(canonicalCipher));
        assertThat(recomputed).isEqualTo(contentHash(contractNo));
    }

    @Test
    void t5_alreadySignedPartyRejected() throws Exception {
        final String contractNo = toPending(unique("重复签署"));
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        final MvcResult repeated = sign(contractNo, PROV, PROVIDER_DID);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(repeated)).isEqualTo("1008C0013");
        assertThat(signCount(contractNo)).isEqualTo(1);
    }

    // ==== T6 终止与解除（行为 3 规则 3 + 行为 6 规则 1/2/4；剧本 S2-4/5、S3-3/4/5）====

    @Test
    void t6_refuseSignBeforeEffectiveBothStates() throws Exception {
        // 待签署态拒签（S2-4）
        final String pending = toPending(unique("拒签待签署"));
        final MvcResult refused = mockMvc.perform(post(
                        BASE + "/" + pending + "/signature-refusals")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(refused).path("data").path("terminationType").asText())
                .isEqualTo("SIGNATURE_REFUSED");
        // 部分签署态已签方撤回（Q4-A：拒签终止同一载体内涵）
        final String partial = toPending(unique("拒签部分签署"));
        assertThat(sign(partial, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        final MvcResult withdrawn = mockMvc.perform(post(
                        BASE + "/" + partial + "/signature-refusals")
                        .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(withdrawn.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(withdrawn).path("data").path("status").asText()).isEqualTo("TERMINATED");
    }

    @Test
    void t6_afterEffectiveSingleSideActionsRejectedWithLog() throws Exception {
        final String contractNo = toEffective(unique("生效后单方"));
        // 生效后拒签 → C0013 + 留痕（S2-5）
        final MvcResult refused = mockMvc.perform(post(
                        BASE + "/" + contractNo + "/signature-refusals")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(refused)).isEqualTo("1008C0013");
        final Integer refusalDenials = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'REFUSE_SIGN' AND reason_code = 'C0013'",
                Integer.class, contractNo);
        assertThat(refusalDenials).isEqualTo(1);
        // 生效后提案（单方变更类动作）→ C0013（剧本 C-4.3 S1-5 锚）
        final MvcResult proposed = propose(contractNo, REQ, slotsJson(unique("生效后单方·提案")));
        assertThat(proposed.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(proposed)).isEqualTo("1008C0013");
    }

    @Test
    void t6_releaseConsentBothPartiesCompletes() throws Exception {
        final String contractNo = toEffective(unique("合意解除"));
        // 提供方先确认（状态仍已生效）；重复确认 → C0013 + 留痕
        final MvcResult first = mockMvc.perform(post(BASE + "/" + contractNo + "/release-consents")
                        .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(first).path("data").path("status").asText()).isEqualTo("EFFECTIVE");
        assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/release-consents")
                        .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider"))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        // 需求方确认 → 已完结（终态）+ ended_at + 第二方留痕 to=COMPLETED
        final MvcResult second = mockMvc.perform(post(BASE + "/" + contractNo + "/release-consents")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(second).path("data").path("status").asText()).isEqualTo("COMPLETED");
        final Map<String, Object> contract = jdbcTemplate.queryForMap(
                "SELECT ended_at FROM contract WHERE contract_no = ?", contractNo);
        assertThat(contract.get("ended_at")).isNotNull();
        final Map<String, Object> secondLog = jdbcTemplate.queryForMap(
                "SELECT to_value FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'RELEASE_CONSENT' AND actor_subject_no = ?", contractNo, REQ);
        assertThat(secondLog.get("to_value")).isEqualTo("COMPLETED");
    }

    @Test
    void t6_forceTerminateRequiresReasonAndLogsOperator() throws Exception {
        final String contractNo = toEffective(unique("强制终止"));
        // 理由缺失 / 超长 → 1008C0008
        assertForceTerminateParamInvalid(contractNo, "{\"reason\":\"\"}");
        assertForceTerminateParamInvalid(contractNo, "{\"reason\":\"" + "由".repeat(513) + "\"}");
        // 成功：TERMINATED(GOVERNANCE_FORCE_TERMINATED) + 理由与操作者落库 + 留痕（剧本 S3-3）
        final MvcResult terminated = mockMvc.perform(post(
                        BASE + "/governance/" + contractNo + "/force-termination")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"查实违规使用，依规强制终止\"}"))
                .andReturn();
        assertThat(terminated.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(terminated).path("data").path("terminationType").asText())
                .isEqualTo("GOVERNANCE_FORCE_TERMINATED");
        final Map<String, Object> contract = jdbcTemplate.queryForMap(
                "SELECT termination_reason, terminated_by FROM contract WHERE contract_no = ?",
                contractNo);
        assertThat(contract.get("termination_reason")).isEqualTo("查实违规使用，依规强制终止");
        assertThat(contract.get("terminated_by")).isEqualTo(ADMIN);
        final Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT actor_subject_no, from_value, to_value FROM contract_action_log "
                        + "WHERE contract_no = ? AND action = 'FORCE_TERMINATE'", contractNo);
        assertThat(logRow.get("actor_subject_no")).isEqualTo(ADMIN);
        assertThat(logRow.get("from_value")).isEqualTo("EFFECTIVE");
        assertThat(logRow.get("to_value")).isEqualTo("TERMINATED");
    }

    private void assertForceTerminateParamInvalid(final String contractNo, final String body)
            throws Exception {
        final MvcResult result = mockMvc.perform(post(
                        BASE + "/governance/" + contractNo + "/force-termination")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1008C0008");
    }

    @Test
    void t6_terminalStateRejectsAllWrites() throws Exception {
        final String contractNo = initiateOk(unique("终态零出边"));
        assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/negotiation-terminations")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        // 终态上一切写动作 → C0013（提案/确认/签署/拒签/解除/强制终止全反证）
        assertWriteConflict(contractNo + "/proposals", REQ, "provider",
                proposeBody(slotsJson(unique("终态·提案"))));
        assertWriteConflict(contractNo + "/confirmations", REQ, "provider", null);
        assertWriteConflict(contractNo + "/signatures", REQ, "provider",
                signBody(REQUESTER_DID));
        assertWriteConflict(contractNo + "/signature-refusals", REQ, "provider", null);
        assertWriteConflict(contractNo + "/release-consents", REQ, "provider", null);
        assertWriteConflict("governance/" + contractNo + "/force-termination", ADMIN, "admin",
                "{\"reason\":\"终态复证\"}");
    }

    private void assertWriteConflict(final String pathSuffix, final String operator,
            final String role, final String body) throws Exception {
        final MockHttpServletRequestBuilder builder = post(BASE + "/" + pathSuffix)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", role);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        final MvcResult performed = mockMvc.perform(builder).andReturn();
        assertThat(performed.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(performed)).isEqualTo("1008C0013");
    }

    // ==== T7 可见性与治理（行为 7 规则 1/2/3；剧本 S3-1/2/4）====

    @Test
    void t7_nonParticipantSameShapeAsNotExistsWithDeniedLog() throws Exception {
        final String contractNo = initiateOk(unique("可见性"));
        // 非参与方读 → 与"不存在"同码同文案逐字（防枚举）+ DENIED_ACCESS 留痕（含合约号）
        final MvcResult notVisible = mockMvc.perform(get(BASE + "/" + contractNo)
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "provider"))
                .andReturn();
        final MvcResult notExists = mockMvc.perform(get(BASE + "/CO999999")
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(notVisible.getResponse().getStatus()).isEqualTo(404);
        assertThat(notExists.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(notVisible)).isEqualTo("1008C0012");
        assertThat(readBody(notVisible).path("message").asText())
                .isEqualTo(readBody(notExists).path("message").asText());
        assertThat(readBody(notVisible).path("message").asText()).isEqualTo("合约不存在或不可见");
        // 非参与方写（提案）同样 404 同形 + 留痕
        final MvcResult writeDenied = mockMvc.perform(post(BASE + "/" + contractNo + "/proposals")
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposeBody(slotsJson(unique("越权提案")))))
                .andReturn();
        assertThat(writeDenied.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(writeDenied)).isEqualTo("1008C0012");
        // 三条 DENIED_ACCESS：详情越权（含合约号）/ 不存在探测（NULL）/ 写越权（含合约号）
        final List<Map<String, Object>> deniedLogs = jdbcTemplate.queryForList(
                "SELECT contract_no FROM contract_action_log WHERE action = 'DENIED_ACCESS' "
                        + "AND actor_subject_no = ? AND reason_code = 'C0012'", OTHER);
        assertThat(deniedLogs).hasSize(3);
        assertThat(deniedLogs.stream().filter(row -> row.get("contract_no") != null)
                .allMatch(row -> contractNo.equals(row.get("contract_no")))).isTrue();
        assertThat(deniedLogs.stream().anyMatch(row -> row.get("contract_no") == null)).isTrue();
    }

    @Test
    void t7_unauthenticatedAndRolelessZeroActionLogs() throws Exception {
        // 未认证 401（注解层挡，零留痕）
        final Integer before = logCount();
        final MvcResult unauthenticated = mockMvc.perform(get(BASE + "/mine")).andReturn();
        assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
        // 普通档（无任何映射权限）403 零留痕（沿 3.4.2 T3 口径）
        final MvcResult roleless = mockMvc.perform(get(BASE + "/mine")
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "guest"))
                .andReturn();
        assertThat(roleless.getResponse().getStatus()).isEqualTo(403);
        assertThat(logCount()).isEqualTo(before);
    }

    @Test
    void t7_governanceFacesWriteViewLogsWithActualSubject() throws Exception {
        initiateOk(unique("治理留痕"));
        final Integer beforeList = governanceViewCount();
        final MvcResult list = mockMvc.perform(get(BASE + "/governance")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(governanceViewCount()).isEqualTo(beforeList + 1);
        // 治理详情留痕含合约号 + 实际登录主体（剧本 S3-2）
        final String contractNo = initiateOk(unique("治理详情"));
        final MvcResult governanceDetail = mockMvc.perform(get(BASE + "/governance/" + contractNo)
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn();
        assertThat(governanceDetail.getResponse().getStatus()).isEqualTo(200);
        final Map<String, Object> detailLog = jdbcTemplate.queryForMap(
                "SELECT actor_subject_no FROM contract_action_log "
                        + "WHERE action = 'GOVERNANCE_VIEW' AND contract_no = ?", contractNo);
        assertThat(detailLog.get("actor_subject_no")).isEqualTo(ADMIN);
        // provider 调治理面 → 注解层 403（无留痕——治理面越权腿不在应用层）
        final Integer beforeDenied = governanceViewCount();
        assertThat(mockMvc.perform(get(BASE + "/governance")
                        .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(governanceViewCount()).isEqualTo(beforeDenied);
    }

    // ==== T8 策略门槛与失效锚（行为 4 规则 1/2/4/5/6；剧本 C-4.3 S1-2/3/4/5）====

    @Test
    void t8_confirmWithoutPolicyOrDeclarationRejected() throws Exception {
        // 提交放行（提交时只校验基础取值），确认锁定时门槛 → C0015（剧本 C-4.3 S1-4）
        final String contractNo = initiateOk(unique("无策略"),
                "{\"quota\":{\"enabled\":false},\"noRestrictionDeclared\":false}");
        final MvcResult confirmed = confirm(contractNo, REQ);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(confirmed)).isEqualTo("1008C0015");
        // 合约仍在协商中（未锁定）
        assertThat(readBody(getDetail(contractNo, REQ)).path("data").path("status").asText())
                .isEqualTo("NEGOTIATING");
    }

    @Test
    void t8_explicitDeclarationOnlyLocksAndStrategyVisibleToBoth() throws Exception {
        // 显式"无使用限制"对照组可锁定（行为 4 规则 2 第二满足臂）
        final String declaredOnly = toPending(unique("显式声明"));
        assertThat(readBody(getDetail(declaredOnly, REQ)).path("data").path("status").asText())
                .isEqualTo("PENDING_SIGNATURE");
        // 策略全文双方可查（剧本 C-4.3 S1-2 锚；次数要素以 JSON number 承载）
        final String policyContractNo = initiateOk(unique("策略全文"), quotaStrategy(100));
        final JsonNode providerView = readBody(getDetail(policyContractNo, PROV)).path("data")
                .path("clauseValues").path("strategy");
        assertThat(providerView.path("quota").path("enabled").asBoolean()).isTrue();
        assertThat(providerView.path("quota").path("maxCount").asInt()).isEqualTo(100);
        final JsonNode requesterView = readBody(getDetail(policyContractNo, REQ)).path("data")
                .path("clauseValues").path("strategy");
        assertThat(requesterView).isEqualTo(providerView);
    }

    // ==== T9 密文落库探针（行为 7 规则 4/5）====

    @Test
    void t9_cipherColumnsAndActionLogContainNoPlaintext() throws Exception {
        final String marker = unique("密文探针");
        final String contractNo = toEffectiveWithProposal(marker);
        // 四类密文列逐一反查（当前版本行：条款值/变更明细/规范化原文 + 签名值——明文零出现）
        final int currentVersion = currentVersion(contractNo);
        assertThat(new String(versionCipher(contractNo, currentVersion, "clause_values_cipher"),
                StandardCharsets.ISO_8859_1)).doesNotContain(marker);
        assertThat(new String(versionCipher(contractNo, currentVersion, "changes_cipher"),
                StandardCharsets.ISO_8859_1)).doesNotContain(marker);
        assertThat(new String(versionCipher(contractNo, currentVersion, "canonical_cipher"),
                StandardCharsets.ISO_8859_1)).doesNotContain(marker);
        assertThat(new String(signatureCipher(contractNo, "PROVIDER"),
                StandardCharsets.ISO_8859_1)).doesNotContain(PROVIDER_SIGNATURE);
        // 留痕表零敏感原文（字段集逐列反查——值级明细仅在加密版本行）
        final List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT contract_no, version_no, action, actor_subject_no, reason_code, "
                        + "from_value, to_value FROM contract_action_log WHERE contract_no = ?",
                contractNo);
        assertThat(logs).isNotEmpty();
        for (final Map<String, Object> log : logs) {
            for (final Object value : log.values()) {
                if (value instanceof String text) {
                    assertThat(text).doesNotContain(marker).doesNotContain(PROVIDER_SIGNATURE);
                }
            }
        }
        // API 读取经角色边界解密正确（对照探针）
        assertThat(readBody(getDetail(contractNo, REQ)).path("data").path("clauseValues")
                .path("slots").path("subject_matter").asText()).contains(marker);
    }

    // ==== T10 并发兜底（hifi §5；换驱动回归清单 hifi §10-2）====

    @Test
    void t10_concurrentProposesExactlyOneVersionWins() throws Exception {
        final String contractNo = initiateOk(unique("并发提案"));
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final List<Future<MvcResult>> results = new ArrayList<>();
        for (final String marker : List.of(unique("并发A"), unique("并发B"))) {
            results.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post(BASE + "/" + contractNo + "/proposals")
                                .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(proposeBody(slotsJson(marker))))
                        .andReturn();
            }));
        }
        final List<Integer> statuses = new ArrayList<>();
        for (final Future<MvcResult> future : results) {
            final MvcResult result = future.get(15, TimeUnit.SECONDS);
            statuses.add(result.getResponse().getStatus());
            if (result.getResponse().getStatus() != 201) {
                // 恰一成一败（败者撞 uk_contract_version → 1008C0019 重试语义）
                assertThat(result.getResponse().getStatus()).isEqualTo(409);
                assertThat(codeOf(result)).isEqualTo("1008C0019");
            }
        }
        pool.shutdownNow();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        final Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?)", Integer.class, contractNo);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void t10_concurrentConfirmsLockExactlyOnce() throws Exception {
        final String contractNo = initiateOk(unique("并发确认"));
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final List<Future<MvcResult>> results = List.of(
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return confirm(contractNo, REQ);
                }),
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return confirm(contractNo, PROV);
                }));
        for (final Future<MvcResult> future : results) {
            assertThat(future.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        pool.shutdownNow();
        // 恰一次锁定：状态待签署 + 哈希唯一固化（后到者同事务内复查——hifi §5 W7）
        final Map<String, Object> contract = jdbcTemplate.queryForMap(
                "SELECT status FROM contract WHERE contract_no = ?", contractNo);
        assertThat(contract.get("status")).isEqualTo("PENDING_SIGNATURE");
        final Map<String, Object> version = jdbcTemplate.queryForMap(
                "SELECT content_hash FROM contract_clause_version "
                        + "WHERE contract_id = (SELECT id FROM contract WHERE contract_no = ?) "
                        + "AND version_no = 1", contractNo);
        assertThat(String.valueOf(version.get("content_hash"))).hasSize(64);
    }

    @Test
    void t10_concurrentSignsReachEffectiveWithSingleAttestation() throws Exception {
        final String contractNo = toPending(unique("并发签署"));
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final List<Future<MvcResult>> results = List.of(
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return sign(contractNo, PROV, PROVIDER_DID);
                }),
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return sign(contractNo, REQ, REQUESTER_DID);
                }));
        for (final Future<MvcResult> future : results) {
            assertThat(future.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        }
        pool.shutdownNow();
        // 终局一致：已生效 + 生效时间 = 最后一签 + 恰一张存证事件
        final Map<String, Object> contract = jdbcTemplate.queryForMap(
                "SELECT status, effective_at FROM contract WHERE contract_no = ?", contractNo);
        assertThat(contract.get("status")).isEqualTo("EFFECTIVE");
        final Map<String, Object> lastSignature = jdbcTemplate.queryForMap(
                "SELECT MAX(signed_at) AS last_at FROM contract_signature WHERE contract_no = ?",
                contractNo);
        assertThat(contract.get("effective_at")).isEqualTo(lastSignature.get("last_at"));
        assertThat(signCount(contractNo)).isEqualTo(2);
        final Integer attestations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_attestation WHERE contract_no = ?",
                Integer.class, contractNo);
        assertThat(attestations).isEqualTo(1);
    }

    // ==== T11 读面与字段集（行为 7 规则 4；剧本 S2-3）====

    @Test
    void t11_mineListsOnlyOwnContractsWithFilters() throws Exception {
        final String contractNo = initiateOk(unique("我的列表"));
        // 需求方（REQUESTER 过滤）与提供方（无过滤/PROVIDER 过滤）可见；对手方角色过滤正确
        final JsonNode requesterMine = readBody(mockMvc.perform(get(BASE + "/mine?role=REQUESTER")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn());
        assertThat(requesterMine.path("data").path("list").toString()).contains(contractNo);
        final JsonNode requesterAsProvider = readBody(mockMvc.perform(get(BASE + "/mine?role=PROVIDER")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn());
        assertThat(requesterAsProvider.path("data").path("list").toString())
                .doesNotContain(contractNo);
        final JsonNode providerMine = readBody(mockMvc.perform(get(BASE + "/mine")
                        .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider"))
                .andReturn());
        assertThat(providerMine.path("data").path("list").toString()).contains(contractNo);
        // 非参与方恒空（R6 无 404 语义——列表天然按主体过滤）
        final JsonNode otherMine = readBody(mockMvc.perform(get(BASE + "/mine")
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "provider"))
                .andReturn());
        assertThat(otherMine.path("data").path("list").toString()).doesNotContain(contractNo);
    }

    @Test
    void t11_detailFieldSetAnchoredWithoutSignatureValues() throws Exception {
        final String contractNo = toEffective(unique("字段锚定"));
        final JsonNode data = readBody(getDetail(contractNo, REQ)).path("data");
        // 出站字段集显式锚定（hifi §2.2 R7——无数据本体；签名值不出站）
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("contractNo",
                "productId", "productName", "pricingModel", "priceAmount", "providerSubjectNo",
                "requesterSubjectNo", "templateNo", "templateVersionNo", "status",
                "terminationType", "terminationReason", "terminatedBy", "effectiveAt", "endedAt",
                "currentClauseVersion", "clauseValues", "contentHash", "signatures",
                "attestation", "createdAt", "updatedAt");
        final JsonNode signature = data.path("signatures").get(0);
        assertThat(signature.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "partyRole", "subjectNo", "did", "signedAt");
        assertThat(data.path("signatures").toString()).doesNotContain("signatureCipher");
        // 存证事件摘要（哈希一致 + 双方要素）
        assertThat(data.path("attestation").path("contentHash").asText())
                .isEqualTo(data.path("contentHash").asText());
    }

    @Test
    void t11_clauseVersionHistoryCarriesFullTextAndChanges() throws Exception {
        final String contractNo = initiateOk(unique("版本历史"));
        assertThat(propose(contractNo, PROV, slotsJson(unique("版本历史·V2")))
                .getResponse().getStatus()).isEqualTo(201);
        final List<DealVersionView> versions = clauseVersions(contractNo, REQ);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(0).changes()).isNull();
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
        assertThat(versions.get(1).proposedBy()).isEqualTo(PROV);
        assertThat(versions.get(1).changes().toString()).contains("subject_matter")
                .contains("from").contains("to");
        assertThat(versions.get(1).clauseValues().path("slots").path("subject_matter").asText())
                .contains("版本历史·V2");
    }

    @Test
    void t11_verificationReportsPassAndTamperFailWithLog() throws Exception {
        final String contractNo = toEffective(unique("验签"));
        // 双方 PASS（剧本 S2-3）
        final JsonNode report = readBody(mockMvc.perform(post(
                        BASE + "/" + contractNo + "/signature-verifications")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn());
        assertThat(report.path("data").path("results")).hasSize(2);
        assertThat(report.path("data").path("results").toString()).contains("PASS");
        // 篡改对照探针：did 三查 FAIL → 逐方 FAIL + VERIFY_SIGNATURE_FAILED 留痕（reason C0018）
        given(didPort.verify(eq(PROVIDER_DID), anyString(), anyString())).willReturn(
                DidPort.DidVerifyResult.of(DidPort.DidVerifyResult.Outcome.FAIL,
                        "SIGNATURE_INVALID"));
        final JsonNode tampered = readBody(mockMvc.perform(post(
                        BASE + "/" + contractNo + "/signature-verifications")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn());
        assertThat(tampered.path("data").path("results").toString()).contains("FAIL");
        final Map<String, Object> failureLog = jdbcTemplate.queryForMap(
                "SELECT reason_code, to_value FROM contract_action_log "
                        + "WHERE contract_no = ? AND action = 'VERIFY_SIGNATURE_FAILED'", contractNo);
        assertThat(failureLog.get("reason_code")).isEqualTo("C0018");
        assertThat(failureLog.get("to_value")).isEqualTo("FAIL");
        // did 传输不可达 → 整体 S0002（不冒充结论——hifi §2.2 R9 口径）
        given(didPort.verify(anyString(), anyString(), anyString())).willReturn(
                DidPort.DidVerifyResult.transportUnreachable());
        final MvcResult unreachable = mockMvc.perform(post(
                        BASE + "/" + contractNo + "/signature-verifications")
                        .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn();
        assertThat(unreachable.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(unreachable)).isEqualTo("1008S0002");
    }

    // ==== T12 供 3.4.5 的策略读取（移交-5；QC1 无 HTTP 端点，同宿主直调）====

    @Test
    void t12_loadEffectiveStrategyByStatus() {
        // 未生效（协商中）= 空策略
        final var notEffective = queryService.loadEffectiveStrategy(
                contractNoOfNegotiating(unique("策略未生效")));
        assertThat(notEffective).isNotNull();
        assertThat(notEffective.status()).isEqualTo(ContractStatus.NEGOTIATING.name());
        assertThat(notEffective.strategy()).isNull();
        final String effective = effectiveContractNo(unique("策略生效"));
        final var snapshot = queryService.loadEffectiveStrategy(effective);
        assertThat(snapshot.status()).isEqualTo(ContractStatus.EFFECTIVE.name());
        assertThat(snapshot.effectiveAt()).isNotNull();
        assertThat(snapshot.strategy().noRestrictionDeclared()).isTrue();
        // 已终止 = 状态可判（引擎据状态拦截"合约终止 → 策略同步失效"——判定归 3.4.5）
        terminateEffectively(effective);
        final var terminated = queryService.loadEffectiveStrategy(effective);
        assertThat(terminated.status()).isEqualTo(ContractStatus.TERMINATED.name());
        assertThat(terminated.strategy()).isNull();
    }

    private String contractNoOfNegotiating(final String marker) {
        try {
            return initiateOk(marker);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String effectiveContractNo(final String marker) {
        try {
            return toEffective(marker);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void terminateEffectively(final String contractNo) {
        try {
            mockMvc.perform(post(BASE + "/governance/" + contractNo + "/force-termination")
                            .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"策略失效锚\"}"))
                    .andReturn();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==== 支撑（请求/断言助手）====

    private MvcResult initiate(final String operator, final String body) throws Exception {
        return mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String initiateOk(final String marker) throws Exception {
        return initiateOk(marker, DEFAULT_STRATEGY);
    }

    private String initiateOk(final String marker, final String strategyJson) throws Exception {
        final MvcResult result = initiate(REQ,
                initiateBody(marker, TEMPLATE_NO, 1, strategyJson));
        assertThat(result.getResponse().getStatus())
                .as("发起应成功: %s", readBody(result)).isEqualTo(201);
        return readBody(result).path("data").path("contractNo").asText();
    }

    private MvcResult propose(final String contractNo, final String operator,
            final String clauseValuesJson) throws Exception {
        return mockMvc.perform(post(BASE + "/" + contractNo + "/proposals")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(proposeBody(clauseValuesJson))).andReturn();
    }

    private MvcResult confirm(final String contractNo, final String operator) throws Exception {
        return mockMvc.perform(post(BASE + "/" + contractNo + "/confirmations")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                .andReturn();
    }

    private MvcResult sign(final String contractNo, final String operator, final String did)
            throws Exception {
        return mockMvc.perform(post(BASE + "/" + contractNo + "/signatures")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON).content(signBody(did))).andReturn();
    }

    private MvcResult getDetail(final String contractNo, final String operator) throws Exception {
        return mockMvc.perform(get(BASE + "/" + contractNo)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                .andReturn();
    }

    /** 协商链捷径：发起 + 双方确认 → 待签署。 */
    private String toPending(final String marker) throws Exception {
        final String contractNo = initiateOk(marker);
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        return contractNo;
    }

    /** 生效捷径：待签署 + 双方签署（先提供方后需求方）。 */
    private String toEffective(final String marker) throws Exception {
        final String contractNo = toPending(marker);
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(sign(contractNo, REQ, REQUESTER_DID).getResponse().getStatus()).isEqualTo(201);
        return contractNo;
    }

    /** 密文探针捷径：发起 + 提案 + 双方确认 + 双签（变更明细/规范化原文/签名密文齐备）。 */
    private String toEffectiveWithProposal(final String marker) throws Exception {
        final String contractNo = initiateOk(marker);
        assertThat(propose(contractNo, PROV, slotsJson(marker + "-V2"))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(sign(contractNo, REQ, REQUESTER_DID).getResponse().getStatus()).isEqualTo(201);
        return contractNo;
    }

    private List<DealVersionView> clauseVersions(final String contractNo, final String operator)
            throws Exception {
        final JsonNode data = readBody(mockMvc.perform(get(
                        BASE + "/" + contractNo + "/clause-versions")
                        .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                .andReturn()).path("data");
        final List<DealVersionView> versions = new ArrayList<>();
        for (final JsonNode node : data) {
            versions.add(new DealVersionView(node.path("versionNo").asInt(),
                    node.path("clauseValues"), node.path("changes").isNull()
                            || node.path("changes").isMissingNode() ? null : node.path("changes"),
                    node.path("proposedBy").asText()));
        }
        return versions;
    }

    /** 版本历史行轻载体（测试内断言用）。 */
    private record DealVersionView(int versionNo, JsonNode clauseValues, JsonNode changes,
            String proposedBy) {
    }

    /** CT000001 V1 框架九必填槽位（公共数据授权；marker 内嵌 subject_matter——幂等键隔离）。 */
    private static String slotsJson(final String marker) {
        return clauseValuesJson(marker, DEFAULT_STRATEGY);
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

    private static String initiateBody(final String marker) {
        return initiateBody(marker, TEMPLATE_NO, 1, DEFAULT_STRATEGY);
    }

    private static String initiateBody(final String marker, final String templateNo,
            final int versionNo) {
        return initiateBody(marker, templateNo, versionNo, DEFAULT_STRATEGY);
    }

    private static String initiateBody(final String marker, final String templateNo,
            final int versionNo, final String strategyJson) {
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + templateNo
                + "\",\"templateVersionNo\":" + versionNo
                + ",\"clauseValues\":" + clauseValuesJson(marker, strategyJson) + "}";
    }

    private static String initiateBodyFromClauseValues(final String clauseValuesJson) {
        return initiateBodyFromClauseValues(clauseValuesJson, TEMPLATE_NO, 1);
    }

    private static String initiateBodyFromClauseValues(final String clauseValuesJson,
            final String templateNo, final int versionNo) {
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + templateNo
                + "\",\"templateVersionNo\":" + versionNo + ",\"clauseValues\":"
                + clauseValuesJson + "}";
    }

    private static String proposeBody(final String clauseValuesJson) {
        return "{\"clauseValues\":" + clauseValuesJson + "}";
    }

    private static String signBody(final String did) {
        return "{\"did\":\"" + did + "\"}";
    }

    private static String quotaStrategy(final int maxCount) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static String termStrategy(final String startDate, final String endDate) {
        return "{\"term\":{\"enabled\":true,\"startDate\":\"" + startDate
                + "\",\"endDate\":\"" + endDate + "\"},\"noRestrictionDeclared\":false}";
    }

    private static JsonNode parseNode(final String json) {
        try {
            return MAPPER.readTree(json);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode readBody(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String codeOf(final MvcResult result) {
        return readBody(result).path("code").asText();
    }

    private static String unique(final String label) {
        return label + "-" + Long.toHexString(System.nanoTime());
    }

    private int currentVersion(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT current_clause_version FROM contract WHERE contract_no = ?",
                Integer.class, contractNo);
    }

    private byte[] versionCipher(final String contractNo, final int versionNo,
            final String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?) AND version_no = ?",
                byte[].class, contractNo, versionNo);
    }

    private byte[] signatureCipher(final String contractNo, final String role) {
        return jdbcTemplate.queryForObject(
                "SELECT signature_cipher FROM contract_signature WHERE contract_no = ? "
                        + "AND party_role = ?", byte[].class, contractNo, role);
    }

    private String contentHash(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT content_hash FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?) AND version_no = "
                        + "(SELECT current_clause_version FROM contract WHERE contract_no = ?)",
                String.class, contractNo, contractNo);
    }

    private int signCount(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_signature WHERE contract_no = ?",
                Integer.class, contractNo);
    }

    private int logCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log", Integer.class);
    }

    private int governanceViewCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE action = 'GOVERNANCE_VIEW'",
                Integer.class);
    }
}
