package com.ctds.space.application;

import com.ctds.common.evidence.EvidenceEvent;
import com.ctds.common.evidence.EvidenceRecorder;
import com.ctds.space.infrastructure.DidInternalClient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 空间授权环节存证埋点（WBS-3.8.3 hifi §2 E1/E2）：
 * 成员准入 → {@code AUTHORIZATION/SPACE_MEMBER_ADMITTED}（单据号 {@code SPACE{id}:ADMISSION{id}}）；
 * 策略覆盖生效 → {@code AUTHORIZATION/SPACE_POLICY_INHERITED}（单据号 {@code SPACE{id}:POLICY-AL{留痕id}}）。
 * 主体 DID 经 did 内部端点解析；取不到即跳过该条并告警（不伪造，规格 B1-4）。
 */
@Component
public class SpaceEvidencePublisher {

    private static final Logger log = LoggerFactory.getLogger(SpaceEvidencePublisher.class);
    private static final String STAGE_AUTHORIZATION = "AUTHORIZATION";
    private static final String OP_MEMBER_ADMITTED = "SPACE_MEMBER_ADMITTED";
    private static final String OP_POLICY_INHERITED = "SPACE_POLICY_INHERITED";

    private final DidInternalClient didClient;
    private final EvidenceRecorder evidenceRecorder;
    private final Clock clock;

    public SpaceEvidencePublisher(final DidInternalClient didClient,
            final EvidenceRecorder evidenceRecorder, final Clock clock) {
        this.didClient = didClient;
        this.evidenceRecorder = evidenceRecorder;
        this.clock = clock;
    }

    /** 成员准入生效（审批通过 / 邀请确认）。 */
    public void memberAdmitted(final long spaceId, final long admissionId, final String subjectNo,
            final String operator, final LocalDateTime occurredAt) {
        publish("SPACE" + spaceId + ":ADMISSION" + admissionId, OP_MEMBER_ADMITTED, subjectNo,
                operator, occurredAt);
    }

    /** 空间策略覆盖生效（Q4：以覆盖成功承载，同值幂等 / 红线拒绝不调用）。 */
    public void policyEffective(final long spaceId, final long actionLogId, final String subjectNo,
            final String operator, final LocalDateTime occurredAt) {
        publish("SPACE" + spaceId + ":POLICY-AL" + actionLogId, OP_POLICY_INHERITED, subjectNo,
                operator, occurredAt);
    }

    private void publish(final String businessNo, final String operationType, final String subjectNo,
            final String operator, final LocalDateTime occurredAt) {
        final Optional<String> did = didClient.activeDid(subjectNo);
        if (did.isEmpty()) {
            log.warn("空间存证跳过（主体 DID 不可用）: businessNo={}, operationType={}", businessNo,
                    operationType);
            return;
        }
        evidenceRecorder.record(new EvidenceEvent(STAGE_AUTHORIZATION, businessNo, operationType,
                did.get(), occurredAt.atZone(clock.getZone()).toInstant(), operator));
    }
}
