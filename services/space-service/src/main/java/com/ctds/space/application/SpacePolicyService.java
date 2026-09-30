package com.ctds.space.application;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.space.domain.ActionResult;
import com.ctds.space.domain.EffectivePolicyResolver;
import com.ctds.space.domain.EffectivePolicyResolver.EffectivePolicyRow;
import com.ctds.space.domain.PolicyCatalog;
import com.ctds.space.domain.PolicyCatalog.EntryDefinition;
import com.ctds.space.domain.PolicyScope;
import com.ctds.space.domain.PolicyStatus;
import com.ctds.space.domain.Space;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceBizException;
import com.ctds.space.domain.SpaceErrorCodes;
import com.ctds.space.domain.SpacePermissions;
import com.ctds.space.domain.SpacePolicy;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.SpaceStatus;
import com.ctds.space.domain.TargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 空间策略继承与覆盖服务（WBS-3.2.5 hifi §4/§5，规格行为 7 全部规则）：
 * 平台级条目治理（创建/值与红线变更/列表，platform.policy 权限点）、空间级覆盖提交
 * （space.admin；红线放宽拒 1006C0013 + 拒绝留痕——§6.5 硬约束代码强制）、有效策略视图
 * （取严解析 + 来源三态标注；冻结期视图保留、解散归档可查不可变）。
 * 覆盖留痕 target = 被覆盖的平台条目（按平台条目 id 归组审计；from=当前生效值 → to=提交值）。
 */
@Service
public class SpacePolicyService {

    private final SpaceRepository repository;
    private final SpaceAccessGuard guard;
    private final SpaceGovernanceVisitLogger visitLogger;
    private final Clock clock;

    public SpacePolicyService(final SpaceRepository repository, final SpaceAccessGuard guard,
            final SpaceGovernanceVisitLogger visitLogger, final Clock clock) {
        this.repository = repository;
        this.guard = guard;
        this.visitLogger = visitLogger;
        this.clock = clock;
    }

    // ==== 端点 1：平台条目创建 ====

    /** 平台条目创建（platform.policy；键须在目录、值须在值域、同键至多一条 ACTIVE——0012）。 */
    public SpacePolicy createPlatformEntry(final String entryKey, final String entryValue,
            final boolean redline) {
        requirePlatformPermission();
        final EntryDefinition definition = requireDefinition(entryKey);
        requireValueInDomain(definition, entryValue);
        if (repository.findPlatformEntryByKey(entryKey).isPresent()) {
            throw policyEntryInvalid();
        }
        final long id = repository.insertPlatformEntry(
                new SpacePolicy(null, PolicyScope.PLATFORM, null, null, entryKey, entryValue,
                        redline, PolicyStatus.ACTIVE, null, null),
                platformLog("POLICY_DEFINE", null, null, entryValue, ActionResult.SUCCESS, null));
        return repository.findPolicyById(id).orElseThrow(SpacePolicyService::policyEntryRequired);
    }

    // ==== 端点 2：平台条目值与红线变更 ====

