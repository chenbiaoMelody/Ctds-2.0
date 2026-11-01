# WBS-3.7.3 高保真设计（订购与订单流程）——编码契约

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（✅ 2026-11-01 00:42 编排师一次确认 Q1~Q8 全采 A + D1 不拆分，编码契约即时生效）** |
| 任务卡 | `docs/tasks/WBS-3.7.3-订购与订单流程-2026-11-01.md` |
| 低保真 | `docs/designs/WBS-3.7.3-lofi.md`（方向）+ `docs/designs/WBS-3.7.3-原型-订购与订单.html`（4 画面线框） |
| 输入 | C-7.1-7.2 V1.0（B3 / B4 / B6；B12-1 订单读面）+ C-4.1-4.3 V1.0（行为 2 / 3 / 6）+ C-7.1 剧本 V1.1（S2-1~7 + 13）+ 3.7.2 门户交付 + 3.4.3 合约服务 / 3.4.7 合约工作台 |

## 1. 接口契约表

### 1.1 订单写面（`OrderCommandController`，`/api/v1`）

| 编号 | 方法与路径 | 权限 | 请求 | 响应 | 业务规则 |
| --- | --- | --- | --- | --- | --- |
| W-OR1 | `POST /api/v1/orders`（201） | `contract.deal` | `{productId:number, templateNo:string, templateVersionNo:number, clauseValues:{slots,strategy}, requestId:string}` | `OrderResult` | B3-1~4：预检查进行中订单 → 复用合约发起门槛链（ADMITTED / 在架 / 异主体 / 模板 / 条款）→ **同事务**写订单 + 合约草案 + 双方留痕；幂等键 = 需方 + `requestId` |

- `requestId`：客户端弹窗打开时生成一次（UUID 形态，8~64 字符）；缺失 / 空白 / 超长 → 1011C0003（控制器前置校验，零请求）。
- `clauseValues` 形态与 W5 发起逐字一致（`slots` 槽位值 + `strategy` 五要素）；校验由合约发起链路单点承担，订单不复制。
- 订单专用发起通路调用 `ContractInitiationService.prepare/persist`，**不使用** W5 的内容幂等注解（原因：W5 幂等键含条款指纹且 TTL 内返回首次结果，订单关闭后同内容重购会命中已终止合约——Q6-A）。

### 1.2 订单读面（`OrderQueryController`，`/api/v1`）

| 编号 | 方法与路径 | 权限 | 查询参数 | 响应 | 业务规则 |
| --- | --- | --- | --- | --- | --- |
| R-OR1 | `GET /api/v1/orders/mine` | `contract.read` | `pageNum` / `pageSize` / `status?` / `productId?` / `contractNo?` | `PageResult<OrderSummary>` | B4-1 + B12-1：当前主体为需方或供方的订单；`myRole` 标注 `REQUESTER` / `PROVIDER`；创建时间倒序 |
| R-OR2 | `GET /api/v1/orders/{orderNo}` | `contract.read` | — | `OrderDetail` | B4-1 / B4-3 / B4-4：仅需方 / 供方可读；非本方与不存在**同码同文** 1011C0002（防枚举）+ 拒绝留痕；含订单留痕时间线 |
| R-OR3 | `GET /api/v1/orders/governance` | `contract.governance` | `pageNum` / `pageSize` / `status?` / `productId?` / `contractNo?` / `requesterNo?` | `PageResult<OrderSummary>` | B4-2：运营方全量检索；界面归 3.7.7（本卡后端验证） |

- 分页：`pageNum ≥ 1`、`pageSize 1~100`（沿 platform 分页惯例；缺省 1 / 10）；非法 → **1000C0001**（勘误 E6：分页沿 common 1000C0001，不落 1011 段——与 WBS-3.1.11 / 3.2.6 / 3.3.4 仓内惯例一致）。
- `status` 仅接受 `IN_PROGRESS` / `COMPLETED` / `CLOSED`；非法 → 1011C0003。
- `productId` 正整数；非法 → 1011C0003。`contractNo` trim 后 ≤32 字符（超长 → 1011C0003）、不校验 `CO`+6 位前缀形态；`requesterNo`（仅治理读面）仅 trim、不限长——非法值过滤为空集（勘误 E6：行为无害；复审勘误 E8：requesterNo 原表述失实已更正）。
- 排序：`created_at DESC, id DESC`；`PageResult` 字段沿 common-pagination（`list` / `total` / `pageNum` / `pageSize`）。

