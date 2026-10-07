package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    /** C-4.3 五要素同时启用（合法取值）——策略生效侧端到端锚。 */
    private static final String ALL_ELEMENTS_STRATEGY = "{\"quota\":{\"enabled\":true,\"maxCount\":3},"
            + "\"term\":{\"enabled\":true,\"startDate\":\"2027-01-01\",\"endDate\":\"2027-12-31\"},"
            + "\"purpose\":{\"enabled\":true,\"text\":\"政策研究，禁止再分发\"},"
            + "\"territory\":{\"enabled\":true,\"text\":\"中华人民共和国境内\"},"
            + "\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}";
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
        // 单方成功确认亦落 CONFIRM 留痕（规格行为 2 规则 6 / 剧本 S1-3"确认动作均留痕"——
        // 勘误⑨：验收走查实证单方腿缺失后补齐；原实现仅双方齐锁定腿落日志）
        final Integer firstConfirmLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'CONFIRM' AND actor_subject_no = ? AND reason_code IS NULL",
                Integer.class, contractNo, REQ);
        assertThat(firstConfirmLogs).isEqualTo(1);
        // 提供方确认 → 双方齐锁定：规范化原文与内容哈希固化、状态转待签署
        final MvcResult second = confirm(contractNo, PROV);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(second).path("data").path("status").asText())
                .isEqualTo("PENDING_SIGNATURE");
        // 双方确认各留痕一行（成功腿；重复确认的拒绝行 reason_code = C0013 不计入）
        final Integer successConfirmLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'CONFIRM' AND reason_code IS NULL", Integer.class, contractNo);
        assertThat(successConfirmLogs).isEqualTo(2);
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

    /**
     * 确认×提案真并发交错（latch 确定性交错；评审循环 2 P0——修复前"确认停在资格窗口内 +
     * 对方先确认 V1 再提案 V2"可锁定陈旧版本，产生"待签署 + 当前版本未锁定"的不可签死状态）：
     * 需求方确认线程停在资格桩内（requireAdmitted 窗口——合约陈旧读已发生、确认事务未开始），
     * 提供方在其窗口内先确认 V1、再提案 V2 提交；放行后确认以陈旧 V1 版本号进入事务。
     * 期望：锁下版本指针复判不等 → 1008C0013 + 拒绝留痕，合约保持协商中、两版本行均未锁定；
     * 随后按当前版本 V2 逐方确认可正常锁定（不可签死状态未固化）。
     */
    @Test
    void t10_confirmParkedInAdmissionWindowThenProposeCommitsIsRejectedNotLockingStaleVersion()
            throws Exception {
        final String contractNo = initiateOk(unique("确认提案交错"));
        // 提供方先确认 V1（单方确认，未锁定）
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        // 需求方确认线程停在资格桩内（确认事务前的窗口——合约陈旧读已发生）
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        given(subjectAdmissionPort.check(REQ)).willAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return SubjectAdmission.ADMITTED;
        });
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final Future<MvcResult> parked = pool.submit(() -> confirm(contractNo, REQ));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        // 交错动作：提供方在窗口内提案 V2 并提交（指针前移至 2）
        assertThat(propose(contractNo, PROV, slotsJson(unique("交错-V2")))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(currentVersion(contractNo)).isEqualTo(2);
        // 放行确认线程：以陈旧 V1 版本号进入确认事务
        release.countDown();
        final MvcResult staleConfirm = parked.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        // 确认被拒（C0013 + 拒绝留痕）——不得出现"待签署 + 当前版本未锁定"死状态
        assertThat(staleConfirm.getResponse().getStatus())
                .as("陈旧版本确认应答: %s", readBody(staleConfirm)).isEqualTo(409);
        assertThat(codeOf(staleConfirm)).isEqualTo("1008C0013");
        assertThat(statusOf(contractNo)).isEqualTo("NEGOTIATING");
        assertThat(hashOf(contractNo, 1)).isNull();
        assertThat(hashOf(contractNo, 2)).isNull();
        final Integer staleDenials = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE contract_no = ? "
                        + "AND action = 'CONFIRM' AND reason_code = 'C0013'",
                Integer.class, contractNo);
        assertThat(staleDenials).isEqualTo(1);
        // 恢复性：按当前版本 V2 重新逐方确认 → 正常锁定转待签署（死锁解除）
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(contractNo)).isEqualTo("PENDING_SIGNATURE");
        assertThat(hashOf(contractNo, 2)).isNotNull();
        assertThat(hashOf(contractNo, 1)).isNull();
    }

    /**
     * 提案指针前置守卫杀手（评审循环 2 P2——并发提案败者此前仅死于 uk_contract_version
     * 唯一索引，指针/状态条件更新未命中腿无测试触达）：提案线程停在资格桩内，窗口内双方确认
     * V1 齐 → 锁定转待签署；放行后提案以陈旧"协商中"读进入事务——指针条件更新未命中
     * （状态 ≠ NEGOTIATING）→ 版本行插入随事务回滚 → 1008C0019，合约保持待签署不受扰动。
     */
    @Test
    void t10_proposeAfterInterleavedLockMissesPointerGuardAndRollsBackWithC0019() throws Exception {
        final String contractNo = initiateOk(unique("提案守卫杀手"));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger provAdmissions = new AtomicInteger();
        // 提供方第 1 次资格调用（= 提案）停在桩内；后续调用（= 确认）直放——窗口内可完成双方确认
        given(subjectAdmissionPort.check(PROV)).willAnswer(invocation -> {
            if (provAdmissions.incrementAndGet() == 1) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return SubjectAdmission.ADMITTED;
        });
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final Future<MvcResult> parked = pool.submit(() -> propose(contractNo, PROV,
                slotsJson(unique("杀手-V2"))));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        // 窗口内：双方确认 V1 齐 → 锁定转待签署（指针仍为 1，状态已非协商中）
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(contractNo)).isEqualTo("PENDING_SIGNATURE");
        // 放行提案线程：状态/指针条件更新未命中 → 事务回滚 + C0019
        release.countDown();
        final MvcResult loser = parked.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(loser.getResponse().getStatus()).as("败者提案应答: %s", readBody(loser))
                .isEqualTo(409);
        assertThat(codeOf(loser)).isEqualTo("1008C0019");
        // 合约未被扰动：仍待签署 + V1 锁定 + 版本行仅 1（V2 插入已随事务回滚）
        assertThat(statusOf(contractNo)).isEqualTo("PENDING_SIGNATURE");
        assertThat(currentVersion(contractNo)).isEqualTo(1);
        assertThat(hashOf(contractNo, 1)).isNotNull();
        final Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?)", Integer.class,
                contractNo);
        assertThat(versionRows).isEqualTo(1);
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

    // ==== T10 并发兜底补腿（hifi §5 W6/W7 跨动作交错 + §5 锁定/签署口径实证）====

    /**
     * 确认与提案跨动作交错守卫（V1 已被需求方确认、提供方随后提案 V2 的交错序列）：确认对象恒为
     * **当前版本**（W7 无版本入参——口径实证），且锁定对象必须与当前版本指针一致：
     * ① 交错确认落在 V2（不回写 V1）；② V1 保持不可变、不得被锁定（其哈希与 canonical 恒为空）；
     * ③ 双方对 V2 确认齐 → 恰好锁定 V2 并转待签署 → 可签署生效；④ 生效后对 V1 的重复签署被拒。
     * 即：不存在"status=待签署而当前版本未锁定"的不可签死状态（hifi §5 W7 / §6.4）。
     */
    @Test
    void t10_confirmationFollowsCurrentVersionAndLocksExactlyThatVersion() throws Exception {
        final String contractNo = initiateOk(unique("版本交错守卫"));
        // 需求方确认 → 落在当前版本 V1
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(currentVersion(contractNo)).isEqualTo(1);
        assertThat(confirmedRequesterAt(contractNo, 1)).isNotNull();
        assertThat(confirmedProviderAt(contractNo, 1)).isNull();
        // 提供方提案 → 指针前移至 V2（V1 不可变）
        assertThat(propose(contractNo, PROV, slotsJson(unique("守卫-V2"))).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(currentVersion(contractNo)).isEqualTo(2);
        // 交错点：需求方再确认 → 只能作用于当前版本 V2；V1 的确认列在现场保持不变（不回写）
        final MvcResult interleaved = confirm(contractNo, REQ);
        assertThat(interleaved.getResponse().getStatus())
                .as("交错确认应答: %s", readBody(interleaved)).isEqualTo(200);
        assertThat(confirmedRequesterAt(contractNo, 2))
                .as("确认必须落在当前版本 V2（v1Req=%s v2Req=%s ptr=%s）",
                        confirmedRequesterAt(contractNo, 1), confirmedRequesterAt(contractNo, 2),
                        currentVersion(contractNo)).isNotNull();
        assertThat(confirmedProviderAt(contractNo, 2)).isNull();
        assertThat(confirmedRequesterAt(contractNo, 1)).isNotNull();
        assertThat(confirmedProviderAt(contractNo, 1)).isNull();
        // V1 不得被锁定（无 canonical / 无哈希）；V2 未齐 → 未锁定、未转态
        assertThat(canonicalCipher(contractNo, 1)).isNull();
        assertThat(hashOf(contractNo, 1)).isNull();
        assertThat(hashOf(contractNo, 2)).isNull();
        assertThat(readBody(getDetail(contractNo, REQ)).path("data").path("status").asText())
                .isEqualTo("NEGOTIATING");
        // 提供方确认 V2 → 双方齐 → 恰好锁定 V2 并转待签署（恰在这一步锁定）
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(getDetail(contractNo, REQ)).path("data").path("status").asText())
                .isEqualTo("PENDING_SIGNATURE");
        // 锁定对象 = 当前版本 V2（哈希落 V2；V1 无哈希、无规范化原文）
        assertThat(hashOf(contractNo, 2)).isNotNull();
        assertThat(hashOf(contractNo, 1)).isNull();
        assertThat(canonicalCipher(contractNo, 1)).isNull();
        // 锁定后需求方重复确认 → 状态门槛拒绝（幂等语义）
        final MvcResult repeatAfterLock = confirm(contractNo, REQ);
        assertThat(repeatAfterLock.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(repeatAfterLock)).isEqualTo("1008C0013");
        // 签署可用（生效）——交错不产生不可签死状态
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        final MvcResult effective = sign(contractNo, REQ, REQUESTER_DID);
        assertThat(effective.getResponse().getStatus()).isEqualTo(201);
        assertThat(readBody(effective).path("data").path("status").asText()).isEqualTo("EFFECTIVE");
        // 生效后对旧版本 V1 的重复签署 → 状态门槛拒绝，合约态不变（终局一致性）
        final MvcResult replayOnV1 = sign(contractNo, PROV, PROVIDER_DID);
        assertThat(replayOnV1.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(replayOnV1)).isEqualTo("1008C0013");
        assertThat(readBody(getDetail(contractNo, REQ)).path("data").path("status").asText())
                .isEqualTo("EFFECTIVE");
    }

    /**
     * 锁定哈希"恰一次固化"（hifi §5 W7：`content_hash IS NULL` 条件封口）——锁定时固化的规范化原文
     * 与内容哈希**不得被后续确认改写**：锁定后以对方重复确认触发，落库 canonical/哈希逐字不变，
     * 且数据库存哈希 == 按落库密文独立重算的 SM3（锁定值自洽）。
     */
    @Test
    void t10_lockedHashIsImmutableOnRepeatedConfirmation() throws Exception {
        final String contractNo = toPending(unique("锁定固化守卫"));
        final byte[] lockedCanonical = canonicalCipher(contractNo, 1);
        final String lockedHash = hashOf(contractNo, 1);
        assertThat(lockedCanonical).isNotNull();
        assertThat(lockedHash).isNotNull();
        // 对方在已锁定状态重复确认 → 状态门槛拒绝（幂等语义不变）
        final MvcResult repeated = confirm(contractNo, PROV);
        assertThat(repeated.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(repeated)).isEqualTo("1008C0013");
        // 落库锁定值逐字不变（哈希不得被覆盖为第二次确认的候选值）
        assertThat(hashOf(contractNo, 1)).isEqualTo(lockedHash);
        assertThat(canonicalCipher(contractNo, 1)).isEqualTo(lockedCanonical);
        // 数据库存哈希 == 独立重算（按落库规范化原文密文解密后 SM3）
        assertThat(sm3Service.digestHex(dealTextCipher.decrypt(canonicalCipher(contractNo, 1))))
                .isEqualTo(lockedHash);
    }

    /**
     * 签署内容 = 锁定版本规范化原文（hifi §6.4 ③、§6.2"链上/库内一致性"）——以 did 桩**捕获**
     * 代签入参并与锁定 canonical 的 SM3 逐字比对：排除"所签内容与锁定快照脱钩"。
     */
    @Test
    void t10_signedPayloadEqualsLockedCanonicalHash() throws Exception {
        final String contractNo = toPending(unique("签署输入锚"));
        final AtomicReference<String> signedPayload = new AtomicReference<>();
        // 桩先捕获入参，再返回与所签内容一致的签名值（固定桩无法证伪"内容脱钩"）
        given(didPort.sign(eq(PROVIDER_DID), anyString())).willAnswer(invocation -> {
            final String payload = invocation.getArgument(1, String.class);
            signedPayload.set(payload);
            return DidPort.DidSignResult.signed("c2ln" + sm3Service.digestHex(payload));
        });
        final MvcResult signed = sign(contractNo, PROV, PROVIDER_DID);
        assertThat(signed.getResponse().getStatus()).isEqualTo(201);
        final String lockedHash = hashOf(contractNo, 1);
        assertThat(signedPayload.get())
                .as("代签入参必须等于锁定版本内容哈希（=%s）", lockedHash).isEqualTo(lockedHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_hash FROM contract_signature WHERE contract_no = ? "
                        + "AND party_role = 'PROVIDER'", String.class, contractNo))
                .isEqualTo(lockedHash);
        // 与落库规范化原文独立重算一致（锁定快照即代签输入）
        assertThat(sm3Service.digestHex(dealTextCipher.decrypt(canonicalCipher(contractNo, 1))))
                .isEqualTo(lockedHash);
    }

    /**
     * 资格失效 fail-closed 六写语境（规格 Q8-A：每次业务写动作校验操作者 ADMITTED；hifi §5.1 口径
     * 延展）——需求方在中途变为未入驻后，对既有合约的六类写动作（提案/确认/签署/拒签终止/协商终止/
     * 合意解除）一律 fail-closed（1008C0003 合约操作语境文案）+ 拒绝留痕（尾号 C0003），
     * 且**不得产生任何状态转移**（合约态、版本指针、签署数均不变）。
     */
    @Test
    void t2_dealAdmissionFailClosedAcrossAllSixWriteContexts() throws Exception {
        // 先在各真实状态上建好载体（此时需求方为已入驻）
        final String negotiating = initiateOk(unique("资格-协商"));
        final String pending = toPending(unique("资格-待签"));
        final String partially = initiateOk(unique("资格-部分签"));
        assertThat(confirm(partially, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(partially, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(sign(partially, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        final String effective = toEffective(unique("资格-已生效"));
        // 需求方资格失效
        given(subjectAdmissionPort.check(REQ)).willReturn(SubjectAdmission.NOT_ADMITTED);
        // ① 提案（协商中）
        assertDealAdmissionRejected(propose(negotiating, REQ, slotsJson(unique("资格-V2"))));
        // ② 确认（协商中；同伴已确认版本存在时同拒）
        assertDealAdmissionRejected(confirm(negotiating, REQ));
        // ③ 签署（部分签署态：本方未签）
        assertDealAdmissionRejected(sign(partially, REQ, REQUESTER_DID));
        // ④ 拒签终止（待签署态）
        assertDealAdmissionRejected(mockMvc.perform(post(
                        BASE + "/" + pending + "/signature-refusals")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn());
        // ⑤ 协商终止（协商中）
        assertDealAdmissionRejected(mockMvc.perform(post(
                        BASE + "/" + negotiating + "/negotiation-terminations")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn());
        // ⑥ 合意解除（已生效）
        assertDealAdmissionRejected(mockMvc.perform(post(
                        BASE + "/" + effective + "/release-consents")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn());
        // 零状态转移：三份载体状态与版本指针不变、签署行数不变
        assertThat(statusOf(negotiating)).isEqualTo("NEGOTIATING");
        assertThat(currentVersion(negotiating)).isEqualTo(1);
        assertThat(statusOf(pending)).isEqualTo("PENDING_SIGNATURE");
        assertThat(statusOf(partially)).isEqualTo("PARTIALLY_SIGNED");
        assertThat(signCount(partially)).isEqualTo(1);
        assertThat(statusOf(effective)).isEqualTo("EFFECTIVE");
        assertThat(signCount(effective)).isEqualTo(2);
        // 六次拒绝均留痕（reason 尾号 C0003）；六语境合约均已定位——contract_no 必传
        // （DDL"定位前拒绝/不存在场景为 NULL"语义收口，评审循环 2 勘正）
        final Integer denied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE action = 'DENIED_ACCESS' "
                        + "AND reason_code = 'C0003'", Integer.class);
        assertThat(denied).isGreaterThanOrEqualTo(6);
        final Integer located = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE action = 'DENIED_ACCESS' "
                        + "AND reason_code = 'C0003' AND actor_subject_no = ? "
                        + "AND contract_no IS NOT NULL", Integer.class, REQ);
        assertThat(located).isGreaterThanOrEqualTo(6);
    }

    /**
     * 剧本 C-4.1 S2-5 / C-4.2 S3-6：产品下架后既有已生效合约效力不变、零联动（hifi §5.1
     * "下架不联动——既有合约零副作用路径"）——以需求方视角回读合约仍为已生效；且整个"下架"过程
     * 合约服务**不产生任何目录写调用**（无产品状态回写路径——结构性成立；零目录交互腿 =
     * 端到端锚 + 回归哨兵〔读面零 catalog 引用〕——评审循环 2 定位改述）。
     */
    @Test
    void t3_delistedProductDoesNotAffectEffectiveContractAndTriggersNoCatalogWrite()
            throws Exception {
        final String contractNo = toEffective(unique("下架不联动"));
        final JsonNode before = readBody(getDetail(contractNo, REQ)).path("data");
        assertThat(before.path("status").asText()).isEqualTo("EFFECTIVE");
        final String hashBefore = hashOf(contractNo, 1);
        // 产品在目录侧下架（合约服务视角的目录事实变更）
        given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                CatalogProductPort.CatalogProductResult.found(
                        new CatalogProductPort.CatalogProduct(PRODUCT_ID, "城市餐饮单位经营数据集",
                                "DELISTED", PROV, "PER_CALL", new BigDecimal("1.50"))));
        clearInvocations(catalogProductPort);
        // 既有合约效力不变（状态与锁定哈希逐字不变）
        final JsonNode after = readBody(getDetail(contractNo, REQ)).path("data");
        assertThat(after.path("status").asText()).isEqualTo("EFFECTIVE");
        assertThat(after.path("effectiveAt").asText())
                .isEqualTo(before.path("effectiveAt").asText());
        assertThat(hashOf(contractNo, 1)).isEqualTo(hashBefore);
        // 零目录写调用（合约域无产品状态回写路径）：下架事实不影响既有合约的任何读写副作用
        then(catalogProductPort).shouldHaveNoInteractions();
        // 下架后新发起被拒（同码同文防枚举）——门槛侧与效力侧同一事实
        final MvcResult newInitiation = initiate(REQ, initiateBody(unique("下架后发起")));
        assertThat(newInitiation.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(newInitiation)).isEqualTo("1008C0010");
    }

    /**
     * C-4.3 S1-2/S1-3/S1-4 策略双向锚（② 合规侧）：**五要素同时启用**的完整策略可发起 → 可确认
     * → 可锁定 → 可签署生效，且策略全文双方可查（生效侧）；对照组"违规策略被拒"由
     * {@link #t8_invalidPolicyBasicValuesRejectedOnSubmit()} 与
     * {@code t8_confirmWithoutPolicyOrDeclarationRejected}（确认时门槛）承载。
     */
    @Test
    void t8_allFivePolicyElementsEnabledCanLockAndBeVisibleToBothParties() throws Exception {
        final String contractNo = initiateOk(unique("五要素全启用"), ALL_ELEMENTS_STRATEGY);
        // 生效侧①：双方确认齐 → 锁定转待签署
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        final MvcResult lock = confirm(contractNo, PROV);
        assertThat(lock.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(lock).path("data").path("status").asText())
                .isEqualTo("PENDING_SIGNATURE");
        // 生效侧②：策略全文双方可查（剧本 S1-2 策略视图承载——五要素逐项出站）
        final JsonNode strategy = readBody(getDetail(contractNo, REQ)).path("data")
                .path("clauseValues").path("strategy");
        assertThat(strategy.path("quota").path("enabled").asBoolean()).isTrue();
        assertThat(strategy.path("quota").path("maxCount").asInt()).isEqualTo(3);
        assertThat(strategy.path("term").path("startDate").asText()).isEqualTo("2027-01-01");
        assertThat(strategy.path("term").path("endDate").asText()).isEqualTo("2027-12-31");
        assertThat(strategy.path("purpose").path("text").asText()).contains("政策研究");
        assertThat(strategy.path("territory").path("text").asText()).contains("境内");
        assertThat(strategy.path("noRedistribution").path("enabled").asBoolean()).isTrue();
        assertThat(readBody(getDetail(contractNo, PROV)).path("data").path("clauseValues")
                .path("strategy").path("quota").path("maxCount").asInt()).isEqualTo(3);
        // 生效侧③：可签署生效（策略不影响签署链路）
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(sign(contractNo, REQ, REQUESTER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(hashOf(contractNo, 1)).isNotNull();
    }

    /** C-4.3 S1-3 策略双向锚（① 违规侧）：基础取值非法（次数非正 / 期限倒置 / 要素文本空）→ 提交即拒 C0015。 */
    @Test
    void t8_invalidPolicyBasicValuesRejectedOnSubmit() throws Exception {
        // 违规侧零落库：以拒绝前后合约行数不变断言（原断言为共享库下恒真——评审循环 2 勘正）
        final Integer contractsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?", Integer.class, REQ);
        // 次数非正整数
        assertThat(codeOf(initiate(REQ, initiateBody(unique("次数非法"), TEMPLATE_NO, 1,
                "{\"quota\":{\"enabled\":true,\"maxCount\":0},\"noRestrictionDeclared\":false}"))))
                .isEqualTo("1008C0015");
        // 期限倒置
        assertThat(codeOf(initiate(REQ, initiateBody(unique("期限倒置"), TEMPLATE_NO, 1,
                termStrategy("2028-01-01", "2027-01-01"))))).isEqualTo("1008C0015");
        // 启用要素文本空
        assertThat(codeOf(initiate(REQ, initiateBody(unique("用途空文本"), TEMPLATE_NO, 1,
                "{\"purpose\":{\"enabled\":true,\"text\":\"  \"},"
                        + "\"noRestrictionDeclared\":false}")))).isEqualTo("1008C0015");
        // 违规侧无副作用：三次拒绝前后合约行数不变（未产生任何合约行）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?", Integer.class, REQ))
                .isEqualTo(contractsBefore);
    }

    /**
     * C-4.3 写路径校验增强集成锚（WBS-3.4.4 hifi §6 集成锚一）：四类新增拒绝面（R3 版本门槛 /
     * R7 互斥 / R5 时点 / R2 目录封闭集）在**发起路径**（W5）即拒 1008C0015——响应仅常量文案，
     * 明细入服务端日志（沿 C0004/C0015 先例）；违规侧零副作用（前后合约行数不变）。
     */
    @Test
    void t9_dslEnhancedViolationsRejectedOnInitiate() throws Exception {
        final Integer contractsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?", Integer.class, REQ);
        // R3 未知版本 fail-closed（"2.0" 未支持）
        assertThat(codeOf(initiate(REQ, initiateBody(unique("未知版本"), TEMPLATE_NO, 1,
                "{\"dslVersion\":\"2.0\",\"quota\":{\"enabled\":true,\"maxCount\":3},"
                        + "\"noRestrictionDeclared\":false}")))).isEqualTo("1008C0015");
        // R7 互斥："无使用限制"声明与启用要素并存 = 语义矛盾
        assertThat(codeOf(initiate(REQ, initiateBody(unique("互斥矛盾"), TEMPLATE_NO, 1,
                "{\"quota\":{\"enabled\":true,\"maxCount\":3},\"noRestrictionDeclared\":true}"))))
                .isEqualTo("1008C0015");
        // R5 时点：期限起始早于提交日（规格"期限早于生效日"提交时点可判定形态）
        assertThat(codeOf(initiate(REQ, initiateBody(unique("过期期限"), TEMPLATE_NO, 1,
                termStrategy("2020-01-01", "2027-01-01"))))).isEqualTo("1008C0015");
        // R2 未知要素字段名（目录封闭集）
        assertThat(codeOf(initiate(REQ, initiateBody(unique("未知要素"), TEMPLATE_NO, 1,
                "{\"unlimited\":{\"enabled\":true},\"noRestrictionDeclared\":false}"))))
                .isEqualTo("1008C0015");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?", Integer.class, REQ))
                .isEqualTo(contractsBefore);
    }

    /**
     * 写路径校验增强集成锚（hifi §6 集成锚二）：**提案路径**（W6/W7 链路）同样单点全查——
     * 互斥矛盾与未知版本提案被拒 C0015，且版本行不增（违规侧零落库）。
     */
    @Test
    void t9_dslEnhancedViolationsRejectedOnPropose() throws Exception {
        final String contractNo = initiateOk(unique("提案校验"),
                "{\"quota\":{\"enabled\":true,\"maxCount\":3},\"noRestrictionDeclared\":false}");
        final MvcResult mutex = propose(contractNo, PROV, clauseValuesJson(unique("提案互斥"),
                "{\"quota\":{\"enabled\":true,\"maxCount\":3},\"noRestrictionDeclared\":true}"));
        assertThat(mutex.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(mutex)).isEqualTo("1008C0015");
        assertThat(codeOf(propose(contractNo, PROV, clauseValuesJson(unique("提案版本"),
                "{\"dslVersion\":\"2.0\",\"quota\":{\"enabled\":true,\"maxCount\":3}}"))))
                .isEqualTo("1008C0015");
        assertThat(clauseVersions(contractNo, REQ)).hasSize(1);
    }

    /**
     * 存量兼容读探针（hifi §6 集成锚三）：显式携版本（"1.0"）的载荷提交后，存储形态收敛为
     * 无版本位的固定字段序文档——读路径缺省容忍 = 1.0（零迁移、零 DDL），QC1 快照与详情视图
     * 均可正常回读策略模型。
     */
    @Test
    void t9_versionedPayloadStoredVersionlessAndReadTolerantly() throws Exception {
        final String contractNo = initiateOk(unique("版本探针"),
                "{\"dslVersion\":\"1.0\",\"quota\":{\"enabled\":true,\"maxCount\":3},"
                        + "\"noRestrictionDeclared\":false}");
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(sign(contractNo, REQ, REQUESTER_DID).getResponse().getStatus()).isEqualTo(201);
        final var snapshot = queryService.loadEffectiveStrategy(contractNo);
        assertThat(snapshot.status()).isEqualTo(ContractStatus.EFFECTIVE.name());
        assertThat(snapshot.strategy().quota().enabled()).isTrue();
        assertThat(snapshot.strategy().quota().maxCount()).isEqualTo(3);
        // 存量形态收敛：存储文档无版本位（读路径缺省 = "1.0"）
        assertThat(readBody(getDetail(contractNo, REQ)).path("data").path("clauseValues")
                .path("strategy").has("dslVersion")).isFalse();
    }

    /**
     * trim 落库探针（hifi §6 集成锚四）：文本要素首尾空白在**写入时**即规范化——回读值与
     * 判定值同源（防 3.4.5 引擎按"␣风控建模␣"漏拦）。
     */
    @Test
    void t9_paddedTextElementsNormalizedBeforePersistence() throws Exception {
        final String contractNo = initiateOk(unique("trim探针"),
                "{\"purpose\":{\"enabled\":true,\"text\":\"  风控建模  \"},"
                        + "\"territory\":{\"enabled\":true,\"text\":\" 本市域 \"},"
                        + "\"noRestrictionDeclared\":false}");
        final JsonNode strategy = readBody(getDetail(contractNo, REQ)).path("data")
                .path("clauseValues").path("strategy");
        assertThat(strategy.path("purpose").path("text").asText()).isEqualTo("风控建模");
        assertThat(strategy.path("territory").path("text").asText()).isEqualTo("本市域");
    }

    // ==== 支撑（请求/断言助手）====

    /** 资格失效断言（Q8-A：合约操作语境 C0003 + 留痕尾号 C0003）。 */
    private void assertDealAdmissionRejected(final MvcResult result) {
        assertThat(result.getResponse().getStatus()).as("资格失效应答: %s", readBody(result))
                .isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1008C0003");
    }

    /** 合约现态（读库）。 */
    private String statusOf(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM contract WHERE contract_no = ?", String.class, contractNo);
    }

    /** 指定版本的提供方确认时间（未确认 = null）。 */
    private LocalDateTime confirmedProviderAt(final String contractNo, final int versionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT confirmed_provider_at FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?) AND version_no = ?",
                (rs, rowNum) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime(), contractNo, versionNo);
    }

    /** 指定版本的需求方确认时间（未确认 = null）。 */
    private LocalDateTime confirmedRequesterAt(final String contractNo, final int versionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT confirmed_requester_at FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?) AND version_no = ?",
                (rs, rowNum) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime(), contractNo, versionNo);
    }

    /** 指定版本的规范化原文密文（未锁定 = null）。 */
    private byte[] canonicalCipher(final String contractNo, final int versionNo) {
        return versionCipher(contractNo, versionNo, "canonical_cipher");
    }

    /** 指定版本的内容哈希（未锁定 = null）。 */
    private String hashOf(final String contractNo, final int versionNo) {
        return jdbcTemplate.queryForObject(
                "SELECT content_hash FROM contract_clause_version WHERE contract_id = "
                        + "(SELECT id FROM contract WHERE contract_no = ?) AND version_no = ?",
                String.class, contractNo, versionNo);
    }

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

    /** 当前版本（指针所指）的内容哈希；读面/存证对照用。 */
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
