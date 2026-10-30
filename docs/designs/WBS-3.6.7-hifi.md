# WBS-3.6.7 高保真设计（连接器管理界面）——编码契约

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（✅ 2026-10-30 编排师一次确认"全部按建议" = Q1~Q7 全采 A + D1 不拆分；编码契约即时生效）** |
| 任务卡 | `docs/tasks/WBS-3.6.7-连接器管理界面-2026-10-30.md`；低保真 `docs/designs/WBS-3.6.7-lofi.md`；原型 `docs/designs/WBS-3.6.7-原型-连接器管理界面.html` |
| 契约地位 | 实现与本契约不一致 = 打回项（章程 2.6）；标注「**编码段实测锚定**」的字段以后端代码 / 掤针实测为准，偏差走 §10 勘误登记 |
| 确认 | ✅ **2026-10-30 21:50 编排师回复"全部按建议"**：Q1~Q7 采建议口径 A + D1 不拆分；本文件 V1.0 编码契约即时生效；lofi 同批 V1.0；任务卡 §二 / §四 同步留痕 |
| 上游 | 规格 `docs/specs/C-6.1-6.3-接入连接器.md` V1.0；ADR-027（注册核验契约）/ ADR-028（生命周期契约）/ ADR-029（源接入契约）/ ADR-030（三类适配器）/ ADR-005（API 规范）/ ADR-016 §2.7（演示期安全边界）/ ADR-006 / ADR-015（国密唯一入口——界面零接触） |

## 1. 接口契约表（既有 20 + 新增 0~1）

> **总原则**：只调用 `connector-service` 对外端点；`/internal/**` 一律不调用（N8）。连接器域 3.6.3~3.6.6 **未启用幂等键**（无 `common-idempotency` 依赖、无 `X-Idempotency-Key` 要求），界面**不做假幂等**；写操作按钮 in-flight 禁用仅为防误触，不构成重放语义。路径段编号一律 `encodeURIComponent`。

### 1.1 注册申请与核验（`ConnectorRegistrationController`，`/api/v1/connectors`）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| R1 | POST | `/registrations` | `connector.register` | body `{connectorName, connectorType:'STANDARD', connectorVersion}`；身份取 `AuthContext.subject()` | `RegistrationView`（待核验；`connectorNo=null`） |
| R2 | PUT | `/registrations/{applicationNo}` | `connector.register` | 同上三要素；仅属主 + 仅 `PENDING_REVIEW` | `RegistrationView` |
| R3 | GET | `/registrations/mine?pageNum&pageSize` | `connector.read` | 按当前主体；**全部状态**（含 `CANCELLED` 历史） | `PageResult<RegistrationView>` |
| R4 | GET | `/review/queue?pageNum&pageSize` | `connector.review` | 固定 `PENDING_REVIEW`，申请时间升序 | `PageResult<RegistrationView>` |
| R5 | POST | `/registrations/{applicationNo}/verification` | `connector.review` | 空 body；三查（主体资格 → DID → 信息完整） | `VerificationResultView`（`verified=false` 也是 **HTTP 200 业务结论**） |
| R6 | DELETE | `/registrations/{applicationNo}` | `connector.cancel` | 撤回待核验申请；仅属主 + 仅 `PENDING_REVIEW` | `RegistrationView`（`status=CANCELLED`，`connectorNo=null`） |

### 1.2 读面、健康度与绑定（`ConnectorController`，`/api/v1/connectors`）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| Q1 | GET | `/mine?pageNum&pageSize` | `connector.read` | 当前主体；**仅 `REGISTERED`** 摘要 | `PageResult<ConnectorView>`（`did/didStatus=null`；`online` 由服务端读时派生） |
| Q2 | GET | `/{connectorNo}/binding` | `connector.read` | 属主或 admin；**实时**查 DID 域；查验留痕 `connector.binding.read` | `BindingView`（`bindingStatus/did/boundAt/didStatus/verified`） |
| Q3 | GET | `/{connectorNo}/action-logs?pageNum&pageSize` | `connector.read` | 属主或 admin；读取零新增留痕 | `PageResult<ActionLogView>` |
| Q4 | POST | `/{connectorNo}/heartbeats` | `connector.heartbeat` | 空 body；仅属主 + 仅 `REGISTERED` | `ConnectorView`（`online` / `lastHeartbeatAt` 刷新；`didStatus=null`） |
| Q5 | GET | `/{connectorNo}` | `connector.read` | 属主或 admin；**实时**查 DID 域（不可用 → 503） | `ConnectorView` |

> **本卡不调用 Q5**：详情页以生命周期聚合读（L6）为单一数据源，避免进页即依赖 DID 服务；DID 实时状态经 Q2「绑定查验」显式触发（也符合行为 8「查验动作留痕」语义）。

### 1.3 生命周期（`ConnectorLifecycleController` + `ConnectorUpgradeController`，`/api/v1/connectors`）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| L1 | GET | `/{connectorNo}/lifecycle` | `connector.read` | 属主或 admin；零跨域调用；注销后仍可读 | `LifecycleView`（见 §2.1；含版本链 / 绑定状态 / 在途占用 / 注销时间） |
| L2 | POST | `/{connectorNo}/upgrades` | `connector.upgrade` | body `{toVersion, compatibility:'COMPATIBLE'|'INCOMPATIBLE'}`；仅属主 + 仅 `REGISTERED` | `VersionChangeView`（兼容 = `APPLIED`；不兼容 = `PENDING_CONFIRM`） |
| L3 | GET | `/upgrades/pending?pageNum&pageSize` | `connector.upgrade.confirm` | 运营方；提交时间升序 | `PageResult<VersionChangeView>` |
| L4 | POST | `/{connectorNo}/upgrades/{upgradeNo}/confirmation` | `connector.upgrade.confirm` | 运营方显式确认；仅 `PENDING_CONFIRM` | `VersionChangeView`（`APPLIED` + `confirmedBy/appliedAt`） |
| L5 | POST | `/{connectorNo}/cancellation` | `connector.cancel` | 属主或 admin；仅 `REGISTERED`；在途占用 > 0 → `1010C0008` | `LifecycleView`（`CANCELLED`） |
| L6 | GET | `/{connectorNo}/upgrades?pageNum&pageSize` | `connector.read` | 属主或 admin；时间倒序 | `PageResult<VersionChangeView>` —— **本卡不调用**（L1 已含全量版本链，低频操作） |

