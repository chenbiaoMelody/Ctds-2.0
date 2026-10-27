package com.ctds.connector.audit;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.connector.domain.ConnectorActionLog;
import com.ctds.connector.domain.ConnectorLogRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器留痕单点（沿 delivery AuditService 先例）：域动作落 `connector_action_log` + common-logging 审计 JSONL。
 * detail 只记业务要素摘要；禁止凭据、令牌、密钥材料。留痕失败不改变业务结论。
 */
@Service
public class ConnectorAuditService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAuditService.class);
    private static final String TARGET_TYPE = "connector";

    private final ConnectorLogRepository repository;
    private final ObjectProvider<AuditRecorder> auditRecorder;

    public ConnectorAuditService(final ConnectorLogRepository repository,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    /** 成功类动作（申请 / 补正 / 核验通过 / 绑定查验 / 心跳）。 */
    public void success(final String action, final String applicationNo, final String connectorNo,
            final String operatorNo, final String detail) {
        record(action, applicationNo, connectorNo, operatorNo, "SUCCESS", null, detail);
    }

    /**
     * 拒绝类动作（含触发码；不暴露内部细节）。
     * 独立事务提交：拒绝留痕不能随"抛出业务异常 → 外层事务回滚"一起丢失（可追溯义务）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(final String action, final String applicationNo, final String connectorNo,
            final String operatorNo, final String reasonCode, final String detail) {
        record(action, applicationNo, connectorNo, operatorNo, "DENIED", reasonCode, detail);
    }

    /**
     * 业务失败类动作（3.6.5 源自检不通过：200 业务结论 + 失败原因留痕，不是接口错误）。
     * 与拒绝留痕同口径独立提交，不随外层回滚丢失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(final String action, final String connectorNo, final String operatorNo,
            final String reasonCode, final String detail) {
        record(action, null, connectorNo, operatorNo, "FAILURE", reasonCode, detail);
    }

    private void record(final String action, final String applicationNo, final String connectorNo,
            final String operatorNo, final String result, final String reasonCode, final String detail) {
        try {
            repository.insert(new ConnectorActionLog(applicationNo, connectorNo, operatorNo, action,
                    result, reasonCode, detail, null));
        } catch (final RuntimeException e) {
            log.warn("连接器动作留痕写入失败: action={}", action);
        }
        recordJson(action, applicationNo, connectorNo, operatorNo, result, reasonCode, detail);
    }

    private void recordJson(final String action, final String applicationNo, final String connectorNo,
            final String operatorNo, final String result, final String reasonCode, final String detail) {
        final AuditRecorder recorder = auditRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        if (applicationNo != null) {
            values.put("applicationNo", applicationNo);
        }
        if (reasonCode != null) {
            values.put("reasonCode", reasonCode);
        }
        if (detail != null && !detail.isBlank()) {
            values.put("detail", detail);
        }
        try {
            recorder.record(AuditEvent.of(operatorNo, action, TARGET_TYPE,
                    connectorNo == null ? applicationNo : connectorNo, outcomeOf(result), values));
        } catch (final RuntimeException e) {
            log.warn("连接器审计事件写入失败: action={}", action);
        }
    }

    private static AuditOutcome outcomeOf(final String result) {
        return switch (result) {
            case "DENIED" -> AuditOutcome.DENIED;
            case "FAILURE" -> AuditOutcome.FAILURE;
            default -> AuditOutcome.SUCCESS;
        };
    }
}
