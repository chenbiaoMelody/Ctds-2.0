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

/**
 * 网关交付链真链集成测试（hifi §9：T2 头处置 / T3 未认证 / T5 策略执行点 / T6 凭据与发起人 /
 * T8 计量对账）——真实 REACTIVE 应用 + 真实 HTTP，上游与判定均为本地 reactor-netty stub
 * （断言"上游请求计数"，越界与 fail-closed 时必须为 0）。
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

    private static final String REQUESTER = "S-req-352";
    private static final String PROVIDER = "S-prov-352";
    private static final String CONTRACT_NO = "CT-2026-0001";
    private static final String DELIVERY_PATH = "/api/v1/catalog/products/12";
    private static final String TRACE_ID = "trace-352-abc";
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
    }

    // ==== T5 策略执行点（双向）====

    @Test
    void allowedDeliveryIsForwardedAndMeteredOnce() {
        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .header("X-Ctds-Purpose", "风控建模")
                .header("X-Ctds-Territory", "本市域")
                .header("X-Trace-Id", TRACE_ID)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.productId").isEqualTo(12);

        assertThat(JUDGE.requestCount()).as("转发前必须调用一次判定").isEqualTo(1);
        assertThat(UPSTREAM.requestCount()).as("放行后触达数据源一次").isEqualTo(1);
        final List<AuditEvent> metering = meteringEvents();
        assertThat(metering).hasSize(1);
        final AuditEvent event = metering.get(0);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.targetId()).isEqualTo(CONTRACT_NO);
        assertThat(event.actor()).isEqualTo(REQUESTER);
        assertThat(event.detail().get("traceId")).isEqualTo(TRACE_ID);
        assertThat(event.detail().get("result")).isEqualTo("ALLOWED");
        assertThat(event.detail().get("resource"))
                .as("被交付资源 = routeId + 方法 + 路径模板（不含原始路径与查询串）")
                .isEqualTo("delivery-catalog-products GET /api/v1/catalog/products/**");
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
        assertThat(deniedEvents()).hasSize(1);
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
        assertThat(meteringEvents()).hasSize(1);
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

        assertThat(meteringEvents()).as("放行 3 次 ≡ 计量事件 3 条").hasSize(3);
        assertThat(deniedEvents()).as("拒绝 2 次 ≡ 计量 0 条（拒绝留痕独立）").hasSize(2);
        assertThat(UPSTREAM.requestCount()).isEqualTo(3);
    }

    @Test
    void auditEventsCarryNoTokenOrPayload() {
        final String token = TestTokens.valid(REQUESTER, List.of("provider"));

        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isOk();

        final List<AuditEvent> events = recorder().events();
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
                .header("X-Ctds-Purpose", "风控建模")
                .header("X-Ctds-Territory", "本市域")
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

    private static JsonNode readTree(final byte[] body) {
        try {
            return MAPPER.readTree(body == null ? new byte[0] : body);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
