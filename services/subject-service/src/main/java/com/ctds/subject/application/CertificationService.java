package com.ctds.subject.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
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
import com.ctds.subject.domain.TriggerRole;
import com.ctds.subject.domain.VerificationConclusion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实名认证应用服务（规格 C-1.1 行为 2/3/4/7，WBS-3.1.3 hifi B4~B12）：
 * 证照上传与 OCR 识别（影像与原始结果 L4 加密落库）、核对确认与差异阻断、
 * 法人核验（前置校验/重试上限/跨日恢复/渠道异常 fail-fast）、认证双通过自动流转、
 * 结束认证（lofi Q1-A 裁决口径）、认证档案查询与影像查看审计。
 * 渠道调用全程留痕（渠道标识/流水号/结论/耗时，行为 7 第 3 条）；渠道异常与业务不通过严格区分（第 4 条）。
 * 渠道标识与流水号一律取自渠道接口出口（业务代码只见接口不见具体渠道——规格行为 7 第 1 条）。
 */
@Service
public class CertificationService {

    private static final Logger log = LoggerFactory.getLogger(CertificationService.class);

    /** 审计/日志动作标识（服务端常量）。 */
    static final String ACTION_UPLOAD = "certification.upload";
    static final String ACTION_CONFIRM = "certification.confirm";
    static final String ACTION_VERIFY = "certification.verify";
    static final String ACTION_IMAGE_VIEW = "certification.image.view";
    static final String ACTION_ABANDON = "certification.abandon";

    /** 认证通过自动流转与结束认证的留痕备注（流转四要素口径，规格行为 4 第 2 条）。 */
    static final String AUTO_TRANSITION_REMARK = "证照确认与法人核验通过，自动流转";
    static final String ABANDON_REMARK = "申请人结束认证";

    /** 系统触发方的操作人标识（认证自动流转，规格行为 4 第 1 条：无需人工触发）。 */
    private static final String SYSTEM_OPERATOR = "system";

    /** 上传文件名长度上限（与 cert_material.file_name VARCHAR(256) 对齐，超长先行拒绝）。 */
    private static final int MAX_FILE_NAME_CHARS = 256;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubjectRepository subjectRepository;
    private final CertificationRepository certificationRepository;
    private final CertificationStandardApi certificationChannel;
    private final Sm4Service sm4Service;
    private final Sm3Service sm3Service;
    private final OwnershipGuard ownershipGuard;
    private final SubjectStatusService statusService;
    private final AuditRecorder auditRecorder;
    private final CertificationProperties properties;
    private final Clock clock;

