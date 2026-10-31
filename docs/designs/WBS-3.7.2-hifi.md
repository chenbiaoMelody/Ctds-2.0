# WBS-3.7.2 高保真设计（门户前台框架）——编码契约

| 字段 | 内容 |
| --- | --- |
| 版本 | **V0.9（待编排师一次确认 = Q1~Q6 全采 A + D1 不拆分 → 转 V1.0 编码契约即时生效）** |
| 任务卡 | `docs/tasks/WBS-3.7.2-门户前台框架-2026-10-31.md`；低保真 `docs/designs/WBS-3.7.2-lofi.md`；原型 `docs/designs/WBS-3.7.2-原型-数据产品门户.html` |
| 契约地位 | 实现与本契约不一致 = 打回项（章程 2.6）；标注「**编码段实测锚定**」的字段以后端代码 / 探针实测为准，偏差走 §10 勘误登记 |
| 确认 | 🟡 **待编排师一次确认**（Q1~Q6 + D1）；确认后本文件转 V1.0 |
| 上游 | 规格 `docs/specs/C-7.1-7.2-门户与计费.md` V1.0（B1 / B2 / B6）；目录规格 `docs/specs/C-3.1-2.3-数据目录与资源.md` V1.0（行为 5 / 6）；剧本 `docs/manuals/C-7.1-数据产品门户-验收剧本.md` V1.0（S1）；ADR-005 / ADR-007 / ADR-016 §2.7 / ADR-001 |

## 1. 接口契约表（复用 5 读 + 4 写 + R7 加性字段）

> **总原则**：只调用 `catalog-service` 对外端点；`/internal/**`、治理读面（`/{id}/governance`，会写 `GOVERNANCE_VIEW` 留痕）与任何订单 / 计费端点一律不调用。目录域写端点（收藏 / 订阅）为业务态幂等（uk 兜底），界面**不做假幂等**；路径段编号 `encodeURIComponent`（产品编号为数字，仍沿统一封装）。

### 1.1 读面（`ProductCatalogController`，`/api/v1`）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| R5 | GET | `/catalog/categories` | `catalog.read` | 无 | `CategoryNode[]`（两级；`categoryCode / categoryName / children`） |
| R6 | GET | `/data-products?keyword&categoryCode&pageNum&pageSize` | `catalog.read` | 关键词（名称 / 简介）+ 类目码（含子树）+ 分页；服务端 ADMITTED 门槛 | `PageResult<CatalogProductView>`（**不含 status**，结果恒已上架；无 `priceAmount`） |
| R7 | GET | `/data-products/{productId}` | `catalog.read` | 服务端 ADMITTED 门槛；仅 LISTED | `CatalogProductDetail`（R6 字段 + `status` + **加性 `priceAmount: String \| null`**；非在架 / 不存在 → `1007C0011` 同形拒绝） |
| R9 | GET | `/catalog/favorites?pageNum&pageSize` | `catalog.read` | 恒仅本人 | `PageResult<CatalogFavoriteItemView>`（含 `productStatus`） |
| R10 | GET | `/catalog/subscriptions?pageNum&pageSize` | `catalog.read` | 恒仅本人 | `PageResult<CatalogSubscriptionItemView>`（含 `productStatus`） |

> **R7 加性字段（Q2-A 生效时）**：`PRODUCT_COLUMNS` 增 `p.price_amount`；`CatalogProductRow` 增 `BigDecimal priceAmount`；`CatalogProductDetail` 增 `String priceAmount`（`row.priceAmount() == null ? null : row.priceAmount().toPlainString()`）。**R6 / R9 / R10 出参不变**（其 DTO 不映射该字段）；收藏 / 订阅仓储的 `CatalogProductRow` 构造传 `null`（其 SQL 不查询该列，无契约变化）。**零新端点 / 零迁移 / 零新错误码 / 零新权限点 / 零 `application.yml` 改动**。

### 1.2 互动写面（`ProductInteractionController`，`/api/v1`；用户显式动作才调用）

