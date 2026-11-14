package com.ctds.evidence.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prometheus / Alertmanager 桩服务（WBS-3.9.4 T2/T4/T8~T11；沿 {@link AuditStubServer}
 * com.sun.net.httpserver 零依赖范式）。响应体 = P5 真机实测形状（Prometheus v3.13.3 /
 * Alertmanager v0.34.0 一次性容器捕获，build-output/w394-precheck-data/ 原件），可变形位：
 * up 值（构造存活/下线）、错误率规则态（inactive/pending 构造状态映射双向）、静默失败（503 构造）。
 * 静默请求体捕获 + 计数供处置契约断言（matchers/startsAt/endsAt/createdBy/comment）。
 */
public final class MonitorStubServer {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String SILENCE_ID = "sil-w394-0001";

    private static final String TS = "1794670266.203";

    /** 错误率规则表达式原文（P5 实测；JSON 内引号转义 + 两段拼接过 LineLength 门禁）。 */
    private static final String ERROR_RATE_QUERY =
            "sum(rate(http_server_requests_seconds_count{job=\\\"ctds-backend\\\",status=~\\\"5..\\\"}[5m]))"
                    + " / sum(rate(http_server_requests_seconds_count{job=\\\"ctds-backend\\\"}[5m])) > 0.05";

    private final HttpServer server;
    /** up 单值（"1" = 存活；"0" = 下线构造）。 */
    private final AtomicReference<String> upValue = new AtomicReference<>("1");
    /** 错误率规则态（false = inactive；true = pending，构造状态映射 pending 透传）。 */
    private final AtomicBoolean pendingErrorRate = new AtomicBoolean(false);
    /** 静默桩失败构造（500 → 处置 503 + 1013S0003）。 */
    private final AtomicBoolean failSilences = new AtomicBoolean(false);
    private final AtomicInteger silencePosts = new AtomicInteger();
    private final AtomicReference<String> lastSilenceBody = new AtomicReference<>("");

    public MonitorStubServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            final String query = URLDecoder.decode(
                    exchange.getRequestURI().getRawQuery().replaceFirst("(?i)^.*query=", ""),
                    StandardCharsets.UTF_8);
            respond(exchange, vectorBody(scalarFor(query)));
        });
        server.createContext("/api/v1/rules", exchange -> respond(exchange, rulesBody()));
        server.createContext("/api/v1/alerts", exchange -> respond(exchange, alertsBody()));
        server.createContext("/api/v2/silences", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                silencePosts.incrementAndGet();
                lastSilenceBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                final byte[] body;
                if (failSilences.get()) {
                    body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, body.length);
                } else {
                    body = ("{\"silenceID\":\"" + SILENCE_ID + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                }
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            respond(exchange, "[]");
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setUpValue(final String value) {
        upValue.set(value);
    }

    public void usePendingErrorRate() {
        pendingErrorRate.set(true);
    }

    /** 规则态复位（用例隔离：finally 归还 inactive 缺省形态）。 */
    public void resetErrorRateState() {
        pendingErrorRate.set(false);
    }

    public void failSilences() {
        failSilences.set(true);
    }

    /** 静默失败复位（用例隔离：finally 归还 200 形态）。 */
    public void resetSilenceFailure() {
        failSilences.set(false);
    }

    public int silencePosts() {
        return silencePosts.get();
    }

    /** 清空静默计数与最近请求体（用例隔离——@BeforeEach 调用，沿 AuditStubServer.resetCalls 先例）。 */
    public void resetCalls() {
        silencePosts.set(0);
        lastSilenceBody.set("");
    }

    public String lastSilenceBody() {
        return lastSilenceBody.get();
    }

    public JsonNode lastSilenceJson() throws IOException {
        return MAPPER.readTree(lastSilenceBody.get());
    }

    public void stop() {
        server.stop(0);
    }

    /** 查询路由（读面 PromQL 与规则表达式均为封闭集合；P5 实测口径）。 */
    private String scalarFor(final String query) {
        if (query.contains("jvm_memory_used_bytes")) {
            return "28311552";
        }
        if (query.contains("jvm_memory_max_bytes")) {
            return "402653184";
        }
        if (query.contains("jvm_gc_pause_seconds_sum")) {
            return "0.003";
        }
        if (query.contains("http_server_requests_seconds_bucket")) {
            return "0.123";
        }
        if (query.contains("status=~\"5..\"")) {
            // 看板公式（100 * 前缀）= 百分比；规则表达式 = 占比（0.025 < 0.05 → inactive 自洽）
            return query.startsWith("100") ? "2.5" : "0.025";
        }
        if (query.contains("http_server_requests_seconds_count")) {
            return "12.5";
        }
        if (query.contains("up{")) {
            return query.contains("job=\"ctds-backend\"") ? upValue.get() : null;
        }
        return null;
    }

    private static String vectorBody(final String scalar) {
        if (scalar == null) {
            return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"__name__\":\"w394\",\"instance\":\"stub:8080\",\"job\":\"ctds-backend\"},"
                + "\"value\":[" + TS + ",\"" + scalar + "\"]}]}}";
    }

    private String rulesBody() {
        final String secondState = pendingErrorRate.get() ? "pending" : "inactive";
        return """
                {"status":"success","data":{"groups":[{"name":"ctds-platform",
                  "file":"/etc/prometheus/rules.yml","rules":[
                  {"state":"firing","name":"CtdsBackendDown",
                   "query":"up{job=\\"ctds-backend\\"} == 0 or absent(up{job=\\"ctds-backend\\"})",
                   "duration":60,"keepFiringFor":0,
                   "labels":{"severity":"critical"},
                   "annotations":{"summary":"平台后端服务下线",
                     "description":"后端 ctds-backend 抓取目标缺失或 up=0 已持续 1 分钟"},
                   "alerts":[{"labels":{"alertname":"CtdsBackendDown",
                      "instance":"stub:8080","job":"ctds-backend","severity":"critical"},
                     "annotations":{"summary":"平台后端服务下线"},
                     "state":"firing","activeAt":"2026-11-14T15:31:00.000000Z","value":"0e+00"}],
                   "health":"ok","lastEvaluation":"2026-11-14T15:31:09.389433903Z","type":"alerting"},
                  {"state":"%s","name":"CtdsBackendHighErrorRate",
                   "query":"%s",
                   "duration":300,"keepFiringFor":0,
                   "labels":{"severity":"warning"},
                   "annotations":{"summary":"平台后端 HTTP 错误率异常",
                     "description":"后端 5 分钟窗口 5xx 错误率超过 5%%"},
                   "alerts":[],
                   "health":"ok","lastEvaluation":"2026-11-14T15:31:09.389918828Z","type":"alerting"}
                ]}]}}""".formatted(secondState, ERROR_RATE_QUERY);
    }

    private static String alertsBody() {
        return """
                {"status":"success","data":{"alerts":[
                  {"labels":{"alertname":"CtdsBackendDown","instance":"stub:8080",
                     "job":"ctds-backend","severity":"critical"},
                   "annotations":{"summary":"平台后端服务下线"},
                   "state":"firing","activeAt":"2026-11-14T15:31:00.000000Z","value":"0e+00"}]}}""";
    }

    private static void respond(final HttpExchange exchange, final String dataBody) throws IOException {
        final byte[] body = dataBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
