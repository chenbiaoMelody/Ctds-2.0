package com.ctds.common.idempotency;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * 幂等组件配置（契约 = WBS-2.4.7-hifi 配置项表；前缀 ctds.idempotency）。
 */
@ConfigurationProperties(prefix = "ctds.idempotency")
public class IdempotencyProperties {

    /** 组件总开关：false 不注册切面与 Bean（fail-fast：使用方注入失败即启动失败，沿 2.4.5 教训）。 */
    private boolean enabled = true;

    /** 实现模式：redis（默认，production 语义）/ memory（单机语义，演示/单测/单机部署兜底）。 */
    private String mode = "redis";

    /** 幂等键前缀（防业务键冲突）。 */
    private String keyPrefix = "ctds:idem:";

    /** 执行中标记 TTL（契约键 processing-ttl-seconds，纯数字=秒）：须 ≥ 业务最长执行时间，
     *  否则超长业务可能被重复执行（使用方责任，hifi 边界表；@DurationUnit 防纯数字被按毫秒静默解析，评审③P1）。 */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration processingTtlSeconds = Duration.ofSeconds(60);

    /** 结果缓存默认 TTL（@Idempotent 未显式指定 expireSeconds 时）。 */
    private long defaultExpireSeconds = 600;

    /** 审计联动开关（幂等命中/处理中记审计；沿 2.4.5 已确认口径"默认开启可配置关闭"）。 */
    private boolean auditEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(final String mode) {
        this.mode = mode;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(final String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getProcessingTtlSeconds() {
        return processingTtlSeconds;
    }

    public void setProcessingTtlSeconds(final Duration processingTtlSeconds) {
        this.processingTtlSeconds = processingTtlSeconds;
    }

    public long getDefaultExpireSeconds() {
        return defaultExpireSeconds;
    }

    public void setDefaultExpireSeconds(final long defaultExpireSeconds) {
        this.defaultExpireSeconds = defaultExpireSeconds;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(final boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
