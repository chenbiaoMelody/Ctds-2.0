# WBS-3.2.4 空间成员与权限服务 · 高保真设计（编码契约）

> 任务卡：`docs/tasks/WBS-3.2.4-空间成员与权限服务-2026-09-27.md`｜低保真：`docs/designs/WBS-3.2.4-lofi.md`｜规格：`docs/specs/C-2.1-2.3-逻辑空间管理.md` V1.0
> **本文即编码契约**：实现与本文件不一致 = 打回项（章程 2.6）。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 编排师确认 | **已确认（2026-09-27 12:1x 会话回复"都按建议"）：Q1~Q7 均采建议 A + D1 不拆分**——本文转**编码契约**，实现与本文件不一致 = 打回项 |
| 确认时间 | 2026-09-27 12:1x 签署；**实现已回填（2026-09-27 编码会话，实现批 `d2ad46e`，T1~T21 全绿 + checkstyle 0 违规）** |

## 1. 端点契约表（REST，前缀 `/api/v1/data-spaces`，ADR-005 先例；响应统一 ApiResult 封套）

| # | 端点 | 权限判定 | 请求体 → 出参 | 规格锚点 |
| --- | --- | --- | --- | --- |
| 1 | `POST /{id}/admissions/applications` | 登录主体须 ADMITTED（`SubjectAdmissionGate`）；空间 access_mode ∈ {OPEN, APPROVAL} | `{intro? 不设——准入单无 intro 列，仅空体}` → `AdmissionView`（PENDING_APPROVAL） | 行为 3 规则 1/2 |
| 2 | `POST /{id}/admissions/invitations` | `space.admin`（owner/admin 或 platform.operator）；空间 access_mode = INVITE | `{subjectNo, reason?}` → `AdmissionView`（PENDING_CONFIRMATION） | 行为 3 规则 1/2 |
| 3 | `POST /{spaceId}/admissions/{admissionId}/confirmation` | 被邀方本人（准入单 subject_no = 登录主体） | `{decision: CONFIRM\|DECLINE, reason?}` → `AdmissionView`（APPROVED / DECLINED） | 行为 3 规则 2/5 |
| 4 | `POST /{spaceId}/admissions/{admissionId}/approval` | `space.admin` | `{decision: APPROVE\|REJECT, reason?}` → `AdmissionView`（APPROVED / REJECTED） | 行为 3 规则 2/5 |
| 5 | `GET /{id}/admissions` | `space.admin` | `?pageNum&pageSize&status?` → `PageResult<AdmissionView>` | 行为 3 规则 5（待办发现面） |
| 6 | `GET /admissions/mine` | 登录主体（跨空间个人面：发出的申请 + 收到的邀请） | `?pageNum&pageSize` → `PageResult<AdmissionView>` | 剧本 S1-2"我的邀请等价入口" |
| 7 | `GET /{id}/members` | 空间成员（`space.member`）或 platform.operator | `?pageNum&pageSize` → `PageResult<MemberView>`（含 id/主体编号/角色/状态/加入时点；不含终态行） | 剧本 S1-1/S3-1 判定工具 |
| 8 | `POST /{id}/members/{memberId}/role-assignment` | `space.admin`；操作者 ≠ 目标行本人（自我提权门槛） | `{role: ADMIN\|MEMBER}` → `MemberView` | 行为 4 规则 2/5 |
| 9 | `POST /{id}/members/{memberId}/removal` | `space.admin`；目标行不得为 OWNER | `{reason}`（必填） → `MemberView`（REMOVED） | 行为 5 规则 2 |
| 10 | `POST /{id}/leaving` | 登录主体本人（活跃成员行）；OWNER 行拒绝 | 空体 → `MemberView`（LEFT） | 行为 5 规则 1/4 |
| 11 | `POST /{id}/ownership-transfer` | **仅 owner 本人**（规格行为 4 规则 5"只归所有者本人发起"——platform.operator 亦不可代发） | `{targetMemberId}`（须为本空间活跃成员行且非本人） → `OwnershipTransferView`（新旧 owner 双行） | 行为 4 规则 3/5 + 行为 5 规则 1/3 |

