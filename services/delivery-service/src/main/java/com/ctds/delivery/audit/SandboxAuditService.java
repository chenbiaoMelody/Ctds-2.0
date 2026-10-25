package com.ctds.delivery.audit;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditOutcome;
import com.ctds.common.logging.AuditRecorder;
import com.ctds.delivery.domain.SandboxActionLog;
import com.ctds.delivery.domain.SandboxRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 沙箱留痕单点（hifi §8）：域动作落 {@code sandbox_action_log} + common-logging 审计 JSONL。
 * <b>禁止</b>写入：文件内容与原文、宿主机路径、容器 ID/名、镜像名、命令 argv、stdout/stderr、
 * 密钥材料与令牌。留痕失败不改变业务结论。
 */
@Service
public class SandboxAuditService {

    private static final Logger log = LoggerFactory.getLogger(SandboxAuditService.class);

    private final SandboxRepository repository;
    private final ObjectProvider<AuditRecorder> auditRecorder;

    public SandboxAuditService(final SandboxRepository repository,
            final ObjectProvider<AuditRecorder> auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    /** 成功类动作（create/close/expire/reconcile/read/job/governance.read）。 */
    public void success(final String action, final String sandboxNo, final String jobNo,
            final String contractNo, final String operatorNo) {
        record(action, sandboxNo, jobNo, contractNo, operatorNo, "SUCCESS", null);
    }

    /** 拒绝类动作（403/404/409/429/400）：带触发码，不暴露内部细节。 */
    public void denied(final String action, final String sandboxNo, final String jobNo,
            final String contractNo, final String operatorNo, final String reasonCode) {
        record(action, sandboxNo, jobNo, contractNo, operatorNo, "DENIED", reasonCode);
    }

    /** 平台侧失败（运行时异常 / 作业失败）。 */
    public void failure(final String action, final String sandboxNo, final String jobNo,
            final String contractNo, final String operatorNo, final String reasonCode) {
        record(action, sandboxNo, jobNo, contractNo, operatorNo, "FAILURE", reasonCode);
    }

    /** 仅 JSONL（用于 DB 动作留痕已随事务落库的场景，避免重复行）。 */
    public void jsonOnly(final String action, final String sandboxNo, final String jobNo,
            final String contractNo, final String operatorNo) {
        recordJson(action, sandboxNo, jobNo, contractNo, operatorNo, "SUCCESS", null);
    }

    /** 结果物维度成功类动作（结果物标识入 result_name 列与审计 detail；WBS-3.5.5）。 */
    public void successResult(final String action, final String sandboxNo, final String resultName,
            final String contractNo, final String operatorNo) {
        recordResult(action, sandboxNo, resultName, contractNo, operatorNo, "SUCCESS", null);
    }

    /** 结果物维度拒绝（404/409/403/400/透传码；带结果物标识）。 */
    public void deniedResult(final String action, final String sandboxNo, final String resultName,
            final String contractNo, final String operatorNo, final String reasonCode) {
        recordResult(action, sandboxNo, resultName, contractNo, operatorNo, "DENIED", reasonCode);
    }

    /** 结果物维度平台侧失败（导出链加密 / 存储失败等）。 */
    public void failureResult(final String action, final String sandboxNo, final String resultName,
            final String contractNo, final String operatorNo, final String reasonCode) {
        recordResult(action, sandboxNo, resultName, contractNo, operatorNo, "FAILURE", reasonCode);
    }

    /** 结果物维度仅 JSONL（DB 动作留痕已随事务落库的场景）。 */
    public void jsonOnlyResult(final String action, final String sandboxNo,
            final String resultName, final String contractNo, final String operatorNo) {
        recordJsonResult(action, sandboxNo, resultName, contractNo, operatorNo, "SUCCESS", null);
    }

    private void record(final String action, final String sandboxNo, final String jobNo,
            final String contractNo, final String operatorNo, final String result,
            final String reasonCode) {
        try {
            repository.insertActionLog(new SandboxActionLog(null, sandboxNo, jobNo, contractNo,
                    operatorNo, action, result, reasonCode, null, null));
        } catch (final RuntimeException e) {
            log.warn("沙箱动作留痕写入失败: action={}", action);
        }
        recordJson(action, sandboxNo, jobNo, contractNo, operatorNo, result, reasonCode);
    }

    private void recordResult(final String action, final String sandboxNo, final String resultName,
            final String contractNo, final String operatorNo, final String result,
            final String reasonCode) {
        try {
            repository.insertActionLog(new SandboxActionLog(null, sandboxNo, null, contractNo,
                    operatorNo, action, result, reasonCode, resultName, null));
        } catch (final RuntimeException e) {
            log.warn("沙箱动作留痕写入失败: action={}", action);
        }
        recordJsonResult(action, sandboxNo, resultName, contractNo, operatorNo, result,
                reasonCode);
    }

    private void recordJson(final String action, final String sandboxNo, final String jobNo,
            final String contractNo, final String operatorNo, final String result,
            final String reasonCode) {
        final AuditRecorder recorder = auditRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("contractNo", contractNo == null ? "" : contractNo);
        if (jobNo != null && !jobNo.isBlank()) {
            detail.put("jobNo", jobNo);
        }
        if (reasonCode != null) {
            detail.put("reasonCode", reasonCode);
        }
        recordJson(action, sandboxNo, contractNo, operatorNo, result, detail);
    }

    private void recordJsonResult(final String action, final String sandboxNo,
            final String resultName, final String contractNo, final String operatorNo,
            final String result, final String reasonCode) {
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("contractNo", contractNo == null ? "" : contractNo);
        if (resultName != null && !resultName.isBlank()) {
            detail.put("resultName", resultName);
        }
        if (reasonCode != null) {
            detail.put("reasonCode", reasonCode);
        }
        recordJson(action, sandboxNo, contractNo, operatorNo, result, detail);
    }

    private void recordJson(final String action, final String sandboxNo, final String contractNo,
            final String operatorNo, final String result, final Map<String, String> detail) {
        final AuditRecorder recorder = auditRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        final String target = sandboxNo == null ? contractNo : sandboxNo;
        try {
            recorder.record(AuditEvent.of(operatorNo, action, "sandbox", target, outcomeOf(result),
                    detail));
        } catch (final RuntimeException e) {
            log.warn("沙箱审计事件写入失败: action={}", action);
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
