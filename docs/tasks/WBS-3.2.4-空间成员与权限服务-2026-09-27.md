# WBS-3.2.4 任务卡：空间成员与权限服务（2026-09-27）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账「下一包」= **WBS 行 254**；编排师 2026-09-27 会话确认"**立卡并起草**"（承接日志 `docs/logs/Ctds-项目开发日志-26-09-27-1144.md` 续点"下一包 = 3.2.4 空间成员与权限服务，待编排师立卡指示"） |
| 级别与性质 | L1-3 平台功能**实施包**（WBS 行 254 预算 **1 会话**）——**非界面类**：交付 = 空间成员准入/角色权限/退出移除的完整服务面（接口层+应用服务+领域+基础设施）；**走两级设计门禁**（lofi/hifi 一并落盘、一次确认——章程 2.6.3），设计文件 `docs/designs/WBS-3.2.4-lofi.md` / `-hifi.md` |
| 需求锚点核对 | 规格 `docs/specs/C-2.1-2.3-逻辑空间管理.md` V1.0：**行为 3**（成员准入：资格门槛/三档形态/空间状态门槛/重复准入幂等/留痕）+ **行为 4**（角色与权限边界：三档角色/动作级权限面/服务端强制/不得自我提权/留痕）+ **行为 5**（退出与移除：主动退出/移除含理由/唯一所有者保护/冻结期限制/立即失效）+ 行为 6 规则 5 读面拒绝留痕（3.2.3 hifi §10 E9 移交）；WBS 行 254（本卡 = "成员与权限服务，复用 common 鉴权"）；PRD 行 110（C-2.2 P0） |
| 上游能力边界 | **3.2.2 交付**（`ctds_space` 六表：`space_member` 双生成列+双唯一索引 / `space_admission` 准入单载体（状态机流转细则归本包 = §1.4 预告）/ `space_action_log` 动作码已预留 ADMIT_*、LEAVE、REMOVE、ROLE_*、ACCESS_DENIED；12 枚举含 MemberRole/MemberStatus/AdmissionType/AdmissionStatus）；**3.2.3 交付**（SpaceAccessGuard 双轨判定 / SubjectAdmissionGate 资格通道 / SpaceRepository.findActiveMembers / 留痕模式 / 乐观门槛先例 / 1006 段 8 码）；**3.2.3 移交五项**（①双轨收敛统一权限面+权限点命名 space.admin/space.member 定稿、yml `platform.operator: space.manage` 零消费待修正；②isOwner 列口径+所有权转移双处同步（`uk_active_owner` 兜底）；③读面拒绝留痕；④appendTransition from/to 双表达收敛；⑤幂等键长 256/257——均见 `docs/designs/WBS-3.2.3-hifi.md` §10 E8/E9/E11）；`common/auth`（AuthContext+角色头+AuthProperties permissions 映射）/ pagination / errorcode；ADR-005 资源命名 `/api/v1/data-spaces` |
| 交付物 | ① space-service 四层扩展：接口层 **11 端点（写面 8 + 读面 3**：申请/邀请/确认/审批/准入单列表/我的准入单/成员列表/角色变更/移除/退出/所有权转移）+ 应用服务（新增 SpaceAdmissionService + SpaceMemberService；SpaceAccessGuard 重构为统一权限面协作件）+ domain（SpacePermissions 权限点常量 + SpaceRepository 追加成员/准入/转移方法）+ infrastructure（JdbcRepository 对应实现）；② `SpaceErrorCodes` 新增 4 码（1006C0008~0011）；③ space-service **application.yml 权限点映射修正 1 行**（`space.admin,space.member` 替换零消费的 `space.manage`）；④ **零迁移**（六表载体与动作码全复用，无 V3）；⑤ 集成测试（Testcontainers 实跑，T1~T21：准入三形态/幂等/状态门槛/逐动作权限矩阵/唯一所有者保护/所有权转移四写/读面拒绝留痕/移交④一致性锚）；⑥ 本任务卡+lofi/hifi+台账+日志 |
| 关闭条件 | ① 两级设计经编排师**一次确认**（实现与设计逐条一致）；② 本地门禁全绿（compile/test/checkstyle）+ 集成测试全过；③ 规格行为 → 端点/规则/测试映射表齐备（本卡 §三）；④ 4 视角评审通过+修复批复审；⑤ 编排师验收通过 |
| 分支 | `feat/WBS-3.2.4-空间成员与权限服务`（自 `main` = `ac7c017`，沿"每包独立分支"惯例） |
| 纪律声明 | **只做成员域与权限面**——策略继承引擎归 3.2.5、界面归 3.2.6、测试达标（覆盖率/变异）归 3.2.7；common 组件零改动（权限面收敛 = space-service 内部协作件 + yml 映射修正）；不动门禁配置；不引新依赖；零迁移；错误码仅 1006 段顺延（0008~0011，语义独立才新增，自我提权复用 0007）；`CANCELLED` 准入单值域保留不启用（规格无撤回行为，开放走变更流程）；发现规格缺口一律走变更流程（红线 2/章程 2.6） |

