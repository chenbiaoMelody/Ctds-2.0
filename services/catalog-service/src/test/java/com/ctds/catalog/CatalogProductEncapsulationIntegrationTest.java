package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
 * 产品封装集成测试（WBS-3.3.5 hifi §5 T7；规格行为 3 全部规则 + C-3.3 剧本 S1 判定面）：
 * 正向封装（未上架初始态 + CREATE 留痕四要素 + 类目缺省继承）、非本人资源拒绝（1007C0006 +
 * 资源域 DENIED 留痕）、已注销资源与已解散空间拒绝（1007C0016——S1-5）、同名拒绝（归一化含
 * 首尾空白——S1-4）、一资源多产品与免费显式档（S1-3）、幂等重放（S1-6）、免费档携值拒绝（Q2-A）、
 * 名称边界（1007C0018）、未入驻统一文案防枚举（零副作用）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductEncapsulationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCTS = "/api/v1/data-products";
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_encap_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @MockitoBean
    private SpaceMembershipPort spaceMembershipPort;

    @BeforeEach
    void defaultsAdmittedAndActiveSpace() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
        jdbc.update("DELETE FROM product_favorite");
        jdbc.update("DELETE FROM product_subscription");
        jdbc.update("DELETE FROM product_interaction_log");
        jdbc.update("DELETE FROM product_action_log");
        jdbc.update("DELETE FROM data_product");
        jdbc.update("DELETE FROM dataset_action_log");
        jdbc.update("DELETE FROM dataset_name_lock");
        jdbc.update("DELETE FROM dataset");
    }

    // ==== 正向：未上架初始态 + 留痕四要素 + 类目缺省继承（S1-1 判定面）====

    @Test
    void createProductStartsDraftWithCreateLogAndInheritedCategory() throws Exception {
        final long datasetId = insertDataset("普惠金融数据集", "provider-p1", "ACTIVE");
        final MvcResult ok = mockMvc.perform(auth(post(PRODUCTS), "provider-p1", PROVIDER)
                        .content(createBody(datasetId, "普惠金融数据服务", "PER_CALL", "5.00", null)))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode view = payload(ok);
        assertThat(view.get("status").asText()).as("S1-1：封装成功处于未上架初始态").isEqualTo("未上架");
        assertThat(view.get("pricingModel").asText()).isEqualTo("按次");
        assertThat(view.get("priceAmount").asText()).isEqualTo("5.00");
        assertThat(view.get("categoryCode").asText())
                .as("类目缺省 = 按资源申报值「金融」继承（Q2-A 传导）").isEqualTo("finance");

        final Integer logs = jdbc.queryForObject("SELECT COUNT(*) FROM product_action_log "
                + "WHERE product_id = ? AND action = 'CREATE' AND operator_subject_no = 'provider-p1'",
                Integer.class, view.get("productId").asLong());
        assertThat(logs).as("CREATE 留痕一行（谁/何时/对哪个产品/动作四要素）").isEqualTo(1);
    }

    // ==== 显式类目覆盖（W8 ⑥ 显式传入须为类目树节点）====

    @Test
    void createProductWithExplicitCategoryOverridesInheritance() throws Exception {
        final long datasetId = insertDataset("交通流量数据集", "provider-p2", "ACTIVE");
        final MvcResult ok = mockMvc.perform(auth(post(PRODUCTS), "provider-p2", PROVIDER)
                        .content(createBody(datasetId, "交通流量服务", "FREE", null, "transport-smart")))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("categoryCode").asText()).isEqualTo("transport-smart");
    }

    // ==== 非本人资源拒绝（1007C0006 + 资源域 DENIED_CREATE 留痕——S1-2 判定面）====

    @Test
    void createProductFromOthersDatasetDeniedWithResourceLog() throws Exception {
        final long datasetId = insertDataset("他人资源数据集", "provider-owner", "ACTIVE");
        final MvcResult denied = mockMvc.perform(auth(post(PRODUCTS), "provider-p3", PROVIDER)
                        .content(createBody(datasetId, "越权封装产品", "FREE", null, null)))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1007C0006");
        final Integer deniedLogs = jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log "
                + "WHERE dataset_id = ? AND action = 'DENIED_CREATE' AND actor_subject_no = 'provider-p3'",
                Integer.class, datasetId);
        assertThat(deniedLogs).as("越权封装在资源域留痕（行为 7 规则 4 可取证）").isEqualTo(1);
    }

    // ==== 已注销资源 / 已解散空间拒绝（1007C0016——S1-5 判定面）====

    @Test
    void createProductFromDeletedDatasetOrDissolvedSpaceRejected() throws Exception {
        final long deletedId = insertDataset("待注销数据集", "provider-p4", "DELETED");
        final MvcResult byDeleted = mockMvc.perform(auth(post(PRODUCTS), "provider-p4", PROVIDER)
                        .content(createBody(deletedId, "注销资源封装", "FREE", null, null)))
                .andReturn();
        assertThat(byDeleted.getResponse().getStatus()).as(body(byDeleted)).isEqualTo(409);
        assertThat(codeOf(byDeleted)).as("已注销资源不得封装（S1-5）").isEqualTo("1007C0016");

        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("DISSOLVED", "MEMBER"));
        final long dissolvedId = insertDataset("解散空间资源", "provider-p4", "ACTIVE");
        final MvcResult byDissolved = mockMvc.perform(auth(post(PRODUCTS), "provider-p4", PROVIDER)
                        .content(createBody(dissolvedId, "解散空间封装", "FREE", null, null)))
                .andReturn();
        assertThat(byDissolved.getResponse().getStatus()).as(body(byDissolved)).isEqualTo(409);
        assertThat(codeOf(byDissolved)).as("已解散空间内资源不得封装（S1-5）").isEqualTo("1007C0016");
    }

    // ==== 同名拒绝（归一化判定——S1-4 判定面；唯一键含已注销行）====
    // 同名拒绝路径换用同提供方的另一资源（绕开幂等结果缓存——同资源+同名=同请求键窗口内复用首结果
    // 〔幂等，S1-6 承载〕；跨资源同名=不同键 → 判重命中，同时锚定"命名唯一与资源无关"），
    // 沿 CatalogDatasetLifecycleIntegrationTest T5 同款处理。

    @Test
    void createProductDuplicateNameRejectedAfterNormalization() throws Exception {
        final long datasetA = insertDataset("同名资源甲", "provider-p5", "ACTIVE");
        final long datasetB = insertDataset("同名资源乙", "provider-p5", "ACTIVE");
        mockMvc.perform(auth(post(PRODUCTS), "provider-p5", PROVIDER)
                        .content(createBody(datasetA, "同名数据服务", "FREE", null, null)))
                .andReturn();
        final MvcResult duplicated = mockMvc.perform(auth(post(PRODUCTS), "provider-p5", PROVIDER)
                        .content(createBody(datasetB, "  同名数据服务  ", "FREE", null, null)))
                .andReturn();
        assertThat(duplicated.getResponse().getStatus()).as(body(duplicated)).isEqualTo(409);
        assertThat(codeOf(duplicated)).as("归一化后同名拒绝（S1-4：首尾空白不绕过）")
                .isEqualTo("1007C0017");
    }

    // ==== 一资源多产品 + 免费显式档（S1-3 判定面）====

    @Test
    void createTwoProductsFromSameDatasetWithExplicitFreeTier() throws Exception {
        final long datasetId = insertDataset("一源多品资源", "provider-p6", "ACTIVE");
        final MvcResult first = mockMvc.perform(auth(post(PRODUCTS), "provider-p6", PROVIDER)
                        .content(createBody(datasetId, "按次服务版", "PER_CALL", "3.50", null)))
                .andReturn();
        final MvcResult free = mockMvc.perform(auth(post(PRODUCTS), "provider-p6", PROVIDER)
                        .content(createBody(datasetId, "免费体验版", "FREE", null, null)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        assertThat(free.getResponse().getStatus()).as(body(free)).isEqualTo(200);
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_product WHERE dataset_id = ?", Integer.class, datasetId);
        assertThat(count).as("一资源多产品（Q4-A 不设上限）").isEqualTo(2);
        assertThat(payload(free).get("priceAmount").isNull())
                .as("免费档 price_amount 恒 NULL（Q2-A）").isTrue();
    }

    // ==== 免费档携值拒绝（Q2-A）+ 名称边界（1007C0018）====

    @Test
    void createProductParameterViolationsRejected() throws Exception {
        final long datasetId = insertDataset("参数探针资源", "provider-p7", "ACTIVE");
        final MvcResult freeWithAmount = mockMvc.perform(auth(post(PRODUCTS), "provider-p7", PROVIDER)
                        .content(createBody(datasetId, "免费却带价产品", "FREE", "9.90", null)))
                .andReturn();
        assertThat(freeWithAmount.getResponse().getStatus()).as(body(freeWithAmount)).isEqualTo(409);
        assertThat(codeOf(freeWithAmount)).as("免费档不得携带数值（Q2-A）").isEqualTo("1007C0020");

        final MvcResult longName = mockMvc.perform(auth(post(PRODUCTS), "provider-p7", PROVIDER)
                        .content(createBody(datasetId, "名".repeat(129), "FREE", null, null)))
                .andReturn();
        assertThat(longName.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(longName)).as("名称超长 → 1007C0018").isEqualTo("1007C0018");

        final MvcResult blankName = mockMvc.perform(auth(post(PRODUCTS), "provider-p7", PROVIDER)
                        .content(createBody(datasetId, "  ", "FREE", null, null)))
                .andReturn();
        assertThat(codeOf(blankName)).as("名称为空 → 1007C0018").isEqualTo("1007C0018");
    }

    // ==== 幂等重放（S1-6 判定面）：同要素重复提交返回首次结果、数量不变 ====

    @Test
    void createProductIdempotentReplayReturnsFirstResult() throws Exception {
        final long datasetId = insertDataset("幂等封装资源", "provider-p8", "ACTIVE");
        final MvcResult first = mockMvc.perform(auth(post(PRODUCTS), "provider-p8", PROVIDER)
                        .content(createBody(datasetId, "幂等产品", "PER_CALL", "2.00", null)))
                .andReturn();
        final long firstId = payload(first).get("productId").asLong();
        final MvcResult replay = mockMvc.perform(auth(post(PRODUCTS), "provider-p8", PROVIDER)
                        .content(createBody(datasetId, "幂等产品", "PER_CALL", "2.00", null)))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).as(body(replay)).isEqualTo(200);
        assertThat(payload(replay).get("productId").asLong()).as("幂等重放返回首次结果")
                .isEqualTo(firstId);
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_product WHERE product_name = '幂等产品'", Integer.class);
        assertThat(count).as("产品数量不变（S1-6）").isEqualTo(1);
    }

    // ==== 未入驻统一文案（防枚举；零副作用）====

    @Test
    void createProductByNonAdmittedSubjectRejectedWithUnifiedMessage() throws Exception {
        final long datasetId = insertDataset("资格探针资源", "provider-p9", "ACTIVE");
        final Long productBefore = countProducts();
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult denied = mockMvc.perform(auth(post(PRODUCTS), "provider-p9", PROVIDER)
                        .content(createBody(datasetId, "未入驻封装", "FREE", null, null)))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1007C0006");
        assertThat(root(denied).get("message").asText())
                .isEqualTo("主体未入驻或不存在，无法使用统一目录服务");
        assertThat(countProducts()).as("零副作用").isEqualTo(productBefore);
    }

    // ==== 助手 ====

    private long countProducts() {
        final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM data_product", Long.class);
        return count == null ? 0 : count;
    }

    private long insertDataset(final String name, final String owner, final String status) {
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, "
                        + "type, intro, semantic_tags, declare_category, declare_level, "
                        + "declare_important, status) VALUES (?, 1, ?, ?, ?, 'DATASET', '简介', '[]', "
                        + "'金融', 'L2', 0, ?)",
                "DS" + System.nanoTime(), owner, name, name, status);
        return jdbc.queryForObject("SELECT id FROM dataset WHERE name = ?", Long.class, name);
    }

    private static String createBody(final long datasetId, final String name, final String model,
            final String amount, final String categoryCode) {
        return "{\"datasetId\":" + datasetId + ",\"productName\":\"" + name + "\",\"intro\":\"测试简介\","
                + "\"productType\":\"DATASET\",\"pricingModel\":\"" + model + "\""
                + (amount == null ? "" : ",\"priceAmount\":" + amount)
                + (categoryCode == null ? "" : ",\"categoryCode\":\"" + categoryCode + "\"") + "}";
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static JsonNode root(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode payload(final MvcResult result) throws Exception {
        return root(result).get("data");
    }

    private static String body(final MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String codeOf(final MvcResult result) throws Exception {
        return root(result).get("code").asText();
    }
}
