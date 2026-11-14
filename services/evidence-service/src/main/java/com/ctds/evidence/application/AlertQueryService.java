package com.ctds.evidence.application;

import com.ctds.evidence.application.MonitorViews.AlertItemView;
import com.ctds.evidence.application.MonitorViews.AlertsView;
import com.ctds.evidence.domain.MonitorSourcePort;
import com.ctds.evidence.domain.MonitorSourcePort.AlertRuleSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 告警列表映射（WBS-3.9.4 hifi §1；Q4-A：Prometheus rules 权威渲染"规则即告警项"）。
 * 状态映射（B7-R1 本卡落定）：firing → 已触发；pending / inactive → 已恢复（未触发）；
 * 触发时点 = 活跃实例最早 activeAt（端口适配器并入快照）；当前值 = 规则表达式实时查询
 * （NaN / 无 series → null，不显示 0）；阈值 = 封闭两规则登记映射（P5 以规则文件原文核对）。
 * 列表读面不依赖 Alertmanager 存活（仅 Prometheus 一个数据源）。
 */
@Service
public class AlertQueryService {

    /** pending 态 raw 透传文案（hifi §2 契约原文）。 */
    static final String PENDING_NOTE = "条件满足等待窗口，未正式触发";
    /** 规则未在数据源注册（保守视为未触发，不伪造触发态）。 */
    static final String MISSING_RULE_NOTE = "规则未在数据源注册，保守视为未触发";

    /** 状态二值（B7-1）：firing 已触发 / resolved 已恢复。 */
    static final String STATE_FIRING = "firing";
    static final String STATE_RESOLVED = "resolved";

    /** 封闭两规则登记映射（B7-R2 规则封闭；阈值文案 = 规则文件 annotations 口径，target = job 标签）。 */
    private static final Map<String, RuleRegistration> REGISTERED_RULES = new LinkedHashMap<>();

    static {
        REGISTERED_RULES.put("CtdsBackendDown",
                new RuleRegistration("critical", "ctds-backend", "up == 0（服务不可达/副本下线）"));
        REGISTERED_RULES.put("CtdsBackendHighErrorRate",
                new RuleRegistration("warning", "ctds-backend", "5xx 占比 > 5%（5m 窗口）"));
    }

    private record RuleRegistration(String severity, String target, String threshold) {
    }

    private final MonitorSourcePort monitorSource;

    public AlertQueryService(final MonitorSourcePort monitorSource) {
        this.monitorSource = monitorSource;
    }

    /** 告警列表（封闭两规则固定入列，顺序 = 登记映射顺序）。 */
    public AlertsView alerts() {
        final Map<String, AlertRuleSnapshot> byName = new LinkedHashMap<>();
        for (final AlertRuleSnapshot snapshot : monitorSource.alertRules()) {
            byName.put(snapshot.name(), snapshot);
        }
        final List<AlertItemView> items = new ArrayList<>();
        for (final Map.Entry<String, RuleRegistration> entry : REGISTERED_RULES.entrySet()) {
            items.add(item(entry.getKey(), entry.getValue(), byName.get(entry.getKey())));
        }
        return new AlertsView(List.copyOf(items));
    }

    private AlertItemView item(final String alertName, final RuleRegistration registration,
            final AlertRuleSnapshot snapshot) {
        final boolean firing = snapshot != null && STATE_FIRING.equals(snapshot.state());
        final Optional<String> current = snapshot == null
                ? Optional.empty()
                : monitorSource.queryScalar(snapshot.query());
        return new AlertItemView(
                alertName,
                severity(snapshot, registration),
                target(snapshot, registration),
                firing ? snapshot.activeAt() : null,
                current.flatMap(AlertQueryService::parse).orElse(null),
                registration.threshold(),
                firing ? STATE_FIRING : STATE_RESOLVED,
                stateNote(snapshot));
    }

    /** Q4-A 映射：state=firing → 已触发；pending / inactive（含规则缺失）→ 已恢复（未触发）。 */
    private static String stateNote(final AlertRuleSnapshot snapshot) {
        if (snapshot == null) {
            return MISSING_RULE_NOTE;
        }
        return "pending".equals(snapshot.state()) ? PENDING_NOTE : null;
    }

    private static String severity(final AlertRuleSnapshot snapshot,
            final RuleRegistration registration) {
        return (snapshot == null || snapshot.severity() == null || snapshot.severity().isBlank())
                ? registration.severity() : snapshot.severity();
    }

    private static String target(final AlertRuleSnapshot snapshot,
            final RuleRegistration registration) {
        return (snapshot == null || snapshot.targetJob() == null || snapshot.targetJob().isBlank())
                ? registration.target() : snapshot.targetJob();
    }

    private static Optional<Double> parse(final String text) {
        try {
            return Optional.of(Double.parseDouble(text));
        } catch (final NumberFormatException failure) {
            return Optional.empty();
        }
    }
}
