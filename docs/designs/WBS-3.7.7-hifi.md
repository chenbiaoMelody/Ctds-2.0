# WBS-3.7.7 高保真设计：计费管理界面（V0.9 待确认 = 编码契约草案）

> 任务卡 `docs/tasks/WBS-3.7.7-计费管理界面-2026-11-02.md`；lofi `docs/designs/WBS-3.7.7-lofi.md`；原型 `docs/designs/WBS-3.7.7-原型-计费管理界面.html`。
> 上游契约唯一权威 = `docs/designs/WBS-3.7.6-hifi.md` §1 / §3.4 / §6 / §7 / §8（八端点、字段、状态机、错误码、权限）。
> 版本：**V1.0（编码契约，即时生效）——2026-11-02 编排师一次确认：Q1~Q8 全采建议口径 A + D1 不拆分**；V0.9 = 立卡段草案（确认内容一字未改）。勘误编号自 **E1** 起（§12）；**实现与本文不一致 = 打回项**。

## 1. 接口契约表（既有 8 + 新增 1 = 9 个对外端点；本卡新增仅 1 个只读端点）

| # | 方法与路径 | 权限点 | 请求 | 响应 data | 错误 | 本卡界面用途 |
| --- | --- | --- | --- | --- | --- | --- |
| **B-R3** | `GET /api/v1/billing/bills/governance` | `billing.governance` | `contractNo` / `period` / `productId` / `requesterNo` / `providerNo` / `status` / `billType` / `pageNum` / `pageSize` | `PageResult<BillSummary>` | 1011C0001 参数非法 | 运营 Tab1 列表 |
| **B-W1** | `POST /api/v1/billing/bills` | `billing.governance` | `{contractNo, period?, agreedTransactionAmount?}` | 201 `BillDetailView` | 1011C0010 重复 / 1011C0013 缺基数 / 1011C0014 合约不存在 / 1011C0015 不可出账 | 手动出账弹窗 |
| **B-R2** | `GET /api/v1/billing/bills/{billNo}` | `billing.read` | 路径 `billNo` | `BillDetailView` | 1011C0009（非本方 / 不存在同码同文） | 运营详情（admin 放行）+ 门户详情 |
| **B-W2** | `POST /api/v1/billing/bills/{billNo}/confirm` | `billing.governance` | 无 body | `BillDetailView` | 1011C0011 已冻结 / 1011C0012 状态不允许 | 详情「确认」 |
| **B-W3** | `POST /api/v1/billing/bills/{billNo}/corrections` | `billing.governance` | `{amount, reason}` | 201 `BillDetailView`（更正单） | 1011C0011 / 1011C0012 | 详情「更正」弹窗 |
| **B-R1** | `GET /api/v1/billing/bills/mine` | `billing.read` | `contractNo` / `period` / `productId` / `pageNum` / `pageSize` | `PageResult<BillSummary>` | 1011C0001 | 门户「我的账单」列表 |
| **B-R4** | `GET /api/v1/billing/contracts/{contractNo}/reconciliations` | `billing.read` | `period`（可选）/ `pageNum` / `pageSize` | `PageResult<ReconciliationView>` | 1011C0001 / 1011C0014 | Tab2 对账记录 |
| **B-W4** | `POST /api/v1/billing/contracts/{contractNo}/reconciliations` | `billing.governance` | query `period`（必填） | `ReconciliationView` | 1011C0009 无该期账单 / 1011C0014 合约不存在 | Tab2「执行对账」 |
| **R-M4（新增）** | `GET /api/v1/metering/flows/governance` | `metering.governance` | `contractNo` / `requesterNo` / `sourceType` / `from` / `to` / `pageNum` / `pageSize` | `PageResult<MeteringFlowRow>` | 1000C0001 参数非法 | Tab3 全量计量流水 |
| （复用） | `GET /api/v1/orders/governance` | `contract.governance` | `status` / `productId` / `contractNo` / `requesterNo` / `pageNum` / `pageSize` | 既有 `PageResult<OrderSummary>` | 1011C0003 | Tab4 订单检索（零后端改动） |

**R-M4 契约细化（新增，唯一后端改动）**：

- 落点：`services/metering-billing`，`interfaces/MeteringController` 新增 1 个方法（同前缀 `/api/v1/metering`），应用层 `MeteringQueryService.governanceFlows(criteria, PageQuery)` + 仓储分页查询（`metering_flow` 只读）；
- 行形状 `MeteringFlowRow`：`id / contractNo / requesterNo / sourceType / sourceNo / action / resource / occurredAt / createdAt`（源类型枚举值与既有 `MeteringSourceType` 四值一致：`GATEWAY_API / FILE_DELIVERY / SANDBOX_EXPORT / SANDBOX_JOB`）；
- 排序：`occurred_at DESC, id DESC`（稳定分页）；默认 `pageSize = 10`，上限 100（沿 `PageQuery` 既有口径）；
- 语义边界：**仅运营全量读面**；3.7.5 既有 `R-M1/R-M2` 的归属硬校验与 404 防枚举**零改动**（两套读面并存，互不影响）；
- 零迁移、零新权限点、零新错误码；非法 `contractNo` 形态 / `sourceType` 枚举 / `from`·`to` 时间格式 → 1000C0001（通用参数错误，沿既有 `ParamInvalid` 口径）；`from > to` 同样 1000C0001。

