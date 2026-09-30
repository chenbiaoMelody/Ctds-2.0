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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
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
 * 受控词表（语义标签采集载体）集成测试（WBS-3.3.3 hifi §5 T2~T7；规格 C-3.1 行为 1 规则 3
 * 「受控词表选取」、行为 2 规则 1、行为 7 规则 1/5）：读面 R3/R4（清单/分页/keyword/边界/401/403/
 * 未知册 404）、写面成员校验正反向（T4/T5：含全半角与空白写法命中同词条、词表外标签拒绝且零副作用）、
 * 变更联动与无部分写入（T6）、既有演示数据兼容（T7）。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1+V2（种子 12 词条随迁移落库）；跨服务判定端口 @MockitoBean
 * （沿 3.3.2 先例）；本机 Docker 未运行时整类跳过（门禁不红）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogTagVocabularyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT_TYPE = MediaType.APPLICATION_JSON_VALUE;
    private static final String VOCABULARIES = "/api/v1/tag-vocabularies";
    private static final String PROVIDER = "provider";

    private static Path auditDir;

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_vocab_it");
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
    }

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-catalog-vocab");
    }

    @AfterAll
    static void deleteAuditDir() throws Exception {
        try (Stream<Path> paths = Files.walk(auditDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
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
    void defaultsAdmittedAndActiveMember() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
        given(spaceMembershipPort.check(anyLong(), any()))
                .willReturn(SpaceMembership.of("ACTIVE", "MEMBER"));
    }

    // ==== T2 读面：词表册清单 + 词条分页 + keyword 过滤（行为 7 规则 5）====

    @Test
    void vocabularyListAndTermPagingAreStableAndMetadataOnly() throws Exception {
        final JsonNode list = payload(mockMvc.perform(auth(get(VOCABULARIES), "reader-t2", PROVIDER))
                .andReturn());
        assertThat(list.size()).as("本版恒 1 册").isEqualTo(1);
        assertThat(list.get(0).get("vocabularyCode").asText()).isEqualTo("SEMANTIC_TAG");
        assertThat(list.get(0).properties().stream().map(java.util.Map.Entry::getKey).toList())
                .as("册出站字段集").containsExactlyInAnyOrder("vocabularyCode", "vocabularyName");

        final JsonNode page = payload(mockMvc.perform(
                        auth(get(VOCABULARIES + "/SEMANTIC_TAG/terms"), "reader-t2", PROVIDER)
                                .param("pageNum", "1").param("pageSize", "5"))
                .andReturn());
        assertThat(page.get("total").asLong()).as("种子 12 条").isEqualTo(12);
        assertThat(page.get("pageNum").asInt()).isEqualTo(1);
        assertThat(page.get("pageSize").asInt()).isEqualTo(5);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page.get("list").size()).isEqualTo(5);
        // 稳定排序 = term_code 升序（跨页结果不漂移）
        assertThat(page.get("list").get(0).get("termCode").asText()).isEqualTo("TT0001");
        assertThat(page.get("list").get(4).get("termCode").asText()).isEqualTo("TT0005");
        assertThat(page.get("list").get(0).properties().stream().map(java.util.Map.Entry::getKey).toList())
                .as("词条出站仅元数据（行为 7 规则 5）").containsExactlyInAnyOrder("termCode", "termName");
    }

    @Test
    void keywordFilterMatchesByNameAndReturnsEmptyListWhenNoHit() throws Exception {
        final JsonNode hit = payload(mockMvc.perform(
                        auth(get(VOCABULARIES + "/SEMANTIC_TAG/terms"), "reader-t2b", PROVIDER)
                                .param("keyword", "金融"))
                .andReturn());
        assertThat(hit.get("total").asLong()).isEqualTo(1);
        assertThat(hit.get("list").get(0).get("termName").asText()).isEqualTo("金融");

        final JsonNode miss = payload(mockMvc.perform(
                        auth(get(VOCABULARIES + "/SEMANTIC_TAG/terms"), "reader-t2b", PROVIDER)
                                .param("keyword", "词表中不存在的标签"))
                .andReturn());
        assertThat(miss.get("total").asLong()).as("词表存在但无命中 = 200 空列表（与 404 可分辨）").isZero();
        assertThat(miss.get("list").size()).isZero();
    }

    @Test
    void pagingParametersOutOfRangeRejected() throws Exception {
        final MvcResult tooLarge = mockMvc.perform(
                        auth(get(VOCABULARIES + "/SEMANTIC_TAG/terms"), "reader-t2c", PROVIDER)
                                .param("pageSize", "101"))
                .andReturn();
        assertThat(tooLarge.getResponse().getStatus()).as("pageSize 超上限 → 400").isEqualTo(400);
        final MvcResult zeroPage = mockMvc.perform(
                        auth(get(VOCABULARIES + "/SEMANTIC_TAG/terms"), "reader-t2c", PROVIDER)
                                .param("pageNum", "0"))
                .andReturn();
        assertThat(zeroPage.getResponse().getStatus()).as("pageNum < 1 → 400").isEqualTo(400);
    }

    // ==== T3 读面边界：未知册 404 / 未认证 401 / 无权限 403（行为 7 规则 1）====

    @Test
    void unknownVocabularyIsNotFoundWhileAnonymousAndUnauthorizedAreDistinct() throws Exception {
        final MvcResult unknown = mockMvc.perform(
                        auth(get(VOCABULARIES + "/NO_SUCH_BOOK/terms"), "reader-t3", PROVIDER))
                .andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(unknown)).isEqualTo("1007C0010");
        assertThat(root(unknown).get("message").asText())
                .isEqualTo("受控词表册不存在");

        final MvcResult anonymous = mockMvc.perform(get(VOCABULARIES)).andReturn();
        assertThat(anonymous.getResponse().getStatus()).as("未认证 → 401").isEqualTo(401);

        final MvcResult forbidden = mockMvc.perform(auth(get(VOCABULARIES), "reader-t3", "guest"))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus())
                .as("无 vocabulary.read 角色 → 403").isEqualTo(403);
    }

    // ==== T4 成员校验正向：全触发写法命中同一词条，落库为提交原文 ====

    @Test
    void registerAcceptsVocabularyTagsWrittenInIrregularForms() throws Exception {
        final long spaceId = 4001L;
        final String body = registerBody("受控标签数据集", "[\"金融\", \"　普惠　\", \"风控\"]");
        final MvcResult result = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t4", PROVIDER).contentType(CONTENT_TYPE).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        final JsonNode data = payload(result);
        assertThat(data.get("tags").get(0).asText()).as("落库为提交原文").isEqualTo("金融");
        assertThat(data.get("dataNo").asText()).matches("^DS\\d{14}$");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE space_id = ? "
                        + "AND action = 'REGISTER' AND result = 'SUCCESS'", Integer.class, spaceId))
                .as("REGISTER 留痕一行").isEqualTo(1);
    }

    // ==== T5 成员校验反向（核心锚）：词表外标签拒绝且零副作用、文案不回显原文 ====

    @Test
    void registerWithUnknownTagRejectedWithoutAnySideEffect() throws Exception {
        final long spaceId = 4002L;
        final long seqBefore = dailySeqTotal();
        final MvcResult denied = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                        "owner-t5", PROVIDER).contentType(CONTENT_TYPE)
                        .content(registerBody("越界标签数据集", "[\"金融\", \"火星数据\"]")))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(denied)).isEqualTo("1007C0009");
        // 零副作用：无资源行、无留痕、无取号（非法入参不触碰库内状态）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset WHERE space_id = ?", Integer.class,
                spaceId)).as("零资源行").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE space_id = ?",
                Integer.class, spaceId)).as("零留痕").isZero();
        assertThat(dailySeqTotal()).as("零取号").isEqualTo(seqBefore);
        // 对外文案为服务端常量，不回显被拒标签原文
        assertThat(root(denied).get("message").asText()).isEqualTo("语义标签不在受控词表范围内");
        assertThat(body(denied)).doesNotContain("火星数据");
    }

    @Test
    void updateWithUnknownTagRejectedAndExistingRowUntouched() throws Exception {
        final long datasetId = registerOk("owner-t5b", 4003L, "既有标签数据集", "[\"金融\"]");
        final MvcResult denied = mockMvc.perform(auth(put("/api/v1/datasets/" + datasetId), "owner-t5b",
                        PROVIDER).contentType(CONTENT_TYPE).content("{\"tags\":[\"风控\",\"火星数据\"]}"))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(denied)).isEqualTo("1007C0009");
        assertThat(jdbc.queryForObject("SELECT semantic_tags FROM dataset WHERE id = ?", String.class,
                datasetId)).as("无部分写入——既有标签不变").isEqualTo("[\"金融\"]");
    }

    // ==== T6 变更联动：合法集变更留痕 from→to 逐字 ====

    @Test
    void updateWithValidTagsWritesFromToLog() throws Exception {
        final long datasetId = registerOk("owner-t6", 4004L, "变更标签数据集", "[\"金融\",\"普惠\"]");
        final MvcResult updated = mockMvc.perform(auth(put("/api/v1/datasets/" + datasetId), "owner-t6",
                        PROVIDER).contentType(CONTENT_TYPE)
                        .content("{\"tags\":[\"风控\",\"信用\"]}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).as(body(updated)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                        + "AND action = 'UPDATE' AND result = 'SUCCESS' AND from_value = ? AND to_value = ?",
                Integer.class, datasetId, "semanticTags:金融,普惠", "semanticTags:风控,信用"))
                .as("留痕 from→to 逐字").isEqualTo(1);
    }

    // ==== T7 既有演示数据兼容：3.3.2 走查同款记录（金融/普惠/风控）可变更 ====

    @Test
    void legacyWalkthroughDatasetCanStillBeUpdated() throws Exception {
        final long spaceId = 4005L;
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, type, "
                        + "intro, semantic_tags, declare_category, declare_level, declare_important, status) "
                        + "VALUES ('DS20260930000099', ?, 'owner-t7', '走查遗留数据集', '走查遗留数据集', 'DATASET', "
                        + "'简介', '[\"金融\",\"普惠\",\"风控\"]', '金融', 'L2', 0, 'ACTIVE')",
                spaceId);
        final long datasetId = jdbc.queryForObject("SELECT id FROM dataset WHERE data_no = 'DS20260930000099'",
                Long.class);
        final MvcResult updated = mockMvc.perform(auth(put("/api/v1/datasets/" + datasetId), "owner-t7",
                        PROVIDER).contentType(CONTENT_TYPE).content("{\"tags\":[\"信用\",\"政务\"]}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).as(body(updated)).as("种子集覆盖既有演示标签 → 变更通过")
                .isEqualTo(200);
        assertThat(payload(updated).get("tags").get(0).asText()).isEqualTo("信用");
    }

    // ==== 助手 ====

    private long registerOk(final String subject, final long spaceId, final String name, final String tags)
            throws Exception {
        final MvcResult result = mockMvc.perform(auth(post("/api/v1/data-spaces/" + spaceId + "/datasets"),
                subject, PROVIDER).contentType(CONTENT_TYPE).content(registerBody(name, tags))).andReturn();
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return payload(result).get("id").asLong();
    }

    private static String registerBody(final String name, final String tags) {
        return "{\"name\":\"" + name + "\",\"type\":\"DATASET\",\"intro\":\"资源简介\",\"tags\":" + tags
                + ",\"declareCategory\":\"金融\",\"declareLevel\":\"L2\"}";
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