通用约定（沿 3.2.3 hifi §1）：路径 `{id}` 为空间技术 id、`{admissionId}`/`{memberId}` 为准入单/成员行技术 id；空间不存在 = `1006C0004`（治理/成员动作可区分不存在）；读面对"不公开空间"按不存在口径**同形**返回（防存在性探测）；身份与角色经 `common/auth`（`AuthContext.subject()` / 角色头），`X-Ctds-Subject` 头为演示期身份。

## 2. 错误码表（`SpaceErrorCodes` 1006 段顺延新增 4 码；既有 0001~0007 / S0001 语义不变）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| 1006C0008 | MEMBER_RELATION_REQUIRED | 成员关系不存在或不活跃（目标成员行定位失败 / 操作者无活跃成员行——统一文案防成员存在性探测） | 404 |
| 1006C0009 | OWNER_PROTECTED | 唯一所有者保护：owner 行不可退出 / 移除 / 角色变更（行为 5 规则 1/2/3） | 409 |
| 1006C0010 | ADMISSION_STATE_GATE | 准入单状态不允许该动作（已处理单再确认 / 再审批——乐观门槛 0 行） | 409 |
| 1006C0011 | ADMISSION_MODE_MISMATCH | 准入形态与空间参与方范围不匹配（向邀请制空间提交申请 / 向公开·审批制空间发邀请） | 409 |

**复用既有码（不新增）**：`1006C0001`（主体未入驻，防枚举统一文案）、`1006C0002`（空间状态门槛——文案区分"空间当前不可接纳成员"vs"主体未入驻"两码天然分立）、`1006C0004`（空间不存在）、`1006C0007`（越权一律拒绝——含 member 越权、非成员访问、**自我提权**，行为 4 规则 3/5）、`1006S0001`（主体服务不可用）。**自我提权不新增码**（Q7：0007 语义天然覆盖）；理由/decision 字段超长与非法值走 Bean Validation + common 处理器（400，沿 3.2.3 教训"理由超长改走 common 处理器"）。

## 3. 准入单状态机与事务契约

- **准入单合法边**（`AdmissionStatus` 既有值域，不得增删）：`APPLICATION 型`：`PENDING_APPROVAL → APPROVED`、`PENDING_APPROVAL → REJECTED`；`INVITATION 型`：`PENDING_CONFIRMATION → APPROVED`、`PENDING_CONFIRMATION → DECLINED`；`APPROVED/REJECTED/DECLINED` 无出边（终态）；`CANCELLED` 值域保留**不启用**（lofi 不做什么 4）。
- **实现**（沿 space/subject 乐观门槛先例）：`UPDATE space_admission SET status=?, member_id=?, updated_at=NOW() WHERE id=? AND space_id=? AND status=?`（0 行 → `1006C0010`）；type 与边的对应由应用服务校验（形态错配 = `1006C0011`）。
- **成员生效事务**（确认 CONFIRM / 审批 APPROVE 共用，`@Transactional` 单事务）：① `space_member` INSERT（`role=MEMBER, status=ACTIVE`，uk_active_member 兜底并发窗口）；② 准入单乐观门槛更新（PENDING → APPROVED + 回填 member_id = ①主键）；③ 留痕（ADMIT_CONFIRM / ADMIT_APPROVE，result=SUCCESS，operator 四要素）。任一失败整体回滚。
  - **重复准入前置门槛**（行为 3 规则 4，Q3）：提交申请/邀请前查——活跃成员行存在 → 返回既有成员关系（200 幂等不新增）；同型待处理单存在 → 返回既有单；终态单存在 → 允许新单。
- **资格门槛**（行为 3 规则 1）：申请提交与邀请发出时均调 `SubjectAdmissionGate`（复用 3.2.3 通道零改动）——未入驻/不存在统一文案 `1006C0001`；确认/审批时**不重复出站判定**（发出时已判定，演示期身份受控；诚实登记：确认与发出之间资格可能变化，属可接受窗口，与 C-1.1 先例口径一致）。
- **空间状态门槛**（行为 3 规则 3 / 行为 5 规则 4）：申请/邀请/确认/审批/角色变更 → 空间须 ACTIVE（否则 `1006C0002`）；**退出与移除不受冻结/解散阻碍**（行为 5 规则 4 明文"终止成员关系不受阻"+ Q6 裁决冻结期允许移除）。

