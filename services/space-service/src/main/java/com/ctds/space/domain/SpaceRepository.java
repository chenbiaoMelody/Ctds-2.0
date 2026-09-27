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
 *
 * <p><b>留痕 from/to 单一表达（WBS-3.2.4 移交④收敛）</b>：一切带留痕的仓储方法，留痕行的
 * from_value/to_value 一律由实现以方法参数统一回填——调用方构造的留痕对象该两字段被忽略/覆盖，
 * 防"参数与留痕载体"双表达漂移（一致性锚 T20）。</p>
 */
public interface SpaceRepository {

    // ==== 查询 ====

    /** 按技术主键取空间。 */
    Optional<Space> findById(long id);

    /** 按技术主键取准入单（准入单操作目标定位）。 */
    Optional<SpaceAdmission> findAdmissionById(long admissionId);

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

    // ==== 成员与准入（WBS-3.2.4）====

    /**
     * 该空间该主体是否已有活跃成员行（重复准入幂等前置门槛，行为 3 规则 4；DB 兜底 uk_active_member）。
     */
    boolean existsActiveMembership(long spaceId, String subjectNo);

    /** 同空间同主体同型待处理单（PENDING_APPROVAL/PENDING_CONFIRMATION）——重复提交返回既有（Q3-A）。 */
    Optional<SpaceAdmission> findPendingAdmission(long spaceId, String subjectNo, AdmissionType type);

    /** 按成员行 id 取成员（操作目标定位，Q5-A 行级精确——同主体多历史行时行 id 无歧义）。 */
    Optional<SpaceMember> findMemberById(long memberId);

    /**
     * 插入准入单（申请/邀请创建）+ 创建留痕（ADMIT_REQUEST/ADMIT_INVITE）同事务落库
     * （沿 create 单事务先例——拒绝留痕例外见评审循环 1 修复批），返回技术主键。
     */
    long insertAdmission(SpaceAdmission admission, SpaceActionLog log);

    /**
     * 成员生效事务（确认 CONFIRM / 审批 APPROVE 共用，hifi §3 单事务三步）：
     * ① space_member INSERT（role=MEMBER, status=ACTIVE，uk_active_member 兜底并发窗口）；
     * ② 准入单乐观门槛更新（WHERE id AND space_id AND status=from，0 行 → 1006C0010）+ 回填 member_id；
     * ③ 留痕同事务（from/to 由参数回填——移交④）。任一失败整体回滚。返回新成员行主键。
     */
    long activateMembership(SpaceMember member, long admissionId, long spaceId,
            AdmissionStatus fromStatus, SpaceActionLog log);

    /**
     * 准入单状态流转（谢绝 DECLINED / 拒绝 REJECTED；乐观门槛 WHERE id AND space_id AND status=from，
     * 0 行 → 1006C0010）；留痕同事务，from/to 由参数回填（移交④）。
     */
    void appendAdmissionTransition(long admissionId, long spaceId, AdmissionStatus fromStatus,
            AdmissionStatus toStatus, SpaceActionLog log);

    /** 准入单分页（空间视角，owner/admin 待办发现面；status null = 全部，按 id 升序）。 */
    PageResult<SpaceAdmission> searchAdmissions(long spaceId, AdmissionStatus status, PageQuery page);

    /** 我的准入单分页（个人视角：发出的申请 + 收到的邀请，subject_no 命中；按 id 降序——最新在前）。 */
    PageResult<SpaceAdmission> searchAdmissionsBySubject(String subjectNo, PageQuery page);

    /** 活跃成员分页（成员列表读面；按 id 升序）。 */
    PageResult<SpaceMember> searchActiveMembers(long spaceId, PageQuery page);

    /**
     * 成员关系终态化（退出 LEFT / 移除 REMOVED；乐观门槛 WHERE id AND space_id AND status=ACTIVE，
     * 0 行 → 1006C0008）；留痕（LEAVE/REMOVE）同事务，from_value=原角色、to_value=终态由参数回填。
     */
    void terminateMembership(long memberId, long spaceId, MemberRole fromRole, MemberStatus terminalStatus,
            SpaceActionLog log);

    /**
     * 角色变更（乐观门槛 WHERE id AND space_id AND status=ACTIVE AND role=fromRole，0 行 → 1006C0008）；
     * 留痕（ROLE_GRANT/ROLE_REVOKE）同事务，from/to 由参数回填（移交④）。
     */
    void changeRole(long memberId, long spaceId, MemberRole fromRole, MemberRole toRole, SpaceActionLog log);

