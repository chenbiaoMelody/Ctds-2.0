# WBS-3.5.6 高保真设计（交付管理界面）——编码契约

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（编码契约，2026-10-25 编排师一次确认即时生效）** |
| 任务卡 | `docs/tasks/WBS-3.5.6-交付管理界面-2026-10-25.md`；低保真 `docs/designs/WBS-3.5.6-lofi.md`；原型 `docs/designs/WBS-3.5.6-原型-交付管理界面.html` |
| 契约地位 | 实现与本契约不一致 = 打回项（章程 2.6）；标注"**编码段实测锚定**"的字段以后端代码 / 掤针实测为准，偏差走 §11 勘误登记 |

## 1. 接口契约表（复用 22 + 新增 0~1 + 跨域 1）

### 1.1 交付面（`DeliveryController`，`/api/v1/deliveries`）

| # | 端点 | 权限点 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- |
| D1 | POST `/api/v1/deliveries` | delivery.request | **`X-Idempotency-Key` 必填**；body `{contractNo, deliveryType: API\|FILE, fileId, purpose, territory}` | `DeliveryView` |
| D2 | GET `/api/v1/deliveries/{deliveryNo}` | delivery.read | 路径段 `encodeURIComponent` | `DeliveryView` |
| D3 | GET `/api/v1/deliveries?contractNo&deliveryType&pageNum&pageSize&orderBy` | delivery.read | `pageQuery()`；pageSize ≤100（固定 10） | `PageData<DeliveryView>` |
| D4 | GET `/api/v1/deliveries/{deliveryNo}/package` | delivery.package.download | **二进制流**；响应头 `X-Ctds-Sm3` | 密文文件落盘 |

### 1.2 沙箱面（`SandboxController` + `SandboxResultController`，`/api/v1/sandboxes`）

| # | 端点 | 权限点 | 请求要点 | 响应 |
| --- | --- | --- | --- | --- |
| S1 | POST `/api/v1/sandboxes` | sandbox.request | **幂等键必填**；body `{contractNo}` | `SandboxView` |
| S2 | GET `/api/v1/sandboxes/{sandboxNo}` | sandbox.read | — | `SandboxView` |
| S3 | GET `/api/v1/sandboxes?contractNo&status&pageNum&pageSize&orderBy` | sandbox.read | — | `PageData<SandboxView>` |
| S4 | POST `/{sandboxNo}/actions/close` | sandbox.use | 幂等（重复关返回当前视图） | `SandboxView` |
| S5 | POST `/{sandboxNo}/actions/run-job` | sandbox.use | **幂等键必填**；body `{jobId, purpose, territory}`；jobId 白名单 = `dataset-profile` / `input-inventory`（`application.yml` `ctds.sandbox.jobs`） | `JobView` |
| S6 | GET `/{sandboxNo}/jobs?pageNum&pageSize` | sandbox.read | — | `PageData<JobView>` |
| S7 | GET `/{sandboxNo}/results` | sandbox.read | 无分页（元数据列表） | `ResultView[]`（3.5.5 加性字段 status / conclusion / rejectReason / reviewRound；**未登记文件 status 为空**——编码段实测锚定空值语义） |
| S8 | POST `/{sandboxNo}/results/{fileName}/actions/submit-review` | sandbox.use | **幂等键必填**；fileName 路径段编码 | `ResultView` |
| S9 | POST `/{sandboxNo}/results/{fileName}/actions/export` | sandbox.use | **幂等键必填**；body `{purpose, territory}`（可选） | `ExportView` |
| S10 | GET `/{sandboxNo}/exports?pageNum&pageSize` | sandbox.read | — | `PageData<ExportView>` |

### 1.3 治理面（读即留痕）

