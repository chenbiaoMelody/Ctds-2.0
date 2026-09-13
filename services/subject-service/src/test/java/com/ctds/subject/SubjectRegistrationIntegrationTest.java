package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.common.errorcode.BizException;
import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 主体注册全链路集成测试（规格 C-1.1 行为 1/4 验收标准 + hifi B2~B9，ADR-010 容器化基座）：
 * 注册/校验/唯一性/幂等并发/撤销重报/进度查询/权限三态/审计留痕/唯一索引兜底。
 * 需要业务重新执行的用例（重报/重复注册拒绝）经仓储直接造数，绕开幂等结果缓存
 * （幂等键 = 信用代码，TTL 窗口内同键请求返回首次结果——设计口径，见交付说明观察项）。
 * 本机 Docker 未运行时 disabledWithoutDocker 自动跳过（门禁不红，跳过态留痕 mvn 输出）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SubjectRegistrationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGISTER_URL = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static Path auditDir;

    /** 显式镜像标签 mysql:8.0（ADR-010 禁止 latest），类级共享容器。 */
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ctds_subject");

    @BeforeAll
    static void createAuditDir() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-subject");
    }

    @AfterAll
    static void deleteAuditDir() throws Exception {
        try (Stream<Path> paths = Files.walk(auditDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @DynamicPropertySource
    static void auditProperties(final DynamicPropertyRegistry registry) {
        registry.add("ctds.audit.file-dir", () -> auditDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SubjectRepository subjectRepository;

    @Test
    void registerCreatesPendingSubjectAndReturnsApplicationNo() throws Exception {
        final JsonNode data = submitRegister(uscc(1), "示例数据科技有限公司");

        assertThat(data.get("subjectNo").asText()).matches("S\\d{14}");
        assertThat(data.get("status").asText()).isEqualTo("PENDING_CERT");
        assertThat(countSubjects(uscc(1))).isEqualTo(1);
    }

    @Test
    void missingFieldsReportPerFieldReasonsAndCreateNothing() throws Exception {
        final int before = countAll();
        mockMvc.perform(post(REGISTER_URL)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0001"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("主体名称不能为空")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("统一社会信用代码不能为空")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("联系电话不能为空")));
        assertThat(countAll()).isEqualTo(before);
    }

    @Test
    void duplicateActiveSubjectRejectedWithFixedMessage() throws Exception {
        createSubjectDirect(uscc(2), "重复注册演示公司", SubjectStatus.PENDING_CERT, "S20260913000201");

        mockMvc.perform(post(REGISTER_URL)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uscc(2), "重复注册演示公司")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0001"))
                .andExpect(jsonPath("$.message").value("该主体已注册"));
        assertThat(countSubjects(uscc(2))).isEqualTo(1);
    }

    @Test
    void sameRequestTwiceReturnsFirstResultWithoutDuplication() throws Exception {
        final JsonNode first = submitRegister(uscc(3), "幂等演示公司");
        final JsonNode second = submitRegister(uscc(3), "幂等演示公司");

        assertThat(second).isEqualTo(first);
        assertThat(countSubjects(uscc(3))).isEqualTo(1);
    }

    @Test
    void concurrentSameUsccCreatesExactlyOneSubject() throws Exception {
        final int threads = 20;
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger clientErrors = new AtomicInteger();
        final AtomicInteger serverErrors = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    final int status = mockMvc.perform(post(REGISTER_URL)
                                    .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(registerBody(uscc(4), "并发注册演示公司")))
                            .andReturn().getResponse().getStatus();
                    if (status >= 500) {
                        serverErrors.incrementAndGet();
                    } else if (status >= 400) {
                        clientErrors.incrementAndGet();
                    } else {
                        successes.incrementAndGet();
                    }
                } catch (Exception e) {
                    serverErrors.incrementAndGet();
                }
            });
        }
        if (!ready.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("workers did not become ready");
        }
        go.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new AssertionError("workers did not finish");
        }
        pool.shutdownNow();
        assertThat(serverErrors.get()).isZero();
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        assertThat(clientErrors.get()).isLessThanOrEqualTo(threads - 1);
        assertThat(countSubjects(uscc(4))).isEqualTo(1);
    }

    @Test
    void cancelPendingSubjectThenResubmitReusesSameApplicationNo() throws Exception {
        createSubjectDirect(uscc(5), "撤销重报演示公司", SubjectStatus.PENDING_CERT, "S20260913000501");
        final String subjectNo = "S20260913000501";

        mockMvc.perform(post(REGISTER_URL + "/" + subjectNo + "/cancellation")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.cancelled").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_CERT"));

        // 撤销后重复撤销被拒
        mockMvc.perform(post(REGISTER_URL + "/" + subjectNo + "/cancellation")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004C0001"))
                .andExpect(jsonPath("$.message").value("当前状态不可撤销"));

        // 撤销后重新注册 = 同一申请编号重报，主体名称更新
        final JsonNode resubmitted = submitRegister(uscc(5), "撤销后修改的名称");
        assertThat(resubmitted.get("subjectNo").asText()).isEqualTo(subjectNo);

        final JsonNode detail = fetchDetail(subjectNo);
        assertThat(detail.get("subjectName").asText()).isEqualTo("撤销后修改的名称");
        final JsonNode logs = detail.get("statusLogs");
        assertThat(logs.size()).isEqualTo(3);
        assertThat(logs.get(0).get("fromStatus").asText()).isEqualTo("NONE");
        assertThat(logs.get(0).get("toStatus").asText()).isEqualTo("PENDING_CERT");
        assertThat(logs.get(0).get("triggerRole").asText()).isEqualTo("APPLICANT");
        assertThat(logs.get(1).get("remark").asText()).contains("申请人撤销");
        assertThat(logs.get(2).get("remark").asText()).isEqualTo("撤销后重新提交");
    }

    @Test
    void rejectedSubjectResubmitsWithUpdatedInfo() throws Exception {
        createSubjectDirect(uscc(6), "驳回重报演示公司", SubjectStatus.REJECTED, "S20260913000601");

        final JsonNode resubmitted = submitRegister(uscc(6), "驳回后修改的名称");

        assertThat(countSubjects(uscc(6))).isEqualTo(1);
        assertThat(resubmitted.get("status").asText()).isEqualTo("PENDING_CERT");
        assertThat(resubmitted.get("subjectNo").asText()).isEqualTo("S20260913000601");
        final JsonNode detail = fetchDetail("S20260913000601");
        assertThat(detail.get("subjectName").asText()).isEqualTo("驳回后修改的名称");
        assertThat(detail.get("statusLogs").get(1).get("remark").asText()).isEqualTo("驳回后重新申请");
    }

    @Test
    void detailMasksContactPhoneAndShowsTransitionLogs() throws Exception {
        final JsonNode registered = submitRegister(uscc(7), "脱敏演示公司");
        final JsonNode detail = fetchDetail(registered.get("subjectNo").asText());

        assertThat(detail.get("contactPhone").asText()).isEqualTo("138****1234");
        assertThat(detail.get("subjectName").asText()).isEqualTo("脱敏演示公司");
        assertThat(detail.get("statusLogs").size()).isEqualTo(1);
    }

    @Test
    void unknownSubjectNoIsResourceNotFound() throws Exception {
        mockMvc.perform(get(REGISTER_URL + "/S20260913000099")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1000C0003"))
                .andExpect(jsonPath("$.message").value("申请编号不存在"));
    }

    @Test
    void unauthenticatedRequestIsUnauthorized() throws Exception {
        mockMvc.perform(post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uscc(8), "未认证演示公司")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("1000C0002"));
    }

    @Test
    void unauthorizedRoleIsForbiddenAndDeniedAudited() throws Exception {
        mockMvc.perform(post(REGISTER_URL)
                        .header("X-Ctds-Subject", "other-01").header("X-Ctds-Roles", "reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uscc(9), "越权演示公司")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("1000C0005"))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));
        assertThat(countSubjects(uscc(9))).isEqualTo(0);

        final JsonNode denied = awaitEvent("rbac.check");
        assertThat(denied.get("outcome").asText()).isEqualTo("DENIED");
    }

    @Test
    void registerSuccessIsAudited() throws Exception {
        submitRegister(uscc(10), "审计演示公司");
        final JsonNode event = awaitEvent("subject.register");
        assertThat(event.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(event.get("actor").asText()).isEqualTo(APPLICANT);
    }

    @Test
    void usccUniqueIndexGuardsConcurrentWindow() throws Exception {
        submitRegister(uscc(11), "唯一索引兜底演示公司");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO subject (subject_no, subject_name, uscc, subject_type, reg_address, "
                                + "contact_name, contact_phone, admin_account, status, created_at, updated_at) "
                                + "VALUES ('S20260913999999', '重复行', ?, 'ENTERPRISE', '地址', '联系人', '13800001234', "
                                + "'admin', 'PENDING_CERT', NOW(), NOW())", uscc(11)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateKeyInRepositoryTranslatedToBusinessErrorNotDbDetails() {
        createSubjectDirect(uscc(12), "兜底转换演示公司", SubjectStatus.PENDING_CERT, "S20260913001201");

        final LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> subjectRepository.create(
                        new Subject(null, "S20260913001202", "兜底转换演示公司", uscc(12), SubjectType.ENTERPRISE,
                                "杭州市XX区XX路88号", "张三", "13800001234", "admin001",
                                SubjectStatus.PENDING_CERT, now, now),
                        new StatusTransition(null, SubjectStatus.PENDING_CERT, TriggerRole.APPLICANT,
                                APPLICANT, null, now)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(SubjectErrorCodes.SUBJECT_ALREADY_REGISTERED))
                .hasMessage("该主体已注册");
    }

    @Test
    void concurrentDistinctUsccRegistrationsGetDistinctSubjectNos() throws Exception {
        final int threads = 20;
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = 21 + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                try {
                    return submitRegister(uscc(index), "并发取号演示公司" + index).get("subjectNo").asText();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        if (!ready.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("workers did not become ready");
        }
        go.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new AssertionError("workers did not finish");
        }
        pool.shutdownNow();

        final List<String> subjectNos = new java.util.ArrayList<>();
        for (final java.util.concurrent.Future<String> future : futures) {
            subjectNos.add(future.get());
        }
        assertThat(subjectNos).doesNotHaveDuplicates();
        assertThat(subjectNos).allMatch(no -> no.matches("S\\d{14}"));
        for (int i = 0; i < threads; i++) {
            assertThat(countSubjects(uscc(21 + i))).isEqualTo(1);
        }
    }

    /** 幂等窗口内同信用代码、不同内容的注册返回首次结果（ADR-016 §2.5 边界口径的固化锚点，防回归漂移）。 */
    @Test
    void idempotencyWindowReturnsFirstResultEvenWhenPayloadDiffers() throws Exception {
        final JsonNode first = submitRegister(uscc(13), "幂等窗口公司A");
        final JsonNode second = submitRegister(uscc(13), "幂等窗口公司B");

        assertThat(second).isEqualTo(first);
        assertThat(countSubjects(uscc(13))).isEqualTo(1);
        final JsonNode detail = fetchDetail(first.get("subjectNo").asText());
        assertThat(detail.get("subjectName").asText()).isEqualTo("幂等窗口公司A");
    }

    /** 当日序号首次取号 = 1（LAST_INSERT_ID 新插入路径返回 0 的边界，实测引入回归后修复），再取递增。 */
    @Test
    void dailySeqFirstTakeReturnsOneAndIncrements() {
        final LocalDate futureDate = LocalDate.now().plusYears(1);

        assertThat(subjectRepository.nextDailySeq(futureDate)).isEqualTo(1);
        assertThat(subjectRepository.nextDailySeq(futureDate)).isEqualTo(2);
    }

    /** 仓储直造主体（含初始留痕），绕开幂等结果缓存——供重报/重复注册拒绝类用例使用。 */
    private void createSubjectDirect(final String uscc, final String subjectName, final SubjectStatus status,
            final String subjectNo) {
        final LocalDateTime now = LocalDateTime.now();
        subjectRepository.create(
                new Subject(null, subjectNo, subjectName, uscc, SubjectType.ENTERPRISE, "杭州市XX区XX路88号",
                        "张三", "13800001234", "admin001", status, now, now),
                new StatusTransition(null, status, TriggerRole.APPLICANT, APPLICANT, null, now));
    }

    private JsonNode submitRegister(final String uscc, final String subjectName) throws Exception {
        final MvcResult result = mockMvc.perform(post(REGISTER_URL)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uscc, subjectName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode fetchDetail(final String subjectNo) throws Exception {
        final MvcResult result = mockMvc.perform(get(REGISTER_URL + "/" + subjectNo)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private String registerBody(final String uscc, final String subjectName) {
        return "{\"subjectName\":\"" + subjectName + "\",\"uscc\":\"" + uscc + "\",\"subjectType\":\"ENTERPRISE\","
                + "\"regAddress\":\"杭州市XX区XX路88号\",\"contactName\":\"张三\",\"contactPhone\":\"13800001234\","
                + "\"adminAccount\":\"admin001\"}";
    }

    /** 合法 18 位统一社会信用代码（2+6+10，按测试序号区分），如 91330100MA27X8AB01。 */
    private static String uscc(final int index) {
        return "91330100" + "MA27X8" + String.format("AB%02d", index);
    }

    private int countSubjects(final String uscc) {
        final List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT COUNT(1) FROM subject WHERE uscc = ?", Integer.class, uscc);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    private int countAll() {
        final List<Integer> counts = jdbcTemplate.queryForList("SELECT COUNT(1) FROM subject", Integer.class);
        return counts.isEmpty() ? 0 : counts.get(0);
    }

    private JsonNode awaitEvent(final String action) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (Files.exists(auditDir)) {
                try (Stream<Path> files = Files.list(auditDir)) {
                    final List<Path> auditFiles = files.filter(p -> p.getFileName().toString().startsWith("audit-"))
                            .toList();
                    for (final Path file : auditFiles) {
                        for (final String line : Files.readAllLines(file)) {
                            final JsonNode node = MAPPER.readTree(line);
                            if (action.equals(node.get("action").asText())) {
                                return node;
                            }
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("2 秒内审计未出现 action=" + action);
    }
}