### 1.3 复用端点（零新增 / 零语义改动）

| 用途 | 端点 | 说明 |
| --- | --- | --- |
| 合约发起（门槛链 / 模板 / 条款 / 快照） | `POST /api/v1/contracts`（W5） | 订单专用通路复用应用层 `ContractInitiationService`，不新增对外端点；W5 行为 / 幂等 / 错误码零变更 |
| 合约协商 / 签署 / 终止 / 解除 | W6~W11 | 订单不新增合约动作；状态联动挂接在既有 `sign` / `terminate` 应用服务 |
| 合约详情 / 工作台深链 | `GET /api/v1/contracts/{contractNo}`；前端 `/contracts/workbench/:contractNo` | 订单详情提供关联合约链接 |
| 产品详情 / 定价 | R7 `/api/v1/data-products/{productId}` | 门户详情既有读面；订单快照与合约快照同源 |

### 1.4 响应字段

**`OrderResult`（W-OR1，201）**
```
orderNo: string            // OD + 6 位
status: string             // IN_PROGRESS
contractNo: string         // CO + 6 位
contractStatus: string     // NEGOTIATING
productId: number
productName: string
providerSubjectNo: string
requesterSubjectNo: string
pricingModel: string       // FREE / PER_CALL / MONTHLY / REVENUE_SHARE（原值）
priceAmount: string|null   // BigDecimal.toPlainString；免费 / 未登记 = null
templateNo: string
templateVersionNo: number
createdAt: string
```

**`OrderSummary`（R-OR1 / R-OR3）**
```
orderNo, status, contractNo, contractStatus, productId, productName,
providerSubjectNo, requesterSubjectNo, pricingModel, priceAmount, createdAt,
myRole?: 'REQUESTER'|'PROVIDER'   // 仅 mine 面
```

**`OrderDetail`（R-OR2）** = `OrderSummary` + `templateNo` / `templateVersionNo` / `closedReason:string|null` / `closedAt:string|null` / `actions: OrderActionView[]`。

**`OrderActionView`**
```
action: 'CREATE'|'COMPLETE'|'CLOSE'|'DENIED_ACCESS'
fromStatus: string|null
toStatus: string|null
reason: string|null
operatorSubjectNo: string|null
createdAt: string
```
> 用户读面不返回 `DENIED_ACCESS` 行；拒绝留痕仅供治理与审计。

### 1.5 错误码（1011 段定稿；全库实测零占用）

| 码值 | HTTP | 语义 | 文案（服务端常量） |
| --- | --- | --- | --- |
| `1011C0001` | 409 | 同需方同产品已有进行中订单 | 该产品已有进行中订单，请勿重复订购 |
| `1011C0002` | 404 | 订单不存在或非本方（**同码同文**防枚举） | 订单不存在或无权查看 |
| `1011C0003` | 400 | 请求参数不合法（requestId / 分页 / 状态 / 产品编号） | 请求参数不合法 |
| `1011C0004` | 409 | 订单号并发冲突 / 其他并发写冲突 | 订单正在处理中，请重试 |

- 合约发起门槛链错误（1008C0010 产品不可用 / 1008C0016 自买 / 1008C0017 模板不可用 / 1008C0014·C0015 条款 / 1008C0003 未入驻 / 1008S0001·S0003 不可达）**原样透传**，不包装、不改码。
- 1011S 段不声明（无自有 500 语义；DB 故障交全局处理器）；避免死码（沿 ADR-027 段位说明口径）。

## 2. 数据模型与迁移 V4

### 2.1 `product_order`（订单主表）

