package com.ctds.subject.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ctds.common.crypto.KeyProvider;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.std.StdAdapterErrorCodes;
import com.ctds.std.certification.CertificationStandardApi;
import com.ctds.std.certification.LegalPersonVerification;
import com.ctds.std.certification.OcrRecognition;
import com.ctds.subject.domain.CertMaterial;
import com.ctds.subject.domain.CertVerificationLog;
import com.ctds.subject.domain.CertificationRepository;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectErrorCodes;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
import com.ctds.subject.domain.TriggerRole;
import com.ctds.subject.domain.VerificationConclusion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 认证应用服务单测（hifi B4~B12 业务分支；HTTP 封套与库表由集成测试覆盖）。
 * 覆盖：OCR 加密落库、差异阻断、核验前置校验、当日上限与 Clock 拨次日恢复、渠道异常 fail-fast、
 * 自动流转、结束认证。Sm4Service 用真实实现 + 固定密钥桩（加解密往返可信）。
 */
class CertificationServiceTest {

    private static final String SUBJECT_NO = "S20260913000901";
    private static final String APPLICANT = "applicant-01";
    private static final String KEY_REF = "subject-cert-material";
    /** 16 字节固定测试密钥（仅测试用，非真实密钥材料）。 */
    private static final byte[] TEST_KEY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private SubjectRepository subjectRepository;
    private CertificationRepository certificationRepository;
    private CertificationStandardApi channel;
    private Sm4Service sm4Service;
    private OwnershipGuard ownershipGuard;
    private SubjectStatusService statusService;
    private AuditRecorder auditRecorder;
    private CertificationProperties properties;
    private CertificationService service;
    private Subject subject;

    @BeforeEach
    void setUp() {
        subjectRepository = mock(SubjectRepository.class);
        certificationRepository = mock(CertificationRepository.class);
        channel = mock(CertificationStandardApi.class);
        sm4Service = new Sm4Service(stubKeyProvider());
        ownershipGuard = mock(OwnershipGuard.class);
        statusService = mock(SubjectStatusService.class);
        auditRecorder = mock(AuditRecorder.class);
        properties = new CertificationProperties();
        service = new CertificationService(subjectRepository, certificationRepository, channel, sm4Service,
                ownershipGuard, statusService, auditRecorder, properties, Clock.systemDefaultZone());

        subject = new Subject(9L, SUBJECT_NO, "认证演示公司", "91330100MA27X8AB01", SubjectType.ENTERPRISE,
                "杭州市XX区XX路88号", "李四", "13800001234", "admin001", APPLICANT, SubjectStatus.PENDING_CERT,
                LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(subject));
    }

    @Test
    void uploadEncryptsImageAndRawResultBeforeStoring() {
        when(channel.ocrBusinessLicense(any(), anyString())).thenReturn(
                new OcrRecognition(true, "认证演示公司", "91330100MA27X8AB01", "张伟", "杭州市XX区XX路88号", "识别成功"));
        final byte[] image = "fake-jpeg-bytes".getBytes(StandardCharsets.US_ASCII);

        final LicenseUploadResult result = service.uploadLicense(SUBJECT_NO, image, "A1.jpg");

        assertThat(result.ocrResult().uscc()).isEqualTo("91330100MA27X8AB01");
        final ArgumentCaptor<CertMaterial> material = ArgumentCaptor.forClass(CertMaterial.class);
        verify(certificationRepository).replaceMaterial(material.capture());
        assertThat(material.getValue().contentCipher()).isNotEqualTo(image);
        assertThat(sm4Service.decrypt(material.getValue().contentCipher(), KEY_REF)).isEqualTo(image);
        assertThat(sm4Service.decrypt(material.getValue().ocrRawCipher(), KEY_REF))
                .asString(StandardCharsets.UTF_8).contains("91330100MA27X8AB01");
        verify(certificationRepository).appendVerification(any(CertVerificationLog.class));
    }