---

## 一、范围与设计

- **做什么 / 不做什么**：见 lofi「做什么/不做什么」与 hifi 端点表/错误码表；规格行为 3/4/5 全部规则逐条落地，行为 6 仅补读面拒绝留痕一个缺口（E9 移交）。
- **两级设计一并提交**（章程 2.6.3 ≤1 天卡）：lofi = 方向（端点清单/权限面收敛/Q 清单），hifi = 编码契约（端点契约表/错误码表/准入状态机与事务/成员关系事务/测试计划）。

**设计要点摘要（供快速表决）**

1. **11 端点最小完整面**：准入面 5（申请/邀请/确认/审批/空间准入单列表）+ 个人面 1（我的准入单——被邀方获取待确认邀请的入口，演示链路闭环）+ 成员面 5（成员列表/角色变更/移除/退出/所有权转移）；
2. **统一权限面收敛**（移交①）：权限点定稿 `space.admin` / `space.member` 两点；映射矩阵单点化（空间内 OWNER/ADMIN→两点、MEMBER→member、角色头 platform.operator→两点经 yml）；`SpaceAccessGuard` 重构、既有方法签名与判定语义不变（3.2.3 T10 不回归）；owner 专属动作走属主判定与权限点正交（**所有权转移仅 owner 本人**——规格行为 4 规则 5 明文，platform.operator 亦不可代发）；
3. **准入单状态机乐观门槛**：PENDING_* → APPROVED/REJECTED/DECLINED 四边，0 行更新 = `1006C0010`；成员生效 = 单事务三步（成员行 INSERT + 准入单更新回填 member_id + 留痕）；重复准入幂等三态（已是成员→返回既有 / 待处理单→返回既有单 / 终态单→允许新单，Q3）；
4. **所有权转移单事务四写**（移交②）：先降原 owner（ADMIN）→ 再升目标（OWNER）→ 同步 `space.owner_subject_no` 列 → 留痕两行（ROLE_GRANT+ROLE_REVOKE，复用既有动作码零迁移）；顺序先降后升避 `uk_active_owner` 冲突；
5. **唯一所有者保护三门槛 + DB 兜底**：owner 行退出/移除/角色变更应用层显式拒（`1006C0009`）——`uk_active_owner` 只兜"两个 OWNER"，拦不住"OWNER 被降级"，**必须显式前置判断**（3.2.3 终态自环同款教训：同值/唯一性门槛拦不住的拒绝要显式写）；
6. **读面拒绝留痕**（移交③）：非成员访问 PRIVATE 详情保持 404 同形（防探测）+ 对内 ACCESS_DENIED 落库；对外形态不变、对内可审计。

## 二、待确认决策点（请一次确认；章程 2.6.3）