| 列 | 类型 | 约束 / 说明 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | 技术主键 |
| `order_no` | VARCHAR(32) | 对外逻辑键；`OD`+6 位；`uk_order_no` |
| `product_id` | BIGINT | 产品 id（逻辑引用；不建外键） |
| `product_name` | VARCHAR(128) | 产品名快照 |
| `provider_subject_no` | VARCHAR(32) | 提供方快照（= 产品属主） |
| `requester_subject_no` | VARCHAR(32) | 需方（= 发起人） |
| `pricing_model` | VARCHAR(16) | 定价档快照（FREE / PER_CALL / MONTHLY / REVENUE_SHARE） |
| `price_amount` | DECIMAL(12,2) NULL | 定价数值快照（免费恒 NULL） |
| `template_no` | VARCHAR(32) | 模板编号快照 |
| `template_version_no` | INT | 模板版本快照 |
| `contract_no` | VARCHAR(32) | 关联合约 `uk_contract_no` |
| `status` | VARCHAR(16) | `IN_PROGRESS` / `COMPLETED` / `CLOSED` |
| `active_order_key` | VARCHAR(80) GENERATED ALWAYS AS (`CASE WHEN status='IN_PROGRESS' THEN CONCAT(requester_subject_no,'#',product_id) ELSE NULL END`) STORED | `uk_active_order_key`（MySQL 唯一索引允许多 NULL → 终态订单可多份） |
| `closed_reason` | VARCHAR(512) NULL | 关闭原因业务文本（协商终止 / 拒签终止） |
| `closed_at` | DATETIME NULL | 关闭时点 |
| `created_by` | VARCHAR(24) | 创建操作者（= requester） |
| `created_at` | DATETIME | 创建时点 |
| `updated_at` | DATETIME | 更新时间 |

索引：`idx_requester (requester_subject_no, created_at)`、`idx_provider (provider_subject_no, created_at)`、`idx_product (product_id, created_at)`、`idx_status (status, created_at)`。

### 2.2 `product_order_action_log`（订单留痕）

`id` / `order_no` / `action`（CREATE / COMPLETE / CLOSE / DENIED_ACCESS）/ `from_status` / `to_status` / `reason` / `operator_subject_no` / `created_at`；索引 `(order_no, created_at)`。**零敏感原文**：reason 仅业务常量文案，不落条款值 / 合同正文。

### 2.3 `product_order_no_seq`（订单编号序号）

`id` / `next_no`；单行原子取号（沿 `contract_no_seq` 先例）；`OD` + `%06d`。

## 3. 后端模块、事务与联动

### 3.1 类与职责

| 层 | 类 | 职责 |
| --- | --- | --- |
| domain | `Order` / `OrderStatus` / `OrderAction` / `OrderRepository` / `OrderErrorCodes` | 聚合 / 三态 / 动作码 / 仓储接口 / 1011 码表 |
| application | `ContractInitiationService`（**新提取**） | `prepare(cmd)` 门槛链（①~⑤）+ `persist(prepared)` 快照落库（⑥）；`initiate(cmd)` = prepare + persist；W5 行为等价 |
| application | `OrderCommandService` | 创建编排（requestId 校验 → 预检查 → prepare → 事务 { persist + 订单落库 + 留痕 }）；`@Idempotent` 键 = 需方 + requestId |
| application | `OrderQueryService` | mine / detail / governance；归属校验；订单留痕时间线；`contractStatus` 批量读取 |
| application | `ContractOrderSyncService`（**新**，`@Transactional`） | 合约 `sign` / `terminate` 与订单状态更新原子提交（见 §3.3） |
| infrastructure | `JdbcOrderRepository` | JDBC 仓储；取号 / 插入 / 条件更新 / 分页查询 / 留痕插入 |
| infrastructure | `ContractStatusReader` | 批量读取关联合约状态（只读；`SELECT contract_no,status FROM contract WHERE contract_no IN (...)`） |
| interfaces | `OrderCommandController` / `OrderQueryController`（1011 HTTP 映射经 `ContractExceptionHandler` 登记制——勘误 E2，不设独立 advice） | 端点 / 参数校验 / 1011 HTTP 映射 |
| interfaces | DTO | `OrderRequests.Create` / `OrderViews.Result` / `Summary` / `Detail` / `Action` |

### 3.2 创建流程与事务边界

