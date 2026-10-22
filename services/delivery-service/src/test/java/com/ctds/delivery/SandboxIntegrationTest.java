package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.delivery.application.SandboxLifecycleService;
import com.ctds.delivery.domain.JobExecResult;
import com.ctds.delivery.domain.SandboxRuntime;
import com.ctds.delivery.support.FakeSandboxRuntime;
import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 沙箱集成测试（hifi §9 准入 12 / 生命周期 10 / 作业与策略 10 / 数据面 4 / 留痕 4）：
 * 真实 MySQL 容器 + 合约/目录 stub + Fake 运行时（CI 主路径不依赖 Docker）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ctds.audit.enabled=false",
            "ctds.sandbox.sweep.fixed-delay-ms=3600000",
            "ctds.sandbox.runtime.enabled=true",
            "ctds.sandbox.quota.max-active-per-requester=2",
            "ctds.sandbox.quota.max-active-global=8"
        })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@Import(SandboxIntegrationTest.FakeRuntimeConfig.class)
class SandboxIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FakeSandboxRuntime RUNTIME = new FakeSandboxRuntime();
    private static final StubDependencyServer STUB;
    private static final Path WORKSPACE;
    private static final Path JOBS;
    private static final Path SOURCE;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String OTHER = "S-other";
    private static final String CONTRACT = "CO000001";

    static {
        try {
            STUB = new StubDependencyServer();
            WORKSPACE = Files.createTempDirectory("w354-workspace-");
            JOBS = Files.createTempDirectory("w354-jobs-");
            SOURCE = Files.createTempDirectory("w354-source-");
            Files.writeString(SOURCE.resolve("sample.csv"), "a,b\n1,2\n", StandardCharsets.UTF_8);
            Files.writeString(SOURCE.resolve("sample2.csv"), "x,y\n3,4\n", StandardCharsets.UTF_8);
            Files.createDirectories(SOURCE.resolve("store"));
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_sandbox_it");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.source-root", SOURCE::toString);
        registry.add("ctds.delivery.storage-root", () -> SOURCE.resolve("store").toString());
        registry.add("ctds.sandbox.workspace-root", WORKSPACE::toString);
        registry.add("ctds.sandbox.jobs-root", JOBS::toString);
        registry.add("ctds.delivery.files[0].file-id", () -> "sample-dataset");
        registry.add("ctds.delivery.files[0].product-id", () -> 13L);
        registry.add("ctds.delivery.files[0].path", () -> "sample.csv");
        registry.add("ctds.delivery.files[0].display-name", () -> "演示数据集.csv");
        registry.add("ctds.delivery.files[1].file-id", () -> "sample-dataset-2");
        registry.add("ctds.delivery.files[1].product-id", () -> 13L);
        registry.add("ctds.delivery.files[1].path", () -> "sample2.csv");
        registry.add("ctds.delivery.files[1].display-name", () -> "演示数据集2.csv");
    }

    @TestConfiguration
    static class FakeRuntimeConfig {
        @Bean
        @Primary
        SandboxRuntime fakeSandboxRuntime() {
            return RUNTIME;
        }
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SandboxLifecycleService lifecycleService;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM sandbox_task");
        jdbc.update("DELETE FROM sandbox_job");
        jdbc.update("DELETE FROM sandbox_action_log");
        jdbc.update("UPDATE sandbox_no_seq SET next_no = 1 WHERE id = 1");
        jdbc.update("UPDATE sandbox_job_no_seq SET next_no = 1 WHERE id = 1");
        RUNTIME.reset();
        STUB.resetCounters();
        STUB.respondSummary(CONTRACT, 13, "演示数据集", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(13, "DATASET");
        STUB.respondCheckAllowed();
        try (Stream<Path> paths = Files.list(WORKSPACE)) {
            paths.forEach(path -> deleteRecursively(path));
        }
    }

    // ---------- 准入组 ----------

    @Test
    void createSandboxCopiesInputsAndLaunchesContainer() throws Exception {
        final MvcResult result = createSandbox("idem-create-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        final String sandboxNo = data.path("sandboxNo").asText();
        assertThat(sandboxNo).matches("SB\\d{6}");
        assertThat(data.path("status").asText()).isEqualTo("RUNNING");
        assertThat(data.path("dataFileCount").asInt()).isEqualTo(2);
        assertThat(data.path("contractNo").asText()).isEqualTo(CONTRACT);
        assertThat(data.has("containerId")).isFalse();
        assertThat(RUNTIME.launches()).hasSize(1);
        final Path taskRoot = WORKSPACE.resolve(sandboxNo);
        assertThat(taskRoot.resolve("input").resolve("sample-dataset")).exists();
        assertThat(taskRoot.resolve("input").resolve("manifest.tsv")).exists();
        assertThat(taskRoot.resolve("output")).isDirectory();
        assertThat(countTasks()).isEqualTo(1);
        assertThat(countActionLogs("sandbox.create")).isEqualTo(1);
    }

    @Test
    void idempotentReplayReturnsSameSandboxWithoutSecondLaunch() throws Exception {
        final String first = dataOf(createSandbox("idem-replay-1", REQUESTER))
                .path("sandboxNo").asText();
        final MvcResult replay = createSandbox("idem-replay-1", REQUESTER);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(replay).path("sandboxNo").asText()).isEqualTo(first);
        assertThat(countTasks()).isEqualTo(1);
        assertThat(RUNTIME.launches()).hasSize(1);
    }

    @Test
    void nonEffectiveContractPassThrough() throws Exception {
        STUB.respondSummaryError("1008C0013", "合约当前状态不允许该操作", 409);
        final MvcResult result = createSandbox("idem-state-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1008C0013");
        assertThat(countTasks()).isZero();
    }

    @Test
    void nonParticipantContractPassThrough() throws Exception {
        STUB.respondSummaryError("1008C0012", "合约不存在或不可见", 404);
        final MvcResult result = createSandbox("idem-visible-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1008C0012");
    }

    @Test
    void providerCannotCreateSandbox() throws Exception {
        final MvcResult result = createSandbox("idem-provider-1", PROVIDER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
        assertThat(countTasks()).isZero();
        assertThat(RUNTIME.launches()).isEmpty();
    }

    @Test
    void apiProductFormRejectedByG1Rule() throws Exception {
        STUB.respondProduct(13, "API");
        final MvcResult result = createSandbox("idem-api-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0006");
        assertThat(countTasks()).isZero();
        assertThat(RUNTIME.launches()).isEmpty();
    }

    @Test
    void invalidParamsRejected() throws Exception {
        final MvcResult missingContract = mockMvc.perform(post("/api/v1/sandboxes")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "idem-bad-1")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(missingContract.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(missingContract)).isEqualTo("1009C0005");

        final MvcResult badKey = mockMvc.perform(post("/api/v1/sandboxes")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "bad key!")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"" + CONTRACT + "\"}")).andReturn();
        assertThat(badKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badKey)).isEqualTo("1009C0005");
        assertThat(countTasks()).isZero();
    }

    @Test
    void noRegisteredDataFilesFailsClosed() throws Exception {
        STUB.respondSummary(CONTRACT, 99, "无数据产品", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(99, "DATASET");
        final MvcResult result = createSandbox("idem-nodata-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0004");
        assertThat(countTasks()).isZero();
    }

    @Test
    void contractServiceUnavailableFailsClosed() throws Exception {
        STUB.respondSummaryRaw("", 502);
        final MvcResult result = createSandbox("idem-down-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(countTasks()).isZero();
    }

    @Test
    void requesterQuotaExceeded() throws Exception {
        insertTask("SB900001", REQUESTER, "RUNNING", LocalDateTime.now().plusHours(1));
        insertTask("SB900002", REQUESTER, "RUNNING", LocalDateTime.now().plusHours(1));
        final MvcResult result = createSandbox("idem-quota-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(codeOf(result)).isEqualTo("1009C0013");
        assertThat(RUNTIME.launches()).isEmpty();
    }

    @Test
    void launchFailureCleansUpWithoutRecord() throws Exception {
        RUNTIME.setLaunchFails(true);
        final MvcResult result = createSandbox("idem-launch-fail-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).isEqualTo("1009S0005");
        assertThat(countTasks()).isZero();
        try (Stream<Path> paths = Files.list(WORKSPACE)) {
            assertThat(paths.count()).isZero();
        }
    }

    @Test
    void runtimeUnavailablePassThrough() throws Exception {
        RUNTIME.setUnavailable(true);
        final MvcResult result = createSandbox("idem-unavailable-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0004");
        assertThat(countTasks()).isZero();
    }

    // ---------- 生命周期组 ----------

    @Test
    void closeStopsContainerAndMarksStopped() throws Exception {
        final String sandboxNo = createAndGetNo("idem-close-1");
        final MvcResult closed = close(sandboxNo, REQUESTER);
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(closed).path("status").asText()).isEqualTo("STOPPED");
        assertThat(dataOf(closed).path("closeReason").asText()).isEqualTo("CLOSED");
        assertThat(RUNTIME.stopCount()).isGreaterThanOrEqualTo(1);
        assertThat(RUNTIME.isRunning(sandboxNo)).isFalse();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        final String sandboxNo = createAndGetNo("idem-close-2");
        close(sandboxNo, REQUESTER);
        final MvcResult again = close(sandboxNo, REQUESTER);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(again).path("status").asText()).isEqualTo("STOPPED");
    }

    @Test
    void providerCannotClose() throws Exception {
        final String sandboxNo = createAndGetNo("idem-close-3");
        final MvcResult result = close(sandboxNo, PROVIDER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
    }

    @Test
    void nonParticipantCloseIsNotFound() throws Exception {
        final String sandboxNo = createAndGetNo("idem-close-4");
        final MvcResult result = close(sandboxNo, OTHER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0010");
    }

    @Test
    void sweepExpiresOverdueTask() throws Exception {
        final String sandboxNo = createAndGetNo("idem-expire-1");
        jdbc.update("UPDATE sandbox_task SET expires_at = ? WHERE sandbox_no = ?",
                LocalDateTime.now().minusMinutes(1), sandboxNo);
        lifecycleService.sweepExpired();
        assertThat(statusOf(sandboxNo)).isEqualTo("EXPIRED");
        assertThat(RUNTIME.isRunning(sandboxNo)).isFalse();
        assertThat(countActionLogs("sandbox.expire")).isEqualTo(1);
    }

    @Test
    void runJobAfterExpiryRejectsAndMarksExpired() throws Exception {
        final String sandboxNo = createAndGetNo("idem-expire-2");
        jdbc.update("UPDATE sandbox_task SET expires_at = ? WHERE sandbox_no = ?",
                LocalDateTime.now().minusMinutes(1), sandboxNo);
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-expired-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0011");
        assertThat(statusOf(sandboxNo)).isEqualTo("EXPIRED");
        assertThat(RUNTIME.execCount()).isZero();
    }

    @Test
    void reconcileMarksMissingContainerStopped() throws Exception {
        final String sandboxNo = createAndGetNo("idem-reconcile-1");
        jdbc.update("UPDATE sandbox_task SET expires_at = ? WHERE sandbox_no = ?",
                LocalDateTime.now().plusHours(1), sandboxNo);
        RUNTIME.stop(sandboxNo);
        lifecycleService.reconcile();
        assertThat(statusOf(sandboxNo)).isEqualTo("STOPPED");
        assertThat(closeReasonOf(sandboxNo)).isEqualTo("RECONCILED");
        assertThat(countActionLogs("sandbox.reconcile")).isEqualTo(1);
    }

    // ---------- 作业与策略组 ----------

    @Test
    void runJobSucceedsAndCountsOnce() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-1");
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-run-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        assertThat(data.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(data.path("jobNo").asText()).matches("SJ\\d{6}");
        assertThat(data.path("exitCode").asInt()).isZero();
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(STUB.lastCheckBody()).contains("USE", CONTRACT);
        assertThat(countJobs()).isEqualTo(1);
        assertThat(RUNTIME.lastCommand()).contains("python3").contains("/work/input");
        assertThat(countActionLogs("sandbox.job")).isEqualTo(1);
    }

    @Test
    void unknownJobRejectedWithoutCounting() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-2");
        final MvcResult result = runJob(sandboxNo, "rm-rf", "idem-job-unknown-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0014");
        assertThat(STUB.checkCount()).isZero();
        assertThat(countJobs()).isZero();
        assertThat(RUNTIME.execCount()).isZero();
    }

    @Test
    void policyViolationPassThroughWithoutRecord() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-3");
        STUB.respondCheckError("1008C0020", "用途超出合约约定", 403);
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-policy-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1008C0020");
        assertThat(countJobs()).isZero();
        assertThat(RUNTIME.execCount()).isZero();
    }

    @Test
    void contractUnavailableLeavesNoJobRecord() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-4");
        STUB.respondCheckError("500", "boom", 500);
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-down-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(countJobs()).isZero();
    }

    @Test
    void jobTimeoutRecorded() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-5");
        RUNTIME.setNextResult(new JobExecResult(124, true, 120000L));
        final MvcResult result = runJob(sandboxNo, "input-inventory", "idem-job-timeout-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(dataOf(result).path("exitCode").asInt()).isEqualTo(124);
    }

    @Test
    void jobNonZeroExitRecordedAsFailed() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-6");
        RUNTIME.setNextResult(new JobExecResult(2, false, 30L));
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-fail-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("status").asText()).isEqualTo("FAILED");
        assertThat(dataOf(result).path("exitCode").asInt()).isEqualTo(2);
    }

    @Test
    void jobIdempotentReplayDoesNotDoubleCount() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-7");
        final String first = dataOf(runJob(sandboxNo, "dataset-profile", "idem-job-replay-1",
                REQUESTER)).path("jobNo").asText();
        final MvcResult replay = runJob(sandboxNo, "dataset-profile", "idem-job-replay-1", REQUESTER);
        assertThat(dataOf(replay).path("jobNo").asText()).isEqualTo(first);
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(countJobs()).isEqualTo(1);
        assertThat(RUNTIME.execCount()).isEqualTo(1);
    }

    @Test
    void crossSandboxJobIsNotFound() throws Exception {
        createAndGetNo("idem-job-8");
        final MvcResult result = runJob("SB999999", "dataset-profile", "idem-job-cross-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0010");
    }

    @Test
    void runJobAfterCloseRejected() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-9");
        close(sandboxNo, REQUESTER);
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-closed-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0011");
        assertThat(RUNTIME.execCount()).isZero();
    }

    @Test
    void resultsExposeMetadataOnlyWithSm3() throws Exception {
        final String sandboxNo = createAndGetNo("idem-result-1");
        Files.writeString(WORKSPACE.resolve(sandboxNo).resolve("output").resolve("profile.md"),
                "result-body", StandardCharsets.UTF_8);
        final MvcResult result = mockMvc.perform(get("/api/v1/sandboxes/{no}/results", sandboxNo)
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode first = dataOf(result).get(0);
        assertThat(first.path("fileName").asText()).isEqualTo("profile.md");
        assertThat(first.path("sizeBytes").asInt()).isEqualTo(11);
        assertThat(first.path("checksum").asText()).matches("[0-9a-f]{64}");
        assertThat(first.has("content")).isFalse();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("result-body", WORKSPACE.toString());
    }

    // ---------- 数据面组 ----------

    @Test
    void dataInputDirectAccessDenied() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-1");
        final MvcResult result = dataAccess(sandboxNo + "/data/input/sample-dataset", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0012");
        assertThat(countActionLogs("sandbox.data.denied")).isEqualTo(1);
    }

    @Test
    void dataOutputDirectAccessDenied() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-2");
        final MvcResult result = dataAccess(sandboxNo + "/data/output/profile.md", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0012");
    }

    @Test
    void dataPathTraversalDenied() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-3");
        final MvcResult result = dataAccess(sandboxNo + "/data/..%2F..%2Fetc%2Fpasswd", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void dataAccessByNonParticipantIsNotFound() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-4");
        final MvcResult result = dataAccess(sandboxNo + "/data/input/sample-dataset", OTHER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0010");
    }

    // ---------- 留痕与无本体组 ----------

    @Test
    void actionLogKeepsBusinessFieldsOnly() throws Exception {
        final String sandboxNo = createAndGetNo("idem-audit-1");
        runJob(sandboxNo, "dataset-profile", "idem-audit-job-1", REQUESTER);
        final List<String> rows = jdbc.queryForList(
                "SELECT CONCAT(operator_no, '|', action, '|', COALESCE(sandbox_no, ''), '|', "
                        + "COALESCE(reason_code, '')) FROM sandbox_action_log", String.class);
        assertThat(rows).isNotEmpty();
        final String joined = String.join("\n", rows);
        assertThat(joined).contains("sandbox.create", "sandbox.job", sandboxNo);
        assertThat(joined).doesNotContain("python3", "ctds-sb-", "python:3.12-alpine",
                WORKSPACE.toString(), "/work/input");
    }

    @Test
    void governanceReadLeavesTrace() throws Exception {
        final String sandboxNo = createAndGetNo("idem-gov-1");
        final MvcResult result = mockMvc.perform(get("/api/v1/sandboxes/governance/{no}", sandboxNo)
                .header("X-Ctds-Subject", "S-admin").header("X-Ctds-Roles", "admin")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("sandboxNo").asText()).isEqualTo(sandboxNo);
        assertThat(countActionLogs("sandbox.governance.read")).isEqualTo(1);
    }

    @Test
    void participantListShowsOwnSandboxesOnly() throws Exception {
        final String sandboxNo = createAndGetNo("idem-list-1");
        final MvcResult own = mockMvc.perform(get("/api/v1/sandboxes")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester"))
                .andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(own).path("total").asLong()).isEqualTo(1);
        assertThat(dataOf(own).path("list").get(0).path("sandboxNo").asText())
                .isEqualTo(sandboxNo);

        final MvcResult other = mockMvc.perform(get("/api/v1/sandboxes")
                .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(other.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(other).path("total").asLong()).isZero();
        assertThat(dataOf(other).path("list")).isEmpty();
    }

    @Test
    void nonParticipantDetailIsNotFound() throws Exception {
        final String sandboxNo = createAndGetNo("idem-gov-2");
        final MvcResult result = mockMvc.perform(get("/api/v1/sandboxes/{no}", sandboxNo)
                .header("X-Ctds-Subject", OTHER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0010");
    }

    @Test
    void detailResponseHasNoInternalDetails() throws Exception {
        final String sandboxNo = createAndGetNo("idem-gov-3");
        final String body = mockMvc.perform(get("/api/v1/sandboxes/{no}", sandboxNo)
                        .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("ctds-sb-", "python:3.12-alpine", WORKSPACE.toString(),
                "sample-dataset");
    }

    // ---------- 辅助 ----------

    private MvcResult createSandbox(final String idempotencyKey, final String subject)
            throws Exception {
        return mockMvc.perform(post("/api/v1/sandboxes")
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"" + CONTRACT + "\"}")).andReturn();
    }

    private String createAndGetNo(final String idempotencyKey) throws Exception {
        final MvcResult result = createSandbox(idempotencyKey, REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return dataOf(result).path("sandboxNo").asText();
    }

    private MvcResult close(final String sandboxNo, final String subject) throws Exception {
        return mockMvc.perform(post("/api/v1/sandboxes/{no}/actions/close", sandboxNo)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester")).andReturn();
    }

    private MvcResult runJob(final String sandboxNo, final String jobId, final String key,
            final String subject) throws Exception {
        return mockMvc.perform(post("/api/v1/sandboxes/{no}/actions/run-job", sandboxNo)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + jobId + "\",\"purpose\":\"风控建模\"}")).andReturn();
    }

    private MvcResult dataAccess(final String path, final String subject) throws Exception {
        return mockMvc.perform(get("/api/v1/sandboxes/" + path)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester")).andReturn();
    }

    private void insertTask(final String sandboxNo, final String requester, final String status,
            final LocalDateTime expiresAt) {
        jdbc.update("INSERT INTO sandbox_task (sandbox_no, contract_no, product_id, product_name, "
                        + "requester_no, provider_no, status, cpus, memory_mb, ttl_seconds, expires_at, "
                        + "data_file_count, idempotency_key) VALUES (?, ?, 13, '演示数据集', ?, ?, ?, "
                        + "1.00, 512, 7200, ?, 2, ?)",
                sandboxNo, CONTRACT, requester, PROVIDER, status, expiresAt, "seed-" + sandboxNo);
    }

    private int countTasks() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_task", Integer.class);
    }

    private int countJobs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_job", Integer.class);
    }

    private int countActionLogs(final String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_action_log WHERE action = ?",
                Integer.class, action);
    }

    private String statusOf(final String sandboxNo) {
        return jdbc.queryForObject("SELECT status FROM sandbox_task WHERE sandbox_no = ?",
                String.class, sandboxNo);
    }

    private String closeReasonOf(final String sandboxNo) {
        return jdbc.queryForObject("SELECT close_reason FROM sandbox_task WHERE sandbox_no = ?",
                String.class, sandboxNo);
    }

    private static JsonNode dataOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }

    private static String codeOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").asText();
    }

    private static void deleteRecursively(final Path path) {
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (final Exception ignored) {
                    // best-effort
                }
            });
        } catch (final Exception ignored) {
            // best-effort
        }
    }
}
