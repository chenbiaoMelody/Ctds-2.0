package com.ctds.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.did.domain.DidKmsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DID 签发/吊销全链路集成测试（WBS-3.1.8 行为清单 B1~B6，ADR-010 容器化基座）：
 * 幂等唯一守卫（uk_guard 反向探针）/ 失败可重试 / 吊销五要素 + 不可逆 / 重签新序号新密钥 /
 * 私钥零明文（库表 64-hex 扫描 + 反向探针）。KMS 客户端 @MockitoBean 替换（私钥生成在 KMS，不在 DID）。
 * 各用例独立 subject_no（库共享，防跨用例状态串扰）。本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DidIssuanceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/did";
    private static final String PUBLIC_KEY_HEX = "04" + "ab".repeat(64);
    private static final String ADMIN = "ops-admin";
    /** 私钥 D 值样式：恰好 64 位 hex、两侧非 hex 边界（区分 130 位公钥 04‖X‖Y）。 */
    private static final String PRIVATE_KEY_PATTERN = "(^|[^0-9a-fA-F])[0-9a-fA-F]{64}([^0-9a-fA-F]|$)";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_did");

    @MockitoBean
    private DidKmsClient kmsClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubKms() {
        doReturn(PUBLIC_KEY_HEX).when(kmsClient).createKeyPair(anyString());
    }

    @Test
    void issueCreatesActiveRowAndFourElementLog() throws Exception {
        final String subjectNo = "S20260920000101";
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.did").value("did:ctds:" + subjectNo + ".1"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM did_identity WHERE subject_no = ? AND guard_key IS NOT NULL",
                String.class, subjectNo)).isEqualTo("ACTIVE");
        final var log = jdbcTemplate.queryForMap(
                "SELECT operation, subject_no, did, key_ref, occurred_at FROM did_operation_log "
                        + "WHERE subject_no = ?", subjectNo);
        assertThat(log.get("operation")).isEqualTo("ISSUE");
        assertThat(log.get("did")).isEqualTo("did:ctds:" + subjectNo + ".1");
        assertThat(log.get("key_ref")).isEqualTo("did-" + subjectNo + "-1");
        assertThat(log.get("occurred_at")).isNotNull();
    }

    @Test
    void issueIsIdempotentAndUniqueGuardRejectsDuplicate() throws Exception {
        final String subjectNo = "S20260920000102";
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM did_identity WHERE subject_no = ? AND guard_key IS NOT NULL",
                Integer.class, subjectNo)).isEqualTo(1);

        // 反向探针（B2 库表层守卫）：直插第二条非吊销行 → uk_guard 唯一键拒绝
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO did_identity (subject_no, issuance_seq, status, guard_key, created_at, updated_at) "
                        + "VALUES (?, ?, 'PENDING_ISSUE', ?, ?, ?)",
                subjectNo, 2, subjectNo, Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void issuanceResponseContainsNoPrivateKeyMaterial() throws Exception {
        final String subjectNo = "S20260920000103";
        final String body = mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        final JsonNode data = MAPPER.readTree(body).get("data");
        // 接口面：响应 data 仅 did/status/keyRef/issuedAt，无私钥/材料字段
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("did", "status", "keyRef", "issuedAt");

        // 库表面：did_identity/did_operation_log 全文无私钥明文（恰好 64 位 hex）
        assertThat(privateKeyPatternHits()).isZero();
        // 反向探针：植入 64-hex → 命中（证明扫描非空断言，删加密实现必变红）
        jdbcTemplate.update("UPDATE did_identity SET document_json = ? WHERE subject_no = ?",
                "{\"leak\":\"" + "aa".repeat(32) + "\"}", subjectNo);
        assertThat(privateKeyPatternHits()).isGreaterThan(0);
    }

    @Test
    void kmsFailureLeavesPendingAndRetryCompletes() throws Exception {
        final String subjectNo = "S20260920000104";
        doThrow(new IllegalStateException("KMS 不可达")).when(kmsClient).createKeyPair(anyString());
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_ISSUE"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM did_identity WHERE subject_no = ? AND guard_key IS NOT NULL",
                String.class, subjectNo)).isEqualTo("PENDING_ISSUE");

        // KMS 恢复 → 重试转有效并补齐留痕
        doReturn(PUBLIC_KEY_HEX).when(kmsClient).createKeyPair(anyString());
        mockMvc.perform(post(BASE + "/subjects/" + subjectNo + "/issuance-retries")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void revokeRequiresReasonAndRejectsNonActive() throws Exception {
        final String subjectNo = "S20260920000105";
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk());
        final String did = "did:ctds:" + subjectNo + ".1";

        mockMvc.perform(post(BASE + "/" + did + "/revocation")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0002"));
        mockMvc.perform(post(BASE + "/did:ctds:unknown.1/revocation")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"理由\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0003"));

        mockMvc.perform(post(BASE + "/" + did + "/revocation")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"私钥疑似泄露\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        // 五要素 + guard 释放 + 不可逆
        final var log = jdbcTemplate.queryForMap(
                "SELECT operation, reason, did, status_from, status_to, operator FROM did_operation_log "
                        + "WHERE operation = 'REVOKE' AND subject_no = ?", subjectNo);
        assertThat(log.get("reason")).isEqualTo("私钥疑似泄露");
        assertThat(log.get("status_from")).isEqualTo("ACTIVE");
        assertThat(log.get("status_to")).isEqualTo("REVOKED");
        assertThat(log.get("operator")).isEqualTo(ADMIN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM did_identity WHERE subject_no = ? AND guard_key IS NOT NULL",
                Integer.class, subjectNo)).isZero();

        // 不可逆：不存在"恢复"端点（规格行为 4 验收标准 5）
        assertThat(mockMvc.perform(post(BASE + "/" + did + "/recovery")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void reissueCreatesNewIdentityAndRetainsOld() throws Exception {
        final String subjectNo = "S20260920000106";
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/did:ctds:" + subjectNo + ".1/revocation")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"重签前吊销\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/subjects/" + subjectNo + "/reissuances")
                        .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.did").value("did:ctds:" + subjectNo + ".2"))
                .andExpect(jsonPath("$.data.keyRef").value("did-" + subjectNo + "-2"));

        // 旧 DID 仍保留（REVOKED 可追溯），新密钥引用与旧不同
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM did_identity WHERE subject_no = ?", Integer.class, subjectNo))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM did_identity WHERE did = ?", String.class,
                "did:ctds:" + subjectNo + ".1")).isEqualTo("REVOKED");
    }

    private int privateKeyPatternHits() {
        final String identitySql = "SELECT COUNT(1) FROM did_identity WHERE did REGEXP '" + PRIVATE_KEY_PATTERN
                + "' OR public_key_hex REGEXP '" + PRIVATE_KEY_PATTERN + "' OR key_ref REGEXP '"
                + PRIVATE_KEY_PATTERN + "' OR document_json REGEXP '" + PRIVATE_KEY_PATTERN + "'";
        final Integer identity = jdbcTemplate.queryForObject(identitySql, Integer.class);
        final String opLogSql = "SELECT COUNT(1) FROM did_operation_log WHERE did REGEXP '" + PRIVATE_KEY_PATTERN
                + "' OR key_ref REGEXP '" + PRIVATE_KEY_PATTERN + "' OR reason REGEXP '" + PRIVATE_KEY_PATTERN + "'";
        final Integer opLog = jdbcTemplate.queryForObject(opLogSql, Integer.class);
        return identity + opLog;
    }
}