1. **控制器**：认证 + `contract.deal` → 校验 `requestId` / `productId` / `templateNo` / `templateVersionNo`（缺失或非法 → 1011C0003，零业务请求）→ 组装 `CreateOrderCommand`（requester = `AuthContext.subject()`）→ 调应用服务。
2. **应用服务（@Idempotent）**：预检查 `findActive(requester, productId)`（存在 → 1011C0001）→ `prepared = contractInitiationService.prepare(cmd)`（外部门槛链：ADMITTED / 产品 / 异主体 / 模板 / 条款；**事务外**）→ `TransactionTemplate.execute`：
   - `ContractInitiationService.persist(prepared)`（合约 + 条款版本 V1 + CREATE 留痕；仓储事务加入外层）；
   - `orderRepository.create(order, CREATE 留痕)`（取号 + 插入）；
   - 捕获 `DuplicateKeyException`：回查进行中订单 → 1011C0001；否则 → 1011C0004（**整个事务回滚，不残留合约**）；
   - 返回 `OrderResult`。
3. **快照一致性**：订单的 `product_name / provider_subject_no / pricing_model / price_amount / template_no / template_version_no / contract_no` 全部取自 `PreparedInitiation` 与 `persist` 结果——与合约落库同源（B3-3 / B9-1 上游口径）。

### 3.3 合约状态联动（B3-4）

- `ContractOrderSyncService`（新 bean，`@Transactional`）：
  - `signAndSync(SignCommand)`：调用 `contractRepository.sign(...)`；`outcome == EFFECTED` → `orderRepository.completeByContract(contractNo, now, operatorNo)`（条件 `status='IN_PROGRESS'`；命中 1 行才写 COMPLETE 留痕）。首签（FIRST_SIGNED）不动订单。
  - `terminateAndSync(TerminateCommand)`：调用 `contractRepository.terminate(...)`；`outcome == TERMINATED` 且 `terminationType ∈ {NEGOTIATION_TERMINATED, SIGNATURE_REFUSED}` → `orderRepository.closeByContract(contractNo, 业务原因文案, now, operatorNo)`（条件 `status='IN_PROGRESS'`；命中才写 CLOSE 留痕）。
- `ContractCommandService.sign/terminate` 改为调用上述 bean（DID 调用仍在事务外；合约仓储方法加入外层事务）；`releaseConsent` / `forceTerminate` 不改订单（Q7-A）。
- 关闭原因文案：协商终止 = `合约协商终止`；拒签终止 = `合约拒签终止`；不落内部终止类型码 / 理由原文。
- 幂等：订单状态条件更新 + 订单号唯一 → 重复同步不产生第二条留痕；无订单的合约（经 W5 直接发起）为 no-op。

### 3.4 查询与防枚举

- `mine`：`requester = subject OR provider = subject`；`myRole` 计算；状态 / 产品过滤；分页。
- `detail`：先按 `order_no` 查；不存在或 subject 非双方 → 1011C0002 同码同文；订单存在但越权 → 插 `DENIED_ACCESS` 留痕（order_no + operator），不存在 → 仅应用日志（不伪造订单行）。
- `governance`：`@RequirePermission("contract.governance")`；全量分页检索；不做归属过滤；零写订单状态。
- `contractStatus`：`ContractStatusReader.statusesOf(orderNos -> contractNos)` 批量读取；缺失返回 null（异常数据兜底，不冒充状态）。

### 3.5 网关与开发期转发

- 网关 `application.yml` 新增路由 `connector-orders`：`Path=/api/v1/orders/**` → `${CTDS_GATEWAY_CONTRACT_BASEURL:http://127.0.0.1:8085}`；**普通代理路由**（不带 `delivery-face`，不进交付判定链 / 零计量，沿 `connector-contract` 先例）。
- `GatewayConnectorRoutesIntegrationTest` 路由清单加入 `connector-orders` 并保留既有负向断言。
- `frontend/vite.config.ts` 新增 `/api/v1/orders` → `http://localhost:8085`（开发期界面走查，不改服务契约与门禁配置）。
- **零新服务 / 端口 / 数据库 / 容器 / K8s 清单改动**。

## 4. 前端模块与界面说明书

### 4.1 模块与路由

