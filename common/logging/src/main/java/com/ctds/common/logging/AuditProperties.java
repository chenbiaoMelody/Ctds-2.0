package com.ctds.common.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计组件配置项（前缀 ctds.audit，ADR-005 §3 第 6 项）。
 */
@ConfigurationProperties(prefix = "ctds.audit")
public class AuditProperties {

    /** 是否启用审计埋点。 */
    private boolean enabled = true;

    /** 审计文件目录（相对工作目录）。 */
    private String fileDir = "logs";

    /** 事件队列容量（满后丢弃并计数告警）。 */
    private int queueCapacity = 10000;

    /** 审计出口类型（WBS-3.7.5 Q1-A）：null/其他 = 仅文件（现状默认，其他服务零影响）；
     *  "metering-db" = 文件 + 计量落库双写（file+jdbc 组合——文件存证职能不中断）。 */
    private String sink;

    /** 计量落库数据源 URL（sink=metering-db 时必填；指向 ctds_metering 库，账号 INSERT-only）。 */
    private String jdbcUrl;

    /** 计量落库账号。 */
    private String jdbcUsername;

    /** 计量落库口令（只经环境变量注入，章程红线 7）。 */
    private String jdbcPassword;

    /** 计量流水表名（默认 metering_flow）。 */
    private String meteringTable = "metering_flow";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getFileDir() {
        return fileDir;
    }

    public void setFileDir(final String fileDir) {
        this.fileDir = fileDir;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(final int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public String getSink() {
        return sink;
    }

    public void setSink(final String sink) {
        this.sink = sink;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(final String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public void setJdbcUsername(final String jdbcUsername) {
        this.jdbcUsername = jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public void setJdbcPassword(final String jdbcPassword) {
        this.jdbcPassword = jdbcPassword;
    }

    public String getMeteringTable() {
        return meteringTable;
    }

    public void setMeteringTable(final String meteringTable) {
        this.meteringTable = meteringTable;
    }
}