> 全文与备选影响见 **lofi「待确认问题」节**；下表为摘要，**确认后 lofi/hifi 确认记录签署、进入编码**。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 权限点定稿与收敛形态 | **A**：`space.admin`/`space.member` 两点 + Guard 重构统一判定（语义不变）+ yml 映射修正；owner 属主判定正交不入权限点 |
| **Q2** | 确认/审批端点形态 | **A**：合一端点 + decision 字段（CONFIRM\|DECLINE / APPROVE\|REJECT）；准入单操作挂空间路径下（归属校验） |
| **Q3** | 重复准入语义 | **A**：已成员→返回既有；待处理单→返回既有单；终态单→允许新单 |
| **Q4** | 所有权转移细节 | **A**：目标=活跃非本人成员；原 owner 降 ADMIN；留痕复用 ROLE_GRANT/ROLE_REVOKE（零迁移）；不设二次确认（规格仅解散要求） |
| **Q5** | 成员操作目标定位 | **A**：按 memberId（成员行 id）——行级精确，规避同主体多历史行歧义 |
| **Q6** | 冻结期移除语义 | **A**：允许移除（冻结语义只封"进"与"授权"，规格未禁不加严）；测试固化 |
| **Q7** | 新错误码清单 | **A**：顺延 4 码（0008 成员关系不存在 / 0009 所有者保护 / 0010 准入单状态门槛 / 0011 准入形态错配）；自我提权复用 0007 |
| **D1** | 体量 / 是否拆分 | **A**：不拆分——预估 ~1400~1800 行，超 400 行指引（单服务单目标原子交付，沿 3.2.2/3.2.3 先例） |

> **确认留痕（2026-09-27 12:1x，编排师会话回复"都按建议"）**：**Q1~Q7 均采建议 A + D1 不拆分**——两级设计转**已确认（编码契约）**，lofi/hifi 确认记录同批签署；缺口声明两处（Q3 重复申请 / Q6 冻结期移除）随确认视为裁决落定；同批进入编码。

## 三、规格行为 → 端点/规则/测试 映射表

