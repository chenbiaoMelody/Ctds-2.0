package com.ctds.common.auth;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * WBS-3.9.3 Q5-A：生命周期动作启动审计事件（B9-R3/B9-4 三证之"启动审计"）。
 * `ApplicationReadyEvent` 触发：三键（action/change-order/operator）齐备 → 落
 * `domain.lifecycle` 事件（字段 = 变更单编号/操作人/动作/状态/版本，时点由审计载体自带）；
 * 全空 = 零事件（缺省 ACTIVE 基线）；部分填写 = 启动失败 fail-closed（边界 9，登记义务不完整）。
 * outcome 取既有枚举 SUCCESS（hifi §9 E-2 勘误位：AuditOutcome 无 APPLIED 值，枚举零改动）。
 */
public class DomainLifecycleAuditEmitter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DomainLifecycleAuditEmitter.class);

    /** 审计动作名（B9-4 可查锚点；detail.lifecycle-action 携带 OPEN/UPGRADE/RETIRE/RESTORE）。 */
    public static final String AUDIT_ACTION_DOMAIN_LIFECYCLE = "domain.lifecycle";

    private final DomainProperties properties;
    private final AuditRecorder auditRecorder;

    public DomainLifecycleAuditEmitter(final DomainProperties properties,
            final AuditRecorder auditRecorder) {
        this.properties = properties;
        this.auditRecorder = auditRecorder;
    }

    @Override
    public void onApplicationEvent(final ApplicationReadyEvent event) {
        emit();
    }

    /** 就绪时落事件；启动失败语义（部分填写）从本方法抛出并中断启动。 */
    public void emit() {
        properties.validateStatus();
        if (!properties.hasCompleteLifecycleRegistration()) {
            throw new IllegalStateException("ctds.domain 生命周期登记不完整：action / change-order / "
                    + "operator 三键要么全空要么全齐（B9-R3 登记义务，部分填写 = 拒绝启动）");
        }
        if (properties.getAction().isBlank()) {
            return;
        }
        if (auditRecorder == null) {
            log.warn("domain.lifecycle 事件被跳过：AuditRecorder 不可用（ctds.audit 关闭或组件缺失）"
                    + "——部署记录与变更单两证仍有效，审计证缺位");
            return;
        }
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("domainId", properties.getId());
        detail.put("lifecycle-action", properties.getAction());
        detail.put("change-order", properties.getChangeOrder());
        detail.put("operator", properties.getOperator());
        detail.put("status", properties.getStatus());
        detail.put("platform-version", properties.getPlatformVersion());
        auditRecorder.record(AuditEvent.of(properties.getOperator(),
                AUDIT_ACTION_DOMAIN_LIFECYCLE, "deployment-domain",
                properties.getId(), AuditOutcome.SUCCESS, detail));
    }
}