## 4. 成员关系事务契约（角色 / 退出 / 移除 / 所有权转移）

- **角色授予/收回**（端点 8）：门槛链 = `space.admin` 权限 → 空间 ACTIVE → 目标行定位（`WHERE id=? AND space_id=? AND status='ACTIVE' AND role<>'OWNER'`，0 行 → `1006C0008`；**OWNER 行不参与角色变更** → `1006C0009`）→ 自我提权门槛（操作者 = 目标行本人 → `1006C0007`）→ `UPDATE ... SET role=?`（乐观门槛）+ 留痕（目标角色 ADMIN 且原 MEMBER = ROLE_GRANT；目标 MEMBER = ROLE_REVOKE；from→to 落留痕）。
- **退出**（端点 10）：门槛链 = 登录主体 → 本人活跃行定位（0 行 → `1006C0008`）→ OWNER 行拒绝（→ `1006C0009`，行为 5 规则 1）→ `UPDATE SET status='LEFT', exited_at=NOW() WHERE id=? AND status='ACTIVE'` + 留痕（LEAVE）。冻结/解散状态不阻断。
- **移除**（端点 9）：门槛链 = `space.admin` 权限 → 目标行定位（同角色变更，OWNER 行 → `1006C0009`）→ reason 必填 → `UPDATE SET status='REMOVED', exited_at=NOW()` + 留痕（REMOVE，reason 落业务文案）。冻结期允许（Q6）。
- **所有权转移**（端点 11，移交②兑现，`@Transactional` 单事务**四写**，顺序先降后升避 `uk_active_owner` 冲突）：
  1. `UPDATE space_member SET role='ADMIN' WHERE space_id=? AND subject_no=当前owner AND status='ACTIVE' AND role='OWNER'`（原 owner 降级，Q4-A；0 行 → `1006C0008`）；
  2. `UPDATE space_member SET role='OWNER' WHERE id=? AND space_id=? AND status='ACTIVE' AND role<>'OWNER'`（目标升 OWNER；0 行 → `1006C0008`）；
  3. `UPDATE space SET owner_subject_no=新owner WHERE id=? AND owner_subject_no=当前owner`（列口径乐观门槛，移交②双处同步；0 行 → 并发变更拒绝 `1006C0008`）；
  4. 留痕两行：ROLE_GRANT（operator=原 owner，target=新 owner 行，from=ADMIN/MEMBER → to=OWNER）+ ROLE_REVOKE（operator=原 owner，target=原 owner 行，from=OWNER → to=ADMIN）。
  - 任一失败整体回滚；`uk_active_owner` 为并发窗口 DB 兜底（DuplicateKeyException → `1006C0009` 文案）。
- **立即失效**（行为 5 规则 5）：读面与动作权限判定唯一依据 = `findActiveMembers`（status=ACTIVE），终态行自然出局——集成测试断言退出/移除后原成员访问即被拒。

## 5. 统一权限面（移交①兑现；行为 4 规则 3/4 服务端强制）

- **权限点定稿**（Q1-A）：`space.admin`（管理动作：邀请/审批/准入单管理/移除/角色变更/生命周期管理动作）+ `space.member`（空间内读取与使用/退出）。常量类 `SpacePermissions`（domain，两常量）。
- **映射矩阵**（单一来源）：
  | 权限来源 | 折算结果 |
  | --- | --- |
  | 空间内角色 OWNER（成员表活跃行） | `space.admin, space.member` |
  | 空间内角色 ADMIN（成员表活跃行） | `space.admin, space.member` |
  | 空间内角色 MEMBER（成员表活跃行） | `space.member` |
  | 角色头 `platform.operator`（yml 映射） | `space.admin, space.member` |
