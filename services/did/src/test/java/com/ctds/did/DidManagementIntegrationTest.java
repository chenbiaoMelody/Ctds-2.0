package com.ctds.did;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 * DID 管理界面读数端点集成测试（WBS-3.1.11，hifi T1~T6/T8）：
 * 记录列表（分页/过滤/状态三值/空态/最新在前）、单 DID 操作留痕（五要素）、验证留痕（分页 + 列级无原文）、
 * 管理面权限 401/403、演示签名入口**默认关闭**（1000C0003 且零 KMS 调用）。
 * 本类以 application.yml 出厂默认（入口关闭）启动，覆盖生产默认态；入口开启态见
 * {@link DidDemoSignatureIntegrationTest}。各用例独立 subject_no（库共享，防跨用例串扰）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DidManagementIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/did";
    private static final String ADMIN = "ops-admin";
    private static final String ADMIN_ROLES = "admin";
    private static final String PUBLIC_KEY_HEX = "04" + "ab".repeat(64);

    /** DB-25：模块共享容器 + 本类独立库名（ADR-010 §8 形态 B）。 */
    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_did_management");
    }

    @MockitoBean
    private DidKmsClient kmsClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int tick;

    @Test
    void recordsListSupportsPagingAndFilters() throws Exception {
        // 同一主体多代（重签）三行 + 另一主体待签发行——全部断言按主体编号过滤，免受同库其它用例影响
        insertIdentity("S20260925100101", 1, "did:ctds:S20260925100101.1", DidStatus.REVOKED, PUBLIC_KEY_HEX);
        insertIdentity("S20260925100101", 2, "did:ctds:S20260925100101.2", DidStatus.REVOKED, PUBLIC_KEY_HEX);
        insertIdentity("S20260925100101", 3, "did:ctds:S20260925100101.3", DidStatus.ACTIVE, PUBLIC_KEY_HEX);
        insertIdentity("S20260925100102", 1, null, DidStatus.PENDING_ISSUE, null);

        final String body = mockMvc.perform(getAdmin(BASE + "/records")
                        .param("subjectNo", "S20260925100101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        // B1 出参字段集严格 = 契约字段（无 publicKeyHex / privateKey / document_json 等内部字段）
        final JsonNode first = MAPPER.readTree(body).path("data").path("list").get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "subjectNo", "issuanceSeq", "did", "status", "keyRef", "createdAt", "updatedAt");
        assertThat(body).doesNotContain("privateKey").doesNotContain("publicKeyHex")
                .doesNotContain("documentJson").doesNotContain("material");

        // 分页：pageSize=2 → 第二页 1 条、总页数 2
        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "S20260925100101")
                        .param("pageNum", "2").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(1));

        // 过滤：subjectNo / status（既有 DidStatus 三值口径，无新增枚举）
        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "S20260925100101")
                        .param("status", "ACTIVE"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].status").value("ACTIVE"));
        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "S20260925100101")
                        .param("status", "REVOKED"))
                .andExpect(jsonPath("$.data.total").value(2));

        // E7：待签发记录 did/keyRef 为空，状态 PENDING_ISSUE（记录中间态，非 DID 状态）
        final String pendingBody = mockMvc.perform(getAdmin(BASE + "/records")
                        .param("subjectNo", "S20260925100102"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].status").value("PENDING_ISSUE"))
                .andReturn().getResponse().getContentAsString();
        final JsonNode pending = MAPPER.readTree(pendingBody).path("data").path("list").get(0);
        assertThat(pending.path("did").isNull()).isTrue();
        assertThat(pending.path("keyRef").isNull()).isTrue();

        // 全量列表（不筛选）：总数 = 库表总数、默认页大小 10；E1 空白 subjectNo 等价于不筛选
        final long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM did_identity", Long.class);
        mockMvc.perform(getAdmin(BASE + "/records"))
                .andExpect(jsonPath("$.data.total").value(total))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10));
        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "   "))
                .andExpect(jsonPath("$.data.total").value(total));
        // E5：无匹配 → total=0 + 空列表（界面空态，非错误）
        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "S20260925199999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void recordsRejectInvalidFilterAndPagingInputs() throws Exception {
        // E4：分页参数越界 → 1000C0001（PageQuery 复用，不重复实现）
        mockMvc.perform(getAdmin(BASE + "/records").param("pageNum", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
        mockMvc.perform(getAdmin(BASE + "/records").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"));
        // E2：subjectNo 超 32 字符 → 1005C0001
        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "S".repeat(33)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0001"));
        // E3：status 非三值之一 → 1005C0001
        mockMvc.perform(getAdmin(BASE + "/records").param("status", "PENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0001"));
    }

    @Test
    void recordsAreOrderedNewestFirst() throws Exception {
        // E6：同主体多代 DID（重签场景）最新签发在前，旧记录（REVOKED）保留可追溯
        insertIdentity("S20260925100103", 1, "did:ctds:S20260925100103.1", DidStatus.REVOKED, PUBLIC_KEY_HEX);
        insertIdentity("S20260925100103", 2, "did:ctds:S20260925100103.2", DidStatus.ACTIVE, PUBLIC_KEY_HEX);

        mockMvc.perform(getAdmin(BASE + "/records").param("subjectNo", "S20260925100103"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].issuanceSeq").value(2))
                .andExpect(jsonPath("$.data.list[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.list[1].issuanceSeq").value(1))
                .andExpect(jsonPath("$.data.list[1].status").value("REVOKED"));
    }

    @Test
    void managementEndpointsEnforceDidAdminPermission() throws Exception {
        // T2：无身份 → 401（1000C0002）；非 did.admin 角色 → 403（1000C0005）
        for (final String path : new String[] {"/records", "/verification-logs",
            "/records/did:ctds:S20260925100104.1/operation-logs"}) {
            assertThat(mockMvc.perform(get(BASE + path)).andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
        mockMvc.perform(post(BASE + "/did:ctds:S20260925100104.1/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));

        mockMvc.perform(get(BASE + "/records").header("X-Ctds-Subject", "clerk")
                        .header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
        mockMvc.perform(post(BASE + "/did:ctds:S20260925100104.1/demo-signatures")
                        .header("X-Ctds-Subject", "clerk").header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
    }

    @Test
    void operationLogsReturnIssueAndRevokeWithFiveElements() throws Exception {
        // T4：吊销留痕五要素（操作人/时间/理由/DID/状态变更）齐备；签发留痕四要素齐备
        final String subjectNo = "S20260925100105";
        final String did = "did:ctds:" + subjectNo + ".1";
        doReturn(PUBLIC_KEY_HEX).when(kmsClient).createKeyPair(anyString());
        mockMvc.perform(post(BASE + "/issuances").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectNo\":\"" + subjectNo + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(postAdmin(BASE + "/" + did + "/revocation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"私钥疑似泄露\"}"))
                .andExpect(status().isOk());

        final String body = mockMvc.perform(getAdmin(BASE + "/records/" + did + "/operation-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].operation").value("ISSUE"))
                .andExpect(jsonPath("$.data[0].operator").value("SYSTEM"))
                .andExpect(jsonPath("$.data[0].keyRef").value("did-" + subjectNo + "-1"))
                .andExpect(jsonPath("$.data[0].statusFrom").value("PENDING_ISSUE"))
                .andExpect(jsonPath("$.data[0].statusTo").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].operation").value("REVOKE"))
                .andExpect(jsonPath("$.data[1].operator").value(ADMIN))
                .andExpect(jsonPath("$.data[1].reason").value("私钥疑似泄露"))
                .andExpect(jsonPath("$.data[1].statusFrom").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].statusTo").value("REVOKED"))
                .andReturn().getResponse().getContentAsString();

        final JsonNode revoke = MAPPER.readTree(body).path("data").get(1);
        assertThat(revoke.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "operation", "operator", "reason", "keyRef", "statusFrom", "statusTo", "occurredAt");
        assertThat(revoke.path("occurredAt").asText()).isNotEmpty();
        // 出参无任何私钥/材料字段
        assertThat(body).doesNotContain("privateKey").doesNotContain("publicKeyHex");
    }

    @Test
    void operationLogsReturnEmptyListWhenNoLogRowsExist() throws Exception {
        // E8 口径（含 WBS-3.1.11 澄清）：留痕为空时返回空列表（界面"暂无操作留痕"）；
        // 待签发记录无 DID 值，界面按空态呈现、不发起请求（见 IndexView 前端用例）
        final String subjectNo = "S20260925100106";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, 1, did, DidStatus.ACTIVE, PUBLIC_KEY_HEX);
        jdbcTemplate.update("DELETE FROM did_operation_log WHERE subject_no = ?", subjectNo);

        mockMvc.perform(getAdmin(BASE + "/records/" + did + "/operation-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void operationLogsForUnknownDidReturnNotRegistered() throws Exception {
        // T5/E9：未登记 DID → 1005B0003 明确业务答复
        mockMvc.perform(getAdmin(BASE + "/records/did:ctds:S20260925199998.1/operation-logs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005B0003"));
    }

    @Test
    void verificationLogsPaginateAndExposeNoRawPayload() throws Exception {
        // T6：分页 + did 过滤 + 列级断言（时间/DID/结果/原因四字段，无数据原文）
        final String did = "did:ctds:S20260925100107.1";
        final String other = "did:ctds:S20260925100108.1";
        insertVerificationLog(did, "PASS", null);
        insertVerificationLog(did, "FAIL", "SIGNATURE_INVALID");
        insertVerificationLog(did, "UNAVAILABLE", "BINDING_UNAVAILABLE");
        insertVerificationLog(other, "PASS", null);
        insertVerificationLog(other, "FAIL", "REVOKED");

        final String body = mockMvc.perform(getAdmin(BASE + "/verification-logs").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(MAPPER.readTree(body).path("data").path("list").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("did", "result", "reason", "occurredAt");

        mockMvc.perform(getAdmin(BASE + "/verification-logs").param("did", did))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list[0].result").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.list[0].reason").value("BINDING_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.list[2].result").value("PASS"));

        // E24：留痕列表出参不含数据原文与私钥字段
        assertThat(body).doesNotContain("signature").doesNotContain("privateKey");
    }

    @Test
    void verificationLogsRejectOverlongDidFilter() throws Exception {
        mockMvc.perform(getAdmin(BASE + "/verification-logs").param("did", "d".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1005C0001"));
    }

    @Test
    void demoSignatureDisabledByDefaultReturnsResourceNotFoundWithoutKmsCall() throws Exception {
        // T8/E19：出厂默认关闭 → 1000C0003 + 文案；且不产生任何 KMS 调用
        // （HTTP 状态：平台统一按错误类型映射 1000C0003 → 400，见 common-errorcode ErrorType；非 404）
        final String did = "did:ctds:S20260925100109.1";
        insertIdentity("S20260925100109", 1, did, DidStatus.ACTIVE, PUBLIC_KEY_HEX);

        mockMvc.perform(postAdmin(BASE + "/" + did + "/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"确认文本\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"))
                .andExpect(jsonPath("$.message").value("演示签名入口未启用（仅演示/调试期）"));
        verifyNoInteractions(kmsClient);

        // 未登记 DID 同样只回"入口未启用"（关闭态不区分目标存在性，不构成状态枚举通道）
        mockMvc.perform(postAdmin(BASE + "/did:ctds:S20260925199997.1/demo-signatures")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"确认文本\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));
    }

    @Test
    void recordsPathDoesNotShadowResolutionEndpoint() throws Exception {
        // 回归锚点：/records 与既有 /{did} 共用前缀，字面量路径优先，解析端点行为不变
        final String subjectNo = "S20260925100110";
        final String did = "did:ctds:" + subjectNo + ".1";
        insertIdentity(subjectNo, 1, did, DidStatus.ACTIVE, PUBLIC_KEY_HEX);

        mockMvc.perform(getAdmin(BASE + "/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get(BASE + "/" + did))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.document.publicKey.valueHex").value(PUBLIC_KEY_HEX));
    }

    private MockHttpServletRequestBuilder getAdmin(final String url) {
        return get(url).header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", ADMIN_ROLES);
    }

    private MockHttpServletRequestBuilder postAdmin(final String url) {
        return post(url).header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", ADMIN_ROLES);
    }

    private void insertIdentity(final String subjectNo, final int issuanceSeq, final String did,
            final DidStatus status, final String publicKeyHex) {
        final LocalDateTime now = LocalDateTime.now().withNano(0).plusSeconds(tick++);
        jdbcTemplate.update("INSERT INTO did_identity (subject_no, issuance_seq, did, status, public_key_hex, "
                        + "key_ref, document_json, guard_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                subjectNo, issuanceSeq, did, status.name(), publicKeyHex,
                did == null ? null : "did-" + subjectNo + "-" + issuanceSeq,
                did == null ? null : documentJson(did, subjectNo, publicKeyHex),
                status == DidStatus.REVOKED ? null : subjectNo,
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    private void insertVerificationLog(final String did, final String result, final String reason) {
        jdbcTemplate.update("INSERT INTO did_verification_log (did, result, reason, occurred_at) "
                        + "VALUES (?, ?, ?, ?)",
                did, result, reason, Timestamp.valueOf(LocalDateTime.now().withNano(0).plusSeconds(tick++)));
    }

    private static String documentJson(final String did, final String subjectNo, final String publicKeyHex) {
        return "{\"did\":\"" + did + "\",\"publicKey\":{\"type\":\"SM2\","
                + "\"algorithm\":\"sm2p256v1\",\"valueHex\":\"" + publicKeyHex + "\"},"
                + "\"controller\":\"" + subjectNo + "\",\"service\":[{\"id\":\"#resolution\","
                + "\"type\":\"DidResolution\",\"serviceEndpoint\":\"/api/v1/did\"}],"
                + "\"created\":\"2026-09-25T10:00:00\"}";
    }
}
