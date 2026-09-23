package com.ctds.did.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.errorcode.BizException;
import com.ctds.did.domain.DidErrorCodes;
import com.ctds.did.domain.DidIdentity;
import com.ctds.did.domain.DidStatus;
import com.ctds.did.domain.SignatureVerifier;
import com.ctds.did.domain.SubjectAdmission;
import com.ctds.did.domain.SubjectStatusPort;
import com.ctds.did.domain.VerificationLog;
import com.ctds.did.domain.VerificationOutcome;
import com.ctds.did.domain.VerificationReason;
import com.ctds.did.support.DidRepositoryStub;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DID 验证单元测试（WBS-3.1.9 行为 3 B5~B12 + 边界值表全分支）：
 * 三查逐一否定（签名/状态/绑定）、不可用如实表征、未登记、输入边界、内部错误、留痕字段与无数据原文。
 */
class DidVerificationServiceTest {

    private static final String SUBJECT_NO = "S20260922000001";
    private static final String DID = "did:ctds:S20260922000001.1";
    private static final String PUBLIC_KEY_HEX = "04" + "ab".repeat(64);
    private static final byte[] DATA = "身份主张：我是该 DID 持有者".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGNATURE = "sm2-der-signature-bytes".getBytes(StandardCharsets.UTF_8);

    private DidRepositoryStub repository;
    private StubSignatureVerifier verifier;
    private StubSubjectStatusPort subjectStatusPort;
    private DidVerificationService service;

    @BeforeEach
    void setUp() {
        repository = new DidRepositoryStub();
        verifier = new StubSignatureVerifier();
        subjectStatusPort = new StubSubjectStatusPort();
        service = new DidVerificationService(repository, verifier, subjectStatusPort);
    }

    @Test
    void verifyPassesWhenAllThreeChecksPass() {
        repository.put(identity(DidStatus.ACTIVE));

        final DidVerificationService.VerificationResult result =
                service.verify(DID, base64(DATA), base64(SIGNATURE));

        assertThat(result.result()).isEqualTo(VerificationOutcome.PASS);
        assertThat(result.reason()).isNull();
        assertThat(result.verifiedAt()).isNotNull();
        // 留痕三要素：did/result/occurredAt（PASS 无原因）
        final VerificationLog log = repository.verificationLogs().get(0);
        assertThat(log.did()).isEqualTo(DID);
        assertThat(log.result()).isEqualTo(VerificationOutcome.PASS);
        assertThat(log.reason()).isNull();
        assertThat(log.occurredAt()).isNotNull();
    }

    @Test
    void verifyFailsWithSignatureInvalidWhenDataTampered() {
        repository.put(identity(DidStatus.ACTIVE));
        verifier.result = false;

        final DidVerificationService.VerificationResult result =
                service.verify(DID, base64("被篡改的数据".getBytes(StandardCharsets.UTF_8)), base64(SIGNATURE));

        assertThat(result.result()).isEqualTo(VerificationOutcome.FAIL);
        assertThat(result.reason()).isEqualTo(VerificationReason.SIGNATURE_INVALID);
        assertThat(repository.verificationLogs()).hasSize(1);
        assertThat(repository.verificationLogs().get(0).reason())
                .isEqualTo(VerificationReason.SIGNATURE_INVALID);
    }

    @Test
    void verifyFailsWithRevokedWhenIdentityRevoked() {
        repository.put(identity(DidStatus.REVOKED));

        final DidVerificationService.VerificationResult result =
                service.verify(DID, base64(DATA), base64(SIGNATURE));

        assertThat(result.result()).isEqualTo(VerificationOutcome.FAIL);
        assertThat(result.reason()).isEqualTo(VerificationReason.REVOKED);
        assertThat(repository.verificationLogs().get(0).reason()).isEqualTo(VerificationReason.REVOKED);
    }

    @Test
    void verifyFailsWithSubjectBindingFailedWhenNotAdmitted() {
        repository.put(identity(DidStatus.ACTIVE));
        subjectStatusPort.admission = SubjectAdmission.NOT_ADMITTED;

        final DidVerificationService.VerificationResult result =
                service.verify(DID, base64(DATA), base64(SIGNATURE));

        assertThat(result.result()).isEqualTo(VerificationOutcome.FAIL);
        assertThat(result.reason()).isEqualTo(VerificationReason.SUBJECT_BINDING_FAILED);
    }

