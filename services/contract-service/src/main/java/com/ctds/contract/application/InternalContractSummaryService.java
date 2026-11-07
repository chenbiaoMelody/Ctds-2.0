package com.ctds.contract.application;

import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAttestation;
import com.ctds.contract.domain.ContractBizException;
import com.ctds.contract.domain.ContractErrorCodes;
import com.ctds.contract.domain.ContractRepository;
import com.ctds.contract.domain.ContractStatus;
import com.ctds.contract.interfaces.dto.InternalContractSummaryViews;
import org.springframework.stereotype.Service;

/**
 * 内部只读合约摘要（WBS-3.5.3 hifi §4.1；ADR-016 §6 第 8 消费方 = delivery-service）：
 * 参与方可见性 + 生效门槛 + 产品/参与方摘要；<b>不递增用量计数、不写 usage_log</b>。
 * 非参与方/不存在 → 1008C0012 同码同文；未生效/终止/完结 → 1008C0013（沿合约域状态码）。
 *
 * <p>WBS-3.8.3 §3.4：随摘要返回 {@code requesterDid}（取自 contract_attestation，缺失为 null），
 * 供交付环节存证取主体标识——只增字段，既有消费方零影响。</p>
 */
@Service
public class InternalContractSummaryService {

    private final ContractVisibilityGuard visibilityGuard;
    private final ContractRepository repository;

    public InternalContractSummaryService(final ContractVisibilityGuard visibilityGuard,
            final ContractRepository repository) {
        this.visibilityGuard = visibilityGuard;
        this.repository = repository;
    }

    public InternalContractSummaryViews.Summary summary(final String contractNo,
            final String requesterNo) {
        final String no = requireText(contractNo);
        final String requester = requireText(requesterNo);
        final Contract contract = visibilityGuard.requireParticipant(no, requester);
        if (!ContractStatus.EFFECTIVE.name().equals(contract.status().name())) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_STATE_FORBIDDEN,
                    ContractErrorCodes.CONTRACT_STATE_FORBIDDEN_MESSAGE);
        }
        final String requesterDid = repository.findAttestation(contract.id())
                .map(ContractAttestation::requesterDid)
                .orElse(null);
        return new InternalContractSummaryViews.Summary(contract.contractNo(), contract.productId(),
                contract.productName(), contract.requesterSubjectNo(), contract.providerSubjectNo(),
                contract.status().name(), contract.effectiveAt(), requesterDid);
    }

    private static String requireText(final String value) {
        if (value == null || value.isBlank()) {
            throw new ContractBizException(ContractErrorCodes.CONTRACT_PARAM_INVALID,
                    ContractErrorCodes.CONTRACT_PARAM_INVALID_MESSAGE);
        }
        return value.trim();
    }
}
