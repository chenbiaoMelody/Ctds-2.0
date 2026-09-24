package com.ctds.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.crypto.Sm2KeyPair;
import com.ctds.common.crypto.Sm2Service;
import com.ctds.did.domain.DidStatus;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 主体绑定核验"真实网络不可达"链路集成测试（WBS-3.1.9 B9，评审先例沿 3.1.8 P2-C 处置）：
 * 不 mock SubjectStatusPort，真实 SubjectStatusHttpClient 指向不可达端口 → 验证结论 UNAVAILABLE/
 * BINDING_UNAVAILABLE（系统态不冒充"验证不通过"）且留痕如实记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SubjectStatusClientFailureIntegrationTest {

    private static final String BASE = "/api/v1/did";
    private static final byte[] DATA = "身份主张数据".getBytes(StandardCharsets.UTF_8);
    /** 启动时动态取一个当前空闲端口（占位后立即释放），保证主体服务地址确实不可达。 */
    private static final int UNREACHABLE_PORT = unusedPort();

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_did")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withEnv("TZ", "UTC");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Sm2Service sm2Service;

    @DynamicPropertySource
    static void unreachableSubjectService(final DynamicPropertyRegistry registry) {
        registry.add("ctds.did.subject.base-url", () -> "http://127.0.0.1:" + UNREACHABLE_PORT);
    }

    @Test
    void verificationReportsUnavailableWhenSubjectServiceUnreachable() throws Exception {
        final String subjectNo = "S20260922000201";
        final String did = "did:ctds:" + subjectNo + ".1";
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, pair.publicKeyHex());
        final byte[] signature = sm2Service.sign(DATA, pair.privateKeyHex());

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"" + Base64.getEncoder().encodeToString(DATA)
                                + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.reason").value("BINDING_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT result FROM did_verification_log WHERE did = ?", String.class, did))
                .isEqualTo("UNAVAILABLE");
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            return 59998;
        }
    }

    private void insertIdentity(final String subjectNo, final String did, final String publicKeyHex) {
        final String documentJson = "{\"did\":\"" + did + "\",\"publicKey\":{\"type\":\"SM2\","
                + "\"algorithm\":\"sm2p256v1\",\"valueHex\":\"" + publicKeyHex + "\"},"
                + "\"controller\":\"" + subjectNo + "\",\"service\":[{\"id\":\"#resolution\","
                + "\"type\":\"DidResolution\",\"serviceEndpoint\":\"/api/v1/did\"}],"
                + "\"created\":\"2026-09-22T20:00:00\"}";
        jdbcTemplate.update("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                        + "key_ref, document_json, guard_key, created_at, updated_at) "
                        + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectNo, did, DidStatus.ACTIVE.name(), publicKeyHex, "did-" + subjectNo + "-1", documentJson,
                subjectNo, Timestamp.valueOf(LocalDateTime.now().withNano(0)),
                Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }
}