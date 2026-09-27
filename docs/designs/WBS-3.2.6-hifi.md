# WBS-3.2.6 空间管理界面 · 高保真设计（编码契约）

- 型态：**界面类**（高保真 = 编码契约 + 界面说明书）
- 任务卡：`docs/tasks/WBS-3.2.6-空间管理界面-2026-09-27.md`；低保真：`docs/designs/WBS-3.2.6-lofi.md`（W1~W12 / N1~N8 / Q1~Q8 / D1）
- 线框原型：`docs/designs/WBS-3.2.6-原型-空间管理界面.html`
- 上游契约：`docs/designs/WBS-3.2.3-hifi.md` §1、`WBS-3.2.4-hifi.md` §1（+ §11 E1 出参勘误）、`WBS-3.2.5-hifi.md` §1 + §3；通用约定 `ADR-005`（REST / 九位错误码 / 分页 §3.2 / 401-403 文案）、`ADR-016 §2.7`（演示期安全边界）、`ADR-001`（前端栈冻结）
- 本文件 §1~§11 经编排师确认后即**编码契约**（未确认 = 禁止编码，章程 2.6.1）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 高保真确认（编码契约） | **已确认（2026-09-27 20:2x）**：编排师会话回复"**都按建议**"→ Q1~Q8 均采建议口径 + D1 不拆分（详见 `WBS-3.2.6-lofi.md` §3 末与任务卡 §二 确认留痕）→ **本文件 §1~§11 = 编码契约，自本行起生效**；编码归新会话（第一步 = §11 前置检查项） |

---

## 1. 接口契约表（**全部复用既有端点**，本包零改动语义；唯一新增 = 端点 25，是否新增见 lofi Q6）

**通用约定**：路径前缀 `/api/v1`；响应统一 `ApiResult<T>`（`code="0"` 成功）；分页请求 `?pageNum&pageSize`，响应 `PageResult<T>{list,total,pageNum,pageSize,totalPages}`（`common-pagination`）；身份与角色经 `common/auth`（`X-Ctds-Subject` / `X-Ctds-Roles`）；**权限判定一律在服务端**（应用服务 / `SpaceAccessGuard`），前端只做体验层显隐。

| # | 端点 | 权限判定（服务端） | 界面落点 |
| --- | --- | --- | --- |
| 1 | `POST /data-spaces` | 登录主体且须 ADMITTED（`SubjectAdmissionGate`） | 列表页「创建空间」弹窗 |
| 2 | `POST /data-spaces/{id}/enablement` | owner/admin 或 `platform.operator` | 详情·概览「启用」 |
| 3 | `POST /data-spaces/{id}/freezing` | owner/admin 或 `platform.operator` | 详情·概览「冻结」 |
| 4 | `POST /data-spaces/{id}/unfreezing` | owner/admin 或 `platform.operator` | 详情·概览「恢复」 |
| 5 | `POST /data-spaces/{id}/dissolution` | **仅 owner 或 `platform.operator`**（admin 不可） | 详情·概览「解散」（二次确认） |
| 6 | `PUT /data-spaces/{id}` | owner/admin 或 `platform.operator`；白名单字段（简介 / 生效期） | 详情·概览「修改配置」 |
| 7 | `GET /data-spaces?pageNum&pageSize&keyword?` | 公开：登录主体（仅 PUBLIC）；`platform.operator`：全量 | 列表页表格 |
| 8 | `GET /data-spaces/{id}` | 成员/owner/admin 或 `platform.operator`：全量；非成员：仅 PUBLIC 返回摘要 | 详情页头部/概览 |
| 9 | `POST /data-spaces/{id}/admissions/applications` | 登录主体须 ADMITTED；空间 `accessMode ∈ {OPEN, APPROVAL}` | 详情·成员与准入「提交加入申请」 |
| 10 | `POST /data-spaces/{id}/admissions/invitations` | `space.admin`；空间 `accessMode = INVITE` | 详情·成员与准入「邀请成员」 |
| 11 | `POST /data-spaces/{spaceId}/admissions/{admissionId}/confirmation` | 被邀方本人（准入单 `subjectNo` = 登录主体） | 我的邀请「接受 / 谢绝」 |
| 12 | `POST /data-spaces/{spaceId}/admissions/{admissionId}/approval` | `space.admin`；拒绝须填理由 | 详情·成员与准入「审批」 |
| 13 | `GET /data-spaces/{id}/admissions?pageNum&pageSize&status?` | `space.admin` | 详情·成员与准入·准入单表格 |
| 14 | `GET /data-spaces/admissions/mine?pageNum&pageSize` | 登录主体（跨空间个人面） | 我的邀请页表格 |
| 15 | `GET /data-spaces/{id}/members?pageNum&pageSize` | 空间成员（`space.member`）或 `platform.operator` | 详情·成员与准入·成员表格 |
| 16 | `POST /data-spaces/{id}/members/{memberId}/role-assignment` | `space.admin`；操作者 ≠ 目标行本人（禁止自我提权） | 成员行「授予 / 收回管理员」 |
| 17 | `POST /data-spaces/{id}/members/{memberId}/removal` | `space.admin`；目标行不得为 OWNER；理由必填 | 成员行「移除」 |
| 18 | `POST /data-spaces/{id}/leaving` | 登录主体本人活跃成员行；OWNER 行拒绝 | 成员区「退出空间」 |
| 19 | `POST /data-spaces/{id}/ownership-transfer` | **仅 owner 本人** | 成员行「转移所有权」 |
| 20 | `POST /platform-policies` | `platform.policy` | 平台治理「新建条目」 |
| 21 | `PUT /platform-policies/{entryId}` | `platform.policy`；值与红线至少一项；键不可变更 | 平台治理「变更」 |
| 22 | `GET /platform-policies?pageNum&pageSize` | `platform.policy` | 平台治理表格 |
| 23 | `POST /data-spaces/{id}/policies/overrides` | `space.admin`；空间须 ACTIVE | 策略区「提交覆盖」 |
| 24 | `GET /data-spaces/{id}/policies/effective` | ACTIVE/FROZEN：`space.member` 或 `platform.operator`；DISSOLVED：仅 owner 或 `platform.operator` | 策略区有效策略表格 |
| **25** | **`GET /data-spaces/{id}/action-logs?pageNum&pageSize`（新增，Q6-A）** | 空间成员或 `platform.operator`；DISSOLVED：仅 owner 或 `platform.operator`；拒绝访问写拒绝留痕 | 详情·概览·操作留痕表格 |