**响应封套**：`{code, message, traceId, data}`；成功 `code = "0"`；分页 `data = {list, total, pageNum, pageSize, totalPages}`（前端 `PageData`）。金额一律字符串（`BigDecimal.toPlainString()`），前端不做数值换算。

## 2. 前端模块与类型

### 2.1 `frontend/src/api/billing.ts`（新增）

| 函数 | 端点 | 说明 |
| --- | --- | --- |
| `listGovernanceBills(query)` | B-R3 | `contractNo?/period?/productId?/requesterNo?/providerNo?/status?/billType?/pageNum/pageSize` → `PageData<BillSummary>` |
| `getBillDetail(billNo)` | B-R2 | 运营与门户共用（身份头决定可见面） |
| `outbillBill(body)` | B-W1 | `{contractNo, period?, agreedTransactionAmount?}` → `BillDetail` |
| `confirmBill(billNo)` | B-W2 | → `BillDetail` |
| `createCorrection(billNo, body)` | B-W3 | `{amount: string, reason: string}` → `BillDetail`（金额以字符串提交，避免浮点误差） |
| `listMyBills(query)` | B-R1 | `contractNo?/period?/productId?/pageNum/pageSize` → `PageData<BillSummary>` |
| `listReconciliations(contractNo, query)` | B-R4 | `period?/pageNum/pageSize` → `PageData<Reconciliation>` |
| `runReconciliation(contractNo, period)` | B-W4 | query `period` 必填 → `Reconciliation` |
| `listGovernanceMeteringFlows(query)` | R-M4 | 新增端点 → `PageData<MeteringFlowRow>` |

- 复用 `client.ts` 的 `makeDomainJson(rolesHeader)` 工厂 + `pageQuery`；**不新增 axios / 不新增 npm 依赖**；
- `api/order.ts` 追加 `listGovernanceOrders(query)`（R-OR3；订单端点收口在 `api/order.ts`，`PortalSourceGuard.spec.ts` 静态断言要求）；
- **计费端点无幂等键**（3.7.6 未启用 `common-idempotency`）→ 界面**不做假幂等**；写按钮仅 in-flight 禁用（防误触）。

### 2.2 类型（`api/billing.ts` 内定义，不新建 types 文件）

`BillSummary` / `BillDetail` / `BillDetailLine` / `BillAction` / `Reconciliation` / `ReconciliationDifference` / `MeteringFlowRow` —— 字段与 §1 及 3.7.6 `BillSummary.java` / `BillDetailView.java` / `ReconciliationView.java` 逐字对齐（金额 `string`、时点 `string`）。

### 2.3 `frontend/src/constants/billing.ts`（新增）

| 常量 | 内容 |
| --- | --- |
| `BILL_STATUS_LABELS` / `BILL_STATUS_TAG` | `ISSUED → 已开具(info)` / `CONFIRMED → 已确认(success)` / `FROZEN → 已冻结(danger)` |
| `BILL_TYPE_LABELS` | `NORMAL → 普通账单` / `CORRECTION → 更正单` |
| `PRICING_MODEL_LABELS` | `FREE → 免费` / `PER_CALL → 按次` / `MONTHLY → 包月` / `REVENUE_SHARE → 交易额分成` |
| `PRICING_MODEL_RULES` | 四档口径说明（费率视图 + 出账弹窗提示复用） |
| `CONCLUSION_LABELS` / `CONCLUSION_TAG` | `PASS → 通过(success)` / `DIFFERENCE → 差异(danger)` |
| `DIFFERENCE_TYPE_LABELS` | `COUNTER_VS_USAGE_LOG_ALL` / `USAGE_LOG_VS_METERING_ALL` / `USAGE_LOG_VS_METERING_PERIOD` / `BILL_QUANTITY_VS_METERING` / `AMOUNT_RECOMPUTE` / `MISSING_IN_BILL` / `EXTRA_IN_BILL` 七类中文说明（未知值原样显示） |
| `BILL_ACTION_LABELS` | `CREATE → 创建` / `CONFIRM → 确认` / `FREEZE → 冻结` / `UNFREEZE → 解冻` |
| `SOURCE_TYPE_LABELS` | 四源中文名 |
| `MY_ROLE_LABELS` | `REQUESTER → 需方` / `PROVIDER → 供方` |
| `BILLING_HINTS` | 文案常量：出账四档提示 / 分成基数必填提示 / 冻结不可确认 / 更正负正语义 / 改价不追溯 / 明细合计对照免责（"以服务端账单为准"） |

