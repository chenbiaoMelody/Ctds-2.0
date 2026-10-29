package com.ctds.connector;

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

/**
 * 三类通用源自检可用（WBS-3.6.6 hifi §9 T1 / T8 改写；规格行为 11 规则 1 / 行为 13）：
 * 数据库 / 对象存储 / API 网关均由适配器承载，不再返回 {@code 1010C0011}。
 *
 * <p>本类由 3.6.5 的"未开放类型 → 1010C0011"用例改写而来（能力开放的必要同步，逐条留痕见任务卡 §五）：</p>
 * <ul>
 *   <li>databaseSourceRegistersButCheckIsUnavailable → databaseCheckPassesViaJdbcProbe</li>
 *   <li>databaseSourceReadIsUnavailable → 读取归 {@link ConnectorSourceReadApiTest} 三类源摘要用例</li>
 *   <li>objectStorageAndApiGatewayAlsoUnavailable → objectStorageAndApiGatewayCheckPass</li>
 * </ul>
 */
class ConnectorSourceCheckAvailabilityApiTest extends ConnectorTestSupport {

    private static final String OWNER = "S20261026000051";

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/object", exchange -> respond(exchange, 200, "object"));
        server.createContext("/api", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int status,
            final String body) throws IOException {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private void check(final String connectorNo, final String sourceNo, final boolean passed)
            throws Exception {
        mockMvc.perform(post(CONNECTORS + connectorNo + "/sources/" + sourceNo + "/checks")
                        .header("X-Ctds-Subject", OWNER)
                        .header("X-Ctds-Roles", APPLICANT_ROLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.passed").value(passed));
    }

    @Test
    void databaseCheckPassesViaJdbcProbe() throws Exception {
        final String connectorNo = registerConnector(OWNER, "avail-db");
        final String sourceNo = registerSource(OWNER, connectorNo, "DATABASE",
                databaseUrl(), databaseCredential());

        check(connectorNo, sourceNo, true);
    }

    @Test
    void objectStorageCheckPassesViaPresignedGet() throws Exception {
        final String connectorNo = registerConnector(OWNER, "avail-oss");
        final String sourceNo = registerSource(OWNER, connectorNo, "OBJECT_STORAGE",
                "oss://dataset-bucket/object-1", baseUrl + "/object?X-Amz-Signature=test");

        check(connectorNo, sourceNo, true);
    }

    @Test
    void apiGatewayCheckPassesWithAuthorizationHeader() throws Exception {
        final String connectorNo = registerConnector(OWNER, "avail-api");
        final String sourceNo = registerSource(OWNER, connectorNo, "API_GATEWAY",
                baseUrl + "/api", "Bearer test-token");

        check(connectorNo, sourceNo, true);
    }

}
