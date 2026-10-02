package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
 * 订阅感知集成测试（WBS-3.3.4 hifi §5 T4；规格行为 6 规则 2）：
 * 订阅者可查产品变更留痕（product_action_log 测试自造行——写面归 3.3.5）、按 created_at 倒序、
 * 出站字段白名单（action/summary/operatorSubjectNo/createdAt——不含敏感原文）、非订阅者与产品
 * 不存在同形拒绝（1007C0011 同码同文案逐字对照，不暴露订阅关系与产品存在性）、订阅者但无留痕
 * = 200 空页（载体表空天然空页，非错误）。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1+V2+V3；资格端口 {@code @MockitoBean}；无 Docker 整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductChangeLogIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_changelog_it");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SubjectAdmissionPort admissionPort;

    @BeforeEach
    void defaultsAdmitted() {
        given(admissionPort.check(any())).willReturn(SubjectAdmission.ADMITTED);
    }

    // ==== 行为 6 规则 2：订阅者可查变更留痕（倒序 + 字段白名单 + 不含敏感原文）====

    @Test
    void subscriberCanReadChangeLogsInReverseOrder() throws Exception {
        final long productId = insertListedProduct("变更留痕产品");
        insertSubscription("reader-c1", productId);
        insertActionLog(productId, "PUBLISH", "产品已上架", "provider-c", LocalDateTime.of(2026, 10, 1, 9, 0));
        insertActionLog(productId, "UPDATE", "简介变更:旧→新", "provider-c",
                LocalDateTime.of(2026, 10, 1, 10, 0));
        insertActionLog(productId, "DELIST", "产品已下架", "provider-c",
                LocalDateTime.of(2026, 10, 1, 11, 0));

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/change-logs"), "reader-c1", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode page = payload(ok);
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("list").get(0).get("action").asText())
                .as("按 created_at 倒序：最新在前").isEqualTo("DELIST");
        assertThat(page.get("list").get(2).get("action").asText())
                .as("值域定稿后自造行沿 PUBLISH（WBS-3.3.5 V4 动作码登记）").isEqualTo("PUBLISH");
        assertThat(page.get("list").get(0).properties().stream()
                .map(java.util.Map.Entry::getKey).toList())
                .as("R8 出站字段白名单（四要素；不含敏感原文与数据本体）")
                .containsExactlyInAnyOrder("action", "summary", "operatorSubjectNo", "createdAt");
        assertThat(body(ok)).as("留痕摘要仅业务要素").doesNotContain("身份证").doesNotContain("手机号");
    }

    // ==== 行为 6 规则 2 + 行为 7 规则 2：非订阅者与不存在同形拒绝（防枚举）====

    @Test
    void nonSubscriberAndMissingProductRejectedSameShape() throws Exception {
        final long productId = insertListedProduct("非订阅者探针产品");
        insertSubscription("reader-c2-other", productId);
        insertActionLog(productId, "PUBLISH", "产品已上架", "provider-c", LocalDateTime.now());

        final MvcResult nonSubscriber = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/change-logs"), "reader-c2", PROVIDER)).andReturn();
        assertThat(nonSubscriber.getResponse().getStatus()).as("非订阅者 → 404").isEqualTo(404);
        assertThat(codeOf(nonSubscriber)).isEqualTo("1007C0011");
        assertThat(root(nonSubscriber).get("message").asText()).isEqualTo("产品不存在或未在架");

        final MvcResult missing = mockMvc.perform(auth(get("/api/v1/data-products/999999/change-logs"),
                "reader-c2", PROVIDER)).andReturn();
        assertThat(missing.getResponse().getStatus()).as("产品行不存在 → 同码同文案").isEqualTo(404);
        assertThat(codeOf(missing)).isEqualTo("1007C0011");
        assertThat(root(missing).get("message").asText())
                .as("两种拒绝逐字一致（不暴露订阅关系与产品存在性）").isEqualTo("产品不存在或未在架");
    }

    // ==== 订阅者 + 无留痕 = 200 空页（载体表空天然空页，hifi §10.4）====

    @Test
    void subscriberWithoutLogsGetsEmptyPage() throws Exception {
        final long productId = insertListedProduct("空留痕产品");
        insertSubscription("reader-c3", productId);
        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/change-logs"), "reader-c3", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("total").asLong()).isZero();
        assertThat(payload(ok).get("list").size()).isZero();
    }

    // ==== 资格门槛：未订阅前提下的 ADMITTED 三态沿 R6 同款（未入驻 → 1007C0006 统一文案）====

    @Test
    void changeLogsAdmissionGateRunsBeforeSubscriberCheck() throws Exception {
        final long productId = insertListedProduct("留痕资格产品");
        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult denied = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/change-logs"), "reader-c4", PROVIDER)).andReturn();
        assertThat(denied.getResponse().getStatus()).as("资格门槛先于订阅者判定（链序 ③）").isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1007C0006");
        assertThat(root(denied).get("message").asText())
                .isEqualTo("主体未入驻或不存在，无法使用统一目录服务");
    }

    // ==== 助手 ====

    private long insertListedProduct(final String productName) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                        + "provider_subject_no, dataset_id, category_code, listed_at) "
                        + "VALUES (?, '简介', 'DATASET', 'FREE', 'LISTED', 'provider-c', 1, 'transport', ?)",
                productName, Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                productName);
    }

    private void insertSubscription(final String subjectNo, final long productId) {
        jdbc.update("INSERT INTO product_subscription (subject_no, product_id) VALUES (?, ?)",
                subjectNo, productId);
    }

    private void insertActionLog(final long productId, final String action, final String summary,
            final String operator, final LocalDateTime createdAt) {
        jdbc.update("INSERT INTO product_action_log (product_id, action, operator_subject_no, summary, "
                        + "created_at) VALUES (?, ?, ?, ?, ?)",
                productId, action, operator, summary, Timestamp.valueOf(createdAt));
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
