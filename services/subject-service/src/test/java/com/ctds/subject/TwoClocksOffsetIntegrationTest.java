package com.ctds.subject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ctds.subject.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DB-23（清债卡2）：时区偏移窗口回归固化——"两把钟"测试面的自动化锚。
 *
 * <p>债务机理（台账 DB-23）：核验留痕造数已改为经应用时钟传参（DB-22），但若将来有人把造数
 * 回退为 SQL {@code NOW()}（数据库服务器时钟），在 +8 时区的门禁里只有恰好跑在本地 0~8 点
 * （数据库钟与服务钟"当日"分叉的窗口）才会红，其余时段静默假绿。本测试把该分叉固化为自动
 * 回归：应用时钟被覆盖为固定偏移钟（系统"昨天"23:00），使"应用时钟的今天"（= 系统昨天）
 * 与数据库钟当天（UTC 容器钟）在<b>本地 8~24 点带</b>恒分叉——造数若回退 SQL NOW()，
 * 行落位与锚用例的窗口期望错位而红。</p>
 *
 * <p><b>守护边界（时带差异，评审循环 1 勘误）</b>：本地 0~8 点带数据库钟（UTC）日期恰等于
 * 应用"今天"，分叉塌陷——该带锚①（当日上限）整例假绿；<b>类级必红由锚②全天候承担</b>
 * （任意时带落位自检与服务判定断言至少两路独立红，解析推演见锚②注释；反向探针实测时点
 * 2026-09-26 12:19 属 8~24 带，Failures: 2）。门禁为手动触发（无定时 CI），0~8 带暴露概率低，
 * 且后果是必红强度衰减而非漏检。</p>
 *
 * <p>两条"今日对照"锚用例（规格行为 3 第 3 条"超限当日锁定、次日自动恢复"的偏移钟口径，
 * 两半各一）：① 当日第 6 次核验被拒 400（1004B0005）；② 昨日失败次日恢复放行
 * （remainingAttemptsToday=5、自动流转待审核）。判定按应用时钟成立 = 服务"当日"判定与
 * 造数同源（Clock 注入点）的证明。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class TwoClocksOffsetIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 与 SubjectRegistrationService.SEQ_DATE 同格式的本地副本（该常量为 private）。 */
    private static final java.time.format.DateTimeFormatter SEQ_DATE_LOCAL =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String BASE = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static final String LEGAL_PERSON = "张伟";
    /** 虚构且校验位合法、尾号非 8（核验通过）。 */
    private static final String LEGAL_PERSON_ID_OK = "110101199001011229";
    /** 虚构且校验位合法、尾号为 8（触发模拟渠道不通过）。 */
    private static final String LEGAL_PERSON_ID_FAIL = "110101199001011288";
    /** 模拟渠道对预置影像 A1 返回的固定信用代码（剧本附录 A 组 A1），确认值以 OCR 回填为准
     *  （与 CertificationIntegrationTest 同值——渠道预置若变须两处同步）。 */
    private static final String MOCK_OCR_USCC = "91330100MA27XW123X";
    private static Path auditDir;
    private static Path keyFile;

    /** DB-25：模块共享容器 + 本类独立库名（ADR-010 §8 形态 B）。 */
    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_subject_two_clocks");
    }

    @BeforeAll
    static void createDirsAndKeyFile() throws Exception {
        auditDir = Files.createTempDirectory("ctds-audit-twoclocks");
        keyFile = Files.createTempFile("ctds-test-keys-twoclocks", ".keys");
        // 16 字节测试密钥（仅测试用）；文件命名 *.keys（.gitignore 拦截口径，密钥零入库）
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

    /** 覆盖应用时钟：固定在"系统昨天 23:00"（相对构造）——应用"今天" = 系统昨天，与本地 JVM 钟在
     *  24 小时内恒分叉。选 -1 天方向而非 +1 天：SQL NOW() 回退时造数行落数据库钟当天（UTC 容器钟），
     *  本地 8~24 带与系统同日、0~8 带为系统昨天——两种时带下都不落入锚②的期望窗口（应用昨天 =
     *  系统前天 / 应用今天 = 系统昨天在 0~8 带恰好是 NOW() 落点、落位自检昨日窗应 5 实 0 仍红），
     *  锚②落位自检全天候必红（+1 天方向会让"昨日恢复"期望与 NOW() 落点巧合重合致探针失效，
     *  首跑实证后改向）。 */
    @TestConfiguration
    static class OffsetClockConfig {
        @Bean
        @Primary
        Clock offsetClock() {
            final ZoneId zone = ZoneId.systemDefault();
            return Clock.fixed(LocalDate.now(Clock.systemDefaultZone()).minusDays(1)
                    .atTime(23, 0).atZone(zone).toInstant(), zone);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 偏移后的应用时钟（与服务的"当日"判定同源——本类的全部窗口计算经此）。 */
    @Autowired
    private Clock clock;

    @BeforeEach
    void requireDivergedClocks() {
        // 分叉前提自检：应用"今天"必须 ≠ JVM 系统钟"今天"（本断言不校验数据库钟；本地 0~8 点带
        // 数据库钟——UTC——分叉塌陷由锚②落位自检全天候兜底）。偏移方向为 -1 天，24 小时内的测试
        // 运行 JVM 钟不可能追平；分叉若失效在此显式红而非静默退化。
        assertThat(LocalDate.now(clock).isEqual(LocalDate.now(Clock.systemDefaultZone())))
                .as("两把钟必须处于分叉状态（应用今天 = 系统昨天）——DB-23 锚用例前提")
                .isFalse();
    }

    @Test
    void appClockTodaySixthVerifyBlocked() throws Exception {
        // 锚用例①：应用钟"今天"已 5 条 FAIL → 第 6 次核验被拒 400（判定按应用时钟成立）。
        // 反向探针（仅本地 8~24 带红，0~8 带本用例假绿、类级由锚②兜底）：造数若回退 SQL NOW()，
        // 行落数据库钟当天——但服务的当日计数是 created_at >= 应用今日零点的【开区间】（无上界，
        // CertificationJdbcRepository#countFailuresSince），回退行仍被计入 → 第 6 次仍被拒 →
        // isBadRequest 断言在回退态【通过，从不承担必红】；真实红路径 = 落位自检
        // （应用"今天"窗口应 5 行实为 0 行，8~24 带成立）。
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB01", "偏移钟当日上限演示公司");
        insertVerificationRows(subjectNo, "FAIL", 1, 0, 5);
        assertWindowRows(subjectNo, 0, 5);
        // DB-09 钉死断言（评审④建议采纳）：申请编号日期段 = 应用钟"今天"——取号日期回退直连
        // 系统钟时此断言红（偏移钟下注册路径真实跨日，固化延伸到产品侧改动）。
        assertThat(subjectNo).as("申请编号日期段 = 应用钟当天（DB-09 取号日期同源）")
                .startsWith("S" + SEQ_DATE_LOCAL.format(LocalDate.now(clock)));

        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, APPLICANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0005"))
                .andExpect(jsonPath("$.message").value("今日核验次数已用完，请次日再试"));
    }

    @Test
    void appClockYesterdayFailuresRestoreToday() throws Exception {
        // 锚用例②：应用钟"昨天"（= 系统前天）5 条 FAIL 不占应用钟"今天"（= 系统昨天）额度
        // → 第 1 次核验放行并自动流转。
        // 反向探针（全天候红，类级必红的承担者）：造数若回退 SQL NOW()，行落数据库钟当天
        // （UTC）——8~24 带 = 系统今天（不在应用"昨天"窗 = 系统前天）→ 落位自检昨日窗应 5 实 0 红；
        // 0~8 带 = 应用"今天"（系统昨天）→ 落位自检今日窗应 0 实 5 同样红。且开区间当日计数把
        // 回退行计入 → 服务判 400 → isOk 断言若被执行同样红（只因落位自检先红未达）。
        // 两路独立红在任何时带成立。
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB0B", "偏移钟次日恢复演示公司");
        insertVerificationRows(subjectNo, "FAIL", 1, -1, 5);
        assertWindowRows(subjectNo, 5, 0);
        assertThat(subjectNo).as("申请编号日期段 = 应用钟当天（DB-09 取号日期同源）")
                .startsWith("S" + SEQ_DATE_LOCAL.format(LocalDate.now(clock)));

        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("PASS"))
                .andExpect(jsonPath("$.data.remainingAttemptsToday").value(5));
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
    }

    // ==== 辅助（与 CertificationIntegrationTest 同链路口径；独立上下文故最小复制。
    //  守护边界声明：本类 insertVerificationRows 与 CertificationIntegrationTest#insertVerificationRows
    //  当前逐字同构——造数口径改动须两处双向同步，否则一处改后另一处静默落后） ====

    /** 窗口落位自检：按应用时钟的昨日/今日各应有几条【法人核验】留痕。口径比服务的当日计数
     *  更严（服务为 created_at >= 今日零点的开区间 + FAIL/counted 过滤；本自检为有界双窗 +
     *  verify_type 过滤——OCR 上传/政务渠道也写留痕行但不参与当日计数）——正是这份"更严"
     *  在反向探针里接住了服务开区间计数的假通过。 */
    private void assertWindowRows(final String subjectNo, final long yesterdayRows, final long todayRows) {
        final LocalDateTime appTodayStart = LocalDate.now(clock).atStartOfDay();
        final LocalDateTime appYesterdayStart = appTodayStart.minusDays(1);
        final Long yesterday = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cert_verification_log v JOIN subject s ON s.id = v.subject_id "
                        + "WHERE s.subject_no = ? AND v.verify_type = 'LEGAL_PERSON' "
                        + "AND v.created_at >= ? AND v.created_at < ?",
                Long.class, subjectNo, appYesterdayStart, appTodayStart);
        final Long today = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cert_verification_log v JOIN subject s ON s.id = v.subject_id "
                        + "WHERE s.subject_no = ? AND v.verify_type = 'LEGAL_PERSON' "
                        + "AND v.created_at >= ? AND v.created_at < ?",
                Long.class, subjectNo, appTodayStart, appTodayStart.plusDays(1));
        assertThat(yesterday).as("应用钟昨日窗口法人核验行数（subject=%s）", subjectNo).isEqualTo(yesterdayRows);
        assertThat(today).as("应用钟今日窗口法人核验行数（subject=%s）", subjectNo).isEqualTo(todayRows);
    }

    /** 直插核验留痕行：dayOffset 相对【应用时钟】（0 = 应用今天、-1 = 应用昨天）；禁 SQL NOW()。
     *  反向探针实证（2026-09-26 12:19，本地 8~24 带）：本方法临时改为 SQL NOW() 时类级必红
     *  （Failures: 2；其中锚②全天候必红、锚①仅 8~24 带经落位自检红，0~8 带锚①假绿——
     *  时带机理见类 javadoc 守护边界段），已还原——造数回退 SQL NOW() 的回归类级不再静默。 */
    private void insertVerificationRows(final String subjectNo, final String conclusion, final int counted,
            final int dayOffset, final int rows) {
        final LocalDateTime createdAt = LocalDateTime.now(clock).plusDays(dayOffset);
        for (int i = 0; i < rows; i++) {
            jdbcTemplate.update("INSERT INTO cert_verification_log (subject_id, verify_type, channel_code, "
                            + "channel_request_no, legal_person_name, legal_person_id_cipher, conclusion, "
                            + "fail_reason, cost_ms, counted, created_at) SELECT id, 'LEGAL_PERSON', "
                            + "'mock-certification', ?, ?, NULL, ?, ?, 1, ?, ? "
                            + "FROM subject WHERE subject_no = ?",
                    "MOCK-SEED-" + conclusion + '-' + i, LEGAL_PERSON, conclusion,
                    "FAIL".equals(conclusion) ? "身份证号尾号 8（造数）" : null,
                    counted, createdAt, subjectNo);
        }
    }

    private String subjectStatus(final String subjectNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM subject WHERE subject_no = ?", String.class, subjectNo);
    }

    private String registerAndUploadAndConfirm(final String uscc, final String subjectName) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"" + subjectName + "\",\"uscc\":\"" + uscc + "\","
                                + "\"subjectType\":\"ENTERPRISE\",\"regAddress\":\"杭州市XX区XX路88号\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13800001234\","
                                + "\"adminAccount\":\"admin001\"}"))
                .andExpect(status().isOk())
                .andReturn();
        final String subjectNo = MAPPER.readTree(
                result.getResponse().getContentAsString()).get("data").get("subjectNo").asText();
        mockMvc.perform(multipart(BASE + "/" + subjectNo + "/certification/license")
                        .file(new MockMultipartFile("file", "A1.jpg", MediaType.IMAGE_JPEG_VALUE,
                                "image-bytes".getBytes(StandardCharsets.US_ASCII)))
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant"))
                .andExpect(status().isOk());
        // 确认值以 OCR 回填为准（业务口径：申请人核对的是 OCR 要素）
        mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/license/confirmation")
                        .header("X-Ctds-Subject", APPLICANT).header("X-Ctds-Roles", "applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"演示主体名称\",\"uscc\":\"" + MOCK_OCR_USCC + "\","
                                + "\"legalPerson\":\"" + LEGAL_PERSON
                                + "\",\"regAddress\":\"杭州市XX区XX路88号\"}"))
                .andExpect(status().isOk());
        return subjectNo;
    }

    private ResultActions verifyLegal(final String subjectNo, final String idNo, final String operator)
            throws Exception {
        return mockMvc.perform(post(BASE + "/" + subjectNo + "/certification/legal-person-verifications")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", "applicant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"legalPersonName\":\"" + LEGAL_PERSON + "\",\"legalPersonIdNo\":\"" + idNo + "\"}"));
    }
}
