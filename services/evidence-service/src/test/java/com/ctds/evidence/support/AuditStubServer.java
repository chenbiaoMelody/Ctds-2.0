package com.ctds.evidence.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨域 audit-trail 桩服务（WBS-3.8.4 T4~T7 / T19；沿 DeliveryEvidenceIntegrationTest 内嵌桩范式）：
 * 三个被调域的四条内部只读路径各返回一份白名单封套（ApiResult code=0）；
 * {@link #FAIL_SPACE} 置位后 space 路径返回 500（构造跨域降级，fail-visible）；
 * 请求计数与最近身份头可供客户端契约断言（T20）。
 */
public final class AuditStubServer {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPACE_BODY = """
            {"admission":{"id":5,"subjectNo":"S20260922000001","type":"DATA_SPACE",
              "status":"ACTIVE","operator":"S20260922000001","reason":"联合建模准入",
              "createdAt":"2026-11-01T08:00:00.000Z"},
             "actionLogs":[{"id":9,"targetType":"ADMISSION","targetId":5,"action":"ADMIT",
              "operator":"S20260922000001","fromValue":null,"toValue":"ACTIVE","result":"SUCCESS",
              "reason":null,"createdAt":"2026-11-01T08:05:00.000Z"}]}""";

    private static final String CONTRACT_BODY = """
            {"attestation":{"contractNo":"CO000123",
              "contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "providerDid":"did:ctds:provider-0001","providerSignedAt":"2026-11-02T08:00:00.000Z",
              "requesterDid":"did:ctds:requester-0001","requesterSignedAt":"2026-11-02T08:01:00.000Z",
              "attestedAt":"2026-11-02T08:02:00.000Z"},
             "actionLogs":[{"id":1,"contractNo":"CO000123","versionNo":1,"action":"CONTRACT_EFFECTIVE",
              "actorSubjectNo":"S20260922000001","reasonCode":"NONE","fromValue":"NEGOTIATING",
              "toValue":"EFFECTIVE","createdAt":"2026-11-02T08:02:00.000Z"}]}""";

    private static final String USAGE_BODY = """
            {"usageLog":{"id":123,"contractNo":"CO000123","requesterNo":"S20260922000002",
              "actionType":"USE","outcome":"ALLOWED","reasonCode":"NONE","usedCount":1,
              "occurredAt":"2026-11-03T08:00:00.000Z"}}""";

    private static final String DELIVERY_BODY = """
            {"delivery":{"deliveryNo":"DL000123","contractNo":"CO000123","deliveryType":"FILE",
              "result":"SUCCESS","fileName":"model-v1.zip","fileSize":1024,
              "createdAt":"2026-11-04T08:00:00.000Z"},
             "actionLogs":[{"id":1,"deliveryNo":"DL000123","action":"DELIVERY_CREATED",
              "operatorNo":"S20260922000001","result":"SUCCESS","reasonCode":"NONE",
              "createdAt":"2026-11-04T08:00:00.000Z"}]}""";

    /** 置位后 space 桩返回 500（T19 跨域降级构造；其余域不受影响）。 */
    public final AtomicBoolean FAIL_SPACE = new AtomicBoolean(false);

    private final ConcurrentMap<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> lastRoles = new ConcurrentHashMap<>();
    private final HttpServer server;

    public AuditStubServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/data-spaces/internal/", exchange ->
                respond(exchange, "space", SPACE_BODY, FAIL_SPACE.get()));
        server.createContext("/api/v1/contracts/internal/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            final String key = path.contains("/usage-logs/") ? "usage" : "contract";
            respond(exchange, key, "usage".equals(key) ? USAGE_BODY : CONTRACT_BODY, false);
        });
        server.createContext("/api/v1/deliveries/internal/", exchange ->
                respond(exchange, "delivery", DELIVERY_BODY, false));
        server.start();
    }

    /** 三个域桩的 base-url（注入 ctds.evidence.trail.*-base-url）。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int hits(final String key) {
        final AtomicInteger value = hits.get(key);
        return value == null ? 0 : value.get();
    }

    public String lastRoles(final String key) {
        final String value = lastRoles.get(key);
        return value == null ? "" : value;
    }

    /** 清空命中计数与最近身份头（用例隔离——@BeforeEach 调用，消除跨用例顺序依赖）。 */
    public void resetCalls() {
        hits.clear();
        lastRoles.clear();
    }

    public void stop() {
        server.stop(0);
    }

    private void respond(final HttpExchange exchange, final String key, final String dataBody,
            final boolean fail) throws IOException {
        hits.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        lastRoles.put(key, exchange.getRequestHeaders().getFirst("X-Ctds-Roles"));
        final byte[] body;
        if (fail) {
            body = "{\"code\":\"1012S0002\",\"message\":\"桩构造：跨域暂不可用\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(500, body.length);
        } else {
            final JsonNode data = MAPPER.readTree(dataBody);
            body = MAPPER.writeValueAsBytes(
                    MAPPER.createObjectNode().put("code", "0").put("message", "success")
                            .put("traceId", "-").set("data", data));
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