- `constants/billing.ts` **不得出现 `admin` 字面量**、不得出现端点 URL（`PortalSourceGuard.spec.ts` 扫描面约束）。

### 2.4 视图与子组件

| 件 | 路径 | 职责 |
| --- | --- | --- |
| 运营容器 | `views/billing/BillingManageView.vue` | 五 Tab 容器 + 各 Tab 列表/面板（Tab1/Tab3/Tab4/Tab5 内联；Tab2 用 `ReconcilePanel`） |
| 运营详情 | `views/billing/BillDetailView.vue` | 深链详情：快照 + 明细 + 合计对照 + 留痕 + 更正链 + 确认/更正入口 |
| 门户列表 | `views/portal/PortalBillListView.vue` | 本方账单列表 + 过滤 + 分页 |
| 门户详情 | `views/portal/PortalBillDetailView.vue` | 本方账单详情（只读；零写动作） |
| 出账弹窗 | `views/billing/components/OutbillDialog.vue` | 合约号 + 账期 + 约定交易额（分成档） |
| 更正弹窗 | `views/billing/components/CorrectionDialog.vue` | 金额（可负）+ 原因 |
| 确认弹窗 | `views/billing/components/ConfirmDialog.vue` | 二次确认 + 冻结态前置说明 |
| 对账面板 | `views/billing/components/ReconcilePanel.vue` | 执行对账 + 结论卡 + 四方数值 + 差异明细 + 记录列表 |
| 明细表 | `views/billing/components/BillDetailSections.vue` | 快照 + 明细 + 合计 + 留痕 + 更正链（运营/门户共用，props 控制写动作显隐） |

## 3. 界面说明书（画面 × 元素 × 数据源 × 交互 × 态 × 权限）

> 画面编号 P1~P11；静态原型见 `docs/designs/WBS-3.7.7-原型-计费管理界面.html`（线框级，标注画面编号便于对照）。

**P1 计费管理 · Tab1 账单管理（运营）**
- 工具栏：合约号（输入，占位 `CO000001`）、账期（`el-date-picker type=month`，值格式 `YYYY-MM`）、产品 ID（数字）、需方主体号、供方主体号、状态（`全部/ISSUED/CONFIRMED/FROZEN`）、类型（`全部/NORMAL/CORRECTION`）、「查询」「重置」、「手动出账」主按钮。
- 表格列：账单号（`router-link` → `/billing/bills/:billNo`）｜类型｜合约号｜需方｜供方｜产品（`productName` + ID）｜账期｜定价档｜金额（右对齐）｜状态（`el-tag`）｜生成时点。
- 分页：`pageNum/pageSize`（10/20/50），服务端分页；查询条件变化重置到第 1 页。
- 空态：`暂无账单，可调整过滤条件或发起手动出账`；加载态：表格 `v-loading`；失败态：`el-alert` 显示服务端 `message` + 「重试」。
- 权限：路由 `meta.permission = billing.governance`；非 admin → 守卫跳 `/dashboard?denied=1`。

**P2 手动出账弹窗（`OutbillDialog`）**
- 字段：合约号（必填，提示 `CO + 6 位数字`）、账期（月份选择，**默认上一自然月**，可清空 = 交由服务端默认）、约定交易额（文本，提示"**仅交易额分成档必填**，其余档留空"）。
- 说明卡（常显）：`出账按合约定价快照计算：按次 = 事件数 × 次单价；包月 = 整月周期费；交易额分成 = 约定交易额 × 费率；免费档不出账单。`
- 提交：`提交`（in-flight 禁用）/ `取消`；成功 → 关闭弹窗 + `ElMessage.success('出账成功：' + billNo)` + 跳转 `/billing/bills/:billNo`。
- 失败：弹窗内 `el-alert` 原样展示服务端 `message`（1011C0010 / C0013 / C0014 / C0015）；表单不做业务预判（仅合约号空值前端拦截提示）。

**P3 运营账单详情（`/billing/bills/:billNo`）**
- 头区：账单号 + 类型标签 + 状态标签 + 操作区（`确认` / `更正` / `刷新` / `返回列表`）。
- 快照区（描述列表，两列）：合约号｜账期｜需方｜供方｜产品（名称 + ID）｜定价档｜数量（按次才有）｜单价｜约定交易额基数（分成才有）｜分成费率（分成才有）｜账单金额｜生成时点｜生成人｜更正原单（`correctionOfBillNo` 链接）。
- 明细区（表格）：行号｜类型（`USAGE` / `PERIOD_FEE` / `ADJUSTMENT` 等原值 + 中文）｜说明｜计量流水号（`meteringFlowId`）｜源类型｜源单号｜事件时点｜数量｜单价｜金额；表尾：`明细合计：X`｜`账单金额：Y`（不相等时红色提示 `账单金额以服务端为准`，**不拦截、不改值**）。
- 留痕区（表格）：动作｜原状态→新状态｜原因（**纯文本转义**）｜操作人｜时点。
- 更正链：`correctionBillNos` 逐条链接（双向可溯）；本单为更正单时显示「返回原单」。
- 状态行为：`ISSUED` → 确认可用、更正可用；`CONFIRMED` → 确认禁用（已确认）、更正可用；`FROZEN` → 确认禁用 + 提示 `账单已冻结，需对账澄清后重新对账解冻`、更正禁用（服务端 1011C0011/C0012 兜底）。
- 错误态：1011C0009 → 整页空态 `账单不存在或不可读`；其余 → `el-alert` + 重试。