| # | 端点 | 权限点 | 响应 |
| --- | --- | --- | --- |
| G1 | GET `/api/v1/sandboxes/governance?contractNo&status&分页` | sandbox.governance | `PageData<SandboxView>` |
| G2 | GET `/api/v1/sandboxes/governance/{sandboxNo}` | sandbox.governance | `SandboxView` |
| G3 | POST `/api/v1/sandboxes/governance/{sandboxNo}/results/{fileName}/actions/review` | sandbox.review | body `{conclusion: APPROVE\|REJECT, reason}`；**REJECT 理由必填 ≤256 字；结论一经作出不可修改**；`ResultView` |
| G4 | GET `/api/v1/sandboxes/governance/{sandboxNo}/reviews?fileName&pageNum&pageSize` | sandbox.governance | `PageData<ReviewView>`（含轮次） |
| G5 | GET `/api/v1/deliveries/governance?contractNo&deliveryNo&分页&orderBy` | delivery.governance | `PageData<DeliveryView>` |
| G6 | GET `/api/v1/deliveries/governance/{deliveryNo}` | delivery.governance | `DeliveryView` |
| **G7** | **（Q5-A）GET `/api/v1/deliveries/{contractNo}/action-logs?pageNum&pageSize`**（路径编码段按 3.4.7 R13 形态定稿：参与方 + 治理例外双入口或独立治理路径） | delivery.read / delivery.governance | `PageData<ActionLogView>`（谁 / 何时 / 对象 / 动作 / 结果码；**零新表零新码零迁移**；防枚举同码同文；治理读即留痕） |

### 1.4 跨域复用（1 个，只读）

| # | 端点 | 来源模块 | 用途 |
| --- | --- | --- | --- |
| X1 | 合约"我的合约"列表（仅 EFFECTIVE 过滤前端展示，全量返回由用户选） | `api/contract.ts` 既有函数（带 `contractRolesHeader()` 域头） | 发起交付 / 申请沙箱弹窗"检索回填"数据源 |

### 1.5 明确不调用

- `SandboxDataDenialController`（`/data/**` 一律 `1009C0012`）——**界面零入口零调用**（N3）；
- 网关 8086 任何端点（API 调用链 = 技术侧配合项，N1）。

## 2. 前端模块与类型

### 2.1 `src/api/delivery.ts`（~20 函数 + spec）

- 工厂：`const deliveryJson = makeDomainJson(deliveryRolesHeader())`（沿 DB-41 收敛模式）；写函数统一接收 `idempotencyKey: string` 参数 → `X-Idempotency-Key` 头。
- 函数清单：`requestDelivery` / `listDeliveries` / `getDelivery` / `downloadDeliveryPackage`（fetch blob + `X-Ctds-Sm3` 提示返回）/ `requestSandbox` / `listSandboxes` / `getSandbox` / `closeSandbox` / `runSandboxJob` / `listSandboxJobs` / `listSandboxResults` / `submitResultReview` / `exportSandboxResult` / `listSandboxExports` / `listGovernanceSandboxes` / `getGovernanceSandbox` / `reviewGovernanceResult` / `listGovernanceReviews` / `listGovernanceDeliveries` / `getGovernanceDelivery` /（Q5-A）`listDeliveryActionLogs`。
- 类型（与后端 DTO **逐字对齐**，编码段以 `DeliveryViews.java` / `SandboxViews.java` 实测校准）：

```ts
export interface DeliveryView { deliveryNo; contractNo; productId; productName; deliveryType: 'API'|'FILE'|'SANDBOX_EXPORT'; requesterNo; providerNo; fileId; fileName; fileSize; packageId; checksum; result; createdAt }
export interface SandboxView { sandboxNo; contractNo; productId; productName; requesterNo; providerNo; status; cpus; memoryMb; ttlSeconds; expiresAt; dataFileCount; closeReason; createdAt }
export interface SandboxJobView { jobNo; jobId; status; exitCode; durationMs; purpose; territory; createdAt; finishedAt }
export interface SandboxResultView { fileName; sizeBytes; checksum; createdAt; status; conclusion; rejectReason; reviewRound }
export interface SandboxReviewView { sandboxNo; fileName; reviewRound; conclusion; reason; reviewedBy; createdAt }
export interface SandboxExportView { deliveryNo; deliveryType; contractNo; sandboxNo; resultName; requesterNo; providerNo; fileName; fileSize; checksum; result; createdAt }
```

