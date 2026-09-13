package com.ctds.common.crypto;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * ctds.crypto 配置项（全表见 docs/designs/WBS-2.4.6-hifi.md 与 WBS-2.6.3-hifi.md，契约固化 ADR-006/ADR-015）。
 * 密钥文件位置可配，密钥材料本身禁止入库（红线 7）。
 */
@ConfigurationProperties(prefix = "ctds.crypto")
public class CryptoProperties {

    /** 组件总开关：关闭 = 不注册任何 Bean（使用方注入失败=启动失败，fail-fast）。 */
    private boolean enabled = true;

    private final Local local = new Local();

    private final Kms kms = new Kms();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean value) {
        this.enabled = value;
    }

    public Local getLocal() {
        return local;
    }

    public Kms getKms() {
        return kms;
    }

    /** SM4 本地密钥文件实现（演示路径；配置 kms.base-url 后由 KMS 覆盖接入）。 */
    public static class Local {

        /** 密钥文件路径；配置了但文件缺失/坏行 → 启动失败（fail-fast）；未配置 → WARN 一次。 */
        private String keyFile;

        public String getKeyFile() {
            return keyFile;
        }

        public void setKeyFile(final String value) {
            this.keyFile = value;
        }
    }

    /**
     * KMS 托管密钥（WBS-2.6.3）：配置 base-url 即启用 KmsKeyProvider 并覆盖本地实现（ADR-006 §5.1，业务零改动）。
     * 超时字段纯数字按秒解释（@DurationUnit，防毫秒静默失效）。
     */
    public static class Kms {

        /** KMS 服务地址（如 http://kms:8080）；未设置 = 不启用 KMS 模式。 */
        private String baseUrl;

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration connectTimeout = Duration.ofSeconds(3);

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration readTimeout = Duration.ofSeconds(5);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String value) {
            this.baseUrl = value;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(final Duration value) {
            this.connectTimeout = value;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(final Duration value) {
            this.readTimeout = value;
        }
    }
}