### 1.4 源接入（`ConnectorSourceController`，`/api/v1/connectors`；Q4-A 生效时使用）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| S1 | GET | `/{connectorNo}/sources?pageNum&pageSize` | `connector.read` | 属主（界面仅普通档调用）；清单**不含**自检结论 | `PageResult<SourceView>`（无 `lastCheck`） |
| S2 | GET | `/{connectorNo}/sources/{sourceNo}` | `connector.read` | 属主；详情含最近一次自检 | `SourceView`（含 `lastCheck`，可空） |
| S3 | POST | `/{connectorNo}/sources` | `connector.source.manage` | body `{sourceType, endpoint, credential}`；凭据仅请求侧 | `SourceView`（仅掩码） |
| S4 | PUT | `/{connectorNo}/sources/{sourceNo}` | `connector.source.manage` | `{endpoint, credential?}`；凭据空 = 不变 | `SourceView`（仅掩码） |
| S5 | POST | `/{connectorNo}/sources/{sourceNo}/checks` | `connector.source.manage` | 空 body；只读探测 | `SourceCheckView`（200 业务结论；失败含 `failureType` + `message`） |

### 1.5 运营方治理列表（**Q3-A 生效时新增 1 个加性只读端点**）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| G1 | GET | `/governance/connectors?status&pageNum&pageSize` | `connector.review` | `status` 可空（空 = 三态全部）；分页；运营方枚举 | `PageResult<ConnectorView>`（`summary` 摘要：`did/didStatus=null`、`verified=false`；`online` 读时派生；按 `createdAt DESC, id DESC`） |

> **G1 实现边界**：`ConnectorGovernanceListController`（或并入 `ConnectorController` 同级路径）+ `ConnectorQueryService.governanceConnectors(status, page)` / `countGovernanceConnectors(status)` → 复用 `ConnectorRepository.findRegistrations(null, status, offset, limit)` / `countRegistrations(null, status)` 与 `ConnectorView.summary(registration, online)`；**零新表 / 零新错误码 / 零迁移 / 零权限点 / 零 `application.yml` 改动**。`status` 参数按 `String` 接收并手工解析（空 / 空白 = 不过滤；非法值捕获 `IllegalArgumentException` → `ConnectorBizException(1010C0001)`），**不把枚举直接绑定到 `@RequestParam`**——common `GlobalExceptionHandler` 未覆盖类型转换异常，直接绑定非法值会落入兜底 500，违背「参数不合法 = 400 可读」口径。

### 1.6 明确不调用

- `GET /api/v1/connectors/internal/connectors/{connectorNo}`、`POST/DELETE/GET /internal/{connectorNo}/occupancies**`、`POST /internal/{connectorNo}/sources/{sourceNo}/reads`——**内部面零调用**（服务身份 `connector-internal` 不得出现在任何前端角色头；N8）；
- `GET /{connectorNo}`（Q5）与 `GET /{connectorNo}/upgrades`（L6）——本卡以 L1 聚合读替代，减少 DID 依赖与重复请求；
- 任何网关 / 交付域 / 计量 / 存证端点——归技术侧（N1）。

## 2. 前端模块与类型

### 2.1 `src/api/connector.ts`（21 函数）

- 工厂：`const connectorJson = makeDomainJson(connectorRolesHeader)`（沿 DB-41 收敛模式；空主体前置拦截零请求）。
- 写函数统一返回后端视图；**无幂等键参数**。
- 函数清单：

```ts
// 注册申请与核验
applyConnector(body: { connectorName: string; connectorType: 'STANDARD'; connectorVersion: string }): Promise<RegistrationRecord>
amendConnector(applicationNo: string, body: { connectorName; connectorType; connectorVersion }): Promise<RegistrationRecord>
listMyRegistrations(pageNum: number, pageSize: number): Promise<PageData<RegistrationRecord>>
listReviewQueue(pageNum: number, pageSize: number): Promise<PageData<RegistrationRecord>>
verifyRegistration(applicationNo: string): Promise<VerificationResult>
withdrawRegistration(applicationNo: string): Promise<RegistrationRecord>
// 读面 / 健康度 / 绑定 / 留痕
listMyConnectors(pageNum: number, pageSize: number): Promise<PageData<ConnectorRecord>>
heartbeat(connectorNo: string): Promise<ConnectorRecord>
getBinding(connectorNo: string): Promise<BindingRecord>
listActionLogs(connectorNo: string, pageNum: number, pageSize: number): Promise<PageData<ActionLogRecord>>
getLifecycle(connectorNo: string): Promise<LifecycleRecord>
// 生命周期
requestUpgrade(connectorNo: string, body: { toVersion: string; compatibility: 'COMPATIBLE' | 'INCOMPATIBLE' }): Promise<VersionChangeRecord>
listPendingUpgrades(pageNum: number, pageSize: number): Promise<PageData<VersionChangeRecord>>
confirmUpgrade(connectorNo: string, upgradeNo: string): Promise<VersionChangeRecord>
cancelConnector(connectorNo: string): Promise<LifecycleRecord>
// 源接入
listSources(connectorNo: string, pageNum: number, pageSize: number): Promise<PageData<SourceRecord>>
getSource(connectorNo: string, sourceNo: string): Promise<SourceRecord>
registerSource(connectorNo: string, body: { sourceType: SourceTypeCode; endpoint: string; credential: string }): Promise<SourceRecord>
amendSource(connectorNo: string, sourceNo: string, body: { endpoint: string; credential?: string }): Promise<SourceRecord>
checkSource(connectorNo: string, sourceNo: string): Promise<SourceCheckRecord>
// 运营方治理列表（Q3-A）
listGovernanceConnectors(status: ConnectorStatus | '', pageNum: number, pageSize: number): Promise<PageData<ConnectorRecord>>
```

- 类型（字段与后端 DTO **逐字对齐**；`null` 语义显式）：

