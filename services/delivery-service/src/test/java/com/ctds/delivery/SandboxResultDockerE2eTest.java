package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.delivery.infrastructure.DefaultProcessRunner;
import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 真 Docker 端到端（WBS-3.5.5 hifi §8）：<b>默认跳过</b>，走查段以 {@code CTDS_SANDBOX_E2E=1}
 * 启用并留痕。真容器跑作业产出结果物 → 提交审核 → 治理审核通过 → 导出 → 领取加密包 →
 * 解密复核 SM3（一次导出全链，密文出服务、明文只在需求方侧复核）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ctds.sandbox.sweep.fixed-delay-ms=3600000",
        "ctds.sandbox.runtime.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "CTDS_SANDBOX_E2E", matches = "1")
class SandboxResultDockerE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUESTER = "E-req";
    private static final String PROVIDER = "E-prov";
    private static final String ADMIN = "E-admin";
    private static final String CONTRACT = "CO000009";
    private static final String RESULT_BODY = "e2e-result-355";
    private static final String KEY_REF = "delivery-file";
    private static final StubDependencyServer STUB;
    private static final Path SOURCE;
    private static final Path WORKSPACE;
    private static final Path JOBS;
    private static final Path STORAGE;
    private static final Path AUDIT_DIR;
    private static final Path KEY_FILE;
    /** W-2 收尾锚点：用例创建的沙箱号（@AfterAll best-effort 移除真容器，防本机残留）。 */
    private static volatile String createdSandboxNo;

    static {
        try {
            STUB = new StubDependencyServer();
            SOURCE = Files.createTempDirectory("w355e2e-source-");
            WORKSPACE = Files.createTempDirectory("w355e2e-workspace-");
            JOBS = Files.createTempDirectory("w355e2e-jobs-");
            STORAGE = Files.createTempDirectory("w355e2e-storage-");
            AUDIT_DIR = Files.createTempDirectory("w355e2e-audit-");
            KEY_FILE = Files.createTempFile("w355e2e-keys-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, KEY_REF + "=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(SOURCE.resolve("sample.csv"), "a,b\n1,2\n", StandardCharsets.UTF_8);
            Files.writeString(JOBS.resolve("dataset_profile.py"),
                    "import sys\nwith open(sys.argv[2], 'w') as f:\n    f.write('"
                            + RESULT_BODY + "')\n", StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_result_e2e");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.source-root", SOURCE::toString);
        registry.add("ctds.delivery.storage-root", STORAGE::toString);
        registry.add("ctds.sandbox.workspace-root", WORKSPACE::toString);
        registry.add("ctds.sandbox.jobs-root", JOBS::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
        registry.add("ctds.audit.enabled", () -> "true");
        registry.add("ctds.audit.file-dir", AUDIT_DIR::toString);
        registry.add("ctds.delivery.files[0].file-id", () -> "sample-dataset");
        registry.add("ctds.delivery.files[0].product-id", () -> 13L);
        registry.add("ctds.delivery.files[0].path", () -> "sample.csv");
        registry.add("ctds.delivery.files[0].display-name", () -> "演示数据集.csv");
    }

    @AfterAll
    static void stop() {
        STUB.stop();
        removeSandboxContainerQuietly();
    }

    /** W-2 收尾：best-effort 移除用例创建的真容器（运行时命名 ctds-sb-{no}），防本机残留。 */
    private static void removeSandboxContainerQuietly() {
        if (createdSandboxNo == null) {
            return;
        }
        try {
            new DefaultProcessRunner().run(
                    List.of("docker", "rm", "-f", "ctds-sb-" + createdSandboxNo),
                    Duration.ofSeconds(30), true);
        } catch (final RuntimeException ignored) {
            // 清理 best-effort（容器不存在等场景）
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Sm3Service sm3Service;
    @Autowired
    private Sm4Service sm4Service;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM sandbox_result_review");
        jdbc.update("DELETE FROM sandbox_result");
        jdbc.update("DELETE FROM sandbox_action_log");
        jdbc.update("DELETE FROM sandbox_job");
        jdbc.update("DELETE FROM sandbox_task");
        jdbc.update("DELETE FROM delivery");
        jdbc.update("UPDATE delivery_no_seq SET next_no = 1 WHERE id = 1");
        STUB.resetCounters();
        STUB.respondSummary(CONTRACT, 13, "演示数据集", REQUESTER, PROVIDER, "EFFECTIVE");
        STUB.respondProduct(13, "DATASET");
        STUB.respondCheckAllowed();
    }

    @Test
    void resultSubmitReviewExportDownloadRoundTrip() throws Exception {
        // 1. 开通沙箱（真容器）+ 受控作业产出结果物
        final MvcResult created = mockMvc.perform(post("/api/v1/sandboxes")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "e2e-create-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contractNo\":\"" + CONTRACT + "\"}")).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        final String sandboxNo = dataOf(created).path("sandboxNo").asText();
        createdSandboxNo = sandboxNo;

        final MvcResult job = mockMvc.perform(post("/api/v1/sandboxes/{no}/actions/run-job",
                sandboxNo).header("X-Ctds-Subject", REQUESTER)
                .header("X-Ctds-Roles", "requester").header("X-Idempotency-Key", "e2e-job-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"dataset-profile\",\"purpose\":\"风控建模\"}")).andReturn();
        assertThat(job.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(job).path("status").asText()).isEqualTo("SUCCEEDED");
        // 作业链自身一次策略计数（3.5.4 作业计数规则），导出计数断言以此为基线
        final int checksAfterJob = STUB.checkCount();

        // 2. 提交审核 → 治理审核通过 → 导出（策略放行一次计数）
        final MvcResult submitted = mockMvc.perform(post(
                "/api/v1/sandboxes/{no}/results/{file}/actions/submit-review", sandboxNo,
                "dataset-profile.md").header("X-Ctds-Subject", REQUESTER)
                .header("X-Ctds-Roles", "requester").header("X-Idempotency-Key", "e2e-submit-1"))
                .andReturn();
        assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(submitted).path("status").asText()).isEqualTo("PENDING");

        final MvcResult reviewed = mockMvc.perform(post(
                "/api/v1/sandboxes/governance/{no}/results/{file}/actions/review", sandboxNo,
                "dataset-profile.md").header("X-Ctds-Subject", ADMIN)
                .header("X-Ctds-Roles", "admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"APPROVE\"}")).andReturn();
        assertThat(reviewed.getResponse().getStatus()).isEqualTo(200);

        final MvcResult exported = mockMvc.perform(post(
                "/api/v1/sandboxes/{no}/results/{file}/actions/export", sandboxNo,
                "dataset-profile.md").header("X-Ctds-Subject", REQUESTER)
                .header("X-Ctds-Roles", "requester").header("X-Idempotency-Key", "e2e-export-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"风控建模\"}")).andReturn();
        assertThat(exported.getResponse().getStatus()).isEqualTo(200);
        assertThat(STUB.checkCount()).isEqualTo(checksAfterJob + 1); // 导出恰 +1（W-1 修正：基线为作业腿）
        final String deliveryNo = dataOf(exported).path("deliveryNo").asText();
        final String checksum = dataOf(exported).path("checksum").asText();

        // 3. 领取加密包 → 需求方侧解密复核 SM3（密文出服务，明文不出现在任何留痕）
        final MvcResult download = mockMvc.perform(get("/api/v1/deliveries/{no}/package",
                deliveryNo).header("X-Ctds-Subject", REQUESTER)
                .header("X-Ctds-Roles", "requester")).andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getHeader("X-Ctds-Sm3")).isEqualTo(checksum);
        final byte[] envelope = download.getResponse().getContentAsByteArray();
        final byte[] plaintext = sm4Service.decrypt(envelope, KEY_REF);
        assertThat(plaintext).isEqualTo(RESULT_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(sm3Service.digestHex(plaintext)).isEqualTo(checksum);
    }

    private static JsonNode dataOf(final MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
    }
}
