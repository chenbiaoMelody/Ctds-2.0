package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证进度档案（hifi 接口契约；身份证号全程不回显，L4 展示管控——规格行为 2 第 5 条）。
 *
 * @param subjectNo              申请编号
 * @param status                主体当前状态
 * @param license                证照材料状态
 * @param verifications          渠道调用记录（结论/原因/时间）
 * @param remainingAttemptsToday 当日剩余核验次数
 */
public record CertificationProfile(String subjectNo, SubjectStatus status, LicenseProfile license,
        List<VerificationEntry> verifications, int remainingAttemptsToday) {

    /** 证照材料状态。 */
    public record LicenseProfile(boolean uploaded, boolean recognizable, boolean confirmed,
            LocalDateTime confirmedAt, OcrElements confirmedResult) {

        /** 未上传态。 */
        public static LicenseProfile empty() {
            return new LicenseProfile(false, false, false, null, null);
        }
    }

    /** 渠道调用记录条目（不含身份证号与密文，档案展示口径）。 */
    public record VerificationEntry(com.ctds.subject.domain.VerificationConclusion conclusion,
            String failReason, LocalDateTime createdAt) {
    }
}
