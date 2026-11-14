package com.ctds.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WBS-3.9.3 T3/T5/T7 留痕腿 + 边界 9/10：生命周期三键齐备 → 启动落 `domain.lifecycle`
 * 审计事件（字段 = 变更单编号/操作人/动作/状态/版本，B9-4 五要素）；部分填写 = 启动失败
 * fail-closed；全空（缺省）= 零事件（hifi §3 / §4 边界 9~10；outcome 映射见 hifi §9 E-2）。
 */
class DomainLifecycleAuditEmitterTest {

    private static final class CapturingRecorder implements AuditRecorder {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(final AuditEvent event) {
            events.add(event);
        }
    }

    private static DomainProperties completeProperties() {
        DomainProperties properties = new DomainProperties();
        properties.setId("ctds-demo-domain");
        properties.setName("演示部署域");
        properties.setPlatformVersion("V1.0");
        properties.setStatus("RETIRED");
        properties.setAction("RETIRE");
        properties.setChangeOrder("CHG-2026-11-001");
        properties.setOperator("ops-a");
        return properties;
    }

    @Test
    void completeRegistrationEmitsLifecycleEventWithFiveElements() {
        CapturingRecorder recorder = new CapturingRecorder();
        new DomainLifecycleAuditEmitter(completeProperties(), recorder).emit();
        assertThat(recorder.events).hasSize(1);
        AuditEvent event = recorder.events.get(0);
        assertThat(event.action()).isEqualTo("domain.lifecycle");
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.actor()).isEqualTo("ops-a");
        assertThat(event.targetType()).isEqualTo("deployment-domain");
        assertThat(event.targetId()).isEqualTo("ctds-demo-domain");
        assertThat(event.detail())
                .containsEntry("domainId", "ctds-demo-domain")
                .containsEntry("lifecycle-action", "RETIRE")
                .containsEntry("change-order", "CHG-2026-11-001")
                .containsEntry("operator", "ops-a")
                .containsEntry("status", "RETIRED")
                .containsEntry("platform-version", "V1.0");
    }

    @Test
    void upgradeActionEmitsUpgradeEventFields() {
        CapturingRecorder recorder = new CapturingRecorder();
        DomainProperties properties = completeProperties();
        properties.setAction("UPGRADE");
        properties.setStatus("ACTIVE");
        properties.setPlatformVersion("V1.1");
        new DomainLifecycleAuditEmitter(properties, recorder).emit();
        assertThat(recorder.events.get(0).detail())
                .containsEntry("lifecycle-action", "UPGRADE")
                .containsEntry("platform-version", "V1.1")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void blankRegistrationEmitsNothing() {
        CapturingRecorder recorder = new CapturingRecorder();
        new DomainLifecycleAuditEmitter(new DomainProperties(), recorder).emit();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    void partialRegistrationFailsStartupFailClosed() {
        DomainProperties properties = new DomainProperties();
        properties.setAction("RETIRE");
        assertThatThrownBy(() ->
                new DomainLifecycleAuditEmitter(properties, new CapturingRecorder()).emit())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ctds.domain");
    }

    @Test
    void missingRecorderWithCompleteRegistrationIsSkippedWithoutFailure() {
        assertThatCode(() -> new DomainLifecycleAuditEmitter(completeProperties(), null).emit())
                .as("无 recorder 时不抛异常（hifi §9 E-7①：跳过 + 显式 WARN 审计证缺位，两证仍有效）")
                .doesNotThrowAnyException();
    }
}
