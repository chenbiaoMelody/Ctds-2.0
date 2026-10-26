package com.ctds.connector.interfaces.dto;

import com.ctds.connector.application.ConnectorDetail;
import com.ctds.connector.application.VerificationOutcome;
import com.ctds.connector.domain.ConnectorActionLog;
import com.ctds.connector.domain.ConnectorBinding;
import com.ctds.connector.domain.ConnectorRegistration;
import com.ctds.connector.domain.CurrentDid;
import java.time.LocalDateTime;

/**
 * 连接器域响应视图（interfaces 层转换；不外露内部实现字段）。
 */
public final class ConnectorViews {

    private ConnectorViews() {
    }

    /** 注册申请视图（待核验态 connectorNo 为 null）。 */
    public record RegistrationView(
            String applicationNo,
            String connectorNo,
            String subjectNo,
            String connectorName,
            String connectorType,
            String connectorVersion,
            String status,
            String lastRejectionCode,
            String lastRejectionReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static RegistrationView of(final ConnectorRegistration registration) {
            return new RegistrationView(registration.applicationNo(), registration.connectorNo(),
                    registration.subjectNo(), registration.connectorName(), registration.connectorType(),
                    registration.connectorVersion(), registration.status().name(),
                    registration.lastRejectionCode(), registration.lastRejectionReason(),
                    registration.createdAt(), registration.updatedAt());
        }
    }

    /** 连接器视图（详情 / 绑定 / 内部面含实时 didStatus；列表摘要不含 DID 状态，避免 N 次跨域调用）。 */
    public record ConnectorView(
            String connectorNo,
            String subjectNo,
            String connectorName,
            String connectorType,
            String connectorVersion,
            String status,
            String did,
            String didStatus,
            boolean verified,
            boolean online,
            LocalDateTime lastHeartbeatAt,
            LocalDateTime updatedAt) {

        public static ConnectorView of(final ConnectorDetail detail) {
            final ConnectorRegistration registration = detail.registration();
            final String did = detail.binding() == null ? null : detail.binding().did();
            return new ConnectorView(registration.connectorNo(), registration.subjectNo(),
                    registration.connectorName(), registration.connectorType(),
                    registration.connectorVersion(), registration.status().name(), did,
                    detail.didStatus(), detail.verified(), detail.online(),
                    registration.lastHeartbeatAt(), registration.updatedAt());
        }

        /** 列表摘要（did / didStatus 不查：列表不做逐条跨域调用）。 */
        public static ConnectorView summary(final ConnectorRegistration registration) {
            return new ConnectorView(registration.connectorNo(), registration.subjectNo(),
                    registration.connectorName(), registration.connectorType(),
                    registration.connectorVersion(), registration.status().name(), null, null,
                    false, false, registration.lastHeartbeatAt(), registration.updatedAt());
        }
    }

    /** 绑定视图（行为 8：连接器 ↔ DID ↔ 建立时间 + 实时状态）。 */
    public record BindingView(
            String connectorNo,
            String subjectNo,
            String did,
            String bindingStatus,
            LocalDateTime boundAt,
            String didStatus,
            boolean verified) {

        public static BindingView of(final ConnectorBinding binding, final String didStatus) {
            return new BindingView(binding.connectorNo(), binding.subjectNo(), binding.did(),
                    binding.status().name(), binding.boundAt(), didStatus,
                    CurrentDid.ACTIVE.equals(didStatus));
        }
    }

    /** 核验结论视图（HTTP 200 业务结论；verified=false 时 failedCheck + reason 标明首个不通过项）。 */
    public record VerificationResultView(
            String applicationNo,
            boolean verified,
            String failedCheck,
            String reasonCode,
            String reason,
            String connectorNo,
            String status,
            BindingView binding) {

        public static VerificationResultView of(final VerificationOutcome outcome) {
            final ConnectorRegistration registration = outcome.registration();
            final BindingView binding = outcome.binding() == null
                    ? null
                    : BindingView.of(outcome.binding(), CurrentDid.ACTIVE);
            return new VerificationResultView(registration.applicationNo(), outcome.verified(),
                    outcome.failedCheck() == null ? null : outcome.failedCheck().name(),
                    outcome.reasonCode(), outcome.reason(), registration.connectorNo(),
                    registration.status().name(), binding);
        }
    }

    /** 动作留痕视图。 */
    public record ActionLogView(
            String applicationNo,
            String connectorNo,
            String operatorNo,
            String action,
            String result,
            String reasonCode,
            String detail,
            LocalDateTime createdAt) {

        public static ActionLogView of(final ConnectorActionLog log) {
            return new ActionLogView(log.applicationNo(), log.connectorNo(), log.operatorNo(),
                    log.action(), log.result(), log.reasonCode(), log.detail(), log.createdAt());
        }
    }
}