```ts
export type ConnectorStatus = 'PENDING_REVIEW' | 'REGISTERED' | 'CANCELLED'
export type SourceTypeCode = 'GOV_EXCHANGE' | 'DATABASE' | 'OBJECT_STORAGE' | 'API_GATEWAY'

export interface RegistrationRecord {
  applicationNo: string; connectorNo: string | null; subjectNo: string;
  connectorName: string; connectorType: string; connectorVersion: string;
  status: ConnectorStatus; lastRejectionCode: string | null; lastRejectionReason: string | null;
  createdAt: string; updatedAt: string
}
export interface ConnectorRecord {
  // 勘误 E1（编码段实测）：治理列表含待核验档案（summary 直接取申请行），connectorNo 可为 null。
  connectorNo: string | null; subjectNo: string; connectorName: string; connectorType: string;
  connectorVersion: string; status: ConnectorStatus;
  did: string | null; didStatus: string | null; verified: boolean; online: boolean;
  lastHeartbeatAt: string | null; updatedAt: string
}
export interface BindingRecord {
  connectorNo: string; subjectNo: string; did: string; bindingStatus: 'ACTIVE' | 'RELEASED';
  boundAt: string; didStatus: string; verified: boolean
}
export interface VerificationResult {
  applicationNo: string; verified: boolean; failedCheck: 'SUBJECT_ADMISSION' | 'DID_STATUS' | 'COMPLETENESS' | null;
  reasonCode: string | null; reason: string | null; connectorNo: string | null;
  status: ConnectorStatus; binding: BindingRecord | null
}
export interface LifecycleRecord {
  connectorNo: string; subjectNo: string; connectorName: string; connectorType: string;
  connectorVersion: string; status: ConnectorStatus; online: boolean;
  lastHeartbeatAt: string | null; cancelledAt: string | null;
  bindingStatus: 'ACTIVE' | 'RELEASED' | null; did: string | null;
  boundAt: string | null; releasedAt: string | null; inProgressJobs: number;
  pendingUpgrade: boolean; versionHistory: VersionChangeRecord[]; updatedAt: string
}
export interface VersionChangeRecord {
  upgradeNo: string; connectorNo: string; subjectNo: string;
  fromVersion: string; toVersion: string; compatibility: 'COMPATIBLE' | 'INCOMPATIBLE' | null;
  status: 'PENDING_CONFIRM' | 'APPLIED' | 'VOIDED'; requestedBy: string;
  confirmedBy: string | null; appliedAt: string | null; createdAt: string
}
export interface ActionLogRecord {
  applicationNo: string | null; connectorNo: string | null; operatorNo: string;
  action: string; result: 'SUCCESS' | 'DENIED' | 'FAILURE'; reasonCode: string | null;
  detail: string | null; createdAt: string
}
export interface CheckSummary {
  passed: boolean; failureType?: 'NETWORK_UNREACHABLE' | 'INVALID_CREDENTIAL' | 'INSUFFICIENT_PERMISSION' | null;
  message: string; checkedAt: string
}
export interface SourceRecord {
  sourceNo: string; connectorNo: string; sourceType: SourceTypeCode; endpoint: string;
  credentialRef: string; credentialMasked: string; createdAt: string; updatedAt: string;
  lastCheck?: CheckSummary
}
export interface SourceCheckRecord {
  sourceNo: string; passed: boolean;
  failureType?: 'NETWORK_UNREACHABLE' | 'INVALID_CREDENTIAL' | 'INSUFFICIENT_PERMISSION' | null;
  message: string; checkedAt: string
}
```

> **编码段实测锚定**：枚举值集与可空性以 `ConnectorViews.java` / `ConnectorLifecycleViews.java` / `ConnectorSourceViews.java` 实读为准；`ConnectorView.summary` 的 `did/didStatus=null`、`verified=false` 是既有设计（列表不做逐条跨域），界面不得把 `verified=false` 当作「DID 无效」展示。

### 2.2 `src/constants/connector.ts`

- `CONNECTOR_STATUS_LABELS / TAG_TYPES`：`PENDING_REVIEW` 待核验（warning）/ `REGISTERED` 已注册（success）/ `CANCELLED` 已注销（info）。
- `CONNECTOR_TYPE_LABELS`：`STANDARD` 标准连接器；`CONNECTOR_TYPE_OPTIONS` = 仅一项。
- `ONLINE_LABELS`：`true` 在线 / `false` 离线；`onlineTagType(status, online)` = 非 `REGISTERED` 一律 `info` 且文案「—」（健康度只对已注册态有意义）。
- `BINDING_STATUS_LABELS`：`ACTIVE` 已绑定 / `RELEASED` 已解除。
- `COMPATIBILITY_LABELS`：`COMPATIBLE` 兼容 / `INCOMPATIBLE` 不兼容；`COMPATIBILITY_OPTIONS`。
- `VERSION_STATUS_LABELS / TAG_TYPES`：`PENDING_CONFIRM` 待确认（warning）/ `APPLIED` 已生效（success）/ `VOIDED` 已作废（info）。
- `SOURCE_TYPE_LABELS`：`GOV_EXCHANGE` 政务共享交换平台（模拟源）/ `DATABASE` 数据库 / `OBJECT_STORAGE` 对象存储 / `API_GATEWAY` API 网关；`SOURCE_TYPE_OPTIONS` 四项全量。
- `CHECK_FAILURE_LABELS`：`NETWORK_UNREACHABLE` 网络不可达 / `INVALID_CREDENTIAL` 凭据无效 / `INSUFFICIENT_PERMISSION` 权限不足。
- `VERIFICATION_CHECK_LABELS`：`SUBJECT_ADMISSION` 主体资格 / `DID_STATUS` DID 有效性 / `COMPLETENESS` 信息完整性。
- `AUDIT_ACTION_LABELS`（18 个）：`connector.apply` 提交申请 / `connector.amend` 补正 / `connector.verify.approved` 核验通过 / `connector.verify.rejected` 核验拒绝 / `connector.binding.read` 绑定查验 / `connector.heartbeat` 心跳上报 / `connector.denied` 拒绝 / `connector.upgrade.requested` 升级登记（不兼容待确认）/ `connector.upgrade.applied` 升级生效 / `connector.upgrade.confirmed` 升级确认 / `connector.cancelled` 注销 / `connector.occupancy.register` 在途作业登记 / `connector.occupancy.release` 在途作业释放 / `source.register` 源登记 / `source.amend` 源变更 / `source.check` 源自检 / `source.read` 源读取 / `source.denied` 源拒绝（未知动作原样显示 code）。
- `AUDIT_RESULT_LABELS`：`SUCCESS` 成功 / `DENIED` 拒绝。
- 提示文案：`HEALTH_THRESHOLD_HINT`（在线 = 最近心跳未超过服务端阈值，默认 5 分钟；状态以服务端返回为准）、`SOURCE_SELF_CHECK_HINT`（自检通过仅证明连得上，不构成业务授权；读取仍受平台判定约束）、`GOV_SOURCE_MOCK_HINT`（政务共享交换平台 V1.0 为标准报文模拟源；真实政务平台生产对接属部署实施期事项）、`SOURCE_CREDENTIAL_MASK_HINT`（凭据仅以掩码展示，提交后不再回显）、`BINDING_CHECK_TRACE_HINT`（绑定查验将产生服务端留痕）、`OPERATOR_SOURCE_HIDDEN_HINT`（运营档不展示源接入区——DB-88 口径挂起，仅属主可见）。
- **错误文案零内置**：一律展示 `ApiError.message`（服务端权威常量）；界面不复制、不改写、不拼接用户输入。

