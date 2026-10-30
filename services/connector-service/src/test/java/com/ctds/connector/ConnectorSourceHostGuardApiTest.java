package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.domain.CredentialProtector;
import com.ctds.connector.support.ConnectorTestSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 调用边界解析级主机防护（WBS-3.6.6 hifi §9 T5 / DB-91；规格行为 14 规则 1）：
 * 默认配置（{@code allow-private-endpoint=false}）下，回环 / 内网 / 不可解析主机在<b>调用适配器前</b>被拒——
 * 自检为 200 业务结论（网络不可达），读取为 400 {@code 1010C0010}，且适配器零触达。
 *
 * <p>源记录用直插方式构造（模拟"受理期合法、调用期 DNS / 网络环境变化"的 TOCTOU 场景）；
 * 本地 {@link HttpServer} 计数器证明适配器未被调用。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"ctds.audit.enabled=false",
                "ctds.connector.source.allow-private-endpoint=false"})
class ConnectorSourceHostGuardApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String CONTRACT_NO = "CT0000000001";
    private static final AtomicInteger STUB_HITS = new AtomicInteger();
    private static final AtomicInteger SOURCE_SEQ = new AtomicInteger(100);

    private static HttpServer server;
    private static String loopbackEndpoint;

    @Autowired
    private CredentialProtector cipher;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            STUB_HITS.incrementAndGet();
            final byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        // 带尾点的 localhost 通过受理期字面量规则（无法从字符串判定内网），解析后才暴露回环本质 —— DB-91 价值面
        loopbackEndpoint = "http://localhost.:" + server.getAddress().getPort() + "/api";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private String registerConnectorWithSource(final String name, final String endpoint,
            final String credential) throws Exception {
        final String connectorNo = registerConnector(OWNER, name);
        final String sourceNo = "SR" + String.format("%06d", SOURCE_SEQ.incrementAndGet());
        jdbc.update("INSERT INTO connector_source (source_no, connector_no, subject_no, source_type, "
                        + "endpoint, credential_ref, credential_ciphertext, credential_masked) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sourceNo, connectorNo, OWNER, "API_GATEWAY", endpoint, "SC" + UUID.randomUUID(),
                cipher.encrypt(credential), cipher.mask(credential));
        return connectorNo + "/" + sourceNo;
    }

    private void check(final String path) throws Exception {
        final String[] parts = path.split("/");
        mockMvc.perform(post(CONNECTORS + parts[0] + "/sources/" + parts[1] + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failureType").value("NETWORK_UNREACHABLE"));
    }

    private void read(final String path) throws Exception {
        final String[] parts = path.split("/");
        mockMvc.perform(post(INTERNAL + parts[0] + "/sources/" + parts[1] + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBody(CONTRACT_NO, "统计分析")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1010C0010"))
                .andExpect(jsonPath("$.message").value("源地址不被允许（解析到受限网络或访问形态不支持）"));
    }

    @Test
    void loopbackCheckIsBusinessFailureWithoutTouchingAdapter() throws Exception {
        final String path = registerConnectorWithSource("guard-loopback-check", loopbackEndpoint,
                "Bearer test-token");

        check(path);

        assertThat(STUB_HITS.get()).isZero();
        final String reason = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'source.check' "
                        + "AND result = 'FAILURE' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(reason).isEqualTo("NETWORK_UNREACHABLE");
    }

    @Test
    void objectStorageCredentialHostIsGuardedToo() throws Exception {
        final String connectorNo = registerConnector(OWNER, "guard-oss-credential");
        final String sourceNo = "SR" + String.format("%06d", SOURCE_SEQ.incrementAndGet());
        final String credential = "http://localhost.:" + server.getAddress().getPort()
                + "/object?X-Amz-Signature=test";
        jdbc.update("INSERT INTO connector_source (source_no, connector_no, subject_no, source_type, "
                        + "endpoint, credential_ref, credential_ciphertext, credential_masked) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sourceNo, connectorNo, OWNER, "OBJECT_STORAGE", "oss://dataset-bucket/object",
                "SC" + UUID.randomUUID(), cipher.encrypt(credential), cipher.mask(credential));

        check(connectorNo + "/" + sourceNo);

        assertThat(STUB_HITS.get()).isZero();
    }

    @Test
    void unresolvableHostCheckIsBusinessFailure() throws Exception {
        final String path = registerConnectorWithSource("guard-dns-check",
                "http://no-such-host.invalid/api", "Bearer test-token");

        check(path);
    }

    @Test
    void loopbackReadIsRejectedAndAudited() throws Exception {
        final String path = registerConnectorWithSource("guard-loopback-read", loopbackEndpoint,
                "Bearer test-token");

        read(path);

        assertThat(STUB_HITS.get()).isZero();
        final String reason = jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'source.denied' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(reason).isEqualTo("1010C0010");
    }

    @Test
    void unresolvableHostReadIsRejectedAndAudited() throws Exception {
        final String path = registerConnectorWithSource("guard-dns-read-audit",
                "http://no-such-host.invalid/api", "Bearer test-token");

        read(path);

        final String detail = jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.denied' "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertThat(detail).contains("denied=restricted-host");
    }

    @Test
    void unresolvableHostReadIsRejected() throws Exception {
        read(registerConnectorWithSource("guard-dns-read", "http://no-such-host.invalid/api",
                "Bearer test-token"));
    }
}