**请求体形状（逐字段，界面须按此构造）**

| 端点 | 请求体 | 出参（界面所需字段） |
| --- | --- | --- |
| 1 | `{name, sceneType, accessMode, visibility, intro?, effectiveFrom?, effectiveTo?}` | `SpaceDetailView` |
| 5 | `{confirmDissolve: true, reason?}` | `SpaceDetailView` |
| 6 | `{intro?, effectiveFrom?, effectiveTo?}`（**仅白名单**） | `SpaceDetailView` |
| 10 | `{subjectNo, reason?}` | `AdmissionOperationView{alreadyMember, admission, member}` |
| 9 | 空体 | `AdmissionOperationView` |
| 11 | `{decision: CONFIRM\|DECLINE, reason?}` | `AdmissionView` |
| 12 | `{decision: APPROVE\|REJECT, reason?}` | `AdmissionView` |
| 16 | `{role: ADMIN\|MEMBER}` | `MemberView` |
| 17 | `{reason}` | `MemberView` |
| 19 | `{targetMemberId}` | `OwnershipTransferView{spaceOwnerSubjectNo, formerOwner, newOwner, transferredAt}` |
| 20 | `{entryKey, entryValue, redline}` | `PolicyEntryView` |
| 21 | `{entryValue?, redline?}` | `PolicyEntryView` |
| 23 | `{entryKey, entryValue}`（**不得携带 redline 等白名单外字段** → 后端 `1006C0012`） | `PolicyOverrideView` |

**出参字段（界面展示口径）**

- 列表 `SpaceSummaryView`：`id, name, sceneType, accessMode, visibility, intro, status, effectiveFrom, effectiveTo`；
- 详情 `SpaceDetailView`：+ `ownerSubjectNo, createdAt, updatedAt, members[{subjectNo, role}]`（内联成员仅两项，**成员表以端点 15 为准**，见 lofi Q3）；
- 成员 `MemberView`：`id, spaceId, subjectNo, role, status, joinedAt, exitedAt`；
- 准入单 `AdmissionView`：`id, spaceId, subjectNo, type, status, operator, reason, memberId, createdAt`；
- 有效策略 `EffectivePolicyItemView`：`entryKey, displayName, effectiveValue, source, provenance, note, platformValue, spaceValue, spaceStatus, redline`；
- 覆盖结果 `PolicyOverrideView`：`entryKey, effectiveValue, source, provenance, note, platformValue, spaceValue, redline`；
- 平台条目 `PolicyEntryView`：`id, entryKey, displayName, entryValue, redline, status, createdAt, updatedAt`；
- 留痕（新增）`SpaceActionLogView`：`id, action, operator, result, reason, fromValue, toValue, targetType, targetId, createdAt`。

---