### 2.3 演示身份 / 路由 / 转发

- `stores/demoIdentity.ts` 扩展：

```ts
/** 连接器域页面级角色头（WBS-3.6.7 Q2-A）：普通档 applicant / 运营档 admin。 */
export function connectorRolesHeader(): string {
  return pageRolesHeader('admin', 'applicant')
}
```

硬约束（测试锚 T2）：普通档不得出现 `admin`；任何档位不得出现 `connector-internal`、`connector.internal.read`、`connector.internal.write`。

- `router/index.ts` 新增（声明顺序：两菜单在交付域之后；详情深链在 `/connectors` 之后）：

```ts
{ path: 'connectors', name: 'connector-manage', component: () => import('../views/connector/ConnectorManageView.vue'),
  meta: { title: '连接器管理', menu: true, menuOrder: 20, icon: 'Link' } },
{ path: 'connectors/:connectorNo', name: 'connector-detail',
  component: () => import('../views/connector/ConnectorDetailView.vue'),
  meta: { title: '连接器详情' } },
{ path: 'connector-governance', name: 'connector-governance',
  component: () => import('../views/connector/ConnectorGovernanceView.vue'),
  meta: { title: '连接器治理', menu: true, menuOrder: 21, icon: 'Tools', permission: 'connector.review' } }
```

- `vite.config.ts` 新增键（置于通用 `/api` 之前）：

```ts
'/api/v1/connectors': { target: 'http://localhost:8088', changeOrigin: true },
```

nginx 零改动（部署面无 API 代理；沿 3.2.6 / 3.3.6 / 3.4.7 / 3.5.6 先例）。

- 菜单基线：普通用户 **7 → 8**（+ 连接器管理）；admin **19 → 21**（+ 连接器管理 + 连接器治理）。`MainLayout.spec.ts` 计数与文案断言、`router.spec.ts` 权限路由清单同步。

## 3. 界面说明书

### 3.1 页面一：连接器管理（`/connectors`）

**布局**：页头操作栏（左「申请接入」按钮；右「刷新」）→ `el-tabs` 两个页签 → 各页签过滤栏 + 表格 + 分页。

**Tab1「注册申请」**：
- 过滤：状态下拉（全部 / 待核验 / 已注册 / 已注销；**本页筛选**，明确标注）+ 关键字输入（申请单号 / 名称；本页筛选）+ 重置。
- 列：申请单号 / 连接器编号（空显示「—」）/ 名称 / 类型 / 版本 / 状态 tag / 最近拒绝理由（`lastRejectionReason`，空「—」，超长省略 + tooltip）/ 申请时间 / 更新时间 / 操作。
- 操作：`PENDING_REVIEW` →「补正」+「撤回」；有 `connectorNo` →「查看连接器」（跳详情）；`CANCELLED` → 仅「查看连接器」（若有编号）。
- 空态：「暂无注册申请」；加载失败：`ElMessage.error(err.message)` + 空表（不吞错）。
- 分页：服务端分页，`pageSize=10`；总数 = `PageData.total`。

**Tab2「我的连接器」**：
- 过滤：在线状态下拉（全部 / 在线 / 离线；**本页筛选**）+ 关键字（编号 / 名称；本页筛选）+ 重置。
- 列：编号 / 名称 / 类型 / 当前版本 / 状态 tag / 在线 tag（非 `REGISTERED` 显示「—」）/ 最近心跳（空「—」）/ 更新时间 / 操作。
- 操作：「详情」；`REGISTERED` →「上报心跳」（行内 loading；成功刷新列表并提示「心跳已上报」；失败文案原样）。
- 空态：「暂无已注册连接器」+ 引导「请先在「注册申请」页提交接入申请并经运营方核验」。

**「申请接入」弹窗**（`destroy-on-close`）：
- 字段：名称（文本）/ 类型（下拉，仅「标准连接器」）/ 版本（文本，占位 `1.0.0`）。
- **无前端业务校验**：三要素为空或格式非法均可提交；服务端返回 `1010C0001` 及字段级文案（如「连接器版本不能为空」）→ 弹窗内 `el-alert` 原样展示，可修改重提。
- 成功后：关闭弹窗、刷新 Tab1、`ElMessage.success('申请已受理')`；被拒（`1010C0006` 未入驻）→ 弹窗内原样展示。
- 重复提交防护：按钮 `loading` 直至响应返回（无幂等键；服务端 `1010C0002` 防重）。

**「补正」弹窗**：预填当前三要素；提交 `PUT`；成功后刷新 Tab1；状态门槛失败（`1010C0004`）原样展示。

**「撤回」确认**：`ElMessageBox.confirm` 文案「撤回后该申请将进入已注销终态，且不再出现在运营方待核验队列；是否继续？」→ `DELETE`；成功后刷新 Tab1 + 提示；失败原样展示。

### 3.2 页面二：连接器详情（`/connectors/:connectorNo`，深链）

**数据加载**：进入即调 L1 `getLifecycle`；并行调 Q3 留痕第 1 页；（普通档）S1 源清单第 1 页。L1 失败 → 整页错误态 + 重试按钮；Q3/S1 失败 → 对应分区错误提示，不影响其他分区。

**头部**：返回链接「← 返回连接器管理」/「← 返回连接器治理」（按来源 query `from=governance` 或运营档决定）+ 名称 + 编号 + 状态 tag + 在线 tag（仅 `REGISTERED`）。

**分区 1 基本信息**：编号 / 所属主体 / 名称 / 类型 / 当前版本 / 状态 / 在线 / 最近心跳 / 更新时间 / 注销时间（`cancelledAt`，非空时展示）。字段值一律来自 L1。

**分区 2 健康度**：
- 在线 tag + 最近心跳 + `HEALTH_THRESHOLD_HINT` +「刷新」。
- 「上报心跳」按钮：仅 **普通档且 `REGISTERED`**；成功 → 重新加载 L1；失败原样展示（`1010C0004` 等）。

**分区 3 DID 绑定**：
- 展示 L1 的 `bindingStatus / did / boundAt`；`RELEASED` 时展示 `releasedAt`；无绑定显示「—」。
- 「绑定查验」按钮：**仅 `bindingStatus === 'ACTIVE'` 可用**（`RELEASED` 已无活跃绑定，服务端 Q2 会返回 `1010C0003`，界面禁用并展示解除记录，不制造必然失败的请求）；调 Q2 → 结果面板展示 `didStatus`（`ACTIVE` / `REVOKED` / `PENDING_ISSUE` / `NONE`）+ `verified` + `BINDING_CHECK_TRACE_HINT`；`didStatus != ACTIVE` 时 `verified=false` 红字提示「DID 状态无效」；DID 服务不可用（`1010S0002`）→ 错误文案原样展示，**不冒充结论**。
- 运营档同样可查验（admin 持 `connector.read`）。

