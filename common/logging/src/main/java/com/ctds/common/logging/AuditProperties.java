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
}
