package com.ctds.gateway.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 上游 / 判定服务本地 stub（hifi §9"上游 stub 形态"）：reactor-netty {@link HttpServer} 进程内
 * 起随机端口（零新增依赖，reactor-netty-http 由 webflux 传递提供），记录<b>请求计数</b>、收到的
 * 身份头、<b>最近一次请求的路径与请求体</b>，并可切换响应状态 / 响应体 / 响应延迟。
 *
 * <p>"越界 / fail-closed 时上游零请求"的断言依赖 {@link #requestCount()}；"转发到配置路径模板
 * 实例"与"判定收到解码后要素原值"的断言依赖 {@link #lastPath()} / {@link #lastBody()}。</p>
 */
public final class StubServer implements AutoCloseable {

    public static final String SUBJECT_HEADER = "X-Ctds-Subject";
    public static final String ROLES_HEADER = "X-Ctds-Roles";

    private static final String ALLOW_BODY = "{\"code\":\"0\",\"message\":\"success\"}";

    private final DisposableServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<List<String>> subjectHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> rolesHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private volatile int responseStatus = 200;
    private volatile String responseBody = ALLOW_BODY;
    private volatile long delayMillis;

    public StubServer() {
        this.server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    requestCount.incrementAndGet();
                    lastPath.set(pathOf(request.uri()));
                    subjectHeaders.set(request.requestHeaders().getAll(SUBJECT_HEADER));
                    rolesHeaders.set(request.requestHeaders().getAll(ROLES_HEADER));
                    // 先读完请求体再响应（记录判定收到的要素原值；GET 无体时为空串）
                    return request.receive().aggregate().asString(StandardCharsets.UTF_8)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                lastBody.set(body);
                                return response.status(responseStatus)
                                        .header("Content-Type", "application/json")
                                        // 显式 UTF-8：响应体含中文常量文案（判定拒绝原文案透传断言依赖
                                        // 编码一致；Netty 在无 charset 时默认 ISO-8859-1 会致中文乱码）
                                        .sendString(Mono.just(responseBody)
                                                .delayElement(Duration.ofMillis(delayMillis)),
                                                StandardCharsets.UTF_8)
                                        .then();
                            });
                })
                .bindNow();
    }

    public int port() {
        return server.port();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public int requestCount() {
        return requestCount.get();
    }

    public List<String> subjectHeaders() {
        return subjectHeaders.get();
    }

    public List<String> rolesHeaders() {
        return rolesHeaders.get();
    }

    /** 最近一次请求的路径（不含查询串）。 */
    public String lastPath() {
        return lastPath.get();
    }

    /** 最近一次请求的请求体（UTF-8；无体为空串）。 */
    public String lastBody() {
        return lastBody.get();
    }

    public void resetCounters() {
        requestCount.set(0);
        lastPath.set("");
        lastBody.set("");
    }

    /** 判定响应：放行 + 合约参与方摘要（applicantNo = 合约需求方）。 */
    public void respondAllowed(final String applicantNo, final String providerNo) {
        this.responseStatus = 200;
        this.responseBody = "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"verdict\":{\"allowed\":true,"
                + "\"usedCount\":1,\"violations\":[],\"occurredAt\":\"2026-10-16T09:00:00\"},"
                + "\"contract\":{\"applicantNo\":\"" + applicantNo + "\",\"providerNo\":\"" + providerNo
                + "\",\"status\":\"EFFECTIVE\",\"effectiveAt\":\"2026-10-01T00:00:00\"}}}";
    }

    /** 判定响应：合约域业务拒绝（原码 + 原文案 + 原 HTTP 状态）。 */
    public void respondContractError(final int status, final String code, final String message) {
        this.responseStatus = status;
        this.responseBody = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
    }

    /** 上游普通响应（探针路由被转发时上游返回体）。 */
    public void respondUpstream(final int status, final String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    /** 响应延迟（用于判定超时 → fail-closed 用例）。 */
    public void setDelayMillis(final long delayMillis) {
        this.delayMillis = delayMillis;
    }

    private static String pathOf(final String uri) {
        final int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
