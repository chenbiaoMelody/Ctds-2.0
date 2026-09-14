package com.ctds.subject.application;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.crypto.Sm4Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditOutcome;
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
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.SubjectType;
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
    static final String ACTION_GOV_SUBMIT = "certification.gov.submit";

    /** 认证通过自动流转与结束认证的留痕备注（流转四要素口径，规格行为 4 第 2 条）。 */
    static final String AUTO_TRANSITION_REMARK = "证照确认与法人核验通过，自动流转";
    static final String ABANDON_REMARK = "申请人结束认证";
    /** 政务 CA 验证通过的自动流转留痕备注（WBS-3.1.4，规格行为 6 第 2 条；不免人工审核——Q2 裁决）。 */
    static final String GOV_TRANSITION_REMARK = "政务 CA 证书验证通过，自动流转";

    /** 系统触发方的操作人标识（认证自动流转，规格行为 4 第 1 条：无需人工触发）。 */
    private static final String SYSTEM_OPERATOR = "system";

    /** 上传文件名长度上限（与 cert_material.file_name VARCHAR(256) 对齐，超长先行拒绝）。 */
    private static final int MAX_FILE_NAME_CHARS = 256;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CertificationRepository certificationRepository;
    private final CertificationStandardApi certificationChannel;
    private final Sm4Service sm4Service;
    private final Sm3Service sm3Service;
    private final OwnershipGuard ownershipGuard;
    private final SubjectStatusService statusService;
    private final SubjectOpsSupport ops;
    private final CertificationProperties properties;
    private final Clock clock;

    public CertificationService(final CertificationRepository certificationRepository,
            final CertificationStandardApi certificationChannel, final Sm4Service sm4Service,
            final Sm3Service sm3Service, final OwnershipGuard ownershipGuard,
            final SubjectStatusService statusService, final SubjectOpsSupport ops,
            final CertificationProperties properties, final Clock clock) {
        this.certificationRepository = certificationRepository;
        this.certificationChannel = certificationChannel;
        this.sm4Service = sm4Service;
        this.sm3Service = sm3Service;
        this.ownershipGuard = ownershipGuard;
        this.statusService = statusService;
        this.ops = ops;
        this.properties = properties;
        this.clock = clock;
    }

    /** 证照上传与 OCR 识别（行为 2 第 1~3 条）：影像密文与原始结果密文即时落库，识别要素回填仅供核对。 */
    public LicenseUploadResult uploadLicense(final String subjectNo, final byte[] image, final String fileName) {
        // 文件名归一化（WBS-3.1.5 hifi B8①，3.1.4 观察项）：控制字符剥除后再走校验/渠道/落库/回显全链
        final String safeName = ops.normalizeFileName(fileName);
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_UPLOAD);
        requirePendingCert(subject);
        requireEnterpriseChannel(subject, ACTION_UPLOAD);
        requireImage(image, safeName);

        final Instant start = Instant.now();
        final OcrRecognition recognition = callChannel(
                () -> certificationChannel.ocrBusinessLicense(image, safeName),
                subject.id(), CertVerificationLog.TYPE_OCR_LICENSE, null);
        final int costMs = elapsedMs(start);
        final LocalDateTime now = LocalDateTime.now(clock);
        final byte[] imageCipher = sm4Service.encrypt(image, properties.getMaterialKeyRef());
        final String imageDigest = sm3Service.digestHex(image);

        if (!recognition.recognizable()) {
            saveMaterial(subject.id(), safeName, imageCipher, imageDigest, recognition, costMs, now);
            ops.audit(ops.operator(), ACTION_UPLOAD, subjectNo, AuditOutcome.DENIED, "ocr_unrecognizable");
            throw new BizException(SubjectErrorCodes.CERT_LICENSE_UNRECOGNIZABLE, "证照影像无法识别，请重传");
        }
        final CertMaterial material =
                saveMaterial(subject.id(), safeName, imageCipher, imageDigest, recognition, costMs, now);
        ops.audit(ops.operator(), ACTION_UPLOAD, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("license uploaded: subjectNo={}, materialId={}", subjectNo, material.id());
        return new LicenseUploadResult(material.id(), safeName, true,
                new OcrElements(recognition.subjectName(), recognition.uscc(),
                        recognition.legalPerson(), recognition.regAddress()));
    }

    /** 核对确认（行为 2 第 3~4 条）：确认信用代码与 OCR 识别值一致才生效，修改过的字段以人工确认为准。 */
    public ConfirmationResult confirmLicense(final String subjectNo, final ConfirmationCommand command) {
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_CONFIRM);
        requirePendingCert(subject);
        requireEnterpriseChannel(subject, ACTION_CONFIRM);
        requireText(command.subjectName(), "主体名称");
        requireText(command.uscc(), "统一社会信用代码");
        requireText(command.legalPerson(), "法定代表人");
        requireText(command.regAddress(), "注册地址");

        final CertMaterial material = requireMaterial(subject.id());
        if (!command.uscc().equals(material.ocrUscc())) {
            ops.audit(ops.operator(), ACTION_CONFIRM, subjectNo, AuditOutcome.DENIED, "uscc_mismatch");
            throw new BizException(SubjectErrorCodes.CERT_USCC_MISMATCH,
                    "统一社会信用代码与证照识别结果不一致，请修正后提交");
        }
        certificationRepository.updateConfirmation(material.id(), command.subjectName(), command.uscc(),
                command.legalPerson(), command.regAddress(), LocalDateTime.now(clock));
        ops.audit(ops.operator(), ACTION_CONFIRM, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("license confirmed: subjectNo={}", subjectNo);
        return new ConfirmationResult(subjectNo, true, "LEGAL_PERSON_VERIFICATION");
    }

    /** 法人实人核验（行为 3）：前置校验 → 渠道核验 → 留痕 → 通过自动流转待审核（行为 4 第 1 条）。 */
    public VerificationResult verifyLegalPerson(final String subjectNo, final VerificationCommand command) {
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_VERIFY);
        if (subject.status() != SubjectStatus.PENDING_CERT && subject.status() != SubjectStatus.CERT_FAILED) {
            throw new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "当前状态不允许执行认证操作");
        }
        requireEnterpriseChannel(subject, ACTION_VERIFY);
        requireText(command.legalPersonName(), "法人姓名");
        requireText(command.legalPersonIdNo(), "法人身份证号");

        final CertMaterial material = requireMaterial(subject.id());
        if (!material.ocrRecognizable() || !material.confirmed()) {
            throw new BizException(SubjectErrorCodes.CERT_LICENSE_NOT_CONFIRMED, "请先完成证照上传与核对确认");
        }
        if (!command.legalPersonName().equals(material.confirmedLegalPerson())) {
            ops.audit(ops.operator(), ACTION_VERIFY, subjectNo, AuditOutcome.DENIED, "legal_person_mismatch");
            throw new BizException(SubjectErrorCodes.CERT_LEGAL_PERSON_MISMATCH,
                    "法人信息与证照识别结果不一致，请先修正后再发起核验");
        }
        requireIdChecksum(command.legalPersonIdNo());
        requireDailyLimitNotReached(subject);

        final String idCipher = sm4Service.encryptText(command.legalPersonIdNo(), properties.getMaterialKeyRef());
        final Instant start = Instant.now();
        final LegalPersonVerification verification = callChannel(
                () -> certificationChannel.verifyLegalPerson(command.legalPersonName(), command.legalPersonIdNo()),
                subject.id(), CertVerificationLog.TYPE_LEGAL_PERSON, command.legalPersonName());
        final int costMs = elapsedMs(start);
        final LocalDateTime now = LocalDateTime.now(clock);

        if (verification.passed()) {
            certificationRepository.appendVerification(new CertVerificationLog(null, subject.id(),
                    CertVerificationLog.TYPE_LEGAL_PERSON, certificationChannel.channelCode(),
                    verification.channelRequestNo(), command.legalPersonName(), idCipher,
                    VerificationConclusion.PASS, null, costMs, false, now));
            statusService.transition(subject.id(), subject.status(), SubjectStatus.PENDING_REVIEW,
                    TriggerRole.SYSTEM, SYSTEM_OPERATOR, AUTO_TRANSITION_REMARK);
            ops.audit(ops.operator(), ACTION_VERIFY, subjectNo, AuditOutcome.SUCCESS, null);
            log.info("legal person verified: subjectNo={} -> PENDING_REVIEW", subjectNo);
            return new VerificationResult(subjectNo, VerificationConclusion.PASS.name(),
                    SubjectStatus.PENDING_REVIEW.name(), null, remainingAttempts(subject.id()));
        }
        certificationRepository.appendVerification(new CertVerificationLog(null, subject.id(),
                CertVerificationLog.TYPE_LEGAL_PERSON, certificationChannel.channelCode(),
                verification.channelRequestNo(), command.legalPersonName(), idCipher,
                VerificationConclusion.FAIL, verification.failReason(), costMs, true, now));
        ops.audit(ops.operator(), ACTION_VERIFY, subjectNo, AuditOutcome.SUCCESS, "verify_failed");
        log.info("legal person verification failed: subjectNo={}", subjectNo);
        return new VerificationResult(subjectNo, VerificationConclusion.FAIL.name(), subject.status().name(),
                verification.failReason(), remainingAttempts(subject.id()));
    }

    /** 认证进度档案（行为 3 第 2 条 / 行为 4 第 2 条 / 行为 6 验收-3）：L4 字段不回显；
     * 政务主体返回 govCa 段且无"当日剩余次数"概念（hifi 接口契约）。 */
    public CertificationProfile profile(final String subjectNo) {
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, "certification.profile");
        final List<CertVerificationLog> logs = certificationRepository.findVerifications(subject.id());
        final List<CertificationProfile.VerificationEntry> entries = logs.stream()
                .map(item -> new CertificationProfile.VerificationEntry(item.conclusion(),
                        item.failReason(), item.createdAt()))
                .collect(Collectors.toList());
        if (subject.subjectType() == SubjectType.GOV) {
            return new CertificationProfile(subjectNo, subject.status(),
                    CertificationProfile.LicenseProfile.empty(),
                    govCaProfile(subject.id(), logs), entries, null);
        }
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
        return new CertificationProfile(subjectNo, subject.status(), license, null, entries,
                remainingAttempts(subject.id()));
    }

    /** 查看证照影像（行为 2 验收-4）：解密返回 + 查看审计留痕"谁在何时查看"；政务主体不适用企业影像端点（hifi B5）。 */
    public ImageView viewImage(final String subjectNo) {
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_IMAGE_VIEW);
        requireEnterpriseChannel(subject, ACTION_IMAGE_VIEW);
        final CertMaterial material = requireMaterial(subject.id());
        final byte[] plain = sm4Service.decrypt(material.contentCipher(), properties.getMaterialKeyRef());
        ops.audit(ops.operator(), ACTION_IMAGE_VIEW, subjectNo, AuditOutcome.SUCCESS, null);
        return new ImageView(material.fileName(), imageBaseUrl(material.fileName())
                + Base64.getEncoder().encodeToString(plain));
    }

    /** 结束认证（lofi Q1-A：待认证 → 认证失败，触发方=申请人；之后可重新发起核验）。 */
    public CertificationActionResult abandonCertification(final String subjectNo) {
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_ABANDON);
        requirePendingCert(subject);
        statusService.transition(subject.id(), SubjectStatus.PENDING_CERT, SubjectStatus.CERT_FAILED,
                TriggerRole.APPLICANT, ops.operator(), ABANDON_REMARK);
        ops.audit(ops.operator(), ACTION_ABANDON, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("certification abandoned: subjectNo={}", subjectNo);
        return new CertificationActionResult(subjectNo, SubjectStatus.CERT_FAILED);
    }

    /**
     * 政务 CA 证书提交与验证（WBS-3.1.4，规格行为 6；lofi Q2-A 单端点一步口径）：
     * 前置校验（状态/通道互斥/文件）→ 渠道验证 → 材料与留痕落库 → 通过自动流转待审核（不免人工审核，Q2 裁决）。
     * 业务不通过是结论非异常（FAIL + 明确原因，主体停留待认证可重新提交换证）；
     * 政务通道不设失败次数上限（lofi Q3-A：规格行为 6 未定义），留痕兜底。
     */
    public GovCaCertificationResult submitGovCaCertificate(final String subjectNo, final byte[] certBytes,
            final String fileName) {
        // 文件名归一化（WBS-3.1.5 hifi B8①，3.1.4 观察项）：控制字符剥除后再走校验/渠道/落库全链
        final String safeName = ops.normalizeFileName(fileName);
        final Subject subject = ops.requireSubject(subjectNo);
        ownershipGuard.requireOwnerOrReviewer(subject, ACTION_GOV_SUBMIT);
        if (subject.status() != SubjectStatus.PENDING_CERT && subject.status() != SubjectStatus.CERT_FAILED) {
            throw new BizException(SubjectErrorCodes.CERT_STATE_NOT_ALLOWED, "当前状态不允许执行认证操作");
        }
        requireGovChannel(subject, ACTION_GOV_SUBMIT);
        requireCertFile(certBytes, safeName);

        final Instant start = Instant.now();
        final GovCaVerification verification = callChannel(
                () -> certificationChannel.verifyGovCaCertificate(certBytes, safeName),
                subject.id(), CertVerificationLog.TYPE_GOV_CA, null);
        final int costMs = elapsedMs(start);
        final LocalDateTime now = LocalDateTime.now(clock);
        saveGovCertMaterial(subject.id(), safeName, certBytes, verification, costMs, now);

        if (!verification.passed()) {
            ops.audit(ops.operator(), ACTION_GOV_SUBMIT, subjectNo, AuditOutcome.SUCCESS, "gov_verify_failed");
            log.info("gov ca verification failed: subjectNo={}", subjectNo);
            return new GovCaCertificationResult(subjectNo, VerificationConclusion.FAIL.name(),
                    subject.status(), verification.failReason());
        }
        statusService.transition(subject.id(), subject.status(), SubjectStatus.PENDING_REVIEW,
                TriggerRole.SYSTEM, SYSTEM_OPERATOR, GOV_TRANSITION_REMARK);
        ops.audit(ops.operator(), ACTION_GOV_SUBMIT, subjectNo, AuditOutcome.SUCCESS, null);
        log.info("gov ca verified: subjectNo={} -> PENDING_REVIEW", subjectNo);
        return new GovCaCertificationResult(subjectNo, VerificationConclusion.PASS.name(),
                SubjectStatus.PENDING_REVIEW, null);
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

    /** 通道互斥（WBS-3.1.4 hifi B5）：政府部门主体不适用企业认证流程（规格行为 6 第 1 条），双向同码 1004B0007。 */
    private void requireEnterpriseChannel(final Subject subject, final String action) {
        if (subject.subjectType() == SubjectType.GOV) {
            ops.audit(ops.operator(), action, subject.subjectNo(), AuditOutcome.DENIED, "channel_type_mismatch");
            throw new BizException(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH,
                    "主体类型与认证流程不匹配，请使用对应主体的认证方式");
        }
    }

    /** 通道互斥反向门槛：政务 CA 通道仅政府部门主体可用（规格行为 6 第 1 条）。 */
    private void requireGovChannel(final Subject subject, final String action) {
        if (subject.subjectType() != SubjectType.GOV) {
            ops.audit(ops.operator(), action, subject.subjectNo(), AuditOutcome.DENIED, "channel_type_mismatch");
            throw new BizException(SubjectErrorCodes.CERT_CHANNEL_TYPE_MISMATCH,
                    "主体类型与认证流程不匹配，请使用对应主体的认证方式");
        }
    }

    /** 政务证书文件校验（lofi Q4-A 定参：≤2MB、cer/crt/pem、文件名 ≤256）。 */
    private void requireCertFile(final byte[] certBytes, final String fileName) {
        if (certBytes == null || certBytes.length == 0) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "政务 CA 证书文件为空");
        }
        if (certBytes.length > properties.getGovCertMaxBytes()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "政务 CA 证书文件大小超出上限（≤"
                    + properties.getGovCertMaxBytes() / (1024 * 1024) + "MB）");
        }
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME_CHARS) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "文件名缺失或超长（最长 "
                    + MAX_FILE_NAME_CHARS + " 字符）");
        }
        final String extension = extensionOf(fileName);
        if (!properties.getGovCertAllowedExtensions().contains(extension)) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "政务 CA 证书仅支持 "
                    + String.join("/", properties.getGovCertAllowedExtensions()) + " 格式");
        }
    }

    /** 政务证书材料落库（复用 cert_material 表 GOV_CA_CERT 类型；文件密文 + SM3 + 验证要素密文，hifi 库表节）。 */
    private void saveGovCertMaterial(final long subjectId, final String fileName, final byte[] certBytes,
            final GovCaVerification verification, final int costMs, final LocalDateTime now) {
        final byte[] certCipher = sm4Service.encrypt(certBytes, properties.getMaterialKeyRef());
        final String certDigest = sm3Service.digestHex(certBytes);
        final byte[] rawCipher = sm4Service.encrypt(govVerifyRawJson(verification),
                properties.getMaterialKeyRef());
        certificationRepository.replaceMaterial(new CertMaterial(null, subjectId,
                CertMaterial.TYPE_GOV_CA_CERT, fileName, certDigest, certCipher, rawCipher,
                null, null, verification.passed(),
                null, null, null, null, null, now));
        certificationRepository.appendVerification(new CertVerificationLog(null, subjectId,
                CertVerificationLog.TYPE_GOV_CA, certificationChannel.channelCode(),
                verification.channelRequestNo(), null, null,
                verification.passed() ? VerificationConclusion.PASS : VerificationConclusion.FAIL,
                verification.failReason(), costMs, false, now));
    }

    /** 政务验证要素密文 JSON（组织信息非 L4 但统一密文落 ocr_raw_cipher，hifi 库表节口径）。 */
    private byte[] govVerifyRawJson(final GovCaVerification verification) {
        try {
            final Map<String, String> raw = new LinkedHashMap<>();
            raw.put("unitName", verification.unitName());
            raw.put("unitCode", verification.unitCode());
            raw.put("message", verification.message());
            return MAPPER.writeValueAsBytes(raw);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("gov ca verification raw serialization failed", e);
        }
    }

    /** 政务 CA 档案段（GOV 主体）：最近一次材料 + 最近一条 GOV_CA 留痕结论（复用 profile 已取的留痕列表）。 */
    private CertificationProfile.GovCaProfile govCaProfile(final long subjectId,
            final List<CertVerificationLog> logs) {
        final CertMaterial material =
                certificationRepository.findLatestMaterial(subjectId, CertMaterial.TYPE_GOV_CA_CERT)
                        .orElse(null);
        if (material == null) {
            return null;
        }
        CertificationProfile.VerificationEntry last = null;
        for (final CertVerificationLog item : logs) {
            if (CertVerificationLog.TYPE_GOV_CA.equals(item.verifyType())) {
                last = new CertificationProfile.VerificationEntry(item.conclusion(), item.failReason(),
                        item.createdAt());
            }
        }
        return new CertificationProfile.GovCaProfile(true, material.fileName(),
                last == null ? null : last.conclusion().name(),
                last == null ? null : last.failReason(), material.createdAt());
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
            ops.audit(ops.operator(), ACTION_VERIFY, subject.subjectNo(), AuditOutcome.DENIED, "daily_limit_reached");
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
            final String errorVerifyType, final String errorPersonName) {
        final Instant start = Instant.now();
        try {
            return call.invoke();
        } catch (final BizException e) {
            if (!StdAdapterErrorCodes.CHANNEL_UNAVAILABLE.equals(e.getErrorCode())) {
                throw e;
            }
            recordChannelError(subjectId, errorVerifyType, errorPersonName, elapsedMs(start));
            throw new CertChannelUnavailableException();
        } catch (final IllegalArgumentException e) {
            throw e;
        } catch (final RuntimeException e) {
            log.error("certification channel unexpected failure: subjectId={}", subjectId, e);
            recordChannelError(subjectId, errorVerifyType, errorPersonName, elapsedMs(start));
            throw new CertChannelUnavailableException();
        }
    }

    private void recordChannelError(final long subjectId, final String verifyType, final String personName,
            final int costMs) {
        certificationRepository.appendVerification(new CertVerificationLog(null, subjectId,
                verifyType,
                certificationChannel.channelCode(), null,
                personName,
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

    private static void requireText(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, label + "不能为空");
        }
    }

    /** 渠道调用的函数出口（内部封闭）。 */
    @FunctionalInterface
    private interface ChannelCall<T> {
        T invoke();
    }
}
