package com.ctds.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ctds.delivery.support.SharedMySqlContainer;
import com.ctds.delivery.support.StubDependencyServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 沙箱结果审核与导出集成测试（WBS-3.5.5 hifi §8）：提交审核 10 / 治理审核 9 / 导出 13 /
 * 读面与防枚举 6 / 留痕与无本体 4 / 配置与迁移 3。真实 MySQL 容器 + 合约 stub；
 * 任务与工作区直接造数（不依赖容器运行时），一切断言走 HTTP + DB 取证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ctds.sandbox.sweep.fixed-delay-ms=3600000",
        "ctds.delivery.file.max-bytes=4096"})
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@Testcontainers(disabledWithoutDocker = true)
class SandboxResultIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final StubDependencyServer STUB;
    private static final Path WORKSPACE;
    private static final Path STORAGE;
    private static final Path AUDIT_DIR;
    private static final Path KEY_FILE;
    private static final String REQUESTER = "R-req";
    private static final String PROVIDER = "R-prov";
    private static final String OTHER = "R-other";
    private static final String ADMIN = "R-admin";
    private static final String CONTRACT = "CO000002";
    private static final String KEY_REF = "delivery-file";
    private static final String SECRET_MARKER = "RESULT-CONTENT-355";
    /** 审计 JSONL 允许的顶层字段集（common-logging AsyncFileAuditRecorder 固定 schema）。 */
    private static final Set<String> AUDIT_FIELDS = Set.of("eventId", "eventTime", "service",
            "actor", "action", "targetType", "targetId", "outcome", "detail", "traceId");
    /** 结果物维度审计 detail 允许的业务字段（hifi §7.2 最小必要）。 */
    private static final Set<String> AUDIT_DETAIL_FIELDS = Set.of("contractNo", "jobNo",
            "reasonCode", "resultName");
    /** 响应面顶层字段白名单（新增字段须显式扩集，防字段膨胀带出敏感面）。 */
    private static final Set<String> SUBMIT_VIEW_FIELDS = Set.of("fileName", "sizeBytes",
            "checksum", "createdAt", "status", "conclusion", "rejectReason", "reviewRound");
    private static final Set<String> EXPORT_VIEW_FIELDS = Set.of("deliveryNo", "deliveryType",
            "contractNo", "sandboxNo", "resultName", "requesterNo", "providerNo", "fileName",
            "fileSize", "checksum", "result", "createdAt");

    static {
        try {
            STUB = new StubDependencyServer();
            WORKSPACE = Files.createTempDirectory("w355-workspace-");
            STORAGE = Files.createTempDirectory("w355-storage-");
            AUDIT_DIR = Files.createTempDirectory("w355-audit-");
            KEY_FILE = Files.createTempFile("w355-keys-", ".keys");
            final String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
            Files.writeString(KEY_FILE, KEY_REF + "=" + key + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("测试支撑初始化失败", e);
        }
    }

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        SharedMySqlContainer.register(registry, "ctds_result_it");
        registry.add("ctds.delivery.contract.base-url", STUB::baseUrl);
        registry.add("ctds.delivery.catalog.base-url", STUB::baseUrl);
        registry.add("ctds.sandbox.workspace-root", WORKSPACE::toString);
        registry.add("ctds.delivery.storage-root", STORAGE::toString);
        registry.add("ctds.crypto.local.key-file", KEY_FILE::toString);
        registry.add("ctds.audit.enabled", () -> "true");
        registry.add("ctds.audit.file-dir", AUDIT_DIR::toString);
    }

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("DELETE FROM sandbox_result_review");
        jdbc.update("DELETE FROM sandbox_result");
        jdbc.update("DELETE FROM sandbox_action_log");
        jdbc.update("DELETE FROM sandbox_task");
        jdbc.update("DELETE FROM delivery");
        jdbc.update("UPDATE delivery_no_seq SET next_no = 1 WHERE id = 1");
        STUB.resetCounters();
        STUB.respondCheckAllowed();
        clearWorkspace();
        clearDir(STORAGE);
        clearAuditFiles();
    }

    // ---------- 提交审核组（hifi §3） ----------

    @Test
    void submitRegistersPendingWithSnapshotRoundAndTrace() throws Exception {
        final String sandboxNo = seedRunningTask("SB100001");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final MvcResult result = submit(sandboxNo, "report.md", "key-submit-1", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        assertThat(data.path("fileName").asText()).isEqualTo("report.md");
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("reviewRound").asInt()).isEqualTo(1);
        assertThat(data.path("sizeBytes").asInt()).isEqualTo(SECRET_MARKER.length());
        assertThat(data.path("checksum").asText()).matches("[0-9a-f]{64}");
        assertThat(data.path("conclusion").isNull()).isTrue();
        assertThat(data.path("rejectReason").isNull()).isTrue();

        assertThat(countResults()).isEqualTo(1);
        final String row = resultRow(sandboxNo, "report.md");
        assertThat(row).contains("PENDING", "1", REQUESTER, "key-submit-1");
        assertThat(row).doesNotContain(SECRET_MARKER);
        assertActionLog("sandbox.result.submit", "SUCCESS", null, 1);
    }

    @Test
    void submitMissingFileIs404ResultLevel() throws Exception {
        final String sandboxNo = seedRunningTask("SB100002");
        final MvcResult result = submit(sandboxNo, "absent.md", "key-submit-2", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0015");
        assertThat(countResults()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0015", 1);
    }

    @Test
    void submitInvalidFileNameIs400() throws Exception {
        final String sandboxNo = seedRunningTask("SB100003");
        final MvcResult result = mockMvc.perform(post(
                        "/api/v1/sandboxes/{no}/results/{file}/actions/submit-review", sandboxNo,
                        "%E6%8A%A5%E5%91%8A.md")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", "key-submit-3")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0005");
        assertThat(countResults()).isZero();
    }

    @Test
    void submitOversizeFileRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB100004");
        writeResult(sandboxNo, "big.bin", "x".repeat(5000));
        final MvcResult result = submit(sandboxNo, "big.bin", "key-submit-4", REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0008");
        assertThat(countResults()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0008", 1);
    }

    @Test
    void submitByProviderRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB100005");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final MvcResult result = submit(sandboxNo, "report.md", "key-submit-5", PROVIDER);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
        assertThat(countResults()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0003", 1);
    }

    @Test
    void submitByNonParticipantSameCodeAndMessageAsMissingTask() throws Exception {
        final String sandboxNo = seedRunningTask("SB100006");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final JsonNode notVisible = MAPPER.readTree(submit(sandboxNo, "report.md",
                "key-submit-6a", OTHER).getResponse().getContentAsString(StandardCharsets.UTF_8));
        final JsonNode missing = MAPPER.readTree(submit("SB999999", "report.md",
                "key-submit-6b", OTHER).getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(notVisible.path("code").asText()).isEqualTo("1009C0010");
        assertThat(missing.path("code").asText()).isEqualTo(notVisible.path("code").asText());
        assertThat(missing.path("message").asText())
                .isEqualTo(notVisible.path("message").asText())
                .isEqualTo("沙箱任务不存在或无权查看");
        assertThat(countResults()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 2);
    }

    @Test
    void submitWhilePendingRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB100007");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-submit-7a", REQUESTER);
        final MvcResult again = submit(sandboxNo, "report.md", "key-submit-7b", REQUESTER);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(again)).isEqualTo("1009C0016");
        assertThat(countResults()).isEqualTo(1);
        assertActionLog("sandbox.denied", "DENIED", "1009C0016", 1);
    }

    @Test
    void resubmitAfterRejectAdvancesRoundAndRefreshesSnapshot() throws Exception {
        final String sandboxNo = seedRunningTask("SB100008");
        writeResult(sandboxNo, "report.md", "v1");
        submit(sandboxNo, "report.md", "key-submit-8a", REQUESTER);
        review(sandboxNo, "report.md", "REJECT", "格式不符合要求");
        writeResult(sandboxNo, "report.md", "v2-longer-content");
        final MvcResult resubmit = submit(sandboxNo, "report.md", "key-submit-8b", REQUESTER);
        assertThat(resubmit.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(resubmit);
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("reviewRound").asInt()).isEqualTo(2);
        assertThat(data.path("sizeBytes").asInt()).isEqualTo("v2-longer-content".length());
        assertThat(countReviews()).isEqualTo(1);
        assertThat(resultRow(sandboxNo, "report.md")).contains("PENDING", "2");
        assertActionLog("sandbox.result.submit", "SUCCESS", null, 2);
    }

    @Test
    void submitAfterApprovedRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB100009");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-submit-9a", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        final MvcResult again = submit(sandboxNo, "report.md", "key-submit-9b", REQUESTER);
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(again)).isEqualTo("1009C0016");
        assertActionLog("sandbox.denied", "DENIED", "1009C0016", 1);
    }

    @Test
    void submitIdempotentReplayReturnsFirstRegistration() throws Exception {
        final String sandboxNo = seedRunningTask("SB100010");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final String first = submit(sandboxNo, "report.md", "key-submit-10", REQUESTER)
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        final String replay = submit(sandboxNo, "report.md", "key-submit-10", REQUESTER)
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(replay).isEqualTo(first);
        assertThat(countResults()).isEqualTo(1);
        assertActionLog("sandbox.result.submit", "SUCCESS", null, 1);
    }

    // ---------- 治理审核组（hifi §4） ----------

    @Test
    void reviewApproveTransitionsStatusAndWritesReviewRow() throws Exception {
        final String sandboxNo = seedRunningTask("SB200001");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-1", REQUESTER);
        final MvcResult result = review(sandboxNo, "report.md", "APPROVE", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("status").asText()).isEqualTo("APPROVED");
        assertThat(dataOf(result).path("conclusion").asText()).isEqualTo("APPROVED");
        assertThat(resultRow(sandboxNo, "report.md")).contains("APPROVED", ADMIN);
        assertThat(countReviews()).isEqualTo(1);
        assertThat(reviewRow(sandboxNo, "report.md")).contains("APPROVED", ADMIN);
        assertActionLog("sandbox.review", "SUCCESS", null, 1);
    }

    @Test
    void reviewRejectKeepsReasonInResultAndReviewRow() throws Exception {
        final String sandboxNo = seedRunningTask("SB200002");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-2", REQUESTER);
        final MvcResult result = review(sandboxNo, "report.md", "REJECT", "含敏感字段，请脱敏后重试");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("status").asText()).isEqualTo("REJECTED");
        assertThat(dataOf(result).path("rejectReason").asText()).isEqualTo("含敏感字段，请脱敏后重试");
        assertThat(resultRow(sandboxNo, "report.md")).contains("REJECTED", "含敏感字段，请脱敏后重试");
        assertThat(reviewRow(sandboxNo, "report.md")).contains("REJECTED", "含敏感字段，请脱敏后重试");
    }

    @Test
    void reviewRejectWithoutReasonIs400() throws Exception {
        final String sandboxNo = seedRunningTask("SB200003");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-3", REQUESTER);
        final MvcResult result = review(sandboxNo, "report.md", "REJECT", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(result)).isEqualTo("1009C0005");
        assertThat(resultRow(sandboxNo, "report.md")).contains("PENDING");
        assertActionLog("sandbox.denied", "DENIED", "1009C0005", 1);
    }

    @Test
    void reviewAlreadyConcludedRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB200004");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-4", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        assertThat(codeOf(review(sandboxNo, "report.md", "REJECT", "改主意"))).isEqualTo("1009C0016");
        final String sandboxNo2 = seedRunningTask("SB200005");
        writeResult(sandboxNo2, "model.bin", "m");
        submit(sandboxNo2, "model.bin", "key-review-5", REQUESTER);
        review(sandboxNo2, "model.bin", "REJECT", "理由");
        assertThat(codeOf(review(sandboxNo2, "model.bin", "APPROVE", null)))
                .isEqualTo("1009C0016");
        assertThat(countReviews()).isEqualTo(2);
        assertActionLog("sandbox.denied", "DENIED", "1009C0016", 2);
    }

    @Test
    void reviewUnregisteredResultIs404() throws Exception {
        final String sandboxNo = seedRunningTask("SB200006");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final MvcResult result = review(sandboxNo, "report.md", "APPROVE", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(codeOf(result)).isEqualTo("1009C0015");
        assertThat(countReviews()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0015", 1);
    }

    @Test
    void reviewByNonAdminDeniedByPermissionLayer() throws Exception {
        final String sandboxNo = seedRunningTask("SB200007");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-7", REQUESTER);
        final MvcResult result = mockMvc.perform(post(
                        "/api/v1/sandboxes/governance/{no}/results/{file}/actions/review",
                        sandboxNo, "report.md")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"APPROVE\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1000C0005");
        assertThat(resultRow(sandboxNo, "report.md")).contains("PENDING");
    }

    @Test
    void reviewListShowsConclusionsAndReasons() throws Exception {
        final String sandboxNo = seedRunningTask("SB200008");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        writeResult(sandboxNo, "model.bin", "m");
        submit(sandboxNo, "report.md", "key-review-8a", REQUESTER);
        submit(sandboxNo, "model.bin", "key-review-8b", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        review(sandboxNo, "model.bin", "REJECT", "形态不符");
        final MvcResult result = reviewsList(sandboxNo);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("total").asLong()).isEqualTo(2);
        final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("APPROVED", "REJECTED", "形态不符", ADMIN);
        assertThat(body).doesNotContain(SECRET_MARKER, WORKSPACE.toString());
    }

    @Test
    void reviewAfterTaskClosedRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB200009");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-9", REQUESTER);
        jdbc.update("UPDATE sandbox_task SET status = 'STOPPED', close_reason = 'CLOSED' "
                + "WHERE sandbox_no = ?", sandboxNo);
        final MvcResult result = review(sandboxNo, "report.md", "APPROVE", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0011");
        assertThat(countReviews()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0011", 1);
    }

    @Test
    void reviewRowsKeepBusinessFieldsOnly() throws Exception {
        final String sandboxNo = seedRunningTask("SB200010");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-review-10", REQUESTER);
        review(sandboxNo, "report.md", "REJECT", "内容需复核");
        final String reviews = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(CONCAT(sandbox_no, '|', file_name, '|', conclusion, '|', "
                        + "COALESCE(reason, ''), '|', reviewed_by)) FROM sandbox_result_review",
                String.class);
        assertThat(reviews).contains(sandboxNo, "report.md", "REJECTED", "内容需复核", ADMIN);
        assertThat(reviews).doesNotContain(SECRET_MARKER, WORKSPACE.toString());
    }

    // ---------- 导出组（hifi §5） ----------

    @Test
    void exportApprovedResultCreatesPackageRecordAndCountsOnce() throws Exception {
        final String sandboxNo = seedRunningTask("SB300001");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-export-1", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        final MvcResult result = export(sandboxNo, "report.md", "key-export-1x", REQUESTER,
                "{\"purpose\":\"风控建模\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode data = dataOf(result);
        final String deliveryNo = data.path("deliveryNo").asText();
        assertThat(deliveryNo).matches("DL\\d{6}");
        assertThat(data.path("sandboxNo").asText()).isEqualTo(sandboxNo);
        assertThat(data.path("resultName").asText()).isEqualTo("report.md");
        assertThat(data.path("fileName").asText()).isEqualTo("report.md");
        assertThat(data.path("fileSize").asInt()).isEqualTo(SECRET_MARKER.length());
        assertThat(data.path("checksum").asText()).matches("[0-9a-f]{64}");

        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(STUB.lastCheckBody()).contains("USE", CONTRACT, "风控建模");
        assertThat(exportRowCount()).isEqualTo(1);
        final String row = jdbc.queryForObject(
                "SELECT CONCAT(delivery_no, '|', delivery_type, '|', COALESCE(sandbox_no, ''), '|', "
                        + "COALESCE(result_name, ''), '|', file_name, '|', file_size, '|', checksum, "
                        + "'|', key_ref) FROM delivery WHERE delivery_no = ?", String.class,
                deliveryNo);
        assertThat(row).contains("SANDBOX_EXPORT", sandboxNo, "report.md", KEY_REF);
        assertThat(row).doesNotContain(SECRET_MARKER);
        final Path pkg = STORAGE.resolve(deliveryNo + ".sm4");
        assertThat(pkg).exists();
        assertThat(Files.readAllBytes(pkg)).isNotEqualTo(
                SECRET_MARKER.getBytes(StandardCharsets.UTF_8));
        assertActionLog("sandbox.export", "SUCCESS", null, 1);
    }

    @Test
    void exportUnregisteredRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB300002");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final MvcResult result = export(sandboxNo, "report.md", "key-export-2", REQUESTER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0016");
        assertThat(STUB.checkCount()).isZero();
        assertThat(exportRowCount()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0016", 1);
    }

    @Test
    void exportRejectedResultRejectedWithReasonQueryable() throws Exception {
        final String sandboxNo = seedRunningTask("SB300003");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-export-3", REQUESTER);
        review(sandboxNo, "report.md", "REJECT", "含敏感字段");
        final MvcResult result = export(sandboxNo, "report.md", "key-export-3x", REQUESTER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0016");
        assertThat(exportRowCount()).isZero();
        final String listBody = listResults(sandboxNo, REQUESTER).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(listBody).contains("含敏感字段");
    }

    @Test
    void exportPendingRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB300004");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-export-4", REQUESTER);
        final MvcResult result = export(sandboxNo, "report.md", "key-export-4x", REQUESTER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0016");
        assertThat(STUB.checkCount()).isZero();
    }

    @Test
    void exportPolicyViolationPassesThroughWithoutRecord() throws Exception {
        final String sandboxNo = seedRunningTask("SB300005");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-export-5", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        STUB.respondCheckError("1008C0020", "用途超出合约约定", 403);
        final MvcResult result = export(sandboxNo, "report.md", "key-export-5x", REQUESTER,
                "{\"purpose\":\"营销外呼\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1008C0020");
        assertThat(STUB.lastCheckBody()).contains("营销外呼");
        assertThat(exportRowCount()).isZero();
        assertThat(packageCount()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1008C0020", 1);
    }

    @Test
    void exportSnapshotMismatchRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB300006");
        writeResult(sandboxNo, "report.md", "original");
        submit(sandboxNo, "report.md", "key-export-6", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        writeResult(sandboxNo, "report.md", "tampered-after-review");
        final MvcResult result = export(sandboxNo, "report.md", "key-export-6x", REQUESTER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(result)).isEqualTo("1009C0016");
        assertThat(exportRowCount()).isZero();
        assertThat(packageCount()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0016", 1);
    }

    @Test
    void exportAfterTaskClosedOrExpiredRejected() throws Exception {
        final String stopped = seedRunningTask("SB300007");
        writeResult(stopped, "report.md", SECRET_MARKER);
        submitApproved(stopped, "report.md", "key-export-7", "key-review-7x");
        jdbc.update("UPDATE sandbox_task SET status = 'STOPPED', close_reason = 'CLOSED' "
                + "WHERE sandbox_no = ?", stopped);
        assertThat(codeOf(export(stopped, "report.md", "key-export-7x1", REQUESTER, "{}")))
                .isEqualTo("1009C0011");

        final String sandboxNo = seedRunningTask("SB300008");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-8", "key-review-8x");
        jdbc.update("UPDATE sandbox_task SET expires_at = ? WHERE sandbox_no = ?",
                LocalDateTime.now().minusMinutes(1), sandboxNo);
        assertThat(codeOf(export(sandboxNo, "report.md", "key-export-8x1", REQUESTER, "{}")))
                .isEqualTo("1009C0011");
        assertThat(exportRowCount()).isZero();
    }

    @Test
    void exportByProviderRejected() throws Exception {
        final String sandboxNo = seedRunningTask("SB300009");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-9", "key-review-9x");
        final MvcResult result = export(sandboxNo, "report.md", "key-export-9x1", PROVIDER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("1009C0003");
        assertThat(STUB.checkCount()).isZero();
        assertActionLog("sandbox.denied", "DENIED", "1009C0003", 1);
    }

    @Test
    void exportByNonParticipantSameCodeAndMessageAsMissingTask() throws Exception {
        final String sandboxNo = seedRunningTask("SB300010");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-10", "key-review-10x");
        final JsonNode notVisible = MAPPER.readTree(export(sandboxNo, "report.md",
                "key-export-10x1", OTHER, "{}").getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        final JsonNode missing = MAPPER.readTree(export("SB999998", "report.md",
                "key-export-10x2", OTHER, "{}").getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(notVisible.path("code").asText()).isEqualTo("1009C0010");
        assertThat(missing.path("code").asText()).isEqualTo(notVisible.path("code").asText());
        assertThat(missing.path("message").asText())
                .isEqualTo(notVisible.path("message").asText());
        assertActionLog("sandbox.denied", "DENIED", "1009C0010", 2);
    }

    @Test
    void exportIdempotentReplayReturnsFirstRecord() throws Exception {
        final String sandboxNo = seedRunningTask("SB300011");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-11", "key-review-11x");
        final String first = dataOf(export(sandboxNo, "report.md", "key-export-11x", REQUESTER,
                "{}")).path("deliveryNo").asText();
        final MvcResult replay = export(sandboxNo, "report.md", "key-export-11x", REQUESTER, "{}");
        assertThat(dataOf(replay).path("deliveryNo").asText()).isEqualTo(first);
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(exportRowCount()).isEqualTo(1);
    }

    @Test
    void exportDuplicateKeyFallbackReturnsFirstRecordAndDeletesPackage() throws Exception {
        final String sandboxNo = seedRunningTask("SB300014");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-14", "key-review-14x");
        // 预插同 (requester_no, idempotency_key) 行 → 越过内存幂等层时 deliveries 唯一键兜底：
        // 返回首次记录 + 本次构建的包删除 + 计数不重复（check 已计数不回滚 = 诚实边界）
        jdbc.update("INSERT INTO delivery (delivery_no, contract_no, product_id, product_name, "
                + "delivery_type, requester_no, provider_no, result, idempotency_key) VALUES "
                + "('DL900002', ?, 13, '演示数据集', 'FILE', ?, ?, 'SUCCESS', 'key-export-14x')",
                CONTRACT, REQUESTER, PROVIDER);
        final MvcResult result = export(sandboxNo, "report.md", "key-export-14x", REQUESTER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(dataOf(result).path("deliveryNo").asText()).isEqualTo("DL900002");
        assertThat(dataOf(result).path("deliveryType").asText()).isEqualTo("FILE");
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertThat(packageCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery", Integer.class))
                .isEqualTo(1);
        assertActionLog("sandbox.export", "SUCCESS", null, 0);
    }

    @Test
    void exportPackageDownloadByRequesterOkAndProviderDenied() throws Exception {
        final String sandboxNo = seedRunningTask("SB300012");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-12", "key-review-12x");
        final String deliveryNo = dataOf(export(sandboxNo, "report.md", "key-export-12x",
                REQUESTER, "{}")).path("deliveryNo").asText();
        final MvcResult download = mockMvc.perform(get("/api/v1/deliveries/{no}/package",
                        deliveryNo)
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester"))
                .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getHeader("X-Ctds-Sm3")).matches("[0-9a-f]{64}");
        assertThat(download.getResponse().getContentAsByteArray()).isNotEmpty();

        final MvcResult provider = mockMvc.perform(get("/api/v1/deliveries/{no}/package",
                        deliveryNo)
                .header("X-Ctds-Subject", PROVIDER).header("X-Ctds-Roles", "requester"))
                .andReturn();
        assertThat(provider.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(provider)).isEqualTo("1009C0009");
    }

    @Test
    void exportPackageFailureLeavesNoRecordOrResidue() throws Exception {
        final String sandboxNo = seedRunningTask("SB300013");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-export-13", "key-review-13x");
        // 预占首个交付号对应的包标识 → FilePackageStore 拒绝覆盖既有包（平台侧失败路径）；
        // 序号表置 1 后首次取号 = DL000002（LAST_INSERT_ID(next_no + 1) 语义）
        Files.write(STORAGE.resolve("DL000002.sm4"), "occupied".getBytes(StandardCharsets.UTF_8));
        final MvcResult result = export(sandboxNo, "report.md", "key-export-13x", REQUESTER, "{}");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(codeOf(result)).isEqualTo("1009S0003");
        assertThat(exportRowCount()).isZero();
        assertThat(packageCount()).isEqualTo(1);
        assertThat(STUB.checkCount()).isEqualTo(1);
        assertActionLog("sandbox.export", "FAILURE", "1009S0003", 1);
    }

    // ---------- 读面与防枚举组（hifi §6 / §7.1） ----------

    @Test
    void resultsListCarriesStatusFieldsAndNullsForUnsubmitted() throws Exception {
        final String sandboxNo = seedRunningTask("SB400001");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        writeResult(sandboxNo, "draft.md", "draft");
        submit(sandboxNo, "report.md", "key-read-1", REQUESTER);
        review(sandboxNo, "report.md", "REJECT", "格式不符");
        final MvcResult result = listResults(sandboxNo, REQUESTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        final JsonNode list = dataOf(result);
        assertThat(list).hasSize(2);
        JsonNode rejected = null;
        JsonNode unsubmitted = null;
        for (final JsonNode item : list) {
            if ("report.md".equals(item.path("fileName").asText())) {
                rejected = item;
            } else {
                unsubmitted = item;
            }
        }
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("conclusion").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("rejectReason").asText()).isEqualTo("格式不符");
        assertThat(rejected.path("reviewRound").asInt()).isEqualTo(1);
        assertThat(unsubmitted.path("status").isNull()).isTrue();
        assertThat(unsubmitted.path("conclusion").isNull()).isTrue();
        assertThat(unsubmitted.path("rejectReason").isNull()).isTrue();
        assertThat(unsubmitted.path("reviewRound").isNull()).isTrue();
    }

    @Test
    void exportsListVisibleToBothParticipants() throws Exception {
        final String sandboxNo = seedRunningTask("SB400002");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-read-2", "key-review-2r");
        export(sandboxNo, "report.md", "key-read-2x", REQUESTER, "{}");
        for (final String subject : List.of(REQUESTER, PROVIDER)) {
            final MvcResult result = exportsList(sandboxNo, subject);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(dataOf(result).path("total").asLong()).isEqualTo(1);
            final JsonNode row = dataOf(result).path("list").get(0);
            assertThat(row.path("deliveryNo").asText()).matches("DL\\d{6}");
            assertThat(row.path("sandboxNo").asText()).isEqualTo(sandboxNo);
            assertThat(row.path("resultName").asText()).isEqualTo("report.md");
            assertThat(row.path("deliveryType").asText()).isEqualTo("SANDBOX_EXPORT");
        }
        final String body = exportsList(sandboxNo, REQUESTER).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(SECRET_MARKER, WORKSPACE.toString());
    }

    @Test
    void nonParticipantResultFacesShareCodeAndMessage() throws Exception {
        final String sandboxNo = seedRunningTask("SB400003");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-read-3", "key-review-3r");
        final List<String> codes = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        for (final MvcResult result : List.of(
                listResults(sandboxNo, OTHER),
                exportsList(sandboxNo, OTHER),
                listResults("SB999997", OTHER),
                exportsList("SB999997", OTHER),
                reviewsListMissing("SB999997"))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            final JsonNode body = MAPPER.readTree(result.getResponse()
                    .getContentAsString(StandardCharsets.UTF_8));
            codes.add(body.path("code").asText());
            messages.add(body.path("message").asText());
        }
        assertThat(codes).containsOnly("1009C0010");
        assertThat(new LinkedHashSet<>(messages)).hasSize(1);
    }

    @Test
    void providerCanReadButNotSubmitOrExport() throws Exception {
        final String sandboxNo = seedRunningTask("SB400004");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        assertThat(listResults(sandboxNo, PROVIDER).getResponse().getStatus()).isEqualTo(200);
        assertThat(submit(sandboxNo, "report.md", "key-read-4p", PROVIDER).getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(export(sandboxNo, "report.md", "key-read-4x", PROVIDER, "{}").getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(STUB.checkCount()).isZero();
    }

    @Test
    void rejectedReasonVisibleToRequesterInResults() throws Exception {
        final String sandboxNo = seedRunningTask("SB400005");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-read-5", REQUESTER);
        review(sandboxNo, "report.md", "REJECT", "敏感数据未脱敏");
        final String body = listResults(sandboxNo, REQUESTER).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("敏感数据未脱敏");
        // 结果物读面承载在列表端点（本卡不新增详情端点）：非参与方同面同码同文由上文覆盖
        final String otherBody = listResults(sandboxNo, OTHER).getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(otherBody).doesNotContain("敏感数据未脱敏");
    }

    @Test
    void governanceReviewsReadIsTraced() throws Exception {
        final String sandboxNo = seedRunningTask("SB400006");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-read-6", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        assertThat(reviewsList(sandboxNo).getResponse().getStatus()).isEqualTo(200);
        assertActionLog("sandbox.governance.read", "SUCCESS", null, 1);
    }

    // ---------- 留痕与无本体组（hifi §7.2 / §8 字段集断言） ----------

    @Test
    void auditJsonlForResultActionsKeepsMinimalFieldSet() throws Exception {
        final String sandboxNo = seedRunningTask("SB500001");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-audit-1", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        export(sandboxNo, "report.md", "key-audit-1x", REQUESTER, "{}");
        final List<JsonNode> events = awaitSandboxAuditEvents(sandboxNo,
                Set.of("sandbox.result.submit", "sandbox.review", "sandbox.export"));
        for (final JsonNode event : events) {
            assertThat(fieldNames(event)).isSubsetOf(AUDIT_FIELDS);
            if (!event.path("detail").isNull() && !event.path("detail").isMissingNode()) {
                assertThat(fieldNames(event.path("detail"))).isSubsetOf(AUDIT_DETAIL_FIELDS);
            }
        }
        final String raw = readAuditRaw();
        assertThat(raw).doesNotContain(SECRET_MARKER, WORKSPACE.toString(), STORAGE.toString());
    }

    @Test
    void dbRowsKeepBusinessFieldsOnly() throws Exception {
        final String sandboxNo = seedRunningTask("SB500002");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-audit-2", REQUESTER);
        review(sandboxNo, "report.md", "APPROVE", null);
        export(sandboxNo, "report.md", "key-audit-2x", REQUESTER, "{}");
        final String resultRows = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(CONCAT(sandbox_no, '|', file_name, '|', status, '|', "
                        + "COALESCE(checksum, ''), '|', COALESCE(reject_reason, ''))) "
                        + "FROM sandbox_result", String.class);
        final String logRows = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(CONCAT(action, '|', COALESCE(result_name, ''), '|', "
                        + "COALESCE(reason_code, ''))) FROM sandbox_action_log", String.class);
        assertThat(resultRows).contains(sandboxNo, "report.md", "APPROVED");
        assertThat(resultRows).doesNotContain(SECRET_MARKER, WORKSPACE.toString());
        assertThat(logRows).contains("sandbox.result.submit", "sandbox.review", "sandbox.export",
                "report.md");
        assertThat(logRows).doesNotContain(SECRET_MARKER, WORKSPACE.toString());
    }

    @Test
    void responsesExposeNoContentOrPath() throws Exception {
        final String sandboxNo = seedRunningTask("SB500003");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        final String submitted = submit(sandboxNo, "report.md", "key-audit-3", REQUESTER)
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        review(sandboxNo, "report.md", "APPROVE", null);
        final String exported = export(sandboxNo, "report.md", "key-audit-3x", REQUESTER, "{}")
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (final String body : List.of(submitted, exported)) {
            assertThat(body).doesNotContain(SECRET_MARKER, WORKSPACE.toString(),
                    STORAGE.toString());
        }
        assertThat(fieldNames(MAPPER.readTree(submitted).path("data")))
                .isSubsetOf(SUBMIT_VIEW_FIELDS);
        assertThat(fieldNames(MAPPER.readTree(exported).path("data")))
                .isSubsetOf(EXPORT_VIEW_FIELDS);
    }

    @Test
    void exportRecordKeyRefIsReferenceNotSecret() throws Exception {
        final String sandboxNo = seedRunningTask("SB500004");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submitApproved(sandboxNo, "report.md", "key-audit-4", "key-review-4r");
        export(sandboxNo, "report.md", "key-audit-4x", REQUESTER, "{}");
        final List<String> keyRefs = jdbc.queryForList(
                "SELECT key_ref FROM delivery WHERE delivery_type = 'SANDBOX_EXPORT'",
                String.class);
        assertThat(keyRefs).containsExactly(KEY_REF);
        final String raw = readAuditRaw();
        assertThat(raw).doesNotContain(new String(Base64.getEncoder()
                .encode("0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    // ---------- 配置与迁移组（hifi §1 / §2） ----------

    @Test
    void flywayV3SchemaMatchesDesignContract() {
        final Integer resultTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = "
                        + "DATABASE() AND table_name = 'sandbox_result'", Integer.class);
        final Integer reviewTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = "
                        + "DATABASE() AND table_name = 'sandbox_result_review'", Integer.class);
        assertThat(resultTable).isEqualTo(1);
        assertThat(reviewTable).isEqualTo(1);
        final List<String> resultColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = "
                        + "DATABASE() AND table_name = 'sandbox_result' ORDER BY ordinal_position",
                String.class);
        assertThat(resultColumns).contains("sandbox_no", "file_name", "status", "review_round",
                "size_bytes", "checksum", "submitted_by", "submitted_at", "reviewed_by",
                "reviewed_at", "reject_reason", "idempotency_key");
        final String typeWidth = jdbc.queryForObject(
                "SELECT column_type FROM information_schema.columns WHERE table_schema = "
                        + "DATABASE() AND table_name = 'delivery' AND column_name = "
                        + "'delivery_type'", String.class);
        // V1 列宽 8 容不下 SANDBOX_EXPORT（14 字符），V3 放宽至 16（既有值零变更）
        assertThat(typeWidth).isEqualTo("varchar(16)");
        final String ukColumns = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema"
                        + ".statistics WHERE table_schema = DATABASE() AND table_name = "
                        + "'sandbox_result' AND index_name = 'uk_sandbox_result'", String.class);
        // 一任务一文件名一行 = 并发兜底唯一键锚
        assertThat(ukColumns).isEqualTo("sandbox_no,file_name");
        final List<String> reviewColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = "
                        + "DATABASE() AND table_name = 'sandbox_result_review' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(reviewColumns).contains("sandbox_no", "file_name", "review_round", "conclusion",
                "reason", "reviewed_by", "created_at");
        final String widths = jdbc.queryForObject(
                "SELECT CONCAT((SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sandbox_result' AND "
                        + "column_name = 'file_name'), '|', (SELECT character_maximum_length FROM "
                        + "information_schema.columns WHERE table_schema = DATABASE() AND "
                        + "table_name = 'sandbox_result' AND column_name = 'reject_reason'))",
                String.class);
        assertThat(widths).isEqualTo("128|256");
        jdbc.update("INSERT INTO delivery (delivery_no, contract_no, product_id, product_name, "
                + "delivery_type, requester_no, provider_no, result, idempotency_key) VALUES "
                + "('DL900001', ?, 13, '演示数据集', 'API', ?, ?, 'SUCCESS', 'seed-legacy')",
                CONTRACT, REQUESTER, PROVIDER);
        final Integer legacyNulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery WHERE delivery_no = 'DL900001' AND sandbox_no "
                        + "IS NULL AND result_name IS NULL", Integer.class);
        assertThat(legacyNulls).isEqualTo(1);
    }

    @Test
    void adminRoleCarriesSandboxReviewPermission() throws Exception {
        final String sandboxNo = seedRunningTask("SB600001");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-cfg-1", REQUESTER);
        // admin 角色装载 sandbox.review（权限配置面）→ 业务层可达
        assertThat(review(sandboxNo, "report.md", "APPROVE", null).getResponse().getStatus())
                .isEqualTo(200);
        // requester 角色未装载 sandbox.review → 权限层拒绝
        final MvcResult denied = mockMvc.perform(post(
                        "/api/v1/sandboxes/governance/{no}/results/{file}/actions/review",
                        sandboxNo, "report.md")
                .header("X-Ctds-Subject", REQUESTER).header("X-Ctds-Roles", "requester")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"REJECT\",\"reason\":\"x\"}")).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(denied)).isEqualTo("1000C0005");
    }

    @Test
    void reviewAndExportParamValidation() throws Exception {
        final String sandboxNo = seedRunningTask("SB600002");
        writeResult(sandboxNo, "report.md", SECRET_MARKER);
        submit(sandboxNo, "report.md", "key-cfg-2", REQUESTER);
        final MvcResult badConclusion = mockMvc.perform(post(
                        "/api/v1/sandboxes/governance/{no}/results/{file}/actions/review",
                        sandboxNo, "report.md")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"MAYBE\"}")).andReturn();
        assertThat(badConclusion.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(badConclusion)).isEqualTo("1009C0005");

        final MvcResult longReason = mockMvc.perform(post(
                        "/api/v1/sandboxes/governance/{no}/results/{file}/actions/review",
                        sandboxNo, "report.md")
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"REJECT\",\"reason\":\"" + "长".repeat(257) + "\"}"))
                .andReturn();
        assertThat(longReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(longReason)).isEqualTo("1009C0005");

        final MvcResult longPurpose = export(sandboxNo, "report.md", "key-cfg-2x", REQUESTER,
                "{\"purpose\":\"" + "用".repeat(257) + "\"}");
        assertThat(longPurpose.getResponse().getStatus()).isEqualTo(400);
        assertThat(codeOf(longPurpose)).isEqualTo("1009C0005");
    }

    // ---------- 辅助 ----------

    private String seedRunningTask(final String sandboxNo) {
        return seedTask(sandboxNo, REQUESTER, "RUNNING");
    }

    private String seedTask(final String sandboxNo, final String requester, final String status) {
        jdbc.update("INSERT INTO sandbox_task (sandbox_no, contract_no, product_id, product_name, "
                        + "requester_no, provider_no, status, cpus, memory_mb, ttl_seconds, "
                        + "expires_at, data_file_count, idempotency_key) VALUES (?, ?, 13, "
                        + "'演示数据集', ?, ?, ?, 1.00, 512, 7200, ?, 2, ?)",
                sandboxNo, CONTRACT, requester, PROVIDER, status,
                LocalDateTime.now().plusHours(1), "seed-" + sandboxNo);
        return sandboxNo;
    }

    private void writeResult(final String sandboxNo, final String fileName, final String content)
            throws Exception {
        final Path output = WORKSPACE.resolve(sandboxNo).resolve("output");
        Files.createDirectories(output);
        Files.writeString(output.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    /** 提交审核 → 审核通过（组合脚手架）。 */
    private void submitApproved(final String sandboxNo, final String fileName,
            final String submitKey, final String reviewKey) throws Exception {
        submit(sandboxNo, fileName, submitKey, REQUESTER);
        final MvcResult reviewed = review(sandboxNo, fileName, "APPROVE", null);
        assertThat(reviewed.getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult submit(final String sandboxNo, final String fileName, final String key,
            final String subject) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/sandboxes/{no}/results/{file}/actions/submit-review", sandboxNo,
                        fileName)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", key)).andReturn();
    }

    private MvcResult review(final String sandboxNo, final String fileName, final String conclusion,
            final String reason) throws Exception {
        final String body = reason == null
                ? "{\"conclusion\":\"" + conclusion + "\"}"
                : "{\"conclusion\":\"" + conclusion + "\",\"reason\":\"" + reason + "\"}";
        return mockMvc.perform(post(
                        "/api/v1/sandboxes/governance/{no}/results/{file}/actions/review",
                        sandboxNo, fileName)
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult export(final String sandboxNo, final String fileName, final String key,
            final String subject, final String body) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/sandboxes/{no}/results/{file}/actions/export", sandboxNo,
                        fileName)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester")
                .header("X-Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult listResults(final String sandboxNo, final String subject) throws Exception {
        return mockMvc.perform(get("/api/v1/sandboxes/{no}/results", sandboxNo)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester"))
                .andReturn();
    }

    private MvcResult exportsList(final String sandboxNo, final String subject) throws Exception {
        return mockMvc.perform(get("/api/v1/sandboxes/{no}/exports", sandboxNo)
                .header("X-Ctds-Subject", subject).header("X-Ctds-Roles", "requester"))
                .andReturn();
    }

    private MvcResult reviewsList(final String sandboxNo) throws Exception {
        return reviewsListMissing(sandboxNo);
    }

    private MvcResult reviewsListMissing(final String sandboxNo) throws Exception {
        return mockMvc.perform(get("/api/v1/sandboxes/governance/{no}/reviews", sandboxNo)
                .header("X-Ctds-Subject", ADMIN).header("X-Ctds-Roles", "admin")).andReturn();
    }

    private int countResults() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_result", Integer.class);
    }

    private int countReviews() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sandbox_result_review", Integer.class);
    }

    private String resultRow(final String sandboxNo, final String fileName) {
        return jdbc.queryForObject(
                "SELECT CONCAT(sandbox_no, '|', file_name, '|', status, '|', review_round, '|', "
                        + "COALESCE(submitted_by, ''), '|', COALESCE(reviewed_by, ''), '|', "
                        + "COALESCE(reject_reason, ''), '|', COALESCE(idempotency_key, '')) "
                        + "FROM sandbox_result WHERE sandbox_no = ? AND file_name = ?",
                String.class, sandboxNo, fileName);
    }

    private String reviewRow(final String sandboxNo, final String fileName) {
        return jdbc.queryForObject(
                "SELECT CONCAT(sandbox_no, '|', file_name, '|', review_round, '|', conclusion, "
                        + "'|', COALESCE(reason, ''), '|', reviewed_by) FROM sandbox_result_review "
                        + "WHERE sandbox_no = ? AND file_name = ?", String.class, sandboxNo,
                fileName);
    }

    private int exportRowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery WHERE delivery_type = 'SANDBOX_EXPORT'",
                Integer.class);
    }

    private int packageCount() throws Exception {
        try (Stream<Path> paths = Files.list(STORAGE)) {
            return (int) paths.count();
        }
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

    /** 轮询等待"本沙箱"的审计事件（异步写线程，最多 15s）。 */
    private List<JsonNode> awaitSandboxAuditEvents(final String sandboxNo,
            final Set<String> required) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<JsonNode> events = sandboxAuditEventsOf(sandboxNo);
        while (!actionsOf(events).containsAll(required) && System.nanoTime() < deadline) {
            Thread.sleep(100L);
            events = sandboxAuditEventsOf(sandboxNo);
        }
        assertThat(actionsOf(events)).containsAll(required);
        return events;
    }

    private static List<JsonNode> sandboxAuditEventsOf(final String sandboxNo) throws Exception {
        final List<JsonNode> events = new ArrayList<>();
        for (final JsonNode event : readAuditEvents()) {
            if (sandboxNo.equals(event.path("targetId").asText())) {
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
            auditFiles().forEach(SandboxResultIntegrationTest::deleteRecursively);
        } catch (final Exception ignored) {
            // best-effort
        }
    }

    private static void clearWorkspace() throws Exception {
        if (!Files.isDirectory(WORKSPACE)) {
            return;
        }
        try (Stream<Path> paths = Files.list(WORKSPACE)) {
            paths.forEach(SandboxResultIntegrationTest::deleteRecursively);
        }
    }

    private static void clearDir(final Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            paths.forEach(SandboxResultIntegrationTest::deleteRecursively);
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