| 文件 | 内容 |
| --- | --- |
| `src/api/order.ts` | `createOrder` / `listMyOrders` / `getOrderDetail`；`makeDomainJson(contractRolesHeader)`；类型 `OrderSummary` / `OrderDetail` / `OrderActionView` / `OrderResult` / `CreateOrderRequest` |
| `src/constants/order.ts` | `ORDER_STATUS_LABELS`（IN_PROGRESS 进行中 / COMPLETED 已完成（已订购）/ CLOSED 已关闭）/ `ORDER_STATUS_TAG_TYPES` / `ORDER_ACTION_LABELS` / `MY_ROLE_LABELS` / `ORDER_NOT_FOUND_TIP`（原列 `ORDER_DUPLICATED_TIP` 已删——勘误 E6：弹窗无本地重复预检，重复订购提示 = 后端 1011C0001 文案原文，常量本无必要） |
| `src/views/portal/PortalOrderDialog.vue` | 订购弹窗（模板下拉 + 条款框架 + 策略 + 必填前置 + requestId + 提交） |
| `src/views/portal/PortalOrderListView.vue` | 「我的订单」列表（筛选 / 分页 / 空态 / 错误态） |
| `src/views/portal/PortalOrderDetailView.vue` | 订单详情（快照 / 关联合约 / 时间线 / 返回） |
| `src/views/portal/PortalProductDetailView.vue` | **仅改订购区**：启用按钮 + 弹窗挂载 + 成功跳转；其余零改动 |
| `src/router/index.ts` | `/portal/orders`（menuOrder 23，登录可见、无权限点）+ `/portal/orders/:orderNo`（深链，声明在列表之后） |
| `frontend/vite.config.ts` | `/api/v1/orders` → 8085 |

### 4.2 页面一：订购弹窗（产品详情内）

- 标题「订购 · {productName}」；只读展示定价快照（`formatPortalPricing` 同口径）。
- 模板下拉：`listTemplates({pageNum:1,pageSize:50})`（仅启用中）；选中后 `getTemplate` 拉取条款框架。
- 条款：`ClauseEditor mode="framework"`；策略：`StrategyEditor`。
- 必填前置：模板必选 + 框架必填槽位非空 → 否则零请求提示（沿 3.4.7 发起弹窗口径）。
- 底部：提示「提交后将生成订单并同步发起合约草案；协商与签署请在合约工作台完成」；按钮「提交订购」/「取消」。
- 幂等：弹窗打开生成一次 `requestId = newIdempotencyKey()`；重复点击复用同键；关闭重开换新键。
- 成功：关闭弹窗 + 路由到 `/portal/orders/{orderNo}`；失败：后端文案原样展示，弹窗状态不变（不做乐观更新）。
- 运营档：产品详情不显示订购入口（沿用 3.7.2 `isProviderMode` 显隐；服务端仍为判定源）。

### 4.3 页面二：我的订单（`/portal/orders`，menuOrder 23）

- 页头：返回「← 返回数据产品门户」+ 标题「我的订单」。
- 筛选条：状态下拉（全部 / 进行中 / 已完成 / 已关闭）+「查询」/「重置」。
- 表格列：订单号 / 产品名称 / 定价（`formatPortalPricing`）/ 我的角色（需方 / 供方）/ 状态 tag / 关联合约号 / 发起时间 /「查看详情」。
- 分页 10/页；空态「暂无订单」；加载 / 错误态（错误文案原样；认证失效经 `guideToLoginIfAuthFailed` 回登录）。
- 只读：不做订单取消 / 修改（规格未定义订单侧动作；状态随合约走）。

### 4.4 页面三：订单详情（`/portal/orders/:orderNo`）

- 页头：返回「← 返回我的订单」+ 订单号 + 状态 tag。
- 基本信息：产品 / 需方 / 供方 / 定价快照 / 模板与版本 / 发起时间 / 关联合约号 +「前往合约工作台」（`/contracts/workbench/{contractNo}`）。
- 状态区：订单状态 + 关联合约状态（`CONTRACT_STATUS_LABELS` 复用）；已关闭时展示关闭原因与关闭时间。
- 时间线：订单留痕（创建 / 完成 / 关闭）按时间正序；不含 `DENIED_ACCESS`。
- 非本方 / 不存在 / 非法订单号：同一条提示 `ORDER_NOT_FOUND_TIP`（非法编号前端前置校验零请求；服务端同形兜底）。
- 空态 / 加载 / 错误态同上；不做评价 / 账单 / 支付入口。

### 4.5 动作矩阵（角色 × 动作；服务端强制，界面仅显隐）

