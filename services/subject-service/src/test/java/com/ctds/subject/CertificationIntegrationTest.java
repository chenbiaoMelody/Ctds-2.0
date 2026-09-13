package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 实名认证全链路集成测试（规格 C-1.1 行为 2/3/4/7 验收标准 + hifi B4~B12，ADR-010 容器化基座）：
 * 上传 OCR（密文落库断言）/差异阻断/核验通过自动流转/失败计数与当日上限/归属断言双向用例（他人 403 + 审计、
 * 审核员豁免）/影像查看审计/档案脱敏口径/结束认证。本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 * 渠道异常 fail-fast 的 503 出站封套另见 {@link CertChannelUnavailableIntegrationTest}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class CertificationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static final String OTHER = "applicant-02";
    private static final String REVIEWER = "reviewer-01";
    private static final String LEGAL_PERSON = "张伟";
    private static final String LEGAL_PERSON_ID_OK = "11010119900101123X";
    private static final String LEGAL_PERSON_ID_FAIL = "110101199001011238";
    /** 模拟渠道对预置影像 A1 返回的固定信用代码（剧本附录 A 组 A1），确认值以 OCR 回填为准。 */
    private static final String MOCK_OCR_USCC = "91330100MA27XW123X";
    private static Path auditDir;
    private static Path keyFile;

    /** 显式镜像标签 mysql:8.0（ADR-010 禁止 latest），类级共享容器。 */
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_subject");

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-cert");
        // 16 字节测试密钥（仅测试用）；文件命名 *.keys（.gitignore 拦截口径，密钥零入库）
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void uploadOcrConfirmationVerifyFullHappyPath() throws Exception {
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB01", "全链路演示公司");

        // 法人核验通过 → 自动流转待审核（SYSTEM 触发），核验记录留痕三要素
        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.conclusion").value("PASS"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        final Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND from_status = 'PENDING_CERT' AND to_status = 'PENDING_REVIEW' "
                        + "AND trigger_role = 'SYSTEM'",
                Integer.class, subjectNo);
        assertThat(transitions).isEqualTo(1);
        // 核验记录留痕三要素：仅统计法人核验调用（OCR 上传调用另有一条 PASS 留痕）
        final Integer verifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_verification_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND verify_type = 'LEGAL_PERSON' AND conclusion = 'PASS'",
                Integer.class, subjectNo);
        assertThat(verifications).isEqualTo(1);
    }

    @Test
    void uploadedImageAndOcrRawResultAreCiphertextInDatabase() throws Exception {
        final String subjectNo = register("91330100MA27X8AB02", "密文落库演示公司");
        final byte[] image = "fake-license-image-bytes-2".getBytes(StandardCharsets.US_ASCII);


        uploadLicense(subjectNo, image, "A1.jpg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ocrResult.uscc").value(MOCK_OCR_USCC));

        // 规格行为 2 验收-4：库内直查为密文形态（非原文），且 OCR 原始结果同样加密
        final Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content_cipher, ocr_raw_cipher, ocr_recognizable, ocr_uscc FROM cert_material "
                        + "WHERE subject_id = (SELECT id FROM subject WHERE subject_no = ?)",
                subjectNo);
        assertThat((byte[]) row.get("content_cipher")).isNotEqualTo(image);
        assertThat((byte[]) row.get("ocr_raw_cipher")).isNotEmpty();
        assertThat(new String((byte[]) row.get("content_cipher"), StandardCharsets.ISO_8859_1))
                .doesNotContain("fake-license-image-bytes-2");
        assertThat((int) row.get("ocr_recognizable")).isEqualTo(1);
        assertThat((String) row.get("ocr_uscc")).isEqualTo(MOCK_OCR_USCC);
    }

    @Test
    void unrecognizableImagePromptsRetransmissionWithoutElements() throws Exception {
        final String subjectNo = register("91330100MA27X8AB03", "不可识别演示公司");

        uploadLicense(subjectNo, "not-a-license".getBytes(StandardCharsets.US_ASCII), "random.jpg")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0002"))
                .andExpect(jsonPath("$.message").value("证照影像无法识别，请重传"));

        final Integer recognizable = jdbcTemplate.queryForObject(
                "SELECT ocr_recognizable FROM cert_material WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?)",
                Integer.class, subjectNo);
        assertThat(recognizable).isZero();
    }

    @Test
    void confirmationWithMismatchedUsccIsBlockedWithDifferenceHint() throws Exception {
        final String subjectNo = registerAndUpload("91330100MA27X8AB04", "差异阻断演示公司");

        mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/license/confirmation")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody("91330100MA27X8AB99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0003"))
                .andExpect(jsonPath("$.message")
                        .value("统一社会信用代码与证照识别结果不一致，请修正后提交"));
    }

    @Test
    void dailyFailureLimitBlocksSixthAttemptWithFriendlyMessage() throws Exception {
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB05", "重试上限演示公司");
        for (int i = 0; i < 5; i++) {
            verifyLegal(subjectNo, LEGAL_PERSON_ID_FAIL, APPLICANT)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.conclusion").value("FAIL"));
        }

        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, APPLICANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0005"))
                .andExpect(jsonPath("$.message").value("今日核验次数已用完，请次日再试"));
    }

    /** 归属断言双向用例（ADR-016 §2.6）：本人通过 + 他人 403 + 拒绝审计；审核员持 subject.read 豁免查看。 */
    @Test
    void ownershipGuardBlocksOtherApplicantAndExemptsReviewer() throws Exception {
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB06", "归属断言演示公司");

        // 他人（applicant-02）查询/核验/看影像 → 403
        mockMvc.perform(get(BASE + "/" + subjectNo)
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"));
        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, OTHER)
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + subjectNo + "/certification/license/image")
                        .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isForbidden());

        final JsonNode denied = awaitEvent("certification.verify", "DENIED", OTHER);
        assertThat(denied.get("actor").asText()).isEqualTo(OTHER);

        // 审核员豁免（subject.read + subject.review）可查看档案与影像
        mockMvc.perform(get(BASE + "/" + subjectNo + "/certification")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.license.confirmed").value(true));
        mockMvc.perform(get(BASE + "/" + subjectNo + "/certification/license/image")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk());

        // 查看影像留审计（规格行为 2 验收-4："谁在何时查看"）
        final JsonNode viewEvent = awaitEvent("certification.image.view", "SUCCESS", REVIEWER);
        assertThat(viewEvent.get("actor").asText()).isEqualTo(REVIEWER);
    }

    @Test
    void profileHidesIdNumberAndFailureDecreasesRemainingAttempts() throws Exception {
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB07", "档案口径演示公司");

        verifyLegal(subjectNo, LEGAL_PERSON_ID_FAIL, APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("FAIL"))
                .andExpect(jsonPath("$.data.remainingAttemptsToday").value(4));

        // 档案查询：身份证号全程不回显
        final MvcResult result = mockMvc.perform(get(BASE + "/" + subjectNo + "/certification")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.license.confirmed").value(true))
                // 档案含渠道调用记录：1 条 OCR（上传识别）+ 1 条法人核验失败
                .andExpect(jsonPath("$.data.verifications.length()").value(2))
                .andReturn();
        final String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(LEGAL_PERSON_ID_FAIL);
    }

    @Test
    void abandonCertificationMarksFailedWithApplicantTransitionLog() throws Exception {
        final String subjectNo = register("91330100MA27X8AB08", "结束认证演示公司");

        mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/abandonment")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CERT_FAILED"));

        final Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND from_status = 'PENDING_CERT' AND to_status = 'CERT_FAILED' "
                        + "AND trigger_role = 'APPLICANT'",
                Integer.class, subjectNo);
        assertThat(transitions).isEqualTo(1);
    }

    // ==== 辅助 ====

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

    private ResultActions verifyLegal(final String subjectNo, final String idNo, final String operator)
            throws Exception {
        return mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/legal-person-verifications")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "applicant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifyBody(LEGAL_PERSON, idNo)));
    }

    private String registerAndUpload(final String uscc, final String subjectName) throws Exception {
        final String subjectNo = register(uscc, subjectName);
        uploadLicense(subjectNo, "image-bytes".getBytes(StandardCharsets.US_ASCII), "A1.jpg")
                .andExpect(status().isOk());
        return subjectNo;
    }

    private String registerAndUploadAndConfirm(final String uscc, final String subjectName) throws Exception {
        final String subjectNo = registerAndUpload(uscc, subjectName);
        // 确认值以 OCR 回填为准（业务口径：申请人核对的是 OCR 要素）
        mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/license/confirmation")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(MOCK_OCR_USCC)))
                .andExpect(status().isOk());
        return subjectNo;
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

    private String verifyBody(final String name, final String idNo) {
        return "{\"legalPersonName\":\"" + name + "\",\"legalPersonIdNo\":\"" + idNo + "\"}";
    }

    private JsonNode awaitEvent(final String action, final String outcome, final String actor) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (Files.exists(auditDir)) {
                try (Stream<Path> files = Files.list(auditDir)) {
                    final List<Path> auditFiles = files.filter(p -> p.getFileName().toString().startsWith("audit-"))
                            .toList();
                    for (final Path file : auditFiles) {
                        for (final String line : Files.readAllLines(file)) {
                            if (line.contains("\"" + action + "\"")) {
                                final JsonNode event = MAPPER.readTree(line);
                                if (outcome.equals(event.path("outcome").asText())
                                        && actor.equals(event.path("actor").asText())) {
                                    return event;
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("audit event not found: " + action + " / " + outcome + " / " + actor);
    }
}