    public CertificationService(final SubjectRepository subjectRepository,
            final CertificationRepository certificationRepository,
            final CertificationStandardApi certificationChannel, final Sm4Service sm4Service,
            final Sm3Service sm3Service, final OwnershipGuard ownershipGuard,
            final SubjectStatusService statusService, final AuditRecorder auditRecorder,
            final CertificationProperties properties, final Clock clock) {
        this.subjectRepository = subjectRepository;
        this.certificationRepository = certificationRepository;
        this.certificationChannel = certificationChannel;
        this.sm4Service = sm4Service;
        this.sm3Service = sm3Service;
        this.ownershipGuard = ownershipGuard;
        this.statusService = statusService;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    /** 证照上传与 OCR 识别（行为 2 第 1~3 条）：影像密文与原始结果密文即时落库，识别要素回填仅供核对。 */
    public LicenseUploadResult uploadLicense(final String subjectNo, final byte[] image, final String fileName) {
        requireSubjectNo(subjectNo);
        final Subject subject = requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_UPLOAD);
        requirePendingCert(subject);
        requireImage(image, fileName);

        final Instant start = Instant.now();
        final OcrRecognition recognition = callChannel(
                () -> certificationChannel.ocrBusinessLicense(image, fileName),
                subject.id(), null);
        final int costMs = elapsedMs(start);
        final LocalDateTime now = LocalDateTime.now(clock);
        final byte[] imageCipher = sm4Service.encrypt(image, properties.getMaterialKeyRef());
        final String imageDigest = sm3Service.digestHex(image);

        if (!recognition.recognizable()) {
            saveMaterial(subject.id(), fileName, imageCipher, imageDigest, recognition, costMs, now);
            audit(operator(), ACTION_UPLOAD, subjectNo, AuditOutcome.DENIED, "ocr_unrecognizable");
            throw new BizException(SubjectErrorCodes.CERT_LICENSE_UNRECOGNIZABLE, "证照影像无法识别，请重传");
        }
        final CertMaterial material =
                saveMaterial(subject.id(), fileName, imageCipher, imageDigest, recognition, costMs, now);
        audit(operator(), ACTION_UPLOAD, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("license uploaded: subjectNo={}, materialId={}", subjectNo, material.id());
        return new LicenseUploadResult(material.id(), fileName, true,
                new OcrElements(recognition.subjectName(), recognition.uscc(),
                        recognition.legalPerson(), recognition.regAddress()));
    }

    /** 核对确认（行为 2 第 3~4 条）：确认信用代码与 OCR 识别值一致才生效，修改过的字段以人工确认为准。 */
    public ConfirmationResult confirmLicense(final String subjectNo, final ConfirmationCommand command) {
        requireSubjectNo(subjectNo);
        final Subject subject = requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_CONFIRM);
        requirePendingCert(subject);
        requireText(command.subjectName(), "主体名称");
        requireText(command.uscc(), "统一社会信用代码");
        requireText(command.legalPerson(), "法定代表人");
        requireText(command.regAddress(), "注册地址");

        final CertMaterial material = requireMaterial(subject.id());
        if (!command.uscc().equals(material.ocrUscc())) {
            audit(operator(), ACTION_CONFIRM, subjectNo, AuditOutcome.DENIED, "uscc_mismatch");
            throw new BizException(SubjectErrorCodes.CERT_USCC_MISMATCH,
                    "统一社会信用代码与证照识别结果不一致，请修正后提交");
        }
        certificationRepository.updateConfirmation(material.id(), command.subjectName(), command.uscc(),
                command.legalPerson(), command.regAddress(), LocalDateTime.now(clock));
        audit(operator(), ACTION_CONFIRM, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("license confirmed: subjectNo={}", subjectNo);
        return new ConfirmationResult(subjectNo, true, "LEGAL_PERSON_VERIFICATION");
    }

    /** 法人实人核验（行为 3）：前置校验 → 渠道核验 → 留痕 → 通过自动流转待审核（行为 4 第 1 条）。 */
    public VerificationResult verifyLegalPerson(final String subjectNo, final VerificationCommand command) {
        requireSubjectNo(subjectNo);
        final Subject subject = requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_VERIFY);
        if (subject.status() != SubjectStatus.PENDING_CERT && subject.status() != SubjectStatus.CERT_FAILED) {
            throw new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "当前状态不允许执行认证操作");
        }
        requireText(command.legalPersonName(), "法人姓名");
        requireText(command.legalPersonIdNo(), "法人身份证号");

        final CertMaterial material = requireMaterial(subject.id());
        if (!material.ocrRecognizable() || !material.confirmed()) {
            throw new BizException(SubjectErrorCodes.CERT_LICENSE_NOT_CONFIRMED, "请先完成证照上传与核对确认");
        }
        if (!command.legalPersonName().equals(material.confirmedLegalPerson())) {
            audit(operator(), ACTION_VERIFY, subjectNo, AuditOutcome.DENIED, "legal_person_mismatch");
            throw new BizException(SubjectErrorCodes.CERT_LEGAL_PERSON_MISMATCH,
                    "法人信息与证照识别结果不一致，请先修正后再发起核验");
        }
        requireIdChecksum(command.legalPersonIdNo());
        requireDailyLimitNotReached(subject);

        final String idCipher = sm4Service.encryptText(command.legalPersonIdNo(), properties.getMaterialKeyRef());
        final Instant start = Instant.now();
        final LegalPersonVerification verification = callChannel(
                () -> certificationChannel.verifyLegalPerson(command.legalPersonName(), command.legalPersonIdNo()),
                subject.id(), command);
        final int costMs = elapsedMs(start);
        final LocalDateTime now = LocalDateTime.now(clock);

