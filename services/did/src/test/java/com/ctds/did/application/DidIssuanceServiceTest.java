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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DID 签发应用服务单元测试（WBS-3.1.8 行为清单 B1~B6 全分支，仓储用内存桩）：
 * 自动签发（留痕四要素）/ 幂等 / 失败可重试 / 吊销（理由必填 + 五要素 + 不可逆）/ 重签（新序号新密钥）。
 */
class DidIssuanceServiceTest {

    private static final String SUBJECT_NO = "S20260920000001";

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
    }

    /** 内存仓储桩：语义对齐 DidJdbcRepository（uk_guard 幂等守卫 + guard_key 释放）。 */
    private static final class FakeDidRepository implements DidRepository {
        private final Map<Long, DidIdentity> rows = new LinkedHashMap<>();
        private final List<DidOperationLog> logs = new ArrayList<>();
        private long nextId = 1;

        int activeCount() {
            return (int) rows.values().stream().filter(r -> r.status() == DidStatus.ACTIVE).count();
        }

        List<DidOperationLog> logs() {
            return logs;
        }

        @Override
        public Optional<DidIdentity> findActiveOrPending(final String subjectNo) {
            return rows.values().stream()
                    .filter(r -> r.subjectNo().equals(subjectNo) && r.guardKey() != null)
                    .findFirst();
        }

        @Override
        public Optional<DidIdentity> findPending(final String subjectNo) {
            return rows.values().stream()
                    .filter(r -> r.subjectNo().equals(subjectNo) && r.status() == DidStatus.PENDING_ISSUE)
                    .findFirst();
        }

        @Override
        public Optional<DidIdentity> findLatestRevoked(final String subjectNo) {
            return rows.values().stream()
                    .filter(r -> r.subjectNo().equals(subjectNo) && r.status() == DidStatus.REVOKED)
                    .max(Comparator.comparingInt(DidIdentity::issuanceSeq));
        }

        @Override
        public Optional<DidIdentity> findByDid(final String did) {
            return rows.values().stream().filter(r -> did.equals(r.did())).findFirst();
        }

        @Override
        public DidIdentity createPending(final String subjectNo, final int issuanceSeq, final LocalDateTime now) {
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
        public void completeIssuance(final long identityId, final String publicKeyHex, final String documentJson,
                final DidOperationLog operationLog) {
            final DidIdentity old = rows.get(identityId);
            rows.put(identityId, new DidIdentity(identityId, operationLog.subjectNo(), old.issuanceSeq(),
                    operationLog.did(), DidStatus.ACTIVE, publicKeyHex, operationLog.keyRef(), documentJson,
                    operationLog.subjectNo(), old.createdAt(), operationLog.occurredAt()));
            logs.add(operationLog);
        }

        @Override
        public int nextIssuanceSeq(final String subjectNo) {
            return rows.values().stream().filter(r -> r.subjectNo().equals(subjectNo))
                    .mapToInt(DidIdentity::issuanceSeq).max().orElse(0) + 1;
        }

        @Override
        public void revoke(final long identityId, final DidOperationLog operationLog) {
            final DidIdentity old = rows.get(identityId);
            rows.put(identityId, new DidIdentity(identityId, operationLog.subjectNo(), old.issuanceSeq(),
                    old.did(), DidStatus.REVOKED, old.publicKeyHex(), old.keyRef(), old.documentJson(), null,
                    old.createdAt(), operationLog.occurredAt()));
            logs.add(operationLog);
        }
    }
}
