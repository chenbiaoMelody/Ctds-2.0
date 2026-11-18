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
import com.ctds.common.vectorstore.EmbeddingTexts;
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

    // L1 缓存无清理入口：下列 DELETE 不重置自增 id，L1 中的旧产品 id 不会与新行冲突（隔离依赖此不变量）
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
        assertThat(logCount("product_interaction_log")).as("语义检索零互动留痕").isZero();
        assertThat(logCount("product_action_log")).as("语义检索零操作留痕").isZero();
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
        assertThat(second.get("total").asLong()).as("第二页 total 与第一页一致").isEqualTo(4);
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
        final List<String> firstNames = namesOf(first);
        final List<String> secondNames = namesOf(second);
        assertThat(firstNames).doesNotContain("交通物流产品");
        assertThat(secondNames).doesNotContain("交通物流产品");
        final List<String> union = new ArrayList<>(firstNames);
        union.addAll(secondNames);
        assertThat(union).as("两页互不重复且并集 = 4 条").doesNotHaveDuplicates().hasSize(4);

        final JsonNode beyond = payload(mockMvc.perform(auth(get(SEMANTIC)
                .param("q", "金融风控").param("categoryCode", "finance")
                .param("pageNum", "999").param("pageSize", "2"), "reader-s1", PROVIDER)).andReturn());
        assertThat(beyond.get("total").asLong()).as("越界页 total 不变").isEqualTo(4);
        assertThat(beyond.get("list").size()).as("越界页空列表").isZero();
        assertError(get(SEMANTIC).param("q", "金融风控").param("pageNum", "0"), 400, "1000C0001");
    }

    @Test
    void validationAndEligibilityShouldFollowCatalogRules() throws Exception {
        final long datasetId = insertDataset("DSSEM-VALID", "[\"金融\"]");
        insertProduct("金融风控数据集", "风控模型", "LISTED", "finance", datasetId);

        assertError(get(SEMANTIC), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "  "), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "\u3000"), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "金".repeat(65)), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "金融").param("categoryCode", "no-such"), 400, "1007C0013");
        assertError(get(SEMANTIC).param("q", "金融").param("pageSize", "101"), 400, "1000C0001");
        assertError(get(SEMANTIC).param("q", "金融").param("pageNum", "0"), 400, "1000C0001");

        final MvcResult exact64 = mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金".repeat(64)), "reader-s1", PROVIDER)).andReturn();
        assertThat(exact64.getResponse().getStatus()).as("q 恰好 64 字符合法").isEqualTo(200);

        final JsonNode blankCategory = payload(mockMvc.perform(auth(get(SEMANTIC)
                .param("q", "金融").param("categoryCode", "   "), "reader-s1", PROVIDER)).andReturn());
        assertThat(blankCategory.get("total").asLong()).as("空白类目视同未传").isEqualTo(1);

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
        assertThat(logCount("product_interaction_log")).as("资格拒绝零业务留痕").isZero();
        assertThat(logCount("product_action_log")).as("资格拒绝零业务留痕").isZero();
    }

    @Test
    void emptyCandidatesReturnEmptyPage() throws Exception {
        final JsonNode page = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(page.get("total").asLong()).as("无候选 → 空结果页").isZero();
        assertThat(page.get("list").size()).isZero();
    }

    @Test
    void productWithoutDatasetRowIsStillSearchable() throws Exception {
        final long productId = insertProduct("金融风控孤立资源产品", "风控模型", "LISTED",
                "finance", 999999L);
        final JsonNode page = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(idsOf(page)).as("无关联资源行（标签 null）仍可检索").contains(productId);
    }

    @Test
    void corruptedOrDimensionMismatchedCacheShouldBeRecomputed() throws Exception {
        final long datasetId = insertDataset("DSSEM-CACHE", "[\"金融\"]");
        final long shortBytesId = insertProduct("金融风控缓存短字节", "风控模型", "LISTED",
                "finance", datasetId);
        final long dimsMismatchId = insertProduct("金融风控缓存维度不符", "风控模型", "LISTED",
                "finance", datasetId);
        final String shortBytesHash = sourceHashOf("金融风控缓存短字节", "风控模型", "[\"金融\"]");
        final String dimsMismatchHash = sourceHashOf("金融风控缓存维度不符", "风控模型", "[\"金融\"]");
        jdbc.update("INSERT INTO product_embedding (product_id, source_hash, dims, vector) "
                + "VALUES (?, ?, ?, ?)", shortBytesId, shortBytesHash, 384, new byte[] {1, 2, 3, 4});
        jdbc.update("INSERT INTO product_embedding (product_id, source_hash, dims, vector) "
                + "VALUES (?, ?, ?, ?)", dimsMismatchId, dimsMismatchHash, 383,
                new byte[383 * Float.BYTES]);

        final JsonNode page = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(idsOf(page)).as("损坏/维度不符缓存按缺失重算").contains(shortBytesId, dimsMismatchId);
        assertThat(embeddingDims(shortBytesId)).isEqualTo(384);
        assertThat(embeddingDims(dimsMismatchId)).isEqualTo(384);
        assertThat(embeddingVectorLength(shortBytesId)).isEqualTo(384 * Float.BYTES);
        assertThat(embeddingVectorLength(dimsMismatchId)).isEqualTo(384 * Float.BYTES);
    }

    @Test
    void rankingTieBreaksByListedTimeThenIdAndStaysStable() throws Exception {
        final long datasetId = insertDataset("DSSEM-TIE", "[\"金融\"]");
        final Timestamp earlier = Timestamp.valueOf(LocalDateTime.now().minusHours(2));
        final Timestamp later = Timestamp.valueOf(LocalDateTime.now().minusHours(1));
        // 上架时间与 id 反向：若实现仅按 id 降序，本用例将失败
        final long laterListedId = insertProduct("金融风控同分产品", "风控模型", "LISTED", "finance",
                datasetId, "provider-a", later);
        final long earlierListedId = insertProduct("金融风控同分产品", "风控模型", "LISTED", "finance",
                datasetId, "provider-b", earlier);

        final JsonNode firstPage = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(idsOf(firstPage)).as("同分按上架时间降序（与 id 反向）")
                .containsExactly(laterListedId, earlierListedId);

        final JsonNode repeatPage = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(idsOf(repeatPage)).as("重复请求排序稳定")
                .containsExactly(laterListedId, earlierListedId);

        jdbc.update("UPDATE data_product SET listed_at = ? WHERE id IN (?, ?)", later, laterListedId,
                earlierListedId);
        final JsonNode sameTimePage = payload(mockMvc.perform(auth(
                get(SEMANTIC).param("q", "金融风控"), "reader-s1", PROVIDER)).andReturn());
        assertThat(idsOf(sameTimePage)).as("上架时间相同按产品 id 降序")
                .containsExactly(earlierListedId, laterListedId);
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
        return insertProduct(productName, intro, status, categoryCode, datasetId, "provider-s",
                "LISTED".equals(status) ? Timestamp.valueOf(LocalDateTime.now()) : null);
    }

    private long insertProduct(final String productName, final String intro, final String status,
            final String categoryCode, final long datasetId, final String provider,
            final Timestamp listedAt) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                        + "provider_subject_no, dataset_id, category_code, listed_at) "
                        + "VALUES (?, ?, 'DATASET', 'FREE', ?, ?, ?, ?, ?)",
                productName, intro, status, provider, datasetId, categoryCode, listedAt);
        return jdbc.queryForObject("SELECT id FROM data_product WHERE provider_subject_no = ? "
                + "AND product_name = ?", Long.class, provider, productName);
    }

    private long embeddingCount(final long productId) {
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_embedding WHERE product_id = ?", Long.class, productId);
        return count == null ? 0L : count;
    }

    private int embeddingDims(final long productId) {
        return jdbc.queryForObject(
                "SELECT dims FROM product_embedding WHERE product_id = ?", Integer.class, productId);
    }

    private long embeddingVectorLength(final long productId) {
        final Long length = jdbc.queryForObject(
                "SELECT LENGTH(vector) FROM product_embedding WHERE product_id = ?", Long.class,
                productId);
        return length == null ? 0L : length;
    }

    private long logCount(final String table) {
        final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static String sourceHashOf(final String productName, final String intro,
            final String semanticTags) {
        final String sourceText = productName + "\n" + intro + "\n" + semanticTags;
        return EmbeddingTexts.sourceHash(EmbeddingTexts.canonicalize(sourceText));
    }

    private static List<String> namesOf(final JsonNode page) {
        final List<String> names = new ArrayList<>();
        page.get("list").forEach(node -> names.add(node.get("productName").asText()));
        return names;
    }

    private static List<Long> idsOf(final JsonNode page) {
        final List<Long> ids = new ArrayList<>();
        page.get("list").forEach(node -> ids.add(node.get("productId").asLong()));
        return ids;
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
