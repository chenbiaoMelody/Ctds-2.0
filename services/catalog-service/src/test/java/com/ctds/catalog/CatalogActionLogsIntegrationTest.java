package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ctds.catalog.domain.CatalogErrorCodes;
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
 * 操作留痕读面集成测试（WBS-3.3.6 hifi §5 + §7 T1~T3；Q2-A 三个只读端点 R14/R15/R16）：
 * - T1 R14 资源留痕（本人 200 含四要素与 from→to / 非本人 1007C0005 同形 + DENIED_READ 恰 1 行 /
 *   不存在同形 / admin 治理例外 200 且零新增留痕 / 未认证 401 / 无权限 403 / 分页与排序）；
 * - T2 R15 产品留痕（提供方本人 200 全值域：DENIED_* 与 GOVERNANCE_VIEW 在内 / 非本人 1007C0012
 *   管理面文案同形 / admin 200 且零新增留痕 / 与 R8 订阅者可见值域逐字对照）；
 * - T3 R16 互动留痕（恒仅本人：B 读不到 A 的行 / 四动作 + DENIED 行齐备 / denyReason 码尾号 /
 *   字段白名单 / 空列表正常空页 / 分页越界沿 common）。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1~V4；资格端口 {@code @MockitoBean}；无 Docker 整类跳过。
 * 造数沿 {@code CatalogProductChangeLogIntegrationTest} 先例（jdbc 直插自造行，写面不经 API）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogActionLogsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROVIDER = "provider";
    private static final String ADMIN = "admin";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_action_logs_it");
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

    // ==== T1：R14 资源留痕 ====

    @Test
    void r14OwnerReadsOwnLogsWithFullElementsAndStableOrder() throws Exception {
        final long datasetId = insertDataset("R14 本人资源", "owner-r14");
        insertDatasetLog(datasetId, "owner-r14", "REGISTER", "SUCCESS", null, null, null,
                LocalDateTime.of(2026, 10, 2, 9, 0, 0));
        insertDatasetLog(datasetId, "owner-r14", "UPDATE", "SUCCESS", "L2", "L1", null,
                LocalDateTime.of(2026, 10, 2, 9, 0, 0));
        insertDatasetLog(datasetId, "intruder-r14", "DENIED_UPDATE", "DENIED", null, null, "C0006",
                LocalDateTime.of(2026, 10, 2, 9, 0, 0));

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId
                + "/action-logs"), "owner-r14", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode page = payload(ok);
        assertThat(page.get("total").asLong()).isEqualTo(3);
        // 排序稳定：同秒按 id 倒序（插入序 = id 序 → 最新插入在前）
        assertThat(page.get("list").get(0).get("action").asText()).isEqualTo("DENIED_UPDATE");
        assertThat(page.get("list").get(2).get("action").asText()).isEqualTo("REGISTER");
        // 四要素 + from→to + 拒绝码齐备（字段白名单以 hifi §1.2 出参为锚）
        final JsonNode updateRow = page.get("list").get(1);
        assertThat(updateRow.get("fromValue").asText()).isEqualTo("L2");
        assertThat(updateRow.get("toValue").asText()).isEqualTo("L1");
        assertThat(updateRow.get("actorSubjectNo").asText()).isEqualTo("owner-r14");
        assertThat(updateRow.get("result").asText()).isEqualTo("SUCCESS");
        assertThat(updateRow.get("createdAt").asText()).isNotBlank();
        final JsonNode deniedRow = page.get("list").get(0);
        assertThat(deniedRow.get("result").asText()).isEqualTo("DENIED");
        assertThat(deniedRow.get("reasonCode").asText()).isEqualTo("C0006");
        assertThat(page.get("list").get(0).properties().stream().map(java.util.Map.Entry::getKey).toList())
                .as("R14 出参字段白名单（id/action/actorSubjectNo/result/reasonCode/fromValue/toValue/createdAt）")
                .containsExactlyInAnyOrder("id", "action", "actorSubjectNo", "result", "reasonCode",
                        "fromValue", "toValue", "createdAt");
        assertThat(body(ok)).as("留痕不含敏感原文").doesNotContain("身份证").doesNotContain("手机号");
    }

    @Test
    void r14NonOwnerRejectedSameShapeWithExactlyOneDeniedReadLog() throws Exception {
        final long datasetId = insertDataset("R14 越权探针资源", "owner-r14b");
        final long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ?", Long.class, datasetId);

        final MvcResult denied = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId
                + "/action-logs"), "intruder-r14b", PROVIDER)).andReturn();
        assertThat(denied.getResponse().getStatus()).as("非本人 → 404").isEqualTo(404);
        assertThat(codeOf(denied)).isEqualTo("1007C0005");
        assertThat(root(denied).get("message").asText())
                .isEqualTo(CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE);
        final long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ?", Long.class, datasetId);
        assertThat(after - before).as("非本人命中写 DENIED_READ 恰 1 行（沿 R2 先例）").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT action FROM dataset_action_log WHERE dataset_id = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, datasetId)).isEqualTo("DENIED_READ");
        assertThat(jdbc.queryForObject("SELECT actor_subject_no FROM dataset_action_log WHERE dataset_id = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, datasetId))
                .as("留痕归属 = 越权者本人（非资源主）").isEqualTo("intruder-r14b");
    }

    @Test
    void r14MissingDatasetSameShapeWithoutLog() throws Exception {
        final MvcResult missing = mockMvc.perform(auth(get("/api/v1/datasets/999999/action-logs"),
                "owner-r14", PROVIDER)).andReturn();
        assertThat(missing.getResponse().getStatus()).as("不存在 → 同码同文案").isEqualTo(404);
        assertThat(codeOf(missing)).isEqualTo("1007C0005");
        assertThat(root(missing).get("message").asText())
                .as("不存在与非本人逐字一致（防枚举）")
                .isEqualTo(CatalogErrorCodes.DATASET_NOT_FOUND_OR_NO_ACCESS_MESSAGE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = 999999", Long.class))
                .as("不存在对象无留痕（无对象指向）").isZero();
    }

    @Test
    void r14GovernanceExceptionReadsWithoutNewLog() throws Exception {
        final long datasetId = insertDataset("R14 治理例外资源", "owner-r14c");
        insertDatasetLog(datasetId, "owner-r14c", "REGISTER", "SUCCESS", null, null, null,
                LocalDateTime.of(2026, 10, 2, 9, 0, 0));
        final long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ?", Long.class, datasetId);

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId
                + "/action-logs"), "governor-r14", ADMIN)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("total").asLong()).isEqualTo(1);
        final long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dataset_action_log WHERE dataset_id = ?", Long.class, datasetId);
        assertThat(after - before).as("admin 治理例外读留痕不另写留痕（避免看留痕产生留痕的递归）").isZero();
    }

    @Test
    void r14UnauthenticatedAndPermissionDenied() throws Exception {
        final long datasetId = insertDataset("R14 鉴权探针资源", "owner-r14d");
        final MvcResult unauth = mockMvc.perform(get("/api/v1/datasets/" + datasetId + "/action-logs"))
                .andReturn();
        assertThat(unauth.getResponse().getStatus()).as("未认证 → 401").isEqualTo(401);

        final MvcResult forbidden = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId
                + "/action-logs"), "owner-r14d", "no-such-role")).andReturn();
        assertThat(forbidden.getResponse().getStatus()).as("无权限 → 403").isEqualTo(403);
    }

    @Test
    void r14PaginationFieldsAndOutOfRange() throws Exception {
        final long datasetId = insertDataset("R14 分页探针资源", "owner-r14e");
        insertDatasetLog(datasetId, "owner-r14e", "REGISTER", "SUCCESS", null, null, null,
                LocalDateTime.of(2026, 10, 2, 9, 0, 0));

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId
                + "/action-logs?pageNum=1&pageSize=10"), "owner-r14e", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode page = payload(ok);
        assertThat(page.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("list", "total", "pageNum", "pageSize", "totalPages");

        final MvcResult outOfRange = mockMvc.perform(auth(get("/api/v1/datasets/" + datasetId
                + "/action-logs?pageNum=1&pageSize=101"), "owner-r14e", PROVIDER)).andReturn();
        assertThat(outOfRange.getResponse().getStatus()).as("分页越界沿 common").isEqualTo(400);
        assertThat(codeOf(outOfRange)).isEqualTo("1000C0001");
    }

    // ==== T2：R15 产品留痕（全值域） ====

    @Test
    void r15ProviderReadsFullValueDomainIncludingDeniedAndGovernanceView() throws Exception {
        final long productId = insertProduct("R15 全值域产品", "provider-r15");
        insertProductLog(productId, "CREATE", "封装产品", "provider-r15");
        insertProductLog(productId, "DENIED_PUBLISH", "上架被拒", "provider-r15");
        insertProductLog(productId, "DENIED_CREATE", "封装被拒", "intruder-r15");
        insertProductLog(productId, "GOVERNANCE_VIEW", "治理查看：运营方 governor-r15", "governor-r15");

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/action-logs"), "provider-r15", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode page = payload(ok);
        assertThat(page.get("total").asLong()).as("全值域（含 DENIED_* 与 GOVERNANCE_VIEW）").isEqualTo(4);
        final StringBuilder actions = new StringBuilder();
        page.get("list").forEach(row -> actions.append(row.get("action").asText()).append(','));
        assertThat(actions.toString()).contains("CREATE").contains("DENIED_PUBLISH")
                .contains("DENIED_CREATE").contains("GOVERNANCE_VIEW");
        // 字段白名单 + 强制下架/治理查看摘要全文承载
        assertThat(page.get("list").get(0).properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("id", "action", "operatorSubjectNo", "summary", "createdAt");
    }

    @Test
    void r15NonProviderRejectedSameShapeWithManageMessage() throws Exception {
        final long productId = insertProduct("R15 越权探针产品", "provider-r15b");
        insertProductLog(productId, "CREATE", "封装产品", "provider-r15b");

        final MvcResult denied = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/action-logs"), "intruder-r15b", PROVIDER)).andReturn();
        assertThat(denied.getResponse().getStatus()).as("非本人 → 404").isEqualTo(404);
        assertThat(codeOf(denied)).isEqualTo("1007C0012");
        assertThat(root(denied).get("message").asText())
                .as("管理面文案同形").isEqualTo(CatalogErrorCodes.PRODUCT_MANAGE_NOT_FOUND_MESSAGE);

        final MvcResult missing = mockMvc.perform(auth(get("/api/v1/data-products/999999/action-logs"),
                "provider-r15b", PROVIDER)).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(missing)).isEqualTo("1007C0012");
        assertThat(root(missing).get("message").asText())
                .as("不存在与非本人逐字一致（沿产品面读面既有口径，不写留痕）")
                .isEqualTo(CatalogErrorCodes.PRODUCT_MANAGE_NOT_FOUND_MESSAGE);
    }

    @Test
    void r15GovernanceExceptionReadsWithoutNewLog() throws Exception {
        final long productId = insertProduct("R15 治理例外产品", "provider-r15c");
        insertProductLog(productId, "CREATE", "封装产品", "provider-r15c");
        final long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ?", Long.class, productId);

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/action-logs"), "governor-r15", ADMIN)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("total").asLong()).isEqualTo(1);
        final long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_action_log WHERE product_id = ?", Long.class, productId);
        assertThat(after - before).as("admin 读 R15 不另写留痕").isZero();
    }

    @Test
    void r15FullValueDomainContrastsWithR8SubscriberVisibleValueDomain() throws Exception {
        // 同一产品同一批行：R15（提供方本人）全值域可见；R8（订阅者）只见可见值域六码
        final long productId = insertProduct("R15 值域对照产品", "provider-r15d");
        insertProductLog(productId, "PUBLISH", "产品已上架", "provider-r15d");
        insertProductLog(productId, "DENIED_PUBLISH", "上架被拒", "provider-r15d");
        insertProductLog(productId, "GOVERNANCE_VIEW", "治理查看", "governor-r15");
        jdbc.update("INSERT INTO product_subscription (subject_no, product_id) VALUES ('subscriber-r15d', ?)",
                productId);

        final MvcResult mine = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/action-logs"), "provider-r15d", PROVIDER)).andReturn();
        assertThat(mine.getResponse().getStatus()).as(body(mine)).isEqualTo(200);
        assertThat(payload(mine).get("total").asLong()).as("R15 全值域 3 行").isEqualTo(3);

        final MvcResult subscriber = mockMvc.perform(auth(get("/api/v1/data-products/" + productId
                + "/change-logs"), "subscriber-r15d", PROVIDER)).andReturn();
        assertThat(subscriber.getResponse().getStatus()).as(body(subscriber)).isEqualTo(200);
        final JsonNode r8 = payload(subscriber);
        assertThat(r8.get("total").asLong()).as("R8 订阅者可见值域仅 1 行（PUBLISH）").isEqualTo(1);
        assertThat(r8.get("list").get(0).get("action").asText()).isEqualTo("PUBLISH");
        // 同一行在 R15 可见、在 R8 不可见（逐字对照：GOVERNANCE_VIEW / DENIED_*）
        assertThat(r8.toString()).doesNotContain("GOVERNANCE_VIEW").doesNotContain("DENIED_PUBLISH");
    }

    // ==== T3：R16 互动留痕 ====

    @Test
    void r16ReturnsOnlyOwnRowsWithAllActionsAndDenyReason() throws Exception {
        insertInteractionLog("user-r16a", 7, "FAVORITE", "SUCCEEDED", null);
        insertInteractionLog("user-r16a", 7, "SUBSCRIBE", "SUCCEEDED", null);
        insertInteractionLog("user-r16a", 999, "UNFAVORITE", "DENIED", "C0012");
        insertInteractionLog("user-r16a", 7, "UNSUBSCRIBE", "SUCCEEDED", null);
        insertInteractionLog("user-r16b", 7, "UNSUBSCRIBE", "SUCCEEDED", null);

        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/catalog/interaction-logs"),
                "user-r16a", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        final JsonNode page = payload(ok);
        assertThat(page.get("total").asLong()).as("恒仅本人行（B 的行不可见）").isEqualTo(4);
        final StringBuilder content = new StringBuilder();
        page.get("list").forEach(row -> content.append(row.get("action").asText()).append('/')
                .append(row.get("outcome").asText()).append('/').append(row.get("denyReason").asText()).append(';'));
        assertThat(content.toString()).contains("FAVORITE/SUCCEEDED/null")
                .contains("SUBSCRIBE/SUCCEEDED/null").contains("UNFAVORITE/DENIED/C0012")
                .as("四动作齐备（本人行含退订成功）").contains("UNSUBSCRIBE/SUCCEEDED/null");
        assertThat(content.toString().split("UNSUBSCRIBE", -1).length - 1)
                .as("B 主体的退订行不可见（仅本人 1 行）").isEqualTo(1);
        assertThat(page.get("list").get(0).properties().stream().map(java.util.Map.Entry::getKey).toList())
                .as("R16 出参字段白名单（无敏感原文）")
                .containsExactlyInAnyOrder("id", "productId", "action", "outcome", "denyReason", "createdAt");
    }

    @Test
    void r16EmptyListIsNormalEmptyPageAndPaginationOutOfRange() throws Exception {
        final MvcResult ok = mockMvc.perform(auth(get("/api/v1/catalog/interaction-logs"),
                "user-r16-empty", PROVIDER)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("total").asLong()).as("空列表正常空页（无对象不存在分支）").isZero();

        final MvcResult outOfRange = mockMvc.perform(auth(get(
                "/api/v1/catalog/interaction-logs?pageNum=1&pageSize=101"), "user-r16-empty", PROVIDER))
                .andReturn();
        assertThat(outOfRange.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(outOfRange)).isEqualTo("1000C0001");
    }

    // ==== 助手（沿 CatalogProductChangeLogIntegrationTest 先例）====

    private long insertDataset(final String name, final String ownerSubjectNo) {
        jdbc.update("INSERT INTO dataset (data_no, space_id, owner_subject_no, name, normalized_name, type, "
                        + "intro, semantic_tags, declare_category, declare_level, declare_important, status) "
                        + "VALUES (?, 29, ?, ?, ?, 'DATASET', '简介', '[\"金融\"]', '金融', 'L2', 0, 'ACTIVE')",
                "DS-R14-" + name, ownerSubjectNo, name, name);
        return jdbc.queryForObject("SELECT id FROM dataset WHERE name = ?", Long.class, name);
    }

    private void insertDatasetLog(final long datasetId, final String actor, final String action,
            final String result, final String fromValue, final String toValue, final String reasonCode,
            final LocalDateTime createdAt) {
        jdbc.update("INSERT INTO dataset_action_log (actor_subject_no, space_id, dataset_id, action, "
                        + "from_value, to_value, result, reason_code, created_at) VALUES (?, 29, ?, ?, ?, ?, ?, ?, ?)",
                actor, datasetId, action, fromValue, toValue, result, reasonCode, Timestamp.valueOf(createdAt));
    }

    private long insertProduct(final String productName, final String providerSubjectNo) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                        + "provider_subject_no, dataset_id, category_code, listed_at) "
                        + "VALUES (?, '简介', 'DATASET', 'FREE', 'LISTED', ?, 1, 'transport', ?)",
                productName, providerSubjectNo, Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                productName);
    }

    private void insertProductLog(final long productId, final String action, final String summary,
            final String operator) {
        jdbc.update("INSERT INTO product_action_log (product_id, action, operator_subject_no, summary, "
                        + "created_at) VALUES (?, ?, ?, ?, ?)",
                productId, action, operator, summary, Timestamp.valueOf(LocalDateTime.now()));
    }

    private void insertInteractionLog(final String subjectNo, final long productId, final String action,
            final String outcome, final String denyReason) {
        jdbc.update("INSERT INTO product_interaction_log (subject_no, product_id, action, outcome, "
                        + "deny_reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                subjectNo, productId, action, outcome, denyReason, Timestamp.valueOf(LocalDateTime.now()));
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
