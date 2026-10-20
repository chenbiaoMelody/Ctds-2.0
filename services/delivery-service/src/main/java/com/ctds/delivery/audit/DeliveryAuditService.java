package com.ctds.delivery.audit;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.delivery.domain.DeliveryActionLog;
import com.ctds.delivery.domain.DeliveryRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 交付留痕单点：域动作落 `delivery_action_log`（可查/可取证）+ common-logging 审计 JSONL。
 * 字段最小必要；禁止文件本体、原文、路径、密钥材料、令牌。留痕失败不改变业务结论。
 */
@Service
public class DeliveryAuditService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAuditService.class);

    private final DeliveryRepository repository;
    private final ObjectProvider<AuditRecorder> auditRecorder;

    public DeliveryAuditService(final DeliveryRepository repository,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    /** 成功类动作（create/read/download/governance）：DB 动作留痕 + 审计 JSONL。 */
    public void success(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo) {
        success(action, deliveryNo, contractNo, operatorNo, null);
    }

    /** 成功类动作（带交付类型；hifi §7.3 create 字段）。 */
    public void success(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String deliveryType) {
        record(action, deliveryNo, contractNo, operatorNo, "SUCCESS", null, deliveryType);
    }

    /** 拒绝类动作（含触发码；不暴露内部细节）：DB 动作留痕 + 审计 JSONL。 */
    public void denied(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String reasonCode) {
        denied(action, deliveryNo, contractNo, operatorNo, reasonCode, null);
    }

    /** 拒绝类动作（带交付类型；hifi §7.3 denied 字段）。 */
    public void denied(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String reasonCode, final String deliveryType) {
        record(action, deliveryNo, contractNo, operatorNo, "DENIED", reasonCode, deliveryType);
    }

    /** 平台侧失败（hifi §5.4：check 放行后加密/存储失败 → delivery.create FAILURE + 1009S0003）。 */
    public void failure(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String reasonCode, final String deliveryType) {
        record(action, deliveryNo, contractNo, operatorNo, "FAILURE", reasonCode, deliveryType);
    }

    /** 仅 JSONL（用于 create 已随事务落 DB 动作留痕的场景）。 */
    public void jsonOnly(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo) {
        jsonOnly(action, deliveryNo, contractNo, operatorNo, null);
    }

    /** 仅 JSONL（带交付类型）。 */
    public void jsonOnly(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String deliveryType) {
        recordJson(action, deliveryNo, contractNo, operatorNo, "SUCCESS", null, deliveryType);
    }

    private void record(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String result, final String reasonCode,
            final String deliveryType) {
        try {
            repository.insertActionLog(new DeliveryActionLog(null, deliveryNo, contractNo, operatorNo,
                    action, result, reasonCode, null));
        } catch (final RuntimeException e) {
            log.warn("交付动作留痕写入失败: action={}", action);
        }
        recordJson(action, deliveryNo, contractNo, operatorNo, result, reasonCode, deliveryType);
    }

    private void recordJson(final String action, final String deliveryNo, final String contractNo,
            final String operatorNo, final String result, final String reasonCode,
            final String deliveryType) {
        final AuditRecorder recorder = auditRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("contractNo", contractNo == null ? "" : contractNo);
        if (deliveryType != null && !deliveryType.isBlank()) {
            detail.put("deliveryType", deliveryType);
        }
        if (reasonCode != null) {
            detail.put("reasonCode", reasonCode);
        }
        try {
            recorder.record(AuditEvent.of(operatorNo, action, "delivery",
                    deliveryNo == null ? contractNo : deliveryNo, outcomeOf(result), detail));
        } catch (final RuntimeException e) {
            log.warn("交付审计事件写入失败: action={}", action);
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
