package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 * 产品生命周期集成测试（WBS-3.3.5 hifi §5 T8/T9；规格行为 4 全部规则 + 行为 2 规则 2 +
 * C-3.3 剧本 S2/S3 判定面）：上架成功与目录联动（S2-1）、定价不齐备拒绝（S2-2 → 1007C0020）、
 * 变更 from→to 留痕（S2-3）、已解散空间资源上架拒绝（S2-4 → 1007C0016）、未入驻上架拒绝
 * （S2-5 防枚举）、下架隐藏目录与重新上架恢复（S3-1/S3-2、listed_at 三态）、强制下架理由留痕
 * （S3-3）、非提供方拒绝（S3-4 → 1007C0015 + DENIED 留痕）、下架后注销（S3-5）、在架注销拒绝
 * （S3-6 → 1007C0019）、状态机封闭矩阵（DRAFT 下架/重复下架/已注销一切动作）、引用保护双向
 * （T9：引用在 → W3 拒 1007C0021〔C-3.1 剧本 S3-4 兑现〕；产品注销后放行）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCTS = "/api/v1/data-products";
    private static final String PROVIDER = "provider";
    private static final String ADMIN = "admin";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_life_it");
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

    // ==== S2-1：上架成功 + 目录联动可检索 ====

    @Test
    void publishListedProductBecomesSearchable() throws Exception {
        final long productId = insertProduct("上架联动产品", "PER_CALL", "3.00", "DRAFT", null, "provider-l1");
        final MvcResult ok = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/publish"), "provider-l1", PROVIDER))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("status").asText()).isEqualTo("已上架");
        assertThat(payload(ok).get("listedAt")).as("listed_at 上架写入（V3 注释口径）").isNotNull();

        final MvcResult search = mockMvc.perform(auth(get(PRODUCTS), "reader-l1", PROVIDER))
                .andReturn();
        assertThat(payload(search).get("total").asLong())
                .as("上架后目录检索可见（行为 5 规则 1 联动）").isEqualTo(1);
        final Integer publishLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ? AND action = 'PUBLISH'",
                Integer.class, productId);
        assertThat(publishLogs).as("PUBLISH 留痕").isEqualTo(1);
    }

    // ==== S2-2：定价不齐备拒绝（付费档数值缺失 → 1007C0020）====

    @Test
    void publishWithoutPriceRejected() throws Exception {
        final long productId = insertProduct("缺价产品", "PER_CALL", null, "DRAFT", null, "provider-l2");
        final MvcResult denied = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/publish"), "provider-l2", PROVIDER))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(409);
        assertThat(codeOf(denied)).as("S2-2：定价不得空缺").isEqualTo("1007C0020");
        final String status = jdbc.queryForObject(
                "SELECT status FROM data_product WHERE id = ?", String.class, productId);
        assertThat(status).as("拒绝后状态未漂移").isEqualTo("DRAFT");
    }

    // ==== S2-3：变更成功且留痕含 from→to ====

    @Test
    void updateProductLogsFromToSummary() throws Exception {
        final long productId = insertProduct("变更产品", "PER_CALL", "3.00", "LISTED",
                LocalDateTime.now(), "provider-l3");
        final MvcResult ok = mockMvc.perform(auth(put(PRODUCTS + "/" + productId),
                        "provider-l3", PROVIDER)
                        .content("{\"intro\":\"新简介\",\"pricingModel\":\"MONTHLY\","
                                + "\"priceAmount\":19.90}"))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("pricingModel").asText()).isEqualTo("包月");
        final String summary = jdbc.queryForObject(
                "SELECT summary FROM product_action_log WHERE product_id = ? AND action = 'UPDATE'",
                String.class, productId);
        assertThat(summary)
                .as("S2-3：留痕含从何值→到何值")
                .contains("intro:测试简介→新简介")
                .contains("pricingModel:PER_CALL→MONTHLY")
                .contains("priceAmount:3.00→19.90");
    }

    // ==== S2-4：已解散空间内资源的未上架产品上架拒绝 ====

    @Test
    void publishProductOfDissolvedSpaceRejected() throws Exception {
        final long datasetId = insertDataset("解散空间资源产品", "provider-l4");
        final long productId = insertProductWithDataset("解散空间产品", "FREE", null, "DRAFT", null,
                datasetId, "provider-l4");
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("DISSOLVED", "MEMBER"));
        final MvcResult denied = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/publish"), "provider-l4", PROVIDER))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(409);
        assertThat(codeOf(denied)).as("S2-4：已解散空间资源不得上架").isEqualTo("1007C0016");
    }

    // ==== S2-5：未入驻主体上架拒绝（统一文案防枚举）====

    @Test
    void publishByNonAdmittedProviderRejected() throws Exception {
        final long productId = insertProduct("未入驻上架产品", "FREE", null, "DRAFT", null, "provider-l5");
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult denied = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/publish"), "provider-l5", PROVIDER))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(403);
        assertThat(root(denied).get("message").asText())
                .isEqualTo("主体未入驻或不存在，无法使用统一目录服务");
    }

    // ==== S3-1/S3-2：下架隐藏目录 → 重新上架恢复（listed_at 三态）====

    @Test
    void delistHidesFromDirectoryAndRepublishRestores() throws Exception {
        final long productId = insertProduct("下架往返产品", "FREE", null, "LISTED",
                LocalDateTime.now(), "provider-l6");
        final MvcResult delisted = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/delist"), "provider-l6", PROVIDER))
                .andReturn();
        assertThat(delisted.getResponse().getStatus()).as(body(delisted)).isEqualTo(200);
        assertThat(payload(delisted).get("status").asText()).isEqualTo("已下架");
        assertThat(payload(delisted).get("listedAt").isNull())
                .as("下架清 listed_at（V3 注释口径）").isTrue();

        final MvcResult search = mockMvc.perform(auth(get(PRODUCTS), "reader-l6", PROVIDER))
                .andReturn();
        assertThat(payload(search).get("total").asLong()).as("下架后目录不再呈现").isZero();

        final MvcResult republished = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/publish"), "provider-l6", PROVIDER))
                .andReturn();
        assertThat(republished.getResponse().getStatus()).as(body(republished)).isEqualTo(200);
        assertThat(payload(republished).get("listedAt")).as("重新上架恢复 listed_at").isNotNull();
        final MvcResult searchAgain = mockMvc.perform(auth(get(PRODUCTS), "reader-l6", PROVIDER))
                .andReturn();
        assertThat(payload(searchAgain).get("total").asLong()).as("重新上架后恢复可检索").isEqualTo(1);
    }

    // ==== S3-3：平台运营方强制下架（留痕含理由与操作者）====

    @Test
    void forceDelistByAdminLogsReasonAndOperator() throws Exception {
        final long productId = insertProduct("强下产品", "FREE", null, "LISTED", LocalDateTime.now(), "provider-l");
        final MvcResult ok = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/force-delist"), "admin-g1", ADMIN)
                        .content("{\"forceReason\":\"申报信息与实际内容不符\"}"))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("status").asText()).isEqualTo("已下架");
        final String summary = jdbc.queryForObject(
                "SELECT summary FROM product_action_log WHERE product_id = ? "
                        + "AND action = 'FORCE_DELIST'", String.class, productId);
        assertThat(summary).as("S3-3：留痕含理由全文")
                .contains("申报信息与实际内容不符");
        final String operator = jdbc.queryForObject(
                "SELECT operator_subject_no FROM product_action_log WHERE product_id = ? "
                        + "AND action = 'FORCE_DELIST'", String.class, productId);
        assertThat(operator).as("留痕含操作者").isEqualTo("admin-g1");
    }

    // ==== S3-4：非提供方下架/改价拒绝（1007C0015 + DENIED 留痕）====

    @Test
    void nonProviderDelistAndUpdateDeniedWithLog() throws Exception {
        final long productId = insertProduct("越权探针产品", "PER_CALL", "3.00", "LISTED",
                LocalDateTime.now(), "provider-l");
        final Long deniedLogsBefore = countLogs(productId);
        final MvcResult delistDenied = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/delist"), "provider-other", PROVIDER))
                .andReturn();
        assertThat(delistDenied.getResponse().getStatus()).as(body(delistDenied)).isEqualTo(403);
        assertThat(codeOf(delistDenied)).as("S3-4：服务端强制").isEqualTo("1007C0015");
        final MvcResult updateDenied = mockMvc.perform(auth(put(PRODUCTS + "/" + productId),
                        "provider-other", PROVIDER)
                        .content("{\"priceAmount\":99.90}"))
                .andReturn();
        assertThat(updateDenied.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(updateDenied)).isEqualTo("1007C0015");
        final Integer deniedLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ? "
                        + "AND action = 'DENIED_DELIST'", Integer.class, productId);
        assertThat(deniedLogs).as("越权探测留痕（行为 7 规则 4）").isEqualTo(1);
    }

    // ==== S3-5：已下架产品注销（二次确认、不可逆）+ 已注销后一切动作拒绝 ====

    @Test
    void cancelDelistedProductThenAllActionsRejected() throws Exception {
        final long productId = insertProduct("注销往返产品", "FREE", null, "DELISTED", null, "provider-l7");
        final MvcResult noConfirm = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/cancellation"), "provider-l7", PROVIDER)
                        .content("{}"))
                .andReturn();
        assertThat(noConfirm.getResponse().getStatus()).as(body(noConfirm)).isEqualTo(400);

        final MvcResult ok = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/cancellation"), "provider-l7", PROVIDER)
                        .content("{\"confirmCancellation\":true}"))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("status").asText()).isEqualTo("已注销");
        final Integer cancelLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ? AND action = 'CANCEL'",
                Integer.class, productId);
        assertThat(cancelLogs).as("S3-5：注销留痕保留").isEqualTo(1);

        final MvcResult republish = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/publish"), "provider-l7", PROVIDER))
                .andReturn();
        assertThat(republish.getResponse().getStatus()).as(body(republish)).isEqualTo(409);
        assertThat(codeOf(republish)).as("已注销不可逆").isEqualTo("1007C0019");
        final MvcResult update = mockMvc.perform(auth(put(PRODUCTS + "/" + productId),
                        "provider-l7", PROVIDER).content("{\"intro\":\"x\"}"))
                .andReturn();
        assertThat(codeOf(update)).as("已注销变更拒绝").isEqualTo("1007C0019");
    }

    // ==== S3-6：在架产品直接注销拒绝（须先下架）====

    @Test
    void cancelListedProductRejected() throws Exception {
        final long productId = insertProduct("在架注销探针", "FREE", null, "LISTED",
                LocalDateTime.now(), "provider-l8");
        final MvcResult denied = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/cancellation"), "provider-l8", PROVIDER)
                        .content("{\"confirmCancellation\":true}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(409);
        assertThat(codeOf(denied)).as("S3-6：须先下架再注销").isEqualTo("1007C0019");
    }

    // ==== 状态机封闭矩阵：DRAFT 直接注销合法 / DRAFT 下架拒绝 / 重复下架拒绝 ====

    @Test
    void stateMachineClosedMatrixAnchored() throws Exception {
        final long draftId = insertProduct("状态机探针甲", "FREE", null, "DRAFT", null, "provider-l9");
        final MvcResult draftCancel = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + draftId + "/cancellation"), "provider-l9", PROVIDER)
                        .content("{\"confirmCancellation\":true}"))
                .andReturn();
        assertThat(draftCancel.getResponse().getStatus()).as(body(draftCancel))
                .as("未上架可直接注销（行为 4 规则 1）").isEqualTo(200);

        final long draftDelistId = insertProduct("状态机探针乙", "FREE", null, "DRAFT", null, "provider-l9");
        final MvcResult draftDelist = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + draftDelistId + "/delist"), "provider-l9", PROVIDER))
                .andReturn();
        assertThat(draftDelist.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(draftDelist)).as("未上架谈不上下架（1007C0019）").isEqualTo("1007C0019");

        final long listedId = insertProduct("状态机探针丙", "FREE", null, "LISTED",
                LocalDateTime.now(), "provider-l9");
        mockMvc.perform(auth(post(PRODUCTS + "/" + listedId + "/delist"), "provider-l9", PROVIDER))
                .andReturn();
        final MvcResult redelist = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + listedId + "/delist"), "provider-l9", PROVIDER))
                .andReturn();
        assertThat(redelist.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(redelist)).as("重复下架拒绝").isEqualTo("1007C0019");
        final MvcResult republishListed = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + listedId + "/publish"), "provider-l9", PROVIDER))
                .andReturn();
        assertThat(republishListed.getResponse().getStatus()).as(body(republishListed))
                .as("已下架可重新上架").isEqualTo(200);
    }

    // ==== T9 引用保护：存在未注销产品引用的资源不得注销（C-3.1 剧本 S3-4 兑现）====

    @Test
    void referencedDatasetCancelRejectedUntilProductCancelled() throws Exception {
        final long datasetId = insertDataset("引用保护资源", "provider-l10");
        final long productId = insertProductWithDataset("引用产品", "FREE", null, "DRAFT", null,
                datasetId, "provider-l10");
        final MvcResult denied = mockMvc.perform(auth(
                        post("/api/v1/datasets/" + datasetId + "/cancellation"), "provider-l10",
                        PROVIDER)
                        .content("{\"confirmCancellation\":true}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(409);
        assertThat(codeOf(denied)).as("C-3.1 S3-4：先处理产品").isEqualTo("1007C0021");

        mockMvc.perform(auth(post(PRODUCTS + "/" + productId + "/cancellation"), "provider-l10",
                PROVIDER).content("{\"confirmCancellation\":true}")).andReturn();
        final MvcResult allowed = mockMvc.perform(auth(
                        post("/api/v1/datasets/" + datasetId + "/cancellation"), "provider-l10",
                        PROVIDER)
                        .content("{\"confirmCancellation\":true}"))
                .andReturn();
        assertThat(allowed.getResponse().getStatus()).as(body(allowed))
                .as("产品注销后资源可注销（双向）").isEqualTo(200);
    }

    // ==== 助手 ====

    private Long countLogs(final long productId) {
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ?", Long.class, productId);
        return count == null ? 0 : count;
    }

    private long insertDataset(final String name, final String owner) {
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, "
                        + "type, intro, semantic_tags, declare_category, declare_level, "
                        + "declare_important, status) VALUES (?, 1, ?, ?, ?, 'DATASET', '简介', '[]', "
                        + "'金融', 'L2', 0, 'ACTIVE')",
                "DS" + System.nanoTime(), owner, name, name);
        return jdbc.queryForObject("SELECT id FROM dataset WHERE name = ?", Long.class, name);
    }

    private long insertProduct(final String name, final String model, final String amount,
            final String status, final LocalDateTime listedAt, final String owner) {
        return insertProductWithDataset(name, model, amount, status, listedAt,
                insertDataset("资源" + name, owner), owner);
    }

    private long insertProductWithDataset(final String name, final String model, final String amount,
            final String status, final LocalDateTime listedAt, final long datasetId,
            final String owner) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, "
                        + "price_amount, status, provider_subject_no, dataset_id, listed_at) "
                        + "VALUES (?, '测试简介', 'DATASET', ?, ?, ?, ?, ?, ?)",
                name, model, amount == null ? null : new java.math.BigDecimal(amount), status,
                owner, datasetId, listedAt == null ? null : Timestamp.valueOf(listedAt));
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                name);
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
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
