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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 政务 CA 接入全链路集成测试（规格 C-1.1 行为 6 验收标准 + WBS-3.1.4 hifi B1~B9）：
 * A3 有效证书通过自动流转待审核（**持久化状态断言**——3.1.3 教训固化）/ A4 过期证书拒绝停留
 * 待认证并可重新提交换证 / 通道互斥双向 / 证书密文落库与 SM3 / 归属断言双向 / 档案政务段。
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class GovCaCertificationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static final String OTHER = "applicant-02";
    private static final String REVIEWER = "reviewer-01";
    /** 机关统一社会信用代码格式（GB 32100 字符集，伏编号段虚构）。 */
    private static final String GOV_USCC = "11330100MA27XW1301";
    private static Path auditDir;
    private static Path keyFile;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_subject");

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-gov");
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

    @Autowired
    private com.ctds.common.crypto.Sm3Service sm3Service;

    @Test
    void govCaValidCertPassesToPendingReviewWithFullAuditTrail() throws Exception {
        final String subjectNo = registerGovSubject("91330100MA27XW1301", "政务全链路演示局");
        final byte[] cert = "fake-gov-ca-cert-a3".getBytes(StandardCharsets.US_ASCII);

        submitGovCert(subjectNo, cert, "A3.cer", APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("PASS"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.failReason").doesNotExist());

        // 持久化状态断言（3.1.3 教训）：流转必须落 subject.status 列 + SYSTEM 触发留痕
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        final Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND from_status = 'PENDING_CERT' AND to_status = 'PENDING_REVIEW' "
                        + "AND trigger_role = 'SYSTEM' AND remark = '政务 CA 证书验证通过，自动流转'",
                Integer.class, subjectNo);
        assertThat(transitions).isEqualTo(1);
        // 留痕三要素：verify_type=GOV_CA + 渠道标识/流水号取自接口出口 + 不计失败 + 耗时留痕
        final Map<String, Object> logRow = jdbcTemplate.queryForMap(
                "SELECT channel_code, channel_request_no, conclusion, counted, cost_ms FROM cert_verification_log "
                        + "WHERE subject_id = (SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND verify_type = 'GOV_CA'",
                subjectNo);
        assertThat(logRow.get("channel_code")).isEqualTo("mock-certification");
        assertThat((String) logRow.get("channel_request_no")).startsWith("MOCK-");
        assertThat(logRow.get("conclusion")).isEqualTo("PASS");
        assertThat((int) logRow.get("counted")).isZero();
        assertThat((int) logRow.get("cost_ms")).isGreaterThanOrEqualTo(0);
        // 证书文件密文落库（L4）：密文形态 + SM3 与原文现算一致 + 验证要素密文不含明文（评审视角 1/4 补齐）
        final Map<String, Object> materialRow = jdbcTemplate.queryForMap(
                "SELECT material_type, file_name, content_cipher, content_sm3, ocr_raw_cipher, ocr_recognizable "
                        + "FROM cert_material WHERE subject_id = (SELECT id FROM subject WHERE subject_no = ?)",
                subjectNo);
        assertThat(materialRow.get("material_type")).isEqualTo("GOV_CA_CERT");
        assertThat(materialRow.get("file_name")).isEqualTo("A3.cer");
        assertThat(new String((byte[]) materialRow.get("content_cipher"), StandardCharsets.ISO_8859_1))
                .doesNotContain("fake-gov-ca-cert-a3");
        assertThat((String) materialRow.get("content_sm3")).isEqualTo(sm3Service.digestHex(cert));
        assertThat(new String((byte[]) materialRow.get("ocr_raw_cipher"), StandardCharsets.ISO_8859_1))
                .doesNotContain("市大数据管理局");
        assertThat((int) materialRow.get("ocr_recognizable")).isEqualTo(1);
    }

    @Test
    void govCaExpiredCertRejectedStaysPendingCertAndResubmissionPasses() throws Exception {
        final String subjectNo = registerGovSubject("91330100MA27XW1302", "过期证书演示局");

        submitGovCert(subjectNo, "expired-cert".getBytes(StandardCharsets.US_ASCII), "A4.cer", APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("FAIL"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CERT"))
                .andExpect(jsonPath("$.data.failReason").value("证书已过期（模拟渠道预置 A4）"));

        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_CERT");

        // 可重新提交换证：替换 A3 后通过自动流转（规格行为 6 第 2 条）
        submitGovCert(subjectNo, "valid-cert".getBytes(StandardCharsets.US_ASCII), "A3.cer", APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("PASS"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        final Integer materialCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_material WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND material_type = 'GOV_CA_CERT' AND file_name = 'A3.cer'",
                Integer.class, subjectNo);
        assertThat(materialCount).isEqualTo(1);
    }

    @Test
    void govCaSubmissionBlockedOnceAlreadyInReviewState() throws Exception {
        // 评审视角 4 必修①：已流转待审核后再提交 → 状态门槛 1004C0002，库内状态不被扰动
        final String subjectNo = registerGovSubject("91330100MA27XW1309", "门槛演示局");
        submitGovCert(subjectNo, "valid-cert".getBytes(StandardCharsets.US_ASCII), "A3.cer", APPLICANT)
                .andExpect(status().isOk());
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");

        submitGovCert(subjectNo, "another-cert".getBytes(StandardCharsets.US_ASCII), "A3.cer", APPLICANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        final Integer govLogCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_verification_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) AND verify_type = 'GOV_CA'",
                Integer.class, subjectNo);
        assertThat(govLogCount).isEqualTo(1);
    }

    @Test
    void concurrentGovSubmissionExactlyOneWins() throws Exception {
        // WBS-3.1.6 T5b（3.1.4 hifi 边界表"政务重复提交并发"——原承诺"3.1.5 前补"的顺延项）：
        // 双线程并发提交 A3 有效证书 → 恰一次 200，PENDING_CERT→PENDING_REVIEW 流转留痕恰 1 条
        // （败者概率性锚定口径同 ReviewIntegrationTest#concurrentReviewExactlyOneWinner）。
        // 实测注记：败者可能拿门槛 400，也可能在 cert_material 行锁竞争下拿死锁 500（未转译——
        // 登记观察项，随 2.5.x/真实渠道包评估重试策略），本用例只锚定"恰一次成功 + 流转恰一条"
        final String subjectNo = registerGovSubject("91330100MA27XW1306", "政务并发演示局");
        final byte[] cert = "valid-cert".getBytes(StandardCharsets.US_ASCII);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<Integer> first = pool.submit(
                    () -> fireGovSubmit(subjectNo, cert, ready, start));
            final Future<Integer> second = pool.submit(
                    () -> fireGovSubmit(subjectNo, cert, ready, start));
            start.countDown();
            final long okCount = Stream.of(first.get(), second.get()).filter(s -> s == 200).count();
            assertThat(okCount).as("并发政务提交恰一次成功").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND from_status = 'PENDING_CERT' AND to_status = 'PENDING_REVIEW'",
                Integer.class, subjectNo)).isEqualTo(1);
    }

    @Test
    void oversizedGovCertRejectedAtHttpLayerWithoutMaterial() throws Exception {
        // WBS-3.1.6 T6（lofi Q4-A 定参 ≤2MB 的 HTTP 封套 + 材料零落库，与执照族同口径）
        final String subjectNo = registerGovSubject("91330100MA27XW1307", "超限证书演示局");
        submitGovCert(subjectNo, new byte[2_097_153], "oversize.cer", APPLICANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("政务 CA 证书文件大小超出上限（≤2MB）"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM cert_material WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?)",
                Integer.class, subjectNo)).isZero();
    }

    @Test
    void channelMutualExclusionBlocksBothDirections() throws Exception {
        final String govSubjectNo = registerGovSubject("91330100MA27XW1303", "通道互斥演示局");
        final String enterpriseSubjectNo = registerEnterpriseSubject("91330100MA27X8AB0B", "互斥对照演示公司");

        // 政务主体调企业认证端点（上传执照）→ 400 + 1004B0007（全程不出现 OCR 步骤的强制口径）
        mockMvc.perform(multipart(BASE + "/" + govSubjectNo + "/certification/license")
                        .file(new MockMultipartFile("file", "A1.jpg", MediaType.IMAGE_JPEG_VALUE,
                                "image".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0007"))
                .andExpect(jsonPath("$.message").value("主体类型与认证流程不匹配，请使用对应主体的认证方式"));
        // 企业主体调政务端点 → 同码拒绝
        submitGovCert(enterpriseSubjectNo, "cert".getBytes(StandardCharsets.US_ASCII), "A3.cer", APPLICANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0007"));

        final JsonNode denied = awaitEvent("certification.gov.submit", "DENIED", APPLICANT);
        assertThat(denied.get("actor").asText()).isEqualTo(APPLICANT);
        // 双向拒绝留痕（评审视角 4 建议）：政务→企业方向的 upload DENIED 同样落审计
        final JsonNode uploadDenied = awaitEvent("certification.upload", "DENIED", APPLICANT);
        assertThat(uploadDenied.get("actor").asText()).isEqualTo(APPLICANT);

        // 企业主体档案回归：govCa 恒为 null、当日剩余次数正常返回（hifi B8，评审视角 1 建议补齐）
        final MvcResult enterpriseProfile = mockMvc.perform(
                        get(BASE + "/" + enterpriseSubjectNo + "/certification")
                                .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode enterpriseData = MAPPER.readTree(enterpriseProfile.getResponse().getContentAsString())
                .get("data");
        assertThat(enterpriseData.get("govCa").isNull()).isTrue();
        assertThat(enterpriseData.get("remainingAttemptsToday").isNull()).isFalse();
    }

    @Test
    void govCaOwnershipGuardBlocksOtherApplicantAndExemptsReviewer() throws Exception {
        final String subjectNo = registerGovSubject("91330100MA27XW1304", "归属断言演示局");
        submitGovCert(subjectNo, "valid-cert".getBytes(StandardCharsets.US_ASCII), "A3.cer", APPLICANT)
                .andExpect(status().isOk());

        // 他人提交政务证书 → 与"申请编号不存在"出站同形（400 + 1000C0003，防存在性探测）
        submitGovCert(subjectNo, "cert".getBytes(StandardCharsets.US_ASCII), "A3.cer", OTHER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"));
        final JsonNode denied = awaitEvent("certification.gov.submit", "DENIED", OTHER);
        assertThat(denied.get("actor").asText()).isEqualTo(OTHER);

        // 审核员豁免可查档案：政务段可见 + 无"当日剩余次数"（政务无失败次数概念，hifi B8）
        mockMvc.perform(get(BASE + "/" + subjectNo + "/certification")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.govCa.uploaded").value(true))
                .andExpect(jsonPath("$.data.govCa.fileName").value("A3.cer"))
                .andExpect(jsonPath("$.data.govCa.lastConclusion").value("PASS"));
    }

    @Test
    void govProfileExposesGovCaSectionAndOmitsDailyAttempts() throws Exception {
        final String subjectNo = registerGovSubject("91330100MA27XW1305", "档案政务段演示局");
        submitGovCert(subjectNo, "expired".getBytes(StandardCharsets.US_ASCII), "A4.cer", APPLICANT)
                .andExpect(status().isOk());

        final MvcResult result = mockMvc.perform(get(BASE + "/" + subjectNo + "/certification")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.govCa.uploaded").value(true))
                .andExpect(jsonPath("$.data.govCa.lastConclusion").value("FAIL"))
                .andExpect(jsonPath("$.data.govCa.lastFailReason").value("证书已过期（模拟渠道预置 A4）"))
                .andExpect(jsonPath("$.data.license.uploaded").value(false))
                .andReturn();
        final JsonNode data = MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
        // remainingAttemptsToday 对政务主体返回 null（口径：字段在但值为 null）
        assertThat(data.has("remainingAttemptsToday")).isTrue();
        assertThat(data.get("remainingAttemptsToday").isNull()).isTrue();
    }

    // ==== 辅助 ====

    private int fireGovSubmit(final String subjectNo, final byte[] cert, final CountDownLatch ready,
            final CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mockMvc.perform(multipart(BASE + "/" + subjectNo + "/certification/gov-ca-certificate")
                        .file(new MockMultipartFile("file", "A3.cer",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE, cert))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andReturn().getResponse().getStatus();
    }

    private String subjectStatus(final String subjectNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM subject WHERE subject_no = ?", String.class, subjectNo);
    }

    private String registerGovSubject(final String uscc, final String subjectName) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
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

    private String registerEnterpriseSubject(final String uscc, final String subjectName) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"" + subjectName + "\",\"uscc\":\"" + uscc + "\","
                                + "\"subjectType\":\"ENTERPRISE\",\"regAddress\":\"杭州市XX区XX路88号\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13800001234\","
                                + "\"adminAccount\":\"admin001\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data")
                .get("subjectNo").asText();
    }

    private ResultActions submitGovCert(final String subjectNo, final byte[] cert, final String fileName,
            final String operator) throws Exception {
        return mockMvc.perform(multipart(BASE + "/" + subjectNo + "/certification/gov-ca-certificate")
                .file(new MockMultipartFile("file", fileName,
                        MediaType.APPLICATION_OCTET_STREAM_VALUE, cert))
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "applicant"));
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
