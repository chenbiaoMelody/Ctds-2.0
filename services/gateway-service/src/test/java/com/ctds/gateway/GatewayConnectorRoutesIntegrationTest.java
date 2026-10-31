package com.ctds.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.gateway.config.GatewayProperties;
import com.ctds.gateway.support.RecordingAuditConfiguration;
import com.ctds.gateway.support.StubServer;
import com.ctds.gateway.support.TestTokens;
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
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 连接器六门面网关扩路由契约（WBS-3.6.8 W1，hifi §1-A）：六条<b>普通代理</b>路由
 * （subject / did / datasets / data-spaces / contracts / deliveries）装配与转发。
 *
 * <ul>
 *   <li><b>T1-①</b>：六条路由装配（id 唯一、单 {@code Path=} 谓词、filters 为空）；</li>
 *   <li><b>T1-②</b>：新路由转发到配置路径模板的实例路径（白名单外不进交付判定链——
 *       无合约头放行、判定器零调用、计量零触发）；</li>
 *   <li><b>T1-③</b>：既有探针路由（交付白名单）不回归——判定链语义零改动。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayConnectorRoutesIntegrationTest {

    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    /** 六门面路由增量（hifi §1-A 前置检查③实测回填：六条，各一 id 一 Path）。 */
    private static final List<String[]> NEW_ROUTES = List.of(
            new String[] {"connector-identity-subject", "/api/v1/subject/**"},
            new String[] {"connector-identity-did", "/api/v1/did/**"},
            new String[] {"connector-resource-datasets", "/api/v1/datasets/**"},
            new String[] {"connector-resource-spaces", "/api/v1/data-spaces/**"},
            new String[] {"connector-contract", "/api/v1/contracts/**"},
            new String[] {"connector-delivery", "/api/v1/deliveries/**"});

    private static final String REQUESTER = "S-req-368";

    @DynamicPropertySource
    static void gatewayProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.auth.jwt.secret", () -> TestTokens.TEST_SECRET);
        // 六条新路由下游统一指向同一 stub（转发断言看实收路径，不看下游差异）
        registry.add("CTDS_GATEWAY_SUBJECT_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("CTDS_GATEWAY_DID_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("CTDS_GATEWAY_SPACE_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("CTDS_GATEWAY_CATALOG_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("CTDS_GATEWAY_CONTRACT_BASEURL", () -> UPSTREAM.baseUrl());
        registry.add("CTDS_GATEWAY_DELIVERY_BASEURL", () -> UPSTREAM.baseUrl());
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
    private GatewayProperties properties;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        UPSTREAM.resetCounters();
        JUDGE.resetCounters();
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\"}");
        JUDGE.respondAllowed(REQUESTER, "S-prov-368");
    }

    @Test
    void sixConnectorRoutesAssembledWithPlainPathPredicates() {
        for (final String[] expected : NEW_ROUTES) {
            final GatewayProperties.Route route = properties.getRoutes().stream()
                    .filter(item -> expected[0].equals(item.getId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "连接器路由 " + expected[0] + " 不存在（hifi §1-A，W1 扩路由）"));
            assertThat(route.getPredicates())
                    .as("路由 %s 谓词 = 单 Path 模板（普通代理）", expected[0])
                    .containsExactly("Path=" + expected[1]);
            assertThat(route.getFilters())
                    .as("路由 %s 无路由级过滤器（V1.0 形态约束）", expected[0])
                    .isEmpty();
            assertThat(route.getUri())
                    .as("路由 %s 下游已配置", expected[0])
                    .isNotBlank();
        }
    }

    @Test
    void connectorRouteForwardsWithoutDeliveryJudgment() {
        final String path = "/api/v1/subject/registrations/S000018";
        client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("connector")))
                .exchange().expectStatus().isOk();

        assertThat(UPSTREAM.requestCount()).as("普通代理转发上游一次").isEqualTo(1);
        assertThat(UPSTREAM.lastPath()).as("上游实收路径 = 请求实例路径").isEqualTo(path);
        assertThat(JUDGE.requestCount()).as("白名单外路由不进交付判定链（判定器零调用）")
                .isEqualTo(0);
    }

    @Test
    void connectorDeliveryRouteForwardsInstancePath() {
        final String path = "/api/v1/deliveries/DL000018";
        client.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("requester")))
                .exchange().expectStatus().isOk();

        assertThat(UPSTREAM.lastPath()).as("交付读面经网关转发").isEqualTo(path);
        assertThat(JUDGE.requestCount()).as("读面不触发判定链").isEqualTo(0);
    }

    @Test
    void deliveryWhitelistUnchanged() {
        // 判定链白名单零改动锚：六门面普通代理路由不进白名单，既有语义不回归
        assertThat(properties.getDelivery().getRoutes())
                .as("交付判定链白名单仅含既有探针路由（W1 零改动）")
                .containsExactly("delivery-catalog-products");
    }
}