| 角色 | 发起订购 | 我的订单 | 订单详情（本方） | 订单详情（非本方） | 运营检索（界面） |
| --- | --- | --- | --- | --- | --- |
| 需方（普通档） | ✅ | ✅ | ✅ | ❌ 同形 404 | ❌ |
| 供方（普通档） | ✅（对他人产品） | ✅（含供方角色） | ✅ | ❌ 同形 404 | ❌ |
| 运营方（admin） | ❌ 隐藏（沿用 3.7.2） | ✅（本方为空） | ✅（本方） | ❌ 同形 404 | 归 3.7.7（本卡后端） |

### 4.6 文案与常量（单一源）

- 状态：`IN_PROGRESS` 进行中 / `COMPLETED` 已完成（已订购）/ `CLOSED` 已关闭。
- 动作：`CREATE` 创建订单 / `COMPLETE` 合约生效，订单完成 / `CLOSE` 合约终止，订单关闭。
- 角色：`REQUESTER` 需方 / `PROVIDER` 供方。
- 提示：重复订购 = 后端 1011C0001 文案原样；非本方 / 不存在 = `ORDER_NOT_FOUND_TIP`；合约链接文案「前往合约工作台」。
- 定价展示复用 `constants/catalog.ts` 的 `formatPortalPricing`（免费 / 付费统一口径），不新增第二套定价文案。

## 5. 规格 / 剧本 → 页面 / 端点映射

| 规格 | 端点 / 界面 | 剧本步骤 |
| --- | --- | --- |
| B3-1 | W-OR1；订购弹窗 → 订单详情 | S2-1 |
| B3-2 | W-OR1（1011C0001）；弹窗错误提示 | S2-2 |
| B3-3 | W-OR1 同事务 + 订单详情关联合约链接 | S2-3 |
| B3-4 | `ContractOrderSyncService`；订单详情状态与时间线 | S2-4 / S2-5 |
| B4-1 | R-OR1 / R-OR2；「我的订单」+ 详情 | S2-13 |
| B4-2 | R-OR3（后端；界面 3.7.7） | S2-7（后端验证） |
| B4-3 | 订单留痕时间线 | S2-13 |
| B4-4 | R-OR2 归属校验 + 同形提示 | S2-6 |
| B6-1 / B6-2 | 无推送 / 无轮询；订单状态即权威 | S2-13（观察点） |
| B12-1 | R-OR1 双角色（`myRole`） | 下游计费剧本前置 |

## 6. 测试计划

### 6.1 后端（约 20~30 例；Testcontainers / 集成口径沿 3.4.3）

- T1 创建成功：订单 + 合约草案同事务；四要素 / 定价快照 / 模板快照与合约逐项一致。
- T2 重复订购：同需方同产品进行中 → 1011C0001；订单仍一份、合约不新增。
- T3 幂等重放：同 `requestId` → 返回同一订单；订单 / 合约数量不变。
- T4 关闭后重购：先创建→终止→关闭，再以新 `requestId` 同内容订购 → 新订单 + 新合约（不复用已终止合约）。
- T5 双签生效：订单 `IN_PROGRESS → COMPLETED` + COMPLETE 留痕；首签不动订单。
- T6 终止：协商终止 / 拒签终止 → `CLOSED` + 关闭原因 + CLOSE 留痕；生效后强制终止 / 合意完结 → 订单保持 `COMPLETED`（Q7-A）。
- T7 读面：需方 / 供方各见本方订单（`myRole`）；分页 / 状态 / 产品过滤；空态。
- T8 治理检索：按产品 / 需方 / 状态组合检索；非 admin 403。
- T9 防枚举：第三方 / 不存在同码同文 1011C0002；越权访问产生拒绝留痕；无数据本体。
- T10 参数校验：requestId 缺失 / 空白 / 超长、非法 status / productId / contractNo → 1011C0003；分页越界 → 1000C0001（勘误 E6）。
- T11 并发：并行同需方同产品创建 → 恰一份订单；失败事务不残留合约；订单号唯一（唯一索引兜底分译：回查命中 → 1011C0001、未命中 → 1011C0004）。
- T12 迁移：三表 / 生成列唯一索引 / 序号表 / 索引存在；`active_order_key` 终态多 NULL 可多份。
- T13 合约回归：既有用例全绿（发起提取 / 事务编排行为等价；基线实测 244——勘误 E1）。
- T14 网关：`connector-orders` 路由存在且普通代理行为正确（随网关测试）。

