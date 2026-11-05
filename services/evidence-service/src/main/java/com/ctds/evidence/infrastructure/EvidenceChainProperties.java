package com.ctds.evidence.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存证网关配置（hifi §6）：provider 默认 {@code local}（本地不可变账本）；
 * 未知 provider 启动 fail-fast（不静默降级——假链等于假证据）。
 * {@code enabled=false} 仅用于演示期构造"链不可达"（需重启，无运行期开关/后门，Q7-A）。
 */
@ConfigurationProperties(prefix = "ctds.evidence.chain")
public class EvidenceChainProperties {

    private String provider = "local";
    private boolean enabled = true;

    public String getProvider() {
        return provider;
    }

    public void setProvider(final String provider) {
        this.provider = provider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }
}
