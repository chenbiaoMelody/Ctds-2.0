package com.ctds.contract.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合约聚合（逐列对应 contract 表，WBS-3.4.3 hifi §4 表 1）：产品/定价/模板版本为发起时快照；
 * 状态机六态 + 终止分支类型；双签生效时间 = 最后一签；合意解除双方确认时间逐方承载。
 * 状态迁移门槛单点在 {@link ContractTransitions}（应用服务与仓储条件更新同源口径）。
 */
public record Contract(Long id, String contractNo, long productId, String productName,
        String providerSubjectNo, String requesterSubjectNo, String templateNo,
        int templateVersionNo, String pricingModel, BigDecimal priceAmount,
        int currentClauseVersion, ContractStatus status, TerminationType terminationType,
        String terminationReason, String terminatedBy, LocalDateTime effectiveAt,
        LocalDateTime endedAt, LocalDateTime releaseConsentProviderAt,
        LocalDateTime releaseConsentRequesterAt, String createdBy, LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 参与方角色判定（应用服务可见性单点；null = 非参与方）。 */
    public PartyRole roleOf(final String subjectNo) {
        if (subjectNo != null && subjectNo.equals(providerSubjectNo)) {
            return PartyRole.PROVIDER;
        }
        if (subjectNo != null && subjectNo.equals(requesterSubjectNo)) {
            return PartyRole.REQUESTER;
        }
        return null;
    }

    /** 合意解除·本角色确认列时间（W11 条件更新的复查口径）。 */
    public LocalDateTime releaseConsentAt(final PartyRole role) {
        return role == PartyRole.PROVIDER ? releaseConsentProviderAt : releaseConsentRequesterAt;
    }
}
