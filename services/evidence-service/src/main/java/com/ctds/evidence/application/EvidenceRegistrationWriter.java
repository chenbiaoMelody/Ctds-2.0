package com.ctds.evidence.application;

import com.ctds.evidence.domain.EvidenceActionLog;
import com.ctds.evidence.domain.EvidenceRecord;
import com.ctds.evidence.domain.EvidenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写入第一段事务（hifi §4 事务边界①）：记录 insert 与 CREATED 留痕同一事务提交，
 * 保证"链失败也有记录"且留痕无空窗；上链与状态推进在其后独立事务执行。
 */
@Component
public class EvidenceRegistrationWriter {

    private final EvidenceRepository repository;

    public EvidenceRegistrationWriter(final EvidenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void register(final EvidenceRecord record, final EvidenceActionLog createdAction) {
        repository.insert(record);
        repository.appendAction(createdAction);
    }
}
