package com.ctds.contract.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.contract.domain.ContractActionLog;
import com.ctds.contract.domain.ContractAttestation;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.policy.UsageLogAuditRow;
import com.ctds.contract.infrastructure.UsageLogRepository;
import com.ctds.contract.interfaces.ContractInternalAuditController.ContractAuditTrailView;
import com.ctds.contract.interfaces.ContractInternalAuditController.ContractAuditTrailView.ActionLogView;
import com.ctds.contract.interfaces.ContractInternalAuditController.ContractAuditTrailView.AttestationView;
import com.ctds.contract.interfaces.ContractInternalAuditController.UsageAuditTrailView;
import com.ctds.contract.interfaces.ContractInternalAuditController.UsageAuditTrailView.UsageLogView;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 合约域 audit-trail 内部只读查询（WBS-3.8.4 hifi §1；Q1-A 跨域留痕锚，两路径）：
 * contractNo → 存证要素（attestation）+ 统一留痕清单；usageLogId → 使用放行留痕行。
 * DID / 主体编号原文原样回读（evidence 聚合层统一摘要化出站）；
 * 锚点不存在 → 统一 400 + 1000C0003"记录不存在"（与 evidence 审计读面同码同文，防枚举）。
 */
@Service
public class ContractAuditTrailService {

    private final ContractRepository contractRepository;
    private final UsageLogRepository usageLogRepository;

    public ContractAuditTrailService(final ContractRepository contractRepository,
            final UsageLogRepository usageLogRepository) {
        this.contractRepository = contractRepository;
        this.usageLogRepository = usageLogRepository;
    }

    /** 合约锚点：存证要素 + 留痕清单（attestation 缺失 = 锚点不存在，不区分防枚举）。 */
    public ContractAuditTrailView contractTrail(final String contractNo) {
        final ContractAttestation attestation = contractRepository
                .findAttestationByContractNo(contractNo)
                .orElseThrow(() -> notFound());
        final List<ActionLogView> logs = contractRepository.listActionLogs(contractNo).stream()
                .map(ContractAuditTrailService::toLogView)
                .toList();
        return new ContractAuditTrailView(
                new AttestationView(attestation.contractNo(), attestation.contentHash(),
                        attestation.providerDid(), toIso(attestation.providerSignedAt()),
                        attestation.requesterDid(), toIso(attestation.requesterSignedAt()),
                        toIso(attestation.attestedAt())),
                logs);
    }

    /** 使用放行锚点：单行留痕回读。 */
    public UsageAuditTrailView usageTrail(final long usageLogId) {
        final UsageLogAuditRow row = usageLogRepository.findAuditRowById(usageLogId)
                .orElseThrow(() -> notFound());
        return new UsageAuditTrailView(new UsageLogView(row.id(), row.contractNo(),
                row.requesterNo(), row.actionType(), row.outcome(), row.reasonCode(),
                row.usedCount(), toIso(row.occurredAt())));
    }

    private static ActionLogView toLogView(final ContractActionLog log) {
        return new ActionLogView(log.id(), log.contractNo(), log.versionNo(), log.action().name(),
                log.actorSubjectNo(), log.reasonCode(), log.fromValue(), log.toValue(),
                toIso(log.createdAt()));
    }

    private static BizException notFound() {
        return new BizException(ErrorCodes.RESOURCE_NOT_FOUND, "记录不存在");
    }

    private static String toIso(final LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
