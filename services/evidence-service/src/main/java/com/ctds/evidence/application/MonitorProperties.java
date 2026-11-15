package com.ctds.evidence.application;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 监控读面配置（WBS-3.9.4 hifi §3；应用层自有配置，客户端基址经 @Value 注入沿
 * SpaceTrailClient 先例）。service-jobs = `服务标识:job 标签值` 逗号分隔（Q2-A 全集 12 项，
 * 呈现顺序 = 配置顺序；采集状态由部署形态决定——集群外服务无 series → 如实"未接入"）；
 * 静默缺省 30 分钟、上限 1440（越界 400 + `1013C0001`）。
 */
@ConfigurationProperties(prefix = "ctds.evidence.monitor")
public class MonitorProperties {

    /** 静默时长上限（hifi §2 请求约束）。 */
    public static final int SILENCE_MAX_MINUTES = 1440;

    private String serviceJobs = "";
    private int silenceDefaultMinutes = 30;

    public String getServiceJobs() {
        return serviceJobs;
    }

    public void setServiceJobs(final String serviceJobs) {
        this.serviceJobs = serviceJobs == null ? "" : serviceJobs;
    }

    public int getSilenceDefaultMinutes() {
        return silenceDefaultMinutes;
    }

    public void setSilenceDefaultMinutes(final int silenceDefaultMinutes) {
        this.silenceDefaultMinutes = silenceDefaultMinutes;
    }

    /** 解析 `服务标识:job` 逗号分隔映射（LinkedHashMap 保序）；格式不合法启动即败（沿 chain provider fail-fast 口径）。 */
    public Map<String, String> serviceJobMap() {
        final Map<String, String> map = new LinkedHashMap<>();
        for (final String entry : serviceJobs.split(",")) {
            final String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalStateException(
                        "ctds.evidence.monitor.service-jobs 配置不合法（应为 服务标识:job 标签值）：" + trimmed);
            }
            map.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return map;
    }
}
