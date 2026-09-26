package com.ctds.space.application;

import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.space.domain.ActionResult;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpaceNameNormalizer;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.SubjectAdmission;
import com.ctds.space.domain.SubjectAdmissionPort;
import com.ctds.space.domain.TargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 空间生命周期命令服务（WBS-3.2.3 hifi §3 状态机与事务契约）：
 * 创建入口（校验+归一化后交幂等创建服务）、启用/冻结/恢复（乐观门槛）、解散（二次确认+三写）、
 * 配置变更（Q5-A 最小变更面）。一切动作先双轨权限判定（失败 DENIED 留痕 + 1006C0007），
 * 状态变更留痕 from→to 与操作者（行为 2 规则 6）。
 */
@Service
public class SpaceCommandService {

    /** 名称长度门槛（归一化后判定，hifi §4 ③；原始输入同限——name 列 VARCHAR(128) 存原始输入）。 */
    private static final int NAME_MAX_LENGTH = 128;
    /** 简介长度上限（3.2.2 列宽口径，hifi §8）。 */
    private static final int INTRO_MAX_LENGTH = 512;
    /** 解散理由长度上限（hifi §8）。 */
    private static final int REASON_MAX_LENGTH = 256;
    /** 留痕 from/to 中时间值的业务可读格式。 */
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SpaceRepository repository;
    private final SpaceCreationService creationService;
    private final SpaceAccessGuard guard;
    private final SubjectAdmissionPort admissionPort;
    private final Clock clock;

    public SpaceCommandService(final SpaceRepository repository, final SpaceCreationService creationService,
            final SpaceAccessGuard guard, final SubjectAdmissionPort admissionPort, final Clock clock) {
        this.repository = repository;
        this.creationService = creationService;
        this.guard = guard;
        this.admissionPort = admissionPort;
        this.clock = clock;
    }

    // ==== 创建（行为 1）====