**分区 4 版本管理**：
- 当前版本 + `pendingUpgrade=true` 时 `el-alert`「存在待确认的不兼容升级，确认前当前版本继续有效」。
- 「升级登记」按钮：仅 **普通档且 `REGISTERED`**；弹窗字段目标版本（文本，占位当前版本的下一个版本）+ 兼容性（下拉：兼容 / 不兼容）；无前端业务校验，服务端 `1010C0001` / `1010C0009` / `1010C0004` 文案原样展示。
- 版本历史表：`versionHistory`（L1 已倒序）；列：升级单号 / 旧版本 → 新版本 / 兼容性 / 状态 / 提交人 / 提交时间 / 确认人（空「—」）/ 生效时间（空「—」）；空态「暂无版本变更记录」。
- 成功后刷新 L1；不兼容提交成功 → 提示「已登记不兼容升级，待运营方确认后生效」。

**分区 5 源接入（仅普通档；Q4-A）**：
- 可见条件：`!isOperatorMode()`；`REGISTERED` 时可操作；`CANCELLED` 时只读或不展示（本设计：不展示该分区）。
- 提示：`GOV_SOURCE_MOCK_HINT` + `SOURCE_SELF_CHECK_HINT` 常驻。
- 表格列：源编号 / 类型 / 源地址 / 凭据掩码 / 更新时间 / 操作（「自检」「变更」「最近自检」）。
- 「登记源」弹窗：类型（四类）/ 源地址（文本，占位按类型提示）/ 访问凭据（`type=password`）；提交后清空凭据；成功刷新清单；失败（`1010C0001` / `1010C0003` / `1010C0004` / `1010C0010` / `1010S0004`）文案原样展示。
- 「变更」弹窗：源地址预填；凭据留空 = 不变（占位「留空表示不修改」）；成功刷新。
- 「自检」：行内 loading → 结果弹窗（通过 = 绿色「自检通过」；失败 = 失败原因中文标签 + 服务端 `message` + 时间；`checkedAt` 原文）；失败不阻断列表。
- 「最近自检」：调 S2 打开抽屉，展示 `lastCheck`（空 = 「尚未自检」）。
- 运营档：**不渲染源接入区**（表格 / 按钮 / 弹窗全部不出现），仅在详情页顶部显示 `OPERATOR_SOURCE_HIDDEN_HINT` 一行——明确告知口径而非静默缺失；服务端既有语义零改动。

**分区 6 动作留痕**：分页表（`action-logs`）：时间 / 动作中文 / 结果 tag / 原因码 / 操作人 / 详情（省略 + tooltip）；空态「暂无留痕」；读取本身不产生新留痕（Q3 约定）。

**分区 7 注销**：
- 展示 `inProgressJobs`；>0 时提示「存在进行中的交付作业，注销将被拒绝」。
- 「注销连接器」：仅 `REGISTERED` 且（普通档属主 **或** 运营档）；二次确认文案「注销为终态且不可逆，连接器编号终身不复用；是否继续？」→ L5；成功刷新 L1（状态已注销、`cancelledAt`、绑定 `RELEASED`）；`1010C0008` 文案原样展示。
- `CANCELLED` 时所有写动作（心跳 / 升级 / 注销 / 源）隐藏；绑定 / 留痕 / 版本历史仍可读（行为 10-3）。

### 3.3 页面三：连接器治理（`/connector-governance`，运营档）

**Tab1「全平台连接器」**（Q3-A）：
- 过滤：状态下拉（全部 / 待核验 / 已注册 / 已注销；**服务端筛选**）+ 刷新。
- 概览条：「本页在线 X / 离线 Y（仅已注册态计入）」——只统计当前页，文案明确「本页」，不冒充全量统计。
- 列：编号（待核验为空「—」）/ 所属主体 / 名称 / 类型 / 当前版本 / 状态 tag / 在线 tag（非 `REGISTERED` 显示「—」）/ 最近心跳 / 操作。
- 操作：「详情」（有编号时；跳 `/connectors/{no}?from=governance`）。
- 空态「暂无连接器档案」；分页服务端。

**Tab2「注册核验」**：
- 待核验队列（R4），列：申请单号 / 所属主体 / 名称 / 类型 / 版本 / 申请时间 / 操作「核验」。
- 点击核验 → loading → 结论面板（`el-dialog` 或分区）：
  - `verified=true`：绿色「核验通过」+ 连接器编号 + 绑定摘要（DID / 建立时间）+「查看连接器」；
  - `verified=false`：红色「核验拒绝」+ 不通过项中文（`VERIFICATION_CHECK_LABELS`）+ 原因码 + 服务端 `reason` 原样 + 提示「补正后可重新核验」。
- 关闭后刷新队列（拒绝后仍留在队列，可再次核验；通过后移出）。
- 非 `PENDING_REVIEW`（并发已被处理）→ `1010C0004` 原样展示 + 刷新。

**Tab3「升级确认」**：
- 待确认队列（L3），列：升级单号 / 连接器编号 / 所属主体 / 旧版本 → 新版本 / 提交人 / 提交时间 / 操作「确认」+「查看连接器」。
- 「确认」→ 二次确认「确认后新版本立即生效；是否继续？」→ L4；成功刷新队列 + 提示；失败原样。
- 空态「暂无待确认升级」。

**角色口径**：治理页所有动作由服务端 `connector.review` / `connector.upgrade.confirm` / `connector.cancel` 强制；界面显隐不构成权限。普通档误入（若无菜单权限但手输 URL）→ 前端路由守卫跳 dashboard；运营档误入参与方页 → 服务端 fail-closed 原样报错（沿 3.5.6 E-5 口径）。

### 3.4 健康度与版本管理的语义口径

- **在线 = 服务端 `online` 字段**（最近心跳未超 `ctds.connector.health.offline-after`，默认 5 分钟）；界面**不本地计算**、不轮询、不缓存。
- 非 `REGISTERED` 态不展示在线结论（显示「—」）——避免把已注销连接器的历史心跳误读为在线。
- 版本管理 = 当前版本（L1）+ 待确认标记（L1 `pendingUpgrade`）+ 版本链（L1 `versionHistory`）+ 升级登记（L2）+ 运营方确认（L3/L4）；不提供「热升级」「回滚」入口（规格行为 9-1 边界）。
- 不提供「版本下发 / 运行中实例升级」界面（部署动作归实施）。

