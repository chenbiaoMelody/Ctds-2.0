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
 * 交付探针路由契约（DB-47 缺陷 A，hifi §5-3"候选探针路由按实测确定并登记"）：
 * <ul>
 *   <li><b>T-A1a</b>：探针路由谓词指向 catalog <b>真实读面</b> {@code /api/v1/data-products/**}；</li>
 *   <li><b>T-A1b（反向探针）</b>：路由表中不得含 catalog 不存在路径 {@code /api/v1/catalog/products}
 *       （防回归——旧缺陷形态一旦重现即红）；</li>
 *   <li><b>T-A2</b>：真链放行请求转发到配置路径模板的<b>实例路径</b>（上游 stub 实收路径断言）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.gateway.rate-limit.enabled=false",
            "ctds.gateway.policy.timeout=3s"
        })
@Import(RecordingAuditConfiguration.class)
class GatewayProbeRouteIntegrationTest {

    private static final StubServer UPSTREAM = new StubServer();
    private static final StubServer JUDGE = new StubServer();

    private static final String PROBE_ROUTE_ID = "delivery-catalog-products";
    private static final String PROBE_TEMPLATE = "/api/v1/data-products/**";
    private static final String LEGACY_NON_EXISTENT_PATH = "/api/v1/catalog/products";
    private static final String REQUESTER = "S-req-47";
    private static final String PROVIDER = "S-prov-47";
    private static final String CONTRACT_NO = "CO000047";
    private static final String DELIVERY_PATH = "/api/v1/data-products/12";

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
    private GatewayProperties properties;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        UPSTREAM.resetCounters();
        JUDGE.resetCounters();
        UPSTREAM.respondUpstream(HttpStatus.OK.value(), "{\"code\":\"0\"}");
        JUDGE.respondAllowed(REQUESTER, PROVIDER);
    }

    @Test
    void probeRoutePredicateTargetsRealCatalogReadPath() {
        final GatewayProperties.Route route = properties.getRoutes().stream()
                .filter(item -> PROBE_ROUTE_ID.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("探针路由 " + PROBE_ROUTE_ID + " 不存在（hifi §5-1）"));

        assertThat(route.getPredicates())
                .as("探针路由谓词 = catalog 真实读面（DB-47 缺陷 A 修复锚）")
                .containsExactly("Path=" + PROBE_TEMPLATE);
    }

    @Test
    void noRouteTargetsCatalogNonExistentLegacyPath() {
        assertThat(properties.getRoutes())
                .as("反向探针：路由表不得指向 catalog 不存在路径（缺陷 A 防回归）")
                .allSatisfy(route -> assertThat(route.getPredicates())
                        .allSatisfy(predicate -> assertThat(predicate).doesNotContain(LEGACY_NON_EXISTENT_PATH)));
    }

    @Test
    void allowedDeliveryIsForwardedToConfiguredPathInstance() {
        client.get().uri(DELIVERY_PATH)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestTokens.valid(REQUESTER, List.of("provider")))
                .header("X-Ctds-Contract-No", CONTRACT_NO)
                .exchange().expectStatus().isOk();

        assertThat(UPSTREAM.requestCount()).as("放行后触达上游一次").isEqualTo(1);
        assertThat(UPSTREAM.lastPath()).as("上游实收路径 = 配置路径模板实例（缺陷 A 取货腿）")
                .isEqualTo(DELIVERY_PATH);
    }
}
