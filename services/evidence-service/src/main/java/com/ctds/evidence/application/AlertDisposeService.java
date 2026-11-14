package com.ctds.evidence.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.evidence.application.MonitorViews.DisposeRequest;
import com.ctds.evidence.application.MonitorViews.DisposeView;
import com.ctds.evidence.domain.AlertSilencePort;
import com.ctds.evidence.domain.MonitorErrorCodes;
import com.ctds.evidence.domain.RbacErrorCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 运维处置服务（WBS-3.9.4 hifi §1；B8-R1 处置通道唯一性 = 本服务为唯一鉴权 + 留痕入口）。
 * 三动作：SILENCE = 经平台处置服务转 Alertmanager /api/v2/silences（matcher = alertname，
 * silenceId 入留痕）；ACKNOWLEDGE / ESCALATE = 平台留痕型（不调告警引擎、不改变状态；
 * 升级的实际通知效果"待生效"——v1 路由空 + 外发未接 G-04，诚实边界）。
 * 三动作均落审计事件 {@code alert.dispose}（四要素：处置人 = actor / 时点 = 审计载体自带 /
 * 对象 = targetId / 结论 = detail；outcome=SUCCESS 沿 3.9.3 E-2 先例，枚举零改动）。
 * 处置对象封闭校验（非两规则名 → 400 + `1013C0005`）；静默失败不落"OK"留痕（hifi §4 边界 8）。
 */
@Service
public class AlertDisposeService {

    private static final Logger log = LoggerFactory.getLogger(AlertDisposeService.class);

    /** 审计动作名（B8-1 留痕可查锚点；沿 domain.lifecycle 命名先例）。 */
    public static final String AUDIT_ACTION_ALERT_DISPOSE = "alert.dispose";
    static final String TARGET_TYPE_PLATFORM_ALERT = "platform-alert";
    static final String RESULT_OK = "OK";
    static final String ACTION_SILENCE = "SILENCE";

    /** 处置三动作值域（未知动作 → 400 + 1013C0001）。 */
    private static final Set<String> ACTIONS = Set.of("ACKNOWLEDGE", "SILENCE", "ESCALATE");
    /** 处置对象封闭集（B7-R2 两规则；非成员 → 400 + 1013C0005）。 */
    private static final Set<String> DISPOSABLE_ALERTS = Set.of("CtdsBackendDown", "CtdsBackendHighErrorRate");
    /** 静默缺省时长（hifi §3：缺省 30 分钟，请求可覆盖）。 */
    private static final int DEFAULT_SILENCE_MINUTES = 30;

    private final AlertSilencePort alertSilence;
    private final MonitorProperties properties;
    private final AuditRecorder auditRecorder;
    private final Clock clock;

    public AlertDisposeService(final AlertSilencePort alertSilence,
            final MonitorProperties properties, final Clock clock,
            final ObjectProvider<AuditRecorder> auditRecorderProvider) {
        this.alertSilence = alertSilence;
        this.properties = properties;
        this.clock = clock;
        this.auditRecorder = auditRecorderProvider.getIfAvailable();
    }

    /** 处置入口（唯一鉴权 + 留痕入口；actor = X-Ctds-Subject 头处置人）。 */
    public DisposeView dispose(final String actor, final DisposeRequest request) {
        final String alertName = request.alertName() == null ? "" : request.alertName().trim();
        if (!DISPOSABLE_ALERTS.contains(alertName)) {
            throw new BizException(MonitorErrorCodes.ALERT_NOT_DEFINED,
                    MonitorErrorCodes.ALERT_NOT_DEFINED_MESSAGE);
        }
        final String action = request.action() == null ? "" : request.action().trim();
        if (!ACTIONS.contains(action)) {
            throw new BizException(RbacErrorCodes.PARAM_INVALID, RbacErrorCodes.PARAM_INVALID_MESSAGE);
        }
        final int durationMinutes = resolveSilenceMinutes(action, request.durationMinutes());

        String silenceId = null;
        if (ACTION_SILENCE.equals(action)) {
            // 静默先成功、后留痕：失败（不可达）→ 503，不落"OK"留痕（hifi §4 边界 8）
            final Instant startsAt = clock.instant();
            silenceId = alertSilence.createSilence(alertName, startsAt,
                    startsAt.plus(Duration.ofMinutes(durationMinutes)),
                    actor == null || actor.isBlank() ? "anonymous" : actor,
                    noteOrDefault(request.note()));
        }
        recordAudit(actor, alertName, action, request.note(), durationMinutes, silenceId);
        return new DisposeView(alertName, action, RESULT_OK, silenceId, clock.instant().toString());
    }

    private int resolveSilenceMinutes(final String action, final Integer requested) {
        if (!ACTION_SILENCE.equals(action)) {
            return 0;
        }
        final int minutes = requested == null ? DEFAULT_SILENCE_MINUTES : requested;
        if (minutes < 1 || minutes > MonitorProperties.SILENCE_MAX_MINUTES) {
            throw new BizException(RbacErrorCodes.PARAM_INVALID, RbacErrorCodes.PARAM_INVALID_MESSAGE);
        }
        return minutes;
    }

    private void recordAudit(final String actor, final String alertName, final String action,
            final String note, final int durationMinutes, final String silenceId) {
        if (auditRecorder == null) {
            // 沿 3.9.3 E-7 口径：留痕载体缺位不阻断处置，但显式 WARN 可观测（交付说明如实登记）
            log.warn("alert.dispose 事件被跳过：AuditRecorder 不可用（ctds.audit 关闭或组件缺失）"
                    + "——处置已执行，留痕证缺位");
            return;
        }
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("action", action);
        if (note != null && !note.isBlank()) {
            detail.put("note", note);
        }
        if (ACTION_SILENCE.equals(action)) {
            detail.put("durationMinutes", String.valueOf(durationMinutes));
            detail.put("silenceId", silenceId);
        }
        auditRecorder.record(AuditEvent.of(actor, AUDIT_ACTION_ALERT_DISPOSE,
                TARGET_TYPE_PLATFORM_ALERT, alertName, AuditOutcome.SUCCESS, detail));
    }

    private static String noteOrDefault(final String note) {
        return note == null || note.isBlank() ? "平台处置服务静默（WBS-3.9.4）" : note;
    }
}
