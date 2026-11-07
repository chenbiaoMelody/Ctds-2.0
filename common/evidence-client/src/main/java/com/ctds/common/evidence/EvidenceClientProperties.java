package com.ctds.common.evidence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存证客户端配置（hifi §1.5 / Q8-A）：默认开启；默认地址为空 = 跳过 + WARN，
 * 由部署清单显式注入 {@code CTDS_EVIDENCE_BASEURL}（测试默认不打网络）。
 */
@ConfigurationProperties(prefix = "ctds.evidence.client")
public class EvidenceClientProperties {

    /** 埋点总开关（默认开启；关闭时每次调用一条 WARN，不发请求）。 */
    private boolean enabled = true;

    /** 存证服务基地址（空 = 跳过 + WARN）。 */
    private String baseUrl = "";

    /** 存证服务角色映射名（evidence-service 侧映射到 evidence.internal.write）。 */
    private String roles = "evidence-internal";

    /** 连接超时（默认 2s）。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读取超时（默认 3s）。 */
    private Duration readTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(final String roles) {
        this.roles = roles;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(final Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(final Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
