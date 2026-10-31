package com.ctds.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.connector.support.ConnectorTestSupport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 适配器非预期失败出站（WBS-3.6.5 评审循环 1 修复批，补 hifi §5 #5 / §7-③ 的 502 面）：
 * 源端返回 5xx → 502 + 中性文案（码位复用平台 1000S9999）、留痕 reason=ADAPTER_ERROR、响应与留痕均不泄漏内部。
 *
 * <p>本地 JDK {@link HttpServer} 桩（零外网、零新依赖）；测试上下文显式放开内网地址（见基类）。</p>
 */
class ConnectorSourceAdapterFailureApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";
    private static final String CONTRACT_NO = "CT0000000001";

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gov", exchange -> respondStatus(exchange, 500, "UPSTREAM_ERROR"));
        server.createContext("/unauthorized", exchange -> respondStatus(exchange, 401, "DENIED"));
        server.createContext("/forbidden", exchange -> respondStatus(exchange, 403, "FORBIDDEN"));
        server.createContext("/missing", exchange -> respondStatus(exchange, 404, "MISSING"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respondStatus(final com.sun.net.httpserver.HttpExchange exchange,
            final int code, final String message) throws IOException {
        final byte[] payload = ("{\"code\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void checkMapsAdapterFailureTo502WithNeutralText() throws Exception {
        final String connectorNo = registerConnector(OWNER, "probe-check");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                baseUrl + "/gov", SOURCE_SECRET);
        final MvcResult result = mockMvc.perform(
                        post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                                .header("X-Ctds-Subject", OWNER)
                                .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("1000S9999"))
                .andExpect(jsonPath("$.message").value("源连通性探测失败，请稍后重试"))
                .andReturn();
        final String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("IllegalStateException").doesNotContain(baseUrl)
                .doesNotContain(SOURCE_SECRET);
        assertThat(jdbc.queryForObject(
                "SELECT reason_code FROM connector_action_log WHERE action = 'source.check' "
                        + "AND result = 'FAILURE' ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("ADAPTER_ERROR");
        assertThat(jdbc.queryForObject(
                "SELECT detail FROM connector_action_log WHERE action = 'source.check' "
                        + "ORDER BY id DESC LIMIT 1", String.class))
                .doesNotContain("Exception").doesNotContain(SOURCE_SECRET);
    }

    @Test
    void readMapsAdapterFailureTo502WithNeutralText() throws Exception {
        final String connectorNo = registerConnector(OWNER, "probe-read");
        final String sourceNo = registerSource(OWNER, connectorNo, "GOV_EXCHANGE",
                baseUrl + "/gov", SOURCE_SECRET);
        occupy(connectorNo, "job-1");
        mockMvc.perform(post(INTERNAL + connectorNo + "/sources/" + sourceNo + "/reads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ctds-Subject", "delivery-service")
                        .header("X-Ctds-Roles", INTERNAL_ROLE)
                        .content(readBodyWithJobRef(CONTRACT_NO, "统计分析", "job-1")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("1000S9999"))
                .andExpect(jsonPath("$.message").value("源连通性探测失败，请稍后重试"));
    }

    private void expectCheckFailure(final String connectorNo, final String sourceNo,
            final String failureType) throws Exception {
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.passed").value(false))
                .andExpect(jsonPath("$.data.failureType").value(failureType));
    }

    @Test
    void databaseWrongPasswordMapsToInvalidCredential() throws Exception {
        final String connectorNo = registerConnector(OWNER, "db-badpass");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                databaseUrl(), databaseUsername() + ":wrong-password");

        expectCheckFailure(connectorNo, sourceNo, "INVALID_CREDENTIAL");
    }

    @Test
    void databaseWithoutSchemaPrivilegeMapsToInsufficientPermission() throws Exception {
        createLimitedUser();
        final String connectorNo = registerConnector(OWNER, "db-nopriv");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                databaseUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                "w366_limited:w366-limited-pass");

        expectCheckFailure(connectorNo, sourceNo, "INSUFFICIENT_PERMISSION");
    }

    @Test
    void databaseUnreachableMapsToNetworkUnreachable() throws Exception {
        final String connectorNo = registerConnector(OWNER, "db-unreachable");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                "jdbc:mysql://127.0.0.1:1/dataset", "db-user:db-password");

        expectCheckFailure(connectorNo, sourceNo, "NETWORK_UNREACHABLE");
    }

    @Test
    void objectStorageUnauthorizedMapsToInvalidCredential() throws Exception {
        final String connectorNo = registerConnector(OWNER, "oss-401");
        final String sourceNo = registerSource(OWNER, connectorNo, "OBJECT_STORAGE",
                "oss://dataset-bucket/data", baseUrl + "/unauthorized");

        expectCheckFailure(connectorNo, sourceNo, "INVALID_CREDENTIAL");
    }

    @Test
    void objectStorageNotFoundMapsToInsufficientPermission() throws Exception {
        final String connectorNo = registerConnector(OWNER, "oss-404");
        final String sourceNo = registerSource(OWNER, connectorNo, "OBJECT_STORAGE",
                "oss://dataset-bucket/data", baseUrl + "/missing");

        expectCheckFailure(connectorNo, sourceNo, "INSUFFICIENT_PERMISSION");
    }

    @Test
    void apiGatewayForbiddenMapsToInsufficientPermission() throws Exception {
        final String connectorNo = registerConnector(OWNER, "api-403");
        final String sourceNo = registerSource(OWNER, connectorNo, "API_GATEWAY",
                baseUrl + "/forbidden", "Bearer test-token");

        expectCheckFailure(connectorNo, sourceNo, "INSUFFICIENT_PERMISSION");
    }
}
