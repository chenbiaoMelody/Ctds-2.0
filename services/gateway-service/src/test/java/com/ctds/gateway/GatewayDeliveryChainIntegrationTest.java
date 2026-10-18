package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.common.auth.AuthAdvice;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.gateway.audit.GatewayAuditActions;
import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.RecordingAuditRecorder;
import com.ctds.gateway.support.StubServer;
import com.ctds.gateway.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriUtils;

/**
 * 网关交付链真链集成测试（hifi §9：T2 头处置 / T3 未认证 / T5 策略执行点 / T6 凭据与发起人 /
 * T8 计量对账）——真实 REACTIVE 应用 + 真实 HTTP，上游与判定均为本地 reactor-netty stub
 * （断言"上游请求计数"，越界与 fail-closed 时必须为 0）。
 *
 * <p><b>策略要素编码（hifi §5-2，CHG-WBS-3.5.2-hifi-V1.1）</b>：非 ASCII 要素头由测试<b>显式
 * 百分号编码</b>发送并断言判定收到解码后原值；另含非法编码 / 解码后超长两类负向。</p>
 *
 * <p><b>事件最终一致不变式（DB-46⑩ 修复，卡2a）</b>：计量/拒绝留痕记录于响应链完成后的续段
 * （{@code DeliveryMeteringGlobalFilter} 的 {@code chain.filter(exchange).then(Mono.deferContextual(...))}），
 * 与 {@code exchange()} 返回无时序保证。故<strong>每个产事件的测试在结束前都已通过有界等待
 * （{@link #awaitEventsOf} / {@link #awaitAllEvents}）等待其事件到达</strong>——等待预算 5s
 * （录制替身为进程内内存列表，只覆盖"续段尚未执行"的调度窗口；到点未达即按实际计数失败，
 * 绝不等同于放宽）。由此，后续测试中的"事件为空"断言不可能被上一测试的迟到事件污染。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayDeliveryChainIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    /** 有界等待预算（DB-46⑩）：截止期 5s、步进 20ms——覆盖续段调度窗口，到点未达即红。 */
    private static final long AWAIT_BUDGET_NANOS = Duration.ofSeconds(5).toNanos();
    private static final long AWAIT_STEP_MILLIS = 20;

    private static final String REQUESTER = "S-req-352";
    private static final String PROVIDER = "S-prov-352";
    private static final String CONTRACT_NO = "CT-2026-0001";
    private static final String DELIVERY_PATH = "/api/v1/data-products/12";
    private static final String TRACE_ID = "trace-352-abc";

    /**
     * 策略要素（中文）与显式 URL 百分号编码值（hifi §5-2，CHG-WBS-3.5.2-hifi-V1.1）：
     * 测试必须显式编码（不得依赖 HTTP 客户端自动编码——中文会被编成 {@code ?} 造成哑绿，
     * 走查缺陷 C 教训）；断言判定 stub 收到<b>解码后原值</b>。
     */
    private static final String PURPOSE_CN = "风控建模";
    private static final String TERRITORY_CN = "本市域";
    private static final String PURPOSE_ENCODED =
            UriUtils.encode(PURPOSE_CN, StandardCharsets.UTF_8);
    private static final String TERRITORY_ENCODED =
            UriUtils.encode(TERRITORY_CN, StandardCharsets.UTF_8);
    private static final String UPSTREAM_BODY = "{\"code\":\"0\",\"data\":{\"productId\":12}}";
    private static final String OVER_LIMIT_CODE = "1008C0020";
    private static final String OVER_LIMIT_MESSAGE = "越界使用被拒绝，触发要素见留痕";
    private static final String STATE_FORBIDDEN_CODE = "1008C0013";
    private static final String STATE_FORBIDDEN_MESSAGE = "合约当前状态不允许该操作";
    private static final String NOT_VISIBLE_CODE = "1008C0012";
    private static final String NOT_VISIBLE_MESSAGE = "合约不存在或不可见";

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        registry.add("CTDS_GATEWAY_CATALOG_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("ctds.gateway.policy.base-url", () -> JUDGE.baseUrl());
    }

    @AfterAll
    static void stopStubs() {
        UPSTREAM.close();
        JUDGE.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AuditRecorder auditRecorder;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        UPSTREAM.resetCounters();
        JUDGE.resetCounters();
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), UPSTREAM_BODY);
        JUDGE.respondAllowed(REQUESTER, PROVIDER);
        recorder().clear();
    }

    // ==== T3 未认证（负向 + 边界）：401 + 1000C0002 + traceId 非 "-" ====

    @Test
    void unauthenticatedDeliveryIsRejectedWithTraceIdAndNoUpstream() {
        final JsonNode body = errorBody(HttpStatus.UNAUTHORIZED, null, CONTRACT_NO, TRACE_ID);

        assertThat(body.path("code").asText()).isEqualTo("1000C0002");
        assertThat(body.path("traceId").asText()).as("网关自建 401 封套带真实追踪号").isEqualTo(TRACE_ID);
        assertThat(body.path("message").asText()).as("对外文案 = 服务端常量（不回显输入、不暴露内部）")
                .isEqualTo(AuthAdvice.UNAUTHORIZED_MESSAGE);
        assertThat(UPSTREAM.requestCount()).as("未认证不触达数据源").isZero();
        assertThat(JUDGE.requestCount()).as("未认证不调用判定").isZero();
        assertThat(meteringEvents()).as("未认证不产生计量事件").isEmpty();
    }

    @Test
    void unauthenticatedDeliveryGeneratesTraceIdWhenAbsent() {
        final EntityExchangeResult<byte[]> result = client.post().uri(DELIVERY_PATH)
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isUnauthorized().expectBody().returnResult();

        final JsonNode body = readTree(result.getResponseBody());
        assertThat(body.path("code").asText()).isEqualTo("1000C0002");
        assertThat(body.path("traceId").asText()).isNotEqualTo("-").isNotBlank();
        assertThat(result.getResponseHeaders().getFirst("X-Trace-Id")).isNotBlank();
    }

    @Test
    void expiredAndWronglySignedTokensAreRejected() {
        assertThat(errorBody(HttpStatus.UNAUTHORIZED, TestTokens.expired(REQUESTER), CONTRACT_NO, null)
                .path("code").asText()).isEqualTo("1000C0002");
        assertThat(errorBody(HttpStatus.UNAUTHORIZED, TestTokens.wrongSignature(REQUESTER), CONTRACT_NO,
                null).path("code").asText()).isEqualTo("1000C0002");
        assertThat(UPSTREAM.requestCount()).isZero();
        assertThat(JUDGE.requestCount()).isZero();
    }

    // ==== T2 头处置真链（正/反）====

    @Test
    void forgedIdentityHeadersAreStrippedAndAuthoritativeValuesInjected() {
        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("provider", "reviewer")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .header(StubServer.SUBJECT_HEADER, "attacker")
                .header(StubServer.ROLES_HEADER, "admin,governance")
                .exchange().expectStatus().isOk();

        assertThat(UPSTREAM.requestCount()).isEqualTo(1);
        assertThat(UPSTREAM.subjectHeaders()).as("伪造主体头必须被剥离，下游只见网关注入值")
                .containsExactly(REQUESTER);
        assertThat(UPSTREAM.rolesHeaders()).containsExactly("provider,reviewer");
        assertThat(awaitAllEvents(1)).as("放行即计量 1:1（DB-46⑩ 不变式：产事件测试必先等待其事件到达）")
                .hasSize(1);
    }

    @Test
    void nonStringRoleEntriesAreSkipped() {
        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.validWithMixedRoles(REQUESTER))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isOk();

        assertThat(UPSTREAM.rolesHeaders()).as("非字符串角色项跳过，不注入角色头")
                .containsExactly("provider");
        assertThat(awaitAllEvents(1)).as("放行即计量 1:1（DB-46⑩ 不变式：产事件测试必先等待其事件到达）")
                .hasSize(1);
    }

    // ==== T5 策略执行点（双向）====

    @Test
    void allowedDeliveryIsForwardedAndMeteredOnce() {
        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .header("X-Ctds-Purpose", PURPOSE_ENCODED)
                .header("X-Ctds-Territory", TERRITORY_ENCODED)
                .header("X-Trace-Id", TRACE_ID)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.productId").isEqualTo(12);

        assertThat(JUDGE.requestCount()).as("转发前必须调用一次判定").isEqualTo(1);
        assertThat(JUDGE.lastBody())
                .as("判定收到解码后的要素原值（中文策略要素经百分号编码可达判定）")
                .contains("\"purpose\":\"" + PURPOSE_CN + "\"")
                .contains("\"territory\":\"" + TERRITORY_CN + "\"");
        assertThat(UPSTREAM.requestCount()).as("放行后触达数据源一次").isEqualTo(1);
        final List<AuditEvent> metering = awaitEventsOf(GatewayAuditActions.DELIVERY_METERING, 1);
        assertThat(metering).hasSize(1);
        final AuditEvent event = metering.get(0);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.targetId()).isEqualTo(CONTRACT_NO);
        assertThat(event.actor()).isEqualTo(REQUESTER);
        assertThat(event.detail().get("traceId")).isEqualTo(TRACE_ID);
        assertThat(event.detail().get("result")).isEqualTo("ALLOWED");
        assertThat(event.detail().get("resource"))
                .as("被交付资源 = routeId + 方法 + 路径模板（不含原始路径与查询串）")
                .isEqualTo("delivery-catalog-products GET /api/v1/data-products/**");
    }

    @Test
    void outOfScopeUsageIsPassedThroughWithOriginalCodeAndUpstreamUntouched() {
        JUDGE.respondContractError(HttpStatus.FORBIDDEN.value(), OVER_LIMIT_CODE, OVER_LIMIT_MESSAGE);

        final JsonNode body = deliveryBody(HttpStatus.FORBIDDEN, REQUESTER);

        assertThat(body.path("code").asText()).as("越界原码透传（禁止改写为 1009 段）")
                .isEqualTo(OVER_LIMIT_CODE);
        assertThat(body.path("message").asText()).isEqualTo(OVER_LIMIT_MESSAGE);
        assertThat(UPSTREAM.requestCount()).as("越界不触达数据源").isZero();
        assertThat(meteringEvents()).as("越界不产生计量事件").isEmpty();
        assertThat(awaitEventsOf(GatewayAuditActions.DELIVERY_DENIED, 1)).hasSize(1);
        assertThat(deniedEvents().get(0).detail().get("result")).isEqualTo(OVER_LIMIT_CODE);
    }

    @Test
    void ineffectiveContractStateIsPassedThroughAsConflict() {
        JUDGE.respondContractError(HttpStatus.CONFLICT.value(), STATE_FORBIDDEN_CODE,
                STATE_FORBIDDEN_MESSAGE);

        final JsonNode body = deliveryBody(HttpStatus.CONFLICT, REQUESTER);

        assertThat(body.path("code").asText()).isEqualTo(STATE_FORBIDDEN_CODE);
        assertThat(body.path("message").asText()).isEqualTo(STATE_FORBIDDEN_MESSAGE);
        assertThat(UPSTREAM.requestCount()).isZero();
        assertThat(meteringEvents()).isEmpty();
    }

    @Test
    void nonParticipantIsIndistinguishableFromMissingContract() {
        JUDGE.respondContractError(HttpStatus.NOT_FOUND.value(), NOT_VISIBLE_CODE,
                NOT_VISIBLE_MESSAGE);
        final JsonNode nonParticipant = deliveryBody(HttpStatus.NOT_FOUND, REQUESTER);
        final JsonNode missing = deliveryBody(HttpStatus.NOT_FOUND, "S-ghost-352");

        assertThat(nonParticipant.path("code").asText()).isEqualTo(NOT_VISIBLE_CODE);
        assertThat(missing.path("code").asText()).as("防枚举：同码").isEqualTo(NOT_VISIBLE_CODE);
        assertThat(missing.path("message").asText()).as("防枚举：同文")
                .isEqualTo(nonParticipant.path("message").asText());
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    // ==== T6 凭据与发起人（双向）====

    @Test
    void missingCredentialIsRejectedWithoutJudging() {
        final JsonNode body = errorBody(HttpStatus.BAD_REQUEST,
                TestTokens.valid(REQUESTER, List.of("provider")), null, null);

        assertThat(body.path("code").asText()).isEqualTo("1009C0001");
        assertThat(JUDGE.requestCount()).as("凭据缺失不调用判定").isZero();
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    @Test
    void malformedCredentialIsRejected() {
        final JsonNode body = errorBody(HttpStatus.BAD_REQUEST,
                TestTokens.valid(REQUESTER, List.of("provider")), "CT 2026/0001#", null);
        final JsonNode tooLong = errorBody(HttpStatus.BAD_REQUEST,
                TestTokens.valid(REQUESTER, List.of("provider")), "C".repeat(65), null);

        assertThat(body.path("code").asText()).isEqualTo("1009C0001");
        assertThat(tooLong.path("code").asText()).as("合约编号头超长（>64）同码同文")
                .isEqualTo("1009C0001");
        assertThat(JUDGE.requestCount()).isZero();
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    @Test
    void providerInitiatedDeliveryIsForbidden() {
        JUDGE.respondAllowed(REQUESTER, PROVIDER);

        final JsonNode body = deliveryBody(HttpStatus.FORBIDDEN, PROVIDER);

        assertThat(body.path("code").asText()).as("发起人唯一：提供方不可发起交付")
                .isEqualTo("1009C0003");
        assertThat(UPSTREAM.requestCount()).isZero();
        assertThat(meteringEvents()).isEmpty();
    }

    @Test
    void requesterInitiatedDeliveryIsForwarded() {
        JUDGE.respondAllowed(REQUESTER, PROVIDER);

        deliveryBody(HttpStatus.OK, REQUESTER);

        assertThat(UPSTREAM.requestCount()).isEqualTo(1);
        assertThat(awaitEventsOf(GatewayAuditActions.DELIVERY_METERING, 1)).hasSize(1);
    }

    @Test
    void malformedElementEncodingIsRejectedWithoutJudging() {
        final JsonNode body = readTree(client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .header("X-Ctds-Purpose", "%ZZ")
                .exchange().expectStatus().isBadRequest()
                .expectBody().returnResult().getResponseBody());

        assertThat(body.path("code").asText()).as("非法百分号序列 → 1000C0001（平台公共段参数码）")
                .isEqualTo("1000C0001");
        assertThat(body.path("message").asText())
                .isEqualTo("策略要素编码不合法");
        assertThat(JUDGE.requestCount()).as("要素非法不调用判定").isZero();
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    @Test
    void overlongDecodedElementIsRejected() {
        final JsonNode body = readTree(client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .header("X-Ctds-Territory", "A".repeat(257))
                .exchange().expectStatus().isBadRequest()
                .expectBody().returnResult().getResponseBody());

        assertThat(body.path("code").asText()).as("解码后超长（>256）→ 1000C0001")
                .isEqualTo("1000C0001");
        assertThat(JUDGE.requestCount()).isZero();
        assertThat(UPSTREAM.requestCount()).isZero();
    }

    // ==== T8 计量对账（双向）与事件字段最小必要 ====

    @Test
    void meteringReconcilesOneToOneWithAllowedCallsAndZeroForDenied() {
        JUDGE.respondAllowed(REQUESTER, PROVIDER);
        for (int i = 0; i < 3; i++) {
            deliveryBody(HttpStatus.OK, REQUESTER);
        }
        JUDGE.respondContractError(HttpStatus.FORBIDDEN.value(), OVER_LIMIT_CODE, OVER_LIMIT_MESSAGE);
        for (int i = 0; i < 2; i++) {
            deliveryBody(HttpStatus.FORBIDDEN, "S-denied-" + i);
        }

        assertThat(awaitEventsOf(GatewayAuditActions.DELIVERY_METERING, 3)).as("放行 3 次 ≡ 计量事件 3 条").hasSize(3);
        assertThat(awaitEventsOf(GatewayAuditActions.DELIVERY_DENIED, 2)).as("拒绝 2 次 ≡ 计量 0 条（拒绝留痕独立）").hasSize(2);
        assertThat(UPSTREAM.requestCount()).isEqualTo(3);
    }

    @Test
    void auditEventsCarryNoTokenOrPayload() {
        final String token = TestTokens.valid(REQUESTER, List.of("provider"));

        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isOk();

        final List<AuditEvent> events = awaitAllEvents(1);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).detail().keySet())
                .containsExactlyInAnyOrder("traceId", "subject", "contractNo", "resource", "result");
        assertThat(events.toString()).doesNotContain(token).doesNotContain("Bearer")
                .doesNotContain(UPSTREAM_BODY);
    }

    // ==== 辅助 ====

    /** 交付调用（带合法令牌与凭据），断言期望状态并回读响应体。 */
    private JsonNode deliveryBody(final HttpStatus expected, final String subject) {
        return readTree(client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid(subject,
                        List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .header("X-Ctds-Purpose", PURPOSE_ENCODED)
                .header("X-Ctds-Territory", TERRITORY_ENCODED)
                .exchange().expectStatus().isEqualTo(expected.value())
                .expectBody().returnResult().getResponseBody());
    }

    /** 错误路径调用（令牌可空 = 未认证；凭据可空 = 缺头）。 */
    private JsonNode errorBody(final HttpStatus expected, final String token,
            final String contractNo, final String traceId) {
        final WebTestClient.RequestHeadersSpec<?> spec =
                client.get().uri(DELIVERY_PATH);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (contractNo != null) {
            spec.header("X-Ctds-Contract-No", contractNo);
        }
        if (traceId != null) {
            spec.header("X-Trace-Id", traceId);
        }
        return readTree(spec.exchange().expectStatus().isEqualTo(expected.value())
                .expectBody().returnResult().getResponseBody());
    }

    private RecordingAuditRecorder recorder() {
        return (RecordingAuditRecorder) auditRecorder;
    }

    private List<AuditEvent> meteringEvents() {
        return recorder().eventsOf(GatewayAuditActions.DELIVERY_METERING);
    }

    private List<AuditEvent> deniedEvents() {
        return recorder().eventsOf(GatewayAuditActions.DELIVERY_DENIED);
    }

    /**
     * 有界等待（DB-46⑩）：截止期（{@link #AWAIT_BUDGET_NANOS}，步进 {@link #AWAIT_STEP_MILLIS}）
     * 轮询直到指定 action 的事件数达到 {@code expected}。到点未达<strong>不抛异常、不阻塞</strong>，
     * 原样返回当前快照——由调用方断言语义以<strong>实际计数</strong>失败（等待只吸收"续段尚未
     * 执行"的调度窗口，绝不等同于放宽：等不到照样红）。
     */
    private List<AuditEvent> awaitEventsOf(final String action, final int expected) {
        final long deadline = System.nanoTime() + AWAIT_BUDGET_NANOS;
        List<AuditEvent> snapshot = recorder().eventsOf(action);
        while (snapshot.size() < expected && System.nanoTime() < deadline) {
            sleepQuietly(AWAIT_STEP_MILLIS);
            snapshot = recorder().eventsOf(action);
        }
        return snapshot;
    }

    /** 有界等待全部事件（同 {@link #awaitEventsOf} 口径，不过滤 action）。 */
    private List<AuditEvent> awaitAllEvents(final int expected) {
        final long deadline = System.nanoTime() + AWAIT_BUDGET_NANOS;
        List<AuditEvent> snapshot = recorder().events();
        while (snapshot.size() < expected && System.nanoTime() < deadline) {
            sleepQuietly(AWAIT_STEP_MILLIS);
            snapshot = recorder().events();
        }
        return snapshot;
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonNode readTree(final byte[] body) {
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
