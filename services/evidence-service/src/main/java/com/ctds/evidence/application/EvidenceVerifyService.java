package com.ctds.evidence.application;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.ChainEntry;
import com.ctds.evidence.domain.EvidenceAction;
import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.EvidenceGateway;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import com.ctds.evidence.domain.EvidenceRules;
import com.ctds.evidence.domain.EvidenceStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 链上核验应用服务（hifi §1.2 / §9 T7 / T8；规格 B3-1~B3-4）：
 * 重算摘要与账本摘要比对，结论三态显式（不默认通过）；核验只读 + 只追加 VERIFIED 留痕。
 */
@Service
public class EvidenceVerifyService {

    /** 核验通过。 */
    public static final String PASS = "PASS";
    /** 摘要不一致（账本被改 / 账本缺块）。 */
    public static final String MISMATCH = "MISMATCH";
    /** 未上链（待上链 / 上链失败）。 */
    public static final String NOT_ON_CHAIN = "NOT_ON_CHAIN";

    private static final String NONE = "NONE";

    private final EvidenceRepository repository;
    private final EvidenceGateway gateway;
    private final Sm3Service sm3;
    private final Clock clock;

    public EvidenceVerifyService(final EvidenceRepository repository, final EvidenceGateway gateway,
            final Sm3Service sm3, final Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.sm3 = sm3;
        this.clock = clock;
    }

    /**
     * 核验：未上链 → NOT_ON_CHAIN（actualDigest 为空）；已上链 → 从账本取摘要三方比对。
     * 账本不可达 → 503 1012S0001（不把"取不到"伪报为通过或未上链）。
     */
    public EvidenceVerifyView verify(final String evidenceNo, final String actorSubjectNo) {
        final String actor = EvidenceRules.actor(actorSubjectNo);
        final EvidenceRecord record = repository.findByNo(evidenceNo)
                .orElseThrow(() -> new BizException(EvidenceErrorCodes.RECORD_NOT_FOUND,
                        EvidenceErrorCodes.RECORD_NOT_FOUND_MESSAGE));
        final String expected = EvidenceDigests.contentDigest(sm3, record.stageType(),
                record.businessNo(), record.operationType(), record.occurredAt(),
                record.subjectDidDigest());
        String actual = null;
        final String conclusion;
        if (record.status() != EvidenceStatus.ON_CHAIN) {
            conclusion = NOT_ON_CHAIN;
        } else {
            final Optional<ChainEntry> entry = gateway.findByTxId(record.chainTxId());
            if (entry.isEmpty()) {
                conclusion = MISMATCH;
            } else {
                actual = entry.get().payloadDigest();
                conclusion = (expected.equals(actual) && actual.equals(record.contentDigest()))
                        ? PASS : MISMATCH;
            }
        }

        final Instant now = clock.instant();
        repository.appendAction(EvidenceActionLog.of(evidenceNo, EvidenceAction.VERIFIED,
                record.status(), record.status(), actor, NONE, conclusion, now));
        return new EvidenceVerifyView(conclusion, record.evidenceNo(), record.businessNo(),
                record.status().name(), expected, actual, record.contentDigest(),
                record.chainTxId(), actor, EvidenceDigests.occurredAtIso(now));
    }
}
