package com.ctds.common.idempotency;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 幂等切面（契约 = WBS-2.4.7-hifi B2-B5）：外层切面（@Order 0，先判重再互斥）。
 * 流程：SpEL 求幂等键 → tryAcquire（首次/重复）→ 首次执行业务并缓存结果 / 重复返回首次结果或 1002C0001；
 * 业务异常与序列化失败均释放执行权（可重试）；存储不可用 → 1002S0001（fail-closed）。
 */
@Aspect
@Order(0)
public class IdempotencyAdvice {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAdvice.class);
    private static final String AUDIT_ACTION = "idempotency";

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final AuditRecorder auditRecorder;

    public IdempotencyAdvice(final IdempotencyStore store, final IdempotencyProperties properties,
            final AuditRecorder auditRecorder) {
        this.store = store;
        this.properties = properties;
        this.auditRecorder = auditRecorder;
    }

    @Around("@annotation(idempotent)")
    public Object around(final ProceedingJoinPoint pjp, final Idempotent idempotent) throws Throwable {
        final String fullKey = prefixedKey(idempotent.key(), pjp);
        final boolean acquired = tryAcquireSafe(fullKey);
        if (!acquired) {
            return cachedOrInProgress(fullKey, pjp);
        }
        try {
            final Object result = pjp.proceed();
            try {
                store.complete(fullKey, ResultCodec.serialize(result), expireOf(idempotent));
            } catch (JsonProcessingException e) {
                // 返回值类型不可 JSON 序列化 = 使用方代码缺陷 → 平台内部错误（出站统一"系统繁忙"；
                // 评审①P1-2：契约边界表 1002S0002 字样为笔误（该码语义=锁服务不可用），实现按 1000S9999 修正）
                throw new BizException(ErrorCodes.INTERNAL_ERROR, "幂等结果序列化失败", e);
            } catch (RuntimeException e) {
                // 结果写入存储失败 = 幂等存储不可用（fail-closed，与 tryAcquire/getResult 同口径 1002S0001）
                throw new BizException(IdempotencyErrorCodes.IDEMPOTENCY_STORE_UNAVAILABLE, "幂等存储不可用", e);
            }
            return result;
        } catch (Throwable t) {
            releaseSafe(fullKey);
            throw t;
        }
    }

    private String prefixedKey(final String expression, final ProceedingJoinPoint pjp) {
        final String rawKey = SpelKeyResolver.resolve(expression, pjp);
        if (rawKey == null || rawKey.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "幂等键不能为空");
        }
        return properties.getKeyPrefix() + rawKey;
    }

    private boolean tryAcquireSafe(final String fullKey) {
        try {
            return store.tryAcquire(fullKey, properties.getProcessingTtlSeconds());
        } catch (RuntimeException e) {
            throw new BizException(IdempotencyErrorCodes.IDEMPOTENCY_STORE_UNAVAILABLE, "幂等存储不可用", e);
        }
    }

    private void releaseSafe(final String fullKey) {
        try {
            store.release(fullKey);
        } catch (RuntimeException e) {
            log.warn("idempotency release failed: key={}", fullKey, e);
        }
    }

    private Object cachedOrInProgress(final String fullKey, final ProceedingJoinPoint pjp) throws Throwable {
        final Optional<String> cached;
        try {
            cached = store.getResult(fullKey);
        } catch (RuntimeException e) {
            throw new BizException(IdempotencyErrorCodes.IDEMPOTENCY_STORE_UNAVAILABLE, "幂等存储不可用", e);
        }
        if (cached.isEmpty()) {
            audit(fullKey, AuditOutcome.FAILURE, "in_progress");
            throw new BizException(IdempotencyErrorCodes.IDEMPOTENCY_IN_PROGRESS,
                    IdempotencyErrorCodes.IDEMPOTENCY_IN_PROGRESS_MESSAGE);
        }
        audit(fullKey, AuditOutcome.SUCCESS, "hit");
        final Type returnType = ((MethodSignature) pjp.getSignature()).getMethod().getGenericReturnType();
        try {
            return ResultCodec.deserialize(cached.get(), returnType);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "幂等结果解析失败", e);
        }
    }

    private Duration expireOf(final Idempotent idempotent) {
        return idempotent.expireSeconds() > 0
                ? Duration.ofSeconds(idempotent.expireSeconds())
                : Duration.ofSeconds(properties.getDefaultExpireSeconds());
    }

    private void audit(final String fullKey, final AuditOutcome outcome, final String detail) {
        if (!properties.isAuditEnabled() || auditRecorder == null) {
            return;
        }
        auditRecorder.record(AuditEvent.of("anonymous", AUDIT_ACTION, "idempotency-key",
                fullKey, outcome, Map.of("detail", detail)));
    }
}
