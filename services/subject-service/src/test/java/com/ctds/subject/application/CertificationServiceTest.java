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
import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.std.StdAdapterErrorCodes;
import com.ctds.std.certification.CertificationStandardApi;
import com.ctds.std.certification.GovCaVerification;
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
 * 覆盖：OCR 加密落库、差异阻断、核验前置校验（含身份证校验位）、当日上限与 Clock 拨次日恢复、
 * 渠道异常 fail-fast（编程错误重抛/根因日志）、CERT_FAILED 再核验路径、结束认证。
 * Sm4Service/Sm3Service 用真实实现 + 固定密钥桩（加解密往返可信）。
 * 测试身份证号均为按 GB 11643 校验位规则构造的虚构号码，非真实个人信息。
 */
class CertificationServiceTest {

    private static final String SUBJECT_NO = "S20260913000901";
    private static final String APPLICANT = "applicant-01";
    private static final String KEY_REF = "subject-cert-material";
    private static final String CHANNEL_CODE = "mock-certification";
    /** 16 字节固定测试密钥（仅测试用，非真实密钥材料）。 */
    private static final byte[] TEST_KEY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    /** 虚构且校验位合法的 18 位号码（构造方式见 GB 11643 mod 11-2）。 */
    private static final String ID_OK = "110101199001011229";
    /** 虚构且校验位合法、尾号为 8（触发模拟渠道不通过）。 */
    private static final String ID_FAIL = "110101199001011288";

    private SubjectRepository subjectRepository;
    private CertificationRepository certificationRepository;
    private CertificationStandardApi channel;
    private Sm4Service sm4Service;
    private Sm3Service sm3Service;
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
        when(channel.channelCode()).thenReturn(CHANNEL_CODE);
        // 仓储替换语义打桩：返回带 id 的记录（replaceMaterial 契约：删旧插新并回填主键）
        when(certificationRepository.replaceMaterial(any(CertMaterial.class))).thenAnswer(invocation -> {
            final CertMaterial arg = invocation.getArgument(0);
            return new CertMaterial(1L, arg.subjectId(), arg.materialType(), arg.fileName(), arg.contentSm3(),
                    arg.contentCipher(), arg.ocrRawCipher(), arg.ocrUscc(), arg.ocrLegalPerson(),
                    arg.ocrRecognizable(), arg.confirmedName(), arg.confirmedUscc(), arg.confirmedLegalPerson(),
                    arg.confirmedRegAddress(), arg.confirmedAt(), arg.createdAt());
        });
        ownershipGuard = mock(OwnershipGuard.class);
        statusService = mock(SubjectStatusService.class);
        auditRecorder = mock(AuditRecorder.class);
        properties = new CertificationProperties();
        sm4Service = new Sm4Service(stubKeyProvider());
        sm3Service = new Sm3Service();
        service = new CertificationService(subjectRepository, certificationRepository, channel,
                sm4Service, sm3Service, ownershipGuard, statusService,
                new SubjectOpsSupport(auditRecorder), properties, Clock.systemDefaultZone());

