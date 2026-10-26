package com.ctds.connector.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连接器服务参数（hifi §12）：跨域只读调用基址与超时 + 健康度离线阈值。
 * 阈值启动校验 1m~1440m（越界 fail-fast，避免"永远在线/永远离线"的静默错配）。
 */
@ConfigurationProperties(prefix = "ctds.connector")
public class ConnectorProperties {

    private static final Duration MIN_OFFLINE_AFTER = Duration.ofMinutes(1);
    private static final Duration MAX_OFFLINE_AFTER = Duration.ofMinutes(1440);

    private final Subject subject = new Subject();
    private final Did did = new Did();
    private final Health health = new Health();

    public Subject getSubject() {
        return subject;
    }

    public Did getDid() {
        return did;
    }

    public Health getHealth() {
        return health;
    }

    @PostConstruct
    void validate() {
        final Duration offlineAfter = health.getOfflineAfter();
        if (offlineAfter == null || offlineAfter.compareTo(MIN_OFFLINE_AFTER) < 0
                || offlineAfter.compareTo(MAX_OFFLINE_AFTER) > 0) {
            throw new IllegalStateException("ctds.connector.health.offline-after 取值须在 1m~1440m 之间");
        }
    }

    /** 主体服务内部只读面配置。 */
    public static class Subject {

        private String baseUrl = "";
        private Duration timeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(final Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** DID 服务内部只读面配置。 */
    public static class Did {

        private String baseUrl = "";
        private Duration timeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(final Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** 健康度参数（Q8-A：读时派生，无调度）。 */
    public static class Health {

        private Duration offlineAfter = Duration.ofMinutes(5);

        public Duration getOfflineAfter() {
            return offlineAfter;
        }

        public void setOfflineAfter(final Duration offlineAfter) {
            this.offlineAfter = offlineAfter;
        }
    }
}