| 规格行为（V1.0） | 承载端点/规则 | 关键测试 |
| --- | --- | --- |
| 行为 3 规则 1 资格门槛 | 申请/邀请两入口 + SubjectAdmissionGate（复用 3.2.3） | 未入驻/不存在统一文案 `1006C0001`（T3） |
| 行为 3 规则 2 三档形态 | OPEN/APPROVAL=申请+审批；INVITE=邀请+确认；形态错配 `1006C0011` | T1/T4/T19 |
| 行为 3 规则 3 空间状态门槛 | 申请/邀请/确认/审批 → 须 ACTIVE，`1006C0002` | T5 |
| 行为 3 规则 4 重复准入幂等 | 前置门槛三态 + uk_active_member 兜底 | T2 |
| 行为 3 规则 5 留痕 | ADMIT_* 动作码四要素；拒绝含理由 | T1/T4/T13 |
| 行为 4 规则 1 三档角色 | MemberRole 既有值域（不新增档位） | T17 矩阵 |
| 行为 4 规则 2 动作级权限面 | 统一权限点矩阵（space.admin/space.member） | T6/T11/T17 |
| 行为 4 规则 3 越权一律拒绝 | 逐动作判定 + DENIED/ACCESS_DENIED 留痕 | T7/T10 |
| 行为 4 规则 4 权限点命名 | SpacePermissions 常量定稿 + yml 映射修正 | T17 |
| 行为 4 规则 5 不得自我提权 | 自我提权门槛（复用 `1006C0007`）；所有权转移仅 owner 本人 | T8/T9/T16 |
| 行为 4 规则 6 授予收回留痕 | ROLE_GRANT/ROLE_REVOKE 四要素 | T6/T16 |
| 行为 5 规则 1 主动退出 | leaving；owner 拒（先转让或解散） | T12/T14 |
| 行为 5 规则 2 移除含理由 | removal；reason 必填；不得移除 owner | T13/T14 |
| 行为 5 规则 3 唯一所有者保护 | 三门槛显式拒绝 + uk_active_owner 兜底 + 所有权转移双处同步 | T14/T16 |
| 行为 5 规则 4 冻结期限制 | 退出允许；准入/授权拒；移除允许（Q6） | T5/T15 |
| 行为 5 规则 5 留痕与立即失效 | LEAVE/REMOVE 留痕；判定源=活跃行 | T12/T13 |
| 行为 6 规则 5 拒绝留痕 | 读面 ACCESS_DENIED（404 同形口径不变） | T10/T18 |
| 移交① 统一权限面 | §5 收敛实现 + yml 修正 | T17 |
| 移交② 所有权转移双处同步 | 端点 11 四写事务 | T16 |
| 移交③ 读面拒绝留痕 | §6 | T18 |
| 移交④ from/to 单一表达 | 仓储统一回填 + javadoc 契约 | T20 |
| 移交⑤ 幂等键长规避 | 业务幂等（无 @Idempotent 键）+ 沉淀候选保留登记 | T2（设计登记） |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | ✅ **编码交付 + 4 视角评审循环 1 + 修复批完成（2026-09-27 13:2x），待独立复审**——评审无 S0；修复批闭环 S1×3 + S2×6 + S3 择要（明细见本表修复批行）；门禁复跑 **test 100/100（新增 4 用例）+ checkstyle 0**。**前置段（编码交付）**：本地门禁全绿（compile ✅ / test **96/96** ✅（新增 26 + 3.2.3 回归 16/16 + 迁移 7 + 单测族）/ checkstyle **0 违规** ✅）；space-service 模块整体 96 测试全绿，common 依赖模块 63/63 同批回归通过 |
| 4 视角评审与修复批（2026-09-27 13:0x~13:2x） | **4 视角单发串行评审**（各视角独立复跑门禁 96/96 实证）：①规格与设计符合性=不通过（S2×4+S3×7）/②安全供应链=不通过（**S1×1** 转移端点打破成员行防枚举统一文案 + S2×1 申请邀请留痕非同事务 + S3×6）/③一致性重复=不通过（S2×5+S3×8）/④测试质量=不通过（**S1×3** 终态单后新单/admin 自提权显式门槛/端点6我的准入单 零覆盖 + S2×4）；无 S0 → **修复批**：代码 6 文件（activateMembership INSERT 移入 try+并发回查既有行【死捕获修复】/转移目标统一 0008 防探测文案【删死条件】/审批拒绝缺理由改 400 与 remove 对齐/申请邀请留痕同事务化【insertAdmission 单事务两写】/?status 非法值 400【controller 收 String+服务显式校验】/owner 行三路拒绝统一落痕/文案与 javadoc）+ 测试 1 文件（既有 6 方法增补断言 + 新增 4 用例：adminCannotPromoteSelf/myAdmissionsListsBothDirections/memberRelationFailuresUseUnifiedNotFoundMessage/admissionListSupportsStatusFilterAndPagination）+ **hifi §11 勘误登记 8 项（E1~E8，沿 3.2.3 §10 先例）** → **Tests run: 100, Failures: 0, Errors: 0** + checkstyle 0 违规 |
| 验证结果 | `mvn -B -ntp -pl services/space-service -am test`（Testcontainers mysql:8.0 实跑）：**Tests run: 96, Failures: 0, Errors: 0**——SpaceMembershipIntegrationTest 20（T1~T20 集成面）/ SpaceAccessGuardPermissionMatrixTest 6（T17 矩阵）/ SpaceErrorCodesFormatTest 13（T21 含新增 4 码断言+12 码封闭性）/ SpaceExceptionHandlerCodeConsistencyTest 1（码表↔处理器 12 码锚）/ **SpaceLifecycleIntegrationTest 16（3.2.3 回归，Guard 重构零语义漂移实证）** / SpaceMigrationIntegrationTest 7 / SpaceNameNormalizerTest 9 / SubjectAdmissionClientTest 11 / 枚举 12 / 守卫 1；`checkstyle:check` **0 违规** |
| 实现修复批（编码期两处真缺陷，测试实证后即修） | ① **mapAdmission `rs.wasNull()` 判空错位**——member_id 判空必须紧跟 getLong（中间穿插 getString 后 wasNull 看的是末列，reason 为 NULL 的准入单 member_id 被误判为空 → 回填值丢失）；② **确认/审批终态自环**——已 APPROVED 单重复确认被"同值 WHERE 乐观门槛"放行（3.2.3 终态自环同款教训复刻：显式前置门槛拦截 + 乐观门槛保留 TOCTOU 并发兜底），测试 T19 断言重复确认 409 |
| 剧本是否需要更新 | **C-2.2 剧本无需更新**——三幕步骤与实现逐一兼容：S1 准入 12 步（邀请/确认/幂等/未入驻统一文案/申请审批/冻结门槛与恢复复位）全部有端点承载；S2 权限 6 步（授予/逐动作拒绝/仅所有者/自我提权/非成员绕过/admin 正向邀请）逐一被 T6~T11 覆盖；S3 退出移除 5 步（退出/移除理由/唯一所有者保护/冻结期边界/恢复）逐一被 T12~T15 覆盖；"我的邀请（或等价入口）"= 端点 6 我的准入单；界面入口占位不变（Q8-A，3.2.6 交付后核对修订） |
| 复用声明（实现回填） | 复用 3.2.2 六表载体与 12 枚举（**零迁移**）、3.2.3 SubjectAdmissionGate/留痕模式/乐观门槛模式、common auth（**RolePermissionMapper 注入**——yml 权限点映射自此被真实消费，移交①"零消费"状态消除）/pagination/errorcode；**未新增第三方依赖**（GeneratedKeyHolder 为 spring-jdbc 既有） |
| 体量登记（实测） | 预估 ~1400~1800 行；**实测增量 2221 行**（主代码 15 文件 ~1330 + 测试 ~890）——超出预估主因集成测试 799 行（T1~T21 全场景+helper），主代码在预估带内；超出部分全在设计契约内（hifi §8 测试计划 21 项逐项落地） |
| 立卡前素材盘点（只读） | ① WBS 行 254 + 规格 V1.0 行为 3/4/5/6-5 全文；② 3.2.3 代码实测：`SpaceAccessGuard`（isPlatformOperator/isOwner/canManage/canDissolve/requireSubject 六方法）、`SpaceRepository.findActiveMembers`（成员判定源已有）、`SubjectAdmissionGate`（资格通道已有）、`SpaceErrorCodes`（0001~0007+S0001）、`space_action_log` 动作码预留值域覆盖本域全部动作；③ `common/auth` 机制：`AuthProperties.permissions` = 角色→逗号分隔权限清单（模式 A），space yml 现登记 `platform.operator: space.manage`（零消费）；④ 移交五项原文（3.2.3 hifi §10 E8/E9/E11 + 台账下一包行）；⑤ C-2.2 剧本三幕步骤与本包端点逐一对照成立（S1 准入 12 步 / S2 权限 6 步 / S3 退出移除 5 步——"我的邀请等价入口"= 端点 6 我的准入单） |
| 规格外实现声明 | 无（端点/规则全部由规格行为 3/4/5 + 行为 6 规则 5 + 移交五项派生；Q2/Q3/Q4/Q5/Q6 为规格授权范围内实现形态决策——行为 4 规则 5"随 3.2.4 设计"与规格 §尾注"接口签名归 3.2.4"授权；Q3/Q6 两处规格未定义路径已在 lofi 缺口声明登记待裁决） |
| 复用声明 | 复用 3.2.2 六表载体与 12 枚举、3.2.3 SpaceAccessGuard/SubjectAdmissionGate/留痕模式/乐观门槛模式/SpaceRepository、common auth/pagination/errorcode；**未新增第三方依赖、零迁移** |
| 体量登记 | 预估 ~1400~1800 行（见 D1）；实测待编码后回填 |

---

> **边界复述（红线自查）**：本卡不动 common 代码、不动门禁配置、不引新依赖、零迁移；既有服务唯一改动 = space-service 自身 yml 1 行权限点映射修正；策略域与界面域零触碰；错误码仅 1006 段顺延 4 码。
