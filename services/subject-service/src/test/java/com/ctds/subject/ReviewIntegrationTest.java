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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 平台人工审核全链路集成测试（规格 C-1.1 行为 5 验收标准 + WBS-3.1.5 hifi B1~B8）：
 * 待审核清单过滤与分页 / 通过流转已入驻（持久化状态断言——3.1.3 教训固化）/ 驳回理由必填与留痕 /
 * 状态门槛四态拒绝 + 并发双审核单胜出（TOCTOU 观察项）/ 未授权 403 + 拒绝审计 / 文件名控制字符归一化。
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ReviewIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject";
    private static final String APPLICANT = "applicant-01";
    private static final String REVIEWER = "reviewer-01";
    private static final String NO_REVIEW_ROLE = "nobody-01";
    private static Path auditDir;
    private static Path keyFile;

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_subject");

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-review");
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
    void reviewerApprovesPendingSubjectToAdmittedWithFullTrail() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1401", "审核通过演示局");

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/approval")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectNo").value(subjectNo))
                .andExpect(jsonPath("$.data.status").value("ADMITTED"));

        // 持久化状态断言（3.1.3 教训）：审核流转必须落 subject.status 列 + 审核员触发留痕四要素
        assertThat(subjectStatus(subjectNo)).isEqualTo("ADMITTED");
        final Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND from_status = 'PENDING_REVIEW' AND to_status = 'ADMITTED' "
                        + "AND trigger_role = 'REVIEWER' AND operator = ? AND remark = '审核通过'",
                Integer.class, subjectNo, REVIEWER);
        assertThat(transitions).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_at FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND to_status = 'ADMITTED' AND trigger_role = 'REVIEWER'",
                java.sql.Timestamp.class, subjectNo)).isNotNull();
        awaitEvent("certification.review.approve", "SUCCESS", REVIEWER, subjectNo);
    }

    @Test
    void rejectRequiresReasonAndLeavesStateUnchanged() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1402", "驳回必填演示局");

        // 理由为空 / 全空白 → 400 拒绝，库内状态不被扰动（直查库断言）
        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void rejectWithReasonTransitionsToRejectedWithRemark() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1403", "驳回流转演示局");

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"证照材料不齐全，请补正后重新申请\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(subjectStatus(subjectNo)).isEqualTo("REJECTED");
        final Integer transitions = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) "
                        + "AND to_status = 'REJECTED' AND trigger_role = 'REVIEWER' "
                        + "AND remark = '审核驳回：证照材料不齐全，请补正后重新申请'",
                Integer.class, subjectNo);
        assertThat(transitions).isEqualTo(1);
        awaitEvent("certification.review.reject", "SUCCESS", REVIEWER, subjectNo);
    }

    @Test
    void stateGateBlocksReviewOnNonPendingReviewStatuses() throws Exception {
        // 待认证（注册后未提交证书）→ 1004C0002 + DENIED 审计（hifi E3）
        final String pendingCert = registerGovSubject("91330100MA27XW1404", "门槛待认证演示局");
        assertThat(subjectStatus(pendingCert)).isEqualTo("PENDING_CERT");
        reject(pendingCert).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));
        assertThat(subjectStatus(pendingCert)).isEqualTo("PENDING_CERT");
        awaitEvent("certification.review.reject", "DENIED", REVIEWER, pendingCert);

        // 已入驻：通过后再通过 / 再驳回均被门槛拒绝（各落 DENIED 审计）
        final String admitted = createPendingGovSubject("91330100MA27XW1405", "门槛已入驻演示局");
        approve(admitted).andExpect(status().isOk());
        approve(admitted).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));
        reject(admitted).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));
        assertThat(subjectStatus(admitted)).isEqualTo("ADMITTED");
        awaitEvent("certification.review.approve", "DENIED", REVIEWER, admitted);

        // 已驳回（B5 第四态）：驳回后再调任一审核端点均被门槛拒绝
        final String rejected = createPendingGovSubject("91330100MA27XW1414", "门槛已驳回演示局");
        reject(rejected).andExpect(status().isOk());
        assertThat(subjectStatus(rejected)).isEqualTo("REJECTED");
        approve(rejected).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));
        reject(rejected).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));

        // 认证失败（结束认证后）→ 1004C0002
        final String certFailed = registerEnterpriseSubject("91330100MA27X8AB0C", "门槛认证失败演示公司");
        uploadLicense(certFailed, "A1.jpg");
        mockMvc.perform(post(BASE + "/registrations/" + certFailed + "/certification/abandonment")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk());
        assertThat(subjectStatus(certFailed)).isEqualTo("CERT_FAILED");
        approve(certFailed).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0002"));
    }

    @Test
    void concurrentReviewExactlyOneWinner() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1406", "并发审核演示局");

        // 双审核员并发（一通过一驳回）：乐观状态门槛保证恰一人成功（TOCTOU 观察项固化）。
        // 注：门槛锚定为概率性而非确定性——若败者的前置读取发生在胜者提交之后，由 requirePendingReview
        // 前置检查兜拒；两读先于两写时（latch 握手下高概率）由仓储 WHERE 门槛兜拒，本用例即红。
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<Integer> approveResult = pool.submit(() -> fireReview(
                    post(BASE + "/registrations/" + subjectNo + "/review/approval"), ready, start));
            final Future<Integer> rejectResult = pool.submit(() -> fireReview(
                    post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"并发驳回\"}"),
                    ready, start));
            start.countDown();
            final int approveStatus = approveResult.get();
            final int rejectStatus = rejectResult.get();

            final long okCount = List.of(approveStatus, rejectStatus).stream().filter(s -> s == 200).count();
            assertThat(okCount).as("并发双审核恰一人成功").isEqualTo(1);
            final String finalStatus = subjectStatus(subjectNo);
            assertThat(finalStatus).isIn("ADMITTED", "REJECTED");
            // 胜者与终态一致：通过胜 → 终态 ADMITTED；驳回胜 → 终态 REJECTED
            if (approveStatus == 200) {
                assertThat(finalStatus).isEqualTo("ADMITTED");
            } else {
                assertThat(finalStatus).isEqualTo("REJECTED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void queueListsOnlyPendingReviewWithPaging() throws Exception {
        // 待认证主体（注册后未提交证书）不得出现在审核清单
        final String pendingCert = registerGovSubject("91330100MA27XW1407", "清单过滤演示局");
        final String p1 = createPendingGovSubject("91330100MA27XW1408", "清单演示局一");
        final String p2 = createPendingGovSubject("91330100MA27XW1409", "清单演示局二");
        final String p3 = createPendingGovSubject("91330100MA27XW1410", "清单演示局三");

        final MvcResult page1 = mockMvc.perform(get(BASE + "/review/queue")
                        .queryParam("pageNum", "1").queryParam("pageSize", "2")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk()).andReturn();
        final JsonNode data1 = MAPPER.readTree(page1.getResponse().getContentAsString()).get("data");
        assertThat(data1.get("total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(data1.get("list").size()).isEqualTo(2);
        assertThat(data1.get("pageSize").asInt()).isEqualTo(2);

        final MvcResult page3 = mockMvc.perform(get(BASE + "/review/queue")
                        .queryParam("pageNum", "3").queryParam("pageSize", "2")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk()).andReturn();
        final JsonNode data3 = MAPPER.readTree(page3.getResponse().getContentAsString()).get("data");
        // 三页翻尽后过滤断言：全部条目为待审核，待认证主体（未提交证书）不出现
        final MvcResult all = mockMvc.perform(get(BASE + "/review/queue")
                        .queryParam("pageNum", "1").queryParam("pageSize", "100")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk()).andReturn();
        final JsonNode allData = MAPPER.readTree(all.getResponse().getContentAsString()).get("data");
        final List<String> nos = new java.util.ArrayList<>();
        allData.get("list").forEach(item -> {
            assertThat(item.get("subjectType").isTextual()).isTrue();
            nos.add(item.get("subjectNo").asText());
        });
        assertThat(nos).contains(p1, p2, p3).doesNotContain(pendingCert);

        // E6：非法分页参数 → 400 参数错误
        mockMvc.perform(get(BASE + "/review/queue").queryParam("pageNum", "0")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectOverlongReasonRejectedAtHttpLayer() throws Exception {
        // WBS-3.1.6 T2（hifi E2 上包登记欠账）：201 字符理由（DTO 512 传输兜底之内、业务 200 上限之外）
        // → 服务端参数封套 400 + 1000C0001 + 配置上限文案；库内状态不被扰动、无 REJECTED 流转留痕
        final String subjectNo = createPendingGovSubject("91330100MA27XW1415", "理由超长演示局");
        final String overlong = "驳".repeat(201);

        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + overlong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value("驳回理由长度不能超过200字"));

        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM subject_status_log WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) AND to_status = 'REJECTED'",
                Integer.class, subjectNo)).isZero();
    }

    @Test
    void queueOrderedByApplicationTimeAsc() throws Exception {
        // WBS-3.1.6 T3（3.1.5 hifi 接口契约"按申请时间升序"有约无断言）：直改 created_at 打乱物理序
        // （id 序 = 一/二/三，时间序 = 二/三/一），断言清单严格按申请时间升序且分页跨界保序
        final String p1 = createPendingGovSubject("91330100MA27XW1416", "排序演示局一");
        final String p2 = createPendingGovSubject("91330100MA27XW1417", "排序演示局二");
        final String p3 = createPendingGovSubject("91330100MA27XW1418", "排序演示局三");
        backdateCreated(p1, "2026-09-03 08:00:00");
        backdateCreated(p2, "2026-09-01 08:00:00");
        backdateCreated(p3, "2026-09-02 08:00:00");

        // 同库其他待审核条目的申请时间为用例执行当日（晚于上面三天）→ 升序口径下本用例三条必须占前
        assertThat(queueList("1", "100")).startsWith(p2, p3, p1);
        // 分页跨界保序：第 1 页恰为前两条，第 2 页首条为第三条
        assertThat(queueList("1", "2")).containsExactly(p2, p3);
        assertThat(queueList("2", "2").get(0)).isEqualTo(p1);
    }

    @Test
    void unauthorizedRolesAreRejectedWithDeniedAudit() throws Exception {
        final String subjectNo = createPendingGovSubject("91330100MA27XW1411", "越权演示局");

        // 无 subject.review 权限角色：清单 / 通过 / 驳回均 403（功能级拦截在前），拒绝审计留痕
        mockMvc.perform(get(BASE + "/review/queue")
                        .header("X-Ctds-Subject", NO_REVIEW_ROLE).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/approval")
                        .header("X-Ctds-Subject", NO_REVIEW_ROLE).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                        .header("X-Ctds-Subject", NO_REVIEW_ROLE).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"越权\"}"))
                .andExpect(status().isForbidden());
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
        awaitEvent("rbac.check", "DENIED", NO_REVIEW_ROLE, null);
    }

    @Test
    void uploadFileNameControlCharactersAreNormalized() throws Exception {
        // B8①（3.1.4 观察项）：执照上传文件名含控制字符 → 归一化落库与回显
        final String enterprise = registerEnterpriseSubject("91330100MA27X8AB0D", "归一化演示公司");
        mockMvc.perform(multipart(BASE + "/registrations/" + enterprise + "/certification/license")
                        .file(new MockMultipartFile("file", "A1\u0001\u0007.jpg",
                                MediaType.IMAGE_JPEG_VALUE, "image".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("A1.jpg"));
        assertThat(materialFileName(enterprise)).isEqualTo("A1.jpg");

        // 政务证书上传同理：归一化后 A3 规则照常命中（模拟渠道按归一化文件名匹配）
        final String govPending = registerGovSubject("91330100MA27XW1413", "政务归一化演示局");
        mockMvc.perform(multipart(BASE + "/registrations/" + govPending + "/certification/gov-ca-certificate")
                        .file(new MockMultipartFile("file", "A3\u000B.cer",
                                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                "cert".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("PASS"));
        assertThat(materialFileName(govPending)).isEqualTo("A3.cer");
    }

    // ==== 辅助 ====

    private void backdateCreated(final String subjectNo, final String timestamp) {
        jdbcTemplate.update("UPDATE subject SET created_at = ? WHERE subject_no = ?",
                java.sql.Timestamp.valueOf(timestamp), subjectNo);
    }

    private List<String> queueList(final String pageNum, final String pageSize) throws Exception {
        final MvcResult result = mockMvc.perform(get(BASE + "/review/queue")
                        .queryParam("pageNum", pageNum).queryParam("pageSize", pageSize)
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andExpect(status().isOk()).andReturn();
        final List<String> nos = new java.util.ArrayList<>();
        MAPPER.readTree(result.getResponse().getContentAsString()).get("data").get("list")
                .forEach(item -> nos.add(item.get("subjectNo").asText()));
        return nos;
    }

    private int fireReview(final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            final CountDownLatch ready, final CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return mockMvc.perform(builder
                        .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"))
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions approve(final String subjectNo) throws Exception {
        return mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/approval")
                .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer"));
    }

    private org.springframework.test.web.servlet.ResultActions reject(final String subjectNo) throws Exception {
        return mockMvc.perform(post(BASE + "/registrations/" + subjectNo + "/review/rejection")
                .header("X-Ctds-Subject", REVIEWER).header("X-Ctds-Roles", "reviewer")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"门槛驳回\"}"));
    }

    private String subjectStatus(final String subjectNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM subject WHERE subject_no = ?", String.class, subjectNo);
    }

    private String materialFileName(final String subjectNo) {
        return jdbcTemplate.queryForObject(
                "SELECT file_name FROM cert_material WHERE subject_id = "
                        + "(SELECT id FROM subject WHERE subject_no = ?) ORDER BY id DESC LIMIT 1",
                String.class, subjectNo);
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
        return registerSubject(uscc, subjectName, "GOV", "王科", "13800005678", "govadmin");
    }

    private String registerEnterpriseSubject(final String uscc, final String subjectName) throws Exception {
        return registerSubject(uscc, subjectName, "ENTERPRISE", "张三", "13800001234", "admin001");
    }

    private String registerSubject(final String uscc, final String subjectName, final String subjectType,
            final String contactName, final String contactPhone, final String adminAccount) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE + "/registrations")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"" + subjectName + "\",\"uscc\":\"" + uscc + "\","
                                + "\"subjectType\":\"" + subjectType + "\",\"regAddress\":\"杭州市XX区XX路88号\","
                                + "\"contactName\":\"" + contactName + "\",\"contactPhone\":\"" + contactPhone
                                + "\",\"adminAccount\":\"" + adminAccount + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data")
                .get("subjectNo").asText();
    }

    private void uploadLicense(final String subjectNo, final String fileName) throws Exception {
        mockMvc.perform(multipart(BASE + "/registrations/" + subjectNo + "/certification/license")
                        .file(new MockMultipartFile("file", fileName, MediaType.IMAGE_JPEG_VALUE,
                                "image".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk());
    }

    private JsonNode awaitEvent(final String action, final String outcome, final String actor,
            final String targetId) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (Files.exists(auditDir)) {
                try (Stream<Path> files = Files.list(auditDir)) {
                    final List<Path> auditFiles = files.filter(p -> p.getFileName().toString().startsWith("audit-"))
                            .toList();
                    for (final Path file : auditFiles) {
                        for (final String line : Files.readAllLines(file)) {
                            if (line.contains("\"" + action + "\"")) {
                                final JsonNode event = MAPPER.readTree(line);
                                // 按 targetId（申请编号）过滤，防同目录前序用例同类事件"顶替"通过
                                if (outcome.equals(event.path("outcome").asText())
                                        && actor.equals(event.path("actor").asText())
                                        && (targetId == null
                                                || targetId.equals(event.path("targetId").asText()))) {
                                    return event;
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("audit event not found: " + action + " / " + outcome + " / " + actor
                + " / " + targetId);
    }
}