| # | 方法 | 路径 | 权限 | 请求要点 | 响应 / 关键字段 |
| --- | --- | --- | --- | --- | --- |
| W4 | POST | `/data-products/{productId}/favorite` | `catalog.interact` | 空 body；仅已上架产品 | `FavoriteView`（`productId / favoritedAt`；重复收藏幂等） |
| W5 | DELETE | `/data-products/{productId}/favorite` | `catalog.interact` | 无 | `FavoriteView` |
| W6 | POST | `/data-products/{productId}/subscription` | `catalog.interact` | 空 body；仅已上架产品 | `SubscriptionView`（`productId / subscribedAt`） |
| W7 | DELETE | `/data-products/{productId}/subscription` | `catalog.interact` | 无 | `SubscriptionView` |

### 1.3 明确不调用

- `GET /data-products/{productId}/change-logs`（R8）——门户详情不展示变更记录（订阅者可在「数据目录 → 我的订阅 / 详情」查看；C-7.1 未要求）；
- `GET /data-products/{productId}/governance` / `/datasets/{id}/governance`——治理读面会写 `GOVERNANCE_VIEW` 留痕，前台浏览不得使用；
- 资源登记 / 产品管理 / 上下架 / 注销 / 力量下架等写面——归提供方管理页（3.3.6 已交付），门户不出现；
- 任何 `/internal/**`、订单 / 合约 / 交付 / 计量 / 账单 / 评价端点（未实现或归下游）。

## 2. 前端模块与类型

### 2.1 `src/api/catalog.ts`（无新函数；仅类型扩展）

```ts
/** 产品详情（R7：R6 字段 + status + 加性 priceAmount；免费档 priceAmount = null）。 */
export interface CatalogProductDetail extends CatalogProduct {
  status: string
  createdAt: string
  /** WBS-3.7.2 Q2-A：定价数值（BigDecimal.toPlainString；免费 / 未登记 = null）。 */
  priceAmount: string | null
}
```

- 复用既有函数：`listCategories()` / `searchProducts()` / `getProductDetail()` / `listFavorites()` / `listSubscriptions()` / `favoriteProduct()` / `unfavoriteProduct()` / `subscribeProduct()` / `unsubscribeProduct()`；
- 角色头：既有 `catalogRolesHeader()`（普通档 `provider` / 运营档 `admin`）——**零新身份函数**；
- 空主体前置拦截由 `makeDomainJson` 承载（零请求，沿 DB-41）。

### 2.2 `src/constants/catalog.ts`（新增定价展示助手）

```ts
/**
 * 门户定价展示（单一常量源；免费档显式"免费"，不空缺）：
 * - FREE → "免费"；
 * - 付费档 + 数值 → "按次（3.00）" / "包月（19.90）" / "交易额分成（5.00）"；
 * - 付费档无数值（理论不发生：上架门槛要求付费档有价格）→ 仅模型名。
 */
export function formatPortalPricing(pricingModel: string, priceAmount: string | null | undefined): string {
  if (!priceAmount) return pricingModel
  return `${pricingModel}（${priceAmount}）`
}

/** 定价 tag 色（R6/R7 定价档为中文显示名——DB-36 双轨；按显示名反查枚举键取色）。 */
export function pricingModelTagType(value: string): string {
  const key = Object.entries(PRICING_MODEL_LABELS).find(([, label]) => label === value)?.[0]
  return key === 'FREE' ? 'success' : 'info'
}
```

- 复用既有：`PRICING_MODEL_LABELS` / `PRODUCT_STATUS_LABELS` / `productStatusType()` / `PRODUCT_NOT_ACCESSIBLE_TIP` / `SEARCH_EMPTY_TIP` / `INTERACTION_ACTION_LABELS` / `labelOf()`；
- **不新增**任何价格单位 / 币种文案（契约无数值单位，界面只展示登记数值，不臆造"元/次/月"）。

### 2.3 路由与菜单（`src/router/index.ts`）

