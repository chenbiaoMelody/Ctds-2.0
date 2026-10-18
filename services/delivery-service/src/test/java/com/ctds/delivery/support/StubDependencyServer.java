package com.ctds.delivery.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合约/目录依赖本地 stub（JDK HttpServer，零新依赖；hifi §9 测试替身）。
 * 记录请求次数与最近一次判定请求体，供"拒绝/失败时不调用/不计数"断言。
 */
public final class StubDependencyServer {

    private final HttpServer server;
    private final AtomicInteger summaryCount = new AtomicInteger();
    private final AtomicInteger checkCount = new AtomicInteger();
    private final AtomicInteger productCount = new AtomicInteger();
    private volatile String summaryBody;
    private volatile int summaryStatus = 200;
    private volatile String checkBody;
    private volatile int checkStatus = 200;
    private volatile String productBody;
    private volatile int productStatus = 200;
    private volatile String lastCheckBody = "";

    public StubDependencyServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/contracts/internal/contract-summaries/", this::summary);
        server.createContext("/api/v1/contracts/internal/usage-checks", this::check);
        server.createContext("/api/v1/catalog/internal/data-products/", this::product);
        server.start();
        respondSummary("CO000001", 13, "演示数据集", "S-req", "S-prov", "EFFECTIVE");
        respondCheckAllowed();
        respondProduct(13, "DATASET");
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    public void resetCounters() {
        summaryCount.set(0);
        checkCount.set(0);
        productCount.set(0);
        lastCheckBody = "";
    }

    public int summaryCount() {
        return summaryCount.get();
    }

    public int checkCount() {
        return checkCount.get();
    }

    public int productCount() {
        return productCount.get();
    }

    public String lastCheckBody() {
        return lastCheckBody;
    }

    public void respondSummary(final String contractNo, final long productId, final String productName,
            final String applicantNo, final String providerNo, final String status) {
        summaryStatus = 200;
        summaryBody = "{\"code\":\"0\",\"message\":\"ok\",\"data\":{\"contractNo\":\"" + contractNo
                + "\",\"productId\":" + productId + ",\"productName\":\"" + productName
                + "\",\"applicantNo\":\"" + applicantNo + "\",\"providerNo\":\"" + providerNo
                + "\",\"status\":\"" + status + "\",\"effectiveAt\":\"2026-10-01T00:00:00\"}}";
    }

    public void respondSummaryError(final String code, final String message, final int status) {
        summaryStatus = status;
        summaryBody = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
    }

    public void respondCheckAllowed() {
        checkStatus = 200;
        checkBody = "{\"code\":\"0\",\"message\":\"ok\",\"data\":{\"verdict\":{\"allowed\":true,"
                + "\"usedCount\":1,\"violations\":[],\"occurredAt\":\"2026-10-01T00:00:00\"},"
                + "\"contract\":{\"applicantNo\":\"S-req\",\"providerNo\":\"S-prov\","
                + "\"status\":\"EFFECTIVE\",\"effectiveAt\":\"2026-10-01T00:00:00\"}}}";
    }

    public void respondCheckError(final String code, final String message, final int status) {
        checkStatus = status;
        checkBody = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
    }

    public void respondProduct(final long productId, final String productType) {
        productStatus = 200;
        productBody = "{\"code\":\"0\",\"message\":\"ok\",\"data\":{\"productId\":" + productId
                + ",\"productName\":\"演示产品\",\"productType\":\"" + productType
                + "\",\"status\":\"LISTED\",\"providerSubjectNo\":\"S-prov\","
                + "\"pricingModel\":\"FREE\",\"priceAmount\":null}}";
    }

    public void respondProductError(final String code, final int status) {
        productStatus = status;
        productBody = "{\"code\":\"" + code + "\",\"message\":\"产品不存在或未在架\"}";
    }

    private void summary(final HttpExchange exchange) throws IOException {
        summaryCount.incrementAndGet();
        respond(exchange, summaryStatus, summaryBody);
    }

    private void check(final HttpExchange exchange) throws IOException {
        checkCount.incrementAndGet();
        lastCheckBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        respond(exchange, checkStatus, checkBody);
    }

    private void product(final HttpExchange exchange) throws IOException {
        productCount.incrementAndGet();
        respond(exchange, productStatus, productBody);
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
