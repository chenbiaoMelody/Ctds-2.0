package com.ctds.did.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidKmsClient;
import com.ctds.did.domain.DidOperationLog;
import com.ctds.did.domain.DidRepository;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.domain.VerificationLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * DID 签发应用服务单元测试（WBS-3.1.8 行为清单 B1~B6 全分支，仓储用内存桩）：
 * 自动签发（留痕四要素）/ 幂等 / 失败可重试 / 吊销（理由必填 + 五要素 + 不可逆）/ 重签（新序号新密钥）。
 * 并发守卫：completeIssuance 带状态乐观门槛（与 revoke 对称），交错/并发用例锚定"恰一条 ACTIVE + 一条留痕"。
 */
@ExtendWith(OutputCaptureExtension.class)
class DidIssuanceServiceTest {

    private static final String SUBJECT_NO = "S20260920000001";
    /** 私钥 D 值样式：恰好 64 位 hex、两侧非 hex 边界（区分 130 位公钥 04‖X‖Y）。 */
    private static final String PRIVATE_KEY_PATTERN = "(^|[^0-9a-fA-F])[0-9a-fA-F]{64}([^0-9a-fA-F]|$)";

    private FakeDidRepository repository;
    private StubKmsClient kmsClient;
    private DidIssuanceService service;

    @BeforeEach
    void setUp() {
        repository = new FakeDidRepository();
        kmsClient = new StubKmsClient();
        service = new DidIssuanceService(repository, kmsClient, new ObjectMapper());
    }

    @Test
    void issueCreatesActiveIdentityWithFourElementLog() {
        final DidIssuanceService.IssuanceResult result = service.issue(SUBJECT_NO);

        assertThat(result.status()).isEqualTo(DidStatus.ACTIVE);
        assertThat(result.did()).isEqualTo("did:ctds:" + SUBJECT_NO + ".1");
        assertThat(result.keyRef()).isEqualTo("did-" + SUBJECT_NO + "-1");

        // 留痕四要素：occurred_at + subject_no + did + key_ref
        final DidOperationLog log = repository.logs().get(0);
        assertThat(log.operation()).isEqualTo("ISSUE");
        assertThat(log.subjectNo()).isEqualTo(SUBJECT_NO);
        assertThat(log.did()).isEqualTo(result.did());
        assertThat(log.keyRef()).isEqualTo(result.keyRef());
        assertThat(log.occurredAt()).isNotNull();

        // DID 文档只含公开要素（controller=主体编号、公钥 hex、不含密钥引用）
        final JsonNode doc = parseDocument(repository.findActiveOrPending(SUBJECT_NO).get().documentJson());
        assertThat(doc.get("controller").asText()).isEqualTo(SUBJECT_NO);
        assertThat(doc.at("/publicKey/valueHex").asText()).isEqualTo(kmsClient.publicKeyHex());
        assertThat(doc.has("keyRef")).isFalse();
        assertThat(doc.has("privateKey")).isFalse();
    }

    @Test
    void issueTwiceIsIdempotentSingleActive() {
        final DidIssuanceService.IssuanceResult first = service.issue(SUBJECT_NO);
        final DidIssuanceService.IssuanceResult second = service.issue(SUBJECT_NO);

        assertThat(second.did()).isEqualTo(first.did());
        assertThat(repository.activeCount()).isEqualTo(1);
        assertThat(repository.logs()).hasSize(1);
    }

    @Test
    void kmsFailureLeavesPendingAndRetryCompletes() {
        kmsClient.failNext();
        final DidIssuanceService.IssuanceResult pending = service.issue(SUBJECT_NO);

        assertThat(pending.status()).isEqualTo(DidStatus.PENDING_ISSUE);
        assertThat(pending.did()).isNull();
        assertThat(repository.findActiveOrPending(SUBJECT_NO).get().status()).isEqualTo(DidStatus.PENDING_ISSUE);

        // 运营重试（KMS 恢复）→ 转有效并补齐留痕
        final DidIssuanceService.IssuanceResult retried = service.retry(SUBJECT_NO);
        assertThat(retried.status()).isEqualTo(DidStatus.ACTIVE);
        assertThat(repository.logs()).hasSize(1);
    }

