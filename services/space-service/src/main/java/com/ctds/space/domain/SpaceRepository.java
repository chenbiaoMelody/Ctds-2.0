package com.ctds.space.domain;

import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 空间域仓储接口（WBS-3.2.3 hifi §9 四层契约；实现 = infrastructure.SpaceJdbcRepository）。
 * 一切状态变更同事务落留痕（subject appendTransition 先例）；状态门槛 = UPDATE 带 from_status
 * 前置条件的乐观并发控制（0 行即拒，不先查后改——TOCTOU 防护，3.1.3 教训）。
 */
public interface SpaceRepository {

    // ==== 查询 ====

    /** 按技术主键取空间。 */
    Optional<Space> findById(long id);

    /** 名称是否已被历史解散空间锁定（space_name_lock 全平台口径，行为 2 规则 4）。 */
    boolean existsInNameLock(String normalizedName);

    /** 同一所有者是否已有同名（归一化）空间——DB 兜底为 uk_owner_norm_name。 */
    boolean existsByOwnerAndNormalizedName(String ownerSubjectNo, String normalizedName);

    /**
     * 检索列表（行为 6 规则 3 可见性承载 + hifi §8 终态不出现在可检索面）：
     * operatorView=true 全量可见性（platform.operator），false 仅 PUBLIC；两类均不含 DISSOLVED。
     * keywordPrefix 非空时按归一化名称前缀匹配（调用方传已归一化前缀；语义登记：检索按名称前缀）。
     */
    PageResult<Space> search(boolean operatorView, String normalizedNamePrefix, PageQuery page);

    /** 空间活跃成员（status=ACTIVE；读面成员构成与权限判定共用）。 */
    List<SpaceMember> findActiveMembers(long spaceId);

    // ==== 命令（实现须 @Transactional）====

    /**
     * 创建：空间行 + 创建者 owner 成员行 + 创建留痕同事务落库（行为 1 规则 1/4）；
     * uk_owner_norm_name / space_name_lock 唯一约束兜底并发窗口（DuplicateKeyException → 1006C0003）。
     *
     * @return 新空间的技术主键（写入后按 uk 回查，subject 先例）
     */
    long create(Space space, SpaceMember ownerMember, SpaceActionLog log);

    /** 状态流转（启用/冻结/恢复）：乐观门槛更新状态列 + 留痕（from→to）同事务；0 行 → 1006C0002。 */
    void appendTransition(long spaceId, SpaceStatus fromStatus, SpaceStatus toStatus, SpaceActionLog log);

    /**
     * 解散三写（3.2.2 hifi §2 契约兑现，WBS-3.2.3 hifi §3）：① 状态乐观门槛更新（fromStatus 精确匹配，
     * 0 行 → 1006C0002）；② space_name_lock 写入（INSERT...SELECT...WHERE NOT EXISTS，已锁定即跳过——Q6-A，
     * 锁定目标已达成不阻断治理动作）；③ 该空间策略条目全部 ARCHIVED（行为 7 规则 5）。
     * 任一失败整体回滚；留痕行随同事务写入（from→to，reason=解散理由）。
     */
    void dissolve(long spaceId, SpaceStatus fromStatus, String normalizedName, SpaceActionLog log);

    /**
     * 配置变更（Q5-A 最小变更面：仅简介/生效期；null = 不变更该项）：动态 SET + 逐字段留痕
     * （每个实际变更字段一行 UPDATE 留痕，from→to 为变更前后值）；0 行更新 → 1006C0002。
     */
    void updateFields(long spaceId, SpaceUpdate update, List<SpaceActionLog> logs);

    /** 独立写一条留痕（拒绝留痕 DENIED 等；只插不改）。 */
    void insertLog(SpaceActionLog log);

    /** 配置变更载荷（null = 该项不变更）。 */
    record SpaceUpdate(String intro, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
    }
}