> **处置回填（修复循环第 1 轮，2026-11-01；勘误 E7）**：编码段 8 例（t1~t6/t9/t10）承载 T1~T6/T9/T10；**修复轮补齐** T6 负向腿（t6b 拒签关闭 / t6c 生效后强制终止不动）、T7（t7a 双角色+非本方不出现 / t7b 过滤+分页）、T8（t8a admin 检索+过滤 / t8b 非 admin 403）、T11（t11 订单号占位法钉 C0004 腿+事务回滚；回查命中 C0001 腿单线程集成面不可达 → `OrderCommandServiceFallbackTranslateTest` 存根单测）、T12（`ContractMigrationIntegrationTest.orderTablesExistWithGeneratedUniqueKey` 三表+STORED 生成列+三唯一索引+序号种子）；t1 追加合约行快照逐项比对、t9 追加读面纵深过滤断言、t10 追加读面校验腿。承载计数：`ProductOrderFlowIntegrationTest` 15 例 + 迁移 1 例 + 兜底分译单测 1 例。

### 6.2 前端（约 45~60 例）

- T1 `api/order.ts`：URL / 方法 / 角色头 / 分页 / 错误透传。
- T2 订购弹窗：模板加载、框架加载、必填前置零请求、`requestId` 复用。
- T3 提交成功：请求体（含条款与 requestId）→ 关闭 + 跳转订单详情。
- T4 重复订购：1011C0001 文案展示、弹窗不重置、无乐观更新。
- T5 运营档：订购入口隐藏；普通档可见。
- T6 我的订单：渲染 / 状态过滤 / 分页 / 空态 / 错误态。
- T7 订单详情：快照 / 状态 / 关联合约链接 / 时间线 / 关闭原因。
- T8 负向：非法订单号零请求；非本方同形提示；认证失效回登录。
- T9 源集守卫：新增订单视图 / API / 常量纳入扫描；零 `/internal/`、零原始 `fetch(`、零 `admin` 混入普通档、零数据本体。
- T10 路由 / 菜单：`/portal/orders` menuOrder 23、深链、普通 10 / admin 23、`menuOrder` 最大 23。
- T11 静态纪律：无推送 / 轮询 / 站内信 / 评价 / 账单入口。
- T12 红相归档：编码段先写失败测试并归档 `build-output/w373-red-*`。

## 7. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 `feat/WBS-3.7.3-订购与订单流程` == origin（首推后）/ 工作区 clean / `main` 未动。
2. 前端基线 **53 文件 / 703 例** 全绿；`typecheck` / `lint` / `build` EXIT=0。
3. contract-service 基线 **184 例** 全绿（Skipped 0）；`checkstyle:check` 0 违规；全仓 `compile` EXIT=0。
4. 网关基线全绿；路由测试清单现状（`connector-contract` 等六门面）实读。
5. 菜单基线 **9 / 22**、`menuOrder` 最大 **22**；`PortalSourceGuard.spec.ts` 扫描面实读。
6. **1011 段零占用**（Java + docs 双查）；错误码格式 `^\d{4}[CBS]\d{4}$`。
7. 无订单表 / 无 order-service 复核；合约表 `pricing_model/price_amount` 列实读。
8. 合约发起 `@Idempotent` 键 / TTL 实读；`sign/terminate` 仓储事务边界实读。
9. 门户详情订购占位实读（`PortalProductDetailView.vue`）；`ClauseEditor` / `StrategyEditor` props 实读。
10. 演示环境三容器 + 8080 / 8081 / 8082 / 8084 / 8085 / 5173 在跑；C-7.1 附录 A 数据齐备。
11. `gates-config.json` `ConfigSha256 955bdbba…2ee2` 零漂移。
12. 网关 `connector-orders` 路由与 vite 转发键的前置状态实读（不存在 = 预期）。

## 8. 边界、勘误与文件清单

