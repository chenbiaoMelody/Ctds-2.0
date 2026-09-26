package com.ctds.space.application;

import com.ctds.common.idempotency.Idempotent;
import com.ctds.space.domain.ActionResult;
import com.ctds.space.domain.MemberRole;
import com.ctds.space.domain.MemberStatus;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceMember;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
import com.ctds.space.domain.TargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 空间创建服务（幂等边界；WBS-3.2.3 hifi §5）。独立于 SpaceCommandService 的原因：
 * 幂等键 = 所有者 + 归一化名（hifi 定稿），归一化与要素校验在命令服务先行，跨 Bean 调用
 * 使幂等切面经代理拦截（同类自调用会被 AOP 绕过）。重复提交返回首次结果（ADR-007 模式 B，
 * 演示/单测 memory 模式）。
 */
@Service
public class SpaceCreationService {

    private final SpaceRepository repository;
    private final SubjectAdmissionPort admissionPort;
    private final Clock clock;

    public SpaceCreationService(final SpaceRepository repository, final SubjectAdmissionPort admissionPort,
            final Clock clock) {
        this.repository = repository;
        this.admissionPort = admissionPort;
        this.clock = clock;
    }

    /**
     * 创建（行为 1 全部规则）：资格门槛（防枚举同形）→ 名称锁定/同主同名判重（DB 唯一键兜底）→
     * 初始 CREATED + 创建者 owner 成员行 + 创建留痕同事务落库。
     */
    @Idempotent(key = "#cmd.ownerSubjectNo + ':' + #cmd.normalizedName")
    public Space create(final CreateSpaceCommand cmd) {
        requireAdmitted(cmd.ownerSubjectNo());
        if (repository.existsInNameLock(cmd.normalizedName())) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_NAME_TAKEN,
                    SpaceErrorCodes.SPACE_NAME_LOCKED_MESSAGE);
        }
        if (repository.existsByOwnerAndNormalizedName(cmd.ownerSubjectNo(), cmd.normalizedName())) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_NAME_TAKEN,
                    SpaceErrorCodes.SPACE_NAME_TAKEN_MESSAGE);
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final Space space = new Space(null, cmd.name(), cmd.normalizedName(), cmd.sceneType(), cmd.accessMode(),
                cmd.visibility(), cmd.intro(), cmd.effectiveFrom(), cmd.effectiveTo(), cmd.ownerSubjectNo(),
                SpaceStatus.CREATED, now, now);
        // 行为 1 规则 1：创建者自动成为所有者（uk_active_owner 硬兜底唯一所有者）
        final SpaceMember owner = new SpaceMember(null, null, cmd.ownerSubjectNo(), MemberRole.OWNER,
                MemberStatus.ACTIVE, now, null, now, now);
        final SpaceActionLog log = new SpaceActionLog(null, null, TargetType.SPACE, null, "CREATE",
                cmd.ownerSubjectNo(), null, SpaceStatus.CREATED.name(), ActionResult.SUCCESS, null, now);
        final long id = repository.create(space, owner, log);
        return repository.findById(id).orElseThrow();
    }

    /** 资格门槛（行为 1 规则 1；启用前提复查同款）：三态严格分离，防枚举与不可用不互相冒充。 */
    private void requireAdmitted(final String subjectNo) {
        final SubjectAdmission admission = admissionPort.check(subjectNo);
        if (admission == SubjectAdmission.NOT_ADMITTED) {
            throw new SpaceBizException(SpaceErrorCodes.ADMISSION_REQUIRED,
                    SpaceErrorCodes.ADMISSION_REQUIRED_MESSAGE);
        }
        if (admission == SubjectAdmission.UNAVAILABLE) {
            throw new SpaceBizException(SpaceErrorCodes.SUBJECT_SERVICE_UNAVAILABLE,
                    SpaceErrorCodes.SUBJECT_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }
}
