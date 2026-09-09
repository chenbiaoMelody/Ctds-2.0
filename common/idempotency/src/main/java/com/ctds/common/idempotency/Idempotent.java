package com.ctds.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等方法注解（契约 = docs/designs/WBS-2.4.7-hifi.md / ADR-007）：同一幂等键只执行一次业务，
 * 重复请求不重复执行、返回首次结果（模式 B 结果复用）。幂等键须在结果 TTL 窗口内保持业务语义稳定。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 幂等键 SpEL 表达式（方法参数上下文，如 "#orderNo" 或 "#order.orderNo"）；必填，
     * 求值结果不能为 null/空串（违反 → 1000C0001，快速失败，不执行业务）。
     */
    String key();

    /**
     * 结果缓存有效期（秒）；&lt;=0 时取配置 ctds.idempotency.default-expire-seconds（默认 600）。
     * 到期后同一幂等键允许重新执行。执行中标记 TTL 独立取配置 ctds.idempotency.processing-ttl-seconds。
     */
    long expireSeconds() default -1;
}
