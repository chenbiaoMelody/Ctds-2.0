package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.errorcode.BizException;
import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AUD-04 事务边界集成测试（任务卡卡 4，对应债务 DB-04）：
 * 同一业务动作的多次仓储写入必须同属一个事务——异常注入使"第二步"（状态流转）失败时，
 * "第一步"（核验留痕落库）必须回滚，不留下"档案里有 PASS 留痕、状态却没流转"的半成品数据
 * （审核报告 AUD-04 点名 {@code CertificationService.verifyLegalPerson}）。
 * 注入方式：{@code @MockitoSpyBean} 包装真实仓储，仅对被测主体抛出门槛异常（确定性注入，
 * 不依赖并发时序）；控制组证明 spy 装配不破坏正常链路。本机 Docker 未运行时自动跳过（ADR-010）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class VerifyTransactionRollbackIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static final String LEGAL_PERSON = "张伟";
    /** 虚构且校验位合法、尾号非 8（核验通过口径，与 CertificationIntegrationTest 一致）。 */
    private static final String LEGAL_PERSON_ID_OK = "110101199001011229";
    /** 模拟渠道对预置影像 A1 返回的固定信用代码（剧本附录 A 组 A1）。 */
    private static final String MOCK_OCR_USCC = "91330100MA27XW123X";
    private static Path auditDir;
    private static Path keyFile;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_subject");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** spy 包装真实仓储：只对被测主体的流转注入失败，其余走真实逻辑。 */
    @MockitoSpyBean
    private SubjectRepository subjectRepository;

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-txrb");
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
    void verificationLogRollsBackWhenTransitionFails() throws Exception {
        // 第二步（状态流转）被注入失败 → 第一步（核验留痕）必须回滚：
        // 修复前留痕先行独立提交，流转失败后库内残留 PASS 留痕而状态停在 PENDING_CERT（半成品档案）
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB21", "回滚注入演示公司");
        final long subjectId = subjectId(subjectNo);
        doThrow(new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "并发门槛拒绝（AUD-04 注入）"))
                .when(subjectRepository).appendTransition(eq(subjectId), any(StatusTransition.class));

        verifyLegal(subjectNo, LEGAL_PERSON, LEGAL_PERSON_ID_OK)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));

        assertThat(verificationLogCount(subjectNo))
                .as("流转失败后核验留痕必须回滚（不得残留 PASS 行）").isZero();
        assertThat(subjectStatus(subjectNo))
                .as("主体状态保持待认证（无半成品）").isEqualTo("PENDING_CERT");
    }

    @Test
    void controlNormalVerifyStillTransitionsWithLog() throws Exception {
        // 控制组：不打桩时 spy 装配下正常链路不受影响（留痕 + 流转双落库）
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB22", "回滚对照组演示公司");

        verifyLegal(subjectNo, LEGAL_PERSON, LEGAL_PERSON_ID_OK)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("PASS"));

        assertThat(verificationLogCount(subjectNo)).isEqualTo(1);
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
    }

    private long subjectId(final String subjectNo) {
        return jdbcTemplate.queryForObject("SELECT id FROM subject WHERE subject_no = ?", Long.class, subjectNo);
    }

    private int verificationLogCount(final String subjectNo) {
        final Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_verification_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND verify_type = 'LEGAL_PERSON'",
                Integer.class, subjectNo);
        return count == null ? 0 : count;
    }

    private String subjectStatus(final String subjectNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM subject WHERE subject_no = ?", String.class, subjectNo);
    }

    private String registerAndUploadAndConfirm(final String uscc, final String subjectName) throws Exception {
        final String subjectNo = register(uscc, subjectName);
        uploadLicense(subjectNo, "image-bytes".getBytes(StandardCharsets.US_ASCII), "A1.jpg")
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/license/confirmation")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(MOCK_OCR_USCC)))
                .andExpect(status().isOk());
        return subjectNo;
    }

    private String register(final String uscc, final String subjectName) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uscc, subjectName)))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data")
                .get("subjectNo").asText();
    }

    private ResultActions uploadLicense(final String subjectNo, final byte[] image, final String fileName)
            throws Exception {
        return mockMvc.perform(multipart(BASE + "/" + subjectNo + "/certification/license")
                .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_JPEG_VALUE, image))
                .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"));
    }

    private ResultActions verifyLegal(final String subjectNo, final String name, final String idNo)
            throws Exception {
        return mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/legal-person-verifications")
                .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"legalPersonName\":\"" + name + "\",\"legalPersonIdNo\":\"" + idNo + "\"}"));
    }

    private String registerBody(final String uscc, final String subjectName) {
        return "{\"subjectName\":\"" + subjectName + "\",\"uscc\":\"" + uscc + "\",\"subjectType\":\"ENTERPRISE\","
                + "\"regAddress\":\"杭州市XX区XX路88号\",\"contactName\":\"张三\",\"contactPhone\":\"13800001234\","
                + "\"adminAccount\":\"admin001\"}";
    }

    private String confirmBody(final String uscc) {
        return "{\"subjectName\":\"演示主体名称\",\"uscc\":\"" + uscc + "\",\"legalPerson\":\"" + LEGAL_PERSON
                + "\",\"regAddress\":\"杭州市XX区XX路88号\"}";
    }
}