> **编码段实测锚定**（§10 前置检查 ⑧）：`SandboxView.status` 与 `ResultView.status` 枚举值集、`result` 字段码值域、`results` 未登记文件的 status 空值语义、发起交付拒绝码全值域（`1009C00xx`）——一律以代码与掤针实测为准锚入 `constants/delivery.ts`，本节类型名不因实测改名（字段名以 DTO 实测为准）。

### 2.2 `src/constants/delivery.ts`

- `DELIVERY_TYPE_LABELS / TAG_TYPES`（API / FILE / SANDBOX_EXPORT）、`SANDBOX_STATUS_LABELS / TAG_TYPES`（含 closeReason 展示）、`RESULT_STATUS_LABELS / TAG_TYPES`（待审核 / 已通过 / 已驳回 / 未登记〔空值〕）、`CONCLUSION_LABELS`（通过 / 驳回）、`JOB_ID_OPTIONS`（dataset-profile=数据集画像 / input-inventory=输入清单）、`GOVERNANCE_READ_TRACE_HINT`（读即留痕提示）、`SANDBOX_EXIT_BOUNDARY_HINT`（唯一出口诚实边界文案）、`REVIEW_IMMUTABLE_HINT`（结论不可改提示）、`G1_FORM_HINT`（沙箱形态口径提示）、同形错误码提示常量组（§8）。

### 2.3 扩展件

- `src/api/client.ts`：`apiJson` 增加**可选自定义头入参**（加性，既有调用零感知）；新增 `newIdempotencyKey()` 助手（`crypto.randomUUID()`，jsdom 兜底编码段实测锚定）。
- `src/stores/demoIdentity.ts`：新增 `deliveryRolesHeader()`（§4）。
- `src/router/index.ts`：§3 路由表。
- `vite.config.ts` + `deploy/nginx.main.conf` + `deploy/nginx.container.conf`：`/api/v1/deliveries`、`/api/v1/sandboxes` → 8087 两键（**置于通用 `/api` 键之前**，沿 8084/8085 先例；nginx 路径编码段按既有 location 形态对齐）。

## 3. 路由与菜单

| 路由 | 视图 | menuOrder | 权限 | 菜单名 |
| --- | --- | --- | --- | --- |
| `/deliveries` | `views/delivery/DeliveryListView.vue` | 17 | 无（登录可见） | 交付记录 |
| `/sandboxes` | `views/delivery/SandboxListView.vue` | 18 | 无（登录可见） | 沙箱任务 |
| `/sandboxes/:sandboxNo`（深链，不占菜单） | `views/delivery/SandboxDetailView.vue` | — | 登录守卫 | 沙箱任务详情 |
| `/delivery-governance` | `views/delivery/DeliveryGovernanceView.vue` | 19 | admin | 交付治理 |

- 菜单计数断言更新：`MainLayout.spec.ts:84` `toBe(16)` → `toBe(19)`（admin 视角；user 视角新增 2 项——既有 user 计数断言同步 +2，编码段按该文件实测锚定）；`router.spec.ts` max 断言同步。
- 深链守卫：未登录 → `/login`；`/delivery-governance` 非 admin → `/dashboard?denied=1`（沿既有守卫）。

## 4. 演示身份（Q2-A）

```ts
export function deliveryRolesHeader(): string {
  return pageRolesHeader('admin', 'requester,provider')
}
```

