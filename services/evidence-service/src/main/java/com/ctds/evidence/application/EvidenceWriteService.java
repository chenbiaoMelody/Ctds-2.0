package com.ctds.evidence.application;

import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.ChainPayload;
import com.ctds.evidence.domain.ChainReceipt;
import com.ctds.evidence.domain.EvidenceAction;
import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceChainUnavailableException;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.EvidenceGateway;
import com.ctds.evidence.domain.EvidenceNoGenerator;
import com.ctds.evidence.domain.EvidenceOperationType;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import com.ctds.evidence.domain.EvidenceRules;
import com.ctds.evidence.domain.EvidenceStageType;
import com.ctds.evidence.domain.EvidenceStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 存证写入应用服务（hifi §4；规格 B1-1 / B2-1 / B2-2 / B4-1 / B4-3）：
 *
 * <ol>
 *   <li>校验与幂等预检 → 摘要 / 取号 → ① 记录 + CREATED 留痕（独立事务）；</li>
 *   <li>同步上链 → ② 账本写入（适配器独立事务）→ ③ 状态 + 流转留痕（同一事务）；</li>
 *   <li>上链失败不回滚、不静默：记录保持存在并置 FAILED + 脱敏原因摘要，业务侧仍 200（fail-visible）；</li>
 *   <li>状态 / 留痕写入失败不回写 FAILED（不伪造链结果），直接 500 1012S0002，重试可恢复；</li>
 *   <li>重试为手动动作，无 MQ / 无定时任务（Q3-A）；已上链再重试 → 409 1012C0003。</li>
 * </ol>
 */
