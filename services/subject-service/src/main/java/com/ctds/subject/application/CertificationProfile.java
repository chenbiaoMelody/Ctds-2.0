package com.ctds.subject.application;

import com.ctds.subject.domain.SubjectStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证进度档案（hifi 接口契约；身份证号全程不回显，L4 展示管控——规格行为 2 第 5 条）。
 *
 * @param subjectNo              申请编号
 * @param status                主体当前状态
 * @param license                证照材料状态（政务主体为空态）
 * @param govCa                  政务 CA 材料状态（WBS-3.1.4；企业/机构主体为 null）
 * @param verifications          渠道调用记录（结论/原因/时间）
 * @param remainingAttemptsToday 当日剩余核验次数（政务主体无失败次数概念，返回 null）
 */
public record CertificationProfile(String subjectNo, SubjectStatus status, LicenseProfile license,
        GovCaProfile govCa, List<VerificationEntry> verifications, Integer remainingAttemptsToday) {

    /** 证照材料状态。 */
    public record LicenseProfile(boolean uploaded, boolean recognizable, boolean confirmed,
            LocalDateTime confirmedAt, OcrElements confirmedResult) {

        /** 未上传态。 */
        public static LicenseProfile empty() {
            return new LicenseProfile(false, false, false, null, null);
        }
    }

    /** 政务 CA 材料状态（WBS-3.1.4 hifi 接口契约：最近一次提交与最近结论）。 */
    public record GovCaProfile(boolean uploaded, String fileName, String lastConclusion,
            String lastFailReason, LocalDateTime lastSubmittedAt) {
    }

    /** 渠道调用记录条目（不含身份证号与密文，档案展示口径）。 */
    public record VerificationEntry(com.ctds.subject.domain.VerificationConclusion conclusion,
            String failReason, LocalDateTime createdAt) {
    }
}
