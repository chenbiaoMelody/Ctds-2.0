package com.ctds.evidence.domain;

import java.util.List;
import java.util.Optional;

/**
 * 监控数据源端口（WBS-3.9.4；沿 AuditTrailPort 端口先例——应用层只依赖抽象，
 * Prometheus 适配在基础设施侧，换源只换适配器）。实现方 fail-closed：数据源未配置 /
 * 不可达 → 抛业务异常 `1013S0003`（出站 503），不伪造空数据 / 零值（B5-4 同精神）。
 */
public interface MonitorSourcePort {

    /**
     * 单值即时查询：有 series → value 字符串（"NaN" 归一为 empty——无流量 / 无样本 =
     * 未定义，不显示 0）；无 series → empty。
     */
    Optional<String> queryScalar(String promql);

    /** 告警规则快照（封闭两规则；state = Prometheus 规则态 firing/pending/inactive 原文）。 */
    List<AlertRuleSnapshot> alertRules();

    /**
     * 告警规则快照（B7-1 要素投影；P5 实测形状的应用侧载体）：
     * activeAt = 活跃实例最早触发时点（未触发 = null）；severity/targetJob 取自规则标签 /
     * 活跃实例（缺失由调用方登记映射兜底）。
     */
    record AlertRuleSnapshot(
            String name,
            String state,
            String query,
            String severity,
            String targetJob,
            String activeAt) {
    }
}