- **收敛实现**：`SpaceAccessGuard` 重构为统一判定协作件——内部按映射矩阵折算"请求方在本空间的有效权限点集合"，对外保留既有方法签名（`canManage`/`canDissolve`/`isOwner`/`isPlatformOperator`/`requireSubject`，生命周期路径零改动）并新增成员域动作判定（`requireSpaceAdmin(space, members)` / `requireSpaceMember(space, members)`），**逐动作判定结果语义与 3.2.3 完全一致（T10 不回归）**。判定失败由调用方落 DENIED 留痕后抛 `1006C0007`（沿 3.2.3 模式；判定在应用服务，非注解静态门——判定输入含成员表数据）。
- **属主判定与权限点正交**：解散与所有权转移 = owner 属主动作——解散沿 3.2.3 口径（owner 或 platform.operator，行为 2 规则 4）；**所有权转移仅 owner 本人**（规格行为 4 规则 5 明文"只归所有者本人发起"）。属主判定依据 = `space.owner_subject_no` 列（3.2.3 hifi §10 E8 口径，与成员表活跃 OWNER 行一致性由 `uk_active_owner` 兜底）。
- **yml 修正**（space-service `application.yml` 唯一配置改动）：`ctds.auth.permissions.platform.operator: space.manage` → **`space.admin,space.member`**（权限点定稿映射，消除 3.2.3 零消费登记）；`ctds.space.operator-role` 保留。common 组件零改动。

## 6. 读面拒绝留痕（行为 6 规则 5 + 移交③兑现）

| 场景 | 对外响应 | 对内留痕 |
| --- | --- | --- |
| 非成员访问不公开（PRIVATE）空间详情 | 404 同形（3.2.3 既有口径不变，防存在性探测） | `ACCESS_DENIED`，result=DENIED，operator=请求方，target=(SPACE, 空间 id)，reason=业务文案，**无数据原文** |
| 非成员访问成员列表 / 准入单列表 | `1006C0007` | 同上（target 对应被拒对象） |
| 写面越权（既有模式不变） | 对应业务码 | DENIED 留痕（3.2.3 沿革） |

留痕与业务拒绝同事务落库（沿 3.2.3 "拒绝同样留痕"模式）；读面留痕不改变对外同形口径（审计可查、探测不可辨）。

## 7. 幂等与移交④⑤收敛登记

- **成员域幂等 = 业务幂等**（Q3）：重复准入前置门槛（§3）+ `uk_active_member` DB 兜底并发窗口——**不引入 `@Idempotent` 键**。移交⑤登记：本域幂等键不复存在，键长 256（common）vs 257（业务极值）边缘冲突在本域不发生；E11 沉淀候选（长度前缀或摘要编码）**保留给 common 沉淀小卡，不在本包实现**。
- **移交④ from/to 双表达收敛**：`SpaceRepository.appendTransition` 与本包新增的准入单/成员行变更方法——留痕行 `from_value`/`to_value` **一律由仓储实现以方法参数统一回填**，调用方构造的留痕对象该两字段被覆盖（单一表达来源）；接口 javadoc 契约化。一致性锚测试断言（T20）。

## 8. 测试计划（集成 Testcontainers mysql:8.0 实跑 + 矩阵/错误码纯单测；映射表每格可指到测试行）