    /**
     * 所有权转移单事务四写（移交②双处同步，hifi §4；顺序先降后升避 uk_active_owner 冲突）：
     * ① 原 owner 成员行降级（UPDATE role=formerOwnerNewRole WHERE space_id AND subject_no=当前owner
     * AND status=ACTIVE AND role=OWNER，0 行 → 1006C0008）；② 目标成员行升 OWNER（WHERE id AND space_id
     * AND status=ACTIVE AND role<>OWNER，0 行 → 1006C0008）；③ space.owner_subject_no 列乐观门槛同步
     * （WHERE id AND owner_subject_no=当前owner，0 行 → 1006C0008）；④ 留痕两行（grantLog + revokeLog，
     * from/to 为业务构造值——发起时点观察值，不适用状态机参数回填口径）。
     * 任一失败整体回滚；并发窗口由 uk_active_owner 唯一索引兜底（DuplicateKeyException → 1006C0009）。
     */
    void transferOwnership(long spaceId, String currentOwnerSubjectNo, String targetSubjectNo,
            long targetMemberId, MemberRole formerOwnerNewRole, SpaceActionLog grantLog,
            SpaceActionLog revokeLog);

    // ==== 策略继承与覆盖（WBS-3.2.5）====

    /** 平台级 ACTIVE 条目（scope=PLATFORM；覆盖目标定位与有效策略解析数据源）。 */
    Optional<SpacePolicy> findPlatformEntryByKey(String entryKey);

    /** 按技术主键取策略条目（平台条目变更端点定位；调用方校验 scope=PLATFORM 且 ACTIVE）。 */
    Optional<SpacePolicy> findPolicyById(long entryId);

    /** 平台级 ACTIVE 条目全集（有效策略解析数据源；按 id 升序）。 */
    List<SpacePolicy> findPlatformEntries();

    /** 平台级 ACTIVE 条目分页（治理面列表端点；按 id 升序）。 */
    PageResult<SpacePolicy> searchPlatformEntries(PageQuery page);

    /** 该空间该键的覆盖行（scope=SPACE 且 status=ACTIVE——首覆盖 INSERT / 再覆盖 UPDATE 的定位）。 */
    Optional<SpacePolicy> findSpaceEntry(long spaceId, String entryKey);

    /** 该空间条目全集（含 ARCHIVED——解散归档"保留可查"，有效策略解析数据源；按 id 升序）。 */
    List<SpacePolicy> findSpaceEntries(long spaceId);

    /**
     * 平台条目创建 + POLICY_DEFINE 留痕同事务（from=NULL → to=值）；同键已存在 ACTIVE 平台条目时
     * uk_scope_key（scope_uniq=0）兜底并发窗口（DuplicateKeyException → 1006C0012）。
     *
     * @return 新条目技术主键
     */
    long insertPlatformEntry(SpacePolicy entry, SpaceActionLog log);

    /**
     * 平台条目变更（值/红线标记，键不可变更；乐观门槛 WHERE id AND scope='PLATFORM' AND status=ACTIVE，
     * 0 行 → 1006C0014）+ POLICY_DEFINE 留痕同事务（from=旧值 → to=新值；红线标记变更记入 reason）。
     */
    void updatePlatformEntry(long entryId, String toValue, boolean toRedline, SpaceActionLog log);

    /**
     * 空间覆盖行落库（首覆盖 INSERT：scope=SPACE + platform_entry_id 显式指向 + POLICY_OVERRIDE 留痕
     * 同事务；并发首覆盖窗口由 uk_scope_key 兜底——DuplicateKeyException → 1006C0012）。
     *
     * @return 新覆盖行技术主键
     */
    long insertSpaceOverride(SpacePolicy entry, SpaceActionLog log);

    /**
     * 空间覆盖行值更新（再覆盖；乐观门槛 WHERE id AND space_id AND status=ACTIVE，0 行 → 1006C0002
     * 并发归档门槛）+ POLICY_OVERRIDE 留痕同事务（from/to 由参数回填——移交④口径）。
     */
    void updateSpaceOverrideValue(long entryId, long spaceId, String toValue, SpaceActionLog log);
}