```ts
{
  path: 'portal',
  name: 'portal-home',
  component: () => import('../views/portal/PortalHomeView.vue'),
  meta: { title: '数据产品门户', menu: true, menuOrder: 22, icon: 'ShoppingCart' },
},
{
  path: 'portal/products/:productId',
  name: 'portal-product-detail',
  component: () => import('../views/portal/PortalProductDetailView.vue'),
  meta: { title: '门户产品详情' },
},
```

- 菜单顺序：声明于连接器两菜单之后（menuOrder 22 末尾追加，不重排既有项）；深链须声明在 `/portal` 之后；
- **无权限点**（登录可见；服务端 `catalog.read` + ADMITTED 门槛）；登录守卫既有 `beforeEach` 自动覆盖（B1-2）；
- 菜单基线：普通用户 **8 → 9**（+ 数据产品门户）；admin **21 → 22**（+ 数据产品门户）；`router.spec.ts` 两处最大值断言 **21 → 22**；`MainLayout.spec.ts` 计数 / 文案断言；e2e `login-smoke` 补一条菜单可见断言（不计数）。

### 2.4 视图与测试文件

| 文件 | 说明 |
| --- | --- |
| `frontend/src/views/portal/PortalHomeView.vue` | 门户首页（检索 + 类目树 + 卡片网格 + 分页） |
| `frontend/src/views/portal/PortalProductDetailView.vue` | 门户详情（定价 + 收藏 / 订阅 + 订购禁用占位 + 不可达） |
| `frontend/src/views/portal/PortalHomeView.spec.ts` | T3~T5 |
| `frontend/src/views/portal/PortalProductDetailView.spec.ts` | T6~T7 |
| `frontend/src/views/portal/PortalSourceGuard.spec.ts` | T9 源集守卫 |
| `frontend/src/router/router.spec.ts` / `layouts/MainLayout.spec.ts` / `e2e/login-smoke.spec.ts` | 计数与可见性更新 |

> **边界**：不新增组件库 / 样式体系；卡片与详情用 Element Plus 既有组件 + 既有页面样式变量；两视图独立实现，**不复用 3.3.x 的 `PortalView.vue` / `ProductDetailView.vue`**（避免改动已验收页面）。

## 3. 界面说明书

### 3.1 页面一：数据产品门户首页（`/portal`）

**布局**：页头（标题「数据产品门户」+ 说明「浏览平台已上架的数据产品；订购与评价将在后续版本开放。」）→ 检索区（关键词输入 + 查询 / 重置）→ 主体两栏（左 220px 类目树 + 右结果区）。

**检索区**：
- 关键词输入：`maxlength=64`，占位「产品名称 / 简介关键字」；查询 = 页码重置 1 + 以当前关键词请求 R6；
- 重置 = 清空关键词 / 类目 + 重新请求；
- 类目树：R5 全量两级，`default-expand-all`，点击节点 = 以该 `categoryCode` 过滤（页码重置 1）；顶部「全部」清除类目过滤；
- **无排序 / 无形态筛选 / 无本地过滤**（Q4-A）。

**结果区**：
- 卡片网格（响应式：宽屏 3 列、中屏 2 列、窄屏 1 列）；每卡：
  - 名称（单行省略 + tooltip）；
  - 定价标签：`PRICING_MODEL_LABELS` 中文直出；tag 类型由 `pricingModelTagType()` 判定（**免费档 success，其余 info**——仅视觉，不含业务判定）；
  - 简介（最多两行省略；空显示「—」）；
  - 形态 / 类目 / 提供方主体编号 / 上架时间（`listedAt`，空「—」）；
  - 「查看详情」按钮 → `/portal/products/{productId}`；
- 空态：`SEARCH_EMPTY_TIP`（空态不是报错）；
- 加载态：卡片区 `v-loading`；错误态：`ElMessage.error(err.message)`（401/403 走 `guideToLoginIfAuthFailed`），列表保持空态；
- 分页：`pageSize=12`，`PageData.total` 驱动，服务端分页；页码越界由服务端 `1000C0001` 兜底。

