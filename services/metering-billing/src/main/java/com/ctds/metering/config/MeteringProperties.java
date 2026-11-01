package com.ctds.metering.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计量采集配置项（前缀 ctds.metering，hifi §2）。
 * 跨 schema 库名 = 配置化常量：schema 名在绑定期校验标识符形态（fail-fast），
 * 拼入 SQL 前已保证仅含字母数字下划线——配置错误启动即失败，不留运行时注入面。
 */
@ConfigurationProperties(prefix = "ctds.metering")
public class MeteringProperties {

    private final SourceSchema sourceSchema = new SourceSchema();

    public SourceSchema getSourceSchema() {
        return sourceSchema;
    }

    /** 事件源库名（Q1-A 通道 2 跨 schema 只读；默认值 = 演示环境三库口径）。 */
    public static class SourceSchema {

        private static final String IDENTIFIER = "^[A-Za-z0-9_]{1,64}$";

        private String delivery = "ctds_delivery";
        private String contract = "ctds_contract";

        public String getDelivery() {
            return delivery;
        }

        public void setDelivery(final String value) {
            this.delivery = requireIdentifier(value);
        }

        public String getContract() {
            return contract;
        }

        public void setContract(final String value) {
            this.contract = requireIdentifier(value);
        }

        private static String requireIdentifier(final String value) {
            if (value == null || !value.matches(IDENTIFIER)) {
                throw new IllegalArgumentException("事件源库名必须是标识符形态（字母数字下划线，≤64）");
            }
            return value;
        }
    }
}