## 2. 前端模块与类型

| 文件 | 内容 |
| --- | --- |
| `src/api/space.ts`（**新增**） | 25 个端点的调用函数（命名 `createSpace / enableSpace / freezeSpace / unfreezeSpace / dissolveSpace / updateSpace / listSpaces / getSpace / applyAdmission / inviteMember / confirmAdmission / approveAdmission / listAdmissions / listMyAdmissions / listMembers / assignRole / removeMember / leaveSpace / transferOwnership / createPlatformPolicy / updatePlatformPolicy / listPlatformPolicies / submitPolicyOverride / getEffectivePolicies / listActionLogs`）；**页面级角色头**：`headers: { 'X-Ctds-Roles': spaceRolesHeader() }`（覆盖 `apiJson` 全局头，见 §4）；复用 `apiJson`（**不新增 `fetch` 直连**） |
| 同上（类型） | 在上述模块内就地声明响应类型（`SpaceSummary` / `SpaceDetail` / `MemberItem` / `AdmissionItem` / `AdmissionOperation` / `OwnershipTransfer` / `PolicyEntry` / `PolicyOverride` / `EffectivePolicyItem` / `SpaceActionLog`）；分页复用 `src/api/types.ts` 的 `PageData<T>`（**不在本模块重复声明**） |
| `src/constants/space.ts`（**新增**） | 状态 / 角色 / 准入形态与状态 / 场景类型 / 参与方范围 / 可见性 / 策略来源（`source` + `provenance` 三态）/ 策略目录值 / 留痕动作与结果的中文标签与 tag 色映射；**统一提示文案常量**（`SPACE_NOT_ACCESSIBLE_TIP` = "空间不存在或无权访问"、`SPACE_ACCESS_DENIED_TIP` 等）——页面**禁止散写**这些中文（§6.6） |
| `src/stores/demoIdentity.ts`（**新增**） | 演示身份档读写：`getActorMode()` / `setActorMode(mode)`（`'subject'` 默认 / `'operator'`）、`spaceRolesHeader()`（普通档 = `applicant`；运营档 = `applicant,platform.operator`）；主体编号读写复用 `api/client.ts` 的 `getDemoSubject()` / `setDemoSubject()`（**不新建第二份 subject 存储**） |
| `src/views/space/ListView.vue` / `DetailView.vue` / `MyAdmissionsView.vue` / `PlatformPolicyView.vue`（**新增**） | 见 §6 界面说明书 |
| `src/layouts/MainLayout.vue`（**改**） | 顶栏新增"演示身份"控件（主体编号输入 + 档位下拉：普通主体 / 平台运营方），并在切换时写回 store；其余布局行为零改动 |
| `src/router/index.ts`（**改**） | 新增路由（§3） |
| `src/layouts/MainLayout.spec.ts` / `src/router/router.spec.ts`（**改**） | 菜单计数断言同步（admin **10** / user **5**）+ 新路由与权限守卫断言 |

---

## 3. 路由与菜单（`src/router/index.ts`）

| path | name | 组件 | meta |
| --- | --- | --- | --- |
| `spaces` | `space-list` | `views/space/ListView.vue` | `{title: '逻辑空间', menu: true, menuOrder: 9, icon: 'Grid', permission: 'space.member'}` |
| `spaces/my-admissions` | `space-my-admissions` | `views/space/MyAdmissionsView.vue` | `{title: '我的邀请与申请', permission: 'space.member'}`（**不占菜单项**；须声明在 `spaces/:id` 之前） |
| `spaces/:id` | `space-detail` | `views/space/DetailView.vue` | `{title: '空间详情', permission: 'space.member'}` |
| `platform-policies` | `platform-policy` | `views/space/PlatformPolicyView.vue` | `{title: '平台策略治理', menu: true, menuOrder: 10, icon: 'Setting', permission: 'platform.policy'}` |

**菜单计数**：admin 演示角色 **8 → 10**；普通 `user` 角色仍 **5**（新增两项均带权限点，`hasPermission` 对非 admin 不放行）。`MainLayout.spec.ts:56/67` 两处断言与 `router.spec.ts` 路由表断言同步更新。

---

## 4. 演示身份（主体 + 档位）