**数据加载**：进入页面并行调 R5 + R6（第 1 页）；后续筛选 / 翻页只调 R6；**浏览是只读动作，页面加载不调用任何写端点**（B1-3）。

### 3.2 页面二：门户产品详情（`/portal/products/:productId`）

**数据加载**：进入即调 R7；普通档并行调 R9 / R10（`pageSize=100`，第 1 页）判断本人收藏 / 订阅状态（体验层，服务端仍为判定源）；运营档不调 R9/R10、不渲染动作条。

**页头**：返回链接「← 返回数据产品门户」+ 产品名称 + 状态 tag（R7 `status`，通常"已上架"）+ 定价标签。

**元数据区**（`el-descriptions` 两列）：
- 简介（全文；空「—」）；
- 形态（R7 中文显示名）；
- **定价**：`formatPortalPricing(detail.pricingModel, detail.priceAmount)`——免费档显式"免费"，付费档"按次（3.00）"；
- 类目（空「—」）；
- 提供方主体编号；
- 上架时间（空「—」）。

**动作条（仅普通档）**：
- 收藏 / 取消收藏（W4 / W5）：按 R9 初查状态显隐；成功后 `ElMessage.success` + 重拉 R9 状态；失败文案原样；
- 订阅 / 退订（W6 / W7）：按 R10 初查状态显隐；成功后提示 + 重拉 R10 状态；失败文案原样；
- **不做乐观更新**；写按钮 `loading` 防误触；收藏 / 订阅为业务态幂等（以响应为准）。

**订购入口（Q3-A）**：
- 「发起订购」按钮**禁用态**（`disabled`），旁边常驻提示「订购与订单流程将在后续版本开放（WBS-3.7.3）」；
- 点击不产生任何请求 / 路由跳转 / 状态变化；不出现订单号、支付、合约入口。

**不可达分支**：
- R7 返回 `1007C0011` → 整页 `el-alert` 展示 `PRODUCT_NOT_ACCESSIBLE_TIP`（与 3.3.6 详情同文案同样式），不区分"不存在 / 未上架 / 已下架 / 已注销"，不展示任何产品数据；
- 其他错误 → `ElMessage.error(err.message)`（原样）；加载失败不渲染半页数据。

**角色口径**：
- 运营档（`admin`）：可浏览列表与详情；**不显示收藏 / 订阅 / 订购区**（体验层；服务端 `catalog.interact` 仍为判定源）；
- 普通档（`provider`）：显示动作条；`1007C0006`（未入驻）文案原样展示。

### 3.3 未登录引导（B1-2）

- `/portal` 与 `/portal/products/:productId` 均属业务路由；既有 `router.beforeEach` 在未登录时重定向 `/login`，**不发起任何目录请求、不返回产品数据**；
- 页面内不提供「游客浏览 / 匿名浏览」入口（§4-8 非目标）。

### 3.4 状态与通知（B6 / Q5-A）

- 门户不提供站内消息、消息角标、轮询刷新、订阅提醒；
- 产品变更感知维持既有「数据目录」的订阅关系 + 变更留痕读面；订单状态感知归 3.7.3 订单列表 / 详情；
- 页面无任何"推送 / 通知"文案承诺。

## 4. 动作矩阵（角色 × 动作；服务端强制，界面仅显隐）

| 动作 | 普通档（provider） | 运营档（admin） | 说明 |
| --- | --- | --- | --- |
| 浏览列表 / 检索 / 详情 | ✅ | ✅ | 登录即可见；服务端 `catalog.read` + ADMITTED |
| 收藏 / 取消收藏 | ✅（详情页） | ❌（不渲染） | `catalog.interact` 服务端判定 |
| 订阅 / 退订 | ✅（详情页） | ❌（不渲染） | 同上 |
| 发起订购 | ❌ 禁用占位 | ❌ 禁用占位 | 3.7.3 实现前零请求 |
| 查看变更记录 / 互动留痕 | —— | —— | 归「数据目录」既有页面（3.3.6） |