    @Test
    void unrecognizableImageStoresCipherOnlyAndRejectsWithoutElements() {
        when(channel.ocrBusinessLicense(any(), anyString()))
                .thenReturn(OcrRecognition.unrecognizable("无法识别请重传"));

        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "other.png"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_LICENSE_UNRECOGNIZABLE));

        final ArgumentCaptor<CertMaterial> material = ArgumentCaptor.forClass(CertMaterial.class);
        verify(certificationRepository).replaceMaterial(material.capture());
        assertThat(material.getValue().ocrRecognizable()).isFalse();
        assertThat(material.getValue().ocrRawCipher()).isNull();
        assertThat(material.getValue().ocrUscc()).isNull();
    }

    @Test
    void confirmationWithMismatchedUsccIsBlocked() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));

        assertThatThrownBy(() -> service.confirmLicense(SUBJECT_NO,
                new ConfirmationCommand("认证演示公司", "91330100MA27X8AB99", "张伟", "地址")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_USCC_MISMATCH));
    }

    @Test
    void verificationWithoutConfirmedLicenseIsBlocked() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(unconfirmedMaterial()));

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟",
                "11010119900101123X")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_LICENSE_NOT_CONFIRMED));
    }

    @Test
    void verificationWithMismatchedLegalPersonIsBlocked() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("王芳",
                "11010119900101123X")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_LEGAL_PERSON_MISMATCH));
    }

    @Test
    void legalPersonVerificationPassTransitionsToPendingReview() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(0);
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenReturn(new LegalPersonVerification(true, "MOCK-1", null));

        final VerificationResult result = service.verifyLegalPerson(SUBJECT_NO,
                new VerificationCommand("张伟", "11010119900101123X"));

        assertThat(result.conclusion()).isEqualTo("PASS");
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_REVIEW.name());
        verify(statusService).transition(eq(9L), eq(SubjectStatus.PENDING_CERT),
                eq(SubjectStatus.PENDING_REVIEW), eq(TriggerRole.SYSTEM), anyString(), anyString());
        final ArgumentCaptor<CertVerificationLog> logEntry = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(logEntry.capture());
        assertThat(logEntry.getValue().conclusion()).isEqualTo(VerificationConclusion.PASS);
        assertThat(logEntry.getValue().counted()).isFalse();
        assertThat(logEntry.getValue().legalPersonIdCipher()).isNotEqualTo("11010119900101123X");
    }

    @Test
    void dailyFailureLimitReachedBlocksSixthAttempt() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(5);

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟",
                "11010119900101123X")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_VERIFY_LIMIT_REACHED));
        verify(channel, never()).verifyLegalPerson(anyString(), anyString());
    }

    /** Clock 拨次日口径：since 参数随 Clock 前移，跨日计数自然恢复（规格行为 3 第 3 条）。 */
    @Test
    void nextDayClockMovesFailureWindowForwardForAutoRecovery() {
        final Clock nextDay = Clock.fixed(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant(), ZoneId.systemDefault());
        final CertificationService withNextDayClock = new CertificationService(subjectRepository,
                certificationRepository, channel, sm4Service, ownershipGuard, statusService, auditRecorder,
                properties, nextDay);
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(2);
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenReturn(new LegalPersonVerification(true, "MOCK-2", null));

        final VerificationResult result = withNextDayClock.verifyLegalPerson(SUBJECT_NO,
                new VerificationCommand("张伟", "11010119900101123X"));

        assertThat(result.conclusion()).isEqualTo("PASS");
        // 上限检查与剩余次数计算各取一次当日窗口（窗口随 Clock 前移 = 跨日自动恢复）
        verify(certificationRepository, times(2)).countFailuresSince(eq(9L),
                eq(LocalDate.now().plusDays(1).atStartOfDay()));
    }

    @Test
    void channelTechnicalErrorFailsFastWithoutCountingOrTransition() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenThrow(StdAdapterErrorCodes.channelUnavailable());

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟",
                "11010119900101123X")))
                .isInstanceOf(CertChannelUnavailableException.class)
                .hasMessage("认证服务暂不可用，请稍后重试");

        final ArgumentCaptor<CertVerificationLog> logEntry = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(logEntry.capture());
        assertThat(logEntry.getValue().conclusion()).isEqualTo(VerificationConclusion.CHANNEL_ERROR);
        assertThat(logEntry.getValue().counted()).isFalse();
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), any(), any());
        // 渠道技术异常非权限拒绝，不留审计（fail-fast 出站即可）
    }

    @Test
    void abandonCertificationTransitionsPendingCertToCertFailed() {
        final CertificationActionResult result = service.abandonCertification(SUBJECT_NO);

        assertThat(result.status()).isEqualTo(SubjectStatus.CERT_FAILED);
        verify(statusService).transition(eq(9L), eq(SubjectStatus.PENDING_CERT),
                eq(SubjectStatus.CERT_FAILED), eq(TriggerRole.APPLICANT), anyString(), anyString());
    }

    @Test
    void certificationActionsOnWrongStatusAreRejected() {
        final Subject admitted = new Subject(9L, SUBJECT_NO, "认证演示公司", "91330100MA27X8AB01",
                SubjectType.ENTERPRISE, "杭州市XX区XX路88号", "李四", "13800001234", "admin001", APPLICANT,
                SubjectStatus.ADMITTED, LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(admitted));

        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "A1.jpg"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.abandonCertification(SUBJECT_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED));
    }

    @Test
    void oversizedOrWrongFormatImageIsRejectedBeforeChannel() {
        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, new byte[6 * 1024 * 1024], "A1.jpg"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "A1.gif"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        verify(channel, never()).ocrBusinessLicense(any(), anyString());
    }

    private KeyProvider stubKeyProvider() {
        return keyRef -> {
            if (KEY_REF.equals(keyRef)) {
                return TEST_KEY;
            }
            throw new IllegalStateException("unexpected keyRef " + keyRef);
        };
    }

    private CertMaterial unconfirmedMaterial() {
        return new CertMaterial(1L, 9L, CertMaterial.TYPE_BUSINESS_LICENSE, "A1.jpg",
                "sha256", "cipher".getBytes(), "raw".getBytes(), "91330100MA27X8AB01", "张伟",
                true, null, null, null, null, null, LocalDateTime.now());
    }

    private CertMaterial confirmedMaterial(final String ocrUscc) {
        return new CertMaterial(1L, 9L, CertMaterial.TYPE_BUSINESS_LICENSE, "A1.jpg",
                "sha256", "cipher".getBytes(), "raw".getBytes(), ocrUscc, "张伟",
                true, "认证演示公司", "91330100MA27X8AB01", "张伟", "杭州市XX区XX路88号",
                LocalDateTime.now(), LocalDateTime.now());
    }
}