**P4 确认弹窗（`ConfirmDialog`）**：文案 `确认后账单状态将变为"已确认"，该动作会留下操作记录。`；确认 → B-W2；失败（409）弹窗内展示服务端文案并刷新详情。

**P5 更正弹窗（`CorrectionDialog`）**
- 字段：更正金额（必填，文本，允许负号，提示 `负 = 红字冲正；正 = 补差；最多两位小数`）、更正原因（必填，`textarea`，上限 200 字并显示计数）。
- 提示卡：`更正不是修改原单：系统会另出一张更正单，原单金额与明细保持不变。`
- 成功 → 跳转新更正单详情；失败 → 弹窗内原样展示服务端文案（1011C0011 / C0012 / 1000C0001）。

**P6 对账工作台（Tab2 / `ReconcilePanel`）**
- 表单：合约号（必填）、账期（必填月份）→「执行对账」（in-flight 禁用）。
- 结论卡：结论标签（`通过` 绿 / `差异` 红）+ 执行人 + 执行时点 + 「查看该期账单」（切 Tab1 并带入合约号 + 账期过滤）。
- 四方数值表（两列键值）：`合约计数（余额口径）`｜`执行流水放行数（本期）`｜`执行流水放行数（全量）`｜`计量流水数（本期）`｜`计量流水数（全量）`｜`按次账单数量`｜`账单合计金额`｜`按快照复算金额`。
- 差异明细表：类型（中文 + 原值）｜腿｜期望值｜实际值｜说明；空态 `无差异`；差异时顶部 `el-alert type=error`：`该合约该账期账单已冻结，确认前需澄清差异并重新对账通过。`
- 对账记录区：`listReconciliations(contractNo, {period})` 分页列表（执行时点｜结论｜差异数｜执行人），点击行在结论卡回填该次记录。

**P7 计量流水（Tab3）**
- 工具栏：合约号、主体号（`requesterNo`）、源类型（全部 / 四值）、时间范围（`datetimerange` → `from`/`to`，ISO `yyyy-MM-dd HH:mm:ss`）、查询 / 重置。
- 表格：流水号（`id`）｜合约号｜主体号｜源类型（中文）｜源单号｜动作｜资源｜事件时点｜入库时点。
- 分页 + 空态 `暂无计量流水`；失败态展示服务端文案。
- 说明条：`本页为运营方全量逐条流水口径（规格 B12-2）；需方/供方逐条流水请在其本方读面查看。`

**P8 订单检索（Tab4）**
- 工具栏：状态（三态 + 全部）、产品 ID、合约号、需方主体号、查询 / 重置。
- 表格：订单号｜产品｜需方｜定价档｜金额｜状态｜合约号｜下单时点；**无详情跳转**（运营无订单详情读权，避免误导）。
- 空态 `暂无订单`；说明条：`运营检索为全量口径（C-7.1 B4-2）；订单详情仅当事方可读。`

**P9 费率视图（Tab5，只读）**
- 四张卡片：`免费`（不出账单、不产生计费流水）/ `按次`（本期事件数 × 次单价，明细逐笔可溯）/ `包月`（整月周期费，与调用次数无关）/ `交易额分成`（约定交易额 × 费率，费率 = 定价百分比 0~100）。
- 说明块：`定价来源 = 目录域产品定价（四档），下单时快照进合约与订单；账单只认快照，改价不追溯已出账单。`
- 动作：`前往产品上架页变更定价`（`router-link` → `/catalog/products`，仅指路，不内嵌编辑）。

**P10 门户「我的账单」（`/portal/bills`）**
- 工具栏：合约号、账期、产品 ID、查询 / 重置；页面说明 `仅显示与本主体相关的账单（我是需方或供方）`。
- 表格：账单号（链接 → `/portal/bills/:billNo`）｜类型｜产品｜账期｜我的角色（需方 / 供方）｜金额｜状态｜生成时点。
- 分页；空态 `暂无账单`；失败态原样展示服务端文案（1011C0009 等）。
- 权限：登录可见（无 `meta.permission`）；不出现 `admin` 字面量、不出现 `/billing` 端点直拼。