> 「不渲染」均为前端体验层；服务端以权限点 / 归属判定兜底，界面隐藏不构成权限。

## 5. 标签与文案表（前端常量单一源）

| 类别 | 值 → 中文 |
| --- | --- |
| 定价模型 | `FREE` 免费 / `PER_CALL` 按次 / `MONTHLY` 包月 / `REVENUE_SHARE` 交易额分成（`PRICING_MODEL_LABELS`） |
| 定价展示 | `formatPortalPricing()`：免费 → "免费"；付费 + 数值 → "按次（3.00）"；无数值 → 模型名 |
| 产品状态 | `PRODUCT_STATUS_LABELS`（R7 `status` 直出中文；门户在架恒"已上架"） |
| 收藏 / 订阅动作 | `INTERACTION_ACTION_LABELS`（收藏 / 取消收藏 / 订阅 / 退订） |
| 空态 | `SEARCH_EMPTY_TIP`（没有找到符合条件的产品） |
| 不可达 | `PRODUCT_NOT_ACCESSIBLE_TIP`（产品不存在或未在架；与后端 `1007C0011` 文案逐字一致） |
| 订购占位 | 「订购与订单流程将在后续版本开放（WBS-3.7.3）」 |
| 页头说明 | 「浏览平台已上架的数据产品；订购与评价将在后续版本开放。」 |

**错误展示纪律**（服务端文案原样，界面零改写）：`1007C0011` 不可达同形提示（走常量，不展示服务端原文以外的信息）/ `1007C0006` 未入驻统一文案 / `1000C0001` 参数不合法 / `1000C0002` 未认证 / `1000C0005` 权限不足；前端不内置上述文案副本（同形提示常量除外，其值与后端逐字一致并由守卫测试锚定）。

## 6. 表单与交互细节

1. **零前端业务判定**：除「演示主体编号为空」前置拦截（`makeDomainJson`，零请求）外，可见性 / 状态 / 资格 / 归属一律以服务端响应为准；不可达分支只认 `1007C0011` 错误码。
2. **只读浏览**：列表 / 详情加载只调读面；任何写动作必须由用户显式点击触发（B1-3 / S1-6 观察点）。
3. **无乐观更新**：收藏 / 订阅成功后重拉状态；失败保持原状态并展示服务端文案。
4. **不做假幂等**：收藏 / 订阅的业务态幂等由服务端 uk 兜底；界面不宣称幂等、不做重放演示。
5. **分页**：列表 `pageSize=12`；`PageData.total` 驱动；页码 / 类目 / 关键词变化均重置页码为 1。
6. **加载与错误**：页面级 / 区块级 loading；错误走 `ElMessage.error(err.message)`；401/403 走既有 `guideToLoginIfAuthFailed`；不可达不是 toast，而是页内 alert。
7. **导航**：详情页返回固定回门户首页（不带筛选状态；筛选状态不持久化，避免伪状态）；浏览器前进 / 后退正常。
8. **响应式**：卡片网格随宽度降列；类目树窄屏可折叠为顶部下拉（实现按 CSS/既有布局能力，最小实现）。
9. **可访问性**：按钮文案完整、非纯图标；禁用按钮有 `disabled` 属性与可见提示；空态 / 错误态均有文字说明。
10. **数据安全**：只展示目录元数据（名称 / 简介 / 形态 / 定价 / 类目 / 提供方编号 / 时间）；零数据本体、零敏感原文、零个人信息扩展字段。

## 7. 规格 / 剧本 → 页面入口映射

