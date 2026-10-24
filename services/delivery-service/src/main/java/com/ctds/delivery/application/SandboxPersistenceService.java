package com.ctds.delivery.application;

import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.domain.SandboxActionLog;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 沙箱任务事务边界（开通成功 = 任务行 + 动作留痕同事务；沿 DeliveryPersistenceService 先例）。 */
@Service
public class SandboxPersistenceService {

    private final SandboxRepository repository;

    public SandboxPersistenceService(final SandboxRepository repository) {
        this.repository = repository;
    }

    /** 开通成功落库：任务（RUNNING）+ CREATE 动作留痕。 */
    @Transactional
    public void persistCreated(final SandboxTask task) {
        repository.insert(task);
        repository.insertActionLog(new SandboxActionLog(null, task.sandboxNo(), null,
                task.contractNo(), task.requesterNo(), SandboxAuditActions.CREATE, "SUCCESS", null,
                null));
    }
}
