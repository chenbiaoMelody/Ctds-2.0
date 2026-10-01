package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ctds.catalog.domain.CategoryNode;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.SpaceMembership;
import com.ctds.catalog.domain.SpaceMembershipPort;
import com.ctds.catalog.domain.SubjectAdmission;
import com.ctds.catalog.domain.SubjectAdmissionPort;
import com.ctds.catalog.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 类目成员校验集成测试（WBS-3.3.4 hifi §5 T5；3.3.2 移交"类目树校验"兑现 + Q2-A）：
 * 登记（W1）/变更（W2）路径插入校验的正反向（含全半角/首尾空白写法归一化命中）、零副作用三面
 * （零资源行/零留痕/零取号）、双非法优先序锚（词表 1007C0009 先行——插入点或组内顺序回退本例必红）、
 * 变更路径无部分写入、历史数据零回填锚、幂等重放携非法申报回放首次结果、校验链顺序锚、
 * CategoryPort 契约对生产仓储实跑。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1+V2+V3（种子 24 条类目随迁移落库）；跨服务判定端口
 * {@code @MockitoBean}（沿 3.3.2/3.3.3 先例）；本机 Docker 未运行时整类跳过（门禁不红）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogCategoryMembershipIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT_TYPE = MediaType.APPLICATION_JSON_VALUE;
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_category_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CategoryPort categoryPort;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @MockitoBean
    private SpaceMembershipPort spaceMembershipPort;

    @BeforeEach
    void defaultsAdmittedAndActiveMember() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
    }

    // ==== 正向：种子类目命中；全半角/首尾空白写法归一化命中同一类目 ====

    @Test
    void registerAcceptsSeedCategoryWrittenInIrregularForms() throws Exception {
        final long spaceId = 5101L;
        final MvcResult plain = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t5a", PROVIDER).contentType(CONTENT_TYPE)
                        .content(registerBody("类目正向数据集", "金融")))
                .andReturn();
        assertThat(plain.getResponse().getStatus()).as(body(plain)).isEqualTo(200);
        assertThat(payload(plain).get("declareCategory").asText()).as("落库存申报原文").isEqualTo("金融");

        // 全角空白 + 首尾空白写法：归一化后命中同一类目（DatasetNameNormalizer 单一口径）
        final MvcResult irregular = mockMvc.perform(
                        auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"), "owner-t5a", PROVIDER)
                                .contentType(CONTENT_TYPE).content(registerBody("类目异写数据集", "　金融　")))
                .andReturn();
        assertThat(irregular.getResponse().getStatus())
                .describedAs("全角/首尾空白写法归一化命中——响应体=%s", body(irregular)).isEqualTo(200);
        assertThat(payload(irregular).get("declareCategory").asText())
                .as("落库存申报原文（不做改写）").isEqualTo("　金融　");
    }

    // ==== 反向（核心锚）：非受控类目拒绝且零副作用三面、文案不回显申报原文 ====

    @Test
    void registerWithUnknownCategoryRejectedWithoutAnySideEffect() throws Exception {
        final long spaceId = 5102L;
        final long seqBefore = dailySeqTotal();
        final MvcResult denied = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t5b", PROVIDER).contentType(CONTENT_TYPE)
                        .content(registerBody("越界类目数据集", "火星类目")))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(denied)).isEqualTo("1007C0014");
        // 零副作用三面：无资源行、无留痕、无取号（非法入参不触碰库内状态，沿 3.3.3 第 6′ 步口径）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = ?", Integer.class,
                spaceId)).as("零资源行").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE space_id = ?",
                Integer.class, spaceId)).as("零留痕").isZero();
        assertThat(dailySeqTotal()).as("零取号").isEqualTo(seqBefore);
        // 对外文案为服务端常量，不回显申报原文（沿 1007C0009 文案口径）
        assertThat(root(denied).get("message").asText()).isEqualTo("分类申报不在平台受控类目范围内");
        assertThat(body(denied)).doesNotContain("火星类目");
    }

    // ==== 双非法优先序锚：语义标签与类目双非法 → 词表码 1007C0009 先行（组内顺序回退本例必红）====

    @Test
    void doubleInvalidInputPrefersVocabularyCodeOverCategoryCode() throws Exception {
        final long spaceId = 5103L;
        final MvcResult denied = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t5c", PROVIDER).contentType(CONTENT_TYPE)
                        .content("{\"name\":\"双非法数据集\",\"type\":\"DATASET\",\"intro\":\"资源简介\","
                                + "\"tags\":[\"火星数据\"],\"declareCategory\":\"火星类目\","
                                + "\"declareLevel\":\"L2\"}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus())
                .as("组内顺序 = 词表校验先、类目校验后（hifi §4.1，不扰动 3.3.3 错误码优先序）")
                .isEqualTo(400);
        assertThat(codeOf(denied)).as("词表码先行").isEqualTo("1007C0009");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = ?", Integer.class,
                spaceId)).as("零资源行").isZero();
    }

    // ==== 变更路径（W2）：非受控分类申报拒绝 + 无部分写入 + 零留痕；合法申报正常变更 ====

    @Test
    void updateWithUnknownCategoryRejectedAndExistingRowUntouched() throws Exception {
        final long datasetId = registerOk("owner-t5d", 5104L, "变更类目数据集", "金融");
        final MvcResult denied = mockMvc.perform(auth(put("/api/v1/datasets/" + datasetId), "owner-t5d",
                        PROVIDER).contentType(CONTENT_TYPE)
                        .content("{\"declareCategory\":\"火星类目\"}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(denied)).isEqualTo("1007C0014");
        assertThat(jdbc.queryForObject("SELECT declare_category FROM dataset WHERE id = ?", String.class,
                datasetId)).as("无部分写入——既有申报不变").isEqualTo("金融");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                + "AND action IN ('UPDATE', 'DENIED_UPDATE')", Integer.class, datasetId))
                .as("被拒变更零留痕（类目校验无 DENIED 变体，沿词表同款）").isZero();

        // 合法申报（种子类目）正常变更并留痕 from→to
        final MvcResult updated = mockMvc.perform(auth(put("/api/v1/datasets/" + datasetId), "owner-t5d",
                        PROVIDER).contentType(CONTENT_TYPE).content("{\"declareCategory\":\"交通运输\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).as(body(updated)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                        + "AND action = 'UPDATE' AND from_value = ? AND to_value = ?",
                Integer.class, datasetId, "declareCategory:金融", "declareCategory:交通运输")).isEqualTo(1);
    }

    // ==== 校验链顺序锚：资格门槛先于类目校验（未入驻 + 非类目 → 1007C0006 而非 1007C0014）====

    @Test
    void categoryCheckRunsAfterAdmissionGate() throws Exception {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult denied = mockMvc.perform(auth(post("/api/v1/data-spaces/5105/datasets"),
                        "owner-t5e", PROVIDER).contentType(CONTENT_TYPE)
                        .content(registerBody("未入驻越界类目数据集", "火星类目")))
                .andReturn();
        assertThat(denied.getResponse().getStatus())
                .as("资格门槛先于类目校验（hifi §4.1 链序，防枚举同形不被破坏）").isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1007C0006");
    }

    // ==== 历史数据零回填锚：存量申报值不在种子内 → 既有行为不变（不携带分类字段不触发校验）====

    @Test
    void legacyDatasetWithOutOfTreeCategoryStillUpdatableWithoutCategoryField() throws Exception {
        // Q6-A 理论不可达（新写入已全部校验），直插构造存量行验证"只作用新写入、不回填历史"
        final long spaceId = 5106L;
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, type, "
                        + "intro, semantic_tags, declare_category, declare_level, declare_important, status) "
                        + "VALUES ('DS20260930000097', ?, 'owner-t5f', '类目外存量数据集', '类目外存量数据集', "
                        + "'DATASET', '简介', '[\"金融\"]', '火星类目', 'L2', 0, 'ACTIVE')", spaceId);
        final long datasetId = jdbc.queryForObject(
                "SELECT id FROM dataset WHERE data_no = 'DS20260930000097'", Long.class);
        final MvcResult updated = mockMvc.perform(auth(put("/api/v1/datasets/" + datasetId), "owner-t5f",
                        PROVIDER).contentType(CONTENT_TYPE).content("{\"intro\":\"新简介\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).as(body(updated))
                .as("缺省分类字段不触发类目校验（历史申报值不被回填清洗）").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT declare_category FROM dataset WHERE id = ?", String.class,
                datasetId)).as("既有申报保持不变").isEqualTo("火星类目");
    }

    // ==== 幂等重放锚：幂等键命中重放首次结果，重放请求的非法申报不再触发校验 ====

    @Test
    void idempotentReplayWithInvalidCategoryStillReturnsFirstResult() throws Exception {
        // 幂等键命中（空间+主体+归一化名）→ 按 ADR-007 模式 B 回放首次结果：
        // 类目校验位于幂等切面之内（hifi §4.1 插入点），重放请求的非法申报不再触发校验
        final long spaceId = 5107L;
        final MvcResult first = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t5g", PROVIDER).contentType(CONTENT_TYPE)
                        .content(registerBody("幂等重放类目数据集", "金融")))
                .andReturn();
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        final MvcResult replay = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t5g", PROVIDER).contentType(CONTENT_TYPE)
                        .content(registerBody("幂等重放类目数据集", "火星类目")))
                .andReturn();
        assertThat(replay.getResponse().getStatus()).as("重放回首次结果而非 400").isEqualTo(200);
        assertThat(payload(replay).path("id").asLong()).isEqualTo(payload(first).path("id").asLong());
        assertThat(payload(replay).path("declareCategory").asText())
                .as("回放为首次登记快照（首次结果原样返回）").isEqualTo("金融");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = ?", Integer.class,
                spaceId)).as("仍只有首次那一行").isEqualTo(1);
    }

    // ==== CategoryPort 契约对生产仓储实跑（替代领域桩——桩对唯一生产实现零证伪力，沿 3.3.3 先例）====

    @Test
    void categoryPortContractOnProductionRepository() {
        assertThat(categoryPort.existsByNormalizedName("金融"))
                .as("种子一级类目命中").isTrue();
        assertThat(categoryPort.existsByNormalizedName("　金融　"))
                .as("全角/首尾空白写法归一化命中（DatasetNameNormalizer 单一口径）").isTrue();
        assertThat(categoryPort.existsByNormalizedName("火星类目")).isFalse();
        assertThat(categoryPort.existsByCode("finance")).isTrue();
        assertThat(categoryPort.existsByCode("no-such-category")).isFalse();

        final List<String> financeSubtree = categoryPort.selfAndDescendantCodes("finance");
        assertThat(financeSubtree).as("子树展开 = 自身 + 直接子级（2 级树）")
                .containsExactlyInAnyOrder("finance", "finance-banking", "finance-inclusive");
        assertThat(categoryPort.selfAndDescendantCodes("finance-inclusive"))
                .as("叶子类目子树 = 自身").containsExactly("finance-inclusive");

        final List<CategoryNode> all = categoryPort.listAll();
        assertThat(all).as("种子 24 条全量").hasSize(24);
        assertThat(all.stream().filter(CategoryNode::isTopLevel).count()).isEqualTo(8);
    }

    // ==== R5 前置锚：类目树读面挂在 catalog.read 权限点下（本卡权限点接线随 T2/T3 批次验证全链）====

    @Test
    void categoryTreeEndpointAccessibleWithReadPermission() throws Exception {
        final MvcResult result = mockMvc.perform(auth(get("/api/v1/catalog/categories"), "reader-t5",
                PROVIDER)).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        final JsonNode tree = payload(result);
        assertThat(tree.size()).as("2 级树：8 个一级节点").isEqualTo(8);
        assertThat(tree.get(0).get("categoryCode").asText()).as("同级按 sort_order 升序").isEqualTo("transport");
    }

    // ==== 助手 ====

    private long registerOk(final String subject, final long spaceId, final String name,
            final String declareCategory) throws Exception {
        final MvcResult result = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                subject, PROVIDER).contentType(CONTENT_TYPE).content(registerBody(name, declareCategory)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return payload(result).get("id").asLong();
    }

    private static String registerBody(final String name, final String declareCategory) {
        return "{\"name\":\"" + name + "\",\"type\":\"DATASET\",\"intro\":\"资源简介\","
                + "\"tags\":[\"金融\"],\"declareCategory\":\"" + declareCategory + "\","
                + "\"declareLevel\":\"L2\"}";
    }

    /** 当日已取号总数（"零取号"断言的输入：值不变 = 没有消耗任何序号）。 */
    private long dailySeqTotal() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(seq_value), 0) FROM dataset_no_seq "
                + "WHERE seq_date = CURDATE()", Long.class);
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