- 普通档 = `requester,provider` **双头**（主体编号决定视角：星河 = 需求方全操作；蓝天 = 提供方只读 + 代发起走业务层拒绝文案）；运营档 = `admin`。
- **硬约束：普通档头不得包含 `admin`**（测试锚 T2 钉死）；顶栏控件零新增；跨域 X1 调用带 `contractRolesHeader()` 域头，不互污染。
- 权限层先拦现象（DB-63/66 同型）在本域**因双头映射不触发**（provider 动作均由业务层判定出准确文案）；如实触发（实测异常）→ 登记 §11 勘误 + 关联 DB-66，不擅自改后端。

## 5. 幂等键机制（Q3-A）

- 写弹窗（发起交付 / 申请沙箱 / 运行作业 / 提交审核 / 导出 / 治理审核——**审核 G3 后端不收幂等键〔编码段实测锚定〕，其余 5 面必传**）打开时生成 `newIdempotencyKey()`；提交随 `X-Idempotency-Key` 头；**同弹窗重复提交（含按钮连击）= 同键重放**（后端返回首次结果，界面提示"幂等重放：返回首次结果"沿 `1009C0005` 语义之外的重放口径——编码段实测重放响应形态锚定提示文案）；关闭重开 = 换新键。
- 全局**不做**每请求自动生成（N 候选 B 已否决）。

## 6. 界面说明书（逐页面逐区；线框见原型 HTML 8 画面）

### 6.1 「交付记录」页（P1）

- **A 过滤栏**：合约编号输入 + 形态下拉（全部 / API / 文件交付 / 沙箱导出）+ 查询 / 重置；**B 列表**：列 = 交付编号 / 合约编号 / 产品 / 形态（tag）/ 发起方 / 提供方 / 文件名 / 大小（人类可读 KB·MB 换算）/ 结果 / 时间；行操作「详情」；**C 分页**（pageSize 10，`@current-change` 重拉）。
- **D 详情抽屉**（el-drawer，沿合约域先例）：`el-descriptions` 全字段；**校验值（SM3）** 单独行展示 + 复制提示；**「下载加密包」按钮** = 仅当 `deliveryType ∈ {FILE, SANDBOX_EXPORT}` 且当前主体 == `requesterNo`（界面按演示身份主体编号判断显隐；**服务端仍强制**——权限点 `delivery.package.download`）显示；点击 → 密文落盘（文件名沿响应头 / `deliveryNo` + `.pkg`——编码段实测锚定）→ 成功 `ElMessage` 展示 `X-Ctds-Sm3` 值提示"请以校验值比对完整性"；失败原样错误码。
- **E 「发起交付」弹窗**（el-dialog 640px，destroy-on-close）：合约编号（直填 input + 「检索」按钮 → X1 拉取 EFFECTIVE 合约下拉回填，仅回填编号不强制）；形态单选（API / FILE）；FILE 时显示 `fileId` 输入（必填）；用途 / 地域输入（自由文本，**可空可非法**——服务端判定）；必填留空 → 前置拦截零请求；提交成功 → 关弹窗 + 刷新列表 + success 提示；被拒 → 后端文案原样（`ElMessage.error`）。

### 6.2 「沙箱任务」页（P2）

- **A 过滤栏**：合约编号 + 状态下拉（全量状态——编码段锚定）；**B 列表**：任务编号 / 合约 / 产品 / 需求方 / 提供方 / 状态（tag）/ 配额（`{cpus}C·{memoryMb}MB·{ttl}S` 摘要）/ 到期时间 / 关闭原因（终态行）；行操作「详情」→ 深链。
- **C 「申请沙箱」弹窗**：合约编号直填 + 检索回填；**G1 口径提示常驻**（"沙箱仅接受数据集 / 报告 / 模型形态合约；API 形态将被拒绝"——提示文案，判定在服务端）；幂等键；成功 → 跳转深链详情页。

### 6.3 沙箱任务详情深链页（P3，多区块）