    /**
     * 平台条目变更（platform.policy；键不可变更；至少一项变更；0 行乐观门槛 → 0014）。
     * 平台是红线的定义者：放宽自身条目值允许（诚实语义登记，hifi §4）——红线约束的是空间覆盖。
     */
    public SpacePolicy updatePlatformEntry(final long entryId, final String entryValue,
            final Boolean redline) {
        requirePlatformPermission();
        if (entryValue == null && redline == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "至少提供一项变更（entryValue 或 redline）");
        }
        final SpacePolicy entry = repository.findPolicyById(entryId)
                .filter(candidate -> candidate.scope() == PolicyScope.PLATFORM
                        && candidate.status() == PolicyStatus.ACTIVE)
                .orElseThrow(SpacePolicyService::policyEntryRequired);
        if (entryValue != null) {
            requireValueInDomain(PolicyCatalog.find(entry.entryKey()).orElseThrow(), entryValue);
        }
        final String toValue = entryValue != null ? entryValue : entry.entryValue();
        final boolean toRedline = redline != null ? redline : entry.redline();
        if (toValue.equals(entry.entryValue()) && toRedline == entry.redline()) {
            return entry;
        }
        final String reasonNote = toRedline != entry.redline()
                ? "红线标记由 " + (entry.redline() ? 1 : 0) + " 变更为 " + (toRedline ? 1 : 0) : null;
        repository.updatePlatformEntry(entryId, toValue, toRedline,
                platformLog("POLICY_DEFINE", entryId, entry.entryValue(), toValue,
                        ActionResult.SUCCESS, reasonNote));
        return repository.findPolicyById(entryId).orElseThrow(SpacePolicyService::policyEntryRequired);
    }

    // ==== 端点 3：平台条目列表（治理面只读）====

    public PageResult<SpacePolicy> listPlatformEntries(final PageQuery page) {
        requirePlatformPermission();
        // 读后写：运营档治理查看留痕（DB-29，仅运营方角色触发；平台面沿 POLICY_DEFINE 留痕形态）
        final PageResult<SpacePolicy> entries = repository.searchPlatformEntries(page);
        visitLogger.recordPlatformPolicyView();
        return entries;
    }

    // ==== 端点 4：空间覆盖提交 ====

    /**
     * 空间覆盖提交（space.admin + 空间 ACTIVE；hifi §4 写门链）：目录与值校验（0012）→ 平台条目
     * 定位（0014 统一文案防探测）→ 同值幂等无操作无留痕 → 红线放宽拒（0013 + POLICY_OVERRIDE_REJECTED
     * 留痕——§6.5 代码强制）→ 落库（首覆盖 INSERT 显式指向平台条目 / 再覆盖 UPDATE）+ POLICY_OVERRIDE
     * 留痕（from=当前生效值 → to=提交值）。响应回显提交后该键生效结果与来源标注。
     */
    public EffectivePolicyRow submitOverride(final long spaceId, final String entryKey,
            final String entryValue) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        if (!guard.canManage(space, repository.findActiveMembers(spaceId))) {
            denyOverride(space, subject);
        }
        if (space.status() != SpaceStatus.ACTIVE) {
            throw new SpaceBizException(SpaceErrorCodes.SPACE_STATUS_GATE,
                    SpaceErrorCodes.SPACE_STATUS_GATE_MESSAGE);
        }
        final EntryDefinition definition = requireDefinition(entryKey);
        requireValueInDomain(definition, entryValue);
        final SpacePolicy platformEntry = repository.findPlatformEntryByKey(entryKey)
                .orElseThrow(SpacePolicyService::policyEntryRequired);
        final EffectivePolicyRow before = effectiveRow(spaceId, platformEntry);
        if (before.effectiveValue().equals(entryValue)) {
            // 同值幂等：无操作无留痕（沿 3.2.4 E7"同角色重复设定"先例）
            return before;
        }
        if (platformEntry.redline() && PolicyCatalog.isLoosening(definition, platformEntry.entryValue(),
                entryValue)) {
            // 红线放宽：拒绝 + POLICY_OVERRIDE_REJECTED 留痕（拒绝同样留痕，规则 4）
            repository.insertLog(policyLog(spaceId, platformEntry.id(), "POLICY_OVERRIDE_REJECTED",
                    subject, before.effectiveValue(), entryValue, ActionResult.DENIED,
                    SpaceErrorCodes.REDLINE_LOOSENING_REJECTED_MESSAGE));
            throw new SpaceBizException(SpaceErrorCodes.REDLINE_LOOSENING_REJECTED,
                    SpaceErrorCodes.REDLINE_LOOSENING_REJECTED_MESSAGE);
        }
        final SpaceActionLog overrideLog = policyLog(spaceId, platformEntry.id(), "POLICY_OVERRIDE",
                subject, before.effectiveValue(), entryValue, ActionResult.SUCCESS, null);
        final SpacePolicy existing = repository.findSpaceEntry(spaceId, entryKey).orElse(null);
        if (existing != null) {
            repository.updateSpaceOverrideValue(existing.id(), spaceId, entryValue, overrideLog);
        } else {
            repository.insertSpaceOverride(new SpacePolicy(null, PolicyScope.SPACE, spaceId,
                    platformEntry.id(), entryKey, entryValue, false, PolicyStatus.ACTIVE, null, null),
                    overrideLog);
        }
        return effectiveRow(spaceId, platformEntry);
    }

    // ==== 端点 5：有效策略视图 ====

    /**
     * 有效策略视图（取严解析 + 来源三态标注）：空间 ACTIVE/FROZEN → space.member 或 platform.operator；
     * DISSOLVED → 仅 owner 或 platform.operator（解散后不再对外提供访问，仅治理/审计可查——剧本 S3-4/5）。
     * 冻结期视图保留（已配置覆盖不变，剧本 S3-3）；归档条目参与解析（终态值，可查不可变）。
     */
    public List<EffectivePolicyRow> effectivePolicy(final long spaceId) {
        final String subject = guard.requireSubject();
        final Space space = load(spaceId);
        final boolean allowed;
        if (space.status() == SpaceStatus.DISSOLVED) {
            allowed = guard.isOwner(space) || guard.isPlatformOperator();
        } else {
            allowed = guard.canActAsMember(space, repository.findActiveMembers(spaceId))
                    || guard.isPlatformOperator();
        }
        if (!allowed) {
            denyPolicyView(space, subject);
        }
        // 读后写：运营档治理查看留痕（DB-29，仅运营方角色触发；归档面同样可审——剧本 S3-6）
        final List<EffectivePolicyRow> rows = EffectivePolicyResolver.resolve(repository.findPlatformEntries(),
                repository.findSpaceEntries(spaceId));
        visitLogger.recordSpaceView(spaceId);
        return rows;
    }

    // ==== 内部 ====

    /** 单键解析（复用全量解析器：单平台条目入参即只解析该键）。 */
    private EffectivePolicyRow effectiveRow(final long spaceId, final SpacePolicy platformEntry) {
        return EffectivePolicyResolver.resolve(List.of(platformEntry),
                repository.findSpaceEntries(spaceId)).get(0);
    }

    private Space load(final long spaceId) {
        return repository.findById(spaceId)
                .orElseThrow(() -> new SpaceBizException(SpaceErrorCodes.SPACE_NOT_FOUND,
                        SpaceErrorCodes.SPACE_NOT_FOUND_MESSAGE));
    }

    private void requirePlatformPermission() {
        final String subject = guard.requireSubject();
        if (!guard.hasPlatformPermission(SpacePermissions.PLATFORM_POLICY)) {
            // 平台面越权：拒绝同样留痕（规则 4；space_id=NULL 仅限平台级策略动作）
            repository.insertLog(new SpaceActionLog(null, null, TargetType.POLICY, null,
                    "POLICY_DEFINE", subject, null, null, ActionResult.DENIED,
                    SpaceErrorCodes.ACCESS_DENIED_LOG_REASON, LocalDateTime.now(clock)));
            throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                    SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
        }
    }

    private EntryDefinition requireDefinition(final String entryKey) {
        return PolicyCatalog.find(entryKey).orElseThrow(SpacePolicyService::policyEntryInvalid);
    }

    private static void requireValueInDomain(final EntryDefinition definition, final String value) {
        if (value == null || !definition.containsValue(value)) {
            throw policyEntryInvalid();
        }
    }

    private void denyOverride(final Space space, final String subject) {
        repository.insertLog(policyLog(space.id(), null, "POLICY_OVERRIDE", subject, null, null,
                ActionResult.DENIED, SpaceErrorCodes.ACCESS_DENIED_LOG_REASON));
        throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
    }

    private void denyPolicyView(final Space space, final String subject) {
        repository.insertLog(new SpaceActionLog(null, space.id(), TargetType.SPACE, space.id(),
                "ACCESS_DENIED", subject, null, null, ActionResult.DENIED,
                SpaceErrorCodes.POLICY_VIEW_DENIED_LOG_REASON, LocalDateTime.now(clock)));
        throw new SpaceBizException(SpaceErrorCodes.SPACE_ACCESS_DENIED,
                SpaceErrorCodes.SPACE_ACCESS_DENIED_MESSAGE);
    }

    /** 策略留痕（target = 被覆盖的平台条目 id；探测被拒且无实体可指时为 null——V1 契约口径）。 */
    private SpaceActionLog policyLog(final long spaceId, final Long targetId, final String action,
            final String operator, final String fromValue, final String toValue,
            final ActionResult result, final String reason) {
        return new SpaceActionLog(null, spaceId, TargetType.POLICY, targetId, action, operator,
                fromValue, toValue, result, reason, LocalDateTime.now(clock));
    }

    private SpaceActionLog platformLog(final String action, final Long targetId, final String fromValue,
            final String toValue, final ActionResult result, final String reason) {
        return new SpaceActionLog(null, null, TargetType.POLICY, targetId, action,
                guard.requireSubject(), fromValue, toValue, result, reason, LocalDateTime.now(clock));
    }

    private static SpaceBizException policyEntryInvalid() {
        return new SpaceBizException(SpaceErrorCodes.POLICY_ENTRY_INVALID,
                SpaceErrorCodes.POLICY_ENTRY_INVALID_MESSAGE);
    }

    private static SpaceBizException policyEntryRequired() {
        return new SpaceBizException(SpaceErrorCodes.POLICY_ENTRY_REQUIRED,
                SpaceErrorCodes.POLICY_ENTRY_REQUIRED_MESSAGE);
    }
}
