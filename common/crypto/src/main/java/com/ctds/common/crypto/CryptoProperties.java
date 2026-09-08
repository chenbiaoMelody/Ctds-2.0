package com.ctds.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ctds.crypto 配置项（全表见 docs/designs/WBS-2.4.6-hifi.md，契约固化 ADR-006）。
 * 密钥文件位置可配，密钥材料本身禁止入库（红线 7）。
 */
@ConfigurationProperties(prefix = "ctds.crypto")
public class CryptoProperties {

    /** 组件总开关：关闭 = 不注册任何 Bean（使用方注入失败=启动失败，fail-fast）。 */
    private boolean enabled = true;

    private final Local local = new Local();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean value) {
        this.enabled = value;
    }

    public Local getLocal() {
        return local;
    }

    /** SM4 本地密钥文件实现（2.6.3 真实 KMS 以 KeyProvider Bean 覆盖）。 */
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
}
