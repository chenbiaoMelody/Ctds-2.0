package com.ctds.subject.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.subject.domain.Subject;
import com.ctds.subject.domain.SubjectRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 主体服务公共支撑（WBS-3.1.4 承接 3.1.4 视角评审观察项①：注册与认证两个应用服务的
 * 申请编号格式校验、当前操作人取值、审计落痕三处同型工具方法上收，消除第二使用方重复；
 * WBS-3.1.6 S1 续收 requireSubject——审核与认证两服务的"格式校验+查库或同形拒绝"完全同型重复上收，
 * 行为零变化：错误码/文案/审计语义与两服务原私有方法逐字一致）。
 * 审计对象域固定 subject（ADR-016 审计口径）。
 */
@Component
public class SubjectOpsSupport {

    /** 申请编号格式（与申请编号生成规则 S + yyyyMMdd + 6 位序号对齐）。 */
    private static final String SUBJECT_NO_PATTERN = "S\\d{14}";

    /** 归一化后文件名长度上限（超出截断；与证照/证书文件校验的文件名上限一致）。 */
    private static final int MAX_FILE_NAME_CHARS = 255;

    private final AuditRecorder auditRecorder;
    private final SubjectRepository subjectRepository;

    public SubjectOpsSupport(final AuditRecorder auditRecorder, final SubjectRepository subjectRepository) {
        this.auditRecorder = auditRecorder;
        this.subjectRepository = subjectRepository;
    }

    /** 申请编号格式校验（不合法即 1000C0002 参数错误，不泄露存在性）。 */
    public void requireSubjectNo(final String subjectNo) {
        if (subjectNo == null || subjectNo.isBlank() || !subjectNo.matches(SUBJECT_NO_PATTERN)) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "申请编号格式不正确");
        }
    }

    /**
     * 格式校验 + 查库（WBS-3.1.6 S1 上收，原 CertificationService/ReviewService 私有同型方法）：
     * 申请编号不存在时 1000C0003——与"归属不匹配"出站同形，防存在性探测（ADR-016 §2.6 口径）。
     */
    public Subject requireSubject(final String subjectNo) {
        requireSubjectNo(subjectNo);
        return subjectRepository.findBySubjectNo(subjectNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "申请编号不存在"));
    }

    /** 当前操作人（网关身份缺失时落 anonymous，不阻断——演示链路口径）。 */
    public String operator() {
        final String subject = AuthContext.subject();
        return subject == null ? "anonymous" : subject;
    }

    /**
     * 上传文件名归一化（WBS-3.1.5 hifi B8①，3.1.4 观察项处置）：剥除控制字符（U+0000~U+001F/U+007F）、
     * 去首尾空白、超长截 255；null/空白透传（由既有文件校验拒绝）。归一化后值用于渠道调用、落库与档案回显。
     */
    public String normalizeFileName(final String fileName) {
        if (fileName == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            final char c = fileName.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        final String normalized = sb.toString().trim();
        return normalized.length() > MAX_FILE_NAME_CHARS ? normalized.substring(0, MAX_FILE_NAME_CHARS) : normalized;
    }

    /** 审计落痕（reason 为空时不带明细字段）。 */
    public void audit(final String operator, final String action, final String subjectNo,
            final AuditOutcome outcome, final String reason) {
        final var detail = reason == null ? null : Map.of("reason", reason);
        auditRecorder.record(AuditEvent.of(operator, action, "subject", subjectNo, outcome, detail));
    }
}