- **A 基本信息与配额生命周期区**：`el-descriptions`（编号 / 合约 / 产品 / 双方 / 状态 tag / cpus / memoryMb / ttlSeconds / expiresAt / dataFileCount / closeReason / createdAt）；动作条「关闭任务」（仅 RUNNING 显示；确认框 → S4 → 刷新；STOPPED / EXPIRED 隐藏）。
- **B 「沙箱工作台」区**：**诚实边界文案常驻**（"沙箱内可读可算，数据本体不可带出；唯一出口 = 审核通过后导出"）；「运行作业」按钮（仅 RUNNING）→ 弹窗（jobId 下拉 = 白名单二项 + 用途 / 地域自由填写）→ 成功刷新 C；被拒（策略越界 `1008C0020` 等）原样展示；**C 作业历史**：`el-table` + 分页（jobNo / 作业 / 状态 / 退出码 / 时长 ms / 用途 / 地域 / 时间）。
- **D 「结果物」区**：`el-table`（fileName / 大小 / SM3 / 时间 / **状态 tag**（未登记 / 待审核 / 已通过 / 已驳回）/ 结论 / 驳回理由 / 轮次）；行操作：**「提交审核」**——`status` 为空（未登记）或 `REJECTED` 时可用（REJECTED 时附"重新提交（轮次 +1）"提示——G2 口径），`PENDING_REVIEW` 禁用（tooltip"审核中"），`APPROVED` 不显示（已通过不可再提交）；**「导出」**——仅 `APPROVED` 可用，其余禁用并 tooltip 原因（未审核 / 已驳回——对应 `1009C0016` 语义的**体验层提示**，判定仍在服务端）→ 导出弹窗（用途 / 地域可选）→ 成功刷新 E + 展示导出编号；**E 「导出记录」区**：`el-table` + 分页（导出编号 / 合约 / 结果物 / 文件名 / 大小 / SM3 / 结果 / 时间）。
- 页面数据装载：S2 + S6 + S7 + S10 四请求（无依赖顺序，并行；失败各自 showError）。

### 6.4 「交付治理」页（P4，admin）

- **Tab1「导出审批」**：**读即留痕提示常驻**（GOVERNANCE_READ_TRACE_HINT）；过滤（合约 / 状态）+ G1 列表 → 行操作「治理详情」→ 区块 = 基本信息（同 P3-A，无操作面）+ **结果物表**（同 P3-D 字段）+ 行操作「审核」（仅 `PENDING_REVIEW` 可用；已出结论行 tooltip"结论已作出，不可修改；纠错走需求方重新提交"）→ 审核弹窗（结论单选 通过 / 驳回；驳回时理由必填 `maxlength 256` + 计数；**结论不可改提示**常驻）→ 成功刷新结果物 + 留痕区；**审核留痕区** = G4 分页表（轮次 / 结论 / 理由 / 审核人 / 时间；`fileName` 过滤可选）。
- **Tab2「交付记录治理」**：读即留痕提示常驻；过滤（合约 / 交付编号）+ G5 列表 + 「详情」抽屉（同 P1-D，**无下载按钮**——治理面不领取加密包）。
- **（Q5-A）动作留痕区**（Tab1 治理详情内或独立 Tab，编码段按 G7 最终形态定）：G7 分页表（谁 / 何时 / 对象 / 动作 / 结果码）——承载 C-5.1 S3-7"被拒的越权探测记录"屏幕可核对。

### 6.5 全局交互纪律

- 失败统一 `showError`：`guideToLoginIfAuthFailed`（`1000C0002` → 登录页）否则 `ElMessage.error` 原样后端文案；**不做乐观更新**；列表 loading = `v-loading`；空主体（普通档未填主体编号）= `makeDomainJson` 前置拦截零请求 + 统一文案（沿既有）。
- 中文不散写：全部文案入 `constants/delivery.ts`（T14 SourceGuard 守卫）。