## 4. 动作矩阵（状态 × 角色；服务端强制，界面仅显隐）

| 状态 | 普通档 · 属主 | 普通档 · 非属主 | 运营档（admin） |
| --- | --- | --- | --- |
| `PENDING_REVIEW`（档案/申请） | 补正 / 撤回 / 查看 | 不可见（同码同文） | 核验（治理 Tab2）/ 治理列表可查 |
| `REGISTERED` | 详情 / 心跳 / 升级登记 / 绑定查验 / 源登记·变更·自检 / 注销 / 留痕 | 不可见 | 详情 / 绑定查验 / 留痕 / 注销（治理列表或详情）；升级确认（治理 Tab3）；**不展示源接入区**（DB-88） |
| `CANCELLED` | 详情 / 留痕 / 版本链 / 绑定解除记录（只读） | 不可见 | 详情 / 留痕 / 版本链（只读） |

> 所有「不可见」均为前端体验层；服务端以 `1010C0003` 同码同文兜底。所有写动作在 `CANCELLED` 态服务端返回 `1010C0004`（界面隐藏入口，不主动制造请求）。

## 5. 标签与文案表（前端常量单一源）

| 类别 | 值 → 中文 |
| --- | --- |
| 连接器状态 | `PENDING_REVIEW` 待核验 / `REGISTERED` 已注册 / `CANCELLED` 已注销 |
| 类型 | `STANDARD` 标准连接器 |
| 在线 | `true` 在线 / `false` 离线（非已注册态「—」） |
| 绑定 | `ACTIVE` 已绑定 / `RELEASED` 已解除 |
| 兼容性 | `COMPATIBLE` 兼容 / `INCOMPATIBLE` 不兼容 |
| 版本状态 | `PENDING_CONFIRM` 待确认 / `APPLIED` 已生效 / `VOIDED` 已作废 |
| 源类型 | `GOV_EXCHANGE` 政务共享交换平台（模拟源）/ `DATABASE` 数据库 / `OBJECT_STORAGE` 对象存储 / `API_GATEWAY` API 网关 |
| 自检失败 | `NETWORK_UNREACHABLE` 网络不可达 / `INVALID_CREDENTIAL` 凭据无效 / `INSUFFICIENT_PERMISSION` 权限不足 |
| 核验项 | `SUBJECT_ADMISSION` 主体资格 / `DID_STATUS` DID 有效性 / `COMPLETENESS` 信息完整性 |
| 留痕结果 | `SUCCESS` 成功 / `DENIED` 拒绝 |
| 留痕动作 | 见 §2.2 `AUDIT_ACTION_LABELS`（18 项；未知 code 原样显示） |

**错误展示纪律**（服务端文案原样，界面零改写）：`1010C0001` 请求参数不合法（含字段级文案）/ `1010C0002` 同主体同名已存在 / `1010C0003` 未找到该记录或无权访问 / `1010C0004` 当前状态不允许该操作 / `1010C0006` 主体未入驻或状态不可接入连接器 / `1010C0008` 存在进行中的交付作业，请先完结或终止后再注销 / `1010C0009` 目标版本与当前版本相同，无需升级 / `1010C0010` 源地址不被允许 / `1010C0011` 该源类型的适配承载尚未开放 / `1010S0001` 主体服务暂不可用 / `1010S0002` DID 服务暂不可用 / `1010S0004` 源凭据保护组件不可用 / `1000C0002` 未认证 / `1000C0001` 参数不合法（分页 / 枚举）。**前端不内置上述文案副本**，测试以服务端响应为断言源。

## 6. 表单与交互细节

1. **零前端业务判定**（N6）：除「演示主体编号为空」前置拦截（`makeDomainJson`，零请求）外，所有必填 / 格式 / 状态 / 资格由服务端判定；非法值可提交（剧本对照步骤可执行）；服务端文案原样展示。
2. **无幂等键**：连接器域端点未启用幂等；界面不得放置「重放演示」类入口；按钮 in-flight 禁用防误触，不宣称幂等。
3. **无乐观更新**：所有写动作成功后重新拉取受影响数据（列表 / L1 / 源清单 / 队列）。
4. **弹窗实例级状态**：`destroy-on-close`；凭据输入提交后清空且不回填；关闭即丢弃。
5. **分页**：`pageSize=10`；`PageData.total` 驱动 `el-pagination`；页码越界由服务端 `1000C0001` 兜底。
6. **本页筛选标注**：`/registrations/mine` 与 `/mine` 无服务端状态 / 在线过滤，前端筛选一律标注「本页筛选」，不得伪装全量。
7. **加载与错误**：整页 / 分区 / 行三级 loading；错误走 `ElMessage.error(err.message)`（403/503/400 同口径），登录失效走既有 `guideToLoginIfAuthFailed`。
8. **导航**：详情页返回按 `from=governance` 返回治理页，否则返回连接器管理；浏览器前进后退正常。
9. **响应式**：沿用现有页面布局（无新样式体系）；表格列宽与既有列表一致；窄屏横向滚动由页面容器承载。
10. **可访问性**：按钮文案完整、非纯图标；确认框 destructive 动作二次确认。

## 7. 规格 / 剧本 → 页面入口映射