| 剧本步骤（C-7.1 S1） | 页面 / 入口 | 承载说明 | 测试锚 |
| --- | --- | --- | --- |
| S1-1 打开门户产品列表，展示全部已上架（含 P1/P2/P3/P0），无待上架 / 审核中 | 数据产品门户首页卡片列表 | R6 恒仅已上架；P4/P5 不出现 | T3 / T5 |
| S1-2 关键词检索，结果与目录服务同一条件一致 | 首页检索栏 | R6 原样参数与结果；无本地过滤 | T4 |
| S1-3 打开 P1 详情，定价四档口径 / 按次单价可见；P0 显式"免费" | 详情页「定价」行 | R7 加性 `priceAmount` + `formatPortalPricing()` | T6 / T10 |
| S1-4 打开 P4（已下架）/ P5（待上架）详情与检索：不可见或明确提示不可用（负向） | 列表 / 检索 / 详情深链 | 列表天然不出现；详情 `1007C0011` → 同形提示 | T5 / T6 |
| S1-5 未登录访问门户：引导登录，不返回产品数据（负向） | 路由登录守卫 | 既有 `beforeEach`；门户不绕过 | T2 / T8 |
| S1-6 浏览前后目录管理端产品列表一致；主体状态与资格无变化（观察点） | 门户全页 | 只读（SourceGuard：无自动写调用） | T9 |
| S2-1~13（订购 / 订单 / 评价 / 状态感知） | —— | **下游 3.7.3 / 3.7.4**；本卡仅提供禁用订购占位，剧本修订标注 | ——（剧本修订） |
| C-7.1 剧本核对机制 | 界面入口行 + 演示前提⑤ + 维护说明 | 业务判定一字不改，PO 批准 | ——（交付后一次完成） |

> C-3.2 三剧本（统一数据目录）因「数据目录」页零改动而**无需修订**；门户新增页面不改变既有剧本路径与判定。

## 8. 测试锚点

### 8.1 前端（T1~T9，约 45~65 例）

| 组 | 断言要点 |
| --- | --- |
| T1 API 契约 | 复用函数路径 / 方法 / `pageQuery`；`CatalogProductDetail.priceAmount` 类型；无新 fetch；`makeDomainJson(catalogRolesHeader)`；空主体零请求 |
| T2 演示身份 | 普通档 `provider`、运营档 `admin`；普通档源集无 `admin` 字面量（除 `demoIdentity.ts` / `api/catalog.ts`）；门户源集零 `admin` 混入 |
| T3 路由与菜单 | `/portal` menuOrder 22、icon `ShoppingCart`、无权限点；深链不占菜单且在 `/portal` 之后；菜单计数 9 / 22；`menuOrder` 最大值 22；e2e 菜单可见 |
| T4 首页检索 | R5 类目树渲染 / 点击过滤 / 全部清除；关键词查询与重置；页码重置；分页 12；空态；错误 toast；**无本地过滤**（请求参数与输入一致） |
| T5 首页列表 | 卡片字段齐（名称 / 定价 / 简介 / 形态 / 类目 / 提供方 / 上架时间）；免费 tag；查看详情导航；R6 返回集合原样渲染（含 P0~P3、不含 P4/P5 由后端保证） |
| T6 详情与定价 | 元数据全字段；`formatPortalPricing`（免费 → "免费"；按次 → "按次（3.00）"；transhare → "交易额分成（5.00）"）；`1007C0011` → 同形 alert（无数据）；其他错误 toast；返回链接 |
| T7 收藏订阅 | 初查状态显隐；收藏 / 取消 / 订阅 / 退订调用与刷新；运营档不渲染动作条；失败文案原样；无乐观更新（写后重拉） |
| T8 登录守卫 | 未登录访问 `/portal` 与深链 → 重定向 `/login`；不发起目录请求（或路由守卫先于挂载） |
| T9 源集守卫 | 门户视图 + `api/catalog.ts`：零 `/internal/`、零 `governance` 前台调用、零原始 `fetch(`、零数据本体字段、零服务端错误文案副本（同形常量除外）、零定价 / 状态中文散写（经 constants）、零 `admin` 混入普通档、零订单 / 计费端点 |

### 8.2 后端（T10，Q2-A 生效时，2~4 例）

| 组 | 断言要点 |
| --- | --- |
| T10 R7 加性字段 | 付费档已上架产品 R7 返回 `priceAmount`（如 `"3.00"`）；免费档返回 `null`；R6 出参不含该字段（契约不变）；R9/R10 出参不变；`1007C0011` 同形拒绝不变；零迁移 / 零新码 |