    @Test
    void retryWithoutPendingRecordIsRejected() {
        assertThatThrownBy(() -> service.retry(SUBJECT_NO))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_NO_PENDING_ISSUANCE));
    }

    @Test
    void revokeRequiresReasonAndRejectsNonActive() {
        service.issue(SUBJECT_NO);
        final String did = "did:ctds:" + SUBJECT_NO + ".1";

        assertThatThrownBy(() -> service.revoke(did, "  "))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_REVOKE_REASON_REQUIRED));
        assertThatThrownBy(() -> service.revoke("did:ctds:unknown.1", "理由"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_REVOKE_NOT_ACTIVE));

        service.revoke(did, "私钥疑似泄露");
        // 吊销不可逆：再吊销同一 DID → 非有效状态拒绝
        assertThatThrownBy(() -> service.revoke(did, "再次吊销"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_REVOKE_NOT_ACTIVE));
    }

    @Test
    void revokeTransitionsToRevokedWithFiveElementLog() {
        service.issue(SUBJECT_NO);
        final String did = "did:ctds:" + SUBJECT_NO + ".1";

        final DidIssuanceService.RevocationResult result = service.revoke(did, "私钥疑似泄露");

        assertThat(result.status()).isEqualTo(DidStatus.REVOKED);
        // 留痕五要素：operator + occurred_at + reason + did + 状态变更
        final DidOperationLog log = repository.logs().get(repository.logs().size() - 1);
        assertThat(log.operation()).isEqualTo("REVOKE");
        assertThat(log.reason()).isEqualTo("私钥疑似泄露");
        assertThat(log.did()).isEqualTo(did);
        assertThat(log.statusFrom()).isEqualTo("ACTIVE");
        assertThat(log.statusTo()).isEqualTo("REVOKED");
        assertThat(log.occurredAt()).isNotNull();
        // guard 释放：该主体已无非吊销行
        assertThat(repository.findActiveOrPending(SUBJECT_NO)).isEmpty();
    }

    @Test
    void reissueCreatesNewDidAndKeyRefAndRetainsOld() {
        service.issue(SUBJECT_NO);
        final String oldDid = "did:ctds:" + SUBJECT_NO + ".1";
        service.revoke(oldDid, "私钥疑似泄露");

        final DidIssuanceService.IssuanceResult reissued = service.reissue(SUBJECT_NO);

        assertThat(reissued.did()).isEqualTo("did:ctds:" + SUBJECT_NO + ".2");
        assertThat(reissued.keyRef()).isEqualTo("did-" + SUBJECT_NO + "-2");
        assertThat(reissued.did()).isNotEqualTo(oldDid);
        // 旧 DID 仍保留（REVOKED 可追溯），新密钥引用与旧不同
        assertThat(repository.findByDid(oldDid).get().status()).isEqualTo(DidStatus.REVOKED);
        assertThat(repository.activeCount()).isEqualTo(1);
    }

    @Test
    void reissueWithoutRevokedRecordIsRejected() {
        assertThatThrownBy(() -> service.reissue(SUBJECT_NO))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_NO_REVOKED_TO_REISSUE));
    }

    @Test
    void invalidSubjectNoAndReasonBoundsAreRejected() {
        assertThatThrownBy(() -> service.issue(null))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        assertThatThrownBy(() -> service.issue("  "))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        assertThatThrownBy(() -> service.issue("x".repeat(33)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        assertThatThrownBy(() -> service.revoke(null, "理由"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        service.issue(SUBJECT_NO);
        assertThatThrownBy(() -> service.revoke("did:ctds:" + SUBJECT_NO + ".1", "x".repeat(257)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
    }

    @Test
    void completeIssuanceOnNonPendingIdentityIsRejectedWithoutLog() {
        // 交错用例（评审④ P1）：行状态已变（如并发吊销/外部变更）后完成签发 → 乐观门槛拒绝，
        // 不产生 ACTIVE、不落重复 ISSUE 留痕（与 revoke 对称）
        repository.createPending(SUBJECT_NO, 1, LocalDateTime.now());
        final DidIdentity pending = repository.findActiveOrPending(SUBJECT_NO).orElseThrow();
        repository.forceRevoke(pending.id());
        final DidOperationLog op = new DidOperationLog("did:ctds:" + SUBJECT_NO + ".1", SUBJECT_NO, "ISSUE",
                "SYSTEM", null, "did-" + SUBJECT_NO + "-1", "PENDING_ISSUE", "ACTIVE", LocalDateTime.now());

        assertThatThrownBy(() -> repository.completeIssuance(pending.id(), kmsClient.publicKeyHex(), "{}", op))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_ISSUANCE_INTERNAL_ERROR));
        assertThat(repository.activeCount()).isZero();
        assertThat(repository.logs()).isEmpty();
    }

    @Test
    void concurrentIssueProducesSingleActiveIdentityAndSingleLog() throws Exception {
        // 真实并发（评审④ P3-A）：8 线程并发触发同一主体 → 乐观门槛 + uk_guard 兜底 → 恰一条 ACTIVE + 一条留痕
        final int threadCount = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            final CountDownLatch ready = new CountDownLatch(threadCount);
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<DidIssuanceService.IssuanceResult>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.issue(SUBJECT_NO);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (final Future<DidIssuanceService.IssuanceResult> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(repository.activeCount()).isEqualTo(1);
        assertThat(repository.logs()).hasSize(1);
        assertThat(repository.logs().get(0).operation()).isEqualTo("ISSUE");
    }

    @Test
    void logsDoNotExposePrivateKeyMaterial(final CapturedOutput output) {
        // 日志面锚定（评审④ P2-A，hifi B3 三面之一）：签发/吊销/失败路径的日志不含任何 64 位 hex 形态密钥材料
        kmsClient.failNext();
        assertThat(service.issue(SUBJECT_NO).status()).isEqualTo(DidStatus.PENDING_ISSUE);
        assertThat(service.retry(SUBJECT_NO).status()).isEqualTo(DidStatus.ACTIVE);
        service.revoke("did:ctds:" + SUBJECT_NO + ".1", "私钥疑似泄露");

        assertThat(output.getAll())
                .doesNotContain("aa".repeat(32))
                .doesNotContainPattern(PRIVATE_KEY_PATTERN);
    }

    private static JsonNode parseDocument(final String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (final Exception e) {
            throw new AssertionError("DID 文档不是合法 JSON", e);
        }
    }

    /** KMS 客户端桩：可控失败（一次） + 记录生成编号，返回固定公钥 hex。 */
    private static final class StubKmsClient implements DidKmsClient {
        private boolean failOnce;

        void failNext() {
            failOnce = true;
        }

        String publicKeyHex() {
            return "04" + "ab".repeat(64);
        }

        @Override
        public String createKeyPair(final String keyRef) {
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("KMS 不可达");
            }
            return publicKeyHex();
        }

        @Override
        public String sign(final String keyRef, final String dataBase64) {
            throw new UnsupportedOperationException("签发测试未使用");
        }
    }

    /** 内存仓储桩：语义对齐 DidJdbcRepository（uk_guard 幂等守卫 + guard_key 释放 + completeIssuance 乐观门槛）。
     *  全部方法同步（并发用例要求 find→insert/update 原子）。 */
    private static final class FakeDidRepository implements DidRepository {
        private final Map<Long, DidIdentity> rows = new LinkedHashMap<>();
        private final List<DidOperationLog> logs = new ArrayList<>();
        private long nextId = 1;

        synchronized int activeCount() {
            return (int) rows.values().stream().filter(r -> r.status() == DidStatus.ACTIVE).count();
        }

        synchronized List<DidOperationLog> logs() {
            return logs;
        }

        @Override
        public synchronized Optional<DidIdentity> findActiveOrPending(final String subjectNo) {
            return rows.values().stream()
                    .filter(r -> r.subjectNo().equals(subjectNo) && r.guardKey() != null)
                    .findFirst();
        }

        @Override
        public synchronized Optional<DidIdentity> findPending(final String subjectNo) {
            return rows.values().stream()
                    .filter(r -> r.subjectNo().equals(subjectNo) && r.status() == DidStatus.PENDING_ISSUE)
                    .findFirst();
        }

        @Override
        public synchronized Optional<DidIdentity> findLatestRevoked(final String subjectNo) {
            return rows.values().stream()
                    .filter(r -> r.subjectNo().equals(subjectNo) && r.status() == DidStatus.REVOKED)
                    .max(Comparator.comparingInt(DidIdentity::issuanceSeq));
        }

        @Override
        public synchronized Optional<DidIdentity> findByDid(final String did) {
            return rows.values().stream().filter(r -> did.equals(r.did())).findFirst();
        }

        @Override
        public synchronized DidIdentity createPending(final String subjectNo, final int issuanceSeq,
                final LocalDateTime now) {
            final Optional<DidIdentity> existing = findActiveOrPending(subjectNo);
            if (existing.isPresent()) {
                return existing.get();
            }
            final long id = nextId++;
            final DidIdentity pending = new DidIdentity(id, subjectNo, issuanceSeq, null,
                    DidStatus.PENDING_ISSUE, null, null, null, subjectNo, now, now);
            rows.put(id, pending);
            return pending;
        }

        @Override
        public synchronized boolean completeIssuance(final long identityId, final String publicKeyHex,
                final String documentJson, final DidOperationLog operationLog) {
            final DidIdentity old = rows.get(identityId);
            if (old == null || old.status() != DidStatus.PENDING_ISSUE) {
                // 乐观门槛（与 DidJdbcRepository 一致）：已 ACTIVE → 并发完成幂等返回 false；其他 → 内部错误
                if (old != null && old.status() == DidStatus.ACTIVE) {
                    return false;
                }
                throw new BizException(DidErrorCodes.DID_ISSUANCE_INTERNAL_ERROR, "签发处理失败，请重试");
            }
            rows.put(identityId, new DidIdentity(identityId, operationLog.subjectNo(), old.issuanceSeq(),
                    operationLog.did(), DidStatus.ACTIVE, publicKeyHex, operationLog.keyRef(), documentJson,
                    operationLog.subjectNo(), old.createdAt(), operationLog.occurredAt()));
            logs.add(operationLog);
            return true;
        }

        @Override
        public synchronized int nextIssuanceSeq(final String subjectNo) {
            return rows.values().stream().filter(r -> r.subjectNo().equals(subjectNo))
                    .mapToInt(DidIdentity::issuanceSeq).max().orElse(0) + 1;
        }

        @Override
        public synchronized void revoke(final long identityId, final DidOperationLog operationLog) {
            final DidIdentity old = rows.get(identityId);
            rows.put(identityId, new DidIdentity(identityId, operationLog.subjectNo(), old.issuanceSeq(),
                    old.did(), DidStatus.REVOKED, old.publicKeyHex(), old.keyRef(), old.documentJson(), null,
                    old.createdAt(), operationLog.occurredAt()));
            logs.add(operationLog);
        }

        /** 测试辅助：模拟行状态被外部变更为已吊销（交错用例）。 */
        synchronized void forceRevoke(final long identityId) {
            final DidIdentity old = rows.get(identityId);
            rows.put(identityId, new DidIdentity(identityId, old.subjectNo(), old.issuanceSeq(), old.did(),
                    DidStatus.REVOKED, null, null, null, null, old.createdAt(), old.updatedAt()));
        }

        @Override
        public synchronized void insertVerificationLog(final VerificationLog log) {
            throw new UnsupportedOperationException("签发测试未使用");
        }

        @Override
        public synchronized List<DidIdentity> findRecords(final String subjectNo, final String status,
                final int offset, final int limit) {
            throw new UnsupportedOperationException("签发测试未使用");
        }

        @Override
        public synchronized long countRecords(final String subjectNo, final String status) {
            throw new UnsupportedOperationException("签发测试未使用");
        }

        @Override
        public synchronized List<DidOperationLog> findOperationLogs(final String did) {
            throw new UnsupportedOperationException("签发测试未使用");
        }

        @Override
        public synchronized List<VerificationLog> findVerificationLogs(final String did, final int offset,
                final int limit) {
            throw new UnsupportedOperationException("签发测试未使用");
        }

        @Override
        public synchronized long countVerificationLogs(final String did) {
            throw new UnsupportedOperationException("签发测试未使用");
        }
    }
}
