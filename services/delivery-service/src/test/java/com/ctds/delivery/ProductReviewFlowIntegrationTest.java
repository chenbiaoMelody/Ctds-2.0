package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.delivery.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 产品评价全链集成测试（WBS-3.7.4 hifi §8 T1~T8；规格 C-7.1-7.2 B5-1~B5-4 + B6）：
 * 提交/更新 upsert（一人一评）、交付资格同库硬校验（防刷评）、统计与文字列表（仅计未隐藏）、
 * 运营隐藏留痕不删除、零推送/轮询入口。资格数据源 = delivery 表直插（演示预置等效手法）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ProductReviewFlowIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUESTER_A = "S-reqA";
    private static final String REQUESTER_B = "S-reqB";
    private static final String REQUESTER_C = "S-reqC";
    private static final String REQUESTER_D = "S-reqD";
    private static final String ADMIN = "S-admin";
    private static final long PRODUCT_P1 = 1L;
    private static final long PRODUCT_P2 = 2L;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_review_flow_it");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM product_review_action_log");
        jdbc.update("DELETE FROM product_review");
        jdbc.update("DELETE FROM delivery");
    }

    /** T1（B5-1 正向 / S2-8）：有 SUCCESS 交付的需方提交 4 分 + 文字 → 200 + 留痕行 + 统计 4.0/1。 */
    @Test
    void submitterWithSuccessDeliveryCanReviewAndStatsReflect() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        final MvcResult submit = postEvaluation(REQUESTER_A,
                evaluationBody(PRODUCT_P1, 4, "数据质量不错，接口稳定"));
        assertThat(submit.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(submit);
        final long reviewId = data.path("reviewId").asLong();
        assertThat(reviewId).isPositive();
        assertThat(data.path("productId").asLong()).isEqualTo(PRODUCT_P1);
        assertThat(data.path("score").asInt()).isEqualTo(4);
        assertThat(data.path("comment").asText()).isEqualTo("数据质量不错，接口稳定");
        assertThat(data.path("updated").asBoolean()).isFalse();
        assertThat(data.path("createdAt").asText()).isNotBlank();
        // B5-1 留痕可溯：评价人/产品/评分/时点落行
        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT requester_no, product_id, score, comment, hidden, created_at "
                        + "FROM product_review WHERE id = ?", reviewId);
        assertThat(row.get("requester_no")).isEqualTo(REQUESTER_A);
        assertThat(((Number) row.get("product_id")).longValue()).isEqualTo(PRODUCT_P1);
        assertThat(((Number) row.get("score")).intValue()).isEqualTo(4);
        assertThat(row.get("hidden")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("created_at")).isNotNull();
        // CREATE 留痕行
        final Integer createLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_review_action_log "
                        + "WHERE review_id = ? AND action = 'CREATE' AND actor_no = ?",
                Integer.class, reviewId, REQUESTER_A);
        assertThat(createLogs).isEqualTo(1);
        // 统计端点（B5-3：仅计未隐藏）
        final MvcResult stats = getStats(REQUESTER_B, PRODUCT_P1);
        assertThat(stats.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(stats).path("productId").asLong()).isEqualTo(PRODUCT_P1);
        assertThat(dataOf(stats).path("averageScore").asDouble()).isEqualTo(4.0);
        assertThat(dataOf(stats).path("reviewCount").asInt()).isEqualTo(1);
    }

    /** T2（B5-1 负向 / S2-9 双腿）：score=0 与 score=6 → 400 + 1011C0005 业务可读文案。 */
    @Test
    void invalidScoreIsRejectedWithBusinessReadableMessage() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        for (final int score : new int[] {0, 6}) {
            final MvcResult result = postEvaluation(REQUESTER_A,
                    evaluationBody(PRODUCT_P1, score, null));
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(codeOf(result)).isEqualTo("1011C0005");
            assertThat(messageOf(result)).isEqualTo("评分需为 1~5 的整数");
        }
        assertThat(countOf("product_review")).isZero();
        assertThat(countOf("product_review_action_log")).isZero();
    }

    /** T3（B5-2 / S2-11）：再次提交 = 更新本人既有评价，总数不变、created_at 不变、UPDATE 留痕。 */
    @Test
    void resubmitUpdatesOwnReviewAndCountUnchanged() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        final MvcResult first = postEvaluation(REQUESTER_A,
                evaluationBody(PRODUCT_P1, 4, "初评"));
        final long firstId = dataOf(first).path("reviewId").asLong();
        // 把首次时点拨回过去，断言更新不动 created_at（hifi §2 首次提交时点留痕语义）
        jdbc.update("UPDATE product_review SET created_at = '2026-01-01 00:00:00' WHERE id = ?",
                firstId);
        final MvcResult second = postEvaluation(REQUESTER_A,
                evaluationBody(PRODUCT_P1, 5, "更新后的评价"));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(second).path("updated").asBoolean()).isTrue();
        assertThat(dataOf(second).path("reviewId").asLong()).isEqualTo(firstId);
        // 一人一评：总数不变
        assertThat(countOf("product_review")).isEqualTo(1);
        assertThat(dataOf(getStats(REQUESTER_A, PRODUCT_P1)).path("averageScore").asDouble())
                .isEqualTo(5.0);
        // created_at 不变（首次时点），评分/文字已更新
        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT score, comment, created_at FROM product_review WHERE id = ?", firstId);
        assertThat(((Number) row.get("score")).intValue()).isEqualTo(5);
        assertThat(row.get("comment")).isEqualTo("更新后的评价");
        assertThat(String.valueOf(row.get("created_at"))).startsWith("2026-01-01");
        // 留痕：CREATE 1 行 + UPDATE 1 行
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_review_action_log "
                + "WHERE review_id = ? AND action = 'CREATE'", Integer.class, firstId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_review_action_log "
                + "WHERE review_id = ? AND action = 'UPDATE'", Integer.class, firstId)).isEqualTo(1);
    }

    /** T4（B5-1 负向 / S2-10 防刷评）：零交付主体对 P1 提交 → 403 + 1011C0006 业务可读。 */
    @Test
    void requesterWithoutDeliveryIsRejectedByEligibility() {
        // REQUESTER_C 无任何交付行（负向对照，沿 C-7.1 附录 A 演示前提）
        final MvcResult result = postEvaluation(REQUESTER_C,
                evaluationBody(PRODUCT_P1, 3, null));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1011C0006");
        assertThat(messageOf(result)).isEqualTo("完成对该产品的有效交付后方可评价");
        assertThat(countOf("product_review")).isZero();
        assertThat(countOf("product_review_action_log")).isZero();
    }

    /** T5（B5-3）：多人统计 HALF_UP 1 位小数（4,4,4,5 → 4.25 → 4.3）；列表时间倒序全列。 */
    @Test
    void statsRoundHalfUpAndListIsNewestFirstFullList() {
        for (final String requester : List.of(REQUESTER_A, REQUESTER_B, REQUESTER_C, REQUESTER_D)) {
            insertDelivery(requester, PRODUCT_P1);
        }
        postEvaluation(REQUESTER_A, evaluationBody(PRODUCT_P1, 4, "四星"));
        postEvaluation(REQUESTER_B, evaluationBody(PRODUCT_P1, 4, null));
        postEvaluation(REQUESTER_C, evaluationBody(PRODUCT_P1, 4, null));
        postEvaluation(REQUESTER_D, evaluationBody(PRODUCT_P1, 5, "五星"));
        final MvcResult stats = getStats(REQUESTER_A, PRODUCT_P1);
        assertThat(dataOf(stats).path("averageScore").asDouble()).isEqualTo(4.3);
        assertThat(dataOf(stats).path("reviewCount").asInt()).isEqualTo(4);
        // 列表：时间倒序全列不分页（Q3-A 最小口径）；hidden 恒 false；评价人原样
        final MvcResult list = getList(REQUESTER_B, PRODUCT_P1);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        final JsonNode items = dataOf(list).path("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).path("requesterNo").asText()).isEqualTo(REQUESTER_D);
        assertThat(items.get(3).path("requesterNo").asText()).isEqualTo(REQUESTER_A);
        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).path("hidden").asBoolean()).isFalse();
        }
        assertThat(items.get(0).path("comment").asText()).isEqualTo("五星");
    }

    /** T6（mine 回显 + G-EV1 目标不存在 404 同形）。 */
    @Test
    void mineEchoesOwnReviewAndHideMissingTargetIsNotFound() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        postEvaluation(REQUESTER_A, evaluationBody(PRODUCT_P1, 4, null));
        final MvcResult mine = getMine(REQUESTER_A, PRODUCT_P1);
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(mine).path("score").asInt()).isEqualTo(4);
        assertThat(dataOf(mine).path("requesterNo").asText()).isEqualTo(REQUESTER_A);
        // 未评价者 mine → 200 + data=null（本人数据无枚举面，不走 404）
        final MvcResult noneMine = getMine(REQUESTER_B, PRODUCT_P1);
        assertThat(noneMine.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(noneMine).isNull()).isTrue();
        // G-EV1 目标不存在 → 404 + 1011C0007
        final MvcResult missing = hide(ADMIN, "admin", 99999L);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(missing)).isEqualTo("1011C0007");
        assertThat(messageOf(missing)).isEqualTo("评价不存在或不可操作");
    }

    /** T7（B5-4 / S2-12）：运营隐藏即时排除 + HIDE 留痕 + 幂等不重复留痕 + 非 admin 403 + 不物理删除。 */
    @Test
    void governanceHideExcludesWithTraceAndIsIdempotent() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        insertDelivery(REQUESTER_D, PRODUCT_P1);
        postEvaluation(REQUESTER_A, evaluationBody(PRODUCT_P1, 4, null));
        postEvaluation(REQUESTER_D, evaluationBody(PRODUCT_P1, 5, "含违规文字的评价"));
        assertThat(dataOf(getStats(ADMIN, PRODUCT_P1)).path("reviewCount").asInt()).isEqualTo(2);
        final long reviewA = dataOf(getMine(REQUESTER_A, PRODUCT_P1)).path("reviewId").asLong();
        // 非 admin 直调 hide → 权限层 403（common 口径，沿既有 1000C0005）
        final MvcResult denied = hide(REQUESTER_A, "requester", reviewA);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1000C0005");
        // admin 隐藏 → 200 + 统计/列表即时排除 + HIDE 留痕
        final MvcResult hide = hide(ADMIN, "admin", reviewA);
        assertThat(hide.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(hide).path("reviewId").asLong()).isEqualTo(reviewA);
        assertThat(dataOf(hide).path("hidden").asBoolean()).isTrue();
        final JsonNode statsAfter = dataOf(getStats(ADMIN, PRODUCT_P1));
        assertThat(statsAfter.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(statsAfter.path("averageScore").asDouble()).isEqualTo(5.0);
        final JsonNode itemsAfter = dataOf(getList(ADMIN, PRODUCT_P1)).path("items");
        assertThat(itemsAfter).hasSize(1);
        assertThat(itemsAfter.get(0).path("requesterNo").asText()).isEqualTo(REQUESTER_D);
        assertThat(hideTraceCount(reviewA)).isEqualTo(1);
        // 幂等：已隐藏再隐藏 → 200 成功且不重复留痕
        final MvcResult again = hide(ADMIN, "admin", reviewA);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(hideTraceCount(reviewA)).isEqualTo(1);
        // 不物理删除：行仍在且 hidden=1（B5-4 隐藏留痕不删除）
        assertThat(jdbc.queryForObject(
                "SELECT hidden FROM product_review WHERE id = ?", Integer.class, reviewA))
                .isEqualTo(1);
    }

    /** T8（B6 观察）：评价域零推送/轮询/流式入口（静态源码断言）。 */
    @Test
    void reviewDomainHasNoPushOrPollingEntries() throws Exception {
        final Path base = Path.of("src/main/java/com/ctds/delivery");
        for (final String file : List.of(
                "interfaces/ReviewController.java",
                "interfaces/ReviewGovernanceController.java",
                "application/review/ReviewCommandService.java",
                "application/review/ReviewQueryService.java",
                "application/review/ReviewGovernanceService.java")) {
            final String source = Files.readString(base.resolve(file), StandardCharsets.UTF_8);
            assertThat(source)
                    .doesNotContain("@Scheduled")
                    .doesNotContain("SseEmitter")
                    .doesNotContain("WebSocket")
                    .doesNotContain("MessageMapping");
        }
    }

    /** T10（修复轮 1 / hifi §1 W-EV1 必填契约 / 评审 P1-1）：缺 score → 400+1011C0005、
     * 缺 productId → 400+1000C0001（拆箱 NPE 落 500 的缺陷回归锚）。 */
    @Test
    void missingRequiredFieldsAreRejectedWithParamError() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        // 缺 score（score 必填，缺失 = 非法评分）
        final MvcResult noScore = postEvaluation(REQUESTER_A,
                "{\"productId\":" + PRODUCT_P1 + ",\"requestId\":\"" + requestIdSuffix() + "\"}");
        assertThat(noScore.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(noScore)).isEqualTo("1011C0005");
        assertThat(messageOf(noScore)).isEqualTo("评分需为 1~5 的整数");
        // 缺 productId（参数缺失 → 参数通用码）
        final MvcResult noProduct = postEvaluation(REQUESTER_A,
                "{\"score\":4,\"requestId\":\"" + requestIdSuffix() + "\"}");
        assertThat(noProduct.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(noProduct)).isEqualTo("1000C0001");
        assertThat(countOf("product_review")).isZero();
        assertThat(countOf("product_review_action_log")).isZero();
    }

    /** T11（修复轮 1 / hifi §1 §3 边界契约 / 评审 P1-2）：comment>500、productId 0/负数、
     * 读面三端点 productId=0 → 400+1000C0001；score=1 下界合法值 → 200 + 统计 1.0。 */
    @Test
    void parameterBoundariesAreEnforced() {
        insertDelivery(REQUESTER_A, PRODUCT_P1);
        // comment 501 字符（trim 不收敛）→ 1000C0001
        final MvcResult longComment = postEvaluation(REQUESTER_A,
                evaluationBody(PRODUCT_P1, 4, "超".repeat(501)));
        assertThat(longComment.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(longComment)).isEqualTo("1000C0001");
        // 写面 productId=0 与负数 → 1000C0001
        for (final long badProduct : new long[] {0L, -1L}) {
            final MvcResult bad = postEvaluation(REQUESTER_A, evaluationBody(badProduct, 4, null));
            assertThat(bad.getResponse().getStatus()).isEqualTo(400);
            assertThat(codeOf(bad)).isEqualTo("1000C0001");
        }
        // 读面三端点 productId=0 → 1000C0001（hifi §1 R-EV1/R-EV2/R-EV3 错误列）
        for (final MvcResult badRead : new MvcResult[] {getStats(REQUESTER_A, 0L),
                getList(REQUESTER_A, 0L), getMine(REQUESTER_A, 0L)}) {
            assertThat(badRead.getResponse().getStatus()).isEqualTo(400);
            assertThat(codeOf(badRead)).isEqualTo("1000C0001");
        }
        // score=1 下界合法值 → 200 + 统计 1.0
        final MvcResult lowerBound = postEvaluation(REQUESTER_A,
                evaluationBody(PRODUCT_P1, 1, "下界一星"));
        assertThat(lowerBound.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(lowerBound).path("score").asInt()).isEqualTo(1);
        assertThat(dataOf(getStats(REQUESTER_A, PRODUCT_P1)).path("averageScore").asDouble())
                .isEqualTo(1.0);
    }

    // ===== 造数与调用支撑 =====

    /** 交付行直插（资格数据源；落行即 SUCCESS，沿演示数据预置等效手法）。 */
    private void insertDelivery(final String requesterNo, final long productId) {
        jdbc.update("INSERT INTO delivery (delivery_no, contract_no, product_id, product_name, "
                        + "delivery_type, requester_no, provider_no, result, idempotency_key) "
                        + "VALUES (?, ?, ?, ?, 'API', ?, ?, 'SUCCESS', ?)",
                "DL9" + requestIdSuffix(), "CO000001", productId, "演示数据集",
                requesterNo, "S-prov", "idem-" + requestIdSuffix());
    }

    private MvcResult postEvaluation(final String subject, final String body) {
        try {
            return mockMvc.perform(post("/api/v1/evaluations")
                    .header("X-Ctds-Subject", subject)
                    .header("X-Ctds-Roles", "requester")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andReturn();
        } catch (final Exception e) {
            throw new IllegalStateException("评价提交调用失败", e);
        }
    }

    private MvcResult getStats(final String subject, final long productId) {
        try {
            return mockMvc.perform(get("/api/v1/evaluations/products/" + productId + "/stats")
                    .header("X-Ctds-Subject", subject)
                    .header("X-Ctds-Roles", "requester")).andReturn();
        } catch (final Exception e) {
            throw new IllegalStateException("统计调用失败", e);
        }
    }

    private MvcResult getList(final String subject, final long productId) {
        try {
            return mockMvc.perform(get("/api/v1/evaluations/products/" + productId)
                    .header("X-Ctds-Subject", subject)
                    .header("X-Ctds-Roles", "requester")).andReturn();
        } catch (final Exception e) {
            throw new IllegalStateException("列表调用失败", e);
        }
    }

    private MvcResult getMine(final String subject, final long productId) {
        try {
            return mockMvc.perform(get("/api/v1/evaluations/mine")
                    .queryParam("productId", String.valueOf(productId))
                    .header("X-Ctds-Subject", subject)
                    .header("X-Ctds-Roles", "requester")).andReturn();
        } catch (final Exception e) {
            throw new IllegalStateException("mine 调用失败", e);
        }
    }

    private MvcResult hide(final String subject, final String roles, final long reviewId) {
        try {
            return mockMvc.perform(post("/api/v1/evaluations/" + reviewId + "/hide")
                    .header("X-Ctds-Subject", subject)
                    .header("X-Ctds-Roles", roles)).andReturn();
        } catch (final Exception e) {
            throw new IllegalStateException("隐藏调用失败", e);
        }
    }

    private String evaluationBody(final long productId, final int score, final String comment) {
        final String commentPart = comment == null ? "" : ",\"comment\":\"" + comment + "\"";
        return "{\"productId\":" + productId + ",\"score\":" + score + commentPart
                + ",\"requestId\":\"" + requestIdSuffix() + "\"}";
    }

    private int hideTraceCount(final long reviewId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_review_action_log "
                + "WHERE review_id = ? AND action = 'HIDE'", Integer.class, reviewId);
    }

    private int countOf(final String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String requestIdSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode dataOf(final MvcResult result) {
        return readTree(result).path("data");
    }

    private String codeOf(final MvcResult result) {
        return readTree(result).path("code").asText();
    }

    private String messageOf(final MvcResult result) {
        return readTree(result).path("message").asText();
    }

    private JsonNode readTree(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse()
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException("响应解析失败", e);
        }
    }
}