        if (verification.passed()) {
            certificationRepository.appendVerification(new CertVerificationLog(null, subject.id(),
                    CertVerificationLog.TYPE_LEGAL_PERSON, certificationChannel.channelCode(),
                    verification.channelRequestNo(), command.legalPersonName(), idCipher,
                    VerificationConclusion.PASS, null, costMs, false, now));
            statusService.transition(subject.id(), subject.status(), SubjectStatus.PENDING_REVIEW,
                    TriggerRole.SYSTEM, SYSTEM_OPERATOR, AUTO_TRANSITION_REMARK);
            audit(operator(), ACTION_VERIFY, subjectNo, AuditOutcome.SUCCESS, null);
            log.info("legal person verified: subjectNo={} -> PENDING_REVIEW", subjectNo);
            return new VerificationResult(subjectNo, VerificationConclusion.PASS.name(),
                    SubjectStatus.PENDING_REVIEW.name(), null, remainingAttempts(subject.id()));
        }
        certificationRepository.appendVerification(new CertVerificationLog(null, subject.id(),
                CertVerificationLog.TYPE_LEGAL_PERSON, certificationChannel.channelCode(),
                verification.channelRequestNo(), command.legalPersonName(), idCipher,
                VerificationConclusion.FAIL, verification.failReason(), costMs, true, now));
        audit(operator(), ACTION_VERIFY, subjectNo, AuditOutcome.SUCCESS, "verify_failed");
        log.info("legal person verification failed: subjectNo={}", subjectNo);
        return new VerificationResult(subjectNo, VerificationConclusion.FAIL.name(), subject.status().name(),
                verification.failReason(), remainingAttempts(subject.id()));
    }

    /** 认证进度档案（行为 3 第 2 条 / 行为 4 第 2 条）：身份证号等 L4 字段不回显。 */
    public CertificationProfile profile(final String subjectNo) {
        requireSubjectNo(subjectNo);
        final Subject subject = requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, "certification.profile");
        final CertMaterial material =
                certificationRepository.findLatestMaterial(subject.id(), CertMaterial.TYPE_BUSINESS_LICENSE)
                        .orElse(null);
        final CertificationProfile.LicenseProfile license = material == null
                ? CertificationProfile.LicenseProfile.empty()
                : new CertificationProfile.LicenseProfile(true, material.ocrRecognizable(), material.confirmed(),
                        material.confirmedAt(),
                        material.confirmed()
                                ? new OcrElements(material.confirmedName(), material.confirmedUscc(),
                                        material.confirmedLegalPerson(), material.confirmedRegAddress())
                                : null);
        final List<CertificationProfile.VerificationEntry> entries =
                certificationRepository.findVerifications(subject.id()).stream()
                        .map(item -> new CertificationProfile.VerificationEntry(item.conclusion(),
                                item.failReason(), item.createdAt()))
                        .collect(Collectors.toList());
        return new CertificationProfile(subjectNo, subject.status(), license, entries,
                remainingAttempts(subject.id()));
    }

    /** 查看证照影像（行为 2 验收-4）：解密返回 + 查看审计留痕"谁在何时查看"。 */
    public ImageView viewImage(final String subjectNo) {
        requireSubjectNo(subjectNo);
        final Subject subject = requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_IMAGE_VIEW);
        final CertMaterial material = requireMaterial(subject.id());
        final byte[] plain = sm4Service.decrypt(material.contentCipher(), properties.getMaterialKeyRef());
        audit(operator(), ACTION_IMAGE_VIEW, subjectNo, AuditOutcome.SUCCESS, null);
        return new ImageView(material.fileName(), imageBaseUrl(material.fileName())
                + Base64.getEncoder().encodeToString(plain));
    }

    /** 结束认证（lofi Q1-A：待认证 → 认证失败，触发方=申请人；之后可重新发起核验）。 */
    public CertificationActionResult abandonCertification(final String subjectNo) {
        requireSubjectNo(subjectNo);
        final Subject subject = requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_ABANDON);
        requirePendingCert(subject);
        statusService.transition(subject.id(), SubjectStatus.PENDING_CERT, SubjectStatus.CERT_FAILED,
                TriggerRole.APPLICANT, operator(), ABANDON_REMARK);
        audit(operator(), ACTION_ABANDON, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("certification abandoned: subjectNo={}", subjectNo);
        return new CertificationActionResult(subjectNo, SubjectStatus.CERT_FAILED);
    }

    private Subject requireSubject(final String subjectNo) {
        return subjectRepository.findBySubjectNo(subjectNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
    }

    private CertMaterial requireMaterial(final long subjectId) {
        return certificationRepository.findLatestMaterial(subjectId, CertMaterial.TYPE_BUSINESS_LICENSE)
                .orElseThrow(() -> new BizException(SubjectErrorCodes.CERT_LICENSE_NOT_CONFIRMED,
                        "请先完成证照上传与核对确认"));
    }

    private void requirePendingCert(final Subject subject) {
        if (subject.status() != SubjectStatus.PENDING_CERT) {
            throw new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "当前状态不允许执行认证操作");
        }
    }

    private void requireImage(final byte[] image, final String fileName) {
        if (image == null || image.length == 0) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "证照影像文件为空");
        }
        if (image.length > properties.getUploadMaxBytes()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "证照影像大小超出上限（≤"
                    + properties.getUploadMaxBytes() / (1024 * 1024) + "MB）");
        }
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_CHARS) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "文件名缺失或超长（最长 "
                    + MAX_FILE_NAME_CHARS + " 字符）");
        }
        final String extension = extensionOf(fileName);
        if (!properties.getUploadAllowedExtensions().contains(extension)) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "证照影像仅支持 "
                    + String.join("/", properties.getUploadAllowedExtensions()) + " 格式");
        }
    }

    private void requireDailyLimitNotReached(final Subject subject) {
        final LocalDateTime dayStart = LocalDate.now(clock).atStartOfDay();
        if (certificationRepository.countFailuresSince(subject.id(), dayStart)
                >= properties.getVerifyDailyLimit()) {
            audit(operator(), ACTION_VERIFY, subject.subjectNo(), AuditOutcome.DENIED, "daily_limit_reached");
            throw new BizException(SubjectErrorCodes.CERT_VERIFY_LIMIT_REACHED, "今日核验次数已用完，请次日再试");
        }
    }

    /**
     * 18 位身份证号校验位验证（GB 11643-1999 mod 11-2；hifi B6"证件号校验位合法"的实现落点；
     * 15 位旧格式只做格式校验不做校验位）。
     */
    private static void requireIdChecksum(final String idNo) {
        if (idNo.length() != 18) {
            return;
        }
        final int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        final char[] checkChars = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            final int digit = Character.digit(idNo.charAt(i), 10);
            if (digit < 0) {
                throw new BizException(ErrorCodes.PARAM_INVALID, "身份证号格式不正确");
            }
            sum += digit * weights[i];
        }
        if (Character.toUpperCase(idNo.charAt(17)) != checkChars[sum % 11]) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "身份证号校验位不正确");
        }
    }

    /**
     * 渠道调用统一出口：技术异常转译 fail-fast（1004S0001，不计失败次数）并落 CHANNEL_ERROR 留痕。
     * 编程错误（IllegalArgumentException）原样重抛不转译；其余未预期异常记录根因日志后转译
     * （评审修复：禁止吞异常——原始堆栈进服务端日志，出站仍为业务文案）。
     */
    private <T> T callChannel(final ChannelCall<T> call, final long subjectId,
            final VerificationCommand verifyCommand) {
        final Instant start = Instant.now();
        try {
            return call.invoke();
        } catch (final BizException e) {
            if (!StdAdapterErrorCodes.CHANNEL_UNAVAILABLE.equals(e.getErrorCode())) {
                throw e;
            }
            recordChannelError(subjectId, verifyCommand, elapsedMs(start));
            throw new CertChannelUnavailableException();
        } catch (final IllegalArgumentException e) {
            throw e;
        } catch (final RuntimeException e) {
            log.error("certification channel unexpected failure: subjectId={}", subjectId, e);
            recordChannelError(subjectId, verifyCommand, elapsedMs(start));
            throw new CertChannelUnavailableException();
        }
    }

    private void recordChannelError(final long subjectId, final VerificationCommand verifyCommand,
            final int costMs) {
        certificationRepository.appendVerification(new CertVerificationLog(null, subjectId,
                verifyCommand == null ? CertVerificationLog.TYPE_OCR_LICENSE : CertVerificationLog.TYPE_LEGAL_PERSON,
                certificationChannel.channelCode(), null,
                verifyCommand == null ? null : verifyCommand.legalPersonName(),
                null, VerificationConclusion.CHANNEL_ERROR, null, costMs, false, LocalDateTime.now(clock)));
    }

    private CertMaterial saveMaterial(final long subjectId, final String fileName, final byte[] imageCipher,
            final String imageDigest, final OcrRecognition recognition, final int costMs,
            final LocalDateTime now) {
        final byte[] rawCipher = recognition.recognizable()
                ? sm4Service.encrypt(ocrRawJson(recognition), properties.getMaterialKeyRef()) : null;
        final CertMaterial material = certificationRepository.replaceMaterial(new CertMaterial(null, subjectId,
                CertMaterial.TYPE_BUSINESS_LICENSE, fileName, imageDigest, imageCipher, rawCipher,
                recognition.recognizable() ? recognition.uscc() : null,
                recognition.recognizable() ? recognition.legalPerson() : null,
                recognition.recognizable(), null, null, null, null, null, now));
        certificationRepository.appendVerification(new CertVerificationLog(null, subjectId,
                CertVerificationLog.TYPE_OCR_LICENSE, certificationChannel.channelCode(),
                recognition.channelRequestNo(), null, null,
                recognition.recognizable() ? VerificationConclusion.PASS : VerificationConclusion.UNRECOGNIZABLE,
                recognition.recognizable() ? null : recognition.message(), costMs, false, now));
        return material;
    }

    private byte[] ocrRawJson(final OcrRecognition recognition) {
        try {
            final Map<String, String> raw = new LinkedHashMap<>();
            raw.put("subjectName", recognition.subjectName());
            raw.put("uscc", recognition.uscc());
            raw.put("legalPerson", recognition.legalPerson());
            raw.put("regAddress", recognition.regAddress());
            raw.put("message", recognition.message());
            return MAPPER.writeValueAsBytes(raw);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("OCR raw result serialization failed", e);
        }
    }

    private static String imageBaseUrl(final String fileName) {
        final String extension = extensionOf(fileName);
        return "data:image/" + ("jpg".equals(extension) ? "jpeg" : extension) + ";base64,";
    }

    private static String extensionOf(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private int remainingAttempts(final long subjectId) {
        final int remaining = properties.getVerifyDailyLimit()
                - certificationRepository.countFailuresSince(subjectId, LocalDate.now(clock).atStartOfDay());
        return Math.max(0, remaining);
    }

    private static int elapsedMs(final Instant start) {
        return (int) Duration.between(start, Instant.now()).toMillis();
    }

    private void requireSubjectNo(final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank() || !subjectNo.matches("S\\d{14}")) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "申请编号格式不正确");
        }
    }

    private static void requireText(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, label + "不能为空");
        }
    }

    private static String operator() {
        final String subject = AuthContext.subject();
        return subject == null ? "anonymous" : subject;
    }

    private void audit(final String operator, final String action, final String subjectNo,
            final AuditOutcome outcome, final String reason) {
        final var detail = reason == null ? null : Map.of("reason", reason);
        auditRecorder.record(AuditEvent.of(operator, action, "subject", subjectNo, outcome, detail));
    }

    /** 渠道调用的函数出口（内部封闭）。 */
    @FunctionalInterface
    private interface ChannelCall<T> {
        T invoke();
    }
}
