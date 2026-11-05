package com.ctds.evidence.application;

import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写入第三段事务（hifi §4 事务边界③）：状态更新与流转留痕同一事务，
 * 避免"状态变了、留痕丢了"。账本写入（②）在其之前独立提交。
 */
@Component
public class EvidenceStateWriter {

    private final EvidenceRepository repository;

    public EvidenceStateWriter(final EvidenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void transition(final EvidenceRecord updated, final EvidenceActionLog action) {
        repository.updateStatus(updated);
        repository.appendAction(action);
    }
}