**P11 门户账单详情（`/portal/bills/:billNo`）**
- 头区：账单号 + 状态标签 + `返回列表`；**零写动作**（无确认 / 更正 / 出账）。
- 快照区 + 明细区 + 明细合计对照 + 更正链（与 P3 同构，写动作区隐藏）。
- 错误态：1011C0009 → `账单不存在或不可读`（同码同文，不区分"不存在"与"非本方"）。

## 4. 路由与菜单

| 路径 | name | 组件 | 菜单 | menuOrder | meta.permission | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| `/billing` | `billing` | `views/billing/BillingManageView.vue` | 计费管理 | 24 | `billing.governance` | admin 可见 |
| `/billing/bills/:billNo` | `billing-detail` | `views/billing/BillDetailView.vue` | — | — | `billing.governance` | 深链 |
| `/portal/bills` | `portal-bills` | `views/portal/PortalBillListView.vue` | 我的账单 | 25 | 无 | 登录可见 |
| `/portal/bills/:billNo` | `portal-bill-detail` | `views/portal/PortalBillDetailView.vue` | — | — | 无 | 深链 |

- 菜单基线变化：原始 `meta.menu = 23` → **25**；`MainLayout.spec.ts` 普通用户可见 **10 → 11**、admin **23 → 25**（spec 断言同步更新）；命名路由 36 → **40**。
- `vite.config.ts` 新增转发键：`/api/v1/billing` → `http://localhost:8089`、`/api/v1/metering` → `http://localhost:8089`（**必须**放在兜底 `/api` → 8080 之前，沿既有键序）。

## 5. 演示身份

- `stores/demoIdentity.ts` 新增 `billingRolesHeader()`：运营档 `admin` / 普通档 `requester,provider`（`pageRolesHeader(operatorRoles, normalRoles)` 复用，第二参数非空）；
- 运营页（`/billing/**`）用 `billingRolesHeader()`（admin 档同时具备 `billing.governance` + `metering.governance`；Tab4 需 `contract.governance`，admin 档在 contract-service yml 已有）；
- 门户页（`/portal/bills/**`）普通档 `requester,provider`（`myRole` 由服务端判定，前端不用角色头猜）；
- 硬约束：门户视图与 `constants/billing.ts` 零 `admin` 字面量（`PortalSourceGuard.spec.ts` 既有静态断言）。

## 6. 动作矩阵

| 动作 | 端点 | 界面前置（仅体验层） | 成功表现 | 失败表现 |
| --- | --- | --- | --- | --- |
| 出账 | B-W1 | 合约号非空 | 提示 + 跳新单详情 | 弹窗内原样展示服务端文案（C0010/C0013/C0014/C0015/1000C0001） |
| 确认 | B-W2 | 状态 `ISSUED`（其余禁用 + tooltip） | 详情刷新为 `CONFIRMED` | 409 文案原样展示（C0011/C0012）+ 自动刷新详情 |
| 更正 | B-W3 | 金额与原因非空、金额形态合法 | 跳转更正单详情 | 弹窗内原样展示（C0011/C0012/1000C0001） |
| 执行对账 | B-W4 | 合约号 + 账期非空 | 结论卡 + 差异表 + 记录列表刷新 | `el-alert` 原样展示（C0009/C0014/1000C0001） |
| 查账单列表 | B-R3 / B-R1 | 无 | 列表 + 分页 | `el-alert` + 重试 |
| 查账单详情 | B-R2 | `billNo` 非空 | 详情分区 | 1011C0009 整页空态；其他错误 `el-alert` |
| 查对账记录 | B-R4 | 合约号非空 | 记录列表 | 原样展示 |
| 查计量流水 | R-M4 | 无（时间区间可选） | 列表 + 分页 | 原样展示（1000C0001） |
| 查订单 | R-OR3 | 无 | 列表 + 分页 | 原样展示（1011C0003） |

- **前端不做业务判定**：状态门槛、金额校验、费率档位、归属一律以后端为准；界面仅做"空值拦截 + 禁用态提示 + 文案展示"。
- **不做乐观更新**：所有写动作成功后重新拉取详情/列表。
- **不做假幂等**：无幂等键；按钮 in-flight 禁用仅防误触。

## 7. 标签与错误文案口径

- 标签映射见 §2.3；**未知枚举值一律原样显示**（不隐藏、不猜测），防上游扩展导致界面失真；
- 错误展示规则：`code = 1011C0009` 详情页整页空态；`1011C0010/C0011/C0012/C0013/C0014/C0015` 原样文案；`1000C0001` 参数非法原样文案；网络异常显示 `请求失败，请稍后重试`（不含内部细节）；
- **禁止**把 `traceId`、堆栈、SQL 展现在界面；
- 自由文本（更正原因、账单说明、差异说明）一律模板插值转义，**billing 视图零 `v-html`**。

