package com.ctds.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.crypto.Sm2KeyPair;
import com.ctds.did.support.SharedMySqlContainer;
import com.ctds.common.crypto.Sm2Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.domain.SubjectAdmission;
import com.ctds.did.domain.SubjectStatusPort;
import com.ctds.did.infrastructure.DidJdbcRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DID 全链路集成测试（WBS-3.1.8 行为清单 B1~B6 + WBS-3.1.9 解析/验证 B1~B12，ADR-010 容器化基座）：
 * 签发/幂等唯一守卫（uk_guard 反向探针）/ 失败可重试 / 吊销五要素 + 不可逆 / 重签新序号新密钥 /
 * 私钥零明文（库表 64-hex 扫描 + 反向探针）/ 解析三态 / 验证三查（**真实 SM2 验签**）/ 留痕无数据原文。
 * KMS 客户端与主体状态端口 @MockitoBean 替换（真实不可达链路见 SubjectStatusClientFailureIntegrationTest）。
 * 各用例独立 subject_no（库共享，防跨用例状态串扰）。本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 * 注：3.1.9 与 3.1.8 用例合并于同一测试类共享容器（门禁 unitTest 段 600s 上限下压低容器启动开销）。
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
    /** 敏感明文样式（B4 解析侧正向扫描）：18 位身份证号 / 手机号 / 64-hex 私钥样式。 */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(\\d{17}[0-9Xx])|(1[3-9]\\d{9})|(" + PRIVATE_KEY_PATTERN + ")");

    /** DB-25：模块共享容器 + 本类独立库名（ADR-010 §8 形态 B）；UTC 连接参数由共享容器统一承载。 */
    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_did_issuance");
    }

    @MockitoBean
    private DidKmsClient kmsClient;

    @MockitoBean
    private SubjectStatusPort subjectStatusPort;

    @Autowired
    private DidJdbcRepository didJdbcRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Sm2Service sm2Service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubKms() {
        doReturn(PUBLIC_KEY_HEX).when(kmsClient).createKeyPair(anyString());
        doReturn(SubjectAdmission.ADMITTED).when(subjectStatusPort).check(anyString());
    }

    @Test
    void adminEndpointsEnforceDidAdminPermission() throws Exception {
        final String subjectNo = "S20260920000107";
        // 未认证 401（重试/重签/吊销三管理端点；权限注解缺失必变红）
        assertThat(mockMvc.perform(post(BASE + "/subjects/" + subjectNo + "/issuance-retries"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(BASE + "/subjects/" + subjectNo + "/reissuances"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post(BASE + "/did:ctds:unknown.1/revocation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"理由\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // 无权限 403（非 did.admin 角色）
        assertThat(mockMvc.perform(post(BASE + "/subjects/" + subjectNo + "/issuance-retries")
                        .header("X-Ctds-Subject", "clerk").header("X-Ctds-Roles", "applicant"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post(BASE + "/did:ctds:unknown.1/revocation")
                        .header("X-Ctds-Subject", "clerk").header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"理由\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        // 签发端点 = 内部触发面（回环边界，无鉴权——诚实边界登记于 ADR-017 §2.7）
        assertThat(mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
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

    @Test
    void completeIssuanceOnRevokedIdentityIsRejectedWithoutLog() throws Exception {
        // 交错用例（评审④ P1）：行状态已变（并发吊销/外部变更）后完成签发 → 乐观门槛拒绝（与 revoke 对称）
        final String subjectNo = "S20260920000108";
        doThrow(new IllegalStateException("KMS 不可达")).when(kmsClient).createKeyPair(anyString());
        mockMvc.perform(post(BASE + "/issuances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_ISSUE"));

        jdbcTemplate.update("UPDATE did_identity SET status = 'REVOKED', guard_key = NULL "
                + "WHERE subject_no = ?", subjectNo);
        final Long identityId = jdbcTemplate.queryForObject(
                "SELECT id FROM did_identity WHERE subject_no = ?", Long.class, subjectNo);

        final DidOperationLog op = new DidOperationLog("did:ctds:" + subjectNo + ".1", subjectNo, "ISSUE",
                "SYSTEM", null, "did-" + subjectNo + "-1", "PENDING_ISSUE", "ACTIVE",
                LocalDateTime.now().withNano(0));
        assertThatThrownBy(() -> didJdbcRepository.completeIssuance(identityId, PUBLIC_KEY_HEX, "{}", op))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_ISSUANCE_INTERNAL_ERROR));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM did_identity WHERE id = ?", String.class, identityId)).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM did_operation_log WHERE subject_no = ?", Integer.class, subjectNo))
                .isZero();
    }

    // ==== WBS-3.1.9 解析与验证用例（合并自原 DidResolutionVerificationIntegrationTest，共享本类容器）====

    @Test
    void resolveActiveReturnsDocumentAndStatus() throws Exception {
        final String subjectNo = "S20260922000110";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, PUBLIC_KEY_HEX);

        mockMvc.perform(get(BASE + "/" + did))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.did").value(did))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                // B4：文档字段集严格 = 公开要素（不含私钥/密钥引用）
                .andExpect(jsonPath("$.data.document.publicKey.type").value("SM2"))
                .andExpect(jsonPath("$.data.document.controller").value(subjectNo))
                .andExpect(jsonPath("$.data.document.keyRef").doesNotExist())
                .andExpect(jsonPath("$.data.document.privateKey").doesNotExist());
    }

    @Test
    void resolveRevokedStillReturnsDocumentWithRevokedStatus() throws Exception {
        final String subjectNo = "S20260922000111";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, did, DidStatus.REVOKED, PUBLIC_KEY_HEX);

        mockMvc.perform(get(BASE + "/" + did))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }

    @Test
    void resolveUnknownDidReturnsNotRegisteredAnswer() throws Exception {
        mockMvc.perform(get(BASE + "/did:ctds:S20260922009999.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005B0003"))
                .andExpect(jsonPath("$.message").value("该 DID 未登记"));
    }

    @Test
    void verifyPassesWithRealSm2Signature() throws Exception {
        // 前置检查①（验签 round-trip）：真实 SM2 密钥对 + 真实签名 → 三查全过
        final String subjectNo = "S20260922000113";
        final String did = "did:ctds:" + subjectNo + ".1";
        final byte[] data = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, pair.publicKeyHex());
        final byte[] signature = sm2Service.sign(data, pair.privateKeyHex());

        final String body = mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(data, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.result").value("PASS"))
                .andReturn().getResponse().getContentAsString();

        // B11：验证响应字段集严格 = 身份结论四字段（无任何授权/权限字段；PASS 时 reason 为空值）
        assertThat(MAPPER.readTree(body).get("data").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("did", "result", "reason", "verifiedAt");

        // 留痕三要素
        final Map<String, Object> log = jdbcTemplate.queryForMap(
                "SELECT did, result, reason, occurred_at FROM did_verification_log WHERE did = ?", did);
        assertThat(log.get("result")).isEqualTo("PASS");
        assertThat(log.get("reason")).isNull();
        assertThat(log.get("occurred_at")).isNotNull();
    }

    @Test
    void verifyFailsWhenDataTampered() throws Exception {
        final String subjectNo = "S20260922000114";
        final String did = "did:ctds:" + subjectNo + ".1";
        final byte[] data = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, pair.publicKeyHex());
        final byte[] signature = sm2Service.sign(data, pair.privateKeyHex());
        final byte[] tampered = "被篡改的数据".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(tampered, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FAIL"))
                .andExpect(jsonPath("$.data.reason").value("SIGNATURE_INVALID"));
    }

    @Test
    void verifyFailsWithRevokedWhenSignatureIsCryptographicallyReal() throws Exception {
        final String subjectNo = "S20260922000115";
        final String did = "did:ctds:" + subjectNo + ".1";
        final byte[] data = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, DidStatus.REVOKED, pair.publicKeyHex());
        final byte[] signature = sm2Service.sign(data, pair.privateKeyHex());

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(data, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FAIL"))
                .andExpect(jsonPath("$.data.reason").value("REVOKED"));
    }

    @Test
    void verifyFailsWhenSubjectBindingNotAdmitted() throws Exception {
        final String subjectNo = "S20260922000116";
        final String did = "did:ctds:" + subjectNo + ".1";
        final byte[] data = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, pair.publicKeyHex());
        doReturn(SubjectAdmission.NOT_ADMITTED).when(subjectStatusPort).check(subjectNo);

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(data, sm2Service.sign(data, pair.privateKeyHex()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FAIL"))
                .andExpect(jsonPath("$.data.reason").value("SUBJECT_BINDING_FAILED"));
    }

    @Test
    void verifyUnavailableWhenBindingServiceUnavailable() throws Exception {
        final String subjectNo = "S20260922000117";
        final String did = "did:ctds:" + subjectNo + ".1";
        final byte[] data = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, pair.publicKeyHex());
        doReturn(SubjectAdmission.UNAVAILABLE).when(subjectStatusPort).check(subjectNo);

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(data, sm2Service.sign(data, pair.privateKeyHex()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.reason").value("BINDING_UNAVAILABLE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT result FROM did_verification_log WHERE did = ?", String.class, did))
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void verifyUnknownDidFailsNotRegisteredWithLog() throws Exception {
        final String did = "did:ctds:S20260922009998.1";

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody("身份主张数据".getBytes(StandardCharsets.UTF_8),
                                "not-a-real-signature".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FAIL"))
                .andExpect(jsonPath("$.data.reason").value("NOT_REGISTERED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM did_verification_log WHERE did = ?", String.class, did))
                .isEqualTo("NOT_REGISTERED");
    }

    @Test
    void verifyRejectsInvalidInputs() throws Exception {
        final String did = "did:ctds:S20260922000118.1";
        // 非法 Base64
        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"not-base64!!\",\"signature\":\"AAAA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0004"));
        // 空数据
        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"  \",\"signature\":\"AAAA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0004"));
        // 输入类拒绝不落留痕
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM did_verification_log WHERE did = ?", Integer.class, did)).isZero();
    }

    @Test
    void verificationLogContainsNoDataOrSignatureRawBytes() throws Exception {
        final String subjectNo = "S20260922000119";
        final String did = "did:ctds:" + subjectNo + ".1";
        final byte[] data = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, pair.publicKeyHex());
        final byte[] signature = sm2Service.sign(data, pair.privateKeyHex());
        final String dataB64 = Base64.getEncoder().encodeToString(data);

        mockMvc.perform(post(BASE + "/" + did + "/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody(data, signature)))
                .andExpect(jsonPath("$.data.result").value("PASS"));

        // B10 正向：留痕全表不含被验证数据的 Base64 片段（验证通道不是数据存储通道）
        assertThat(rawPayloadHits(dataB64.substring(0, 16))).isZero();
        // 反向探针：植入该片段 → 命中（证明扫描非空断言，删"不留存"实现必变红）
        jdbcTemplate.update("INSERT INTO did_verification_log (did, result, reason, occurred_at) "
                        + "VALUES (?, 'FAIL', ?, ?)", did, dataB64.substring(0, 16),
                Timestamp.valueOf(LocalDateTime.now()));
        assertThat(rawPayloadHits(dataB64.substring(0, 16))).isGreaterThan(0);
        // 清理植入行 + 复扫归零
        jdbcTemplate.update("DELETE FROM did_verification_log WHERE reason = ?", dataB64.substring(0, 16));
        assertThat(rawPayloadHits(dataB64.substring(0, 16))).isZero();
    }

    @Test
    void resolutionResponseContainsNoSensitivePlaintext() throws Exception {
        // B4（行为 2 规则 2 / 验收 4）：解析响应全文不得出现身份证号/手机号/私钥样式明文
        final String subjectNo = "S20260922000120";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, PUBLIC_KEY_HEX);

        final String body = mockMvc.perform(get(BASE + "/" + did))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sensitiveHits(body)).isZero();
        // 反向探针：同一扫描器能命中植入的 L4 样本（证明扫描非空断言；实现若回填 L4 字段必变红）
        assertThat(sensitiveHits("{\"idCard\":\"110101199001011234\"}")).isGreaterThanOrEqualTo(1);
        assertThat(sensitiveHits("{\"phone\":\"13800138000\"}")).isGreaterThanOrEqualTo(1);
        assertThat(sensitiveHits("{\"privateKey\":\"" + "ab".repeat(32) + "\"}")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resolveThrowsInternalErrorWhenDocumentCorrupted() throws Exception {
        // hifi §6 边界表：注册表文档损坏 → 1005S0002（不暴露内部细节、不伪装"未登记"）
        final String subjectNo = "S20260922000121";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, did, DidStatus.ACTIVE, PUBLIC_KEY_HEX, "{not-a-json");

        mockMvc.perform(get(BASE + "/" + did))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("1005S0002"));
    }

    private static int sensitiveHits(final String text) {
        final Matcher matcher = SENSITIVE_PATTERN.matcher(text);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        return hits;
    }

    private int rawPayloadHits(final String fragment) {
        final Integer hits = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM did_verification_log WHERE did LIKE ? OR result LIKE ? OR reason LIKE ?",
                Integer.class, "%" + fragment + "%", "%" + fragment + "%", "%" + fragment + "%");
        assertThat(hits).isNotNull();
        return hits;
    }

    private static String verifyBody(final byte[] data, final byte[] signature) {
        return "{\"data\":\"" + Base64.getEncoder().encodeToString(data) + "\",\"signature\":\""
                + Base64.getEncoder().encodeToString(signature) + "\"}";
    }

    private void insertIdentity(final String subjectNo, final String did, final DidStatus status,
            final String publicKeyHex) {
        insertIdentity(subjectNo, did, status, publicKeyHex, documentJson(did, subjectNo, publicKeyHex));
    }

    /** 造数（文档损坏边界用）：直接写入受控 document_json。 */
    private void insertIdentity(final String subjectNo, final String did, final DidStatus status,
            final String publicKeyHex, final String documentJsonValue) {
        jdbcTemplate.update("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                        + "key_ref, document_json, guard_key, created_at, updated_at) "
                        + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectNo, did, status.name(), publicKeyHex, "did-" + subjectNo + "-1", documentJsonValue,
                status == DidStatus.REVOKED ? null : subjectNo,
                Timestamp.valueOf(LocalDateTime.now().withNano(0)),
                Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    private static String documentJson(final String did, final String subjectNo, final String publicKeyHex) {
        return "{\"did\":\"" + did + "\",\"publicKey\":{\"type\":\"SM2\","
                + "\"algorithm\":\"sm2p256v1\",\"valueHex\":\"" + publicKeyHex + "\"},"
                + "\"controller\":\"" + subjectNo + "\",\"service\":[{\"id\":\"#resolution\","
                + "\"type\":\"DidResolution\",\"serviceEndpoint\":\"/api/v1/did\"}],"
                + "\"created\":\"2026-09-22T20:00:00\"}";
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