- **主体编号**：复用 `api/client.ts` 的 `getDemoSubject()` / `setDemoSubject()`（localStorage 键 `ctds-demo-subject`）；
- **档位**：`src/stores/demoIdentity.ts`，localStorage 键 `ctds-demo-actor-mode`，取值 `subject`（默认）| `operator`；
- **角色头**：`spaceRolesHeader()` = 普通档 `applicant`；运营档 `applicant,platform.operator`（服务端 `ctds.auth.permissions.platform.operator: space.admin,space.member,platform.policy` 映射有效）；
- **硬约束（测试锚点 T27）**：**普通档请求不得携带 `platform.operator`**——否则成员越权类剧本步骤会被服务端放行（lofi §3 Q2 理由③）；
- **边界**：`X-Ctds-Subject` 为空 → `common/auth` `AuthContextFilter` 整头作废（按未认证）→ 顶栏主体编号**必填**，空值提交前端拦截（零请求）；
- **不改全局**：`client.ts` 的 `demoRolesHeader()` 一字不动（最小权限面，沿 3.1.11 Q7 先例）。

---

## 5. 后端新增只读端点（**仅 Q6 采 A 时实施**，否则本节整体不实施）

| 项 | 内容 |
| --- | --- |
| 端点 | `GET /api/v1/data-spaces/{id}/action-logs?pageNum&pageSize` → `ApiResult<PageResult<SpaceActionLogView>>` |
| 落点 | `services/space-service`：`interfaces/SpaceActionLogController`（新增）、`interfaces/dto/SpaceActionLogView`（新增）、`application/SpaceQueryService`（加方法）、`domain/SpaceRepository` + `infrastructure/SpaceJdbcRepository`（加只读方法） |
| 权限 | 与端点 24 同口径：ACTIVE/FROZEN = 空间成员（`space.member`）或 `platform.operator`；DISSOLVED = 仅 owner 或 `platform.operator`；非成员 / 无权 → `1006C0007`（403）+ **拒绝留痕**（动作码复用既有 `ACCESS_DENIED`，`targetType=SPACE`，理由 = 新增服务端常量 `ACTION_LOG_VIEW_DENIED_LOG_REASON` = "无权访问空间操作留痕"） |
| 数据 | 只读既有 `space_action_log`（`WHERE space_id = ? ORDER BY created_at DESC, id DESC`，参数化；列表字段白名单）；**不含敏感原文**（该表本身不含） |
| 错误码 | **零新增**：空间不存在 → `1006C0004`；无权 → `1006C0007`；分页越界 → `1000C0001`（`PageQuery` 承担） |
| 库表/迁移 | **零新增、零迁移**（动作码 `ACCESS_DENIED` 与 `TargetType.SPACE` 均为既有取值，V2 注释已登记） |
| 既有端点 | **零改动**（3.2.3 / 3.2.4 / 3.2.5 的 24 个端点方法签名、字段、错误码一字不动） |

---

## 6. 界面说明书

### 6.1 全局布局

- 左侧菜单（既有 `MainLayout`）：……「逻辑空间」（`menuOrder 9`）、「平台策略治理」（`menuOrder 10`）；
- 顶栏：既有「演示模式」标识 + 角色切换 + **新增「演示身份」控件** = 主体编号输入框（占位提示"如 S20260925000001"）+ 档位下拉（`普通主体` / `平台运营方`）+ 保存按钮；保存后提示"已切换演示身份（主体：XXX，档位：普通主体|平台运营方）"；
- 所有空间域请求经 `apiJson` 自动带 `X-Ctds-Subject`（主体编号）与 `X-Ctds-Roles`（按档位）。

### 6.2 页面 1：逻辑空间（`/spaces`）

| 区块 | 内容 |
| --- | --- |
| A 操作条 | 「创建空间」按钮（打开弹窗 A）、「我的邀请与申请」按钮（跳 `/spaces/my-admissions`） |
| B 检索条 | 名称关键字输入 + 「查询」「重置」（keyword 名称前缀；空值 = 不过滤） |
| C 表格（分页） | 列：空间名称 / 场景类型 / 参与方范围 / 可见性 / 状态（tag）/ 生效期（起止）/ 操作（「进入详情」）；`status` 文案取 `constants/space.ts`（已创建（未启用）/ 已启用 / 已冻结 / 已解散） |
| D 空态 | "暂无可查看的空间（公开空间或你参与的空间）"——**空态不是报错** |

### 6.3 页面 2：空间详情（`/spaces/:id`）

**头部**：空间名称 + 状态 tag +「返回列表」。按状态与身份给出可用动作入口（**仅体验层**：状态不允许时不显示对应按钮，但服务端判定为准）。

**Tab 1 概览**