| 剧本步骤 | 页面 / 入口 | 承载说明 | 测试锚 |
| --- | --- | --- | --- |
| C-6.1 S1-1 未入驻主体申请 | 连接器管理 →「申请接入」（主体编号切未入驻） | 服务端 `1010C0006` 原样展示 | T4 / T14 |
| C-6.1 S1-2 受理（待核验） | 同上 | 记录出现在「注册申请」Tab | T4 |
| C-6.1 S1-3/4 身份查验 / 跨主体 | —— | **技术侧**（SDK 身份门面；连接器域无对外身份查验端点）——剧本修订标注 | —— |
| C-6.1 S2 六项功能 / 交付 / 使用控制 | —— | **技术侧**（SDK + 平台域；界面不模拟）——剧本修订标注 | —— |
| C-6.1 S2-4 零本体检查 | 全界面 | 源 / 留痕 / 详情均无数据本体字段 | T13 |
| C-6.2 S1-1 核验通过 | 连接器治理 → 注册核验 →「核验」 | 结论面板 + 编号 + 绑定摘要 | T11 |
| C-6.2 S1-2 缺版本被拒 | 连接器管理 →「申请接入」留空版本提交 | 服务端字段级文案「连接器版本不能为空」原样展示 | T4 / T14 |
| C-6.2 S1-3/4 未入驻 / DID 吊销拒绝 | 治理 → 注册核验（对照数据技术侧代造） | 不通过项 + 理由 + 可补正提示 | T11 |
| C-6.2 S1-5 同名重复 | 「申请接入」重复提交 | `1010C0002` 原样 | T4 |
| C-6.2 S1-6 注册记录与版本历史 | 治理 Tab1 + 详情版本区 | 运营方全平台列表（Q3-A）+ 版本链 | T10 / T7 |
| C-6.2 S1-7 取 DID → 验身份 | 详情 → DID 绑定 →「绑定查验」 | 实时状态 + `verified` + 留痕提示 | T6 |
| C-6.2 S1-8 吊销联动 | 同上（对照数据技术侧代造） | `didStatus=REVOKED` + `verified=false` | T6 |
| C-6.2 S1-9 撤回 | 注册申请 Tab →「撤回」 | 状态转已注销、编号为空、同名可重提 | T4 |
| C-6.2 S2-1 兼容升级 | 详情 → 版本管理 →「升级登记」 | 当前版本立即替换 | T7 |
| C-6.2 S2-2 不兼容登记 | 同上 | 待确认提示、当前版本不变 | T7 |
| C-6.2 S2-3 显式确认 | 治理 → 升级确认 →「确认」 | 版本替换 + 确认人 / 时间 | T12 |
| C-6.2 S2-4 版本历史链 | 详情 → 版本管理 | 全链倒序 | T7 |
| C-6.2 S3-1 在途作业拒绝注销 | 详情 →「注销连接器」 | `1010C0008` 原样（在途数据技术侧代造） | T8 |
| C-6.2 S3-2/3/4 注销 / 已注销拒绝 / 编号不复用 | 详情 →「注销连接器」；注册申请 Tab 查历史 | 终态、绑定解除、历史保留、新编号；**S3-3「尝试业务动作被拒」由技术侧端点验证**（界面侧证明写入口全部消失，服务端 `1010C0004` 兜底） | T8 / T4 |
| C-6.3 S1-1~7 源登记 / 变更 / 自检 | 详情 → 源接入区（普通档） | 四类源、掩码、三类失败原因、政务模拟源标注 | T9 |
| C-6.3 S2-1~4 读取链 / 越界 / 未登记 / 跨归属 | —— | **技术侧**（内部读取端点；DB-88 未决，运营档不展示源区）——剧本修订标注 | —— |
| C-6.3 S2-5 无明文 | 源接入区全链 | 仅掩码；测试锚定 | T13 |

> 三剧本「界面入口」整段替换与承载说明属 Q6-A 交付物；**业务判定一字不改**，提示 PO 批准留痕。

## 8. 测试锚点

### 8.1 前端（T1~T15，约 55~75 例）

| 组 | 断言要点 |
| --- | --- |
| T1 API 契约 | 21 函数路径 / 方法 / `pageQuery` / `encodeURIComponent`；`makeDomainJson(connectorRolesHeader)`；无幂等键头；写函数 body 逐字；类型字段与 DTO 对齐 |
| T2 演示身份 | 普通档 `applicant`、运营档 `admin`；普通档不含 `admin`；任何档位不含 `connector-internal` / internal 权限；空主体零请求 |
| T3 路由与菜单 | 两菜单 + 一深链；menuOrder 20/21；治理页 `connector.review`；深链不吞噬列表；菜单计数 8 / 21；`router.spec` 权限清单 |
| T4 注册申请 | 列表渲染 / 状态 tag / 拒绝理由；申请成功与被拒（`1010C0001` / `1010C0006`）文案；补正预填与提交；撤回确认；分页；空态 |
| T5 我的连接器 | 列表 / 在线 tag / 最近心跳；心跳成功刷新；失败原样；详情导航 |
| T6 详情基础 | L1 全字段渲染；注销时间；在线「—」口径；绑定查验成功（ACTIVE）/ DID 非 ACTIVE（`verified=false`）/ `1010S0002` 原样；`RELEASED` 态按钮禁用且不请求；留痕分页与动作中文；L1 失败整页错误态 |
| T7 版本管理 | 升级弹窗字段；兼容提交刷新；不兼容待确认提示；`1010C0009` / `1010C0001` 原样；版本链顺序与确认人 / 生效时间；运营档隐藏升级入口 |
| T8 注销 | 二次确认；在途拒绝 `1010C0008` 原样；成功刷新为已注销；已注销态写入口消失；历史 / 绑定解除可见；运营档可注销 |
| T9 源接入 | 普通档可见 / 运营档提示行；四类源选项（含「模拟源」标签）；登记成功刷新；凭据密码框且成功后清空；列表仅掩码；变更凭据留空；自检通过 / 三类失败原因；最近自检抽屉；`1010C0010` / `1010S0004` 原样 |
| T10 治理列表 | 状态服务端筛选参数；在线 tag 与「—」；本页概览条；详情跳转带 `from=governance`；空态 / 分页 |
| T11 注册核验 | 队列渲染；核验通过结论（编号 / 绑定）；拒绝结论（不通过项 / 理由）；`1010C0004` 并发原样；关闭刷新 |
| T12 升级确认 | 待确认队列渲染；确认二次确认；成功刷新；失败原样；详情跳转 |
| T13 SourceGuard | 静态扫描：视图 / api 零 `/internal/`；零 `credential` 明文渲染（仅 `credentialMasked`）；零 `connector-internal`；零 `X-Idempotency-Key`；零服务端错误文案副本；零数据本体字段 |
| T14 错误展示 | 关键码（`1010C0001/2/3/4/6/8/9`、`1010S0001/2/4`、`1000C0002`）在对应动作上原样展示、不改写 |
| T15 路由守卫 | 普通用户访问治理路由 → 重定向 dashboard；admin 放行；深链无权限点；登录守卫前置（沿既有） |

### 8.2 后端（T16，Q3-A 生效时，约 5~7 例）

| 组 | 断言要点 |
| --- | --- |
| T16 治理列表 | admin 全量三态；`status` 过滤；`applicant` → `1000C0005`；`online` 读时派生（新心跳 true / 陈旧 false）；分页边界 `1000C0001`；`status` 非法值 → `1010C0001`（400，不得落 500）；`summary` 的 `did/didStatus=null`、`verified=false` 不被误用；零迁移 / 零新码（`ConnectorErrorCodes` 零改动） |

### 8.3 红相归档

编码段测试先行：前端新增 spec 先红 → 实现转绿；后端 T16 先红 → 实现转绿；红相日志归档 `build-output/w367-red-*`（沿界面包先例）。

