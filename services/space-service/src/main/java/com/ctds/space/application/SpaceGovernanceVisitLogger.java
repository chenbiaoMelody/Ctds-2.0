package com.ctds.space.application;

import com.ctds.space.domain.ActionResult;
import com.ctds.space.domain.SpaceActionLog;
import com.ctds.space.domain.SpaceRepository;
import com.ctds.space.domain.TargetType;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 运营档治理查看留痕（DB-29，Q1~Q5 全 A）：仅 platform.operator 角色**成功**访问治理读端点时
 * 写一条 {@code GOVERNANCE_VIEW} visit 留痕——"谁" = 实际登录主体编号（Q3-A），四要素齐备、
 * 不含敏感原文（from/to/reason 均空）；成员/所有者常规访问不写（Q4-A：规格"治理例外唯一且留痕"，
 * 留痕对象是例外动作本身，非业务读行为）。
 *
 * <p>时序口径（卡 §二）：调用方**先取数、后记录**——当前查询结果不含本次 visit 行，
 * 再次查询可见（留痕分页自引用钉死，防"查一次涨一行"的翻页漂移）。</p>
 */
@Component
public class SpaceGovernanceVisitLogger {

    /** 治理查看动作码（V4 迁移注释登记；代码字面量单点定义，零漂移）。 */
    static final String ACTION = "GOVERNANCE_VIEW";

    private final SpaceRepository repository;
    private final SpaceAccessGuard guard;
    private final Clock clock;

    public SpaceGovernanceVisitLogger(final SpaceRepository repository, final SpaceAccessGuard guard,
            final Clock clock) {
        this.repository = repository;
        this.guard = guard;
        this.clock = clock;
    }

    /** 空间检索列表（空间面、无特定目标：target_id/space_id 均空）。 */
    public void recordSpaceListView() {
        record(null, TargetType.SPACE, null);
    }

    /** 空间面查看（详情/留痕/有效策略/成员/准入单：目标=空间本身）。 */
    public void recordSpaceView(final long spaceId) {
        record(spaceId, TargetType.SPACE, spaceId);
    }

    /** 平台策略条目面查看（platform-policies 治理列表；沿 POLICY_DEFINE 平台留痕形态 space_id=NULL）。 */
    public void recordPlatformPolicyView() {
        record(null, TargetType.POLICY, null);
    }

    private void record(final Long spaceId, final TargetType targetType, final Long targetId) {
        if (!guard.isPlatformOperator()) {
            return;
        }
        repository.insertLog(new SpaceActionLog(null, spaceId, targetType, targetId, ACTION,
                guard.requireSubject(), null, null, ActionResult.SUCCESS, null,
                LocalDateTime.now(clock)));
    }
}
