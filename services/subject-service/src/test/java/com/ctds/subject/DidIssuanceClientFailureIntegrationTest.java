package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.subject.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DID 签发触发"真实网络不可达"链路集成测试（WBS-3.1.8 行为清单 B7，评审④ P2-C）：
 * 不 mock DidIssuancePort，真实 DidIssuanceClient 指向不可达端口（连接超时失败）→
 * 审核通过仍 200/ADMITTED、主体状态不变（行为 1 规则 5：签发失败不影响入驻结论）。
 * 与 DidIssuanceTriggerIntegrationTest（port 为 mock）互补：本类锚定真实 HTTP 失败路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DidIssuanceClientFailureIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject";
    private static final String APPLICANT = "applicant-02";
    private static final String REVIEWER = "reviewer-02";
    /** 启动时动态取一个当前空闲端口（占位后立即释放），保证 DID 地址确实不可达。 */
    private static final int UNREACHABLE_PORT = unusedPort();
    private static Path auditDir;
    private static Path keyFile;

    /** DB-25：模块共享容器 + 本类独立库名（ADR-010 §8 形态 B）。 */
    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_subject_did_client_failure");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-did-client-failure");
        keyFile = Files.createTempFile("ctds-test-keys-client-failure", ".keys");
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
        registry.add("ctds.did.issuance.base-url", () -> "http://127.0.0.1:" + UNREACHABLE_PORT);
    }

    @Test
    void approvalSucceedsWhenDidServiceUnreachable() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1504", "真实不可达局");

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/approval")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADMITTED"));

        assertThat(subjectStatus(subjectNo)).isEqualTo("ADMITTED");
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            return 59999;
        }
    }

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