### 8.3 红相归档

编码段测试先行：前端 T1~T9 先红 → 实现转绿；后端 T10 先红 → 实现转绿；红相日志归档 `build-output/w372-red-*`（沿界面包先例）。

## 9. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 == origin，工作树干净；`main` 未动；3.7.1 合并段批（`4d1ba83`）在本分支梢。
2. 前端基线：`npm test` **650/650**（50 文件）+ `typecheck` / `lint` / `build` 全 EXIT=0（实测复跑，数字以当次为准并回填任务卡）。
3. 后端基线：`mvn -B -ntp -pl services/catalog-service test` **182 例全绿（Skipped 0；2026-10-31 20:51 立卡段实测，02:33）** + `checkstyle:check` 0 违规；全仓 `compile` 成功（如本地 `common` 构件陈旧，先按先例 `mvn -pl common -am install -DskipTests` 或依赖模块 install，留痕）。
4. 端点探针：R5/R6/R7/R9/R10 + W4~W7 角色矩阵（provider / admin / 无角色）实探，逐字记录关键错误码与文案；确认 R7 无 `priceAmount` 的当前事实（Q2-A 前）与加性后形态。
5. 服务与演示环境：MySQL / MinIO / Redis 容器 + subject 8080 + catalog 8084 + 前端 5173；演示数据沿 C-7.1 附录 A 预置；目录 8084 的 5 条 vite 转发键已存在。
6. 静态事实复核：R6 恒 LISTED；R7 非在架 `1007C0011`；`price_amount` 列在 V4；付费档上架门槛要求数值；免费档 NULL；登录守卫覆盖全部业务路由。
7. 门禁基线：`gates-config.json` `ConfigSha256 955bdbba…2ee2`，本卡零改动。
8. 菜单 / 路由基线：普通用户 8、admin 21（实测复跑 `MainLayout.spec` / `router.spec`）；确认新增 22 与 9/22 预期。

## 10. 勘误表（编码段 / 评审段回填）

| # | 设计原文 | 实测 / 修正 | 来源 |
| --- | --- | --- | --- |
| —— | —— | （编码段回填） | —— |

## 11. 边界与纪律复述

- 只做已交付目录端点的界面化（Q2-A 加性字段为唯一例外）；不改 3.3.x 页面 / 端点语义 / 错误码 / 枚举 / 迁移 / 权限点 / `application.yml`。
- 零数据本体、零敏感原文、零内部面调用、零 `admin` 混入普通档、零订单 / 计费假动作。
- 不做前端业务判定；服务端文案原样；不做乐观更新；不做假幂等。
- 不做推送 / 消息 / 轮询（B6 / Q5-A）；不做匿名浏览（§4-8）。
- C-7.1 剧本只核界面入口与承载说明，业务判定一字不改，PO 批准留痕。
- 发现冲突或疑似 S0 安全问题 → 立即停止上报（红线 4）。

## 12. 文件清单与体量

| 类别 | 文件 | 预估 |
| --- | --- | --- |
| 视图 | `frontend/src/views/portal/PortalHomeView.vue` | 300~400 |
| 视图 | `frontend/src/views/portal/PortalProductDetailView.vue` | 250~350 |
| API / 常量 | `api/catalog.ts`（类型 +5~10）、`constants/catalog.ts`（+20） | 25~30 |
| 路由 / 计数 | `router/index.ts`、`router.spec.ts`、`MainLayout.spec.ts`、`e2e/login-smoke.spec.ts` | 30~40 |
| 视图测试 | 两视图 spec + `PortalSourceGuard.spec.ts` | 450~600 |
| 后端（Q2-A） | 三处加性字段 + 2~4 例 | 60~100 |
| 文档 | C-7.1 剧本核对修订 + 任务卡 §四 §五 + 台账 + 日志 | 150~200 |
| 合计 | —— | **约 1200~1800 行**（1 个编码会话；D1 不拆分） |
