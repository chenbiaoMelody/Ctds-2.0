package com.ctds.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.crypto.Sm2KeyPair;
import com.ctds.common.crypto.Sm2Service;
import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.support.IsoSecondTimestamp;
import com.ctds.did.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 演示签名入口集成测试（WBS-3.1.11，hifi T7/E20/E22；规格 C-1.2 §6 第 6 条）：
 * 入口**显式开启**态下代签 → 用该 DID 文档公钥经 common-crypto **真实验签通过**；
 * 原文/签名不入库、不落留痕；边界（未登记/空与超长原文）与 KMS 不可用如实映射。
 * 关闭态（生产默认）由 {@link DidManagementIntegrationTest} 承载。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ctds.did.demo-signature.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DidDemoSignatureIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/did";
    private static final String ADMIN = "ops-admin";

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_did_demo_signature");
    }

    @MockitoBean
    private DidKmsClient kmsClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Sm2Service sm2Service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void demoSignatureProducesRealVerifiableSm2Signature() throws Exception {
        // T7：KMS 桩以真实 SM2 私钥签名 → 返回值用该 DID 文档公钥经 common-crypto 验签必须通过
        final String subjectNo = "S20260925100201";
        final String did = "did:ctds:" + subjectNo + ".1";
        final String keyRef = "did-" + subjectNo + "-1";
        final Sm2KeyPair pair = sm2Service.generateKeyPair();
        insertIdentity(subjectNo, 1, did, DidStatus.ACTIVE, pair.publicKeyHex(), keyRef);
        final byte[] plaintext = "蓝天数据科技有限公司确认接入城市可信数据空间".getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            final byte[] payload = Base64.getDecoder().decode(invocation.getArgument(1, String.class));
            return Base64.getEncoder().encodeToString(sm2Service.sign(payload, pair.privateKeyHex()));
        }).when(kmsClient).sign(eq(keyRef), anyString());

        final String body = mockMvc.perform(postAdmin(BASE + "/" + did + "/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"蓝天数据科技有限公司确认接入城市可信数据空间\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.did").value(did))
                .andReturn().getResponse().getContentAsString();

        final JsonNode data = MAPPER.readTree(body).path("data");
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("did", "data", "signature", "signedAt");
        assertThat(data.path("data").asText())
                .isEqualTo(Base64.getEncoder().encodeToString(plaintext));
        // T2：演示签名 signedAt 钉 ISO-8601 秒级本地时间形（原仅断非空）
        IsoSecondTimestamp.assertSecondPrecisionIso("signedAt", data.path("signedAt").asText());

        final byte[] signature = Base64.getDecoder().decode(data.path("signature").asText());
        // 真实证据：原文 + 签名 + DID 文档公钥 → 验签通过；改动原文即不通过
        assertThat(sm2Service.verify(plaintext, signature, pair.publicKeyHex())).isTrue();
        assertThat(sm2Service.verify("被篡改的文本".getBytes(StandardCharsets.UTF_8), signature,
                pair.publicKeyHex())).isFalse();

        // 原文与签名不入库（演示通道不是数据存储通道），也不产生任何操作/验证留痕
        final String fragment = Base64.getEncoder().encodeToString(plaintext).substring(0, 16);
        assertThat(countMatching("did_identity", "document_json", fragment)).isZero();
        assertThat(countMatching("did_operation_log", "reason", fragment)).isZero();
        assertThat(countMatching("did_verification_log", "reason", fragment)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM did_operation_log WHERE subject_no = ?",
                Integer.class, subjectNo)).isZero();
    }

    @Test
    void demoSignatureRejectsUnknownDidAndInvalidPlaintext() throws Exception {
        // E21/E20：未登记 → 1005B0003；原文空/空白、超 1024 字符 → 1005C0001
        mockMvc.perform(postAdmin(BASE + "/did:ctds:S20260925199996.1/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"确认文本\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005B0003"))
                .andExpect(jsonPath("$.message").value("该 DID 未登记"));

        mockMvc.perform(postAdmin(BASE + "/did:ctds:S20260925199996.1/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0001"));

        final String subjectNo = "S20260925100202";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, 1, did, DidStatus.ACTIVE, "04" + "ab".repeat(64), "did-" + subjectNo + "-1");
        mockMvc.perform(postAdmin(BASE + "/" + did + "/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"" + "字".repeat(1025) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0001"));
    }

    @Test
    void demoSignatureMapsKmsFailureToInternalErrorWithoutLog() throws Exception {
        // E22：KMS 不可达 → 1005S0002 文案；不落任何留痕
        final String subjectNo = "S20260925100203";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, 1, did, DidStatus.ACTIVE, "04" + "ab".repeat(64), "did-" + subjectNo + "-1");
        doThrow(new IllegalStateException("KMS 不可达")).when(kmsClient).sign(anyString(), anyString());

        mockMvc.perform(postAdmin(BASE + "/" + did + "/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"确认文本\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("1005S0002"))
                // S 类错误对外统一遮蔽为平台通用文案（不暴露内部实现，GlobalExceptionHandler 口径）
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM did_operation_log WHERE subject_no = ?",
                Integer.class, subjectNo)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM did_verification_log WHERE did = ?",
                Integer.class, did)).isZero();
    }

    private MockHttpServletRequestBuilder postAdmin(final String url) {
        return post(url).header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin");
    }

    /** 库表文本列片段扫描（演示通道不留存原文/签名；表名与列名为测试内常量，值经占位符）。 */
    private int countMatching(final String table, final String column, final String fragment) {
        final Integer hits = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM " + table + " WHERE " + column + " LIKE ?",
                Integer.class, "%" + fragment + "%");
        assertThat(hits).isNotNull();
        return hits;
    }

    private void insertIdentity(final String subjectNo, final int issuanceSeq, final String did,
            final DidStatus status, final String publicKeyHex, final String keyRef) {
        final LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                        + "key_ref, document_json, guard_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectNo, issuanceSeq, did, status.name(), publicKeyHex, keyRef,
                documentJson(did, subjectNo, publicKeyHex), subjectNo,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private static String documentJson(final String did, final String subjectNo, final String publicKeyHex) {
        return "{\"did\":\"" + did + "\",\"publicKey\":{\"type\":\"SM2\","
                + "\"algorithm\":\"sm2p256v1\",\"valueHex\":\"" + publicKeyHex + "\"},"
                + "\"controller\":\"" + subjectNo + "\",\"service\":[{\"id\":\"#resolution\","
                + "\"type\":\"DidResolution\",\"serviceEndpoint\":\"/api/v1/did\"}],"
                + "\"created\":\"2026-09-25T10:00:00\"}";
    }
}
