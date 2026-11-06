package com.ctds.contract.application;

import com.ctds.common.evidence.EvidenceEvent;
import com.ctds.common.evidence.EvidenceRecorder;
import com.ctds.contract.domain.Contract;
import com.ctds.contract.domain.ContractAttestation;
import com.ctds.contract.domain.ContractTemplateRepository;
import com.ctds.contract.domain.TemplateType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合约与使用环节存证埋点（WBS-3.8.3 hifi §2 E3~E6）：
 * 双签生效 → {@code CONTRACT/CONTRACT_EFFECTIVE}；公共数据授权类合约再加
 * {@code AUTHORIZATION/DATA_AUTH_CONTRACT_EFFECTIVE}（Q5 双写，同单号同时点）；
 * 使用放行 → {@code USAGE/USAGE_ALLOWED}（单据号 {@code USG{12 位}}，Q3）。
 * 主体 DID 取本域 {@code contract_attestation.requester_did}；缺失即跳过 + 告警（不伪造）。
 */
@Component
public class ContractEvidencePublisher {

    private static final Logger log = LoggerFactory.getLogger(ContractEvidencePublisher.class);
    private static final String STAGE_AUTHORIZATION = "AUTHORIZATION";
    private static final String STAGE_CONTRACT = "CONTRACT";
    private static final String STAGE_USAGE = "USAGE";
    private static final String OP_CONTRACT_EFFECTIVE = "CONTRACT_EFFECTIVE";
    private static final String OP_DATA_AUTH_CONTRACT_EFFECTIVE = "DATA_AUTH_CONTRACT_EFFECTIVE";
    private static final String OP_USAGE_ALLOWED = "USAGE_ALLOWED";

    private final EvidenceRecorder evidenceRecorder;
    private final ContractTemplateRepository templateRepository;
    private final ContractQueryService contractQueryService;
    private final Clock clock;

    public ContractEvidencePublisher(final EvidenceRecorder evidenceRecorder,
            final ContractTemplateRepository templateRepository,
            final ContractQueryService contractQueryService, final Clock clock) {
        this.evidenceRecorder = evidenceRecorder;
        this.templateRepository = templateRepository;
        this.contractQueryService = contractQueryService;
        this.clock = clock;
    }

    /**
     * 合约生效（双签）：先发合约环节；公共数据授权类合约再发授权环节（Q5）。
     *
     * @param contract    生效后合约（取 contractNo / templateNo）
     * @param attestation 存证行（取需方 DID 与生效时点）
     * @param operatorNo  后签一方主体编号
     */
    public void contractEffective(final Contract contract, final ContractAttestation attestation,
            final String operatorNo) {
        if (attestation == null || attestation.requesterDid() == null
                || attestation.requesterDid().isBlank()) {
            log.warn("合约存证跳过（需方 DID 缺失）: contractNo={}", contract.contractNo());
            return;
        }
        final String did = attestation.requesterDid();
        final Instant occurredAt = toInstant(attestation.attestedAt());
        evidenceRecorder.record(new EvidenceEvent(STAGE_CONTRACT, contract.contractNo(),
                OP_CONTRACT_EFFECTIVE, did, occurredAt, operatorNo));
        if (isPublicDataAuthorization(contract.templateNo())) {
            evidenceRecorder.record(new EvidenceEvent(STAGE_AUTHORIZATION, contract.contractNo(),
                    OP_DATA_AUTH_CONTRACT_EFFECTIVE, did, occurredAt, operatorNo));
        }
    }

    /**
     * 使用放行（Q3：单据号 = 放行留痕单号 {@code USG{12 位}}）。
     *
     * @param usageLogId 放行留痕行主键
     */
    public void usageAllowed(final String contractNo, final String requesterNo, final long usageLogId,
            final LocalDateTime occurredAt) {
        final Optional<String> did = contractQueryService.requesterDid(contractNo);
        if (did.isEmpty()) {
            log.warn("使用存证跳过（需方 DID 缺失）: businessNo=USG{}", usageLogId);
            return;
        }
        evidenceRecorder.record(new EvidenceEvent(STAGE_USAGE, businessNo(usageLogId),
                OP_USAGE_ALLOWED, did.get(), toInstant(occurredAt), requesterNo));
    }

    /** 放行留痕单号（12 位零填充，如 USG000000001024）。 */
    static String businessNo(final long usageLogId) {
        return "USG" + String.format("%012d", usageLogId);
    }

    private boolean isPublicDataAuthorization(final String templateNo) {
        return templateRepository.findByNo(templateNo)
                .map(template -> template.type() == TemplateType.PUBLIC_DATA_AUTHORIZATION)
                .orElse(false);
    }

    private Instant toInstant(final LocalDateTime time) {
        return time.atZone(clock.getZone()).toInstant();
    }
}