## 9. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 == origin，工作树干净；`main` 未动；3.6.6 收口批（`10f45cc`）在本分支梢。
2. 前端基线：`npm test` **548/548** + `typecheck` / `lint` / `build` 全 EXIT=0（实测复跑，数字以当次为准并回填任务卡）。
3. 后端基线：`mvn -B -ntp -pl services/connector-service test` **240 例**全绿 + `checkstyle:check` 0 违规；全仓 `compile` 成功。
4. 端点掤针：连接器域 20 个既有端点角色矩阵（applicant / admin / 无角色）实探，逐字记录关键错误码与文案；确认 `/internal/**` 不被前端调用。
5. 服务与演示环境：MySQL / MinIO / Redis 容器 + subject 8080 + did 8082 + connector 8088 + 前端 5173；connector 环境变量（`CTDS_CONNECTOR_SUBJECT_BASEURL` / `CTDS_CONNECTOR_DID_BASEURL` / `CTDS_SM4_KEY_FILE` / DB 口令由人侧提供，不落盘）；DID 对演示主体有效；源自检目标可达（是否开 `allow-private-endpoint` 由人侧决定并留痕）。
6. 静态事实复核：`/mine` 仅 `REGISTERED`；`/registrations/mine` 全状态；`lifecycle` 含版本链 / 绑定 / 在途；`source` 清单不含 `lastCheck`；连接器端点无幂等；`admin` 无 `connector.source.manage`；DB-88 挂起口径不变。
7. 门禁基线：`gates-config.json` V1.12，`ConfigSha256 955bdbba…2ee2`，本卡零改动。
8. 菜单 / 路由基线：普通用户 7、admin 19（实测复跑 `MainLayout.spec` / `router.spec`）；确认新增 20/21 与 8/21 预期。

## 10. 勘误表（编码段 / 评审段回填）

| # | 设计原文 | 实测 / 修正 | 来源 |
| --- | --- | --- | --- |
| E1 | §2.1 `ConnectorRecord.connectorNo: string` | 治理列表（G1）含待核验档案，`ConnectorView.summary` 直接取申请行 → **`connectorNo` 可为 null**；前端类型改 `string \| null`，列表详情入口以 `v-if` 守卫，导航函数空值早退 | 编码段 T10 测试实测（待核验行无编号） |
| E2 | §3.2 源登记弹窗「类型下拉四类」 | 下拉未展开时选项文案不在 DOM（EP 懒挂载）→ 弹窗增一行「支持类型：…」（由 `SOURCE_TYPE_OPTIONS` 标签拼装，单一常量源），既满足可读性也锚定四类源展示 | 编码段 T9 测试 |
| E3 | §9 前置检查第 3 项「`mvn -pl services/connector-service test`」 | 本地仓库 `connector-sdk` 安装件为 10-27 旧版（缺 3.6.6 三类适配器）→ 先 `mvn -pl connector-sdk install -DskipTests` 再跑服务测试；门禁配置零改动 | 编码段前置检查实测 |
| E4 | §9 前置检查第 4/5 项「20 端点角色矩阵实探 + 服务与演示环境」 | 编码段当时三容器在线（3306/9000/6379）但 subject 8080 / did 8082 / connector 8088 / 前端 5173 未启动，且服务环境变量由人侧提供 → **静态矩阵已逐端点核对（Controller `@RequirePermission` + `application.yml` 角色映射）**；**实探与界面走查合并到验收段执行**（需人侧拉起服务并留痕） | 编码段前置检查实测 |
| E5 | §2.2 / §5 常量清单 | 实现新增并通过评审：留痕结果补 **`FAILURE`**（后端对源自检 / 源读取失败写入，action-logs 不过滤 → 前端原会显示英文码；§2.2 类型块同步改 `'SUCCESS' \| 'DENIED' \| 'FAILURE'`）；`CheckSummary` / `SourceCheckRecord.failureType` 改可选（后端 `@JsonInclude(NON_NULL)`，通过时字段缺省）；新增 `DID_STATUS_LABELS` / 成功提示（补正 / 撤回 / 升级登记 / 注销 / 源登记 / 源变更 / 自检失败）/ 空态与概览后缀 / 「全部」筛选选项 / 返回入口文案；均属展示层常量，不构成业务规则 | 评审③ P1-1/P1-2 + 编码段 |
| E6 | §1.1 R6 归属 / §3.2 升级占位 | R6 撤回实际在 `ConnectorLifecycleController`（非 `ConnectorRegistrationController`）；升级弹窗占位改为**当前版本下一补丁版本**（`nextVersionHint` 计算，无版本时给示例）；源登记弹窗增加「支持类型」提示行（E2 同源） | 评审① P2 / 评审③ P2 |

## 11. 边界与纪律复述

- 只做已交付端点的界面化（Q3-A 加性只读端点为唯一例外）；不改 3.6.3~3.6.6 端点语义 / 错误码 / 枚举 / 迁移 / 权限点 / `application.yml`。
- 零凭据明文、零数据本体、零内部面调用、零 `connector-internal` 角色、零 `admin` 混入普通档。
- 不做前端业务判定；服务端文案原样；不做乐观更新；不做假幂等。
- 运营档源接入区隐藏（DB-88 未决）；不为界面裁决规格冲突。
- 三剧本只核界面入口与承载说明，业务判定一字不改，PO 批准留痕。
- 发现冲突或疑似 S0 安全问题 → 立即停止上报（红线 4）。

## 12. 文件清单与体量

| 类别 | 文件 | 预估 |
| --- | --- | --- |
| 视图 | `frontend/src/views/connector/ConnectorManageView.vue` | 500~650 |
| 视图 | `frontend/src/views/connector/ConnectorDetailView.vue` | 700~900 |
| 视图 | `frontend/src/views/connector/ConnectorGovernanceView.vue` | 450~600 |
| API | `frontend/src/api/connector.ts` + `connector.spec.ts` | 330 + 250 |
| 常量 | `frontend/src/constants/connector.ts` | 170 |
| 身份 / 路由 / 转发 | `stores/demoIdentity.ts`、`router/index.ts`、`vite.config.ts` | 40 |
| 视图测试 | 三视图 spec + `ConnectorSourceGuard.spec.ts` + 既有计数 spec 更新 | 800~1000 |
| 后端（Q3-A） | Controller（或并入既有）+ 服务两方法 + 测试 | 250~350 |
| 文档 | 三剧本核对修订 + 任务卡 §四 §五 + 台账 + 日志 | 150~200 |
| 合计 | —— | **约 2500~3500 行**（1 个编码会话；D1 不拆分） |
