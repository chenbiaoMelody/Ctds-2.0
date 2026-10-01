package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 统一目录检索集成测试（WBS-3.3.4 hifi §5 T2；规格行为 5 全部规则 + 行为 7 规则 2/5）：
 * 呈现范围恒仅已上架（未上架/已下架/已注销三态对照不出现在检索结果）、分类过滤（父类目含子树）、
 * keyword 命中名称与简介两字段、分页字段齐备翻页一致、排序 listed_at DESC（同秒按 id 倒序稳定锚）、
 * 详情同形双响应逐字对照（不存在/未上架/已下架/已注销——防枚举）、资格三态（401/403/未入驻统一文案
 * 且库内零留痕零副作用）、响应字段白名单显式锚定（无本体无敏感原文无个人信息）、keyword 超长与
 * 通配符字面化、categoryCode 非法拒绝。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1+V2+V3；产品数据测试自造（写面归 3.3.5 未开工，
 * 沿任务卡走查偏差登记同口径）；跨服务判定端口 {@code @MockitoBean}；无 Docker 整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductSearchIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCTS = "/api/v1/data-products";
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_search_it");
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
    void defaultsAdmitted() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
        // 共享库用例隔离：检索断言按全局总数，先清产品域数据（种子类目随迁移保留，不触碰 V1/V2 表）
        jdbc.update("DELETE FROM product_favorite");
        jdbc.update("DELETE FROM product_subscription");
        jdbc.update("DELETE FROM product_interaction_log");
        jdbc.update("DELETE FROM product_action_log");
        jdbc.update("DELETE FROM data_product");
    }

    // ==== 行为 5 规则 1：呈现范围恒仅已上架（三态对照）+ 行为 7 规则 5：字段白名单 ====

    @Test
    void searchContainsOnlyListedProductsWithWhitelistFields() throws Exception {
        final LocalDateTime listedAt = LocalDateTime.now();
        final long listedId = insertProduct("白名单产品", "简介含关键词金融数据", "LISTED", "finance",
                listedAt);
        insertProduct("未上架产品", "简介", "DRAFT", "finance", null);
        insertProduct("已下架产品", "简介", "DELISTED", "finance", listedAt);
        insertProduct("已注销产品", "简介", "CANCELLED", "finance", listedAt);

        final JsonNode page = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s1", PROVIDER))
                .andReturn());
        assertThat(page.get("total").asLong()).as("三态对照：仅 LISTED 一行入检索").isEqualTo(1);
        assertThat(page.get("list").get(0).get("productId").asLong()).isEqualTo(listedId);
        assertThat(page.get("list").get(0).properties().stream()
                .map(java.util.Map.Entry::getKey).toList())
                .as("R6 出站字段白名单（响应不含 status；行为 5 规则 4 检索 ≠ 可访问）")
                .containsExactlyInAnyOrder("productId", "productName", "intro", "productType",
                        "pricingModel", "categoryCode", "categoryName", "providerSubjectNo", "listedAt");
        assertThat(page.get("list").get(0).get("productType").asText()).isEqualTo("数据集");
        assertThat(page.get("list").get(0).get("pricingModel").asText()).isEqualTo("免费");
        assertThat(page.get("list").get(0).get("categoryName").asText()).isEqualTo("金融");
    }

    // ==== 行为 5 规则 3：分类过滤（父类目含子树展开）====

    @Test
    void categoryFilterExpandsSubtreeFromParentCode() throws Exception {
        final LocalDateTime listedAt = LocalDateTime.now();
        insertProduct("父类目直挂产品", "简介", "LISTED", "finance", listedAt);
        insertProduct("银行子类产品", "简介", "LISTED", "finance-banking", listedAt);
        insertProduct("普惠子类产品", "简介", "LISTED", "finance-inclusive", listedAt);
        insertProduct("交通类产品", "简介", "LISTED", "transport", listedAt);

        final JsonNode parent = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s2", PROVIDER)
                        .param("categoryCode", "finance")).andReturn());
        assertThat(parent.get("total").asLong()).as("父类目过滤 = 自身 + 子树（2 级）").isEqualTo(3);
        assertThat(namesOf(parent)).containsExactlyInAnyOrder("父类目直挂产品", "银行子类产品", "普惠子类产品");

        final JsonNode leaf = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s2", PROVIDER)
                        .param("categoryCode", "finance-inclusive")).andReturn());
        assertThat(leaf.get("total").asLong()).as("叶子类目过滤 = 仅自身").isEqualTo(1);
        assertThat(namesOf(leaf)).containsExactly("普惠子类产品");
    }

    // ==== 行为 5 规则 3：keyword 命中名称与简介两字段（OR）====

    @Test
    void keywordMatchesNameAndIntroFields() throws Exception {
        insertProduct("交通流量预测服务", "普通简介", "LISTED", "transport", LocalDateTime.now());
        insertProduct("普通产品甲", "简介提及空气质量监测数据", "LISTED", "environment", LocalDateTime.now());

        final JsonNode byName = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s3", PROVIDER)
                .param("keyword", "交通流量")).andReturn());
        assertThat(namesOf(byName)).as("keyword 命中名称字段").containsExactly("交通流量预测服务");

        final JsonNode byIntro = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s3", PROVIDER)
                .param("keyword", "空气质量")).andReturn());
        assertThat(namesOf(byIntro)).as("keyword 命中简介字段").containsExactly("普通产品甲");
    }

    // ==== 行为 5 规则 5：排序 listed_at DESC（同秒按 id 倒序稳定锚）+ 分页字段齐备翻页一致 ====

    @Test
    void orderingAndPagingAreStableAcrossPages() throws Exception {
        final LocalDateTime sameSecond = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
        final long firstId = insertProduct("分页产品一", "简介", "LISTED", "transport", sameSecond);
        final long secondId = insertProduct("分页产品二", "简介", "LISTED", "transport", sameSecond);
        final long laterId = insertProduct("分页产品三", "简介", "LISTED", "transport",
                sameSecond.plusSeconds(60));

        final JsonNode page1 = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s4", PROVIDER)
                .param("pageNum", "1").param("pageSize", "2")).andReturn());
        assertThat(page1.get("total").asLong()).isEqualTo(3);
        assertThat(page1.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page1.get("list").get(0).get("productId").asLong()).as("上架时间倒序：最新在前")
                .isEqualTo(laterId);
        assertThat(page1.get("list").get(1).get("productId").asLong())
                .as("同秒按 id 倒序（稳定锚）").isEqualTo(secondId);

        final JsonNode page2 = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s4", PROVIDER)
                .param("pageNum", "2").param("pageSize", "2")).andReturn());
        assertThat(page2.get("list").get(0).get("productId").asLong()).isEqualTo(firstId);
        assertThat(page2.get("pageNum").asInt()).isEqualTo(2);
    }

    // ==== 反向：keyword 超长 / categoryCode 非类目树节点 → 1007C0013；通配符字面化 ====

    @Test
    void searchParameterViolationsRejectedWithLiteralWildcards() throws Exception {
        final MvcResult longKeyword = mockMvc.perform(auth(get(PRODUCTS), "reader-s5", PROVIDER)
                        .param("keyword", "金".repeat(65))).andReturn();
        assertThat(longKeyword.getResponse().getStatus()).as("keyword 超长 64 → 400").isEqualTo(400);
        assertThat(codeOf(longKeyword)).isEqualTo("1007C0013");

        final MvcResult badCategory = mockMvc.perform(auth(get(PRODUCTS), "reader-s5", PROVIDER)
                        .param("categoryCode", "火星类目")).andReturn();
        assertThat(badCategory.getResponse().getStatus())
                .as("categoryCode 非类目树节点 → 400").isEqualTo(400);
        assertThat(codeOf(badCategory)).isEqualTo("1007C0013");

        insertProduct("字面化探针产品", "简介", "LISTED", "transport", LocalDateTime.now());
        final JsonNode percent = payload(mockMvc.perform(auth(get(PRODUCTS), "reader-s5", PROVIDER)
                .param("keyword", "%")).andReturn());
        assertThat(percent.get("total").asLong()).as("% 按字面匹配 → 零命中（escapeLike 共用）").isZero();
    }

    // ==== 行为 7 规则 2：详情防枚举同形（不存在/未上架/已下架/已注销 四响应逐字对照）====

    @Test
    void detailRejectionsAreSameShapeAcrossInvisibleStates() throws Exception {
        final long draftId = insertProduct("未上架详情产品", "简介", "DRAFT", "transport", null);
        final long delistedId = insertProduct("已下架详情产品", "简介", "DELISTED", "transport",
                LocalDateTime.now());
        insertProduct("已注销详情产品", "简介", "CANCELLED", "transport", LocalDateTime.now());
        final long cancelledId = jdbc.queryForObject(
                "SELECT id FROM data_product WHERE product_name = '已注销详情产品'", Long.class);

        final MvcResult notFound = mockMvc.perform(
                auth(get(PRODUCTS + "/999999"), "reader-s6", PROVIDER)).andReturn();
        final MvcResult draft = mockMvc.perform(
                auth(get(PRODUCTS + "/" + draftId), "reader-s6", PROVIDER)).andReturn();
        final MvcResult delisted = mockMvc.perform(
                auth(get(PRODUCTS + "/" + delistedId), "reader-s6", PROVIDER)).andReturn();
        final MvcResult cancelled = mockMvc.perform(
                auth(get(PRODUCTS + "/" + cancelledId), "reader-s6", PROVIDER)).andReturn();

        for (final MvcResult result : List.of(notFound, draft, delisted, cancelled)) {
            assertThat(result.getResponse().getStatus()).as("同形 404").isEqualTo(404);
            assertThat(codeOf(result)).isEqualTo("1007C0011");
            assertThat(root(result).get("message").asText())
                    .as("四态文案逐字一致（防枚举）").isEqualTo("产品不存在或未在架");
        }
        // 在架产品详情可读且含 status 字段（详情契约完整性）
        final long listedId = insertProduct("在架详情产品", "简介全文", "LISTED", "transport",
                LocalDateTime.now());
        final MvcResult ok = mockMvc.perform(
                auth(get(PRODUCTS + "/" + listedId), "reader-s6", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("status").asText()).isEqualTo("已上架");
        assertThat(payload(ok).get("intro").asText()).isEqualTo("简介全文");
    }

    // ==== 行为 5 规则 2：资格三态（401 / 403 / 未入驻统一文案）+ 零副作用 ====

    @Test
    void searchAndDetailAdmissionGatesAreDistinctAndSideEffectFree() throws Exception {
        final long listedId = insertProduct("资格探针产品", "简介", "LISTED", "transport",
                LocalDateTime.now());
        final long interactionLogsBefore = interactionLogCount();

        final MvcResult anonymous = mockMvc.perform(get(PRODUCTS)).andReturn();
        assertThat(anonymous.getResponse().getStatus()).as("未认证 → 401").isEqualTo(401);

        final MvcResult forbidden = mockMvc.perform(auth(get(PRODUCTS), "reader-s7", "guest")).andReturn();
        assertThat(forbidden.getResponse().getStatus()).as("无 catalog.read → 403").isEqualTo(403);

        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult notAdmitted = mockMvc.perform(
                auth(get(PRODUCTS), "reader-s7", PROVIDER)).andReturn();
        assertThat(notAdmitted.getResponse().getStatus()).as("未入驻 → 403").isEqualTo(403);
        assertThat(codeOf(notAdmitted)).isEqualTo("1007C0006");
        assertThat(root(notAdmitted).get("message").asText())
                .isEqualTo("主体未入驻或不存在，无法使用统一目录服务");
        final MvcResult detailDenied = mockMvc.perform(
                auth(get(PRODUCTS + "/" + listedId), "reader-s7", PROVIDER)).andReturn();
        assertThat(detailDenied.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(detailDenied)).isEqualTo("1007C0006");

        given(admissionPort.check(any())).willReturn(SubjectAdmission.UNAVAILABLE);
        final MvcResult unavailable = mockMvc.perform(
                auth(get(PRODUCTS), "reader-s7", PROVIDER)).andReturn();
        assertThat(unavailable.getResponse().getStatus())
                .as("主体服务不可用 → 503（不冒充资格拒绝）").isEqualTo(503);
        assertThat(codeOf(unavailable)).isEqualTo("1007S0001");

        assertThat(interactionLogCount()).as("资格探针零留痕零副作用").isEqualTo(interactionLogsBefore);
    }

    // ==== 助手 ====

    private long insertProduct(final String productName, final String intro, final String status,
            final String categoryCode, final LocalDateTime listedAt) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                        + "provider_subject_no, dataset_id, category_code, listed_at) "
                        + "VALUES (?, ?, 'DATASET', 'FREE', ?, 'provider-s', 1, ?, ?)",
                productName, intro, status, categoryCode,
                listedAt == null ? null : Timestamp.valueOf(listedAt));
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                productName);
    }

    private long interactionLogCount() {
        final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM product_interaction_log", Long.class);
        return count == null ? 0 : count;
    }

    private static List<String> namesOf(final JsonNode page) {
        final List<String> names = new java.util.ArrayList<>();
        page.get("list").forEach(node -> names.add(node.get("productName").asText()));
        return names;
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    private static JsonNode root(final MvcResult result) throws Exception {
        return MAPPER.readTree(body(result));
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
