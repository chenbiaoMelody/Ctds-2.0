package com.ctds.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * 收藏订阅交互集成测试（WBS-3.3.4 hifi §5 T3；规格行为 6 全部规则 + 行为 7 规则 1/4）：
 * 收藏/订阅成功与留痕四要素、幂等重放（行数不变 + 留痕不新增 + 返回首次时间）、越权取消（无条目
 * DELETE → 1007C0012 + DENIED 留痕——剧本 S2-3"被拒绝且记录拒绝留痕"承载）、列表仅本人条目、
 * 状态联动（下架/注销后条目保留且 productStatus 读时计算）、新发起拒绝（非在架 W4/W6 → 1007C0011
 * 同形 + DENIED 留痕）、幂等先于状态门槛链序锚（下架前已收藏、下架后重放 = 成功且零新副作用——
 * 若链序回退为状态门槛先行本例必红）、退订成功、未入驻资格门槛（统一文案 + 零副作用）。
 * WBS-3.3.7 补锚（DB-38 收尾与对称）：写面主体服务不可用 → 503 不冒充资格拒绝（零副作用）、
 * 已注销产品新发起订阅 → 同形 1007C0011 + DENIED 留痕（收藏侧由治理类 T10 覆盖）。
 *
 * <p>真实 MySQL 8 容器实跑 Flyway V1+V2+V3；产品数据测试自造（写面归 3.3.5）；资格端口
 * {@code @MockitoBean}；无 Docker 整类跳过。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CatalogProductInteractionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROVIDER = "provider";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_catalog_interact_it");
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

    // ==== 行为 6 规则 1/4：收藏成功 + 留痕四要素逐字 ====

    @Test
    void favoriteSuccessWritesFourElementLog() throws Exception {
        final long productId = insertListedProduct("收藏探针产品");
        final MvcResult ok = mockMvc.perform(
                        favorite("owner-i1", productId)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("productId").asLong()).isEqualTo(productId);
        assertThat(payload(ok).get("favoritedAt").asText()).isNotBlank();
        assertThat(favoriteCount("owner-i1", productId)).as("收藏条目一行").isEqualTo(1);
        assertThat(logCount("owner-i1", productId, "FAVORITE", "SUCCEEDED")).isEqualTo(1);
        final JsonNode logRow = firstLog("owner-i1", productId, "FAVORITE");
        assertThat(logRow.get("action").asText()).isEqualTo("FAVORITE");
        assertThat(logRow.get("outcome").asText()).isEqualTo("SUCCEEDED");
        assertThat(logRow.get("denyReason").isNull()).as("成功行无拒绝理由").isTrue();
    }

    // ==== 行为 6 规则 1：重复收藏幂等（行数不变 + 返回首次时间 + 留痕不新增——ADR-007 重放语义）====

    @Test
    void repeatedFavoriteIsIdempotentReplayWithoutNewSideEffects() throws Exception {
        final long productId = insertListedProduct("幂等收藏产品");
        final MvcResult first = mockMvc.perform(favorite("owner-i2", productId)).andReturn();
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(200);
        final String firstFavoritedAt = payload(first).get("favoritedAt").asText();

        final MvcResult replay = mockMvc.perform(favorite("owner-i2", productId)).andReturn();
        assertThat(replay.getResponse().getStatus()).as("幂等重放 = 200 首次结果").isEqualTo(200);
        assertThat(payload(replay).get("favoritedAt").asText())
                .as("返回首次收藏时间").isEqualTo(firstFavoritedAt);
        assertThat(favoriteCount("owner-i2", productId)).as("行数不变").isEqualTo(1);
        assertThat(logCount("owner-i2", productId, "FAVORITE", "SUCCEEDED"))
                .as("留痕不新增（零新增副作用）").isEqualTo(1);
    }

    // ==== 行为 6 规则 1/4：越权取消（无条目 DELETE → 1007C0012 + DENIED 留痕；剧本 S2-3 承载）====

    @Test
    void unfavoriteWithoutOwnEntryDeniedAndLogged() throws Exception {
        final long productId = insertListedProduct("越权取消探针产品");
        // 他人（owner-other）已收藏——主体 B 对无本人条目的产品 DELETE
        mockMvc.perform(favorite("owner-other", productId)).andReturn();
        final long logsBefore = logCount("owner-i3", productId, "UNFAVORITE", "DENIED");
        final MvcResult denied = mockMvc.perform(unfavorite("owner-i3", productId)).andReturn();
        assertThat(denied.getResponse().getStatus()).as("本人无条目 → 404").isEqualTo(404);
        assertThat(codeOf(denied)).isEqualTo("1007C0012");
        assertThat(root(denied).get("message").asText()).isEqualTo("收藏或订阅记录不存在");
        assertThat(logCount("owner-i3", productId, "UNFAVORITE", "DENIED"))
                .as("DENIED 拒绝留痕（越权探测可取证，行为 7 规则 4）").isEqualTo(logsBefore + 1);
        // 他人条目不受影响（仅本人条目可操作）
        assertThat(favoriteCount("owner-other", productId)).isEqualTo(1);
    }

    // ==== 行为 6 规则 1：取消收藏成功 + 留痕 ====

    @Test
    void unfavoriteSuccessDeletesOwnEntryAndLogs() throws Exception {
        final long productId = insertListedProduct("取消收藏产品");
        mockMvc.perform(favorite("owner-i4", productId)).andReturn();
        final MvcResult ok = mockMvc.perform(unfavorite("owner-i4", productId)).andReturn();
        assertThat(ok.getResponse().getStatus()).as(body(ok)).isEqualTo(200);
        assertThat(payload(ok).get("favoritedAt").asText()).as("返回被删条目首次时间").isNotBlank();
        assertThat(favoriteCount("owner-i4", productId)).as("条目已删").isZero();
        assertThat(logCount("owner-i4", productId, "UNFAVORITE", "SUCCEEDED")).isEqualTo(1);
    }

    // ==== 行为 6 规则 2/4：订阅成功 + 留痕；退订成功 ====

    @Test
    void subscribeAndUnsubscribeSuccessWithLogs() throws Exception {
        final long productId = insertListedProduct("订阅探针产品");
        final MvcResult subscribe = mockMvc.perform(subscription("owner-i5", productId)).andReturn();
        assertThat(subscribe.getResponse().getStatus()).as(body(subscribe)).isEqualTo(200);
        assertThat(subscriptionCount("owner-i5", productId)).isEqualTo(1);
        assertThat(logCount("owner-i5", productId, "SUBSCRIBE", "SUCCEEDED")).isEqualTo(1);

        final MvcResult unsubscribe = mockMvc.perform(unsubscription("owner-i5", productId)).andReturn();
        assertThat(unsubscribe.getResponse().getStatus()).as(body(unsubscribe)).isEqualTo(200);
        assertThat(subscriptionCount("owner-i5", productId)).as("退订后条目删除").isZero();
        assertThat(logCount("owner-i5", productId, "UNSUBSCRIBE", "SUCCEEDED")).isEqualTo(1);
    }

    // ==== 行为 6 规则 1/2：列表仅本人条目（R9/R10）====

    @Test
    void favoriteAndSubscriptionListsContainOnlyOwnEntries() throws Exception {
        final long mine = insertListedProduct("我的列表产品");
        final long others = insertListedProduct("他人列表产品");
        mockMvc.perform(favorite("owner-i6", mine)).andReturn();
        mockMvc.perform(favorite("owner-i6b", others)).andReturn();
        mockMvc.perform(subscription("owner-i6", mine)).andReturn();

        final JsonNode favorites = payload(mockMvc.perform(
                auth(get("/api/v1/catalog/favorites"), "owner-i6", PROVIDER)).andReturn());
        assertThat(favorites.get("total").asLong()).as("R9 恒仅本人条目").isEqualTo(1);
        assertThat(favorites.get("list").get(0).get("productId").asLong()).isEqualTo(mine);
        assertThat(favorites.get("list").get(0).properties().stream()
                .map(java.util.Map.Entry::getKey).toList())
                .as("R9 出站字段 = 目录元数据 + productStatus + favoritedAt")
                .containsExactlyInAnyOrder("productId", "productName", "intro", "productType",
                        "pricingModel", "categoryCode", "categoryName", "providerSubjectNo", "listedAt",
                        "productStatus", "favoritedAt");
        assertThat(favorites.get("list").get(0).get("productStatus").asText()).isEqualTo("已上架");
        assertThat(favorites.get("list").get(0).get("favoritedAt").asText()).isNotBlank();

        final JsonNode subscriptions = payload(mockMvc.perform(
                auth(get("/api/v1/catalog/subscriptions"), "owner-i6", PROVIDER)).andReturn());
        assertThat(subscriptions.get("total").asLong()).as("R10 恒仅本人条目").isEqualTo(1);
        assertThat(subscriptions.get("list").get(0).get("subscribedAt").asText()).isNotBlank();
    }

    // ==== 行为 6 规则 3：状态联动（下架/注销后条目保留且 productStatus 读时计算）====

    @Test
    void delistedAndCancelledProductsKeepEntriesWithComputedStatus() throws Exception {
        final long delistedId = insertListedProduct("状态联动下架产品");
        final long cancelledId = insertListedProduct("状态联动注销产品");
        mockMvc.perform(favorite("owner-i7", delistedId)).andReturn();
        mockMvc.perform(subscription("owner-i7", delistedId)).andReturn();
        mockMvc.perform(favorite("owner-i7", cancelledId)).andReturn();

        jdbc.update("UPDATE data_product SET status = 'DELISTED', listed_at = NULL WHERE id = ?", delistedId);
        jdbc.update("UPDATE data_product SET status = 'CANCELLED' WHERE id = ?", cancelledId);

        final JsonNode favorites = payload(mockMvc.perform(
                auth(get("/api/v1/catalog/favorites"), "owner-i7", PROVIDER)).andReturn());
        assertThat(favorites.get("total").asLong()).as("条目保留不删").isEqualTo(2);
        assertThat(statusOfProductEntry(favorites, delistedId)).as("下架产品条目标记当前状态")
                .isEqualTo("已下架");
        assertThat(statusOfProductEntry(favorites, cancelledId)).isEqualTo("已注销");

        final JsonNode subscriptions = payload(mockMvc.perform(
                auth(get("/api/v1/catalog/subscriptions"), "owner-i7", PROVIDER)).andReturn());
        assertThat(statusOfProductEntry(subscriptions, delistedId)).isEqualTo("已下架");
    }

    // ==== 行为 6 规则 3：新发起拒绝（非在架 W4/W6 → 1007C0011 同形 + DENIED 留痕）====

    @Test
    void newFavoriteOrSubscriptionOnNonListedProductRejectedSameShape() throws Exception {
        final long draftId = insertProduct("未上架新发起产品", "DRAFT");
        final long delistedId = insertProduct("已下架新发起产品", "DELISTED");

        for (final long productId : new long[] {draftId, delistedId}) {
            final MvcResult favoriteDenied = mockMvc.perform(favorite("owner-i8", productId)).andReturn();
            assertThat(favoriteDenied.getResponse().getStatus()).isEqualTo(404);
            assertThat(codeOf(favoriteDenied)).as("新发起对非在架 → 同形 1007C0011").isEqualTo("1007C0011");
            final MvcResult subscribeDenied = mockMvc.perform(
                    subscription("owner-i8", productId)).andReturn();
            assertThat(subscribeDenied.getResponse().getStatus()).isEqualTo(404);
            assertThat(codeOf(subscribeDenied)).isEqualTo("1007C0011");
        }
        assertThat(logCount("owner-i8", draftId, "FAVORITE", "DENIED"))
                .as("有产品指向的拒绝留痕（deny_reason=C0011）").isEqualTo(1);
        assertThat(firstLog("owner-i8", draftId, "FAVORITE").get("denyReason").asText()).isEqualTo("C0011");
        assertThat(favoriteCount("owner-i8", draftId)).as("零写入").isZero();
        assertThat(subscriptionCount("owner-i8", draftId)).isZero();
        assertThat(logCount("owner-i8", delistedId, "SUBSCRIBE", "DENIED")).isEqualTo(1);
    }

    // ==== DB-38 对称锚（WBS-3.3.7 T2）：已注销产品新发起订阅（收藏侧由治理类 T10 覆盖）====

    @Test
    void cancelledProductNewSubscriptionRejectedWithDeniedLog() throws Exception {
        final long productId = insertListedProduct("注销新订阅探针产品");
        jdbc.update("UPDATE data_product SET status = 'CANCELLED' WHERE id = ?", productId);

        final MvcResult subscribeDenied = mockMvc.perform(
                subscription("owner-ic", productId)).andReturn();
        assertThat(subscribeDenied.getResponse().getStatus())
                .as("已注销产品新发起订阅 → 同形 404（防枚举）").isEqualTo(404);
        assertThat(codeOf(subscribeDenied)).isEqualTo("1007C0011");
        assertThat(root(subscribeDenied).get("message").asText()).isEqualTo("产品不存在或未在架");
        assertThat(logCount("owner-ic", productId, "SUBSCRIBE", "DENIED"))
                .as("DENIED 留痕可取证（行为 7 规则 4）").isEqualTo(1);
        assertThat(firstLog("owner-ic", productId, "SUBSCRIBE").get("denyReason").asText())
                .as("留痕含拒绝理由").isEqualTo("C0011");
        assertThat(subscriptionCount("owner-ic", productId)).as("订阅行不增").isZero();
    }

    // ==== 链序锚：幂等重放先于状态门槛（下架前已收藏，下架后重放 = 成功且零新副作用）====

    @Test
    void idempotentReplayRunsBeforeStatusGate() throws Exception {
        final long productId = insertListedProduct("链序锚产品");
        mockMvc.perform(favorite("owner-i9", productId)).andReturn();
        final long logsAfterFirst = logCount("owner-i9", productId, "FAVORITE", "SUCCEEDED");
        jdbc.update("UPDATE data_product SET status = 'DELISTED' WHERE id = ?", productId);

        final MvcResult replay = mockMvc.perform(favorite("owner-i9", productId)).andReturn();
        assertThat(replay.getResponse().getStatus())
                .as("下架后重复收藏 = 幂等重放而非新发起（若链序回退为状态门槛先行本例必红）")
                .isEqualTo(200);
        assertThat(favoriteCount("owner-i9", productId)).as("行数不变").isEqualTo(1);
        assertThat(logCount("owner-i9", productId, "FAVORITE", "SUCCEEDED"))
                .as("零新增留痕").isEqualTo(logsAfterFirst);

        // 同一产品的另一主体新发起 = 仍被状态门槛拒绝（规则 3"不可新发起"不因重放通道松动）
        final MvcResult newSubject = mockMvc.perform(favorite("owner-i9b", productId)).andReturn();
        assertThat(newSubject.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(newSubject)).isEqualTo("1007C0011");
    }

    // ==== 行为 5 规则 2：W4/W6 资格门槛（未入驻统一文案 + 零副作用）；W5/W7 无该门槛 ====

    @Test
    void newInteractionsAdmissionGateAndCancelWithoutAdmission() throws Exception {
        final long productId = insertListedProduct("资格门槛交互产品");
        final long logsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_interaction_log", Long.class);

        given(admissionPort.check(any())).willReturn(SubjectAdmission.NOT_ADMITTED);
        final MvcResult favoriteDenied = mockMvc.perform(favorite("owner-ia", productId)).andReturn();
        assertThat(favoriteDenied.getResponse().getStatus()).as("未入驻 → 403").isEqualTo(403);
        assertThat(codeOf(favoriteDenied)).isEqualTo("1007C0006");
        assertThat(root(favoriteDenied).get("message").asText())
                .isEqualTo("主体未入驻或不存在，无法使用统一目录服务");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_interaction_log", Long.class))
                .as("资格拒绝零留痕零副作用").isEqualTo(logsBefore);
        assertThat(favoriteCount("owner-ia", productId)).isZero();

        // W5 取消收藏不经 ADMITTED 门槛（本人条目操作）：未入驻主体对既有条目（直插构造）可取消
        jdbc.update("INSERT INTO product_favorite (subject_no, product_id) VALUES ('owner-ia', ?)",
                productId);
        final MvcResult cancel = mockMvc.perform(unfavorite("owner-ia", productId)).andReturn();
        assertThat(cancel.getResponse().getStatus())
                .as("W5 无 ADMITTED 门槛（hifi §1：条目产生于 ADMITTED 期）").isEqualTo(200);
    }

    // ==== DB-38 收尾锚（WBS-3.3.7 T1）：写面主体服务不可用 → 503 不冒充资格拒绝（零副作用）====

    @Test
    void writeFaceAdmissionUnavailableReturnsServiceUnavailableWithoutSideEffects() throws Exception {
        final long productId = insertListedProduct("写面不可用探针产品");
        final long logsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_interaction_log", Long.class);

        given(admissionPort.check(any())).willReturn(SubjectAdmission.UNAVAILABLE);
        final MvcResult favoriteUnavailable = mockMvc.perform(
                favorite("owner-id", productId)).andReturn();
        assertThat(favoriteUnavailable.getResponse().getStatus())
                .as("写面资格门不可用 → 503").isEqualTo(503);
        assertThat(codeOf(favoriteUnavailable)).isEqualTo("1007S0001");
        assertThat(root(favoriteUnavailable).get("message").asText())
                .as("不可用与资格拒绝文案不同形（不可用 ≠ 拒绝）")
                .isEqualTo("主体服务暂不可用，请稍后重试");
        final MvcResult subscribeUnavailable = mockMvc.perform(
                subscription("owner-id", productId)).andReturn();
        assertThat(subscribeUnavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(subscribeUnavailable)).isEqualTo("1007S0001");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_interaction_log", Long.class))
                .as("资格门不可用零留痕零副作用").isEqualTo(logsBefore);
        assertThat(favoriteCount("owner-id", productId)).as("零写入").isZero();
        assertThat(subscriptionCount("owner-id", productId)).isZero();
    }

    // ==== 行为 7 规则 1：服务端强制（无 catalog.interact 权限点 → 403）====

    @Test
    void writeEndpointsRequireInteractPermission() throws Exception {
        final long productId = insertListedProduct("权限点交互产品");
        final MvcResult forbidden = mockMvc.perform(favorite("owner-ib", productId, "guest")).andReturn();
        assertThat(forbidden.getResponse().getStatus()).as("无 catalog.interact → 403").isEqualTo(403);
        final MvcResult anonymous = mockMvc.perform(
                post("/api/v1/data-products/" + productId + "/favorite")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).as("未认证 → 401").isEqualTo(401);
    }

    // ==== 助手 ====

    private long insertListedProduct(final String productName) {
        return insertProduct(productName, "LISTED");
    }

    private long insertProduct(final String productName, final String status) {
        jdbc.update("INSERT INTO data_product (product_name, intro, product_type, pricing_model, status, "
                        + "provider_subject_no, dataset_id, category_code, listed_at) "
                        + "VALUES (?, '简介', 'DATASET', 'FREE', ?, 'provider-i', 1, 'transport', ?)",
                productName, status, status.equals("LISTED") ? Timestamp.valueOf(LocalDateTime.now()) : null);
        return jdbc.queryForObject("SELECT id FROM data_product WHERE product_name = ?", Long.class,
                productName);
    }

    private MockHttpServletRequestBuilder favorite(final String subject, final long productId) {
        return favorite(subject, productId, PROVIDER);
    }

    private MockHttpServletRequestBuilder favorite(final String subject, final long productId,
            final String roles) {
        return auth(post("/api/v1/data-products/" + productId + "/favorite"), subject, roles);
    }

    private MockHttpServletRequestBuilder unfavorite(final String subject, final long productId) {
        return auth(delete("/api/v1/data-products/" + productId + "/favorite"), subject, PROVIDER);
    }

    private MockHttpServletRequestBuilder subscription(final String subject, final long productId) {
        return auth(post("/api/v1/data-products/" + productId + "/subscription"), subject, PROVIDER);
    }

    private MockHttpServletRequestBuilder unsubscription(final String subject, final long productId) {
        return auth(delete("/api/v1/data-products/" + productId + "/subscription"), subject, PROVIDER);
    }

    private long favoriteCount(final String subjectNo, final long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_favorite WHERE subject_no = ? "
                + "AND product_id = ?", Long.class, subjectNo, productId);
    }

    private long subscriptionCount(final String subjectNo, final long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_subscription WHERE subject_no = ? "
                + "AND product_id = ?", Long.class, subjectNo, productId);
    }

    private long logCount(final String subjectNo, final long productId, final String action,
            final String outcome) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_interaction_log WHERE subject_no = ? "
                + "AND product_id = ? AND action = ? AND outcome = ?", Long.class,
                subjectNo, productId, action, outcome);
    }

    private JsonNode firstLog(final String subjectNo, final long productId, final String action)
            throws Exception {
        final String row = jdbc.queryForObject("SELECT JSON_OBJECT('action', action, 'outcome', outcome, "
                + "'denyReason', deny_reason) FROM product_interaction_log WHERE subject_no = ? "
                + "AND product_id = ? AND action = ? LIMIT 1", String.class, subjectNo, productId, action);
        return MAPPER.readTree(row);
    }

    private static String statusOfProductEntry(final JsonNode page, final long productId) {
        final java.util.List<String> matched = new java.util.ArrayList<>();
        page.get("list").forEach(node -> {
            if (node.get("productId").asLong() == productId) {
                matched.add(node.get("productStatus").asText());
            }
        });
        return matched.isEmpty() ? null : matched.get(0);
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
