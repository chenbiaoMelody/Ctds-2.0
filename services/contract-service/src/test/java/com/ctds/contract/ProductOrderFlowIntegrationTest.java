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
 * 订购与订单流程集成测试（WBS-3.7.3 hifi §6.1 T1~T10 核心锚；规格 B3/B4/B6 + B12-1；
 * C-7.1 剧本 S2-1~6 + 13 判定点）。真实 MySQL 8 容器实跑 Flyway V1~V4（V4 三订单表）；
 * 资格/产品/DID 三端口 {@code @MockitoBean} 沿合约协商生命周期测试先例；L3 密钥源 = 临时
 * 密钥文件注入（密钥零入库）。本机 Docker 未运行时整类跳过（门禁不红）。
 *
 * <p>订单创建复用合约发起门槛链（ADMITTED / 在架 / 异主体 / 模板 / 条款），本类只钉
 * 订单域自身行为：订单+合约草案同事务落库、双重幂等（requestId + 进行中唯一）、状态
 * 联动（生效→已完成 / 生效前终止→已关闭）、防枚举同码同文 + 拒绝留痕、参数校验。</p>
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
    private static final long PRODUCT_ID = 12L;
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
        final JsonNode first = createOrderOk(unique("首购")).path("data");
        final String contractNo = first.path("contractNo").asText();
        // 协商终止 → 订单关闭（联动断言详见 t6）
        assertThat(negotiationTerminate(contractNo).getResponse().getStatus()).isEqualTo(200);
        final String firstOrderNo = first.path("orderNo").asText();
        assertThat(orderStatus(firstOrderNo)).isEqualTo("CLOSED");
        // 新 requestId 重购：新订单 + 新合约（不复用已终止合约——Q6-A 绕开内容幂等）
        final JsonNode rebuy = createOrderOk(unique("重购")).path("data");
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
        // 需方本方可见（对照组：同一订单需方读取 200）
        assertThat(getOrder(REQ, orderNo).getResponse().getStatus()).isEqualTo(200);
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
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + TEMPLATE_NO
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