        subject = new Subject(9L, SUBJECT_NO, "认证演示公司", "91330100MA27X8AB01", SubjectType.ENTERPRISE,
                "杭州市XX区XX路88号", "李四", "13800001234", "admin001", APPLICANT, SubjectStatus.PENDING_CERT,
                LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(subject));
    }

    @Test
    void uploadEncryptsImageAndRawResultBeforeStoring() {
        stubOcrSuccess();

        final byte[] image = "fake-jpeg-bytes".getBytes(StandardCharsets.US_ASCII);
        final LicenseUploadResult result = service.uploadLicense(SUBJECT_NO, image, "A1.jpg");

        // 归属断言已执行（评审修复：防止单测删除 guard 调用后仍全绿的失明）
        verify(ownershipGuard).requireOwnerOrReviewer(subject, CertificationService.ACTION_UPLOAD);
        assertThat(result.materialId()).isNotNull();
        assertThat(result.recognizable()).isTrue();
        assertThat(result.ocrResult().uscc()).isEqualTo("91330100MA27X8AB01");
        final ArgumentCaptor<CertMaterial> material = ArgumentCaptor.forClass(CertMaterial.class);
        verify(certificationRepository).replaceMaterial(material.capture());
        assertThat(material.getValue().contentCipher()).isNotEqualTo(image);
        assertThat(sm4Service.decrypt(material.getValue().contentCipher(), KEY_REF)).isEqualTo(image);
        assertThat(material.getValue().contentSm3()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(sm4Service.decrypt(material.getValue().ocrRawCipher(), KEY_REF))
                .asString(StandardCharsets.UTF_8).contains("91330100MA27X8AB01");
        final ArgumentCaptor<CertVerificationLog> ocrLog = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(ocrLog.capture());
        // 渠道留痕三要素：渠道标识/流水号取自渠道接口出口，不硬编码（评审修复）
        assertThat(ocrLog.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(ocrLog.getValue().channelRequestNo()).startsWith("MOCK-");
    }

    @Test
    void unrecognizableImageStoresCipherOnlyAndRejectsWithoutElements() {
        when(channel.ocrBusinessLicense(any(), anyString()))
                .thenReturn(OcrRecognition.unrecognizable("MOCK-9", "无法识别请重传"));

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
    void channelProgrammingErrorIsRethrownNotTranslated() {
        stubOcrSuccess();
        when(channel.ocrBusinessLicense(any(), anyString()))
                .thenThrow(new IllegalArgumentException("bad input"));

        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "A1.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(certificationRepository, never()).appendVerification(any(CertVerificationLog.class));
    }

    @Test
    void oversizedOrWrongFormatOrLongNameImageIsRejectedBeforeChannel() {
        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, new byte[6 * 1024 * 1024], "A1.jpg"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "A1.gif"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "A1."))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        assertThatThrownBy(() -> service.uploadLicense(SUBJECT_NO, "img".getBytes(), "x".repeat(257)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        verify(channel, never()).ocrBusinessLicense(any(), anyString());
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

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟", ID_OK)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_LICENSE_NOT_CONFIRMED));
    }

    @Test
    void verificationWithMismatchedLegalPersonIsBlocked() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("王芳", ID_OK)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_LEGAL_PERSON_MISMATCH));
    }

    @Test
    void invalidIdChecksumIsRejectedBeforeChannel() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));

        // 格式合法但校验位错误（hifi B6"证件号校验位合法"）
        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟",
                "11010119900101123X")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCodes.PARAM_INVALID);
                    assertThat(e.getMessage()).isEqualTo("身份证号校验位不正确");
                });
        verify(channel, never()).verifyLegalPerson(anyString(), anyString());
    }

    @Test
    void legalPersonVerificationPassTransitionsToPendingReview() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(0);
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenReturn(new LegalPersonVerification(true, "MOCK-1", null));

        final VerificationResult result = service.verifyLegalPerson(SUBJECT_NO,
                new VerificationCommand("张伟", ID_OK));

        assertThat(result.conclusion()).isEqualTo("PASS");
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_REVIEW.name());
        verify(statusService).transition(eq(9L), eq(SubjectStatus.PENDING_CERT),
                eq(SubjectStatus.PENDING_REVIEW), eq(TriggerRole.SYSTEM), anyString(), anyString());
        final ArgumentCaptor<CertVerificationLog> logEntry = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(logEntry.capture());
        assertThat(logEntry.getValue().conclusion()).isEqualTo(VerificationConclusion.PASS);
        assertThat(logEntry.getValue().counted()).isFalse();
        assertThat(logEntry.getValue().legalPersonIdCipher()).isNotEqualTo(ID_OK);
        assertThat(sm4Service.decryptText(logEntry.getValue().legalPersonIdCipher(), KEY_REF)).isEqualTo(ID_OK);
        assertThat(logEntry.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(logEntry.getValue().channelRequestNo()).isEqualTo("MOCK-1");
    }

    /** 认证失败主体重新核验通过 → 认证失败→待审核（lofi Q1-A 打通路径，hifi B11）。 */
    @Test
    void certFailedSubjectReverificationTransitionsFromCertFailed() {
        final Subject failed = new Subject(9L, SUBJECT_NO, "认证演示公司", "91330100MA27X8AB01",
                SubjectType.ENTERPRISE, "杭州市XX区XX路88号", "李四", "13800001234", "admin001", APPLICANT,
                SubjectStatus.CERT_FAILED, LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(SUBJECT_NO)).thenReturn(Optional.of(failed));
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(2);
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenReturn(new LegalPersonVerification(true, "MOCK-3", null));

        final VerificationResult result = service.verifyLegalPerson(SUBJECT_NO,
                new VerificationCommand("张伟", ID_OK));

        assertThat(result.conclusion()).isEqualTo("PASS");
        verify(statusService).transition(eq(9L), eq(SubjectStatus.CERT_FAILED),
                eq(SubjectStatus.PENDING_REVIEW), eq(TriggerRole.SYSTEM), anyString(), anyString());
    }

    @Test
    void dailyFailureLimitReachedBlocksSixthAttempt() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(5);

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟", ID_OK)))
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
                certificationRepository, channel, new Sm4Service(stubKeyProvider()), new Sm3Service(),
                ownershipGuard, statusService, new SubjectOpsSupport(auditRecorder), properties, nextDay);
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(certificationRepository.countFailuresSince(eq(9L), any())).thenReturn(2);
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenReturn(new LegalPersonVerification(true, "MOCK-2", null));

        final VerificationResult result = withNextDayClock.verifyLegalPerson(SUBJECT_NO,
                new VerificationCommand("张伟", ID_OK));

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

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟", ID_OK)))
                .isInstanceOf(CertChannelUnavailableException.class)
                .hasMessage("认证服务暂不可用，请稍后重试");

        final ArgumentCaptor<CertVerificationLog> logEntry = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(logEntry.capture());
        assertThat(logEntry.getValue().conclusion()).isEqualTo(VerificationConclusion.CHANNEL_ERROR);
        assertThat(logEntry.getValue().counted()).isFalse();
        assertThat(logEntry.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), any(), any());
        // 渠道技术异常非权限拒绝，不留审计（fail-fast 出站即可）
    }

    /** 非渠道类运行时异常：记录根因后同样 fail-fast（禁止吞异常，评审视角 3 修复）。 */
    @Test
    void unexpectedRuntimeErrorIsLoggedAndTranslatedToFailFast() {
        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.of(confirmedMaterial("91330100MA27X8AB01")));
        when(channel.verifyLegalPerson(anyString(), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟", ID_OK)))
                .isInstanceOf(CertChannelUnavailableException.class);

        final ArgumentCaptor<CertVerificationLog> logEntry = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(logEntry.capture());
        assertThat(logEntry.getValue().conclusion()).isEqualTo(VerificationConclusion.CHANNEL_ERROR);
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
        // 核验与确认在非待认证/认证失败状态同样被状态门槛拒绝（评审视角 4 边界补齐）
        assertThatThrownBy(() -> service.verifyLegalPerson(SUBJECT_NO, new VerificationCommand("张伟", ID_OK)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED));
        assertThatThrownBy(() -> service.confirmLicense(SUBJECT_NO,
                new ConfirmationCommand("认证演示公司", "91330100MA27X8AB01", "张伟", "地址")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED));
    }

    private void stubOcrSuccess() {
        when(channel.ocrBusinessLicense(any(), anyString())).thenReturn(
                new OcrRecognition(true, "认证演示公司", "91330100MA27X8AB01", "张伟", "杭州市XX区XX路88号",
                        "MOCK-OCR-1", "识别成功"));
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
                "sm3", "cipher".getBytes(), "raw".getBytes(), "91330100MA27X8AB01", "张伟",
                true, null, null, null, null, null, LocalDateTime.now());
    }

    private CertMaterial confirmedMaterial(final String ocrUscc) {
        return new CertMaterial(1L, 9L, CertMaterial.TYPE_BUSINESS_LICENSE, "A1.jpg",
                "sm3", "cipher".getBytes(), "raw".getBytes(), ocrUscc, "张伟",
                true, "认证演示公司", "91330100MA27X8AB01", "张伟", "杭州市XX区XX路88号",
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ==== 政务 CA 通道（WBS-3.1.4，规格行为 6 / hifi B1~B9 单测层） ====

    private static final String GOV_SUBJECT_NO = "S20260913000902";

    /** 政府部门主体（每次用例独立建档，避免污染共享 subject 桩）。 */
    private Subject govSubject() {
        final Subject gov = new Subject(10L, GOV_SUBJECT_NO, "市大数据管理局", "91330100MA27XW123X",
                SubjectType.GOV, "杭州市XX区XX路88号", "王局", "13800005678", "govadmin", APPLICANT,
                SubjectStatus.PENDING_CERT, LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(GOV_SUBJECT_NO)).thenReturn(Optional.of(gov));
        return gov;
    }

    @Test
    void govCaPassAutoTransitionsToPendingReviewAndStoresEncryptedMaterial() {
        final Subject gov = govSubject();
        when(channel.verifyGovCaCertificate(any(), anyString())).thenReturn(
                new GovCaVerification(true, "MOCK-GOV-1", null, "市大数据管理局", "11330100MA27XW1300", null));
        final byte[] cert = "fake-gov-cert-bytes".getBytes(StandardCharsets.US_ASCII);

        final GovCaCertificationResult result = service.submitGovCaCertificate(GOV_SUBJECT_NO, cert, "A3.cer");

        verify(ownershipGuard).requireOwnerOrReviewer(gov, CertificationService.ACTION_GOV_SUBMIT);
        // 渠道调用参数原样透传（评审视角 1 建议：证书字节与文件名不过改制）
        verify(channel).verifyGovCaCertificate(cert, "A3.cer");
        assertThat(result.conclusion()).isEqualTo(VerificationConclusion.PASS.name());
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_REVIEW);
        assertThat(result.failReason()).isNull();
        // 材料落库：GOV_CA_CERT 类型 + 密文形态 + SM3 精确一致（L4，hifi B6）
        final ArgumentCaptor<CertMaterial> material = ArgumentCaptor.forClass(CertMaterial.class);
        verify(certificationRepository).replaceMaterial(material.capture());
        assertThat(material.getValue().materialType()).isEqualTo(CertMaterial.TYPE_GOV_CA_CERT);
        assertThat(material.getValue().contentCipher()).isNotEqualTo(cert);
        assertThat(sm4Service.decrypt(material.getValue().contentCipher(), KEY_REF)).isEqualTo(cert);
        assertThat(material.getValue().contentSm3()).isEqualTo(sm3Service.digestHex(cert));
        assertThat(material.getValue().ocrRecognizable()).isTrue();
        // hifi 契约：ocr_uscc 等确认类列对 GOV_CA_CERT 恒为 NULL（单位要素只存 ocr_raw_cipher 密文 JSON）
        assertThat(material.getValue().ocrUscc()).isNull();
        assertThat(material.getValue().ocrLegalPerson()).isNull();
        assertThat(sm4Service.decrypt(material.getValue().ocrRawCipher(), KEY_REF))
                .asString(StandardCharsets.UTF_8)
                .contains("市大数据管理局").contains("11330100MA27XW1300");
        // 留痕三要素（渠道标识/流水号取自接口出口）+ counted 恒 0（政务无失败次数概念）
        final ArgumentCaptor<CertVerificationLog> govLog = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(govLog.capture());
        assertThat(govLog.getValue().verifyType()).isEqualTo(CertVerificationLog.TYPE_GOV_CA);
        assertThat(govLog.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(govLog.getValue().channelRequestNo()).isEqualTo("MOCK-GOV-1");
        assertThat(govLog.getValue().counted()).isFalse();
        // 通过自动流转（SYSTEM 触发 + 留痕备注），不自动入驻
        verify(statusService).transition(eq(10L), eq(SubjectStatus.PENDING_CERT),
                eq(SubjectStatus.PENDING_REVIEW), eq(TriggerRole.SYSTEM), anyString(),
                eq(CertificationService.GOV_TRANSITION_REMARK));
    }

    @Test
    void govCaFailStaysPendingCertWithReasonAndCanResubmit() {
        govSubject();
        when(channel.verifyGovCaCertificate(any(), anyString())).thenReturn(
                new GovCaVerification(false, "MOCK-GOV-2", "证书已过期（模拟渠道预置 A4）", null, null, null));

        final GovCaCertificationResult result =
                service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(), "A4.cer");

        assertThat(result.conclusion()).isEqualTo(VerificationConclusion.FAIL.name());
        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_CERT);
        assertThat(result.failReason()).contains("过期");
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), any(), any());
        final ArgumentCaptor<CertVerificationLog> govLog = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(govLog.capture());
        assertThat(govLog.getValue().conclusion()).isEqualTo(VerificationConclusion.FAIL);
        assertThat(govLog.getValue().failReason()).contains("过期");
        assertThat(govLog.getValue().counted()).isFalse();
    }

    @Test
    void govSubjectRejectedOnEnterpriseEndpointsBothWays() {
        govSubject();

        // 政务主体调企业端点（上传/确认/核验）一律 1004B0007（hifi B5 双向互斥）
        assertThatThrownBy(() -> service.uploadLicense(GOV_SUBJECT_NO, "img".getBytes(), "A1.jpg"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH));
        assertThatThrownBy(() -> service.confirmLicense(GOV_SUBJECT_NO,
                        new ConfirmationCommand("市大数据管理局", "91330100MA27XW123X", "张伟", "地址")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH));
        assertThatThrownBy(() -> service.verifyLegalPerson(GOV_SUBJECT_NO,
                        new VerificationCommand("张伟", ID_OK)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH));
        // 影像查看端点同门槛（评审视角 1 必修②：hifi 接口契约第 2 行含 license/image）
        assertThatThrownBy(() -> service.viewImage(GOV_SUBJECT_NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH));
        verify(channel, never()).ocrBusinessLicense(any(), anyString());
        verify(channel, never()).verifyLegalPerson(anyString(), anyString());
        // 反向：企业主体调政务端点同码拒绝
        assertThatThrownBy(() -> service.submitGovCaCertificate(SUBJECT_NO, "cert".getBytes(), "A3.cer"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH));
        verify(channel, never()).verifyGovCaCertificate(any(), anyString());
    }

    @Test
    void govCaChannelErrorFailsFastWithoutMaterialAndCountsNothing() {
        govSubject();
        when(channel.verifyGovCaCertificate(any(), anyString()))
                .thenThrow(StdAdapterErrorCodes.channelUnavailable());

        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(), "A3.cer"))
                .isInstanceOf(CertChannelUnavailableException.class);

        // 无半完成状态：材料不落；留痕 CHANNEL_ERROR 行照落且不计失败（行为 7 第 4 条同口径）
        verify(certificationRepository, never()).replaceMaterial(any());
        final ArgumentCaptor<CertVerificationLog> govLog = ArgumentCaptor.forClass(CertVerificationLog.class);
        verify(certificationRepository).appendVerification(govLog.capture());
        assertThat(govLog.getValue().conclusion()).isEqualTo(VerificationConclusion.CHANNEL_ERROR);
        assertThat(govLog.getValue().verifyType()).isEqualTo(CertVerificationLog.TYPE_GOV_CA);
        assertThat(govLog.getValue().channelRequestNo()).isNull();
        verify(statusService, never()).transition(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void govCaFileValidationRejectsEmptyOversizedAndWrongExtension() {
        govSubject();

        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, null, "A3.cer"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, new byte[3], "A3.exe"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        properties.setGovCertMaxBytes(2);
        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(), "A3.cer"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        // 文件名缺失 / 超长（hifi 边界值表第 3 行四项，评审视角 1 观察补齐）
        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(), null))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(),
                        "A".repeat(257) + ".cer"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCodes.PARAM_INVALID));
    }

    @Test
    void govCaAcceptsCrtAndPemExtensionsWithinWhitelist() {
        govSubject();
        when(channel.verifyGovCaCertificate(any(), anyString())).thenReturn(
                new GovCaVerification(true, "MOCK-GOV-PEM", null, "市大数据管理局", "11330100MA27XW1300", null));

        assertThat(service.submitGovCaCertificate(GOV_SUBJECT_NO, "c1".getBytes(), "A3.crt").conclusion())
                .isEqualTo(VerificationConclusion.PASS.name());
        assertThat(service.submitGovCaCertificate(GOV_SUBJECT_NO, "c2".getBytes(), "A3.pem").conclusion())
                .isEqualTo(VerificationConclusion.PASS.name());
    }

    @Test
    void govCaSubmissionBlockedOnceNotInCertifiableState() {
        // 评审视角 4 必修①：已流转待审核后（非可认证态）再提交 → 状态门槛拒绝，渠道零调用
        final Subject admitted = new Subject(11L, GOV_SUBJECT_NO, "已过认证演示局", "11330100MA27XW1307",
                SubjectType.GOV, "杭州市XX区XX路88号", "王科", "13800005678", "govadmin", APPLICANT,
                SubjectStatus.PENDING_REVIEW, LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(GOV_SUBJECT_NO)).thenReturn(Optional.of(admitted));

        assertThatThrownBy(() -> service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(), "A3.cer"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED));
        verify(channel, never()).verifyGovCaCertificate(any(), anyString());
        verify(certificationRepository, never()).replaceMaterial(any());
    }

    @Test
    void govCaCertFailedSubjectCanResubmitAndTransitionFromCertFailed() {
        // 评审视角 4 必修②：CERT_FAILED 政务主体重新提交换证 → 通过后从 CERT_FAILED 直转待审核
        final Subject failed = new Subject(12L, GOV_SUBJECT_NO, "结束认证演示局", "11330100MA27XW1308",
                SubjectType.GOV, "杭州市XX区XX路88号", "王科", "13800005678", "govadmin", APPLICANT,
                SubjectStatus.CERT_FAILED, LocalDateTime.now(), LocalDateTime.now());
        when(subjectRepository.findBySubjectNo(GOV_SUBJECT_NO)).thenReturn(Optional.of(failed));
        when(channel.verifyGovCaCertificate(any(), anyString())).thenReturn(
                new GovCaVerification(true, "MOCK-GOV-CF", null, "市大数据管理局", "11330100MA27XW1300", null));

        final GovCaCertificationResult result =
                service.submitGovCaCertificate(GOV_SUBJECT_NO, "cert".getBytes(), "A3.cer");

        assertThat(result.status()).isEqualTo(SubjectStatus.PENDING_REVIEW);
        verify(statusService).transition(eq(12L), eq(SubjectStatus.CERT_FAILED),
                eq(SubjectStatus.PENDING_REVIEW), eq(TriggerRole.SYSTEM), anyString(), anyString());
    }

    @Test
    void govProfileReturnsNullGovCaBeforeFirstSubmission() {
        // 评审视角 4 建议：未上传时 govCa 为 null（govCaProfile 空分支）+ 企业主体 govCa 恒 null 回归
        govSubject();
        when(certificationRepository.findLatestMaterial(10L, CertMaterial.TYPE_GOV_CA_CERT))
                .thenReturn(Optional.empty());
        when(certificationRepository.findVerifications(10L)).thenReturn(java.util.List.of());

        assertThat(service.profile(GOV_SUBJECT_NO).govCa()).isNull();

        when(certificationRepository.findLatestMaterial(9L, CertMaterial.TYPE_BUSINESS_LICENSE))
                .thenReturn(Optional.empty());
        assertThat(service.profile(SUBJECT_NO).govCa()).isNull();
    }

    @Test
    void govProfileReturnsGovCaSectionWithoutDailyAttempts() {
        govSubject();
        when(certificationRepository.findLatestMaterial(10L, CertMaterial.TYPE_GOV_CA_CERT))
                .thenReturn(Optional.of(new CertMaterial(7L, 10L, CertMaterial.TYPE_GOV_CA_CERT, "A3.cer",
                        "sm3", "cipher".getBytes(), "raw".getBytes(), "91330100MA27XW123X", null,
                        true, null, null, null, null, null, LocalDateTime.now())));
        when(certificationRepository.findVerifications(10L)).thenReturn(java.util.List.of(
                new CertVerificationLog(null, 10L, CertVerificationLog.TYPE_GOV_CA, CHANNEL_CODE,
                        "MOCK-GOV-1", null, null, VerificationConclusion.PASS, null, 12, false,
                        LocalDateTime.now())));

        final CertificationProfile profile = service.profile(GOV_SUBJECT_NO);

        assertThat(profile.govCa()).isNotNull();
        assertThat(profile.govCa().uploaded()).isTrue();
        assertThat(profile.govCa().fileName()).isEqualTo("A3.cer");
        assertThat(profile.govCa().lastConclusion()).isEqualTo(VerificationConclusion.PASS.name());
        assertThat(profile.govCa().lastSubmittedAt()).isNotNull();
        assertThat(profile.remainingAttemptsToday()).isNull();
        assertThat(profile.license().uploaded()).isFalse();
    }
}
