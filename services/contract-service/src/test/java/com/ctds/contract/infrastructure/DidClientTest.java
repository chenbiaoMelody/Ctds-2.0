package com.ctds.contract.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctds.contract.domain.DidPort.DidBinding;
import com.ctds.contract.domain.DidPort.DidSignResult;
import com.ctds.contract.domain.DidPort.DidVerifyResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DID 客户端三能力三态映射单测（WBS-3.4.3 hifi §7 U 锚；JDK HttpServer 桩，沿
 * SubjectAdmissionClientTest 先例）：解析（FOUND 归属 controller / 1005B0003 未登记 /
 * 不可达 UNAVAILABLE）；代签（成功取签名 / 入口关闭 1000C0003 与不可达 → UNAVAILABLE
 * 不冒充签署能力）；验签（did 结论 PASS/FAIL/UNAVAILABLE 原样承载 / 传输失败
 * transportFailure 分列——R9 不冒充结论口径）。
 */
class DidClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DID = "did:ctds:S-01.1";

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> resolveBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> resolveStatus = new AtomicReference<>(200);
    private final AtomicReference<String> signBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> signStatus = new AtomicReference<>(200);
    private final AtomicReference<String> verifyBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> verifyStatus = new AtomicReference<>(200);
    private final AtomicReference<List<String>> signSubjectHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> signRoleHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<String> signRequestPath = new AtomicReference<>("");
    private final AtomicReference<String> signRequestData = new AtomicReference<>("");

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/did/" + DID, exchange -> route(exchange));
        server.createContext("/api/v1/did/", exchange -> route(exchange));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void route(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/demo-signatures")) {
            signSubjectHeaders.set(exchange.getRequestHeaders().get("X-Ctds-Subject"));
            signRoleHeaders.set(exchange.getRequestHeaders().get("X-Ctds-Roles"));
            signRequestPath.set(path);
            signRequestData.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, signBody.get(), signStatus.get());
        } else if (path.endsWith("/verifications")) {
            respond(exchange, verifyBody.get(), verifyStatus.get());
        } else {
            respond(exchange, resolveBody.get(), resolveStatus.get());
        }
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void respond(final HttpExchange exchange, final String body, final int status)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private DidClient client(final String base) {
        return new DidClient(base, MAPPER);
    }

    // ==== 解析 ====

    @Test
    void resolveActiveDidExtractsControllerAndStatus() {
        resolveBody.set("{\"code\":\"0\",\"data\":{\"did\":\"" + DID + "\",\"status\":\"ACTIVE\","
                + "\"document\":{\"id\":\"" + DID + "\",\"controller\":\"S-01\"}}}");
        final DidBinding binding = client(baseUrl).resolve(DID);
        assertThat(binding.state().name()).isEqualTo("FOUND");
        assertThat(binding.controller()).isEqualTo("S-01");
        assertThat(binding.status()).isEqualTo("ACTIVE");
    }

    @Test
    void resolveUnregisteredBusinessAnswerMapsToNotRegistered() {
        // 1005B0003 随 404 出站（全局处理器映射）——业务码优先于 HTTP 状态判定
        resolveBody.set("{\"code\":\"1005B0003\",\"message\":\"DID 未登记\"}");
        resolveStatus.set(404);
        assertThat(client(baseUrl).resolve(DID).state().name()).isEqualTo("NOT_REGISTERED");
    }

    @Test
    void resolveRevokedStatusPassesThrough() {
        resolveBody.set("{\"code\":\"0\",\"data\":{\"did\":\"" + DID + "\",\"status\":\"REVOKED\","
                + "\"document\":{\"controller\":\"S-01\"}}}");
        final DidBinding binding = client(baseUrl).resolve(DID);
        assertThat(binding.state().name()).isEqualTo("FOUND");
        assertThat(binding.status()).isEqualTo("REVOKED");
    }

    @Test
    void resolveFailuresMapToUnavailable() {
        resolveStatus.set(500);
        assertThat(client(baseUrl).resolve(DID).state().name()).isEqualTo("UNAVAILABLE");
        resolveStatus.set(200);
        resolveBody.set("not-json");
        assertThat(client(baseUrl).resolve(DID).state().name()).isEqualTo("UNAVAILABLE");
        resolveBody.set("{\"code\":\"1005S9999\"}");
        assertThat(client(baseUrl).resolve(DID).state().name()).isEqualTo("UNAVAILABLE");
        assertThat(client("http://127.0.0.1:1").resolve(DID).state().name())
                .isEqualTo("UNAVAILABLE");
        assertThat(client("").resolve(DID).state().name()).isEqualTo("UNAVAILABLE");
    }

    // ==== 代签（演示签名入口）====

    @Test
    void signReturnsSignatureAndSendsServiceIdentity() {
        signBody.set("{\"code\":\"0\",\"data\":{\"did\":\"" + DID + "\","
                + "\"data\":\"" + Base64.getEncoder().encodeToString("hash-64".getBytes())
                + "\",\"signature\":\"MEUCSIGTEST\",\"signedAt\":\"2026-10-05T12:00:00\"}}");
        final DidSignResult result = client(baseUrl).sign(DID, "hash-64");
        assertThat(result.state().name()).isEqualTo("SIGNED");
        assertThat(result.signatureBase64()).isEqualTo("MEUCSIGTEST");
        assertThat(signRequestPath.get()).isEqualTo("/api/v1/did/" + DID + "/demo-signatures");
        final JsonNode sent;
        try {
            sent = MAPPER.readTree(signRequestData.get());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(sent.path("data").asText()).isEqualTo("hash-64");
        assertThat(signSubjectHeaders.get()).containsExactly("contract-service");
        assertThat(signRoleHeaders.get()).containsExactly("contract-internal");
    }

    @Test
    void signDisabledEntryMapsToUnavailableNotIdentityFailure() {
        // 入口关闭（1000C0003）= 平台侧代签能力不可用 → S0002 语义（身份校验已由 resolve 承担）
        signBody.set("{\"code\":\"1000C0003\",\"message\":\"演示签名入口未启用（仅演示/调试期）\"}");
        signStatus.set(404);
        assertThat(client(baseUrl).sign(DID, "hash-64").state().name()).isEqualTo("UNAVAILABLE");
        assertThat(client("http://127.0.0.1:1").sign(DID, "hash-64").state().name())
                .isEqualTo("UNAVAILABLE");
        assertThat(client("").sign(DID, "hash-64").state().name()).isEqualTo("UNAVAILABLE");
    }

    // ==== 验签 ====

    @Test
    void verifyCarriesDidConclusionVerbatim() {
        verifyBody.set("{\"code\":\"0\",\"data\":{\"did\":\"" + DID + "\",\"result\":\"PASS\","
                + "\"verifiedAt\":\"2026-10-05T12:00:00\"}}");
        final DidVerifyResult pass = client(baseUrl).verify(DID, "ZGF0YQ==", "c2ln");
        assertThat(pass.outcome().name()).isEqualTo("PASS");
        assertThat(pass.transportFailure()).isFalse();

        verifyBody.set("{\"code\":\"0\",\"data\":{\"did\":\"" + DID + "\",\"result\":\"FAIL\","
                + "\"reason\":\"SIGNATURE_INVALID\"}}");
        final DidVerifyResult fail = client(baseUrl).verify(DID, "ZGF0YQ==", "c2ln");
        assertThat(fail.outcome().name()).isEqualTo("FAIL");
        assertThat(fail.reason()).isEqualTo("SIGNATURE_INVALID");
        assertThat(fail.transportFailure()).isFalse();

        // did 服务侧明确答复 UNAVAILABLE（如绑定核验不可用）——结论原样承载，非传输失败
        verifyBody.set("{\"code\":\"0\",\"data\":{\"did\":\"" + DID + "\","
                + "\"result\":\"UNAVAILABLE\",\"reason\":\"BINDING_UNAVAILABLE\"}}");
        final DidVerifyResult answered = client(baseUrl).verify(DID, "ZGF0YQ==", "c2ln");
        assertThat(answered.outcome().name()).isEqualTo("UNAVAILABLE");
        assertThat(answered.transportFailure()).isFalse();
    }

    @Test
    void verifyTransportFailuresAreFlagged() {
        verifyStatus.set(500);
        final DidVerifyResult failure = client(baseUrl).verify(DID, "ZGF0YQ==", "c2ln");
        assertThat(failure.outcome().name()).isEqualTo("UNAVAILABLE");
        assertThat(failure.transportFailure()).isTrue();
        assertThat(client("http://127.0.0.1:1").verify(DID, "ZGF0YQ==", "c2ln")
                .transportFailure()).isTrue();
        assertThat(client("").verify(DID, "ZGF0YQ==", "c2ln").transportFailure()).isTrue();
    }
}