## 7. 测试锚点（T1~T16；红相先行，归档 `build-output/w356-red-*`）

| 锚 | 对象 | 关键断言 |
| --- | --- | --- |
| T1 | client 扩展 | 自定义头逐字传递；`newIdempotencyKey()` 唯一性；既有 apiJson 调用零回归 |
| T2 | deliveryRolesHeader | 普通档 = `requester,provider` 且**不含 admin**；运营档 = `admin` |
| T3 | 路由与菜单 | admin 菜单 19 / user 新增 2；深链守卫；治理页非 admin → denied |
| T4 | 交付记录列表 | 过滤参数逐字 / 分页 / 形态 tag / 空主体零请求 / 失败原样 |
| T5 | 发起交付弹窗 | 必填前置拦截零请求；形态联动 fileId；检索回填（X1 mock）；幂等键头出现；成功刷新；拒绝码原样（码值编码段锚定） |
| T6 | 交付详情抽屉 | 字段集全；下载按钮可见性矩阵（形态 × 需求方/他方）；SM3 展示与下载提示；无本体断言 |
| T7 | 沙箱列表 + 申请 | 过滤 / 状态 tag；G1 提示文案存在；申请幂等键；成功跳深链 |
| T8 | 沙箱详情状态机 | 提交审核按钮 × `status` 四态显隐矩阵；导出仅 APPROVED；关闭仅 RUNNING；驳回理由 / 轮次展示 |
| T9 | 运行作业弹窗 | jobId 白名单下拉二项；要素自由填；越界 `1008C0020` 原样；作业历史分页 |
| T10 | 导出弹窗 + 导出记录 | 可选要素可空；幂等键；成功刷新记录区；`1009C0016` 原样 |
| T11 | 治理 Tab1 | 列表 / 详情；审核弹窗（驳回理由必填 + 256 上限；通过免理由）；已出结论行按钮禁用 + 不可改提示；留痕轮次列；读即留痕提示常驻 |
| T12 | 治理 Tab2 | 列表 / 详情；详情无下载按钮；读即留痕提示 |
| T13 | （Q5-A）动作留痕区 | G7 消费渲染 / 分页 / 空态 |
| T14 | SourceGuard | 交付域视图源码零散写中文；零本体字段（content / body / 原文类禁词清单锚定） |
| T15 | 错误码同形矩阵 | §8 码表逐码同一条常量；`1000C0002` 跳登录 |
| T16 | （Q5-A）后端集成 | 参与方 + 治理例外双入口；非参与方同码同文逐字；治理读即留痕（留痕行断言）；分页边界（pageSize 上限 / 越界页）；零新码零迁移（迁移清单不变断言） |

## 8. 边界值与异常行为

| 场景 | 行为 |
| --- | --- |
| 空主体（未填主体编号） | 前置拦截零请求（`1000C0002` 同文案） |
| 认证失效 `1000C0002` | `guideToLoginIfAuthFailed` → 登录页 |
| 防枚举三码 | `1009C0007`（交付）/ `1009C0010`（沙箱）/ `1009C0015`（结果物）与"不存在"**同码同文**——界面原样，不叠加解释 |
| 生命周期 `1009C0011` | 到期 / 已关闭任务操作被拒原样 |
| 数据面 `1009C0012` | 界面零入口（不调用）；如意外触发原样展示 |
| 结果物状态 `1009C0016` | 未审核 / 已驳回 / 快照不一致导出被拒原样 |
| 合约状态 `1008C0013` / 策略越界 `1008C0020` | 原码原文案透传（合约域权威） |
| 缺幂等键 `1009C0005` | 不应发生（前端必传）；如触发原样展示 |
| 同弹窗重复提交 | 同键重放 → 返回首次结果 + 重放提示（响应形态编码段锚定） |
| 驳回理由长度 | `maxlength=256` + 实时计数；超长前端截断（服务端 ≤256 校验兜底） |
| 文件名 / 编号特殊字符 | 路径段一律 `encodeURIComponent`（沿 `contract.ts` 先例） |
| 分页边界 | pageNum 越界 = 空页；pageSize 固定 10（后端上限 100） |
| 二进制下载失败 | 非 `ApiResult` 响应（403 / 404）→ 解析状态码 + 原样提示；不落半截文件 |
| >64MiB 结果物 | checksum 为空的展示口径（"—（超大文件不提供摘要）"，编码段实测锚定） |

