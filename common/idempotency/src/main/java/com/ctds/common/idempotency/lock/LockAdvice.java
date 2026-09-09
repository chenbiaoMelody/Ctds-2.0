package com.ctds.common.idempotency.lock;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.idempotency.IdempotencyErrorCodes;
import com.ctds.common.idempotency.SpelKeyResolver;
import com.ctds.common.idempotency.lock.LockService.LockHandle;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

/**
 * 锁切面（契约 = WBS-2.4.7-hifi B6-B7）：内层切面（@Order 1，幂等判重之后互斥）。
 * 流程：SpEL 求互斥键 → tryLock（等待超时可配）→ 放行业务 → finally 释放（业务异常不泄漏）；
 * 等待超时 → 1002C0002；锁服务不可用 → 1002S0002（fail-closed）。
 */
@Aspect
@Order(1)
public class LockAdvice {

    private static final Logger log = LoggerFactory.getLogger(LockAdvice.class);
    private static final String AUDIT_ACTION = "distributed-lock";

    private final LockService lockService;
    private final LockProperties properties;
    private final AuditRecorder auditRecorder;

    public LockAdvice(final LockService lockService, final LockProperties properties,
            final AuditRecorder auditRecorder) {
        this.lockService = lockService;
        this.properties = properties;
        this.auditRecorder = auditRecorder;
    }

    @Around("@annotation(locked)")
    public Object around(final ProceedingJoinPoint pjp, final Locked locked) throws Throwable {
        final String fullKey = prefixedKey(locked.key(), pjp);
        final Duration waitTime = locked.waitSeconds() > 0
                ? Duration.ofSeconds(locked.waitSeconds()) : properties.getDefaultWaitTime();
        final Duration leaseTime = locked.leaseSeconds() > 0
                ? Duration.ofSeconds(locked.leaseSeconds()) : properties.getDefaultLeaseTime();
        final Optional<LockHandle> handle = lockService.tryLock(fullKey, waitTime, leaseTime);
        if (handle.isEmpty()) {
            audit(fullKey, AuditOutcome.FAILURE, "timeout");
            throw new BizException(IdempotencyErrorCodes.LOCK_ACQUIRE_TIMEOUT,
                    IdempotencyErrorCodes.LOCK_ACQUIRE_TIMEOUT_MESSAGE);
        }
        try {
            return pjp.proceed();
        } finally {
            try {
                handle.get().unlock();
            } catch (RuntimeException e) {
                // 释放失败不覆盖业务结果；锁由看门狗/leaseTime 自动过期兜底
                log.warn("lock unlock failed, will expire by lease/watchdog: key={}", fullKey, e);
            }
        }
    }

    private String prefixedKey(final String expression, final ProceedingJoinPoint pjp) {
        final String rawKey = SpelKeyResolver.resolve(expression, pjp);
        if (rawKey == null || rawKey.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "锁键不能为空");
        }
        return properties.getKeyPrefix() + rawKey;
    }

    private void audit(final String fullKey, final AuditOutcome outcome, final String detail) {
        if (!properties.isAuditEnabled() || auditRecorder == null) {
            return;
        }
        auditRecorder.record(AuditEvent.of("anonymous", AUDIT_ACTION, "lock-key",
                fullKey, outcome, Map.of("detail", detail)));
    }
}
