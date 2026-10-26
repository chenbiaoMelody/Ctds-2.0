package com.ctds.connector.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主体 / DID 依赖本地 stub（JDK HttpServer，零新依赖；hifi §9 测试替身）。
 * 提供"入驻状态 / 当前 DID 状态"两个旋钮 + 不可达模拟 + 调用计数（断言"拒绝时不重复调用"等）。
 */
public final class ConnectorStubServer {

    private final HttpServer server;
    private final AtomicInteger admissionCount = new AtomicInteger();
    private final AtomicInteger didCount = new AtomicInteger();
    private volatile String admissionStatus = "ADMITTED";
    private volatile String didStatus = "ACTIVE";
    private volatile String didValue = "did:ctds:S20261026000001.1";
    private volatile boolean subjectUnavailable;
    private volatile boolean subjectNotFound;
    private volatile boolean didUnavailable;

    public ConnectorStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/subject/internal/subjects/", this::admission);
        server.createContext("/api/v1/did/internal/subjects/", this::currentDid);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public void reset() {
        admissionStatus = "ADMITTED";
        didStatus = "ACTIVE";
        didValue = "did:ctds:S20261026000001.1";
        subjectUnavailable = false;
        subjectNotFound = false;
        didUnavailable = false;
        admissionCount.set(0);
        didCount.set(0);
    }

    public int admissionCount() {
        return admissionCount.get();
    }

    public int didCount() {
        return didCount.get();
    }

    public void respondAdmission(final String status) {
        admissionStatus = status;
    }

    public void respondAdmissionNotFound() {
        subjectNotFound = true;
    }

    public void respondSubjectUnavailable() {
        subjectUnavailable = true;
    }

    public void respondDid(final String status, final String did) {
        didStatus = status;
        didValue = did;
    }

    public void respondDidUnavailable() {
        didUnavailable = true;
    }

    private void admission(final HttpExchange exchange) throws IOException {
        admissionCount.incrementAndGet();
        if (subjectUnavailable) {
            respond(exchange, 500, "{\"code\":\"1000S0001\",\"message\":\"subject down\"}");
            return;
        }
        if (subjectNotFound) {
            respond(exchange, 400, "{\"code\":\"1000C0003\",\"message\":\"资源不存在\"}");
            return;
        }
        respond(exchange, 200, "{\"code\":\"0\",\"message\":\"ok\",\"data\":{\"subjectNo\":\"stub\","
                + "\"status\":\"" + admissionStatus + "\"}}");
    }

    private void currentDid(final HttpExchange exchange) throws IOException {
        didCount.incrementAndGet();
        if (didUnavailable) {
            respond(exchange, 500, "{\"code\":\"1005S0001\",\"message\":\"did down\"}");
            return;
        }
        final String didLiteral = didValue == null ? "null" : "\"" + didValue + "\"";
        respond(exchange, 200, "{\"code\":\"0\",\"message\":\"ok\",\"data\":{\"subjectNo\":\"stub\","
                + "\"did\":" + didLiteral + ",\"status\":\"" + didStatus + "\"}}");
    }

    private static void respond(final HttpExchange exchange, final int status, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