## 9. 剧本步骤 → 界面入口映射（编码段回填实测锚点；核对修订底稿）

| 剧本步骤 | 界面入口 / 承载口径 |
| --- | --- |
| C-5.1 S1-1（已生效合约发起 API 交付） | P1「发起交付」弹窗（形态 = API）✅ 界面承载 |
| C-5.1 S1-2（网关合规调用 + "已计量"标记） | **技术侧配合项**（网关 JWT 直调）；"已计量"标记 = **未开工依赖口径**（Q6-A 修订建议交 PO） |
| C-5.1 S1-3（查看调用 / 交付记录） | P1 列表（形态过滤 = API）+ 详情抽屉 ✅ |
| C-5.1 S1-4（提供方查看） | 普通档主体编号切提供方（双头映射）✅ |
| C-5.1 S1-5 / S2-3（幂等重放） | 同弹窗重复提交（同键）→"返回首次结果、记录不增加" ✅ |
| C-5.1 S1-6 / S3-7（治理查看 / 越权探测记录） | P4 Tab2 ✅；S3-7 = （Q5-A）动作留痕区 ✅ /（Q5-B）技术侧配合 |
| C-5.1 S2-1/2/5（文件交付 + 校验值 + 提供方查看） | P1 弹窗（FILE + fileId）+ 详情抽屉校验值 + 下载 ✅ |
| C-5.1 S2-4（超范围请求） | 界面可填（要素自由），判定服务端；对照参数 = 技术侧配合 |
| C-5.1 S3-1/2/3/8/11（凭据 / 状态 / 代发起 / 形态 / 反向锚） | P1 弹窗自由填写 → 拒绝文案原样 ✅（S3-3 = 提供方主体 + 双头走业务层拒绝） |
| C-5.1 S3-4~6/9/10（防枚举 / 绕过 / 越界 / 全量无本体） | 技术侧配合项为主；界面拒绝提示原样；S3-10 无本体 = T14 守卫 ✅ |
| C-5.2 S1-1/2（申请 + 详情） | P2 弹窗 + P3-A 配额生命周期区 ✅ |
| C-5.2 S1-3（沙箱工作台） | P3-B 区 + 诚实边界文案 ✅ |
| C-5.2 S1-4/5（提供方只读 / 治理查看留痕） | 双头映射切主体 ✅ / P4 Tab1 ✅ |
| C-5.2 S2-1（约定用途使用） | P3「运行作业」弹窗（purpose = 风控建模）→ 作业历史留痕 ✅ |
| C-5.2 S2-2（直接导出原始数据被拒） | 界面零入口（唯一出口文案常驻）；技术侧 `/data/**` 探测配合 |
| C-5.2 S2-3（跨任务 / 跨合约访问） | 技术侧配合项（剧本已标注） |
| C-5.2 S2-4（约定外用途被拒） | 「运行作业」purpose 填约定外 → `1008C0020` 原样 ✅ |
| C-5.2 S2-5~8（提交审核 / 通过 / 驳回 / 留痕） | P3-D「提交审核」+ P4 Tab1「审核」弹窗 + 审核留痕区（轮次）✅ |
| C-5.2 S3-1/2/3（导出放行 / 未审 / 驳回被拒） | P3-D「导出」按钮状态机 + 拒绝原样 ✅ |
| C-5.2 S3-4/5（绕过界面 / 策略越界） | S3-4 技术侧（直调同拒——服务端强制）；S3-5 导出弹窗要素越界 → 原样 ✅ |
| C-5.2 S3-6（到期 / 关闭后操作） | P3 操作按状态显隐 + 服务端 `1009C0011` ✅ |
| C-5.2 S3-7（未生效 / 已终止合约申请） | P2 弹窗自由填写 → 拒绝原样 ✅ |
| C-5.2 S3-8/10（非参与方同码同文） | 技术侧配合（身份头主体对调可演示同码同文界面面） |
| C-5.2 S3-9（配额耗尽继续使用被拒） | 技术侧配合项（对照任务代造） |
| C-5.2 S3-11（全量无本体） | T14 守卫 + 走查目检 ✅ |

