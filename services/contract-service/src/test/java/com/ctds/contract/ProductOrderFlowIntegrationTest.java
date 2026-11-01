package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.DealTextCipher;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 订购与订单流程集成测试（WBS-3.7.3 hifi §6.1 T1~T11 核心锚；规格 B3/B4/B6 + B12-1；
 * C-7.1 剧本 S2-1~6 + 13 判定点。T12 迁移结构归 ContractMigrationIntegrationTest，T14
 * 网关归 GatewayConnectorRoutesIntegrationTest；T11 分译回查命中腿归
 * OrderCommandServiceFallbackTranslateTest 单测）。真实 MySQL 8 容器实跑 Flyway V1~V4
 * （V4 三订单表）；资格/产品/DID 三端口 {@code @MockitoBean} 沿合约协商生命周期测试先例；
 * L3 密钥源 = 临时密钥文件注入（密钥零入库）。本机 Docker 未运行时整类跳过（门禁不红）。
 *
 * <p>订单创建复用合约发起门槛链（ADMITTED / 在架 / 异主体 / 模板 / 条款），本类只钉
 * 订单域自身行为：订单+合约草案同事务落库、双重幂等（requestId + 进行中唯一）、状态
 * 联动（生效→已完成 / 生效前终止→已关闭、生效后强制终止不动）、读面双角色与过滤分页、
 * 运营检索与 403、防枚举同码同文 + 拒绝留痕 + 读面纵深过滤、参数校验（含分页沿 1000C0001）。</p>
 *
 * <p>各用例条款值带唯一标记（subject_matter 值内嵌）——与既有测试用例零串扰（沿先例）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ProductOrderFlowIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/orders";
    private static final String CONTRACT_BASE = "/api/v1/contracts";
    private static final String REQ = "S-req";
    private static final String PROV = "S-prov";
    private static final String OTHER = "S-other";
    /** 治理读面调用者（admin 档——T8 检索与生效后强制终止用）。 */
    private static final String ADMIN = "S-admin";
    private static final long PRODUCT_ID = 12L;
    /** 第二产品（T7/T8 双订单用——B3-2 同需方同产品进行中唯一，双单必须异产品）。 */
    private static final long PRODUCT_ID_2 = 13L;
    private static final String PROVIDER_DID = "did:ctds:" + PROV + ".1";
    private static final String REQUESTER_DID = "did:ctds:" + REQ + ".1";
    /** CT000001 公共数据授权模板（V1 迁移种子，9 必填 + 1 选填槽位）。 */
    private static final String TEMPLATE_NO = "CT000001";
    private static final String DEFAULT_STRATEGY = "{\"noRestrictionDeclared\":true}";
    /** 演示签名桩返回的 SM2 签名（沿合约协商生命周期测试先例）。 */
    private static final String PROVIDER_SIGNATURE = "c2lnLXByb3ZpZGVyLXN0dWI=";

    private static final AtomicInteger SEQ = new AtomicInteger(1000);
    private static Path keyFile;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_order_it");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        // 16 字节测试密钥（仅测试用；文件命名 *.keys——.gitignore 拦截口径，密钥零入库）
        keyFile = Files.createTempFile("ctds-test-order-keys", ".keys");
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
    @MockitoBean
    private SubjectAdmissionPort subjectAdmissionPort;
    @MockitoBean
    private CatalogProductPort catalogProductPort;
    @MockitoBean
    private DidPort didPort;

    /**
     * 共享库状态清理（沿"集成测试共享上下文须 @BeforeEach 清状态"先例）：订单域断言用
     * 固定主体/产品组合（B3-2 进行中唯一——预检查与 uk_active_order_key 均对残留行敏感），
     * 故每用例前清订单域与合约域数据行（序号表保留——编号全局递增语义，不回退）。
     */
    @BeforeEach
    void cleanState() {
        for (final String table : List.of("product_order_action_log", "product_order",
                "contract_action_log", "contract_signature", "contract_attestation",
                "contract_clause_version", "contract")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
    }

    @BeforeEach
    void stubPorts() {
        for (final String subject : List.of(REQ, PROV, OTHER)) {
            given(subjectAdmissionPort.check(subject)).willReturn(SubjectAdmission.ADMITTED);
        }
        given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                CatalogProductPort.CatalogProductResult.found(
                        new CatalogProductPort.CatalogProduct(PRODUCT_ID, "城市餐饮单位经营数据集",
                                "LISTED", PROV, "PER_CALL", new BigDecimal("1.50"))));
        given(catalogProductPort.fetch(PRODUCT_ID_2)).willReturn(
                CatalogProductPort.CatalogProductResult.found(
                        new CatalogProductPort.CatalogProduct(PRODUCT_ID_2, "企业信用画像数据集",
                                "LISTED", PROV, "PER_CALL", new BigDecimal("2.00"))));
        given(didPort.resolve(PROVIDER_DID)).willReturn(DidPort.DidBinding.found(PROV, "ACTIVE"));
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.found(REQ, "ACTIVE"));
        given(didPort.sign(eq(PROVIDER_DID), anyString())).willReturn(
                DidPort.DidSignResult.signed(PROVIDER_SIGNATURE));
        given(didPort.sign(eq(REQUESTER_DID), anyString())).willReturn(
                DidPort.DidSignResult.signed("c2lnLXJlcXVlc3Rlci1zdHVi"));
        given(didPort.verify(anyString(), anyString(), anyString())).willReturn(
                DidPort.DidVerifyResult.of(DidPort.DidVerifyResult.Outcome.PASS, null));
    }

    // ==== T1 创建订单：订单 + 合约草案同事务；四要素与定价快照同源（B3-1/B3-3；S2-1/S2-3）====

    @Test
    void t1_createOrderWritesOrderAndContractDraftAtomically() throws Exception {
        final JsonNode data = createOrderOk(unique("订购成功")).path("data");
        // 订单四要素与状态（B3-1）
        assertThat(data.path("orderNo").asText()).matches("OD\\d{6}");
        assertThat(data.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(data.path("requesterSubjectNo").asText()).isEqualTo(REQ);
        assertThat(data.path("productId").asLong()).isEqualTo(PRODUCT_ID);
        // 定价快照与合约快照同源（B3-3：订单衔接合约草案、定价口径一致；hifi §1.4 priceAmount = toPlainString 字符串）
        assertThat(data.path("pricingModel").asText()).isEqualTo("PER_CALL");
        assertThat(data.path("priceAmount").asText()).isEqualTo("1.50");
        assertThat(data.path("productName").asText()).isEqualTo("城市餐饮单位经营数据集");
        assertThat(data.path("providerSubjectNo").asText()).isEqualTo(PROV);
        assertThat(data.path("templateNo").asText()).isEqualTo(TEMPLATE_NO);
        assertThat(data.path("templateVersionNo").asInt()).isEqualTo(1);
        // 衔接合约草案（同事务落库：NEGOTIATING）
        assertThat(data.path("contractNo").asText()).matches("CO\\d{6}");
        assertThat(data.path("contractStatus").asText()).isEqualTo("NEGOTIATING");
        final String orderNo = data.path("orderNo").asText();
        final String contractNo = data.path("contractNo").asText();
        // DB 断言：订单行快照逐项一致 + 订单留痕 CREATE
        final Map<String, Object> orderRow = jdbcTemplate.queryForMap(
                "SELECT product_id, product_name, provider_subject_no, requester_subject_no, "
                        + "pricing_model, price_amount, template_no, template_version_no, "
                        + "contract_no, status, created_by FROM product_order WHERE order_no = ?",
                orderNo);
        assertThat(orderRow.get("product_id")).isEqualTo(PRODUCT_ID);
        assertThat(orderRow.get("product_name")).isEqualTo("城市餐饮单位经营数据集");
        assertThat(orderRow.get("provider_subject_no")).isEqualTo(PROV);
        assertThat(orderRow.get("requester_subject_no")).isEqualTo(REQ);
        assertThat(orderRow.get("pricing_model")).isEqualTo("PER_CALL");
        assertThat(orderRow.get("price_amount")).isEqualTo(new BigDecimal("1.50"));
        assertThat(orderRow.get("template_no")).isEqualTo(TEMPLATE_NO);
        assertThat(orderRow.get("template_version_no")).isEqualTo(1);
        assertThat(orderRow.get("contract_no")).isEqualTo(contractNo);
        assertThat(orderRow.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(orderRow.get("created_by")).isEqualTo(REQ);
        final Map<String, Object> orderLog = jdbcTemplate.queryForMap(
                "SELECT action, from_status, to_status, operator_subject_no "
                        + "FROM product_order_action_log WHERE order_no = ?", orderNo);
        assertThat(orderLog.get("action")).isEqualTo("CREATE");
        assertThat(orderLog.get("from_status")).isNull();
        assertThat(orderLog.get("to_status")).isEqualTo("IN_PROGRESS");
        assertThat(orderLog.get("operator_subject_no")).isEqualTo(REQ);
        // 合约草案真实存在（同事务——订单详情经 contractNo 可见）
        final Integer contractRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE contract_no = ?", Integer.class, contractNo);
        assertThat(contractRows).isEqualTo(1);
        // 合约行快照与订单行逐项一致（B3-3 定价口径一致：同源构造的 DB 面实证）
        final Map<String, Object> contractRow = jdbcTemplate.queryForMap(
                "SELECT product_id, product_name, provider_subject_no, requester_subject_no, "
                        + "pricing_model, price_amount FROM contract WHERE contract_no = ?",
                contractNo);
        assertThat(contractRow.get("product_id")).isEqualTo(orderRow.get("product_id"));
        assertThat(contractRow.get("product_name")).isEqualTo(orderRow.get("product_name"));
        assertThat(contractRow.get("provider_subject_no"))
                .isEqualTo(orderRow.get("provider_subject_no"));
        assertThat(contractRow.get("requester_subject_no"))
                .isEqualTo(orderRow.get("requester_subject_no"));
        assertThat(contractRow.get("pricing_model")).isEqualTo(orderRow.get("pricing_model"));
        assertThat(contractRow.get("price_amount")).isEqualTo(orderRow.get("price_amount"));
    }

    // ==== T2 重复订购：同需方同产品进行中唯一（B3-2；S2-2 负向）====

    @Test
    void t2_duplicateActiveOrderRejected1011C0001() throws Exception {
        createOrderOk(unique("首单"));
        final MvcResult duplicate = createOrder(REQ, orderBody(unique("重复订购")));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(duplicate)).isEqualTo("1011C0001");
        assertThat(readBody(duplicate).path("message").asText())
                .isEqualTo("该产品已有进行中订单，请勿重复订购");
        // 订单仍一份、合约不新增
        assertThat(orderCount(REQ)).isEqualTo(1);
        assertThat(contractCount(REQ)).isEqualTo(1);
    }

    // ==== T3 幂等重放：同 requestId 返回同一订单（B3-2；Q6-A）====

    @Test
    void t3_idempotentReplaySameRequestIdReturnsSameOrder() throws Exception {
        final String requestId = "req-" + unique("幂等");
        final JsonNode first = createOrderOk(REQ, orderBody(unique("幂等首放"), requestId))
                .path("data");
        final MvcResult replay = createOrder(REQ, orderBody(unique("幂等重放"), requestId));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(readBody(replay).path("data").path("orderNo").asText())
                .isEqualTo(first.path("orderNo").asText());
        assertThat(orderCount(REQ)).isEqualTo(1);
        assertThat(contractCount(REQ)).isEqualTo(1);
    }

    // ==== T4 关闭后重购：新 requestId 生成新订单 + 新合约（Q6-A；S2-5→重购）====

    @Test
    void t4_rebuyAfterCloseCreatesNewOrderAndContract() throws Exception {
        // 同内容重购（hifi T4 原文"同内容订购"：同条款值 + 新 requestId——订单专用通路
        // 不走 W5 内容幂等，关闭后同内容可重购且不复用已终止合约——Q6-A 实证锚）
        final String marker = unique("重购");
        final JsonNode first = createOrderOk(REQ, orderBody(marker, "req-first-" + marker))
                .path("data");
        final String contractNo = first.path("contractNo").asText();
        // 协商终止 → 订单关闭（联动断言详见 t6）
        assertThat(negotiationTerminate(contractNo).getResponse().getStatus()).isEqualTo(200);
        final String firstOrderNo = first.path("orderNo").asText();
        assertThat(orderStatus(firstOrderNo)).isEqualTo("CLOSED");
        // 新 requestId 重购：新订单 + 新合约
        final JsonNode rebuy = createOrderOk(REQ, orderBody(marker, "req-rebuy-" + marker))
                .path("data");
        assertThat(rebuy.path("orderNo").asText()).isNotEqualTo(firstOrderNo);
        assertThat(rebuy.path("contractNo").asText()).isNotEqualTo(contractNo);
        assertThat(rebuy.path("contractStatus").asText()).isEqualTo("NEGOTIATING");
        assertThat(orderCount(REQ)).isEqualTo(2);
    }

    // ==== T5 双签生效：订单 IN_PROGRESS→COMPLETED + COMPLETE 留痕；首签不动订单（B3-4；S2-4）====

    @Test
    void t5_effectiveContractCompletesOrderAndFirstSignKeeps() throws Exception {
        final JsonNode data = createOrderOk(unique("生效联动")).path("data");
        final String orderNo = data.path("orderNo").asText();
        final String contractNo = data.path("contractNo").asText();
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        // 首签（提供方 FIRST_SIGNED）：订单保持进行中
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(orderStatus(orderNo)).isEqualTo("IN_PROGRESS");
        // 双签生效：订单完成
        assertThat(sign(contractNo, REQ, REQUESTER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(orderStatus(orderNo)).isEqualTo("COMPLETED");
        final Map<String, Object> completeLog = jdbcTemplate.queryForMap(
                "SELECT action, from_status, to_status FROM product_order_action_log "
                        + "WHERE order_no = ? AND action = 'COMPLETE'", orderNo);
        assertThat(completeLog.get("from_status")).isEqualTo("IN_PROGRESS");
        assertThat(completeLog.get("to_status")).isEqualTo("COMPLETED");
    }

    // ==== T6 协商终止：订单 CLOSED + 关闭原因 + CLOSE 留痕（B3-4；S2-5 负向腿）====

    @Test
    void t6_negotiationTerminationClosesOrderWithReason() throws Exception {
        final JsonNode data = createOrderOk(unique("终止联动")).path("data");
        final String orderNo = data.path("orderNo").asText();
        assertThat(negotiationTerminate(data.path("contractNo").asText())
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(orderNo)).isEqualTo("CLOSED");
        final Map<String, Object> orderRow = jdbcTemplate.queryForMap(
                "SELECT closed_reason, closed_at, status FROM product_order WHERE order_no = ?",
                orderNo);
        assertThat(orderRow.get("closed_reason")).isEqualTo("合约协商终止");
        assertThat(orderRow.get("closed_at")).isNotNull();
        assertThat(orderRow.get("status")).isEqualTo("CLOSED");
        final Map<String, Object> closeLog = jdbcTemplate.queryForMap(
                "SELECT action, to_status, reason FROM product_order_action_log "
                        + "WHERE order_no = ? AND action = 'CLOSE'", orderNo);
        assertThat(closeLog.get("to_status")).isEqualTo("CLOSED");
        assertThat(closeLog.get("reason")).isEqualTo("合约协商终止");
    }

    // ==== T9 防枚举：非本方与不存在同码同文 + 拒绝留痕（B4-4；S2-6）====

    @Test
    void t9_nonPartyAndMissingOrderSameCodeSameTextWithDeniedLog() throws Exception {
        final String orderNo = createOrderOk(unique("归属")).path("data").path("orderNo").asText();
        // 非本方（第三方）与不存在同码同文（防枚举）
        final MvcResult forbidden = getOrder(OTHER, orderNo);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(forbidden)).isEqualTo("1011C0002");
        assertThat(readBody(forbidden).path("message").asText()).isEqualTo("订单不存在或无权查看");
        final MvcResult missing = getOrder(OTHER, "OD999999");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(missing)).isEqualTo("1011C0002");
        assertThat(readBody(missing).path("message").asText()).isEqualTo("订单不存在或无权查看");
        // 越权访问产生拒绝留痕；不存在不伪造订单行
        final Integer deniedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_order_action_log WHERE order_no = ? "
                        + "AND action = 'DENIED_ACCESS'", Integer.class, orderNo);
        assertThat(deniedRows).isEqualTo(1);
        // 拒绝留痕零数据本体（reason 不含条款值/合同原文）
        final Map<String, Object> deniedLog = jdbcTemplate.queryForMap(
                "SELECT reason, operator_subject_no FROM product_order_action_log "
                        + "WHERE order_no = ? AND action = 'DENIED_ACCESS'", orderNo);
        assertThat(deniedLog.get("operator_subject_no")).isEqualTo(OTHER);
        assertThat(String.valueOf(deniedLog.get("reason"))).doesNotContain("subject_matter");
        // 需方本方可见（对照组：同一订单需方读取 200）+ 读面时间线纵深过滤：
        // DENIED_ACCESS 留痕不进用户读面（hifi §1.4 注——服务端已滤，读面再钉一道）
        final JsonNode ownerDetail = readBody(getOrder(REQ, orderNo));
        assertThat(ownerDetail.path("data").path("actions").size()).isEqualTo(1);
        assertThat(ownerDetail.path("data").path("actions").get(0).path("action").asText())
                .isEqualTo("CREATE");
    }

    // ==== T10 参数校验：requestId 缺失 / 空白 / 超长 → 1011C0003（零业务请求）====

    @Test
    void t10_invalidRequestIdRejectedC0003() throws Exception {
        final String marker = unique("参数校验");
        final String slots = slotsJson(marker);
        // 缺失 requestId
        final MvcResult missing = createOrder(REQ, "{\"productId\":" + PRODUCT_ID
                + ",\"templateNo\":\"" + TEMPLATE_NO + "\",\"templateVersionNo\":1"
                + ",\"clauseValues\":" + slots + "}");
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(missing)).isEqualTo("1011C0003");
        // 空白 requestId
        final MvcResult blank = createOrder(REQ, orderBody(marker, "   "));
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(blank)).isEqualTo("1011C0003");
        // 超长 requestId（>64）
        final MvcResult tooLong = createOrder(REQ, orderBody(marker, "r".repeat(65)));
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(tooLong)).isEqualTo("1011C0003");
        // 零业务请求：无订单、无合约落库
        assertThat(orderCount(REQ)).isEqualTo(0);
        assertThat(contractCount(REQ)).isEqualTo(0);
        // 读面参数校验腿：非法 status / productId → 1011C0003（400）
        final MvcResult badStatus = mockMvc.perform(get(BASE + "/mine?status=NOT_A_STATUS")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn();
        assertThat(badStatus.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badStatus)).isEqualTo("1011C0003");
        final MvcResult badProduct = mockMvc.perform(get(BASE + "/mine?productId=0")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn();
        assertThat(badProduct.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badProduct)).isEqualTo("1011C0003");
        // 分页越界沿 common 1000C0001（hifi §8 勘误 E6：分页不落 1011 段）
        final MvcResult badPage = mockMvc.perform(get(BASE + "/mine?pageNum=10001&pageSize=20")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")).andReturn();
        assertThat(badPage.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badPage)).isEqualTo("1000C0001");
    }

    // ==== T6 负向腿：拒签终止 → 订单关闭（B3-4 生效前终止第二类；S2-5）====

    @Test
    void t6b_signatureRefusalClosesOrderWithReason() throws Exception {
        final JsonNode data = createOrderOk(unique("拒签联动")).path("data");
        final String orderNo = data.path("orderNo").asText();
        final String contractNo = data.path("contractNo").asText();
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        // 待签署态拒签 → 合约 SIGNATURE_REFUSED 终止 → 订单关闭（同事务联动）
        final MvcResult refused = mockMvc.perform(post(
                        CONTRACT_BASE + "/" + contractNo + "/signature-refusals")
                .header("X-Ctds-Subject", PROV).header("X-Ctds-Roles", "provider")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(refused).path("data").path("terminationType").asText())
                .isEqualTo("SIGNATURE_REFUSED");
        assertThat(orderStatus(orderNo)).isEqualTo("CLOSED");
        final Map<String, Object> orderRow = jdbcTemplate.queryForMap(
                "SELECT closed_reason, closed_at, status FROM product_order WHERE order_no = ?",
                orderNo);
        assertThat(orderRow.get("closed_reason")).isEqualTo("合约拒签终止");
        assertThat(orderRow.get("closed_at")).isNotNull();
        assertThat(orderRow.get("status")).isEqualTo("CLOSED");
        final Map<String, Object> closeLog = jdbcTemplate.queryForMap(
                "SELECT to_status, reason FROM product_order_action_log "
                        + "WHERE order_no = ? AND action = 'CLOSE'", orderNo);
        assertThat(closeLog.get("to_status")).isEqualTo("CLOSED");
        assertThat(closeLog.get("reason")).isEqualTo("合约拒签终止");
    }

    // ==== T6 边界：合约生效后强制终止不改订单状态（Q7-A：条件更新仅命中 IN_PROGRESS）====

    @Test
    void t6c_forceTerminateAfterEffectiveKeepsOrderCompleted() throws Exception {
        final JsonNode data = createOrderOk(unique("生效后强制终止")).path("data");
        final String orderNo = data.path("orderNo").asText();
        final String contractNo = data.path("contractNo").asText();
        assertThat(confirm(contractNo, REQ).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirm(contractNo, PROV).getResponse().getStatus()).isEqualTo(200);
        assertThat(sign(contractNo, PROV, PROVIDER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(sign(contractNo, REQ, REQUESTER_DID).getResponse().getStatus()).isEqualTo(201);
        assertThat(orderStatus(orderNo)).isEqualTo("COMPLETED");
        // 生效后治理强制终止：合约改写终态，订单保持已完成（不扩 B3-4 两条联动之外）
        final MvcResult terminated = mockMvc.perform(post(
                        CONTRACT_BASE + "/governance/" + contractNo + "/force-termination")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"查实违规使用，依规强制终止\"}")).andReturn();
        assertThat(terminated.getResponse().getStatus()).isEqualTo(200);
        assertThat(orderStatus(orderNo)).isEqualTo("COMPLETED");
        // 零二次留痕：订单动作仍只有 CREATE + COMPLETE 两条（CLOSE 不产生）
        final Integer logRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_order_action_log WHERE order_no = ?",
                Integer.class, orderNo);
        assertThat(logRows).isEqualTo(2);
    }

    // ==== T7 mine 读面：双角色 myRole 标注 / 非本方不出现（B4-1 + B12-1；S2-13）====

    @Test
    void t7a_mineDualRolesAndNonPartyExcluded() throws Exception {
        final JsonNode data = createOrderOk(unique("读面双角色")).path("data");
        // 需方视角：可见 + myRole=REQUESTER + 快照与关联合约状态
        final JsonNode asRequester = readBody(listMine(REQ, "")).path("data");
        assertThat(asRequester.path("total").asInt()).isEqualTo(1);
        final JsonNode requesterRow = asRequester.path("list").get(0);
        assertThat(requesterRow.path("orderNo").asText())
                .isEqualTo(data.path("orderNo").asText());
        assertThat(requesterRow.path("myRole").asText()).isEqualTo("REQUESTER");
        assertThat(requesterRow.path("contractNo").asText())
                .isEqualTo(data.path("contractNo").asText());
        assertThat(requesterRow.path("contractStatus").asText()).isEqualTo("NEGOTIATING");
        assertThat(requesterRow.path("priceAmount").asText()).isEqualTo("1.50");
        // 供方视角：同一订单可见 + myRole=PROVIDER（B12-1 双角色读）
        final JsonNode asProvider = readBody(listMine(PROV, "")).path("data");
        assertThat(asProvider.path("total").asInt()).isEqualTo(1);
        assertThat(asProvider.path("list").get(0).path("myRole").asText()).isEqualTo("PROVIDER");
        // 非本方：服务端过滤为空集（非本方的行不出现——B4-1 归属边界）
        final JsonNode asOther = readBody(listMine(OTHER, "")).path("data");
        assertThat(asOther.path("total").asInt()).isEqualTo(0);
        assertThat(asOther.path("list").size()).isEqualTo(0);
    }

    // ==== T7 mine 读面：状态/合约号过滤 + 分页（B4-1）====

    @Test
    void t7b_mineFiltersAndPagination() throws Exception {
        // 双单异产品（B3-2 同需方同产品进行中唯一）：甲单保持进行中、乙单协商终止关闭
        final JsonNode first = createOrderOk(REQ,
                orderBody(unique("过滤甲"), "req-" + unique("过滤甲"), PRODUCT_ID)).path("data");
        final JsonNode second = createOrderOk(REQ,
                orderBody(unique("过滤乙"), "req-" + unique("过滤乙"), PRODUCT_ID_2)).path("data");
        assertThat(negotiationTerminate(second.path("contractNo").asText())
                .getResponse().getStatus()).isEqualTo(200);
        // 状态过滤：IN_PROGRESS / CLOSED 各命中一行
        final JsonNode inProgress = readBody(listMine(REQ, "?status=IN_PROGRESS")).path("data");
        assertThat(inProgress.path("total").asInt()).isEqualTo(1);
        assertThat(inProgress.path("list").get(0).path("orderNo").asText())
                .isEqualTo(first.path("orderNo").asText());
        final JsonNode closed = readBody(listMine(REQ, "?status=CLOSED")).path("data");
        assertThat(closed.path("total").asInt()).isEqualTo(1);
        assertThat(closed.path("list").get(0).path("orderNo").asText())
                .isEqualTo(second.path("orderNo").asText());
        // contractNo 过滤
        final JsonNode byContract = readBody(listMine(REQ,
                "?contractNo=" + first.path("contractNo").asText())).path("data");
        assertThat(byContract.path("total").asInt()).isEqualTo(1);
        // 分页：pageSize=1 两页各一条（created_at DESC + id DESC 稳定序），total 不随页变化
        final JsonNode page1 = readBody(listMine(REQ, "?pageNum=1&pageSize=1")).path("data");
        final JsonNode page2 = readBody(listMine(REQ, "?pageNum=2&pageSize=1")).path("data");
        assertThat(page1.path("total").asInt()).isEqualTo(2);
        assertThat(page2.path("total").asInt()).isEqualTo(2);
        assertThat(page1.path("list").size()).isEqualTo(1);
        assertThat(page2.path("list").size()).isEqualTo(1);
        assertThat(page1.path("list").get(0).path("orderNo").asText())
                .isNotEqualTo(page2.path("list").get(0).path("orderNo").asText());
    }

    // ==== T8 governance 读面：admin 全量检索 + 过滤（B4-2；S2-7 后端验证）====

    @Test
    void t8a_governanceAdminSearchWithFilters() throws Exception {
        // 双单异产品（B3-2 同需方同产品进行中唯一）
        final JsonNode first = createOrderOk(REQ,
                orderBody(unique("治理甲"), "req-" + unique("治理甲"), PRODUCT_ID)).path("data");
        createOrderOk(REQ, orderBody(unique("治理乙"), "req-" + unique("治理乙"),
                PRODUCT_ID_2)).path("data");
        // admin 全量检索（不做归属过滤）：requesterNo 过滤命中两行；myRole 不标注（运营读面
        // ——缺省或 null 均视为未标注）
        final JsonNode byRequester = readBody(governanceSearch(ADMIN, "admin",
                "?requesterNo=" + REQ)).path("data");
        assertThat(byRequester.path("total").asInt()).isEqualTo(2);
        final JsonNode govRow = byRequester.path("list").get(0);
        assertThat(govRow.path("myRole").isMissingNode() || govRow.path("myRole").isNull())
                .isTrue();
        // 状态过滤 + contractNo 过滤
        final JsonNode byStatus = readBody(governanceSearch(ADMIN, "admin",
                "?requesterNo=" + REQ + "&status=IN_PROGRESS")).path("data");
        assertThat(byStatus.path("total").asInt()).isEqualTo(2);
        final JsonNode byContract = readBody(governanceSearch(ADMIN, "admin",
                "?contractNo=" + first.path("contractNo").asText())).path("data");
        assertThat(byContract.path("total").asInt()).isEqualTo(1);
        assertThat(byContract.path("list").get(0).path("orderNo").asText())
                .isEqualTo(first.path("orderNo").asText());
    }

    // ==== T8 governance 读面：非 admin 403（contract.governance 仅 admin）====

    @Test
    void t8b_governanceForbiddenForNonAdmin() throws Exception {
        // 非 admin 调运营检索 → 注解层 403（1000C0005；沿留痕读面护栏口径）
        final MvcResult forbidden = governanceSearch(REQ, "provider", "");
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(forbidden)).isEqualTo("1000C0005");
    }

    // ==== T11 并发唯一兜底：uk_order_no 冲突 → 1011C0004 + 事务整体回滚（hifi §3.2）====

    @Test
    void t11_uniqueKeyFallbackTranslatesC0004AndRollsBack() throws Exception {
        // 直插一行占用下一个全局订单号（异需方/异产品——预检查不命中，落库撞 uk_order_no；
        // 序号表 UPDATE 返回旧值+1，故占用 next_no+1 的号位）
        final int nextNo = jdbcTemplate.queryForObject(
                "SELECT next_no FROM product_order_no_seq WHERE id = 1", Integer.class);
        jdbcTemplate.update("INSERT INTO product_order (order_no, product_id, product_name, "
                + "provider_subject_no, requester_subject_no, pricing_model, price_amount, "
                + "template_no, template_version_no, contract_no, status, created_by, "
                + "created_at, updated_at) VALUES (?, 999999, '订单号占位数据集', ?, 'S-blocker', "
                + "'PER_CALL', 1.00, ?, 1, 'COBLOCKED', 'IN_PROGRESS', 'S-blocker', NOW(), NOW())",
                String.format("OD%06d", nextNo + 1), PROV, TEMPLATE_NO);
        // 预检查通过 → 唯一索引兜底 → 回查无进行中订单 → 1011C0004（分译；回查命中腿由
        // OrderCommandServiceFallbackTranslateTest 单测承载——单线程集成面不可达）
        final MvcResult conflict = createOrder(REQ, orderBody(unique("并发兜底")));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(conflict)).isEqualTo("1011C0004");
        assertThat(readBody(conflict).path("message").asText()).isEqualTo("订单正在处理中，请重试");
        // 事务整体回滚：合约草案与订单零残留（hifi §3.2）
        assertThat(orderCount(REQ)).isEqualTo(0);
        assertThat(contractCount(REQ)).isEqualTo(0);
    }

    // ==== 辅助（沿合约协商生命周期测试先例）====

    private MvcResult createOrder(final String operator, final String body) throws Exception {
        return mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode createOrderOk(final String marker) throws Exception {
        return createOrderOk(REQ, orderBody(marker, "req-" + unique(marker)));
    }

    private JsonNode createOrderOk(final String operator, final String body) throws Exception {
        final MvcResult result = createOrder(operator, body);
        assertThat(result.getResponse().getStatus())
                .as("订购应成功: %s", readBody(result)).isEqualTo(201);
        return readBody(result);
    }

    private MvcResult confirm(final String contractNo, final String operator) throws Exception {
        return mockMvc.perform(post(CONTRACT_BASE + "/" + contractNo + "/confirmations")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                .andReturn();
    }

    private MvcResult sign(final String contractNo, final String operator, final String did)
            throws Exception {
        return mockMvc.perform(post(CONTRACT_BASE + "/" + contractNo + "/signatures")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"did\":\"" + did + "\"}")).andReturn();
    }

    private MvcResult negotiationTerminate(final String contractNo) throws Exception {
        return mockMvc.perform(post(
                        CONTRACT_BASE + "/" + contractNo + "/negotiation-terminations")
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider"))
                .andReturn();
    }

    private MvcResult getOrder(final String operator, final String orderNo) throws Exception {
        return mockMvc.perform(get(BASE + "/" + orderNo)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                .andReturn();
    }

    /** mine 读面分页（query 含或不含 ? 前缀均可——T7 过滤/分页腿）。 */
    private MvcResult listMine(final String operator, final String query) throws Exception {
        return mockMvc.perform(get(BASE + "/mine" + query)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "provider"))
                .andReturn();
    }

    /** governance 运营检索（roles 传 admin 或 provider——T8 检索与 403 腿）。 */
    private MvcResult governanceSearch(final String operator, final String roles,
            final String query) throws Exception {
        return mockMvc.perform(get(BASE + "/governance" + query)
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", roles)).andReturn();
    }

    private String orderStatus(final String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM product_order WHERE order_no = ?", String.class, orderNo);
    }

    private int orderCount(final String requester) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_order WHERE requester_subject_no = ?",
                Integer.class, requester);
    }

    private int contractCount(final String requester) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract WHERE requester_subject_no = ?",
                Integer.class, requester);
    }

    private static String orderBody(final String marker) {
        return orderBody(marker, "req-" + unique(marker));
    }

    private static String orderBody(final String marker, final String requestId) {
        return orderBody(marker, requestId, PRODUCT_ID);
    }

    private static String orderBody(final String marker, final String requestId,
            final long productId) {
        return "{\"productId\":" + productId + ",\"templateNo\":\"" + TEMPLATE_NO
                + "\",\"templateVersionNo\":1,\"requestId\":\"" + requestId
                + "\",\"clauseValues\":" + slotsJson(marker) + "}";
    }

    /** CT000001 V1 框架九必填槽位（marker 内嵌 subject_matter——用例间隔离）。 */
    private static String slotsJson(final String marker) {
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
                + "},\"strategy\":" + DEFAULT_STRATEGY + "}";
    }

    private static String unique(final String label) {
        return label + "-" + Long.toHexString(System.nanoTime()) + "-" + SEQ.incrementAndGet();
    }

    private JsonNode readBody(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private String codeOf(final MvcResult result) throws Exception {
        return readBody(result).path("code").asText();
    }
}