## 8. 剧本步骤 → 界面入口映射

| 剧本步骤（业务判定不改） | 界面入口（本卡落地后） | 说明 |
| --- | --- | --- |
| C-7.2 S2-1 手动出账（P1 按次） | 计费管理 → Tab1 →「手动出账」→ 填合约 + 账期 → 提交 | 成功跳新账单详情，核对金额 / 数量 / 明细逐笔 |
| C-7.2 S2-2 重复出账被拒 | 同 P2 再次提交 | 弹窗内 `该合约该周期账单已存在，请勿重复出账` |
| C-7.2 S2-3 包月出账 | 同 P2（包月合约） | 金额 = 周期费、数量为空 |
| C-7.2 S2-4 分成出账 | 同 P2 + 填约定交易额 | 详情可见基数与费率双快照；缺基数 → 400 文案 |
| C-7.2 S2-5 需方复算 | 门户「我的账单」→ 详情 → 明细合计 + 快照 | 合计与账单金额并列；不一致提示以服务端为准 |
| C-7.2 S2-6 改价不追溯 | 供方在「产品上架」改价 → 门户查看已出账单 | 金额与明细逐字不变（界面无改账单入口） |
| C-7.2 S2-7 直接修改被拒 + 更正 | 详情页无"修改金额"入口（结构性拒绝）→「更正」弹窗 | 更正单生成、原单不变、更正链双向可见 |
| C-7.2 S2-8 编号抽查 | Tab1 列表（账单号列，含更正单） | 编号唯一、不复用 |
| C-7.2 S2-9 需方/供方各查本方 | 门户「我的账单」（需方 A / 供方 B 各登录） | `我的角色` 列标注；结果仅本方 |
| C-7.2 S2-10 非本方被拒 | 主体 C 直链 `/portal/bills/{billNo}` | 整页空态 `账单不存在或不可读`（同码同文） |
| C-7.2 S1-5 免费档无账单 | Tab1「手动出账」选免费档合约 | 409 `合约不满足出账条件...`（C0015）；账单零新增 |
| 账单核对 S1-1/S1-2 对账通过 | Tab2 → 填合约 + 账期 →「执行对账」 | 结论「通过」+ 四方数值一致 + 记录留痕 |
| 账单核对 S1-3 需方读对账结论 | 门户账单详情 → 对账结论区（只读列表） | 本方合约结论 + 差异明细可读；主体 C 空态 |
| 账单核对 S2-2 差异必报 | Tab2 重跑对账 | 结论「差异」+ 七类差异逐条可读 |
| 账单核对 S2-3 冻结态确认被拒 | 详情「确认」（FROZEN 禁用 + 服务端兜底） | 409 `账单已冻结...`（C0011） |
| 账单核对 S2-5 还原后解冻 | Tab2 再次对账 → 通过 → 详情确认可用 | 状态恢复 `ISSUED`/`CONFIRMED`，确认 200 |
| C-7.1 S2-7 运营订单检索 | Tab4 → 按产品 / 需方 / 状态分别检索 | 三次检索结果正确（B4-2） |
| （B12-2 全量计量流水） | Tab3 → 过滤 / 分页 | 逐条可读（R-M4） |

## 9. 测试锚点规划（测试先行：红相先行，编码段回填实测类名与例数）