- **边界复述**：订单只做 B3 / B4 / B6 + B12-1 订单读面；不改合约端点语义 / 错误码 / 状态机；不做评价 / 计费 / 账单 / 支付 / 运营界面 / 推送；零新服务 / 端口 / 库 / 镜像 / 依赖 / 权限点；零数据本体 / 零内部面 / 零 `admin` 混入普通档；剧本业务判定一字不改。
- **勘误表**：编码段回填（2026-11-01）——
  **E1 基线口径**：任务卡/hifi 记载 contract-service 基线 **184 例**为陈旧口径（3.4.3 时代）；前置检查实测 **244 例**（3.5.3 起即 244/244，git `d567145` 留痕），编码后 252 例全绿——零回归结论不受影响。
  **E2 处理器落点**：§3.1 规划独立 `OrderExceptionHandler`——实做发现双 `@RestControllerAdvice` 均监听 `ContractBizException` 会按类型劫持（先匹配 advice 拿走全部异常，1008 段映射被吞），故 1011 四码并入 `ContractExceptionHandler.MAPPED_CODES` 登记制（码表↔处理器一致性锚同步扩为双段 27 码），接口行为与本表 §1.5 状态映射逐字一致。
  **E3 测试夹具**：§6.1 T 系列以 `ProductOrderFlowIntegrationTest` 8 例承载核心锚（t1~t6/t9/t10）；共享库跨轮残留敏感（B3-2 唯一性对固定主体/产品组合），补 `@BeforeEach` TRUNCATE 清态（序号表保留——编号全局递增不回退）。
  **E4 priceAmount 形态**：§1.4 `priceAmount: string|null`（toPlainString）——前端测试断言须用文本比较（`asText()`），`decimalValue()` 对文本节点返回 0。
  **E5 订购弹窗测试夹具**：模板夹具含两必填槽位——fill 须覆盖全部 required 槽位，否则必填前置拦截（零请求）导致提交链用例假红。
  **E6 读面校验、错误码与常量口径**（修复轮，评审 P2-1/P2-3/P3-8）：§1.2/T10 原声明"分页非法 → 1011C0003"与实现、仓内惯例三方不符——实现委托 common `PageQuery.of`（越界抛 **1000C0001**），与 WBS-3.1.11 / 3.2.6 / 3.3.4 三份既有 hifi"分页非法沿 common 1000C0001"一致，**1011C0003 限 requestId / status / productId / contractNo**，§1.2 与 T10 行已同步标注，代码零改动（行为即惯例）；`contractNo` 不校验 `CO`+6 位前缀形态；`requesterNo` 仅 trim 不限长（§1.2 原表述更正——行为无害，超长值过滤为空集；原"限长 ≤32 与实现一致"失实，见 E8）；§4.6 常量表 `ORDER_DUPLICATED_TIP` 删除（弹窗无本地重复预检——重复订购提示 = 后端 1011C0001 文案原文，该常量零引用本无必要，§4.6 行已同步标注）。
  **E7 测试承载补齐**（修复轮，评审 P1-2）：编码段 8 例未覆盖 §6.1 T6 负向腿 / T7 / T8 / T11 / T12（任务卡 §三 回填句当时失实——已勘正）；修复轮补集成 8 例（t6b / t6c / t7a / t7b / t8a / t8b / t11 + 迁移结构锚）+ 兜底分译单测 1 例（T11 回查命中腿——单线程集成面不可达，存根直调承载），t1 / t9 / t10 各追加读面与快照比对断言；承载明细见 §6.1 处置回填注。
  **E8 requesterNo 校验表述复审更正**（复审勘误批，评审 M1/P2）：E6 与 §1.2 原"`requesterNo` 限长 ≤32 与实现一致"失实——治理读面实现仅 trim 不限长（`OrderQueryController.java:71` → `OrderQueryService` 透传 → `JdbcOrderRepository` 精确等值过滤），超长值过滤为空集、行为无害；§1.2 与 E6 两处已同步更正。`contractNo` 腿"≤32"表述属实（超长 → 1011C0003）。
- **文件清单（预估）**：迁移 V4；后端 order 包（domain / infrastructure / application / interfaces）≈ 12~16 文件；`ContractInitiationService` / `ContractOrderSyncService` / `ContractStatusReader`；网关 yml + 路由测试；前端 3 视图 + 3 spec + `api/order.ts` + `constants/order.ts` + `order.spec.ts` + 路由 / 布局 / 守卫计数更新；C-7.1 剧本；任务卡 / lofi / hifi / 原型 / 台账 / 日志。
- **体量**：预估 2200~3000 行；超 400 行按 D1 登记豁免。