    @Test
    void verifyUnavailableWhenBindingServiceUnavailable() {
        repository.put(identity(DidStatus.ACTIVE));
        subjectStatusPort.admission = SubjectAdmission.UNAVAILABLE;

        final DidVerificationService.VerificationResult result =
                service.verify(DID, base64(DATA), base64(SIGNATURE));

        // 系统态不冒充"验证不通过"：UNAVAILABLE 独立结论 + 留痕如实记录
        assertThat(result.result()).isEqualTo(VerificationOutcome.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(VerificationReason.BINDING_UNAVAILABLE);
        assertThat(repository.verificationLogs().get(0).result()).isEqualTo(VerificationOutcome.UNAVAILABLE);
    }

    @Test
    void verifyUnknownDidFailsNotRegisteredWithLog() {
        final DidVerificationService.VerificationResult result =
                service.verify("did:ctds:S20260922009999.1", base64(DATA), base64(SIGNATURE));

        assertThat(result.result()).isEqualTo(VerificationOutcome.FAIL);
        assertThat(result.reason()).isEqualTo(VerificationReason.NOT_REGISTERED);
        assertThat(repository.verificationLogs()).hasSize(1);
    }

    @Test
    void verifyRejectsInvalidInputs() {
        repository.put(identity(DidStatus.ACTIVE));
        // did 非法
        assertThatThrownBy(() -> service.verify(null, base64(DATA), base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        assertThatThrownBy(() -> service.verify("not-a-did", base64(DATA), base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_PARAM_INVALID));
        // data/signature 缺失
        assertThatThrownBy(() -> service.verify(DID, null, base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
        assertThatThrownBy(() -> service.verify(DID, base64(DATA), "  "))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
        // 非法 Base64
        assertThatThrownBy(() -> service.verify(DID, "not-base64!!", base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
        // Base64 解码后为空
        assertThatThrownBy(() -> service.verify(DID, base64(new byte[0]), base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
        // signature 超上限（>512 字节）
        assertThatThrownBy(() -> service.verify(DID, base64(DATA), base64(new byte[1024])))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
        // 以上输入类拒绝均不落留痕（非"一次验证"）
        assertThat(repository.verificationLogs()).isEmpty();
    }

    @Test
    void verifyRejectsDataExceedingUpperBound() {
        repository.put(identity(DidStatus.ACTIVE));
        // 边界值表：data 解码后超上限（>1MB）→ 1005C0004（与 signature >512 同类，输入类拒绝不落留痕）
        final byte[] oversized = new byte[1024 * 1024 + 1];
        assertThatThrownBy(() -> service.verify(DID, base64(oversized), base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INPUT_INVALID));
        assertThat(repository.verificationLogs()).isEmpty();
    }

    @Test
    void verifyThrowsInternalErrorWhenVerifierFailsUnexpectedly() {
        repository.put(identity(DidStatus.ACTIVE));
        verifier.throwing = true;

        assertThatThrownBy(() -> service.verify(DID, base64(DATA), base64(SIGNATURE)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(DidErrorCodes.DID_VERIFICATION_INTERNAL_ERROR));
        // 内部错误同样不落"验证结论"留痕（无结论可记）
        assertThat(repository.verificationLogs()).isEmpty();
    }

    @Test
    void verificationLogKeepsThreeElementsAndNoDataRawForEveryAttempt() {
        repository.put(identity(DidStatus.ACTIVE));
        service.verify(DID, base64(DATA), base64(SIGNATURE));               // PASS
        verifier.result = false;
        service.verify(DID, base64(DATA), base64(SIGNATURE));               // FAIL
        subjectStatusPort.admission = SubjectAdmission.UNAVAILABLE;         // UNAVAILABLE
        verifier.result = true;
        service.verify(DID, base64(DATA), base64(SIGNATURE));

        assertThat(repository.verificationLogs()).hasSize(3);
        // B10：留痕只含 did/result/reason/occurredAt 四要素；记录值与文本均不得出现数据/签名原文（值 + 结构双重锚定）
        final String dataB64 = base64(DATA);
        final String signatureB64 = base64(SIGNATURE);
        assertThat(repository.verificationLogs()).allSatisfy(log -> {
            assertThat(log.did()).isEqualTo(DID);
            assertThat(log.result()).isNotNull();
            assertThat(log.occurredAt()).isNotNull();
            assertThat(log.toString()).doesNotContain(dataB64.substring(0, 16))
                    .doesNotContain(signatureB64.substring(0, 16));
        });
    }

    private static DidIdentity identity(final DidStatus status) {
        return new DidIdentity(1L, SUBJECT_NO, 1, DID, status, PUBLIC_KEY_HEX, "did-" + SUBJECT_NO + "-1",
                "{}", status == DidStatus.REVOKED ? null : SUBJECT_NO,
                LocalDateTime.of(2026, 9, 22, 20, 45, 17), LocalDateTime.of(2026, 9, 22, 20, 45, 17));
    }

    private static String base64(final byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 验签桩：可控 通过/不通过/抛异常。 */
    private static final class StubSignatureVerifier implements SignatureVerifier {
        private boolean result = true;
        private boolean throwing;

        @Override
        public boolean verify(final byte[] data, final byte[] signature, final String publicKeyHex) {
            if (throwing) {
                throw new IllegalStateException("公钥数据异常");
            }
            assertThat(publicKeyHex).isEqualTo(PUBLIC_KEY_HEX);
            return result;
        }
    }

    /** 主体状态桩：可控三态。 */
    private static final class StubSubjectStatusPort implements SubjectStatusPort {
        private SubjectAdmission admission = SubjectAdmission.ADMITTED;

        @Override
        public SubjectAdmission check(final String subjectNo) {
            assertThat(subjectNo).isEqualTo(SUBJECT_NO);
            return admission;
        }
    }
}