| # | 锚 | 用例要点（Given/When/Then） | 落点 |
| --- | --- | --- | --- |
| **T1** | 运营账单列表与过滤分页 | 给 3 条账单 → 打开 Tab1 → 列表 3 行、金额字符串直显、状态标签正确；改状态过滤 → 触发带参请求、重置回第 1 页；空结果 → 空态文案；接口失败 → `el-alert` 展示服务端 message | `views/billing/BillingManageView.spec.ts` |
| **T2** | 手动出账三档与拒绝 | 填合约 + 账期 → 提交体含 `contractNo/period`；分成档填基数 → 体含 `agreedTransactionAmount`；409 C0010 / 400 C0013 / 409 C0015 → 弹窗内原样展示、弹窗不关闭；成功 → 跳详情 | 同上 |
| **T3** | 账单详情五分区 | 给完整 `BillDetail` → 快照字段齐（数量/单价/基数/费率按档显示）、明细逐行、留痕、更正链、合计对照（相等/不等两态） | `views/billing/BillDetailView.spec.ts` |
| **T4** | 确认动作与状态门槛 | `ISSUED` → 确认可点、成功后刷新为 `CONFIRMED`；`FROZEN` → 确认禁用 + 提示；服务端 409 C0011 → 原样展示并刷新 | 同上 |
| **T5** | 更正单弹窗 | 空金额 / 空原因 → 前端拦截；负金额 → 请求体 `amount = "-3.00"`；成功 → 跳转更正单；409 → 原样展示 | 同上 |
| **T6** | 对账工作台（正/负双向） | PASS → 结论「通过」+ 四方数值 + 差异表空态；DIFFERENCE → 结论「差异」+ 七类差异逐条（type/expected/actual/detail 渲染）+ 冻结提示；执行对账失败 → 原样展示 C0009/C0014 | `views/billing/components/ReconcilePanel.spec.ts` |
| **T7** | 对账记录列表 | 分页请求参数、结论标签、点击记录回填结论卡 | 同上 |
| **T8** | 计量流水治理页 | 列表渲染九列；合约/主体/源类型/时间过滤生成正确 query；分页；空态；1000C0001 原样展示 | `views/billing/BillingManageView.spec.ts` |
| **T9** | 订单检索页 | 三次过滤（产品 / 需方 / 状态）分别触发 R-OR3 且参数正确；行内无详情链接（断言零 `router-link` 到订单详情）；1011C0003 原样展示 | 同上 |
| **T10** | 费率视图只读 | 四档卡片文案齐；「前往产品上架页」指向 `/catalog/products`；断言该 Tab 零输入框 / 零提交按钮（只读结构性保证） | 同上 |
| **T11** | 门户我的账单 | 列表渲染 + `myRole` 中文；过滤与分页；空态；失败文案原样 | `views/portal/PortalBillListView.spec.ts` |
| **T12** | 门户账单详情只读 | 快照 + 明细 + 合计对照 + 更正链；**断言零确认/更正/出账按钮**；1011C0009 → 空态 | `views/portal/PortalBillDetailView.spec.ts` |
| **T13** | XSS 渲染锚（DB-103 ②） | 更正原因 / 差异说明注入 `<img src=x onerror="window.__xss=1">` → 渲染为文本（`text()` 命中原文、DOM 无 `img` 元素、`window.__xss` 未定义） | `views/billing/BillDetailView.spec.ts` |
| **T14** | 路由与菜单 | `/billing` 需 `billing.governance`（普通档访问 → `/dashboard?denied=1`）；`/portal/bills` 登录可见；两深链可达；`MainLayout.spec.ts` 菜单计数改为普通 11 / admin 25 | `router/router.spec.ts`、`layouts/MainLayout.spec.ts` |
| **T15** | 静态纪律锚 | billing 视图 + `constants/billing.ts` 零 `v-html`、零直拼 `/api/v1/billing`、零 `admin` 字面量（门户视图）、`api/billing.ts` 单点封装端点；`PortalSourceGuard.spec.ts` 三处断言按新口径更新（`/billing`、`/bills`、`/metering` 由"全域禁用"改为"仅经 api/billing.ts 单点封装"，`/payments`、`/invoices` 仍全域禁用） | `views/portal/PortalSourceGuard.spec.ts` |
| **T16** | 后端 R-M4（新端点） | ① 全量分页（3 条 → total=3、按 occurred_at 倒序）；② 合约/主体/源类型/时间区间过滤各一；③ 非运营（requester）→ 403；④ 非法 contractNo / sourceType / 时间格式 / `from > to` → 400 1000C0001；⑤ 3.7.5 既有 R-M1 非当事方仍 404 1011C0008（零回归） | `interfaces/MeteringGovernanceIntegrationTest.java` |

- 前端预计新增 **100~130 例**（58 文件 749 例 → 约 63 文件 860± 例）；后端新增 **5~7 例**（metering-billing 48 → 53~55）。
- **反向锚必做**：T4/T6/T13/T15/T16③⑤ 均为"绕过被拒 / 渲染安全 / 只读结构"类负向断言，禁止只写 happy path。

## 10. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 `feat/WBS-3.7.7-计费管理界面` 自本地 main 梢 `8603098` 创建、工作树 clean（本地 main ahead 3 随本卡 PR 入库）；
2. `git log -1` 与 `origin/main` 对比（本地 ahead 3 = 收口批 `d9c0722` + 回填批 `771afe6` + 勘正批 `8603098`）；
3. 前端基线复跑：`npm run test`（58 文件 749 例）/ `typecheck` / `lint` / `build` 全绿后开工；
4. metering-billing 基线复跑：48 例全绿、`checkstyle:check` 0 违规；
5. 实测 3.7.6 八端点可直连（8089）返回预期形态（含 B-R2 admin 读任意账单、B-R4 admin 读任意合约）；
6. 实测 `GET /api/v1/orders/governance` 可返回（Tab4 零后端改动前提）；
7. 实测 `MeteringSourceType` 四值 / `MeteringFlow` 列面 / `metering_flow` 索引（新增查询走 `idx_flow_contract` 或全表分页可接受）；
8. `PortalSourceGuard.spec.ts` 现有三处断言逐条读原实现（改口径前记录原文，避免误放宽）；
9. `vite.config.ts` 键序确认（新键必须在兜底 `/api` 之前）；
10. `gates-config.json` SHA256 记录基线（本卡预计零改动）；
11. 确认 `common-pagination` 在 metering-billing 已可用（3.7.6 已引入）；
12. 演示环境端口：8089（计费/计量）、8085（订单检索）、8080（主体）；三容器就绪。

