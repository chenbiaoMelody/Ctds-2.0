package com.ctds.subject.application;

import com.ctds.subject.domain.StatusTransition;
import com.ctds.subject.domain.SubjectRepository;
import com.ctds.subject.domain.SubjectStatus;
import com.ctds.subject.domain.TriggerRole;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 主体状态流转服务（hifi B7）：一切状态流转的唯一入口，强制落留痕（前状态/后状态/触发方/时间 +
 * 操作人与备注，规格 C-1.1 行为 4 第 2 条）。
 * 3.1.3（认证自动流转）与 3.1.5（审核流转）复用本服务执行流转，不得直连仓储写状态。
 * Clock 注入（WBS-3.1.3 评审修复：统一时间源；仓储层同步更新 subject.status 列并带 from_status 乐观门槛）。
 */
@Service
public class SubjectStatusService {

    /** 撤销流转备注（服务端常量）；"最新留痕 = 本备注"即撤销标记契约，仓储与查询共同遵守。 */
    public static final String CANCEL_REMARK = "申请人撤销，待重新提交";
    /** 驳回后重新申请流转备注。 */
    public static final String RESUBMIT_AFTER_REJECT_REMARK = "驳回后重新申请";
    /** 撤销后重新提交流转备注。 */
    public static final String RESUBMIT_AFTER_CANCEL_REMARK = "撤销后重新提交";

    private final SubjectRepository repository;
    private final Clock clock;

    public SubjectStatusService(final SubjectRepository repository, final Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 执行一次状态流转：更新主体状态列（from_status 乐观门槛，并发重复流转 0 行即拒）并落留痕（同事务）。 */
    public void transition(final long subjectId, final SubjectStatus fromStatus, final SubjectStatus toStatus,
            final TriggerRole triggerRole, final String operator, final String remark) {
        repository.appendTransition(subjectId,
                new StatusTransition(fromStatus, toStatus, triggerRole, operator, remark,
                        LocalDateTime.now(clock)));
    }
}
