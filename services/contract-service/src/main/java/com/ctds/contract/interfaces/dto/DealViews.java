package com.ctds.contract.interfaces.dto;

import com.ctds.contract.application.ContractQueryService;
import com.ctds.contract.domain.ClauseValues;
import com.ctds.contract.domain.Contract;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合约读面出站视图（WBS-3.4.3 hifi §2.2/§2.3；记录类，出站字段集显式锚定——hifi §2.2 R7
 * "响应字段集显式锚定（无数据本体）"，签名值不出站，留痕原文不出站）。
 */
public final class DealViews {

    /** W5 发起成功响应（发起即协商中——Q2-A）。 */
    public record Initiated(String contractNo, String status, int clauseVersionNo,
            String templateNo, int templateVersionNo, long productId) {
    }

    /** W6 提案结果（新版本号 + 上一版版本号）。 */
    public record Proposed(String contractNo, int clauseVersionNo, int previousVersionNo) {
    }

    /** W7 确认结果（逐方确认状态 + 状态机现态）。 */
    public record Confirmed(String contractNo, int clauseVersionNo, Confirmations confirmations,
            String status) {

        public record Confirmations(boolean provider, boolean requester) {
        }
    }

    /** W8/W10/W12 终止结果（终态 + 终止类型）。 */
    public record Terminated(String contractNo, String status, String terminationType) {
    }

    /** W9 签署结果（本方角色 + 状态机现态 + 生效时间〔后签才非空〕）。 */
    public record Signed(String contractNo, String partyRole, String status,
            LocalDateTime effectiveAt) {
    }

    /** W11 合意解除结果（逐方确认状态 + 状态机现态）。 */
    public record Released(String contractNo, String status, ReleaseConsents releaseConsents) {

        public record ReleaseConsents(boolean provider, boolean requester) {
        }
    }

    /** R6 我的合约行。 */
    public record Mine(String contractNo, String productName, String counterpartySubjectNo,
            String myRole, String status, int currentClauseVersion, LocalDateTime effectiveAt,
            LocalDateTime updatedAt) {
    }

    /** R7/R11 详情（字段集锚定：产品/定价快照 + 双方 + 模板引用 + 状态与终止信息 + 当前条款值
     * 全文〔含策略全文〕 + 逐方签署状态〔无签名值〕 + 内容哈希与生效时间 + 存证事件摘要）。 */
    public record Detail(String contractNo, long productId, String productName,
            String pricingModel, java.math.BigDecimal priceAmount, String providerSubjectNo,
            String requesterSubjectNo, String templateNo, int templateVersionNo, String status,
            String terminationType, String terminationReason, String terminatedBy,
            LocalDateTime effectiveAt, LocalDateTime endedAt, int currentClauseVersion,
            JsonNode clauseValues, String contentHash, List<SignatureStatus> signatures,
            Attestation attestation, LocalDateTime createdAt, LocalDateTime updatedAt) {

        /** 逐方签署状态（DID + 签署时间——签名值不出站）。 */
        public record SignatureStatus(String partyRole, String subjectNo, String did,
                LocalDateTime signedAt) {
        }

        /** 存证事件摘要（未生效 = null）。 */
        public record Attestation(String contentHash, String providerDid,
                LocalDateTime providerSignedAt, String requesterDid, LocalDateTime requesterSignedAt,
                LocalDateTime attestedAt) {
        }
    }

    /** R8 条款版本历史行（全文 + 变更明细"从何值→到何值"；V1 变更明细 = null）。 */
    public record ClauseVersion(int versionNo, JsonNode clauseValues,
            List<ChangeItem> changes, String proposedBy, LocalDateTime proposedAt,
            LocalDateTime confirmedProviderAt, LocalDateTime confirmedRequesterAt) {

        /** 变更明细行（"从何值→到何值"）。 */
        public record ChangeItem(String slot, String from, String to) {
        }
    }

    /** R9 签署核验报告（逐方结论；签名值不出站）。 */
    public record Verification(String contractNo, List<Row> results) {

        /** 逐方结论行（result = PASS/FAIL/UNAVAILABLE；reason = did 服务侧原因码原文）。 */
        public record Row(String partyRole, String did, String result, String reason,
                LocalDateTime verifiedAt) {
        }
    }

    // ==== 映射助手（应用层视图 → 出站 DTO，字段集锚定单点）====

    /** 详情装配（解密后的条款值全文以存储形态出站；签名/存证逐项映射）。 */
    public static Detail detailOf(final ContractQueryService.ContractDetail detail) {
        final Contract contract = detail.contract();
        return new Detail(contract.contractNo(), contract.productId(), contract.productName(),
                contract.pricingModel(), contract.priceAmount(), contract.providerSubjectNo(),
                contract.requesterSubjectNo(), contract.templateNo(), contract.templateVersionNo(),
                contract.status().name(), contract.terminationType() == null
                        ? null : contract.terminationType().name(),
                contract.terminationReason(), contract.terminatedBy(), contract.effectiveAt(),
                contract.endedAt(), contract.currentClauseVersion(),
                detail.clauseValues() == null ? null : detail.clauseValues().toStorageJson(),
                detail.contentHash(),
                detail.signatures().stream().map(s -> new Detail.SignatureStatus(s.partyRole(),
                        s.subjectNo(), s.did(), s.signedAt())).toList(),
                detail.attestation() == null ? null : new Detail.Attestation(
                        detail.attestation().contentHash(), detail.attestation().providerDid(),
                        detail.attestation().providerSignedAt(), detail.attestation().requesterDid(),
                        detail.attestation().requesterSignedAt(), detail.attestation().attestedAt()),
                contract.createdAt(), contract.updatedAt());
    }

    /** 版本历史装配。 */
    public static ClauseVersion versionOf(final ContractQueryService.ClauseVersionItem item) {
        return new ClauseVersion(item.versionNo(),
                item.clauseValues().toStorageJson(),
                item.changes() == null ? null : item.changes().stream()
                        .map(change -> new ClauseVersion.ChangeItem(change.slot(), change.from(),
                                change.to())).toList(),
                item.proposedBy(), item.proposedAt(), item.confirmedProviderAt(),
                item.confirmedRequesterAt());
    }

    /** 变更明细出站复用（留痕表不落原文，值级明细仅在版本行——响应随 R8 出站）。 */
    public static JsonNode clauseValuesJson(final ClauseValues values) {
        return values.toStorageJson();
    }

    private DealViews() {
    }
}