| 区块 | 内容 |
| --- | --- |
| A 要素 | 名称 / 场景类型 / 参与方范围 / 可见性 / 状态 / 简介 / 生效期（起止）/ 所有者主体编号 / 创建时间 / 更新时间；非成员看到的公开摘要仅含公开要素，并显示统一提示 `SPACE_NOT_ACCESSIBLE_TIP`（空间不存在或无权访问）——**与 `1006C0004` 同一条文案与样式** |
| B 生命周期 | 按状态显示：「启用」（CREATED）、「冻结」（ACTIVE）、「恢复」（FROZEN）、「解散」（CREATED/ACTIVE/FROZEN 均可，**弹窗 B 二次确认**） |
| C 修改配置 | 「修改配置」弹窗 C：仅"简介 / 生效期"可编辑（其余只读展示）；请求体**只含白名单字段** |
| D 操作留痕 | 表格（分页）：动作 / 操作者 / 时间 / 结果（SUCCESS/DENIED tag）/ 理由 / 从何值→到何值（`fromValue → toValue`）；Q6-B 时本区块不实施（该步骤改由技术侧配合，剧本同步修订） |

**Tab 2 成员与准入**

| 区块 | 内容 |
| --- | --- |
| A 成员表格（端点 15，分页） | 列：成员 id / 主体编号 / 角色（所有者 / 管理员 / 成员）/ 状态（生效中 / 已退出 / 已移除）/ 加入时间 / 操作；操作 = 「授予管理员」「收回管理员」（端点 16）、「移除」（端点 17，**弹窗 D 理由必填**）、「转移所有权」（端点 19，仅 owner 本人可见） |
| B 本区动作条 | 「邀请成员」（弹窗 E：被邀主体编号 + 理由可选，仅邀请制空间显示）、「提交加入申请」（仅公开/审批制空间显示）、「退出空间」（端点 18） |
| C 准入单表格（端点 13，分页 + 状态筛选） | 列：准入单 id / 主体编号 / 形态（申请 / 邀请）/ 状态（待审批 / 待确认 / 已通过 / 已拒绝 / 已谢绝 / 已取消）/ 操作者 / 时间 / 理由 / 操作；操作 = 「审批」（弹窗 F：通过 / 拒绝 + 理由，**拒绝理由必填**）；**待确认**类由被邀方在"我的邀请"处理 |

**Tab 3 策略**

| 区块 | 内容 |
| --- | --- |
| A 有效策略表格（端点 24，无分页） | 列：条目（`displayName`）/ 生效值 / **来源标注**（继承自平台 / 空间级生效 / 空间覆盖未生效（取严），按 `source` + `provenance` 映射）/ 平台值 / 空间值 / 说明（`note`）/ 红线（tag） |
| B 覆盖提交（端点 23） | 条目下拉（`PolicyCatalog` 三键，标签取 `constants/space.ts`：数据可见范围 / 数据留存期限 / 成员数据导出）+ 取值下拉（该键封闭值域，**前端不做放宽判定**）→ 「提交覆盖」→ 成功后刷新表格；被拒（`1006C0013`）**原样展示后端文案**且表格不变 |
| C 状态提示 | 冻结 / 解散态下的提交按钮给出体验层提示（"空间已冻结 / 已解散，无法调整策略"），提交仍以后端 `1006C0002` 为准 |

### 6.4 页面 3：我的邀请与申请（`/spaces/my-admissions`）

表格（端点 14，分页）：空间 id / 形态 / 状态 / 操作者 / 时间 / 理由 / 操作；「待确认」行提供「接受」「谢绝」（端点 11，谢绝可填理由），成功后刷新并提示"已加入空间 / 已谢绝邀请"。

### 6.5 页面 4：平台策略治理（`/platform-policies`）

表格（端点 22，分页）：条目键 / 显示名 / 值 / 红线 / 状态 / 创建时间 / 更新时间 / 操作（「变更」→ 弹窗 H：值 + 红线，**至少一项变更**）；「新建条目」弹窗 G：键（限 `PolicyCatalog` 三键）+ 值 + 红线标记；错误原样展示（`1006C0012` 键或值不合目录要求）。

### 6.6 文案与样式纪律

1. 状态 / 角色 / 形态 / 来源 / 动作与结果的中文**一律**取 `constants/space.ts`（页面源码中不得出现裸状态中文字面量——由源集守卫断言，T29）；
2. 错误展示统一为 `ElMessage.error(err.message)`（`ApiError.message` = 后端业务文案），**不得改写、不得吞掉**；
3. "空间不存在"与"无权访问"用**同一条**提示常量与样式（非成员同形口径）；
4. 危险动作（解散 / 移除 / 转移 / 收回管理员）一律二次确认（`ElMessageBox.confirm`，沿 `views/review/DetailView.vue` 先例）；取消 = **不发起请求**；
5. 列表与表格空态使用 `el-table` 空态文案，不作报错展示。

### 6.7 弹窗清单

