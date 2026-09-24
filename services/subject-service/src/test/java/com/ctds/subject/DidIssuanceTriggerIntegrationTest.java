package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.subject.domain.DidIssuancePort;
import com.ctds.subject.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DID 签发触发衔接集成测试（WBS-3.1.8 行为清单 B7 / ADR-016 §6）：
 * 审核通过 → DidIssuanceTrigger 收到主体编号；触发抛异常 → 批准仍 200、状态仍 ADMITTED。
 * DID 端口 @MockitoBean 替换（DID 服务自身行为由 did-service 集成测试覆盖）。
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DidIssuanceTriggerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject";
    private static final String APPLICANT = "applicant-01";
    private static final String REVIEWER = "reviewer-01";
    private static Path auditDir;
    private static Path keyFile;

    /** DB-25：模块共享容器 + 本类独立库名（ADR-010 §8 形态 B）。 */
    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_subject_did_trigger");
    }

    @MockitoBean
    private DidIssuancePort didIssuancePort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-did-trigger");
        keyFile = Files.createTempFile("ctds-test-keys", ".keys");
        Files.writeString(keyFile, "subject-cert-material=MDEyMzQ1Njc4OWFiY2RlZg==\n",
                StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteDirs() throws Exception {
        try (Stream<Path> paths = Files.walk(auditDir).sorted(Comparator.reverseOrder())) {
            paths.forEach(path -> path.toFile().delete());
        }
        Files.deleteIfExists(keyFile);
    }

    @DynamicPropertySource
    static void testProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @Test
    void approveTriggersDidIssuanceWithSubjectNo() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1501", "签发触发演示局");

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/approval")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADMITTED"));

        verify(didIssuancePort).triggerIssuance(org.mockito.ArgumentMatchers.eq(subjectNo), anyString(), anyString());
        assertThat(subjectStatus(subjectNo)).isEqualTo("ADMITTED");
    }

    @Test
    void didIssuanceFailureDoesNotAffectApproval() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1502", "触发失败演示局");
        doThrow(new IllegalStateException("DID 服务不可达")).when(didIssuancePort)
                .triggerIssuance(anyString(), anyString(), anyString());

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/approval")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADMITTED"));

        assertThat(subjectStatus(subjectNo)).isEqualTo("ADMITTED");
    }

    @Test
    void rejectedSubjectDoesNotTriggerDidIssuance() throws Exception {
        // 负向锚点（评审④ P2-B，hifi B1"驳回不触发"）：非 ADMITTED 状态 → 签发触发零调用
        final String subjectNo = createPendingGovSubject("91330100MA27XW1503", "驳回不触发局");

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"材料不符\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        verify(didIssuancePort, never()).triggerIssuance(anyString(), anyString(), anyString());
        assertThat(subjectStatus(subjectNo)).isEqualTo("REJECTED");
    }

    // ==== WBS-3.1.9 内部入驻状态端点用例（合并自原 InternalAdmissionIntegrationTest，共享本类容器）====

    @Test
    void internalAdmissionReturnsStatusOnlyWithDidInternalRole() throws Exception {
        final String subjectNo = registerGovSubject("91330100MA27XW1507", "内部状态查询局");

        final String body = mockMvc.perform(get(BASE + "/internal/subjects/" + subjectNo + "/admission")
                        .header("X-Ctds-Subject", "did-service").header("X-Ctds-Roles", "did-internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CERT"))
                .andReturn().getResponse().getContentAsString();

        // 最小暴露：仅 subjectNo + status 两字段（无注册信息、无脱敏字段、无流转记录）
        assertThat(MAPPER.readTree(body).get("data").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("subjectNo", "status");
    }

    @Test
    void internalAdmissionEnforcesReadPermission() throws Exception {
        final String subjectNo = registerGovSubject("91330100MA27XW1508", "内部状态权限局");
        // 未认证 401
        mockMvc.perform(get(BASE + "/internal/subjects/" + subjectNo + "/admission"))
                .andExpect(status().isUnauthorized());
        // 无权限角色 403
        mockMvc.perform(get(BASE + "/internal/subjects/" + subjectNo + "/admission")
                        .header("X-Ctds-Subject", "someone").header("X-Ctds-Roles", "nobody"))
                .andExpect(status().isForbidden());
        // 评审②P2-1：持 subject.read 的申请人/审核员亦不得读内部状态（专用权限点收敛，防按编号枚举）
        mockMvc.perform(get(BASE + "/internal/subjects/" + subjectNo + "/admission")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/internal/subjects/" + subjectNo + "/admission")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalAdmissionUnknownSubjectIsResourceNotFound() throws Exception {
        mockMvc.perform(get(BASE + "/internal/subjects/S20260922999999/admission")
                        .header("X-Ctds-Subject", "did-service").header("X-Ctds-Roles", "did-internal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));
    }

    // ==== 辅助 ====

    private String subjectStatus(final String subjectNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM subject WHERE subject_no = ?", String.class, subjectNo);
    }

    private String createPendingGovSubject(final String uscc, final String subjectName) throws Exception {
        final String subjectNo = registerGovSubject(uscc, subjectName);
        mockMvc.perform(multipart(BASE + "/registrations/" + subjectNo + "/certification/gov-ca-certificate")
                        .file(new MockMultipartFile("file", "A3.cer",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "valid-cert".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk());
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        return subjectNo;
    }

    private String registerGovSubject(final String uscc, final String subjectName) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE + "/registrations")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"" + subjectName + "\",\"uscc\":\"" + uscc + "\","
                                + "\"subjectType\":\"GOV\",\"regAddress\":\"杭州市XX区XX路88号\","
                                + "\"contactName\":\"王科\",\"contactPhone\":\"13800005678\","
                                + "\"adminAccount\":\"govadmin\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data")
                .get("subjectNo").asText();
    }
}
