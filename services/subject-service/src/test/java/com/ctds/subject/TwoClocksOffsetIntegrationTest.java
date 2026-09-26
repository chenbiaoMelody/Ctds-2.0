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
 * （数据库钟与服务钟"当日"分叉的窗口）才会红，其余时段静默假绿。本测试把该分叉固化为
 * <b>任何真实时刻运行都成立</b>的回归：应用时钟被覆盖为固定偏移钟（系统"昨天"23:00），
 * 使"应用时钟的今天"（= 系统昨天）恒不等于"数据库时钟的今天"（= 系统今天）——造数若回退
 * SQL NOW()，行落位与两条锚用例的窗口期望全部错位，落位自检与服务判定断言必红
 * （反向探针，机理见各用例注释）。</p>
 *
 * <p>两条"今日对照"锚用例（规格行为 3 第 2/3 条的偏移钟口径）：
 * ① 当日第 6 次核验被拒 400（1004B0005）；② 昨日失败次日恢复放行（remainingAttemptsToday=5、
 * 自动流转待审核）。判定按应用时钟成立 = 服务"当日"判定与造数同源（Clock 注入点）的证明。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class TwoClocksOffsetIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/subject/registrations";
    private static final String APPLICANT = "applicant-01";
    private static final String LEGAL_PERSON = "张伟";
    /** 虚构且校验位合法、尾号非 8（核验通过）。 */
    private static final String LEGAL_PERSON_ID_OK = "110101199001011229";
    /** 虚构且校验位合法、尾号为 8（触发模拟渠道不通过）。 */
    private static final String LEGAL_PERSON_ID_FAIL = "110101199001011288";
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

    /** 覆盖应用时钟：固定在"系统昨天 23:00"（相对构造）——应用"今天" = 系统昨天，与数据库侧
     *  "今天"（系统今天）恒分叉。选 -1 天方向而非 +1 天：SQL NOW() 回退时造数行落系统"今天"，
     *  既不落入应用"今天"窗口（系统昨天）也不落入应用"昨天"窗口（系统前天）——两条锚用例的
     *  落位自检同时必红（+1 天方向会让"昨日恢复"用例的期望落位与 NOW() 落点巧合重合而探针失效）。 */
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
        // 分叉前提自检：应用"今天"必须 ≠ 系统（数据库侧）"今天"。偏移方向为 -1 天，24 小时内的
        // 测试运行不可能追平；此断言为防御性双保险——分叉若失效，在此显式红而非静默退化。
        assertThat(LocalDate.now(clock).isEqual(LocalDate.now(Clock.systemDefaultZone())))
                .as("两把钟必须处于分叉状态（应用今天 = 系统昨天）——DB-23 锚用例前提")
                .isFalse();
    }

    @Test
    void appClockTodaySixthVerifyBlocked() throws Exception {
        // 锚用例①：应用钟"今天"已 5 条 FAIL → 第 6 次核验被拒 400（判定按应用时钟成立）。
        // 反向探针：造数若回退 SQL NOW()，行落系统"今天"→ 计数窗口（应用今天 = 系统昨天）
        // 计 0 → 第 6 次放行 → isBadRequest 断言红；落位自检（下方）同样红。
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB01", "偏移钟当日上限演示公司");
        insertVerificationRows(subjectNo, "FAIL", 1, 0, 5);
        assertWindowRows(subjectNo, 0, 5);

        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, APPLICANT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004B0005"))
                .andExpect(jsonPath("$.message").value("今日核验次数已用完，请次日再试"));
    }

    @Test
    void appClockYesterdayFailuresRestoreToday() throws Exception {
        // 锚用例②：应用钟"昨天"（= 系统前天）5 条 FAIL 不占应用钟"今天"（= 系统昨天）额度
        // → 第 1 次核验放行并自动流转。
        // 反向探针：造数若回退 SQL NOW()，行落系统"今天"——既不在应用"今天"窗口（系统昨天）
        // 也不在应用"昨天"窗口（系统前天）→ 落位自检红（昨日应 5 行实为 0）→ 用例红
        // （放行断言此时平凡通过，落位自检承担必红义务——造数落位必须经应用时钟）。
        final String subjectNo = registerAndUploadAndConfirm("91330100MA27X8AB0B", "偏移钟次日恢复演示公司");
        insertVerificationRows(subjectNo, "FAIL", 1, -1, 5);
        assertWindowRows(subjectNo, 5, 0);

        verifyLegal(subjectNo, LEGAL_PERSON_ID_OK, APPLICANT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conclusion").value("PASS"))
                .andExpect(jsonPath("$.data.remainingAttemptsToday").value(5));
        assertThat(subjectStatus(subjectNo)).isEqualTo("PENDING_REVIEW");
    }

    // ==== 辅助（与 CertificationIntegrationTest 同链路口径；独立上下文故最小复制） ====

    /** 窗口落位自检：按应用时钟的昨日/今日各应有几条【法人核验】留痕（与服务的当日计数同口径——
     *  OCR 上传/政务渠道也会写留痕行但不参与当日计数，须按 verify_type 过滤）。 */
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
     *  反向探针实证（2026-09-26）：本方法临时改为 SQL NOW() 时两条锚用例必红（Failures: 2），
     *  已还原——造数回退 SQL NOW() 的回归在本类中不再静默。 */
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
                        .content("{\"subjectName\":\"演示主体名称\",\"uscc\":\"91330100MA27XW123X\","
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