| 编号 | 弹窗 | 关键点 |
| --- | --- | --- |
| A | 创建空间 | 名称（必填，≤128）/ 场景类型 / 参与方范围 / 可见性（三选一）/ 简介（≤512，可选）/ 生效期起止（可选）；提交后按返回刷新列表 |
| B | 解散二次确认 | 文案含"**解散后不可恢复**"与空间名称；取消 = 零请求；确认 = `{confirmDissolve: true}` |
| C | 修改配置 | 仅简介 / 生效期（白名单） |
| D | 移除成员 | 理由必填（留空前置拦截，零请求） |
| E | 邀请成员 | 被邀主体编号必填 + 理由可选 |
| F | 审批 | 通过 / 拒绝；**拒绝必填理由** |
| G / H | 平台条目新建 / 变更 | 键不可变更（变更弹窗中键只读）；值与红线至少一项 |
| I | 角色变更 | 授予管理员 / 收回管理员（目标行本人不可操作自己——体验层禁用 + 服务端兜底） |

---

## 7. 测试锚点（T1~T30；映射见任务卡 §三）

**后端（Q6-A）**

| 锚点 | 内容 | 规格依据 |
| --- | --- | --- |
| T1 | 留痕端点权限矩阵：成员 200 / 非成员 403 `1006C0007` **+ 拒绝留痕 1 行** / `platform.operator` 200 / DISSOLVED：owner 200、非 owner 成员 403 | 行为 6 规则 1/2/5 |
| T2 | 分页与排序（`created_at` 倒序、`id` 次序稳定）+ 越界 `pageNum` → `1000C0001` + 出参字段白名单（无敏感字段） | ADR-005 §3.2 |
| T3 | 空间不存在 → `1006C0004`；不公开空间对非成员按同形口径（与端点 8 一致） | 行为 6 规则 1/3 |
| T4 | 留痕内容覆盖三类：创建（CREATE，SUCCESS）、越权拒绝（ACCESS_DENIED，DENIED + 理由）、状态/配置变更（含 `fromValue → toValue`） | 行为 1 规则 4 / 行为 2 规则 6 / 行为 4 规则 3 |

**前端**

| 锚点 | 内容 | 剧本步骤 |
| --- | --- | --- |
| T5 | 列表渲染（字段逐列）+ 分页 + 空态文案 | C-2.3 S1-1 |
| T6 | 检索传参（keyword）与重置 | C-2.3 S1-1 |
| T7 | 创建成功 → 列表刷新且状态"已创建（未启用）" | C-2.1 S1-2、S1-6 |
| T8 | 未入驻被拒：**统一业务文案**（与"空间不存在"同一条提示常量） | C-2.1 S1-1 |
| T9 | 重名 / 空白归一化重名被拒：文案原样展示 | C-2.1 S1-4、S1-5 |
| T10 | 概览字段渲染（所有者主体编号 / 状态 / 创建时间） | C-2.1 S1-3 |
| T11 | 生命周期按钮按状态显隐（体验层）+ 点击调用正确端点（2/3/4） | C-2.1 S2-2/S2-3、S3-1/S3-3、C-2.2 S1-10/S1-12、S3-4/S3-5 |
| T12 | 解散两段式：取消 → **零请求**；确认 → 请求体 `confirmDissolve === true` | C-2.1 S3-4 |
| T13 | 配置变更：请求体**只含** `intro`/`effectiveFrom`/`effectiveTo`（多余字段零出现） | C-2.1 S2-4 |
| T14 | 成员表格渲染（角色 / 状态 / 加入时间） | C-2.2 S1-1/S1-3 |
| T15 | 邀请成功：提示"待被邀方确认"，且 `alreadyMember=false` 时不出现成员行 | C-2.2 S1-1、S1-3 |
| T16 | 提交申请 → 状态"待审批"（未直接成为成员） | C-2.2 S1-6/S1-8 |
| T17 | 审批拒绝：理由留空 → 前置拦截（**零请求**）；填写 → 成功且界面显示理由 | C-2.2 S1-9 |
| T18 | 我的邀请：接受 → 成为成员；谢绝 → 状态"已谢绝"；均为本人身份 | C-2.2 S1-2 |
| T19 | 角色授予 / 收回：调用端点 16 + 刷新后的角色变化 | C-2.2 S2-1 |
| T20 | 移除成员：理由留空 → 前置拦截（**零请求**）；填写 → 成功 | C-2.2 S3-2 |
| T21 | 退出空间：成功；OWNER 行被拒 → **如实展示** `1006C0009` 文案 | C-2.2 S3-1、S3-3 |
| T22 | 所有权转移：目标为成员行 id；成功后双行状态刷新 | 行为 5 规则 3 |
| T23 | 有效策略表格：三态来源标注（`INHERITED` / `SPACE_EFFECTIVE` / `SPACE_NOT_EFFECTIVE_TAKE_STRICTER`）+ 红线 tag 渲染 | C-2.3 S2-1、S2-5 |
| T24 | 覆盖提交：值域下拉来自目录常量；成功后表格刷新且来源标注变化 | C-2.3 S2-3、S2-4 |
| T25 | 放宽被拒 `1006C0013`：文案原样展示 + **表格不变**（不得乐观更新） | C-2.3 S2-2 |
| T26 | 平台治理：列表 / 新建 / 变更（至少一项变更）+ 错误原样展示 | C-2.3 S2（演示前就绪）、3.2.5 承接项③ |
| T27 | **演示身份**：普通档请求头 = `applicant`（**断言不含 `platform.operator`**）；运营档 = `applicant,platform.operator`；主体编号写入 localStorage；空主体 → 零请求 | C-2.1 S1-1/S2-5/S3-7、C-2.2 S2-5、C-2.3 S1-5/S3-6 |
| T28 | 路由与菜单：admin 菜单 10 项 / user 5 项；`/spaces`、`/spaces/:id`、`/spaces/my-admissions`、`/platform-policies` 权限守卫拦截（user → `/dashboard?denied=1`） | 界面可达性 |
| T29 | 源集守卫：① 空间视图源集无裸状态中文字面量（须经 `constants/space.ts`）；② 无 `fetch(` 直连；③ `platform.operator` 只出现在 `api/space.ts` 与 `stores/demoIdentity.ts` | §6.6；lofi Q7 |
| T30 | 非成员同形提示：`1006C0004` 分支与无权分支共用同一提示常量与样式（反向探针：改文案必红） | 行为 6 规则 1/3 |

