package com.ctds.delivery.application;

import com.ctds.delivery.audit.SandboxAuditActions;
import com.ctds.delivery.audit.SandboxAuditService;
import com.ctds.delivery.config.SandboxProperties;
import com.ctds.delivery.domain.SandboxJobRepository;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxRuntime;
import com.ctds.delivery.domain.SandboxStatus;
import com.ctds.delivery.domain.SandboxTask;
import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 沙箱生命周期（hifi §3.2）：到期扫描（条件更新 + 停容器 + 留痕）与启动对账（崩溃 / 重启纠偏）。
 * 单实例语义：memory 幂等 + 本机扫描；多实例部署前换分布式锁 / 选主（登记演进）。
 */
@Service
public class SandboxLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleService.class);
    private static final String REASON_EXPIRED = "EXPIRED";
    private static final String REASON_RECONCILED = "RECONCILED";
    private static final int SCAN_LIMIT = 100;

    private final SandboxRepository repository;
    private final SandboxJobRepository jobRepository;
    private final SandboxRuntime runtime;
    private final SandboxAuditService audit;
    private final SandboxProperties properties;
    private final Clock clock;

    public SandboxLifecycleService(final SandboxRepository repository,
            final SandboxJobRepository jobRepository, final SandboxRuntime runtime,
            final SandboxAuditService audit, final SandboxProperties properties,
            final Clock clock) {
        this.repository = repository;
        this.jobRepository = jobRepository;
        this.runtime = runtime;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** 到期回收（默认 60s；条件更新防并发重复，条件不成立即跳过）。 */
    @Scheduled(fixedDelayString = "${ctds.sandbox.sweep.fixed-delay-ms:60000}")
    public void sweepExpired() {
        if (!properties.getSweep().isEnabled()) {
            return;
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        for (final SandboxTask task : repository.listExpired(now, SCAN_LIMIT)) {
            if (repository.close(task.sandboxNo(), SandboxStatus.EXPIRED, REASON_EXPIRED, now) == 0) {
                continue;
            }
            stopQuietly(task.sandboxNo());
            audit.success(SandboxAuditActions.EXPIRE, task.sandboxNo(), null, task.contractNo(),
                    task.requesterNo());
        }
    }

    /** 启动对账：RUNNING 任务容器缺失 → STOPPED/RECONCILED；残留 RUNNING 作业 → FAILED。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        final LocalDateTime now = LocalDateTime.now(clock);
        try {
            for (final SandboxTask task : repository.listRunning(SCAN_LIMIT)) {
                if (runtime.isRunning(task.sandboxNo())) {
                    continue;
                }
                if (repository.close(task.sandboxNo(), SandboxStatus.STOPPED, REASON_RECONCILED,
                        now) > 0) {
                    audit.success(SandboxAuditActions.RECONCILE, task.sandboxNo(), null,
                            task.contractNo(), task.requesterNo());
                }
            }
        } catch (final RuntimeException e) {
            // 运行时关闭 / 不可用：对账跳过（不把不可判定误记为已停止）
            log.warn("沙箱启动对账跳过（运行时不可用）");
        }
        try {
            jobRepository.failRunning(now);
        } catch (final RuntimeException e) {
            log.warn("沙箱作业对账跳过");
        }
    }

    private void stopQuietly(final String sandboxNo) {
        try {
            runtime.stop(sandboxNo);
        } catch (final RuntimeException e) {
            log.warn("到期容器停止失败（状态已终止，由对账 / 运维兜底）: sandboxNo={}", sandboxNo);
        }
    }
}