> 本表 = Q6-A 剧本核对修订的**底稿**（入口名称 / 路径以实现为准替换占位；技术侧与未开工口径写入承载说明）；**业务判定一字不改**。

## 10. 实施前置检查项（编码段第一动作，逐项实测留痕）

1. 冷启动读序：`AGENTS.md` → 最新日志 → 本卡 → lofi / hifi V1.0 → 台账进行中行；
2. 环境核验：分支 == origin；Docker 三容器在运 + delivery-service 8087 构件新鲜度核验（拆包核验，沿"验收前必查构件陈旧"教训）+ 前端 5173 起 / 停口径；
3. 后端基线复跑：delivery **181 例**全绿（6 跳过语义不变）+ `checkstyle:check` 0（模块目录跑须 `-Dmaven.multiModuleProjectDirectory` 指仓库根）；
4. 前端基线复跑：**440 例**全绿 + typecheck / lint / build（threads 池，沿 Windows 先例）；
5. 22 端点掤针：D1~D4 / S1~S10 / G1~G6 逐端点可达性 + 防枚举同码同文抽测 + `X-Ctds-Sm3` 头实见 + results 未登记 status 空值实测（沿 3.4.7 24 探针先例）；
6. 演示数据实测：CO000026 状态 / 形态、合约双形态（API + 数据集）可用性、RUNNING 沙箱任务现状（走查段备链，本段只记录不造数）；
7. `gates-config.json` ConfigSha256 与基线 `955bdbba…2ee2` 逐字一致核对；
8. §2"编码段实测锚定"清单逐项锚定：状态枚举值集 / result 码值域 / 发起拒绝码全值域 / 审核端点幂等键收否 / 重放响应形态 / >64MiB checksum 空值 / nginx location 形态——锚定结果落编码段日志，偏差走 §11。

## 11. 勘误登记（实施与 V1.0 契约偏差；保持空表 = 逐条一致）

| 编号 | 偏差 | 业务影响 | 处置 |
| --- | --- | --- | --- |
| —— | ——（编码段起填） | —— | —— |

---

## 确认记录

> **✅ 确认留痕（2026-10-25 09:2x 编排师会话回复"全部按建议"）**：**Q1~Q7 均采建议口径 A + D1 不拆分**（逐项口径见 lofi 确认记录）。hifi 转 **V1.0 = 编码契约即时生效**：实现与本契约不一致 = 打回项；§2 / §5 / §6 / §8 标注"编码段实测锚定"的字段以后端代码与掤针实测为准，偏差走 §11 勘误登记（零业务语义变更的物理事实类偏差方可登记放行，业务语义偏差 = 打回）。
> Q5-A 生效：交付物含 G7 只读留痕读面端点（零新表 / 零新码 / 零迁移）+ T13 / T16 测试锚；Q6-A 生效：hifi §9 映射表 = 剧本核对修订底稿（业务判定一字不改，PO 批准留痕）。
> 立卡批（任务卡 + lofi + hifi + 原型 + 本批日志）提交并推送 origin；**编码归新会话**（测试先行，第一动作 = §10 前置检查八项逐项实测留痕）。
