package com.ctds.contract;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ctds.contract.support.SharedMySqlContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
 * 策略模拟器与测试台全链集成测试（WBS-3.4.6 hifi §9 ②③④⑤）：真实 MySQL 8 容器实跑 Flyway
 * V1+V2+V3；资格/产品/DID 三端口 {@code @MockitoBean}（沿既有基座先例）；固定钟 2027-06-15。
 *
 * <p>用例承载：模拟结论以数据返回（200 + allowed=false）；零副作用锚（模拟与测试台后 counter /
 * usage_log 零变化）；对照一致性锚（假想上下文 = 真实状态时模拟结论 ≡ 真实执行结论，含 100/101
 * 边界与各要素越界腿——期限届满腿以"生效后推进判定日越过策略截止日"构造，见 t14）；测试台报告三态
 * （全要素 / 部分要素 SKIPPED / 空策略 / 非生效合约失效预期）；受控执行五腿（放行计数可查 /
 * 耗尽拒绝留痕 / 终止 C0013 / 非参与方同码同文防枚举 / 治理不发起使用）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ContractPolicySimulatorIntegrationTest {

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
    private static final String C0015 = "1008C0015";
    private static final String C0020 = "1008C0020";
    private static final String PURPOSE_TEXT = "风控建模";
    private static final String TERRITORY_TEXT = "本市域";
    /** 测试固定钟判定日（两把钟教训——期限造数与判定共用同一时钟源）。 */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2027, 6, 15);
    /** 期限造数（以固定钟为基准：起始日 ≤ 当天 ≤ 截止日）。 */
    private static final LocalDate TERM_START = LocalDate.of(2027, 6, 1);
    private static final LocalDate TERM_END = LocalDate.of(2027, 12, 31);

    private static Path keyFile;
    /**
     * 测试判定日（缺省 = 固定钟 2027-06-15）；t14（期限届满真实腿）临时推进以越过策略截止日，用毕复位。
     */
    private static final AtomicReference<LocalDate> ENGINE_TODAY = new AtomicReference<>(FIXED_TODAY);

    /** 测试钟（覆盖应用注入 Clock bean——判定与假想日期缺省共用同一时钟源；判定日可推进）。 */
    @TestConfiguration
    static class MutableEngineClockConfig {

        @Bean
        @Primary
        Clock engineClock() {
            return new EngineClock();
        }
    }

    /** 委托 {@link #ENGINE_TODAY} 的时钟（{@code LocalDate.now(clock)} 与 {@code LocalDateTime.now(clock)} 同源）。 */
    private static final class EngineClock extends Clock {

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ENGINE_TODAY.get().atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
    }

    @DynamicPropertySource
    static void sharedMySqlDatasource(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_contract_simulator_it");
        registry.add("ctds.crypto.local.key-file", () -> keyFile.toString());
    }

    @BeforeAll
    static void createKeyFile() throws Exception {
        keyFile = Files.createTempFile("ctds-test-simulator-keys", ".keys");
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
        ENGINE_TODAY.set(FIXED_TODAY);
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

    // ==== t1 模拟结论以数据返回（HTTP 200 + allowed=false —— 非系统错误）====

    @Test
    void t1_simulationDenialIsReturnedAsDataWithHttp200() throws Exception {
        final String contractNo = toEffective(unique("模拟拒绝数据"),
                allElementsStrategy(2, TERM_START, TERM_END));
        final MvcResult result = simulate(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT, 2, null, REQ,
                "provider");
        assertThat(result.getResponse().getStatus()).as("模拟拒绝 = 结论数据（非异常）").isEqualTo(200);
        final JsonNode data = readBody(result).path("data");
        assertThat(data.path("allowed").asBoolean()).isFalse();
        assertThat(violationsOf(data)).containsExactly("QUOTA_EXHAUSTED");
        assertThat(data.path("strategySource").asText()).isEqualTo("EFFECTIVE");
        assertThat(data.path("strategyEffective").asBoolean()).isTrue();
        assertThat(data.path("contractStatus").asText()).isEqualTo("EFFECTIVE");
        assertThat(data.path("assumedUsedCount").asInt()).isEqualTo(2);
        assertThat(data.path("assumedDate").asText()).isEqualTo(FIXED_TODAY.toString());
        assertThat(data.path("policySnapshot").path("usage.quota").asText()).isEqualTo("2");
    }

    // ==== t2 模拟与测试台零副作用锚（counter / usage_log 零变化）====

    @Test
    void t2_simulationAndTestbenchLeaveCounterAndUsageLogUntouched() throws Exception {
        final String contractNo = toEffective(unique("零副作用"), allElementsStrategy(2, TERM_START,
                TERM_END));
        final int counterBefore = counterOf(contractNo);
        final long logBefore = logCount(contractNo);
        // 合规放行腿 + 配额越界腿 + 期限越界腿 + 再分发越界腿（模拟通道各向均不落库）
        assertThat(readBody(simulate(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT, 0, null, REQ,
                "provider")).path("data").path("allowed").asBoolean()).isTrue();
        assertThat(readBody(simulate(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT, 2, null, REQ,
                "provider")).path("data").path("violations").get(0).asText())
                .isEqualTo("QUOTA_EXHAUSTED");
        assertThat(readBody(simulate(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT, 0,
                TERM_END.plusDays(1).toString(), REQ, "provider")).path("data").path("violations")
                .get(0).asText()).isEqualTo("TERM_EXPIRED");
        assertThat(readBody(testbench(contractNo, REQ, "provider")).path("data").path("summary")
                .path("total").asInt()).isEqualTo(11);
        // 零副作用断言：计数行未创建（不存在 = 0）、执行记录零行（模拟与测试台不产生业务记录）
        assertThat(counterOf(contractNo)).as("模拟与测试台不写计数").isEqualTo(counterBefore).isZero();
        assertThat(logCount(contractNo)).as("模拟与测试台不写执行记录").isEqualTo(logBefore).isZero();
    }

    // ==== t3 对照一致性锚（模拟结论 ≡ 真实执行结论；含 100/101 边界与各要素越界腿）====

    @Test
    void t3_simulationMatchesRealExecutionForAllowedAndDeniedPaths() throws Exception {
        final int limit = 100;
        final String contractNo = toEffective(unique("对照一致"), allElementsStrategy(limit, TERM_START,
                TERM_END));
        // 合规放行腿（假想 = 真实计数 0）→ 真实执行放行 + 计数递增
        assertSimulationMatchesRealExecution(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT);
        assertThat(counterOf(contractNo)).isEqualTo(1);
        // 各要素越界腿：模拟（假想 = 真实）与真实执行（C0020 + 留痕触发要素）逐项一致
        assertSimulationMatchesRealExecution(contractNo, "USE", PURPOSE_TEXT + "-越界", TERRITORY_TEXT);
        assertSimulationMatchesRealExecution(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT + "-越界");
        assertSimulationMatchesRealExecution(contractNo, "REDISTRIBUTE", PURPOSE_TEXT, TERRITORY_TEXT);
        // 期限越界腿（真实执行腿日期 = 注入 Clock 当天在期限内；模拟显式假想末日 +1 → 拒绝）
        final JsonNode termSimulation = readBody(simulate(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT,
                null, TERM_END.plusDays(1).toString(), REQ, "provider")).path("data");
        assertThat(termSimulation.path("allowed").asBoolean()).isFalse();
        assertThat(violationsOf(termSimulation)).containsExactly("TERM_EXPIRED");
        // 配额边界 100/101：真实链路推满配额后，模拟（假想 = 真实 100）与真实执行一致拒绝
        for (int i = counterOf(contractNo); i < limit; i++) {
            assertThat(policyExecutionService.check(contractNo,
                    use(PURPOSE_TEXT, TERRITORY_TEXT)).allowed()).isTrue();
        }
        assertThat(counterOf(contractNo)).isEqualTo(limit);
        assertSimulationMatchesRealExecution(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT);
        assertThat(readBody(simulate(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT, null, null, REQ,
                "provider")).path("data").path("violations").get(0).asText())
                .isEqualTo("QUOTA_EXHAUSTED");
    }

    // ==== t4 测试台报告：全要素生效（U1~U10 PASS + U11 SKIPPED）====

    @Test
    void t4_testbenchAllElementsEnabledAllScenariosPass() throws Exception {
        final String contractNo = toEffective(unique("测试台全要素"), allElementsStrategy(2, TERM_START,
                TERM_END));
        final JsonNode data = readBody(testbench(contractNo, REQ, "provider")).path("data");
        assertThat(data.path("strategyEffective").asBoolean()).isTrue();
        assertThat(data.path("summary").path("total").asInt()).isEqualTo(11);
        assertThat(data.path("summary").path("pass").asInt()).isEqualTo(10);
        assertThat(data.path("summary").path("fail").asInt()).isZero();
        assertThat(data.path("summary").path("skipped").asInt()).isEqualTo(1);
        final JsonNode scenarios = data.path("scenarios");
        for (int i = 0; i < 10; i++) {
            assertThat(scenarios.get(i).path("outcome").asText())
                    .as("场景 %s 应 PASS", scenarios.get(i).path("code").asText()).isEqualTo("PASS");
        }
        assertThat(scenarios.get(10).path("code").asText()).isEqualTo("U11");
        assertThat(scenarios.get(10).path("outcome").asText()).isEqualTo("SKIPPED");
        // 触发要素明细（越界腿逐条命中；配额腿仅在四要素全过时判定）
        assertThat(scenarios.get(1).path("actual").path("violations").get(0).asText())
                .isEqualTo("QUOTA_EXHAUSTED");
        assertThat(scenarios.get(3).path("actual").path("violations").get(0).asText())
                .isEqualTo("TERM_EXPIRED");
        assertThat(scenarios.get(9).path("actual").path("allowed").asBoolean()).isTrue();
    }

    // ==== t5 测试台报告：部分要素未启用 → SKIPPED（诚实不假绿）====

    @Test
    void t5_testbenchPartialElementsSkippedForDisabled() throws Exception {
        final String contractNo = toEffective(unique("测试台部分要素"), quotaStrategy(2));
        final JsonNode data = readBody(testbench(contractNo, REQ, "provider")).path("data");
        assertThat(data.path("summary").path("total").asInt()).isEqualTo(11);
        assertThat(data.path("summary").path("pass").asInt()).isEqualTo(2);
        assertThat(data.path("summary").path("fail").asInt()).isZero();
        assertThat(data.path("summary").path("skipped").asInt()).as("八要素腿 + 空策略腿跳过").isEqualTo(9);
        final JsonNode scenarios = data.path("scenarios");
        assertThat(scenarios.get(0).path("outcome").asText()).isEqualTo("PASS");
        assertThat(scenarios.get(1).path("outcome").asText()).isEqualTo("PASS");
        for (int i = 2; i < 11; i++) {
            assertThat(scenarios.get(i).path("outcome").asText())
                    .as("场景 %s 要素未启用应 SKIPPED", scenarios.get(i).path("code").asText())
                    .isEqualTo("SKIPPED");
        }
    }

    // ==== t6 测试台报告：空策略/显式无限制（U1~U10 SKIPPED + U11 PASS）====

    @Test
    void t6_testbenchEmptyStrategyOnlyU11Pass() throws Exception {
        final String contractNo = toEffective(unique("测试台空策略"), noRestrictionStrategy());
        final JsonNode data = readBody(testbench(contractNo, REQ, "provider")).path("data");
        assertThat(data.path("summary").path("pass").asInt()).isEqualTo(1);
        assertThat(data.path("summary").path("fail").asInt()).isZero();
        assertThat(data.path("summary").path("skipped").asInt()).isEqualTo(10);
        final JsonNode scenarios = data.path("scenarios");
        for (int i = 0; i < 10; i++) {
            assertThat(scenarios.get(i).path("outcome").asText()).isEqualTo("SKIPPED");
        }
        assertThat(scenarios.get(10).path("code").asText()).isEqualTo("U11");
        assertThat(scenarios.get(10).path("outcome").asText()).isEqualTo("PASS");
        assertThat(scenarios.get(10).path("actual").path("allowed").asBoolean()).isTrue();
    }

    // ==== t7 测试台报告：合约非生效态 → 全场景"拒绝（策略失效）"预期（S3-7 联动）====

    @Test
    void t7_testbenchNonEffectiveContractAllScenariosStateDenied() throws Exception {
        final String contractNo = initiateOk(unique("测试台非生效"), quotaStrategy(5));
        final JsonNode data = readBody(testbench(contractNo, REQ, "provider")).path("data");
        assertThat(data.path("strategyEffective").asBoolean()).isFalse();
        assertThat(data.path("summary").path("pass").asInt()).isEqualTo(11);
        assertThat(data.path("summary").path("fail").asInt()).isZero();
        assertThat(data.path("summary").path("skipped").asInt()).isZero();
        for (final JsonNode scenario : iterable(data.path("scenarios"))) {
            assertThat(scenario.path("expectation").asText()).isEqualTo("拒绝（策略失效）");
            assertThat(scenario.path("outcome").asText()).isEqualTo("PASS");
            assertThat(scenario.path("actual").path("allowed").asBoolean()).isFalse();
        }
    }

    // ==== t8 受控执行：放行 → 计数递增 + R12 摘要可查 ====

    @Test
    void t8_usageExecutionAllowedThenCountedAndVisibleInSummary() throws Exception {
        final String contractNo = toEffective(unique("执行放行"), quotaStrategy(5));
        final MvcResult result = execute(contractNo, "USE", null, null, REQ, "provider");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = readBody(result).path("data");
        assertThat(data.path("contractNo").asText()).isEqualTo(contractNo);
        assertThat(data.path("allowed").asBoolean()).isTrue();
        assertThat(data.path("usedCount").asInt()).isEqualTo(1);
        assertThat(data.path("occurredAt").asText()).isNotBlank();
        assertThat(counterOf(contractNo)).isEqualTo(1);
        final JsonNode summary = readBody(summary(contractNo, REQ, "provider")).path("data");
        assertThat(summary.path("quota").path("limit").asInt()).isEqualTo(5);
        assertThat(summary.path("quota").path("used").asInt()).isEqualTo(1);
        assertThat(summary.path("allowedCount").asLong()).isEqualTo(1);
    }

    // ==== t9 受控执行：次数耗尽 → C0020 + 拒绝留痕 + 计数不变 ====

    @Test
    void t9_usageExecutionQuotaExhaustedRejectedWithDeniedLog() throws Exception {
        final String contractNo = toEffective(unique("执行耗尽"), quotaStrategy(1));
        assertThat(readBody(execute(contractNo, "USE", null, null, REQ, "provider")).path("data")
                .path("usedCount").asInt()).isEqualTo(1);
        final MvcResult denied = execute(contractNo, "USE", null, null, REQ, "provider");
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(readBody(denied).path("code").asText()).isEqualTo(C0020);
        assertThat(counterOf(contractNo)).as("越界拒绝零副作用（计数不变）").isEqualTo(1);
        assertThat(deniedViolations(contractNo)).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(deniedReasonCode(contractNo)).isEqualTo("C0020");
    }

    // ==== t10 受控执行：终止合约 → C0013（策略失效，S3-7 联动）+ 拒绝留痕 ====

    @Test
    void t10_usageExecutionTerminatedContractRejectedC0013() throws Exception {
        final String contractNo = toEffective(unique("执行终止"), quotaStrategy(5));
        mockMvc.perform(post(BASE + "/governance/" + contractNo + "/force-termination")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"受控执行终止联动锚\"}")).andReturn();
        final MvcResult denied = execute(contractNo, "USE", null, null, REQ, "provider");
        assertThat(denied.getResponse().getStatus()).isEqualTo(409);
        assertThat(readBody(denied).path("code").asText()).isEqualTo(C0013);
        assertThat(deniedReasonCode(contractNo)).isEqualTo("C0013");
        assertThat(counterOf(contractNo)).isZero();
    }

    // ==== t11 受控执行 / 模拟 / 测试台：非参与方与"不存在"同码同文（防枚举）+ 留痕 ====

    @Test
    void t11_simulatorEndpointsRejectNonParticipantWithEnumerationSafeCode() throws Exception {
        final String contractNo = toEffective(unique("模拟防枚举"), quotaStrategy(5));
        final MvcResult simulationNotVisible = simulate(contractNo, "USE", null, null, null, null, OTHER,
                "provider");
        final MvcResult simulationNotExists = simulate("CO999999", "USE", null, null, null, null, OTHER,
                "provider");
        assertThat(simulationNotVisible.getResponse().getStatus()).isEqualTo(404);
        assertThat(readBody(simulationNotVisible).path("code").asText()).isEqualTo(C0012);
        assertThat(readBody(simulationNotVisible).path("message").asText())
                .as("非参与方与不存在同码同文逐字")
                .isEqualTo(readBody(simulationNotExists).path("message").asText());
        assertThat(testbench(contractNo, OTHER, "provider").getResponse().getStatus()).isEqualTo(404);
        final MvcResult executionNotVisible = execute(contractNo, "USE", null, null, OTHER, "provider");
        assertThat(executionNotVisible.getResponse().getStatus()).isEqualTo(404);
        assertThat(readBody(executionNotVisible).path("message").asText())
                .isEqualTo(readBody(simulationNotExists).path("message").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_action_log WHERE action = 'DENIED_ACCESS' "
                        + "AND actor_subject_no = ? AND reason_code = 'C0012'", Integer.class, OTHER))
                .as("三个拒绝点均落 DENIED_ACCESS 留痕").isGreaterThanOrEqualTo(3);
        assertThat(counterOf(contractNo)).as("不可见拒绝零副作用").isZero();
    }

    // ==== t12 受控执行：治理方不发起使用动作（防"代他人使用"污染计数与流水）====

    @Test
    void t12_usageExecutionGovernanceRoleCannotExecute() throws Exception {
        final String contractNo = toEffective(unique("治理不发起"), quotaStrategy(5));
        final MvcResult result = execute(contractNo, "USE", null, null, ADMIN, "admin");
        assertThat(result.getResponse().getStatus()).as("仅参与方口径（治理例外不适用）").isEqualTo(404);
        assertThat(readBody(result).path("code").asText()).isEqualTo(C0012);
        assertThat(counterOf(contractNo)).isZero();
        assertThat(logCount(contractNo)).isZero();
    }

    // ==== t13 模拟草稿：非法草稿 → 1008C0015（解析器单点结论）====

    @Test
    void t13_simulationInvalidDraftStrategyRejectedC0015() throws Exception {
        final String contractNo = toEffective(unique("草稿非法"), quotaStrategy(5));
        final MvcResult result = simulateRaw(contractNo,
                "{\"actionType\":\"USE\",\"strategyDocument\":{\"quota\":{\"enabled\":true,"
                        + "\"maxCount\":0},\"noRestrictionDeclared\":false}}", REQ, "provider");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(readBody(result).path("code").asText()).isEqualTo(C0015);
        assertThat(counterOf(contractNo)).isZero();
    }

    // ==== t14 对照一致性锚：期限届满腿的真实执行腿（推进判定日越过策略截止日）====

    @Test
    void t14_simulationMatchesRealExecutionForExpiredTermOnRealLeg() throws Exception {
        // 策略期限 = 固定钟窗口（TERM_START~TERM_END，沿用既有生效夹具）；生效后推进判定日至截止日次日，
        // 使真实执行腿（既有链路、零改动）真实触发期限届满——补齐"各要素越界腿逐项一致"的期限面。
        final String contractNo = toEffective(unique("对照期限真实腿"),
                allElementsStrategy(100, TERM_START, TERM_END));
        assertThat(counterOf(contractNo)).isZero();
        final LocalDate expiredToday = TERM_END.plusDays(1);
        try {
            ENGINE_TODAY.set(expiredToday);
            assertSimulationMatchesRealExecution(contractNo, "USE", PURPOSE_TEXT, TERRITORY_TEXT,
                    expiredToday);
            assertThat(lastDeniedViolations(contractNo)).as("真实执行腿触发要素 = 期限届满")
                    .isEqualTo("TERM_EXPIRED");
            assertThat(deniedReasonCode(contractNo)).as("五类拦截统一出口 = 1008C0020（留痕记码尾号）")
                    .isEqualTo("C0020");
            assertThat(counterOf(contractNo)).as("期限届满拒绝零副作用（不计数）").isZero();
        } finally {
            ENGINE_TODAY.set(FIXED_TODAY);
        }
    }

    // ==== 对照锚助手 ====

    /** 模拟（假想 = 真实状态）与真实执行逐项对照（结论 + 触发要素集合；判定日 = 固定钟）。 */
    private void assertSimulationMatchesRealExecution(final String contractNo, final String actionType,
            final String purpose, final String territory) throws Exception {
        assertSimulationMatchesRealExecution(contractNo, actionType, purpose, territory, FIXED_TODAY);
    }

    /** 同上；{@code expectedAssumedDate} = 本次判定日（t14 推进判定日后不等于固定钟）。 */
    private void assertSimulationMatchesRealExecution(final String contractNo, final String actionType,
            final String purpose, final String territory, final LocalDate expectedAssumedDate)
            throws Exception {
        final int realCount = counterOf(contractNo);
        final JsonNode simulation = readBody(simulate(contractNo, actionType, purpose, territory, null,
                null, REQ, "provider")).path("data");
        assertThat(simulation.path("assumedUsedCount").asInt()).as("假想计数缺省 = 真实计数")
                .isEqualTo(realCount);
        boolean realAllowed = false;
        String realViolations = null;
        try {
            realAllowed = policyExecutionService.check(contractNo,
                    new UsageRequest(REQ, UsageActionType.valueOf(actionType), purpose, territory))
                    .allowed();
        } catch (final ContractBizException ex) {
            assertThat(ex.getErrorCode().value()).as("越界拒绝出口 = 1008C0020（五类拦截统一）")
                    .isEqualTo(C0020);
            realViolations = lastDeniedViolations(contractNo);
        }
        assertThat(simulation.path("allowed").asBoolean()).as("模拟结论 ≡ 真实执行结论（同源）")
                .isEqualTo(realAllowed);
        if (realAllowed) {
            assertThat(violationsOf(simulation)).isEmpty();
        } else {
            assertThat(String.join(",", violationsOf(simulation))).as("触发要素集合逐项一致")
                    .isEqualTo(realViolations);
        }
        assertThat(simulation.path("assumedDate").asText()).isEqualTo(expectedAssumedDate.toString());
    }

    // ==== 用例夹具（沿 ContractPolicyExecutionIntegrationTest 先例）====

    private static UsageRequest use(final String purpose, final String territory) {
        return new UsageRequest(REQ, UsageActionType.USE, purpose, territory);
    }

    private MvcResult simulate(final String contractNo, final String actionType, final String purpose,
            final String territory, final Integer assumedUsedCount, final String assumedDate,
            final String operator, final String role) throws Exception {
        return simulateRaw(contractNo, simulationBody(actionType, purpose, territory, assumedUsedCount,
                assumedDate), operator, role);
    }

    private MvcResult simulateRaw(final String contractNo, final String body, final String operator,
            final String role) throws Exception {
        return mockMvc.perform(post(BASE + "/" + contractNo + "/policy-simulations")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", role)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult testbench(final String contractNo, final String operator, final String role)
            throws Exception {
        return mockMvc.perform(post(BASE + "/" + contractNo + "/policy-testbench-runs")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", role)).andReturn();
    }

    private MvcResult execute(final String contractNo, final String actionType, final String purpose,
            final String territory, final String operator, final String role) throws Exception {
        return mockMvc.perform(post(BASE + "/" + contractNo + "/usage-executions")
                .header("X-Ctds-Subject", operator).header("X-Ctds-Roles", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(simulationBody(actionType, purpose, territory, null, null))).andReturn();
    }

    private static String simulationBody(final String actionType, final String purpose,
            final String territory, final Integer assumedUsedCount, final String assumedDate) {
        final StringBuilder body = new StringBuilder("{\"actionType\":\"").append(actionType).append('"');
        append(body, "purpose", purpose);
        append(body, "territory", territory);
        if (assumedUsedCount != null) {
            body.append(",\"assumedUsedCount\":").append(assumedUsedCount);
        }
        append(body, "assumedDate", assumedDate);
        return body.append('}').toString();
    }

    private static void append(final StringBuilder body, final String field, final String value) {
        if (value != null) {
            body.append(",\"").append(field).append("\":\"").append(value).append('"');
        }
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

    private static String noRestrictionStrategy() {
        return "{\"noRestrictionDeclared\":true}";
    }

    private static String allElementsStrategy(final int maxCount, final LocalDate start,
            final LocalDate end) {
        return "{\"quota\":{\"enabled\":true,\"maxCount\":" + maxCount + "},"
                + "\"term\":{\"enabled\":true,\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\"}," + "\"purpose\":{\"enabled\":true,\"text\":\"" + PURPOSE_TEXT + "\"},"
                + "\"territory\":{\"enabled\":true,\"text\":\"" + TERRITORY_TEXT + "\"},"
                + "\"noRedistribution\":{\"enabled\":true},\"noRestrictionDeclared\":false}";
    }

    // ==== 数据断言助手 ====

    private int counterOf(final String contractNo) {
        final List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT used_count FROM contract_usage_counter WHERE contract_no = ?",
                Integer.class, contractNo);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private long logCount(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contract_usage_log WHERE contract_no = ?", Long.class,
                contractNo);
    }

    private String deniedViolations(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT violations FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", String.class, contractNo);
    }

    private String lastDeniedViolations(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT violations FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED' ORDER BY id DESC LIMIT 1", String.class, contractNo);
    }

    private String deniedReasonCode(final String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT reason_code FROM contract_usage_log WHERE contract_no = ? AND outcome = "
                        + "'DENIED'", String.class, contractNo);
    }

    private static List<String> violationsOf(final JsonNode data) {
        final List<String> violations = new ArrayList<>();
        for (final JsonNode violation : iterable(data.path("violations"))) {
            violations.add(violation.asText());
        }
        return violations;
    }

    private static Iterable<JsonNode> iterable(final JsonNode array) {
        return array;
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