## 11. 复用声明与规格外实现声明

- **复用**：`api/client.ts` `makeDomainJson` / `pageQuery` 工厂；`views/contract/components/` 子组件分件先例；`constants/<域>.ts` 标签常量化先例（delivery/catalog/connector）；`views/connector/ConnectorManageView.vue` 的"列表 + 服务端分页 + 服务端文案原样展示"形态；`views/portal/PortalOrderListView.vue` 的门户列表形态；`common-pagination` `PageQuery/PageResult`；`common-auth` `@RequirePermission`；`common-errorcode` `ApiResult/ErrorCode`；3.7.6 既有 8 端点与 `BillDetailView` 字段（零重写）；3.7.5 `/metering` 前缀与 `MeteringSourceType`。
- **规格外实现声明（应为空）**：`R-M4` 端点属 **3.7.6 验收段 E4 明文归口**（非规格外）；`Tab4` 属 **C-7.1 Q8-A / S2-7 明文归口**；`Tab5` 属 WBS 行 319 第三词的术语映射（Q2 待确认）。
- **零新增**：npm 依赖 / Maven 依赖 / 迁移 / 权限点 / 错误码 / 容器 / 门禁配置。

## 12. 勘误与边界预留（编码段起登记 E1…）

- **E0 记录（立卡段已知边界）**：
  1. **出账前无法预知定价档**：无"按合约查快照"端点，弹窗对"约定交易额"始终展示并标注"仅分成档必填"，服务端缺基数返回 1011C0013 兜底；不在前端猜档位（避免复制上游语义）。
  2. **运营无订单详情读权**（R-OR2 仅当事方，admin 404）：Tab4 行内不提供详情跳转；若后续需要，走规格/设计变更（登记台账）。
  3. **自动出账无可视化配置**（cron / enabled 走配置文件）：界面仅说明"每月 1 日 00:10 自动出上月账"，不提供运行时改配置。
  4. **明细合计为前端算术展示**：仅用于人工核对，任何不一致以服务端账单为准，不触发拦截或写动作。
  5. **`PortalSourceGuard.spec.ts` 静态断言口径变更**：属本卡**预期内的测试基线变更**（3.7.3 / 3.7.4 开放订单 / 评价时同型处理），须在交付说明中明示，不得静默放宽（`/payments`、`/invoices` 仍全域禁用）。
  6. **分页上限**：R-M4 沿 `PageQuery` 上限 100；超出由 `common-pagination` 既有口径处理（不新增错误码）。

- **编码段勘误（E1…，实现与本文差异的登记；断言/业务判定零改动）**：
  1. **E1（§2.3 常量表两项改为复用）**：`PRICING_MODEL_LABELS`（四档中文）与 `MY_ROLE_LABELS`（需方 / 供方）**不在 `constants/billing.ts` 重复定义**，分别复用 `constants/catalog.ts` / `constants/order.ts` 既有单一源（同一枚举不二写，避免重复定义被一致性评审打回；两份常量值与后端枚举逐字一致）。`constants/billing.ts` 保留其余全部常量（状态 / 类型 / 四档口径说明 / 结论 / 七类差异 / 留痕动作 / 源类型 / 提示文案）。
  2. **E2（§3 P11 补「对账结论」只读区）**：§3 P11 原文未列该区，但 §8 剧本映射行「账单核对 S1-3 需方读对账结论 → 门户账单详情 → 对账结论区（只读列表）」为已确认契约——实现按 §8 落地：门户详情底部「对账结论」区调 `listReconciliations(contractNo)`（B-R4，`billing.read` + 服务端归属校验），结论 / 差异明细只读；该区读取失败仅本区提示，不影响账单主体（不伪造结论）。
  3. **E3（§9 T15 落点拆分）**：billing 视图 + `constants/billing.ts` + `api/billing.ts` 的静态纪律锚**新建** `frontend/src/views/billing/BillingSourceGuard.spec.ts`；`PortalSourceGuard.spec.ts` 只做门户侧三处断言口径更新（`/billing`、`/bills`、`/metering` → 仅经 `api/billing.ts` 单点封装；`/payments`、`/invoices` 仍全域禁用）。断言内容与 T15 要求逐条一致，仅分文件承载（避免"门户守卫"文件扫描计费域造成的职责混淆）。
  4. **E4（§9 T8 时间区间断言分层）**：`datetimerange` 控件在 jsdom 下不可靠驱动，`from`/`to` 查询串断言下沉到 `api/billing.spec.ts`（`listGovernanceMeteringFlows` 精确 URL 断言，含 `+` / `%3A` 编码），视图层仅断言控件存在；过滤语义覆盖强度不变（服务端侧由 T16② 时间区间集成用例锚定）。
