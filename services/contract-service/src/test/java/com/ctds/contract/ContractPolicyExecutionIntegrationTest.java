package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.contract.application.PolicyExecutionService;
import com.ctds.contract.domain.CatalogProductPort;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.DidPort;
import com.ctds.contract.domain.SubjectAdmission;
import com.ctds.contract.domain.SubjectAdmissionPort;
import com.ctds.contract.domain.policy.UsageActionType;
import com.ctds.contract.domain.policy.UsageRequest;
import com.ctds.contract.domain.policy.UsageVerdict;
import com.ctds.contract.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 策略执行引擎全链集成测试（WBS-3.4.5 hifi §9 ②③）：真实 MySQL 8 容器实跑 Flyway
 * V1+V2+V3；资格/产品/DID 三端口 {@code @MockitoBean}（沿既有基座先例）；判定入口为
 * 同宿主直调（应用层方法，q2-A）。用例承载：生效→放行计数可查（R12）→耗尽拒绝留痕；
 * 五类拦截双向（配额/期限/用途/域/再分发）；终止→策略失效（S3-7）；未生效拒绝；
 * R12 权限矩阵（参与方/治理/非参与方防枚举同形）；拒绝不烧次数；空策略放行不计数；
 * 真并发配额两口径（判检一体防超卖）。L3 密钥源 = 临时密钥文件注入（密钥零入库）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractPolicyExecutionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/contracts";
    private static final String REQ = "S-req";
    private static final String PROV = "S-prov";
    private static final String OTHER = "S-other";
    private static final String ADMIN = "S-admin";
    private static final long PRODUCT_ID = 12L;
    private static final String PROVIDER_DID = "did:ctds:" + PROV + ".1";
    private static final String REQUESTER_DID = "did:ctds:" + REQ + ".1";
    /** CT000001 公共数据授权模板（V1 迁移种子，9 必填 + 1 选填槽位）。 */
    private static final String TEMPLATE_NO = "CT000001";
    private static final String C0012 = "1008C0012";
    private static final String C0013 = "1008C0013";
    private static final String C0020 = "1008C0020";
    private static final String PURPOSE_TEXT = "风控建模";
    private static final String TERRITORY_TEXT = "本市域";
    /** 测试固定钟判定日（两把钟教训——期限造数与引擎判定同源同钟，消除跨午夜竞态窗口）。 */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2027, 6, 15);

    private static Path keyFile;

    /**
     * 测试固定钟（覆盖应用注入 Clock bean）：期限造数与引擎判定共用同一时钟源；幂等组件
     * 内部走系统钟（System.currentTimeMillis），不受本覆盖影响。
     */
    @TestConfiguration
    static class FixedEngineClockConfig {

        @Bean
        @Primary
        Clock fixedEngineClock() {
            return Clock.fixed(FIXED_TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                    ZoneId.systemDefault());
        }
    }

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_policy_it");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        keyFile = Files.createTempFile("ctds-test-policy-keys", ".keys");
        Files.writeString(keyFile, "contract-deal-text=MDEyMzQ1Njc4OWFiY2RlZg==\n",
                StandardCharsets.UTF_8);
    }

    @AfterAll
    static void deleteKeyFile() throws Exception {
        Files.deleteIfExists(keyFile);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PolicyExecutionService policyExecutionService;
    @MockitoBean
    private SubjectAdmissionPort subjectAdmissionPort;
    @MockitoBean
    private CatalogProductPort catalogProductPort;
    @MockitoBean
    private DidPort didPort;

    @BeforeEach
    void stubPorts() {
        for (final String subject : List.of(REQ, PROV, OTHER, ADMIN)) {
            given(subjectAdmissionPort.check(subject)).willReturn(SubjectAdmission.ADMITTED);
        }
        given(catalogProductPort.fetch(PRODUCT_ID)).willReturn(
                CatalogProductPort.CatalogProductResult.found(
                        new CatalogProductPort.CatalogProduct(PRODUCT_ID, "城市餐饮单位经营数据集",
                                "LISTED", PROV, "PER_CALL", new BigDecimal("1.50"))));
        given(didPort.resolve(PROVIDER_DID)).willReturn(DidPort.DidBinding.found(PROV, "ACTIVE"));
        given(didPort.resolve(REQUESTER_DID)).willReturn(DidPort.DidBinding.found(REQ, "ACTIVE"));
        given(didPort.sign(eq(PROVIDER_DID), anyString()))
                .willReturn(DidPort.DidSignResult.signed("c2lnLXByb3ZpZGVy"));
        given(didPort.sign(eq(REQUESTER_DID), anyString()))
                .willReturn(DidPort.DidSignResult.signed("c2lnLXJlcXVlc3Rlcg"));
    }

    // ==== t11/t12 放行计数可查 + 耗尽拒绝留痕（规格验收标准 1 直译：第 1~N 次放行、第 N+1 次拒绝）====

    @Test
    void t11_effectiveContractUseAllowedCountedAndVisibleInSummary() throws Exception {
        final int limit = 100;
        final String contractNo = toEffective(unique("放行计数"), quotaStrategy(limit));
        // 第 1~100 次合规使用全部放行且计数递增可查（规格验收标准 1 直译；每次均携带用途
        // 原文——策略无用途要素不影响放行，供行末"请求原文不出站"断言真实生效）
        for (int i = 1; i <= limit; i++) {
            final UsageVerdict verdict = policyExecutionService.check(contractNo,
                    use(PURPOSE_TEXT, null));
            assertThat(verdict.allowed()).as("第 %s 次使用应放行", i).isTrue();
            assertThat(verdict.usedCount()).isEqualTo(i);
        }
        assertThat(counterOf(contractNo)).isEqualTo(limit);
        // 第 101 次拒绝（C0020 + 触发要素 QUOTA_EXHAUSTED 留痕；计数不变）
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf)
                .isEqualTo(C0020);
        assertThat(counterOf(contractNo)).isEqualTo(limit);
        // 拒绝留痕四要素（谁/何时/哪份合约/结果 + 触发要素）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT violations FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", String.class, contractNo)).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT requester_no FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", String.class, contractNo)).isEqualTo(REQ);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", Integer.class, contractNo)).isEqualTo(limit);
        // R12 摘要（参与方视角）：计数视图 + 放行/拒绝统计 + 记录分页（最新在前）
        final JsonNode data = readBody(summary(contractNo, REQ, "provider")).path("data");
        assertThat(data.path("quota").path("limit").asInt()).isEqualTo(limit);
        assertThat(data.path("quota").path("used").asInt()).isEqualTo(limit);
        assertThat(data.path("allowedCount").asLong()).isEqualTo(limit);
        assertThat(data.path("deniedCount").asLong()).isEqualTo(1);
        assertThat(data.path("records").path("total").asLong()).isEqualTo(limit + 1L);
        assertThat(data.path("records").path("list").get(0).path("outcome").asText())
                .isEqualTo("DENIED");
        assertThat(data.path("records").path("list").get(0).path("violations").asText())
                .isEqualTo("QUOTA_EXHAUSTED");
        // 记录行不含请求原文（留痕四要素纪律——上方 100 次请求均携带用途原文，记录行仍不含）
        assertThat(data.path("records").toString()).doesNotContain(PURPOSE_TEXT);
    }

    // ==== t13 期限双向（规格验收标准 2）====

    @Test
    void t13_termWithinRangeAllowedOutsideRangeRejectedWithTermExpired() throws Exception {
        // 期内（起 = 固定钟判定日当天，含首日）→ 放行；未到起始日（期外）→ 拒绝 TERM_EXPIRED
        final String inTerm = toEffective(unique("期限内"), termStrategy(FIXED_TODAY,
                FIXED_TODAY.plusYears(1)));
        assertThat(policyExecutionService.check(inTerm, use(null, null)).allowed()).isTrue();
        final String outOfTerm = toEffective(unique("期限外"), termStrategy(
                FIXED_TODAY.plusDays(1), FIXED_TODAY.plusYears(1)));
        assertThatThrownBy(() -> policyExecutionService.check(outOfTerm, use(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0020);
        assertThat(deniedViolations(outOfTerm)).isEqualTo("TERM_EXPIRED");
    }

    // ==== t14/t15/t16 用途 / 域 / 再分发双向（规格验收标准 3/4/5）====

    @Test
    void t14_purposeMismatchRejectedAndAgreedPurposeAllowed() throws Exception {
        final String contractNo = toEffective(unique("用途"), purposeStrategy(PURPOSE_TEXT));
        assertThat(policyExecutionService.check(contractNo,
                use("  " + PURPOSE_TEXT + "  ", null)).allowed())
                .as("约定用途（请求侧 trim 对称）应放行").isTrue();
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use("政策研究", null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0020);
        assertThat(deniedViolations(contractNo)).isEqualTo("PURPOSE_MISMATCH");
    }

    @Test
    void t15_territoryMismatchRejectedAndInTerritoryAllowed() throws Exception {
        final String contractNo = toEffective(unique("域内"), territoryStrategy(TERRITORY_TEXT));
        assertThat(policyExecutionService.check(contractNo, use(null, TERRITORY_TEXT)).allowed())
                .isTrue();
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use(null, "境外")))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0020);
        assertThat(deniedViolations(contractNo)).isEqualTo("TERRITORY_MISMATCH");
    }

    @Test
    void t16_redistributionBlockedWithDeniedLogAndUseAllowed() throws Exception {
        final String contractNo = toEffective(unique("再分发"), noRedistributionStrategy());
        assertThat(policyExecutionService.check(contractNo, use(null, null)).allowed())
                .as("USE 动作不受禁止再分发影响").isTrue();
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, new UsageRequest(REQ,
                UsageActionType.REDISTRIBUTE, null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0020);
        assertThat(deniedViolations(contractNo)).isEqualTo("REDISTRIBUTION_FORBIDDEN");
    }

    @Test
    void t16_allFiveElementsEnabledContractDecidesByEachElement() throws Exception {
        final String contractNo = toEffective(unique("五要素全启用"), allElementsStrategy(5,
                FIXED_TODAY, FIXED_TODAY.plusYears(1)));
        // 全要素匹配 + USE + 配额未耗尽 → 放行且计数
        final UsageVerdict allowed = policyExecutionService.check(contractNo,
                use(PURPOSE_TEXT, TERRITORY_TEXT));
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.usedCount()).isEqualTo(1);
        // 多要素同时触犯 → 全查明细（用途 + 域 + 再分发三项一并报告，拒绝零计数）
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, new UsageRequest(REQ,
                UsageActionType.REDISTRIBUTE, "政策研究", "境外")))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0020);
        assertThat(deniedViolations(contractNo))
                .isEqualTo("PURPOSE_MISMATCH,TERRITORY_MISMATCH,REDISTRIBUTION_FORBIDDEN");
        assertThat(counterOf(contractNo)).isEqualTo(1);
    }

    // ==== t17 R12 权限矩阵（参与方过 / 治理过 / 非参与方与不存在同形防枚举）====

    @Test
    void t17_usageSummaryVisibilityMatrix() throws Exception {
        final String contractNo = toEffective(unique("摘要可见"), quotaStrategy(3));
        assertThat(policyExecutionService.check(contractNo, use(null, null)).allowed()).isTrue();
        // 参与方双方可见（提供方 / 需求方）
        final MvcResult providerSummary = summary(contractNo, PROV, "provider");
        assertThat(providerSummary.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(providerSummary).path("data").path("contractNo").asText())
                .isEqualTo(contractNo);
        final MvcResult requesterSummary = summary(contractNo, REQ, "provider");
        assertThat(requesterSummary.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(requesterSummary).path("data").path("allowedCount").asLong())
                .isEqualTo(1);
        // 治理（admin 角色头）可见
        final MvcResult governanceSummary = summary(contractNo, ADMIN, "admin");
        assertThat(governanceSummary.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(governanceSummary).path("data").path("quota").path("limit").asInt())
                .isEqualTo(3);
        // 非参与方 → 与"不存在"同码同文逐字（防枚举）+ DENIED_ACCESS 留痕
        final MvcResult notVisible = summary(contractNo, OTHER, "provider");
        final MvcResult notExists = summary("CO999999", OTHER, "provider");
        assertThat(notVisible.getResponse().getStatus()).isEqualTo(404);
        assertThat(readBody(notVisible).path("code").asText()).isEqualTo(C0012);
        assertThat(readBody(notVisible).path("message").asText())
                .isEqualTo(readBody(notExists).path("message").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE action = 'DENIED_ACCESS' "
                        + "AND actor_subject_no = ? AND reason_code = 'C0012'", Integer.class, OTHER))
                .isGreaterThanOrEqualTo(2);
    }

    // ==== t18 拒绝不烧次数（规格行为 5 规则 6 / Q3-A 计数口径）====

    @Test
    void t18_rejectedAttemptDoesNotConsumeQuota() throws Exception {
        final String contractNo = toEffective(unique("拒绝不烧"),
                quotaAndPurposeStrategy(2, PURPOSE_TEXT));
        // 用途不符拒绝 → 计数零变化（counter 无行/零值）
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use("政策研究", null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0020);
        assertThat(counterOf(contractNo)).isZero();
        // 正确用途可连续放行 2 次（配额未被拒绝消耗）
        assertThat(policyExecutionService.check(contractNo, use(PURPOSE_TEXT, null)).usedCount())
                .isEqualTo(1);
        assertThat(policyExecutionService.check(contractNo, use(PURPOSE_TEXT, null)).usedCount())
                .isEqualTo(2);
        assertThat(counterOf(contractNo)).isEqualTo(2);
    }

    // ==== t19 终止 → 策略同步失效（S3-7 联动，移交-5 承接）====

    @Test
    void t19_terminatedContractStrategyIneffective() throws Exception {
        final String contractNo = toEffective(unique("终止失效"), quotaStrategy(5));
        assertThat(policyExecutionService.check(contractNo, use(null, null)).allowed()).isTrue();
        mockMvc.perform(post(BASE + "/governance/" + contractNo + "/force-termination")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"策略失效联动锚\"}")).andReturn();
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0013);
        assertThat(deniedReasonCode(contractNo)).isEqualTo("C0013");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT used_count FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", Integer.class, contractNo))
                .as("拒绝留痕计数快照 = 当前不变值（hifi §5 used_count 口径）").isEqualTo(1);
        assertThat(counterOf(contractNo)).as("状态失效拒绝零副作用（计数不变）").isEqualTo(1);
        // 终止后执行记录仍可查（R12 摘要：quota 视图随策略失效转 null，流水保留——移交-5 可追溯口径）
        final JsonNode terminatedSummary = readBody(summary(contractNo, REQ, "provider")).path("data");
        assertThat(terminatedSummary.path("quota").isNull()).isTrue();
        assertThat(terminatedSummary.path("allowedCount").asLong()).isEqualTo(1);
        assertThat(terminatedSummary.path("deniedCount").asLong()).isEqualTo(1);
    }

    // ==== t20 未生效合约拒绝（策略未生效 = 不做使用放行）====

    @Test
    void t20_notYetEffectiveContractRejected() throws Exception {
        final String contractNo = initiateOk(unique("未生效"), quotaStrategy(5));
        assertThatThrownBy(() -> policyExecutionService.check(contractNo, use(null, null)))
                .isInstanceOf(ContractBizException.class)
                .extracting(ContractPolicyExecutionIntegrationTest::codeOf).isEqualTo(C0013);
        assertThat(counterOf(contractNo)).isZero();
    }

    // ==== t21 空策略 / 显式"无使用限制"（放行不计数）====

    @Test
    void t21_explicitNoRestrictionDeclarationAllowedWithoutCounting() throws Exception {
        final String contractNo = toEffective(unique("显式无限制"),
                "{\"noRestrictionDeclared\":true}");
        final UsageVerdict verdict = policyExecutionService.check(contractNo,
                use("任意用途", "任意地域"));
        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.usedCount()).isZero();
        assertThat(counterOf(contractNo)).as("无配额口径不建计数行").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_usage_counter WHERE contract_no = ?", Integer.class,
                contractNo)).isZero();
        assertThat(deniedCountOf(contractNo)).isZero();
        assertThat(allowedCountOf(contractNo)).isEqualTo(1);
    }

    // ==== t22 真并发配额两口径（hifi §7-3：判检一体防超卖）====

    @Test
    void t22_concurrentQuotaExactlyNLimitAllAllowed() throws Exception {
        final int limit = 5;
        final String contractNo = toEffective(unique("并发N"), quotaStrategy(limit));
        final List<Throwable> failures = runConcurrently(limit,
                () -> policyExecutionService.check(contractNo, use(null, null)));
        assertThat(failures).as("上限内并发不应有拒绝").isEmpty();
        assertThat(counterOf(contractNo)).isEqualTo(limit);
        assertThat(allowedCountOf(contractNo)).isEqualTo(limit);
        assertThat(deniedCountOf(contractNo)).isZero();
    }

    @Test
    void t22_concurrentQuotaLimitPlusOneExactlyOneDenied() throws Exception {
        final int limit = 5;
        final String contractNo = toEffective(unique("并发N+1"), quotaStrategy(limit));
        final AtomicInteger allowed = new AtomicInteger();
        final AtomicInteger denied = new AtomicInteger();
        final List<Throwable> failures = runConcurrently(limit + 1, () -> {
            try {
                final UsageVerdict verdict = policyExecutionService.check(contractNo,
                        use(null, null));
                assertThat(verdict.allowed()).isTrue();
                allowed.incrementAndGet();
            } catch (final ContractBizException ex) {
                assertThat(ex.getErrorCode().value()).isEqualTo(C0020);
                denied.incrementAndGet();
            }
        });
        assertThat(failures).as("并发下不应出现非业务异常").isEmpty();
        assertThat(allowed.get()).as("恰好放行 N 次（判检一体不超卖）").isEqualTo(limit);
        assertThat(denied.get()).as("恰好拒绝 1 次").isEqualTo(1);
        assertThat(counterOf(contractNo)).isEqualTo(limit);
        assertThat(allowedCountOf(contractNo)).isEqualTo(limit);
        assertThat(deniedCountOf(contractNo)).isEqualTo(1);
        assertThat(deniedViolations(contractNo)).isEqualTo("QUOTA_EXHAUSTED");
    }

    // ==== t23 R12 摘要配额视图（quota 要素未启用 → null——契约"无生效配额口径"腿）====

    @Test
    void t23_summaryQuotaNullWhenQuotaElementNotEnabled() throws Exception {
        final String contractNo = toEffective(unique("无配额摘要"), purposeStrategy(PURPOSE_TEXT));
        assertThat(policyExecutionService.check(contractNo, use(PURPOSE_TEXT, null)).allowed())
                .isTrue();
        final JsonNode data = readBody(summary(contractNo, REQ, "provider")).path("data");
        assertThat(data.path("quota").isNull()).as("quota 未启用 → 配额视图 null").isTrue();
        assertThat(data.path("allowedCount").asLong()).isEqualTo(1);
        assertThat(data.path("deniedCount").asLong()).isZero();
    }

    // ==== 支撑（链路捷径 / 请求助手 / 探针）====

    private List<Throwable> runConcurrently(final int threads, final Runnable action)
            throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final List<Future<Throwable>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    action.run();
                    return null;
                } catch (final Throwable failure) {
                    return failure;
                }
            }));
        }
        final List<Throwable> failures = new ArrayList<>();
        for (final Future<Throwable> future : futures) {
            final Throwable failure = future.get(30, TimeUnit.SECONDS);
            if (failure != null) {
                failures.add(failure);
            }
        }
        pool.shutdownNow();
        return failures;
    }

    private static UsageRequest use(final String purpose, final String territory) {
        return new UsageRequest(REQ, UsageActionType.USE, purpose, territory);
    }

    private static String codeOf(final Throwable throwable) {
        return ((ContractBizException) throwable).getErrorCode().value();
    }

    private int counterOf(final String contractNo) {
        final List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT used_count FROM contract_usage_counter WHERE contract_no = ?",
                Integer.class, contractNo);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private long allowedCountOf(final String contractNo) {
        return logCountByOutcome(contractNo, "ALLOWED");
    }

    private long deniedCountOf(final String contractNo) {
        return logCountByOutcome(contractNo, "DENIED");
    }

    private long logCountByOutcome(final String contractNo, final String outcome) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_usage_log WHERE contract_no = ? AND outcome = ?",
                Long.class, contractNo, outcome);
    }

    private String deniedViolations(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT violations FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", String.class, contractNo);
    }

    private String deniedReasonCode(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT reason_code FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", String.class, contractNo);
    }

    private MvcResult summary(final String contractNo, final String operator, final String role)
            throws Exception {
        return mockMvc.perform(get(BASE + "/" + contractNo + "/usage-summary")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", role)).andReturn();
    }

    private String initiateOk(final String marker, final String strategyJson) throws Exception {
        final MvcResult result = mockMvc.perform(post(BASE)
                .header("X-Ctds-Subject", REQ).header("X-Ctds-Roles", "provider")
                .contentType(MediaType.APPLICATION_JSON)
                .content(initiateBody(marker, strategyJson))).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("发起应成功: %s", readBody(result)).isEqualTo(201);
        return readBody(result).path("data").path("contractNo").asText();
    }

    private String toEffective(final String marker, final String strategyJson) throws Exception {
        final String contractNo = initiateOk(marker, strategyJson);
        for (final String operator : List.of(REQ, PROV)) {
            assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/confirmations")
                            .header("X-Ctds-Subject", operator)
                            .header("X-Ctds-Roles", "provider")).andReturn()
                    .getResponse().getStatus()).as("确认应成功（%s）", operator).isEqualTo(200);
        }
        for (final String[] signing : List.of(new String[] {PROV, PROVIDER_DID},
                new String[] {REQ, REQUESTER_DID})) {
            assertThat(mockMvc.perform(post(BASE + "/" + contractNo + "/signatures")
                            .header("X-Ctds-Subject", signing[0]).header("X-Ctds-Roles", "provider")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"did\":\"" + signing[1] + "\"}")).andReturn()
                    .getResponse().getStatus()).as("签署应成功（%s）", signing[0]).isEqualTo(201);
        }
        return contractNo;
    }

    private static String initiateBody(final String marker, final String strategyJson) {
        return "{\"productId\":" + PRODUCT_ID + ",\"templateNo\":\"" + TEMPLATE_NO
                + "\",\"templateVersionNo\":1,\"clauseValues\":" + clauseValuesJson(marker,
                strategyJson) + "}";
    }

    private static String clauseValuesJson(final String marker, final String strategyJson) {
        return "{\"slots\":{"
                + "\"subject_matter\":\"城市餐饮单位经营数据集（" + marker + "）\","
                + "\"scope\":\"内部数据分析\","
                + "\"term\":\"2027-10-04 至 2028-10-03\","
                + "\"purpose_and_restrictions\":\"政策研究，禁止再分发\","
                + "\"security_confidentiality\":\"按平台安全规范执行\","
                + "\"liability\":\"双方违约按实际损失赔付\","
                + "\"dispute_resolution\":\"提交平台争议解决\","
                + "\"data_format_delivery\":\"库表接口拉取\","
                + "\"data_update_obligation\":\"按日更新并通知\""
                + "},\"strategy\":" + strategyJson + "}";
    }

    private static String quotaStrategy(final int maxCount) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static String quotaAndPurposeStrategy(final int maxCount, final String purposeText) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"purpose\":{\"enabled\":true,\"text\":\"" + purposeText + "\"},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static String termStrategy(final LocalDate start, final LocalDate end) {
        return "{\"term\":{\"enabled\":true,\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\"},\"noRestrictionDeclared\":false}";
    }

    private static String purposeStrategy(final String purposeText) {
        return "{\"purpose\":{\"enabled\":true,\"text\":\"" + purposeText + "\"},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static String territoryStrategy(final String territoryText) {
        return "{\"territory\":{\"enabled\":true,\"text\":\"" + territoryText + "\"},"
                + "\"noRestrictionDeclared\":false}";
    }

    private static String noRedistributionStrategy() {
        return "{\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}";
    }

    private static String allElementsStrategy(final int maxCount, final LocalDate start,
            final LocalDate end) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"term\":{\"enabled\":true,\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\"}," + "\"purpose\":{\"enabled\":true,\"text\":\"" + PURPOSE_TEXT + "\"},"
                + "\"territory\":{\"enabled\":true,\"text\":\"" + TERRITORY_TEXT + "\"},"
                + "\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}";
    }

    private static JsonNode readBody(final MvcResult result) {
        try {
            return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String unique(final String label) {
        return label + "-" + Long.toHexString(System.nanoTime());
    }
}
