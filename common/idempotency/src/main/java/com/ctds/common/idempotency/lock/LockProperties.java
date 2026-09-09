package com.ctds.common.idempotency.lock;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 锁组件配置（契约 = WBS-2.4.7-hifi 配置项表；前缀 ctds.lock）。
 */
@ConfigurationProperties(prefix = "ctds.lock")
public class LockProperties {

    /** 组件总开关：false 不注册切面与 Bean（fail-fast，沿 2.4.5 教训）。 */
    private boolean enabled = true;

    /** 实现模式：redis（默认，Redisson）/ memory（单机语义，演示/单测/单机部署兜底）。 */
    private String mode = "redis";

    /** 锁键前缀（防业务键冲突）。 */
    private String keyPrefix = "ctds:lock:";

    /** 获取锁等待超时默认值（@Locked 未显式指定 waitSeconds 时）。 */
    private Duration defaultWaitTime = Duration.ofSeconds(3);

    /** 持锁自动释放默认值（@Locked 未显式指定 leaseSeconds 时；-1 = 看门狗自动续期）。 */
    private Duration defaultLeaseTime = Duration.ofSeconds(-1);

    /** 审计联动开关（锁超时/锁服务异常记审计；沿 2.4.5 已确认口径"默认开启可配置关闭"）。 */
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

    public Duration getDefaultWaitTime() {
        return defaultWaitTime;
    }

    public void setDefaultWaitTime(final Duration defaultWaitTime) {
        this.defaultWaitTime = defaultWaitTime;
    }

    public Duration getDefaultLeaseTime() {
        return defaultLeaseTime;
    }

    public void setDefaultLeaseTime(final Duration defaultLeaseTime) {
        this.defaultLeaseTime = defaultLeaseTime;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(final boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
