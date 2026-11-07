package com.ctds.evidence.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.AuditQueryCriteria;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.EvidenceRules;
import com.ctds.evidence.domain.EvidenceStageType;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 审计读面条件解析与硬约束校验（WBS-3.8.4 hifi E1~E4 共用；Q10-A 全沿 3.8.2 既有口径）：
 * 环节 / 单据号沿写面同款封闭校验；主体摘要 = 64 位小写 hex（Q2-A DID 摘要等值口径）；
 * 时间窗 ≤90 天；可要求"至少一个过滤条件"。全部拒绝 → 1012C0002。
 */
public final class EvidenceAuditConditions {

    private static final Pattern SUBJECT_DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    private EvidenceAuditConditions() {
    }

    /**
     * 解析并校验条件。requireCondition = true 时无条件（五项全空）→ 1012C0002。
     *
     * @param stageTypeRaw    环节（可空）
     * @param businessNoRaw   业务单据号（可空）
     * @param subjectDigestRaw 主体 DID 摘要（可空，64 位小写 hex）
     * @param fromRaw          起始时间（可空，ISO-8601）
     * @param toRaw            结束时间（可空，ISO-8601）
     */
    public static AuditQueryCriteria parse(final String stageTypeRaw, final String businessNoRaw,
            final String subjectDigestRaw, final String fromRaw, final String toRaw,
            final boolean requireCondition) {
        final EvidenceStageType stageType = (stageTypeRaw == null || stageTypeRaw.isBlank())
                ? null : EvidenceStageType.parse(stageTypeRaw);
        final String businessNo = (businessNoRaw == null || businessNoRaw.isBlank())
                ? null : EvidenceRules.businessNo(businessNoRaw);
        final String subjectDigest = (subjectDigestRaw == null || subjectDigestRaw.isBlank())
                ? null : requireDigest(subjectDigestRaw);
        final Instant from = (fromRaw == null || fromRaw.isBlank())
                ? null : EvidenceRules.instant(fromRaw, "起始时间");
        final Instant to = (toRaw == null || toRaw.isBlank())
                ? null : EvidenceRules.instant(toRaw, "结束时间");
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "起始时间不得晚于结束时间");
            }
            if (Duration.between(from, to).compareTo(EvidenceAuditLimits.MAX_WINDOW) > 0) {
                throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "查询时间窗不得超过 90 天");
            }
        }
        final AuditQueryCriteria criteria =
                new AuditQueryCriteria(stageType, businessNo, subjectDigest, from, to);
        if (requireCondition && criteria.isEmpty()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "至少需要一个过滤条件");
        }
        return criteria;
    }

    private static String requireDigest(final String raw) {
        if (!SUBJECT_DIGEST.matcher(raw).matches()) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "主体摘要不合法");
        }
        return raw;
    }

    /** 分页页码越界（沿 3.8.2 同文案口径）。 */
    public static BizException pageNumOutOfRange() {
        return new BizException(EvidenceErrorCodes.PARAM_INVALID, "pageNum 越界");
    }

    /** 分页每页条数越界（沿 3.8.2 同文案口径）。 */
    public static BizException pageSizeOutOfRange() {
        return new BizException(EvidenceErrorCodes.PARAM_INVALID, "pageSize 越界");
    }
}