| # | 测试 | 断言要点 | 映射 |
| --- | --- | --- | --- |
| T1 | 邀请+确认生效 | 邀请后成员列表不含被邀方；确认后成为成员（成员数 +1）；留痕"邀请+确认"四要素；member_id 回填贯通 | 行为 3 规则 2；剧本 S1-1/2 |
| T2 | 重复准入幂等 | 已是成员再邀请 → 返回既有、成员数不变；待确认单重复发出 → 返回既有单；终态单后新单允许 | 行为 3 规则 4；Q3；剧本 S1-3 |
| T3 | 资格门槛防枚举 | 未入驻与不存在统一文案 `1006C0001`（申请与邀请两入口） | 行为 3 规则 1；剧本 S1-4 |
| T4 | 申请+审批 | 申请 → PENDING_APPROVAL 非成员；批准后生效；拒绝含理由留痕且不产生成员 | 行为 3 规则 2/5；剧本 S1-6~9 |
| T5 | 空间状态门槛 | 未启用/冻结/解散空间申请、邀请、确认、审批、角色变更全拒 `1006C0002`；恢复后放行 | 行为 3 规则 3；剧本 S1-10~12 |
| T6 | 角色授予正向 | owner 授 admin 成功 + 留痕四要素；被授予者随后可邀请（能力正向对照）；收回回 member | 行为 4 规则 2/6；剧本 S2-1 |
| T7 | member 逐动作全拒 | member 尝试邀请/审批/移除/角色变更/冻结/解散 → 逐项 `1006C0007` + DENIED 留痕逐动作齐备 | 行为 4 规则 3；剧本 S2-2 |
| T8 | 仅所有者动作 | admin 解散被拒（3.2.3 语义保持）；admin 发起所有权转移被拒 | 行为 4 规则 3/5；剧本 S2-3 |
| T9 | 自我提权 | member 给自己授 admin → `1006C0007`；admin 目标=本人角色变更 → 拒 | 行为 4 规则 5；剧本 S2-4 |
| T10 | 非成员绕过 | 非成员直接请求成员列表/准入单列表 → 服务端拒绝 + ACCESS_DENIED 留痕 | 行为 4 规则 3 + 行为 6 规则 1；剧本 S2-5 |
| T11 | admin 正向邀请 | admin 邀请 E 并确认成功（admin 能力未被误伤） | 行为 4 规则 2；剧本 S2-6 |
| T12 | 退出+立即失效 | 退出后成员列表不含、访问被拒；留痕 LEAVE | 行为 5 规则 1/5；剧本 S3-1 |
| T13 | 移除含理由 | 移除成功、留痕含理由、访问立即失效 | 行为 5 规则 2/5；剧本 S3-2 |
| T14 | 唯一所有者保护 | owner 退出/被移除/被角色变更三路全拒 `1006C0009`；`uk_active_owner` 探针（两 OWNER 行必冲突） | 行为 5 规则 1/2/3；剧本 S3-3 |
| T15 | 冻结期边界 | 冻结后：退出允许、准入/授权拒、**移除允许**（Q6）；恢复后准入授权恢复 | 行为 5 规则 4；剧本 S3-4 |
| T16 | 所有权转移 | 正向四写落库（列同步+原 owner 降 ADMIN+目标升 OWNER+留痕两行）；非 owner 发起拒；目标非活跃/非本人成员拒；转移后新 owner 有属主能力、原 owner 无 | 行为 4 规则 5 + 行为 5 规则 1/3；移交② |
| T17 | 权限面收敛回归 | platform.operator（角色头）经新 yml 映射全动作放行（3.2.3 T10 语义不回归）；`SpaceAccessGuard` 矩阵纯单测（OWNER/ADMIN/MEMBER/角色头四来源 × 两权限点） | 行为 4 规则 4；移交① |
| T18 | 读面拒绝留痕 | 非成员取 PRIVATE 详情 → 404 同形 + ACCESS_DENIED 落库（对外形态不变、对内可审计） | 行为 6 规则 5；移交③ |
| T19 | 准入单状态机 | 谢绝 DECLINED（成员不产生）；重复确认/重复审批拒 `1006C0010`；向邀请制空间提交申请 / 向公开空间发邀请 → `1006C0011` | 行为 3 规则 2；Q7 |
| T20 | from/to 一致性锚 | appendTransition 留痕 from/to 与参数逐字一致（仓储回填单一表达） | 移交④ |
| T21 | 错误码格式锚 | `SpaceErrorCodes` 12 码全量：9 位/段位 1006/类型位/序号顺延 | 值域必填格（-2138 教训） |

**本地门禁**：`mvn -B -ntp -pl services/space-service -am test` + `checkstyle:check`；subject 等其他模块零改动不重跑；前端不触碰。

## 9. 边界值与异常行为

- reason（邀请理由缺省可空 / 谢绝理由可空 / 拒绝理由必填 / 移除理由必填）≤256——`@Size` Bean Validation + common 处理器（400）；decision 非法值 → 400；
- 我的准入单 / 空间准入单 / 成员列表均分页（common-pagination，默认口径沿 3.2.3 端点 7）；
- 准入单列表 `?status?` 可选筛选（值域校验，非法 → 400）；
- 空间不存在与不可访问同形口径（§6 表）；成员行不存在与不活跃统一 `1006C0008` 文案（防成员存在性探测）；
- 解散后空间：准入单操作全拒（`1006C0002`，终态空间不可接纳成员）；退出仍允许（行为 5 规则 4）。

## 10. 交付物核对清单

