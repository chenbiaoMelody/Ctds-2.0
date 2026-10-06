package com.ctds.contract.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.CatalogProductPort.CatalogProductResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 目录产品事实客户端三态映射单测（WBS-3.4.3 hifi §7 U 锚；JDK HttpServer 桩，沿
 * SubjectAdmissionClientTest 先例）：在架产品 → FOUND（6 字段全量）；产品不存在业务答复
 * （1007C0011）→ NOT_FOUND；不可达/非 200/非 0 码/解析失败/未配置 = UNAVAILABLE 不冒充
 * 产品状态（防枚举口径——hifi §3 1008C0010/1008S0003 分工）。
 */
class CatalogProductClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<List<String>> subjectHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> roleHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<String> requestPath = new AtomicReference<>("");

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/catalog/internal/data-products", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            subjectHeaders.set(exchange.getRequestHeaders().get("X-Ctds-Subject"));
            roleHeaders.set(exchange.getRequestHeaders().get("X-Ctds-Roles"));
            respond(exchange);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void respond(final HttpExchange exchange) throws IOException {
        final byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private CatalogProductClient client(final String base) {
        return new CatalogProductClient(base, MAPPER);
    }

    @Test
    void listedProductMapsToFoundWithSixFields() {
        responseBody.set("{\"code\":\"0\",\"data\":{\"productId\":12,\"productName\":\"城市餐饮单位数据集\","
                + "\"status\":\"LISTED\",\"providerSubjectNo\":\"S-provider\","
                + "\"pricingModel\":\"PER_CALL\",\"priceAmount\":1.50}}");
        final CatalogProductResult result = client(baseUrl).fetch(12L);
        assertThat(result.state().name()).isEqualTo("FOUND");
        assertThat(result.product().productId()).isEqualTo(12L);
        assertThat(result.product().productName()).isEqualTo("城市餐饮单位数据集");
        assertThat(result.product().status()).isEqualTo("LISTED");
        assertThat(result.product().providerSubjectNo()).isEqualTo("S-provider");
        assertThat(result.product().pricingModel()).isEqualTo("PER_CALL");
        assertThat(result.product().priceAmount()).isEqualByComparingTo("1.5");
        assertThat(requestPath.get()).isEqualTo("/api/v1/catalog/internal/data-products/12");
        assertThat(subjectHeaders.get()).containsExactly("contract-service");
        assertThat(roleHeaders.get()).containsExactly("contract-internal");
    }

    @Test
    void rawStatusPassesThroughForNonListedProducts() {
        // 内部端点承载原始状态值（DELISTED 等）——门槛判定在应用服务（hifi §7 catalog 随卡测试锚）
        responseBody.set("{\"code\":\"0\",\"data\":{\"productId\":13,\"productName\":\"已下架产品\","
                + "\"status\":\"DELISTED\",\"providerSubjectNo\":\"S-provider\","
                + "\"pricingModel\":\"FREE\"}}");
        final CatalogProductResult result = client(baseUrl).fetch(13L);
        assertThat(result.state().name()).isEqualTo("FOUND");
        assertThat(result.product().status()).isEqualTo("DELISTED");
        assertThat(result.product().priceAmount()).isNull();
    }

    @Test
    void productNotFoundBusinessAnswerMapsToNotFound() {
        // 1007C0011 = 产品不存在/未在架业务答复 → NOT_FOUND（不存在同形——防枚举）
        responseBody.set("{\"code\":\"1007C0011\",\"message\":\"产品不存在或未在架\"}");
        assertThat(client(baseUrl).fetch(99L).state().name()).isEqualTo("NOT_FOUND");
    }

    @Test
    void httpFailureAndBadCodeAndBadBodyMapToUnavailable() {
        responseStatus.set(500);
        assertThat(client(baseUrl).fetch(1L).state().name()).isEqualTo("UNAVAILABLE");
        responseStatus.set(200);
        responseBody.set("{\"code\":\"1007S9999\",\"message\":\"系统繁忙\"}");
        assertThat(client(baseUrl).fetch(1L).state().name()).isEqualTo("UNAVAILABLE");
        responseBody.set("not-json");
        assertThat(client(baseUrl).fetch(1L).state().name()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void unreachableServiceAndBlankBaseUrlMapToUnavailable() {
        assertThat(client("http://127.0.0.1:1").fetch(1L).state().name())
                .isEqualTo("UNAVAILABLE");
        assertThat(client("").fetch(1L).state().name()).isEqualTo("UNAVAILABLE");
        assertThat(client("   ").fetch(1L).state().name()).isEqualTo("UNAVAILABLE");
    }
}