---

## 8. 边界值与异常行为

1. **必填前置**：主体编号（演示身份）、空间名称、场景类型、参与方范围、可见性、邀请主体编号、移除理由、审批拒绝理由——均为**前置拦截（零请求）** + 后端兜底；
2. **长度门槛**：名称 ≤128（归一化后）、简介 ≤512、理由 ≤256（后端既有口径，前端 `maxlength` 对齐）；
3. **空态与无结果**：列表空 / 成员空 / 准入单空 / 策略空 / 留痕空 → 一律空态文案（非报错）；
4. **同形口径**：非成员访问非公开空间 = `1006C0004` → 与"无权"共用同一提示（防存在性探测）；
5. **乐观更新的禁止面**：所有写动作**不**先改本地数据，一律以响应为准刷新（尤其红线放宽被拒）；
6. **状态门槛的体验层提示**：CREATED（未启用）不可邀请/申请 → 按钮提示"空间未启用"；FROZEN → "空间已冻结"；DISSOLVED → "空间已解散"；均仍以后端返回为准；
7. **分页越界**：`pageNum` 超界 → 后端 `1000C0001` 原样展示；
8. **网络/服务不可达**：展示 `1006S0001`（主体服务暂不可用）等后端文案；前端不伪造成功；
9. **401/403**：无身份 → 后端按未认证口径返回，界面提示并引导回登录页；`1000C0005` = 无权限执行该操作（原样展示）。

---

## 9. 剧本步骤 → 界面入口映射（交付后"界面核对修订"的对照基线）

| 剧本 | 幕 / 步骤 | 本包界面入口（拟定名称） |
| --- | --- | --- |
| C-2.1 | S1-1/S1-2/S1-4/S1-5/S1-6 | 菜单「逻辑空间」→「创建空间」弹窗 |
| C-2.1 | S1-3 | 菜单「逻辑空间」→ 进入详情 → 概览·操作留痕 |
| C-2.1 | S2-1/S2-5 | 详情 → 成员与准入（邀请 / 生命周期按钮） |
| C-2.1 | S2-2/S2-3/S3-1/S3-3/S3-7 | 详情 → 概览·生命周期按钮（启用 / 冻结 / 恢复） |
| C-2.1 | S2-4 | 详情 → 概览·修改配置 |
| C-2.1 | S3-4/S3-5/S3-6 | 详情 → 概览·解散（二次确认）→「创建空间」重试同名 |
| C-2.2 | S1-1/S1-3/S1-5/S1-6~S1-9/S1-11 | 详情 → 成员与准入（邀请 / 申请 / 审批） |
| C-2.2 | S1-2 | 「我的邀请与申请」→ 接受 |
| C-2.2 | S1-10/S1-12/S3-4/S3-5 | 详情 → 概览·生命周期按钮 |
| C-2.2 | S2-1/S2-3/S2-4/S2-6 | 详情 → 成员与准入（角色授予 / 邀请）；越权步骤以界面报错为观察点 |
| C-2.2 | S2-2/S2-5 | 同上（**越权被拒**：界面展示后端拒绝文案；绕过界面请求仍由技术侧配合） |
| C-2.2 | S3-1/S3-2/S3-3 | 详情 → 成员与准入（退出 / 移除 / 所有者保护） |
| C-2.3 | S1-1/S1-4 | 「逻辑空间」列表（可见性与检索） |
| C-2.3 | S1-2/S1-3/S1-6 | 技术侧配合（绕过界面请求与留痕核对）；界面侧辅以操作留痕区 |
| C-2.3 | S1-5/S3-6 | 顶栏切"平台运营方"档 → 打开对应空间详情（治理查看） |
| C-2.3 | S2-1~S2-5 | 详情 → 策略（有效策略视图 + 覆盖提交） |
| C-2.3 | S2-6 | 详情 → 概览·操作留痕（与技术侧配合留痕核对） |
| C-2.3 | S3-1/S3-2/S3-4 | 详情 → 概览·生命周期按钮 / 策略区 |
| C-2.3 | S3-5 | 技术侧配合（已解散空间资源访问） |