@Service
public class EvidenceWriteService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceWriteService.class);
    private static final String CHAIN_UNAVAILABLE_SUMMARY = "存证链暂不可达";
    private static final String UNEXPECTED_FAILURE_SUMMARY = "存证处理异常";

    private final EvidenceRepository repository;
    private final EvidenceRegistrationWriter registrationWriter;
    private final EvidenceStateWriter stateWriter;
    private final EvidenceNoGenerator noGenerator;
    private final EvidenceGateway gateway;
    private final Sm3Service sm3;
    private final Clock clock;

    public EvidenceWriteService(final EvidenceRepository repository,
            final EvidenceRegistrationWriter registrationWriter, final EvidenceStateWriter stateWriter,
            final EvidenceNoGenerator noGenerator, final EvidenceGateway gateway,
            final Sm3Service sm3, final Clock clock) {
        this.repository = repository;
        this.registrationWriter = registrationWriter;
        this.stateWriter = stateWriter;
        this.noGenerator = noGenerator;
        this.gateway = gateway;
        this.sm3 = sm3;
        this.clock = clock;
    }

    /** 内部写面：创建存证（幂等；恒返回视图，不因链故障抛错）。 */
    public EvidenceCreateView create(final CreateEvidenceCommand command) {
        final EvidenceStageType stageType = EvidenceStageType.parse(command.stageType());
        final EvidenceOperationType operationType =
                EvidenceOperationType.parse(command.operationType(), stageType);
        final String businessNo = EvidenceRules.businessNo(command.businessNo());
        final String subjectDid = EvidenceRules.subjectDid(command.subjectDid());
        final String actor = EvidenceRules.actor(command.actorSubjectNo());
        final Instant occurredAt = EvidenceRules.occurredAt(command.occurredAt(), clock);

        final Optional<EvidenceRecord> existing =
                repository.findByIdempotentKey(stageType, businessNo, operationType);
        if (existing.isPresent()) {
            return EvidenceCreateView.from(existing.get(), true);
        }

        final String didDigest = EvidenceDigests.subjectDidDigest(sm3, subjectDid);
        final String contentDigest = EvidenceDigests.contentDigest(sm3, stageType, businessNo,
                operationType, occurredAt, didDigest);
        final Instant now = clock.instant();
        final String evidenceNo = noGenerator.next(LocalDate.ofInstant(now, ZoneOffset.UTC));
        final EvidenceRecord record = EvidenceRecord.pending(evidenceNo, stageType, businessNo,
                operationType, didDigest, occurredAt, contentDigest, now);
        final EvidenceActionLog created = EvidenceActionLog.of(evidenceNo, EvidenceAction.CREATED,
                null, EvidenceStatus.PENDING, actor, "NONE", null, now);
        try {
            registrationWriter.register(record, created);
        } catch (final DuplicateKeyException e) {
            // 并发同三元组：唯一键兜底 → 回查返回既有记录（零新行、零新留痕）
            return repository.findByIdempotentKey(stageType, businessNo, operationType)
                    .map(saved -> EvidenceCreateView.from(saved, true))
                    .orElseThrow(() -> new BizException(EvidenceErrorCodes.STORAGE_FAILED,
                            EvidenceErrorCodes.STORAGE_FAILED_MESSAGE));
        }
        final EvidenceRecord saved = repository.findByNo(evidenceNo)
                .orElseThrow(() -> new BizException(EvidenceErrorCodes.STORAGE_FAILED,
                        EvidenceErrorCodes.STORAGE_FAILED_MESSAGE));
        return submit(saved, actor, false);
    }

    /** 手动重试上链（规格 B4-3）；仍不可达 → 记录保持 FAILED 后抛 503 1012S0001。 */
    public EvidenceRecordView retry(final String evidenceNo, final String actorSubjectNo) {
        final String actor = EvidenceRules.actor(actorSubjectNo);
        final EvidenceRecord record = repository.findByNo(evidenceNo)
                .orElseThrow(() -> new BizException(EvidenceErrorCodes.RECORD_NOT_FOUND,
                        EvidenceErrorCodes.RECORD_NOT_FOUND_MESSAGE));
        if (record.status() == EvidenceStatus.ON_CHAIN) {
            throw new BizException(EvidenceErrorCodes.STATE_CONFLICT,
                    EvidenceErrorCodes.STATE_CONFLICT_MESSAGE);
        }
        final Instant now = clock.instant();
        final ChainReceipt receipt;
        try {
            receipt = gateway.submit(payload(record));
        } catch (final RuntimeException e) {
            final String summary = summarize(e);
            log.error("存证重试仍不可达 evidenceNo={}", evidenceNo, e);
            final EvidenceRecord failed = record.retryFailed(summary, now);
            transition(failed, EvidenceActionLog.of(evidenceNo,
                    EvidenceAction.RETRY_FAILED, record.status(), EvidenceStatus.FAILED,
                    actor, EvidenceErrorCodes.CHAIN_UNAVAILABLE.value(), summary, now));
            throw new BizException(EvidenceErrorCodes.CHAIN_UNAVAILABLE,
                    EvidenceErrorCodes.CHAIN_UNAVAILABLE_MESSAGE);
        }
        final EvidenceRecord onChain = record.onChain(receipt.chainTxId(), receipt.blockNo(),
                record.retryCount() + 1, now);
        transition(onChain, EvidenceActionLog.of(evidenceNo,
                EvidenceAction.RETRY_SUCCEEDED, record.status(), EvidenceStatus.ON_CHAIN,
                actor, "NONE", null, now));
        return EvidenceRecordView.from(onChain);
    }

    private EvidenceCreateView submit(final EvidenceRecord record, final String actor,
            final boolean duplicate) {
        final Instant now = clock.instant();
        final ChainReceipt receipt;
        try {
            receipt = gateway.submit(payload(record));
        } catch (final RuntimeException e) {
            final String summary = summarize(e);
            log.error("存证上链失败（fail-visible，业务不回滚） evidenceNo={}", record.evidenceNo(), e);
            final EvidenceRecord failed = record.failed(summary, now);
            transition(failed, EvidenceActionLog.of(record.evidenceNo(),
                    EvidenceAction.CHAIN_FAILED, EvidenceStatus.PENDING, EvidenceStatus.FAILED,
                    actor, EvidenceErrorCodes.CHAIN_UNAVAILABLE.value(), summary, now));
            return EvidenceCreateView.from(failed, duplicate);
        }
        final EvidenceRecord onChain = record.onChain(receipt.chainTxId(), receipt.blockNo(), 0, now);
        transition(onChain, EvidenceActionLog.of(record.evidenceNo(),
                EvidenceAction.CHAIN_SUCCEEDED, EvidenceStatus.PENDING, EvidenceStatus.ON_CHAIN,
                actor, "NONE", null, now));
        return EvidenceCreateView.from(onChain, duplicate);
    }

    /**
     * 第三段事务（状态 + 留痕）：写失败**不得**回写 {@code FAILED} 伪造链结果——链已成功时
     * 记录保持原状态，直接按 1012S0002（500）上抛，由后续重试命中 {@code uk_chain_payload}
     * 复用回执恢复（hifi §4 状态写入失败口径）。
     */
    private void transition(final EvidenceRecord updated, final EvidenceActionLog action) {
        try {
            stateWriter.transition(updated, action);
        } catch (final RuntimeException e) {
            log.error("存证状态写入失败（不伪造链结果，记录保持原状态） evidenceNo={} txId={}",
                    updated.evidenceNo(), updated.chainTxId(), e);
            throw new BizException(EvidenceErrorCodes.STORAGE_FAILED,
                    EvidenceErrorCodes.STORAGE_FAILED_MESSAGE);
        }
    }

    private static ChainPayload payload(final EvidenceRecord record) {
        return new ChainPayload(EvidenceDigests.payloadJson(record.businessNo(), record.occurredAt(),
                record.operationType(), record.subjectDidDigest(), record.contentDigest()),
                record.contentDigest());
    }

    /**
     * 原因摘要：固定业务可读文案，**不含异常类名 / 表名 / 堆栈**（迁移列注释与 hifi §5 口径）；
     * 异常类名与堆栈只进服务日志（P2-1）。
     */
    private static String summarize(final RuntimeException e) {
        return (e instanceof EvidenceChainUnavailableException)
                ? CHAIN_UNAVAILABLE_SUMMARY : UNEXPECTED_FAILURE_SUMMARY;
    }
}