    /**
     * 创建入口：要素校验（1006C0005 逐字段）+ 归一化（移交①）后交幂等创建服务；
     * 未认证 401（平台鉴权口径）。
     */
    public Space create(final CreateSpaceCommand request) {
        final String subject = guard.requireSubject();
        final List<String> problems = new ArrayList<>();
        if (request.name() == null || request.name().isBlank()) {
            problems.add("名称不能为空");
        }
        if (request.sceneType() == null) {
            problems.add("场景类型不能为空");
        }
        if (request.accessMode() == null) {
            problems.add("参与方范围不能为空");
        }
        if (request.visibility() == null) {
            problems.add("可见性不能为空");
        }
        if (request.intro() != null && request.intro().length() > INTRO_MAX_LENGTH) {
            problems.add("空间简介超长（≤" + INTRO_MAX_LENGTH + " 字符）");
        }
        if (!problems.isEmpty()) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_ELEMENT_MISSING, String.join("；", problems));
        }
        // 原始输入同限：name 列存原始输入（VARCHAR(128)），超限纯空白缩减也放不进存储
        if (request.name().length() > NAME_MAX_LENGTH) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_ELEMENT_MISSING,
                    "名称超长（≤" + NAME_MAX_LENGTH + " 字符）");
        }
        final String normalizedName = SpaceNameNormalizer.normalize(request.name());
        if (normalizedName.isEmpty()) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_ELEMENT_MISSING, "名称不能为空");
        }
        if (normalizedName.length() > NAME_MAX_LENGTH) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_ELEMENT_MISSING,
                    "名称超长（归一化后 ≤" + NAME_MAX_LENGTH + " 字符）");
        }
        final CreateSpaceCommand command = new CreateSpaceCommand(subject, request.name(), normalizedName,
                request.sceneType(), request.accessMode(), request.visibility(), request.intro(),
                request.effectiveFrom(), request.effectiveTo());
        return creationService.create(command);
    }

    // ==== 生命周期（行为 2）====

    /** 启用（CREATED→ACTIVE）：启用前提 = 所有者主体仍 ADMITTED（行为 2 规则 2，本卡创建路径要素天然完整）。 */
    public Space enable(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireManagePermission(space, subject, "ENABLE");
        requireAdmitted(space.ownerSubjectNo());
        append(space, SpaceStatus.CREATED, SpaceStatus.ACTIVE, "ENABLE", subject);
        return load(spaceId);
    }

    /** 冻结（ACTIVE→FROZEN）：owner/admin/运营方（行为 2 规则 3）。 */
    public Space freeze(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireManagePermission(space, subject, "FREEZE");
        append(space, SpaceStatus.ACTIVE, SpaceStatus.FROZEN, "FREEZE", subject);
        return load(spaceId);
    }

    /** 恢复（FROZEN→ACTIVE）：冻结可恢复（行为 2 规则 1）。 */
    public Space unfreeze(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireManagePermission(space, subject, "UNFREEZE");
        append(space, SpaceStatus.FROZEN, SpaceStatus.ACTIVE, "UNFREEZE", subject);
        return load(spaceId);
    }

    /**
     * 解散（任一非终态→DISSOLVED，不可逆）：仅所有者或 platform.operator（admin 不可解散）；
     * 二次确认必填（confirmDissolve 显式 true，缺省/null/false 一律 1006C0006，hifi §8）；
     * 同事务三写（状态 DISSOLVED + 名称锁定已锁即跳过 + 策略归档），理由留痕（行为 2 规则 4/6）。
     */
    public Space dissolve(final long spaceId, final Boolean confirmDissolve, final String reason) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        if (!guard.canDissolve(space)) {
            deny(space, subject, "DISSOLVE");
        }
        if (!Boolean.TRUE.equals(confirmDissolve)) {
            throw new SpaceBizException(SpaceErrorCodes.DISSOLVE_CONFIRM_REQUIRED,
                    SpaceErrorCodes.DISSOLVE_CONFIRM_REQUIRED_MESSAGE);
        }
        if (reason != null && reason.length() > REASON_MAX_LENGTH) {
            throw new SpaceBizException(ErrorCodes.PARAM_INVALID, "解散理由超长（≤" + REASON_MAX_LENGTH + " 字符）");
        }
        final SpaceActionLog log = new SpaceActionLog(null, space.id(), TargetType.SPACE, space.id(),
                "DISSOLVE", subject, space.status().name(), SpaceStatus.DISSOLVED.name(), ActionResult.SUCCESS,
                reason, LocalDateTime.now(clock));
        repository.dissolve(space.id(), space.status(), space.normalizedName(), log);
        return load(spaceId);
    }

    // ==== 配置变更（Q5-A：仅简介/生效期可改）====

    /**
     * 配置变更：ACTIVE/FROZEN 可改，未启用与终态拒绝（1006C0002 含"未启用不可变更"，hifi §2）；
     * 不可变更字段（名称/场景/范围/可见性）在接口层即拒（仅接受白名单字段，多余字段 400，hifi §8/T11）；
     * 逐字段留痕"从何值→到何值"（剧本 S2 配置留痕锚点）。
     */
    public Space update(final long spaceId, final SpaceUpdateRequest request) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        requireManagePermission(space, subject, "UPDATE");
        if (space.status() == SpaceStatus.CREATED || space.status() == SpaceStatus.DISSOLVED) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
        if (request.intro() != null && request.intro().length() > INTRO_MAX_LENGTH) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_ELEMENT_MISSING,
                    "空间简介超长（≤" + INTRO_MAX_LENGTH + " 字符）");
        }
        final LocalDateTime now = LocalDateTime.now(clock);
        final List<SpaceActionLog> logs = new ArrayList<>();
        String intro = null;
        LocalDateTime effectiveFrom = null;
        LocalDateTime effectiveTo = null;
        if (request.intro() != null && !request.intro().equals(space.intro())) {
            intro = request.intro();
            logs.add(updateLog(space, subject, "intro",
                    space.intro() == null ? "（未设置）" : space.intro(), intro, now));
        }
        if (request.effectiveFrom() != null && !request.effectiveFrom().equals(space.effectiveFrom())) {
            effectiveFrom = request.effectiveFrom();
            logs.add(updateLog(space, subject, "effectiveFrom", auditTime(space.effectiveFrom()),
                    auditTime(effectiveFrom), now));
        }
        if (request.effectiveTo() != null && !request.effectiveTo().equals(space.effectiveTo())) {
            effectiveTo = request.effectiveTo();
            logs.add(updateLog(space, subject, "effectiveTo", auditTime(space.effectiveTo()),
                    auditTime(effectiveTo), now));
        }
        repository.updateFields(space.id(), new SpaceRepository.SpaceUpdate(intro, effectiveFrom, effectiveTo),
                logs);
        return load(spaceId);
    }

    /** 配置变更请求载荷（null = 不变更该项；由接口层 DTO 白名单映射）。 */
    public record SpaceUpdateRequest(String intro, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
    }

    // ==== 内部 ====

    /** 资格复查（启用前提，行为 2 规则 2）：三态严格分离，与创建同口径（防枚举/不可用不互相冒充）。 */
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

    private Space load(final long spaceId) {
        return repository.findById(spaceId)
                .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND,
                        SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE));
    }

    private void requireManagePermission(final Space space, final String subject, final String action) {
        if (!guard.canManage(space, repository.findActiveMembers(space.id()))) {
            deny(space, subject, action);
        }
    }

    /** 拒绝留痕（行为 6 规则 5 越权拒绝留痕）+ 1006C0007（T10 断言口径）。 */
    private void deny(final Space space, final String subject, final String action) {
        repository.insertLog(new SpaceActionLog(null, space.id(), TargetType.SPACE, space.id(), action,
                subject, space.status().name(), null, ActionResult.DENIED,
                SpaceErrorCodes.ACCESS_DENIED_LOG_REASON, LocalDateTime.now(clock)));
        throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
    }

    /** 状态流转（乐观门槛在仓储实现：WHERE status=from，0 行 → 1006C0002）+ 留痕 from→to（行为 2 规则 6）。 */
    private void append(final Space space, final SpaceStatus fromStatus, final SpaceStatus toStatus,
            final String action, final String subject) {
        final SpaceActionLog log = new SpaceActionLog(null, space.id(), TargetType.SPACE, space.id(),
                action, subject, fromStatus.name(), toStatus.name(), ActionResult.SUCCESS, null,
                LocalDateTime.now(clock));
        repository.appendTransition(space.id(), fromStatus, toStatus, log);
    }

    private SpaceActionLog updateLog(final Space space, final String subject, final String field,
            final String fromValue, final String toValue, final LocalDateTime now) {
        return new SpaceActionLog(null, space.id(), TargetType.SPACE, space.id(), "UPDATE", subject,
                field + ":" + fromValue, field + ":" + toValue, ActionResult.SUCCESS, null, now);
    }

    private static String auditTime(final LocalDateTime value) {
        return value == null ? "（未设置）" : AUDIT_TIME.format(value);
    }
}
