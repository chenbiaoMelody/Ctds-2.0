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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 目录语义检索集成测试（WBS-3.10.2 hifi §7 T3~T7）：仅 LISTED + R6 字段白名单、
 * 相关性排序、语义标签/源文本变更刷新、下架零泄漏（缓存保留）、分类子树/分页、
 * 参数校验与资格三态；真实 MySQL 8 容器实跑 Flyway V1~V5。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogSemanticSearchIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEMANTIC = "/api/v1/catalog/semantic-search";
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_semantic_it");
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
        jdbc.update("DELETE FROM product_embedding");
        jdbc.update("DELETE FROM product_favorite");
        jdbc.update("DELETE FROM product_subscription");
        jdbc.update("DELETE FROM product_interaction_log");
        jdbc.update("DELETE FROM product_action_log");
        jdbc.update("DELETE FROM data_product");
        jdbc.update("DELETE FROM dataset");
    }

    @Test
    void onlyListedProductsReturnWithR6FieldWhitelist() throws Exception {
        final long datasetId = insertDataset("DSSEM-ONLY", "[\"金融\"]");
        final long listedId = insertProduct("金融风控数据集", "风控模型", "LISTED", "finance", datasetId);
        insertProduct("未上架风控产品", "风控模型", "DRAFT", "finance", datasetId);
        insertProduct("已下架风控产品", "风控模型", "DELISTED", "finance", datasetId);
        insertProduct("已注销风控产品", "风控模型", "CANCELLED", "finance", datasetId);

        final MvcResult result = mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode page = payload(result);
        assertThat(page.get("total").asLong()).as("三态对照：仅 LISTED 一行入选").isEqualTo(1);
        assertThat(page.get("list").get(0).get("productId").asLong()).isEqualTo(listedId);
        assertThat(page.get("list").get(0).properties().stream().map(Map.Entry::getKey).toList())
                .as("出站字段白名单与 R6 一致（无 score/status）")
                .containsExactlyInAnyOrder("productId", "productName", "intro", "productType",
                        "pricingModel", "categoryCode", "categoryName", "providerSubjectNo", "listedAt");
    }

    @Test
    void rankingPrefersMatchingProduct() throws Exception {
        final long datasetId = insertDataset("DSSEM-RANK", "[\"金融\"]");
        final long relevantId = insertProduct("金融风控报告", "风控模型与风险画像", "LISTED",
                "finance", datasetId);
        insertProduct("农业气象数据", "气象观测", "LISTED", "transport", datasetId);

        final JsonNode page = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(page.get("list").get(0).get("productId").asLong())
                .as("相关产品按相似度排在未匹配产品之前").isEqualTo(relevantId);
    }

    @Test
    void semanticTagsAndSourceChangeRefreshAndDelistHidesImmediately() throws Exception {
        final long taggedDataset = insertDataset("DSSEM-TAGS-FIN", "[\"金融\",\"风控\"]");
        final long plainDataset = insertDataset("DSSEM-TAGS-PLAIN", "[]");
        insertProduct("通用数据分析服务", "数据分析", "LISTED", "finance", taggedDataset);
        final long plainId = insertProduct("农业气象数据", "气象观测", "LISTED", "transport", plainDataset);

        final List<String> before = namesOf(payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn()));
        assertThat(before).contains("通用数据分析服务", "农业气象数据");
        assertThat(before.indexOf("通用数据分析服务"))
                .as("语义标签贡献命中：标签产品排在未匹配产品之前")
                .isLessThan(before.indexOf("农业气象数据"));

        jdbc.update("UPDATE data_product SET intro = ? WHERE id = ?", "金融风控模型", plainId);
        final List<String> after = namesOf(payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn()));
        assertThat(after.indexOf("农业气象数据"))
                .as("简介变更触发源指纹重算，排名上升")
                .isLessThan(before.indexOf("农业气象数据"));

        jdbc.update("UPDATE data_product SET status = 'DELISTED', listed_at = NULL WHERE id = ?", plainId);
        final List<String> delisted = namesOf(payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn()));
        assertThat(delisted).as("下架后立即不出现").doesNotContain("农业气象数据");
        assertThat(embeddingCount(plainId)).as("下架后向量缓存行保留（状态硬过滤）").isEqualTo(1L);
    }

    @Test
    void categorySubtreeAndPaginationShouldBeStable() throws Exception {
        final long financeDataset = insertDataset("DSSEM-CAT-FIN", "[\"金融\"]");
        final long bankDataset = insertDataset("DSSEM-CAT-BANK", "[\"金融\"]");
        final long transportDataset = insertDataset("DSSEM-CAT-TRANS", "[\"交通\"]");
        insertProduct("金融数据一", "金融风控", "LISTED", "finance", financeDataset);
        insertProduct("金融数据二", "金融风控", "LISTED", "finance", financeDataset);
        insertProduct("金融数据三", "金融风控", "LISTED", "finance", financeDataset);
        insertProduct("银行风控产品", "金融风控", "LISTED", "finance-banking", bankDataset);
        insertProduct("交通物流产品", "交通物流", "LISTED", "transport", transportDataset);

        final JsonNode first = payload(mockMvc.perform(auth(get(SEMANTIC)
                .param("q", "金融风控").param("categoryCode", "finance")
                .param("pageNum", "1").param("pageSize", "2"), "reader-s1", PROVIDER)).andReturn());
        assertThat(first.get("total").asLong()).as("父类目含子树：4 行（不含 transport）").isEqualTo(4);
        assertThat(first.get("list").size()).isEqualTo(2);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);

        final JsonNode second = payload(mockMvc.perform(auth(get(SEMANTIC)
                .param("q", "金融风控").param("categoryCode", "finance")
                .param("pageNum", "2").param("pageSize", "2"), "reader-s1", PROVIDER)).andReturn());
        assertThat(second.get("list").size()).isEqualTo(2);
        assertThat(namesOf(first)).doesNotContain("交通物流产品");
        assertThat(namesOf(second)).doesNotContain("交通物流产品");
    }

    @Test
    void validationAndEligibilityShouldFollowCatalogRules() throws Exception {
        final long datasetId = insertDataset("DSSEM-VALID", "[\"金融\"]");
        insertProduct("金融风控数据集", "风控模型", "LISTED", "finance", datasetId);

        assertError(get(SEMANTIC).param("q", "  "), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "金".repeat(65)), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "金融").param("categoryCode", "no-such"), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "金融").param("pageSize", "101"), 400, "1000C0001");

        final JsonNode punctuation = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "，。！？"), "reader-s1", PROVIDER)).andReturn());
        assertThat(punctuation.get("total").asLong()).as("无有效词元 → 空结果页").isZero();

        final MvcResult unauthenticated = mockMvc.perform(get(SEMANTIC).param("q", "金融")).andReturn();
        assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);

        final MvcResult noPermission = mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融"), "reader-s1", "contract-internal")).andReturn();
        assertThat(noPermission.getResponse().getStatus()).isEqualTo(403);

        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult unadmitted = mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融"), "reader-s1", PROVIDER)).andReturn();
        assertThat(unadmitted.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(unadmitted)).isEqualTo("1007C0006");
    }

    private void assertError(final MockHttpServletRequestBuilder request, final int status,
            final String code) throws Exception {
        final MvcResult result = mockMvc.perform(auth(request, "reader-s1", PROVIDER)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(codeOf(result)).isEqualTo(code);
    }

    private long insertDataset(final String dataNo, final String semanticTags) {
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, "
                        + "type, intro, semantic_tags, declare_category, declare_level, "
                        + "declare_important, status) "
                        + "VALUES (?, 1, 'owner-s', ?, ?, 'DATASET', '简介', ?, '金融', 'L1', 0, 'ACTIVE')",
                dataNo, dataNo, dataNo, semanticTags);
        return jdbc.queryForObject("SELECT id FROM dataset WHERE data_no = ?", Long.class, dataNo);
    }

    private long insertProduct(final String productName, final String intro, final String status,
            final String categoryCode, final long datasetId) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                        + "provider_subject_no, dataset_id, category_code, listed_at) "
                        + "VALUES (?, ?, 'DATASET', 'FREE', ?, 'provider-s', ?, ?, ?)",
                productName, intro, status, datasetId, categoryCode,
                "LISTED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null);
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                productName);
    }

    private long embeddingCount(final long productId) {
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_embedding WHERE product_id = ?", Long.class, productId);
        return count == null ? 0L : count;
    }

    private static List<String> namesOf(final JsonNode page) {
        final List<String> names = new ArrayList<>();
        page.get("list").forEach(node -> names.add(node.get("productName").asText()));
        return names;
    }

    private static MockHttpServletRequestBuilder auth(final MockHttpServletRequestBuilder builder,
            final String subject, final String roles) {
        return builder.header("X-Ctds-Subject", subject).header("X-Ctds-Roles", roles);
    }

    private static JsonNode payload(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private static String codeOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("code").asText();
    }
}