> 三剧本**业务判定一字不改**；交付后按实际界面名称/路径核对修订并提示 PO 批准（W12）。

---

## 10. 变更登记

| 面 | 变更 |
| --- | --- |
| 契约 | 零 ADR 变更；Q6-A 时在 §1 登记新增只读端点（既有 24 端点零改动） |
| 错误码 | 零新增（复用 `1006C0001`~`1006C0014`、`1000C0001/0002/0005`、`1006S0001`） |
| 枚举 | 零新增（复用 12 枚举） |
| 库表 / 迁移 | 零新增、零迁移（Q6-A 仅新增只读查询；拒绝留痕复用既有动作码 `ACCESS_DENIED`） |
| 依赖 | 前端零新增；后端零新增模块依赖 |
| 配置 | 服务端零改动；`frontend/vite.config.ts` 开发期转发新增两条前缀（`/api/v1/data-spaces`、`/api/v1/platform-policies` → `http://localhost:8083`）；**门禁配置零改动** |
| 前端 | 4 视图 + 2 模块 + 1 store + 2 菜单项/4 路由 + 顶栏控件；既有 2 处测试断言同步 |
| 剧本 | 三剧本界面入口核对修订（业务判定不变，提示 PO 审批）+ C-2.3 S2-3 小修一处（随批请 PO 批准） |

---

## 11. 实施前置检查项

同 lofi §6 七项（编码会话第一步逐项实测留痕，本节不重复）。

---

## 12. 勘误登记（实施期回填）

**（编码/评审期按 `E1…` 顺延登记，格式沿 `WBS-3.2.3-hifi.md` §10 / `WBS-3.2.4-hifi.md` §11 / `WBS-3.2.5-hifi.md` §11 先例）**

| 编号 | 位置 | 原表述 | 实施口径（澄清，非改需求） | 登记 |
| --- | --- | --- | --- | --- |
| **E1** | §7 T3 后半句"不公开空间对非成员按同形口径（与端点 8 一致）" | 表述易被读成"非成员访问不公开空间返 `1006C0004`（沿端点 8）"，与同表 T1 及 §5 权限行"非成员 / 无权 → `1006C0007`（403）"字面张力 | **以 §5 权限行为准**：空间**不存在** → `1006C0004`（404）；**非成员**（无论空间 PUBLIC 或 PRIVATE）→ `1006C0007`（403）+ ACCESS_DENIED 拒绝留痕，**同一码同一文案、不因可见性/存在性变形**（"同形口径"= 非成员响应形态一致，与端点 24 同口径；端点 8 的 `1006C0004` 摘要口径只属端点 8）。已由 `SpaceActionLogIntegrationTest` T3 正反两路钉死 | 编码段 2（2026-09-27 20:4x，日志 `-2041`） |
| **E2** | §7 T30 落点（任务卡 §三 落点表） | T30 全量落 `views/space/SpaceSourceGuard.spec.ts` | **拆两半**：源集守卫半（提示常量值 + 页面不得散写文案 + 反向探针）已落 `SpaceSourceGuard.spec.ts`（7 用例含 T29）；**行为半**（挂载详情页，`1006C0004` 与无权两条失败路径渲染**同一条**提示文案）落 `views/space/DetailView.spec.ts`（T30b）——源集守卫无法覆盖"两条分支渲染同一文案"的行为语义 | 编码段 2（2026-09-27 20:4x，日志 `-2041`） |