1. 四层代码：interfaces（SpaceController 追加 11 端点 + DTO）、application（新增 `SpaceAdmissionService` + `SpaceMemberService`；`SpaceAccessGuard` 重构为统一权限面协作件）、domain（`SpacePermissions` 常量；`SpaceRepository` 追加成员/准入/转移方法，javadoc 含移交④契约）、infrastructure（`SpaceJdbcRepository` 对应实现，留痕 from/to 统一回填）；
2. `SpaceErrorCodes` 新增 4 码（1006C0008~0011）；
3. space-service `application.yml` 权限点映射修正 1 行（`space.admin,space.member`）；
4. **零迁移**（六表既有载体全复用，动作码全复用预留值域，无 V3）；
5. 测试 T1~T21 全绿 + checkstyle 0 违规；
6. 本卡与 lofi/hifi 签署回填；台账与日志；C-2.2 剧本兼容性核验声明（预期"无需更新"，界面占位不变）。

## 11. 评审循环 1 勘误与修复批登记（2026-09-27，4 视角评审对账结论；均为澄清与补严，非需求变更）

> 4 视角评审（①规格与设计符合性 / ②安全供应链 / ③一致性重复 / ④测试质量，单发串行独立复跑门禁）：无 S0；修复批闭环 S1×3 + S2×6（含三视角交叉命中的同点问题）+ S3 择要。以下勘误沿 3.2.3 §10 E 系先例（勘误设计而非改代码——设计笔误与登记缺漏不回溯为代码缺陷）。

| # | 条目 | 勘误内容 |
| --- | --- | --- |
| E1 | §1 端点 1/2 出参 | `AdmissionView` → **`AdmissionOperationView{alreadyMember, admission, member}`**（Q3-A 幂等三态的响应形态——"返回既有成员关系"（剧本 S1-3 口径）与"返回既有单"须跨类型表达；实现批 `d2ad46e` 一直如此，本文初稿漏同步） |
| E2 | §5 新增方法名 | `requireSpaceAdmin` / `requireSpaceMember` → **`canManage`（复用既有）/ `canActAsMember` / `hasPermission`**（既有方法签名保留为收敛承诺的一部分，语义等价；实现命名与 3.2.3 既有调用方零改动兼容） |
| E3 | §7 回填口径 | "一切带留痕的仓储方法 from/to 一律由参数统一回填"补例外：**所有权转移留痕两行（grantLog/revokeLog）的 from/to 为业务构造值**（发起时点观察值：目标原角色/OWNER/原 owner 降级角色——无对应状态机参数可回填；T16 断言两行值）。其余方法回填口径不变 |
| E4 | §9 校验机制 | "@Size Bean Validation" → **应用服务长度门槛 + common PARAM_INVALID 通道（400）**（沿 3.2.3"理由超长改走 common 处理器"先例；可观察行为 400 等价，机制表述勘误） |
| E5 | §10 交付物① | "SpaceController 追加 11 端点" → **新建 `SpaceMembershipController`**（同 `@RequestMapping("/api/v1/data-spaces")`，成员域与生命周期域控制器分离，既有控制器零改动） |
| E6 | §3 准入单定位 | 补口径：准入单 id 未命中 → **1006C0010 同形处理**（防单据存在性探测；跨空间单据归属错配 → 0004）；审批"拒绝缺理由"= 参数校验（PARAM_INVALID/400，与移除理由同通道——修复批已对齐） |
| E7 | §4/§3 行为口径 | 修复批落地的四处行为口径登记：① 转移目标不可用（不存在/不活跃/OWNER 行=转移给自己）**统一 1006C0008**（防成员存在性探测，评审②S1 命中点）；② 申请/邀请成功路径 = **准入单 INSERT + 创建留痕同事务**（评审②S2 命中点，沿 create 先例）；③ `activateMembership` 成员行 INSERT 移入 try——并发重复准入窗口冲突 = 回查既有行返回（Q3-A 语义，评审①S2-1 命中点）；④ owner 行退出/移除/角色变更三路拒绝**统一落 DENIED 留痕**（此前仅退出路径落痕）；同角色重复设定 = 幂等无操作无留痕（200 返回现状态） |
| E8 | §5 fail-closed 补注 | Guard 重构后管理动作判定仅认活跃 OWNER/ADMIN 成员行与角色头映射（旧 isOwner 列短路不再参与管理动作判定）——owner 管理能力经其 OWNER 成员行保留，列与行一致性由 uk_active_owner 与转移四写同步保障；极端漂移态 fail-closed 收紧、无放大面（评审②S3-5 登记） |
