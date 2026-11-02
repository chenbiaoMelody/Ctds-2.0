package com.ctds.metering.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计费自动出账配置（前缀 ctds.billing，hifi §4）：默认开启、每月 1 日 00:10 出上月账。
 * 测试 yml 置 enabled=false 并直接调用服务方法（不等待真实时钟）。
 */
@ConfigurationProperties(prefix = "ctds.billing")
public class BillingProperties {

    private final Auto auto = new Auto();

    public Auto getAuto() {
        return auto;
    }

    /** 自动出账调度配置。 */
    public static class Auto {

        private boolean enabled = true;
        private String cron = "0 10 0 1 * ?";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean value) {
            this.enabled = value;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(final String value) {
            this.cron = value;
        }
    }
}
