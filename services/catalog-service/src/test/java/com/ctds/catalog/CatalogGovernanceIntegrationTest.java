package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * 治理例外与债务收口集成测试（WBS-3.3.5 hifi §5 T10；规格行为 7 规则 3 + DB-34/DB-37/DB-38 承接）：
 * admin 治理详情两侧（产品 R12 / 资源 R13）允许且 GOVERNANCE_VIEW 留痕（C-3.2 剧本 S3-3 判定面）、
 * 非运营方拒绝且零留痕（DB-29 Q4-A 负向锚）、未上架对象治理可读、R8 订阅者可见值域过滤
 * （DENIED_ 前缀拒绝码与 GOVERNANCE_VIEW 不暴露——Q5-A）、DB-34 并发幂等窗口收口（预插行后
 * 收藏不 500）、DB-38 补测份额（CANCELLED 产品新发起收藏专属用例）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogGovernanceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCTS = "/api/v1/data-products";
    private static final String PROVIDER = "provider";
    private static final String ADMIN = "admin";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_gov_it");
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

    // ==== R12：admin 治理详情（未上架对象可读）+ GOVERNANCE_VIEW 留痕（S3-3 判定面）====

    @Test
    void adminGovernanceProductViewLogged() throws Exception {
        final long productId = insertProduct("治理产品", "DRAFT", null);
        final MvcResult ok = mockMvc.perform(auth(
                        get(PRODUCTS + "/" + productId + "/governance"), "admin-g1", ADMIN))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("status").asText()).as("未上架对象治理可读").isEqualTo("未上架");
        assertThat(payload(ok).has("priceAmount")).as("治理视图含定价字段（全量治理信息）").isTrue();
        assertThat(payload(ok).has("datasetId")).as("治理视图含来源资源指向").isTrue();
        final Integer views = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ? "
                        + "AND action = 'GOVERNANCE_VIEW' AND operator_subject_no = 'admin-g1'",
                Integer.class, productId);
        assertThat(views).as("治理查看留痕（谁/何时/对象/动作四要素）").isEqualTo(1);
    }

    // ==== R13：admin 资源治理详情 + dataset_action_log 留痕（S3-3"全部产品与资源"）====

    @Test
    void adminGovernanceDatasetViewLogged() throws Exception {
        final long datasetId = insertDataset("治理资源", "provider-g2");
        final MvcResult ok = mockMvc.perform(auth(
                        get("/api/v1/datasets/" + datasetId + "/governance"), "admin-g2", ADMIN))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("dataNo").asText()).startsWith("DS");
        final Integer views = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ? "
                        + "AND action = 'GOVERNANCE_VIEW' AND actor_subject_no = 'admin-g2'",
                Integer.class, datasetId);
        assertThat(views).as("资源侧治理查看留痕（Q5-A 两侧齐备）").isEqualTo(1);
    }

    // ==== 非运营方治理端点拒绝且零留痕（DB-29 Q4-A 负向锚）====

    @Test
    void nonAdminGovernanceDeniedWithoutLog() throws Exception {
        final long productId = insertProduct("越权治理产品", "DRAFT", null);
        final long datasetId = insertDataset("越权治理资源", "provider-g3");
        final MvcResult productDenied = mockMvc.perform(auth(
                        get(PRODUCTS + "/" + productId + "/governance"), "provider-g3", PROVIDER))
                .andReturn();
        assertThat(productDenied.getResponse().getStatus()).as(body(productDenied)).isEqualTo(403);
        final MvcResult datasetDenied = mockMvc.perform(auth(
                        get("/api/v1/datasets/" + datasetId + "/governance"), "provider-g3", PROVIDER))
                .andReturn();
        assertThat(datasetDenied.getResponse().getStatus()).isEqualTo(403);
        final Integer productViews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ?", Integer.class,
                productId);
        final Integer datasetViews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ?", Integer.class,
                datasetId);
        assertThat(productViews).as("非运营方零产品侧留痕").isZero();
        assertThat(datasetViews).as("非运营方零资源侧留痕").isZero();
    }

    // ==== R8 可见值域过滤（Q5-A：DENIED_*/GOVERNANCE_VIEW 不对订阅者暴露）====

    @Test
    void changeLogsHideDeniedAndGovernanceEntries() throws Exception {
        final long productId = insertProduct("留痕过滤产品", "LISTED", LocalDateTime.now());
        insertSubscription("reader-g4", productId);
        insertActionLog(productId, "UPDATE", "定价变更:3→5", "provider-g", Timestamp.valueOf(
                LocalDateTime.of(2026, 10, 2, 9, 0)));
        insertActionLog(productId, "DENIED_UPDATE", null, "intruder-g", Timestamp.valueOf(
                LocalDateTime.of(2026, 10, 2, 10, 0)));
        insertActionLog(productId, "GOVERNANCE_VIEW", null, "admin-g", Timestamp.valueOf(
                LocalDateTime.of(2026, 10, 2, 11, 0)));

        final MvcResult ok = mockMvc.perform(auth(get(PRODUCTS + "/" + productId + "/change-logs"),
                        "reader-g4", PROVIDER))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode page = payload(ok);
        assertThat(page.get("total").asLong()).as("订阅者仅见变更类六码").isEqualTo(1);
        assertThat(page.get("list").get(0).get("action").asText()).isEqualTo("UPDATE");
    }

    // ==== DB-34 收口：预插行后收藏 → 幂等成功非 500 ====

    @Test
    void duplicateKeyOnFavoriteReplaysFirstResult() throws Exception {
        final long productId = insertProduct("幂等收口产品", "LISTED", LocalDateTime.now());
        jdbc.update("INSERT INTO product_favorite (subject_no, product_id) VALUES ('reader-g5', ?)",
                productId);
        final MvcResult ok = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/favorite"), "reader-g5", PROVIDER))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok))
                .as("DB-34：并发唯一键命中转幂等重放，不 500").isEqualTo(200);
        final Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorite WHERE subject_no = 'reader-g5' "
                        + "AND product_id = ?", Integer.class, productId);
        assertThat(count).as("收藏数不变（幂等语义）").isEqualTo(1);
    }

    // ==== DB-38 补测份额：CANCELLED 产品新发起收藏专属用例 ====

    @Test
    void cancelledProductNewFavoriteRejected() throws Exception {
        final long productId = insertProduct("已注销新藏产品", "CANCELLED", null);
        final Long logsBefore = countInteractionLogs();
        final MvcResult denied = mockMvc.perform(auth(
                        post(PRODUCTS + "/" + productId + "/favorite"), "reader-g6", PROVIDER))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(404);
        assertThat(codeOf(denied)).as("DB-38：CANCELLED 新发起被拒（防枚举同形）")
                .isEqualTo("1007C0011");
        assertThat(countInteractionLogs()).as("拒绝留痕（独立提交）").isEqualTo(logsBefore + 1);
    }

    // ==== 助手 ====

    private Long countInteractionLogs() {
        final Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_interaction_log", Long.class);
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

    private long insertProduct(final String name, final String status, final LocalDateTime listedAt) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, "
                        + "status, provider_subject_no, dataset_id, listed_at) "
                        + "VALUES (?, '测试简介', 'DATASET', 'FREE', ?, 'provider-g', 1, ?)",
                name, status, listedAt == null ? null : Timestamp.valueOf(listedAt));
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                name);
    }

    private void insertSubscription(final String subject, final long productId) {
        jdbc.update("INSERT INTO product_subscription (subject_no, product_id) VALUES (?, ?)",
                subject, productId);
    }

    private void insertActionLog(final long productId, final String action, final String summary,
            final String operator, final Timestamp createdAt) {
        jdbc.update("INSERT INTO product_action_log (product_id, action, operator_subject_no, "
                        + "summary, created_at) VALUES (?, ?, ?, ?, ?)",
                productId, action, operator, summary, createdAt);
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
