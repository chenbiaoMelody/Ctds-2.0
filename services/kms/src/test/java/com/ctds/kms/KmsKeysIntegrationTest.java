package com.ctds.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.crypto.KmsKeyProvider;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * KMS 全链路集成测试（规格 C-2.6.3 行为 1/2 验收标准，ADR-010 容器化基座）：
 * 权限三态（401/403/200）→ 创建/轮换 → common-crypto KmsKeyProvider 经真实端口加密/解密/轮换后旧密文不失效
 * → 审计四要素落库 → 密钥库无明文材料。
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过（门禁不红，跳过态留痕 mvn 输出）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class KmsKeysIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_SUBJECT = "X-Ctds-Subject: ops-admin";
    private static final byte[] TEST_ROOT_MATERIAL = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** 显式镜像标签 mysql:8.0（ADR-010 禁止 latest），方法内单类共享，无跨类状态冲突。 */
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_kms");

    @DynamicPropertySource
    static void kmsProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.kms.root-key", () -> Base64.getEncoder().encodeToString(TEST_ROOT_MATERIAL));
        registry.add("ctds.audit.file-dir", () -> System.getProperty("java.io.tmpdir") + "/ctds-kms-audit-test");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://127.0.0.1:" + port + "/api/v1/keys";
    }

    private HttpResponse<String> post(final String url, final String body, final String subject, final String roles)
            throws Exception {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        if (subject != null) {
            builder.header("X-Ctds-Subject", subject);
        }
        if (roles != null) {
            builder.header("X-Ctds-Roles", roles);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postJson(final String url, final String body, final String subject, final String roles)
            throws Exception {
        return MAPPER.readTree(post(url, body, subject, roles).body());
    }

    @Test
    void fullLifecycleRotationAndNoPlaintextInDb() throws Exception {
        // 权限三态：未认证 401 / 无权限 403 / admin 200
        assertThat(post(baseUrl(), "{\"keyRef\":\"order-data\"}", null, null).statusCode()).isEqualTo(401);
        assertThat(post(baseUrl(), "{\"keyRef\":\"order-data\"}", "clerk", "user").statusCode()).isEqualTo(403);
        final JsonNode created = postJson(baseUrl(), "{\"keyRef\":\"order-data\"}", "ops-admin", "admin");
        assertThat(created.get("code").asText()).isEqualTo("0");
        assertThat(created.at("/data/currentVersion").asInt()).isEqualTo(1);

        // 重复创建 → 1002B0001
        assertThat(postJson(baseUrl(), "{\"keyRef\":\"order-data\"}", "ops-admin", "admin")
                .get("code").asText()).isEqualTo("1002B0001");

        // 业务侧经 KmsKeyProvider（真实端口）加密 → v2 信封版本 1
        final KmsKeyProvider provider = new KmsKeyProvider(baseUrl().replace("/api/v1/keys", ""),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        final Sm4Service business = new Sm4Service(provider);
        assertThat(provider.currentVersion("order-data")).isEqualTo(1);
        final byte[] oldEnvelope = business.encrypt("轮换前的机密数据".getBytes(StandardCharsets.UTF_8), "order-data");
        assertThat(oldEnvelope[4]).isEqualTo((byte) 0x02);
        assertThat(keyVersionOf(oldEnvelope)).isEqualTo(1);
        assertThat(business.decrypt(oldEnvelope, "order-data"))
                .isEqualTo("轮换前的机密数据".getBytes(StandardCharsets.UTF_8));

        // 轮换：旧密文仍可解，新数据用版本 2（规格行为 2 GTT-1）
        final JsonNode rotated = postJson(baseUrl() + "/order-data/rotations", "", "ops-admin", "admin");
        assertThat(rotated.get("code").asText()).isEqualTo("0");
        assertThat(rotated.at("/data/oldVersion").asInt()).isEqualTo(1);
        assertThat(rotated.at("/data/newVersion").asInt()).isEqualTo(2);
        assertThat(business.decrypt(oldEnvelope, "order-data"))
                .isEqualTo("轮换前的机密数据".getBytes(StandardCharsets.UTF_8));
        final byte[] newEnvelope = business.encrypt("轮换后的机密数据".getBytes(StandardCharsets.UTF_8), "order-data");
        assertThat(keyVersionOf(newEnvelope)).isEqualTo(2);

        // 审计四要素落库（规格行为 2 GTT-2）
        final var audit = jdbcTemplate.queryForMap(
                "SELECT action, key_ref, old_version, new_version, operator FROM kms_key_audit "
                        + "WHERE action = 'ROTATE' AND key_ref = 'order-data'");
        assertThat(audit.get("old_version")).isEqualTo(1);
        assertThat(audit.get("new_version")).isEqualTo(2);
        assertThat(audit.get("operator")).isEqualTo("ops-admin");

        // 密钥库无明文材料（规格行为 1 GTT-3）：material_cipher 与供给端点的材料不同
        final String storedCipher = jdbcTemplate.queryForObject(
                "SELECT material_cipher FROM kms_key_version WHERE key_ref = 'order-data' AND version = 1",
                String.class);
        final byte[] supplied = provider.sm4Key("order-data", 1);
        assertThat(storedCipher).isNotEqualTo(Base64.getEncoder().encodeToString(supplied));

        // 历史版本供给 + 未知编号收敛（供给面语义）
        assertThat(provider.sm4Key("order-data", 1)).isEqualTo(supplied);
        assertThatThrownBy(() -> provider.sm4Key("nope"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode().value()).isEqualTo("1001S0001"));
    }

    /** 信封 v2 密钥版本号（字节 5..8 大端；CipherEnvelope 为组件包私有，此处独立读字节防同源）。 */
    private static int keyVersionOf(final byte[] envelope) {
        return ((envelope[5] & 0xFF) << 24) | ((envelope[6] & 0xFF) << 16)
                | ((envelope[7] & 0xFF) << 8) | (envelope[8] & 0xFF);
    }
}
