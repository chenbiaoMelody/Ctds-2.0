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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private static final Path AUDIT_DIR;
    private static final String REQUESTER = "S-req";
    private static final String PROVIDER = "S-prov";
    private static final String OTHER = "S-other";
    private static final String CONTRACT = "CO000001";
    /** 审计 JSONL 允许的顶层字段集（common-logging AsyncFileAuditRecorder 固定 schema）。 */
    private static final Set<String> AUDIT_FIELDS = Set.of("eventId", "eventTime", "service",
            "actor", "action", "targetType", "targetId", "outcome", "detail", "traceId");
    /** 沙箱审计 detail 允许的业务字段（hifi §8.2 最小必要）。 */
    private static final Set<String> AUDIT_DETAIL_FIELDS = Set.of("contractNo", "jobNo",
            "reasonCode");

    static {
        try {
            STUB = new StubDependencyServer();
            WORKSPACE = Files.createTempDirectory("w354-workspace-");
            JOBS = Files.createTempDirectory("w354-jobs-");
            SOURCE = Files.createTempDirectory("w354-source-");
            AUDIT_DIR = Files.createTempDirectory("w354-audit-");
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
        registry.add("ctds.audit.enabled", () -> "true");
        registry.add("ctds.audit.file-dir", AUDIT_DIR::toString);
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
        clearAuditFiles();
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
        assertThat(data.path("cpus").asDouble()).isEqualTo(1.0);
        assertThat(data.path("memoryMb").asInt()).isEqualTo(512);
        assertThat(data.path("ttlSeconds").asInt()).isEqualTo(7200);
        assertThat(data.path("expiresAt").asText()).isNotBlank();
        assertThat(RUNTIME.launches()).hasSize(1);
        final Path taskRoot = WORKSPACE.resolve(sandboxNo);
        final com.ctds.delivery.domain.SandboxSpec spec = RUNTIME.launches().get(0);
        assertThat(spec.sandboxNo()).isEqualTo(sandboxNo);
        assertThat(spec.inputDir().getFileName().toString()).isEqualTo("input");
        assertThat(spec.inputDir().getParent().getFileName().toString()).isEqualTo(sandboxNo);
        assertThat(spec.outputDir().getFileName().toString()).isEqualTo("output");
        assertThat(spec.jobsDir()).isEqualTo(JOBS.toRealPath());
        assertThat(spec.image()).isEqualTo("python:3.12-alpine");
        assertThat(spec.cpus()).isEqualTo(1.0);
        assertThat(spec.memoryMb()).isEqualTo(512);
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
        assertActionLog("sandbox.denied", "DENIED", "1008C0013", 1);
    }

    @Test
    void nonParticipantContractPassThrough() throws Exception {
        STUB.respondSummaryError("1008C0012", "合约不存在或不可见", 404);
        final MvcResult result = createSandbox("idem-visible-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1008C0012");
        assertActionLog("sandbox.denied", "DENIED", "1008C0012", 1);
    }

    @Test
    void providerCannotCreateSandbox() throws Exception {
        final MvcResult result = createSandbox("idem-provider-1", PROVIDER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
        assertThat(countTasks()).isZero();
        assertThat(RUNTIME.launches()).isEmpty();
        assertActionLog("sandbox.denied", "DENIED", "1009C0003", 1);
    }

    @Test
    void apiProductFormRejectedByG1Rule() throws Exception {
        STUB.respondProduct(13, "API");
        final MvcResult result = createSandbox("idem-api-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0006");
        assertThat(countTasks()).isZero();
        assertThat(RUNTIME.launches()).isEmpty();
        assertActionLog("sandbox.denied", "DENIED", "1009C0006", 1);
    }

    @Test
    void reportAndModelFormsAreAccepted() throws Exception {
        STUB.respondProduct(13, "REPORT");
        assertThat(createAndGetNo("idem-report-1")).matches("SB\\d{6}");
        STUB.respondProduct(13, "MODEL");
        assertThat(createAndGetNo("idem-model-1")).matches("SB\\d{6}");
        assertThat(countTasks()).isEqualTo(2);
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
        assertActionLog("sandbox.denied", "DENIED", "1009C0005", 2);
    }

    @Test
    void noRegisteredDataFilesFailsClosed() throws Exception {
        STUB.respondSummary(CONTRACT, 99, "无数据产品", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(99, "DATASET");
        final MvcResult result = createSandbox("idem-nodata-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0004");
        assertThat(countTasks()).isZero();
        assertActionLog("sandbox.create", "FAILURE", "1009S0004", 1);
    }

    @Test
    void contractServiceUnavailableFailsClosed() throws Exception {
        STUB.respondSummaryRaw("", 502);
        final MvcResult result = createSandbox("idem-down-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(countTasks()).isZero();
        assertActionLog("sandbox.create", "FAILURE", "1009S0002", 1);
    }

    @Test
    void requesterQuotaExceeded() throws Exception {
        insertTask("SB900001", REQUESTER, "RUNNING", LocalDateTime.now().plusHours(1));
        insertTask("SB900002", REQUESTER, "RUNNING", LocalDateTime.now().plusHours(1));
        final MvcResult result = createSandbox("idem-quota-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(codeOf(result)).isEqualTo("1009C0013");
        assertThat(RUNTIME.launches()).isEmpty();
        assertActionLog("sandbox.denied", "DENIED", "1009C0013", 1);
    }

    @Test
    void globalQuotaExceeded() throws Exception {
        // 全局上限 8、单需求方上限 2：8 个活跃任务分布在 4 个其他需求方，S-req 自身 0 个
        for (int i = 0; i < 8; i++) {
            insertTask(String.format("SB%06d", 800001 + i), "S-bulk-" + (i / 2), "RUNNING",
                    LocalDateTime.now().plusHours(1));
        }
        final MvcResult result = createSandbox("idem-global-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(codeOf(result)).isEqualTo("1009C0013");
        assertThat(RUNTIME.launches()).isEmpty();
        assertActionLog("sandbox.denied", "DENIED", "1009C0013", 1);
    }

    @Test
    void concurrentSameKeyCreatesSingleSandboxWithoutDuplicateRows() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final Callable<MvcResult> request = () -> {
                start.await();
                return createSandbox("idem-concurrent-1", REQUESTER);
            };
            final Future<MvcResult> first = pool.submit(request);
            final Future<MvcResult> second = pool.submit(request);
            start.countDown();
            final int firstStatus = first.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            final int secondStatus = second.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            // 幂等切面串行化同键：首个 200；另一个为 200（重放）或 409（处理中），不得出现 5xx
            assertThat(List.of(firstStatus, secondStatus)).contains(200);
            assertThat(List.of(firstStatus, secondStatus)).allMatch(status -> status < 500);
            assertThat(countTasks()).isEqualTo(1);
            assertThat(RUNTIME.launches()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
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
        assertActionLog("sandbox.create", "FAILURE", "1009S0005", 1);
    }

    @Test
    void runtimeUnavailablePassThrough() throws Exception {
        RUNTIME.setUnavailable(true);
        final MvcResult result = createSandbox("idem-unavailable-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0004");
        assertThat(countTasks()).isZero();
        assertActionLog("sandbox.create", "FAILURE", "1009S0004", 1);
    }

    // ---------- 生命周期组 ----------

    @Test
    void closeStopsContainerAndMarksStopped() throws Exception {
        final String sandboxNo = createAndGetNo("idem-close-1");
        final MvcResult closed = close(sandboxNo, REQUESTER);
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(closed).path("status").asText()).isEqualTo("STOPPED");
        assertThat(dataOf(closed).path("closeReason").asText()).isEqualTo("CLOSED");
        assertThat(RUNTIME.stopCount()).isEqualTo(1);
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
        assertActionLog("sandbox.denied", "DENIED", "1009C0003", 1);
    }

    @Test
    void nonParticipantCloseIsNotFound() throws Exception {
        final String sandboxNo = createAndGetNo("idem-close-4");
        final MvcResult result = close(sandboxNo, OTHER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0010");
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 1);
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
        assertActionLog("sandbox.expire", "SUCCESS", null, 1);
        assertActionLog("sandbox.denied", "DENIED", "1009C0011", 1);
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
        assertThat(RUNTIME.lastTimeout()).isEqualTo(Duration.ofSeconds(120));
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
        assertActionLog("sandbox.denied", "DENIED", "1009C0014", 1);
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
        assertActionLog("sandbox.denied", "DENIED", "1008C0020", 1);
    }

    @Test
    void territoryViolationPassThroughWithoutRecord() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-territory-1");
        STUB.respondCheckError("1008C0020", "地域超出合约约定", 403);
        final MvcResult result = mockMvc.perform(
                        post("/api/v1/sandboxes/{no}/actions/run-job", sandboxNo)
                                .header("X-Ctds-Subject", REQUESTER)
                                .header("X-Ctds-Roles", "requester")
                                .header("X-Idempotency-Key", "idem-job-territory-2")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"jobId\":\"dataset-profile\",\"purpose\":\"风控建模\","
                                        + "\"territory\":\"境外\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1008C0020");
        assertThat(STUB.lastCheckBody()).contains("\"territory\":\"境外\"");
        assertThat(countJobs()).isZero();
        assertThat(RUNTIME.execCount()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1008C0020", 1);
    }

    @Test
    void contractUnavailableLeavesNoJobRecord() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-4");
        STUB.respondCheckError("500", "boom", 500);
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-down-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(codeOf(result)).isEqualTo("1009S0002");
        assertThat(countJobs()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009S0002", 1);
    }

    @Test
    void jobTimeoutRecorded() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-5");
        RUNTIME.setNextResult(new JobExecResult(124, true, 120000L));
        final MvcResult result = runJob(sandboxNo, "input-inventory", "idem-job-timeout-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(dataOf(result).path("exitCode").asInt()).isEqualTo(124);
        assertThat(RUNTIME.lastTimeout()).isEqualTo(Duration.ofSeconds(60));
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
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 1);
    }

    @Test
    void runJobAfterCloseRejected() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-9");
        close(sandboxNo, REQUESTER);
        final MvcResult result = runJob(sandboxNo, "dataset-profile", "idem-job-closed-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0011");
        assertThat(RUNTIME.execCount()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0011", 1);
    }

    @Test
    void jobHistoryExposesStatusOnlyWithinParticipant() throws Exception {
        final String sandboxNo = createAndGetNo("idem-job-history-1");
        runJob(sandboxNo, "dataset-profile", "idem-job-history-run-1", REQUESTER);
        final MvcResult own = mockMvc.perform(get("/api/v1/sandboxes/{no}/jobs", sandboxNo)
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(own).path("total").asLong()).isEqualTo(1);
        final JsonNode job = dataOf(own).path("list").get(0);
        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.has("command")).isFalse();
        assertThat(own.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("python3", "/work/input", WORKSPACE.toString());
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
        assertActionLog("sandbox.data.denied", "DENIED", "1009C0012", 1);
    }

    @Test
    void dataOutputDirectAccessDenied() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-2");
        final MvcResult result = dataAccess(sandboxNo + "/data/output/profile.md", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0012");
        assertActionLog("sandbox.data.denied", "DENIED", "1009C0012", 1);
    }

    @Test
    void dataPathTraversalDenied() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-3");
        final MvcResult result = dataAccess(sandboxNo + "/data/..%2F..%2Fetc%2Fpasswd", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0012");
        assertActionLog("sandbox.data.denied", "DENIED", "1009C0012", 1);
    }

    @Test
    void dataPathCaseDoubleEncodingAndTrailingSlashVariantsDenied() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-5");
        for (final String path : List.of(sandboxNo + "/data/INPUT/sample-dataset",
                sandboxNo + "/data/..%252F..%252Fetc%252Fpasswd",
                sandboxNo + "/data/input/sample-dataset/")) {
            final MvcResult result = dataAccess(path, REQUESTER);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(codeOf(result)).isEqualTo("1009C0012");
        }
        assertActionLog("sandbox.data.denied", "DENIED", "1009C0012", 3);
    }

    @Test
    void dataAccessByNonParticipantIsNotFound() throws Exception {
        final String sandboxNo = createAndGetNo("idem-data-4");
        final MvcResult result = dataAccess(sandboxNo + "/data/input/sample-dataset", OTHER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0010");
        assertActionLog("sandbox.data.denied", "DENIED", "1009C0012", 1);
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
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 1);
    }

    @Test
    void nonParticipantAndMissingResponsesShareCodeAndMessage() throws Exception {
        final String sandboxNo = createAndGetNo("idem-enum-1");
        final JsonNode notVisible = MAPPER.readTree(mockMvc.perform(
                        get("/api/v1/sandboxes/{no}", sandboxNo)
                                .header("X-Ctds-Subject", OTHER)
                                .header("X-Ctds-Roles", "requester"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        final JsonNode missing = MAPPER.readTree(mockMvc.perform(
                        get("/api/v1/sandboxes/{no}", "SB999998")
                                .header("X-Ctds-Subject", OTHER)
                                .header("X-Ctds-Roles", "requester"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(notVisible.path("code").asText()).isEqualTo("1009C0010");
        assertThat(missing.path("code").asText()).isEqualTo(notVisible.path("code").asText());
        assertThat(missing.path("message").asText())
                .isEqualTo(notVisible.path("message").asText())
                .isEqualTo("沙箱任务不存在或无权查看");
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 2);
    }

    @Test
    void nonParticipantAndMissingJobResponsesShareCodeAndMessage() throws Exception {
        final String sandboxNo = createAndGetNo("idem-enum-2");
        final JsonNode notVisible = MAPPER.readTree(runJob(sandboxNo, "dataset-profile",
                        "idem-enum-job-1", OTHER)
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        final JsonNode missing = MAPPER.readTree(runJob("SB999997", "dataset-profile",
                        "idem-enum-job-2", OTHER)
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(notVisible.path("code").asText()).isEqualTo("1009C0010");
        assertThat(missing.path("code").asText()).isEqualTo(notVisible.path("code").asText());
        assertThat(missing.path("message").asText())
                .isEqualTo(notVisible.path("message").asText())
                .isEqualTo("沙箱任务不存在或无权查看");
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 2);
    }

    @Test
    void auditJsonlKeepsMinimalFieldSetOnly() throws Exception {
        final String sandboxNo = createAndGetNo("idem-audit-jsonl-1");
        runJob(sandboxNo, "dataset-profile", "idem-audit-jsonl-job-1", REQUESTER);
        dataAccess(sandboxNo + "/data/input/sample-dataset", REQUESTER);

        final List<JsonNode> events = awaitSandboxAuditEvents(sandboxNo,
                Set.of("sandbox.create", "sandbox.job", "sandbox.data.denied"));
        assertThat(actionsOf(events)).contains("sandbox.create", "sandbox.job",
                "sandbox.data.denied");
        for (final JsonNode event : events) {
            assertThat(fieldNames(event)).isSubsetOf(AUDIT_FIELDS);
            assertThat(event.path("service").asText()).isNotBlank();
            assertThat(event.path("actor").asText()).isNotBlank();
            if (!event.path("detail").isNull() && !event.path("detail").isMissingNode()) {
                assertThat(fieldNames(event.path("detail"))).isSubsetOf(AUDIT_DETAIL_FIELDS);
            }
        }
        final String raw = readAuditRaw();
        assertThat(raw).doesNotContain(WORKSPACE.toString(), SOURCE.toString(), JOBS.toString(),
                "ctds-sb-", "python:3.12-alpine", "python3", "/work/input", "sample-dataset");
    }

    @Test
    void providerCanReadDetailOnly() throws Exception {
        final String sandboxNo = createAndGetNo("idem-gov-4");
        final MvcResult detail = mockMvc.perform(get("/api/v1/sandboxes/{no}", sandboxNo)
                .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "provider")).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(detail).path("sandboxNo").asText()).isEqualTo(sandboxNo);
        assertThat(close(sandboxNo, PROVIDER).getResponse().getStatus()).isEqualTo(403);
        assertThat(runJob(sandboxNo, "dataset-profile", "idem-gov-4-job", PROVIDER)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(RUNTIME.execCount()).isZero();
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

    /** 拒绝 / 失败留痕断言（action + result + reasonCode；reasonCode 为空时按 IS NULL 比对）。 */
    private void assertActionLog(final String action, final String result, final String reasonCode,
            final int expected) {
        final int actual = reasonCode == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_action_log WHERE action = ? "
                        + "AND result = ? AND reason_code IS NULL", Integer.class, action, result)
                : jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_action_log WHERE action = ? "
                        + "AND result = ? AND reason_code = ?", Integer.class, action, result,
                        reasonCode);
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * 轮询等待"本沙箱"的审计事件（异步写线程，最多 15s）。
     * 以 targetId = sandboxNo 过滤，避免上一用例的延迟落盘事件提前满足条件。
     */
    private List<JsonNode> awaitSandboxAuditEvents(final String sandboxNo,
            final Set<String> required) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<JsonNode> events = sandboxAuditEventsOf(sandboxNo);
        while (!actionsOf(events).containsAll(required) && System.nanoTime() < deadline) {
            Thread.sleep(100L);
            events = sandboxAuditEventsOf(sandboxNo);
        }
        return events;
    }

    private static List<JsonNode> sandboxAuditEventsOf(final String sandboxNo) throws Exception {
        final List<JsonNode> events = new ArrayList<>();
        for (final JsonNode event : readAuditEvents()) {
            if (event.path("action").asText().startsWith("sandbox.")
                    && sandboxNo.equals(event.path("targetId").asText())) {
                events.add(event);
            }
        }
        return events;
    }

    private static Set<String> actionsOf(final List<JsonNode> events) {
        final Set<String> actions = new LinkedHashSet<>();
        events.forEach(event -> actions.add(event.path("action").asText()));
        return actions;
    }

    private static List<JsonNode> readAuditEvents() throws Exception {
        final List<JsonNode> events = new ArrayList<>();
        for (final Path file : auditFiles()) {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    try {
                        events.add(MAPPER.readTree(line));
                    } catch (final Exception ignored) {
                        // 异步写线程可能正在追加当前行：跳过不完整行，下一轮轮询重读
                    }
                }
            }
        }
        return events;
    }

    private static String readAuditRaw() throws Exception {
        final StringBuilder raw = new StringBuilder();
        for (final Path file : auditFiles()) {
            raw.append(Files.readString(file, StandardCharsets.UTF_8));
        }
        return raw.toString();
    }

    private static List<Path> auditFiles() throws Exception {
        if (!Files.isDirectory(AUDIT_DIR)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(AUDIT_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList();
        }
    }

    private static void clearAuditFiles() {
        try {
            auditFiles().forEach(SandboxIntegrationTest::deleteRecursively);
        } catch (final Exception ignored) {
            // best-effort
        }
    }

    private static Set<String> fieldNames(final JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Set.of();
        }
        final Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
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
