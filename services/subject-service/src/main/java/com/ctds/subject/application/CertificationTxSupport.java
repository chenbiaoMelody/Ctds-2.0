package com.ctds.subject.application;

import com.ctds.subject.domain.CertMaterial;
import com.ctds.subject.domain.CertVerificationLog;
import com.ctds.subject.domain.CertificationRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.TriggerRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证域事务协作组件（AUD-04，任务卡卡 4，对应债务 DB-04）：同一业务动作的多次仓储写入
 * 原子落库——任一写失败则整组回滚，不留"档案有留痕/材料、状态却未流转"的半成品数据。
 * 事务边界只包住写序列：渠道调用与"写完后的业务拒绝信号"（如 OCR 无法识别）留在事务外，
 * 业务拒绝时已完成的落库照常保留（无法识别材料留存提示重传 / 渠道异常留痕兜底均不受影响）。
 * 仓储各写方法自身的事务（REQUIRED）加入本组件开启的事务。
 */
@Service
public class CertificationTxSupport {

    private final CertificationRepository certificationRepository;
    private final SubjectStatusService statusService;

    public CertificationTxSupport(final CertificationRepository certificationRepository,
            final SubjectStatusService statusService) {
        this.certificationRepository = certificationRepository;
        this.statusService = statusService;
    }

    /** 材料落库 + 渠道留痕：同一动作原子落库（证照上传、政务提交不通过路径）。 */
    @Transactional
    public CertMaterial saveMaterialWithLog(final CertMaterial material, final CertVerificationLog log) {
        final CertMaterial saved = certificationRepository.replaceMaterial(material);
        certificationRepository.appendVerification(log);
        return saved;
    }

    /** 材料落库 + 渠道留痕 + 状态流转：同一动作原子落库（政务提交通过路径）。 */
    @Transactional
    public CertMaterial saveMaterialWithLogAndTransition(final CertMaterial material, final CertVerificationLog log,
            final long subjectId, final SubjectStatus fromStatus, final SubjectStatus toStatus,
            final TriggerRole triggerRole, final String operator, final String remark) {
        final CertMaterial saved = saveMaterialWithLog(material, log);
        statusService.transition(subjectId, fromStatus, toStatus, triggerRole, operator, remark);
        return saved;
    }

    /** 核验留痕 + 状态流转：同一动作原子落库（法人核验通过路径；流转门槛拒绝 → 留痕一并回滚）。 */
    @Transactional
    public void recordVerificationAndTransition(final CertVerificationLog log, final long subjectId,
            final SubjectStatus fromStatus, final SubjectStatus toStatus, final TriggerRole triggerRole,
            final String operator, final String remark) {
        certificationRepository.appendVerification(log);
        statusService.transition(subjectId, fromStatus, toStatus, triggerRole, operator, remark);
    }
}
