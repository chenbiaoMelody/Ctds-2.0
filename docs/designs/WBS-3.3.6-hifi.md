# WBS-3.3.6 高保真设计：目录与上架界面（hifi · 编码契约）

- 型态：**界面类**（高保真 = 编码契约 + 界面说明书）
- 任务卡：`docs/tasks/WBS-3.3.6-目录与上架界面-2026-10-03.md`；低保真：`docs/designs/WBS-3.3.6-lofi.md`（W1~W11 / N1~N8 / Q1~Q7 / D1）
- 线框原型：`docs/designs/WBS-3.3.6-原型-目录与上架界面.html`
- 上游契约：`docs/designs/WBS-3.3.2-hifi.md` §1（R1/R2、W1~W3）、`WBS-3.3.3-hifi.md` §1（R3/R4）、`WBS-3.3.4-hifi.md` §1 + §11 E1（R5~R10、W4~W7）、`WBS-3.3.5-hifi.md` §1 + §11/§11.1（W8~W13、R11~R13）；通用约定 `ADR-005`（REST / 九位错误码 / 分页 §3.2 / 401-403 文案）、`ADR-007`（幂等）、`ADR-016 §2.7`（演示期安全边界）、`ADR-001`（前端栈冻结）
- 本文件 §1~§11 **已获编排师确认（2026-10-03 19:2x，"确认" = Q1~Q7 采建议 A + D1 不拆分）即编码契约**（未确认 = 禁止编码，章程 2.6.1）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 高保真确认（编码契约） | **已确认（2026-10-03 19:2x）**：编排师会话回复"**确认**"→ Q1~Q7 均采建议口径 A + D1 不拆分（详见 `WBS-3.3.6-lofi.md` 确认记录与任务卡 §二 确认留痕）→ **本文件 §1~§11 = 编码契约，自本行起生效**；编码归新会话（第一步 = §11 前置检查项八项逐项实测留痕，测试先行） |

---

## 1. 接口契约表

**通用约定**：路径前缀 `/api/v1`；响应统一 `ApiResult<T>`（`code="0"` 成功）；分页请求 `?pageNum&pageSize`（`pageSize` 上限 100），响应 `PageResult<T>{list,total,pageNum,pageSize,totalPages}`（`common-pagination`）；身份与角色经 `common/auth`（`X-Ctds-Subject` / `X-Ctds-Roles`）；**权限判定一律在服务端**，前端只做体验层显隐。

### 1.1 复用面（目录域 26 端点中界面消费的 24 个 + 空间域 1 个；**全部零改动**）

| # | 端点 | 权限判定（服务端） | 界面落点 |
| --- | --- | --- | --- |
| R5 | `GET /catalog/categories` | 认证 + `catalog.read` | 门户类目树 / 登记·封装类目下拉 |
| R6 | `GET /data-products?categoryCode&keyword&pageNum&pageSize` | 认证 + `catalog.read` + ADMITTED | 门户检索列表 |
| R7 | `GET /data-products/{productId}` | 认证 + `catalog.read` + ADMITTED（非在架同形 `1007C0011`） | 产品详情页 |
| R8 | `GET /data-products/{productId}/change-logs?pageNum&pageSize` | 认证 + `catalog.read` + ADMITTED + 本人订阅者（非订阅者同形） | 详情页「变更记录」区 |
| R9 | `GET /catalog/favorites?pageNum&pageSize` | 认证 + `catalog.read` | 门户「我的收藏」页签 |
| R10 | `GET /catalog/subscriptions?pageNum&pageSize` | 认证 + `catalog.read` | 门户「我的订阅」页签 |
| W4/W5 | `POST` / `DELETE /data-products/{productId}/favorite` | 认证 + `catalog.interact`（W4 加 ADMITTED；W5 无） | 详情页「收藏 / 取消收藏」 |
| W6/W7 | `POST` / `DELETE /data-products/{productId}/subscription` | 同 W4/W5 | 详情页「订阅 / 退订」 |
| W1 | `POST /data-spaces/{spaceId}/datasets` | 认证 + `dataset.register` + ADMITTED + 成员判定 + 空间状态 + 重要数据拒收 + 词表/类目成员校验 + 判重 | 资源登记页「登记资源」弹窗 |
| R1 | `GET /datasets/mine?pageNum&pageSize&spaceId?` | 认证 + `dataset.read`（恒仅本人） | 资源登记页列表（含全部状态） |
| R2 | `GET /datasets/{datasetId}` | 认证 + `dataset.read` + 本人（非本人同形 `1007C0005` + `DENIED_READ` 留痕） | 资源行「详情」（可选面；列内联字段已够列表展示） |
| W2 | `PUT /datasets/{datasetId}` | 认证 + `dataset.update` + 本人 + 终态/收紧门槛 | 「变更」弹窗 |
| W3 | `POST /datasets/{datasetId}/cancellation` | 认证 + `dataset.cancel` + 本人 + `confirmCancellation=true` + 引用保护 | 「注销」二次确认 |
| R3/R4 | `GET /tag-vocabularies` / `GET /tag-vocabularies/{code}/terms?keyword&pageNum&pageSize` | 认证 + `vocabulary.read` | 登记/变更弹窗标签多选数据源（提交 `termName`） |
| W8 | `POST /data-products` `{datasetId,productName,intro,productType,pricingModel,priceAmount,categoryCode}` | 认证 + `catalog.product` + ADMITTED + 资源本人/有效 + 判重 + 幂等业务键（ADR-007） | 「封装产品」弹窗 |
| W9 | `PUT /data-products/{productId}` `{intro,productType,pricingModel,priceAmount,categoryCode}` | 认证 + `catalog.product` + 本人 | 「变更」弹窗 |
| W10/W11 | `POST /data-products/{productId}/publish` / `/delist` | 认证 + `catalog.product` + 本人 + 上架门槛 / 状态机 | 行「上架 / 下架 / 重新上架」 |
| W13 | `POST /data-products/{productId}/cancellation` `{confirmCancellation:true}` | 认证 + `catalog.product` + 本人 + 状态机（在架拒 `1007C0019`） | 「注销」二次确认 |
| R11 | `GET /data-products/mine?pageNum&pageSize` | 认证 + `catalog.product`（恒仅本人，全状态） | 产品上架页列表 |
| W12 | `POST /data-products/{productId}/force-delist` `{forceReason}` | 认证 + `catalog.governance` + 理由必填 | 治理页「强制下架」 |
| R12 | `GET /data-products/{productId}/governance` | 认证 + `catalog.governance`（每次查看写 `GOVERNANCE_VIEW`） | 治理页产品直查 |
| R13 | `GET /datasets/{datasetId}/governance` | 认证 + `catalog.governance`（同上） | 治理页资源直查 |
| —— | `GET /api/v1/data-spaces?pageNum&pageSize&keyword?`（**空间域**，只读复用） | 登录主体（仅可见空间；`platform.operator` 全量） | 登记弹窗「从我的空间选择」下拉（**页面级空间角色头**，见 §4） |

**出参（界面所需字段；以各上游 hifi 为准）**：`DatasetView{id,dataNo,spaceId,name,type,intro,tags[],declareCategory,declareLevel,declareImportant,status,createdAt}`；`ProviderProductView{productId,productName,intro,productType,pricingModel,priceAmount,status,providerSubjectNo,datasetId,categoryCode,categoryName,listedAt,createdAt}`；`CatalogProductView{…}` / `CatalogProductDetail{…,+status}`；`CatalogFavoriteItemView` / `CatalogSubscriptionItemView`（+`productStatus`、`favoritedAt`/`subscribedAt`）；`ProductChangeLogView{action,summary,operatorSubjectNo,createdAt}`；`CategoryNodeView{categoryCode,categoryName,children[]}`；`TagTermView{termCode,termName}`；`CancellationView{datasetId,dataNo,status,cancelled}`；`FavoriteView` / `SubscriptionView`；空间域 `SpaceSummaryView{id,name,status,…}`。

### 1.2 新增只读端点（**仅 Q2 采 A 时实施**；编号续 R13）

| # | 端点 | 权限判定（服务端） | 入参 | 出参 | 失败码 |
| --- | --- | --- | --- | --- | --- |
| **R14** | `GET /api/v1/datasets/{datasetId}/action-logs` | 认证 + `dataset.read` +（**登记主体本人** 或 `catalog.governance`〔admin 治理例外〕） | `pageNum/pageSize` | `PageResult<DatasetActionLogView>`：`id`、`action`、`actorSubjectNo`、`result`、`reasonCode`、`fromValue`、`toValue`、`createdAt`；按 `created_at DESC, id DESC` | 不存在 / 非本人且非治理档 → **`1007C0005` 同形**（404；非本人命中写 `DENIED_READ` 留痕，沿 R2 既有先例）；分页非法沿 common |
| **R15** | `GET /api/v1/data-products/{productId}/action-logs` | 认证 + `catalog.read` +（**提供方本人** 或 `catalog.governance`） | `pageNum/pageSize` | `PageResult<ProductActionLogView>`：`id`、`action`、`operatorSubjectNo`、`summary`、`createdAt`；**全值域**（含 `DENIED_*`、`GOVERNANCE_VIEW`——与 R8 订阅者可见值域形成对照） | 不存在 / 非本人且非治理档 → **`1007C0012` + 管理面文案**（404，同形） |
| **R16** | `GET /api/v1/catalog/interaction-logs` | 认证 + `catalog.read`（**恒仅本人**，无跨主体读法） | `pageNum/pageSize` | `PageResult<InteractionLogView>`：`id`、`productId`、`action`、`outcome`、`denyReason`、`createdAt`；按 `created_at DESC, id DESC`；空列表 = 正常空页 | 分页非法沿 common（无对象不存在分支） |

**读面留痕口径（本卡登记，DB-37 场景延伸）**：R14/R15 的**治理例外读面（admin）不另写留痕**（避免"看留痕产生留痕"的递归；治理查看留痕义务已由 R12/R13 承载——每次治理详情查看各写 1 行 `GOVERNANCE_VIEW`）；非本人命中 R14 写 `DENIED_READ`（沿 R2）与 R15 不写留痕（沿产品面读面既有口径）**维持各自资源轨既有先例**，不新增第三套口径。

**零新增**：错误码（复用 `1007C0005` / `1007C0012` / `1000C0001`）、库表、迁移、状态枚举、依赖；**既有 26 端点零改动**（方法签名 / 字段 / 错误码一字不动）。

---

## 2. 前端模块与类型

| 文件 | 内容 |
| --- | --- |
| `src/api/catalog.ts`（**新增**） | 端点调用函数（命名 `listCategories / searchProducts / getProductDetail / getProductChangeLogs / listFavorites / favoriteProduct / unfavoriteProduct / listSubscriptions / subscribeProduct / unsubscribeProduct / registerDataset / listMyDatasets / getDataset / updateDataset / cancelDataset / listVocabularies / listTerms / createProduct / updateProduct / publishProduct / delistProduct / cancelProduct / listMyProducts / forceDelistProduct / getProductGovernance / getDatasetGovernance / listDatasetActionLogs / listProductActionLogs / listInteractionLogs`）；**页面级角色头** `headers: {'X-Ctds-Roles': catalogRolesHeader()}`（覆盖 `apiJson` 全局头，见 §4）；主体编号空白 → **零请求**前置拦截（`ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP)`，沿 3.2.6 E3 口径）；复用 `apiJson`（**不新增 `fetch` 直连**） |
| 同上（类型） | 就地声明响应类型（`CategoryNode` / `CatalogProduct` / `CatalogProductDetail` / `ProductChangeLog` / `FavoriteItem` / `SubscriptionItem` / `DatasetItem` / `ProviderProduct` / `DatasetActionLog` / `ProductActionLog` / `InteractionLog`）；分页复用 `src/api/types.ts` 的 `PageData<T>`（**不重复声明**）；空间下拉复用 `src/api/space.ts` 的 `SpaceSummary` 与 `listSpaces` |
| `src/constants/catalog.ts`（**新增**） | 资源类型 / 产品形态 / 定价档 / 产品状态 / 资源状态 / 分级申报 / 留痕动作与结果的中文标签与 tag 色映射（**DB-36 Q5-A 的界面统一落点**：资源域枚举码与产品面中文显示名同屏观感一致）；**统一提示文案常量**（`RESOURCE_NOT_ACCESSIBLE_TIP` = "资源不存在或无权访问"、`PRODUCT_NOT_ACCESSIBLE_TIP` = "产品不存在或未在架"、`PRODUCT_MANAGE_NOT_FOUND_TIP` = "产品不存在或无权操作"、`ADMISSION_REQUIRED_TIP`〔目录场景统一文案，直接使用后端原文亦可〕等）——页面**禁止散写**这些中文（§6.7） |
| `src/api/authGuide.ts`（**平移**，原 `src/views/space/authGuide.ts`） | 认证失效引导（`1000C0002` → 原样提示 + 清登录态 + 回登录页）；**逻辑零变更**；4 处空间视图 import 路径一行同步（`./authGuide` → `../../api/authGuide`）；`AUTH_FAILED_CODE` 仍取自 `constants/space.ts`（避免无关同步；其迁移评估随 3.9.1 登记） |
| `src/stores/demoIdentity.ts`（**扩展**） | 新增 `catalogRolesHeader()`：普通档 `provider`；运营档 `admin`；既有 `spaceRolesHeader()` / 档位读写零改动 |
| `src/views/catalog/PortalView.vue`（**新增，替换并删除占位 `IndexView.vue`**） | 检索门户 + 三个个人页签（§6.2/§6.3） |
| `src/views/catalog/ProductDetailView.vue`（**新增**） | 产品详情（深链） |
| `src/views/catalog/DatasetManageView.vue`（**新增**） | 资源登记页 |
| `src/views/catalog/ProductManageView.vue`（**新增**） | 产品上架页 |
| `src/views/catalog/GovernanceView.vue`（**新增**） | 目录治理页 |
| `src/router/index.ts`（**改**） | 见 §3 |
| `src/layouts/MainLayout.spec.ts` / `src/router/router.spec.ts`（**改**） | 菜单计数断言 admin **10 → 13**、user 仍 **5**；`menuOrder` 最大值 **10 → 13**（两处）；新路由与权限守卫断言；`/catalog` 既有断言保持有效（路由 name 不变）；**e2e 登录冒烟零改动** |
| `vite.config.ts`（**改**） | 开发期转发新增 5 键 → **8084**（键序在前，正则键先于空间域前缀）：`'^/api/v1/data-spaces/[^/]+/datasets'`、`'/api/v1/datasets'`、`'/api/v1/data-products'`、`'/api/v1/catalog'`、`'/api/v1/tag-vocabularies'`（属开发期转发，不改服务契约与门禁配置） |

---

## 3. 路由与菜单（`src/router/index.ts`）

| path | name | 组件 | meta |
| --- | --- | --- | --- |
| `catalog` | `catalog` | `views/catalog/PortalView.vue` | `{title: '数据目录', menu: true, menuOrder: 3, icon: 'FolderOpened'}`（**既有菜单项内容替换**；无权限点 → 登录即可见，普通演示角色菜单计数不变，e2e 冒烟零改动；检索资格由服务端 ADMITTED 承载） |
| `catalog/datasets` | `catalog-datasets` | `views/catalog/DatasetManageView.vue` | `{title: '资源登记', menu: true, menuOrder: 11, icon: 'Files', permission: 'dataset.register'}` |
| `catalog/products` | `catalog-products` | `views/catalog/ProductManageView.vue` | `{title: '产品上架', menu: true, menuOrder: 12, icon: 'Goods', permission: 'catalog.product'}` |
| `catalog/products/:productId` | `catalog-product-detail` | `views/catalog/ProductDetailView.vue` | `{title: '产品详情'}`（**须声明在 `catalog/products` 之后**；不占菜单；无权限点——门槛在服务端） |
| `catalog/governance` | `catalog-governance` | `views/catalog/GovernanceView.vue` | `{title: '目录治理', menu: true, menuOrder: 13, icon: 'View', permission: 'catalog.governance'}` |

**菜单计数**：admin 演示角色 **10 → 13**；普通 `user` 角色仍 **5**。`MainLayout.spec.ts:64/68` 与 `router.spec.ts:62/95` 四处断言同步更新；`views/catalog/IndexView.vue`（2.4.9 占位）删除（死代码禁令）。

---

## 4. 演示身份与角色头

- **主体编号**：复用 `api/client.ts` 的 `getDemoSubject()` / `setDemoSubject()`（localStorage `ctds-demo-subject`）；**档位**：`stores/demoIdentity.ts`（`ctds-demo-actor-mode`，`subject` 默认 / `operator`）；顶栏控件零新增（3.2.6 已交付）。
- **目录域角色头（本卡新增）**：`catalogRolesHeader()` = 普通档 **`provider`**；运营档 **`admin`**（服务端 `ctds.auth.permissions.provider` 八点 / `admin` 四点映射有效）——沿 3.3.5 验收走查实测口径。
- **硬约束（测试锚点 T17）**：**普通档请求不得携带 `admin`**——否则治理类与"越权被拒"类剧本步骤会被服务端放行（让判定失真）；运营档打开提供方面页面（资源登记 / 产品上架）→ 服务端按权限拒绝（`1000C0005`）**原样展示** + 体验层提示"当前为平台运营方档，提供方页面请切回普通主体档"（不得伪装成功/空列表）。
- **空间域调用例外**：登记弹窗的"从我的空间选择"下拉走 `api/space.ts`（**空间角色头** `spaceRolesHeader()`，普通档 `applicant` / 运营档 `applicant,platform.operator`）——页面级头各自独立，互不污染。
- **边界**：`X-Ctds-Subject` 为空 → `AuthContextFilter` 整头作废（按未认证）→ 各 API 封装在主体编号空白时**前置拦截零请求**（目录域 `catalogJson` 与空间域 `spaceJson` 同口径）。
- **不改全局**：`client.ts` 的 `demoRolesHeader()` 一字不动（最小权限面，沿 3.1.11 / 3.2.6 先例）。

---

## 5. 后端新增只读端点（**仅 Q2 采 A 时实施**，否则本节整体不实施）

| 项 | 内容 |
| --- | --- |
| 落点 | `services/catalog-service`（**不新建模块**）：`interfaces/CatalogActionLogController`（新增；或按归属并入 `DatasetController` / `ProductCatalogController`——以"控制器与资源归属一致"为准，实现期定稿并在任务卡 §四登记）、`interfaces/dto/DatasetActionLogView` + `ProductActionLogView` + `InteractionLogView`（新增，落 `interfaces/dto` 包，沿 3.3.3 V1.1 归位口径）、`application/DatasetQueryService`（+`actionLogs` 方法）、`application/ProductCatalogQueryService`（+`actionLogs` / `myInteractionLogs` 方法）、`domain/DatasetRepository` + `infrastructure/JdbcDatasetRepository`（+只读分页方法）、`domain/ProductActionLogRepository` + `JdbcProductActionLogRepository`（+全值域分页方法，与 R8 的可见值域过滤方法并存）、`domain/ProductInteractionLogRepository` + `JdbcProductInteractionLogRepository`（+按主体分页方法） |
| 权限实现 | `@RequirePermission("dataset.read")`（R14）/ `@RequirePermission("catalog.read")`（R15/R16）为第一道功能门槛；**本人或治理档**的行级判定在应用服务内：`AccessControl.hasPermission("catalog.governance")`（common/auth 既有接口，注解与代码内判定共用同一内核）→ 本人 → OK；治理档 → OK（admin 读不写留痕，见 §1.2）；否则按各端点失败码**同形拒绝** |
| 数据 | 只读既有 `dataset_action_log` / `product_action_log` / `product_interaction_log`（参数化 SQL、列名白名单、`ORDER BY created_at DESC, id DESC`）；**不含敏感原文与数据本体**（三表本身不含） |
| 分页映射 | 服务内单点助手（`interfaces/dto/PageViews.page(...)` 或等价静态方法，1 处定义）承载 3 处新端点的 `PageResult` 映射（**新增面零复制**；DB-39 Q6-A）；既有 7 处手工映射**零改动**（沿"既有端点零改动"纪律，随 common 变更窗口统一收口） |
| 错误码 / 库表 / 迁移 | **零新增**（复用 `1007C0005` / `1007C0012` / `1000C0001`）；**零新表、零迁移** |
| 既有端点 | **零改动**（26 端点的签名、字段、错误码、留痕行为一字不动） |
| 测试 | 见 §7 T1~T3（`SharedMySqlContainer` 实跑；与既有 160 例零回归） |

---

## 6. 界面说明书

### 6.1 全局布局

- 左侧菜单：既有「数据目录」（内容替换）+ 新增「资源登记 / 产品上架 / 目录治理」（`menuOrder 11/12/13`）；
- 顶栏：既有「演示模式」+ 角色切换 + 「演示身份」控件（3.2.6 交付，零新增）；
- 全部目录域请求经 `apiJson` 自动带 `X-Ctds-Subject` 与 `X-Ctds-Roles`（`catalogRolesHeader()`）。

### 6.2 页面 1：数据目录（`/catalog`，检索门户）

**页签 1 目录检索**

| 区块 | 内容 |
| --- | --- |
| A 类目树 | 左侧树（R5；2 级）；点节点 = 选中过滤（父类目含子树，服务端展开）；「全部」= 清除类目过滤 |
| B 检索条 | 关键词输入（≤64）+「查询」「重置」 |
| C 结果表格（分页） | 列：产品名称 / 简介（截断展示） / 形态 / 定价模型 / 类目 / 提供方主体编号 / 上架时间 / 操作（「查看详情」）；行点击 = 进入详情 |
| D 空态 | "没有找到符合条件的产品"（非报错）；未入驻主体检索被拒 → 统一业务文案原样展示（`1007C0006` + `CATALOG_ADMISSION_REQUIRED_MESSAGE`） |

**页签 2 我的收藏 / 页签 3 我的订阅**（R9/R10，分页）

列：产品名称 / 形态 / 定价 / **产品当前状态**（已上架 / 已下架 / 已注销，tag）/ 收藏（订阅）时间 / 操作（取消收藏 / 退订）；下架后条目**保留并标记**；空态"暂未收藏任何产品 / 暂未订阅任何产品"。

**页签 4 我的互动留痕**（R16，仅 Q2-A）

列：动作（收藏/取消收藏/订阅/退订）/ 结果（成功·被拒 tag）/ 产品编号 / 时间 / 拒绝原因（错误码尾号，如 `C0012`）；空态"暂无互动记录"。

### 6.3 页面 2：产品详情（`/catalog/products/:productId`，深链）

| 区块 | 内容 |
| --- | --- |
| A 元数据 | 产品名称 / 状态 tag / 简介（全文）/ 形态 / 定价模型 / 类目 / 提供方主体编号 / 上架时间；**无任何数据本体内容段落**（检索 ≠ 可访问） |
| B 动作条 | 「收藏 / 取消收藏」「订阅 / 退订」（按本人当前状态显隐；重复操作幂等——提示成功且列表不重复；**运营档不显示该动作条**〔体验层〕，服务端仍为判定源 `catalog.interact`） |
| C 变更记录 | R8 分页表：动作 / 摘要（含从何值→到何值）/ 操作者主体编号 / 时间；加载策略 = 先取本人订阅列表（R10）判断是否已订阅（体验层），已订阅才加载；未订阅显示"订阅后可查看产品变更记录"；R8 被拒（同形 `1007C0011`）时原样展示 |
| D 不可达分支 | 非本人 / 未上架 / 已下架 / 已注销 / 不存在 → **同一条**提示常量（`PRODUCT_NOT_ACCESSIBLE_TIP`）与样式；不得区分"不存在 / 未上架" |

### 6.4 页面 3：资源登记（`/catalog/datasets`）

| 区块 | 内容 |
| --- | --- |
| A 操作条 | 「登记资源」按钮（弹窗 A） |
| B 我的资源表格（R1，分页） | 列：数据标识（`dataNo`）/ 名称 / 类型（枚举码 → 中文标签）/ 状态（生效中 / 已注销 tag）/ 所属空间（`spaceId`）/ 分类分级申报（类目 / 级别）/ 登记时间 / 操作（「变更」「注销」，已注销行无操作） |
| C 操作留痕区（R14，仅 Q2-A，分页） | 列：动作 / 结果（成功·被拒 tag）/ 操作者 / 时间 / 从何值→到何值 / 拒绝码；空态"暂无操作记录" |
| D 状态门槛体验层 | 已注销行的「变更 / 注销」按钮禁用并提示终态；提交仍以后端为准 |

### 6.5 页面 4：产品上架（`/catalog/products`）

| 区块 | 内容 |
| --- | --- |
| A 操作条 | 「封装产品」按钮（弹窗 C） |
| B 我的产品表格（R11，分页） | 列：名称 / **来源资源**（R1 映射资源名，页容量内；超出显示编号）/ 形态 / 定价（模型 + 数值）/ 状态（未上架 / 已上架 / 已下架 / 已注销 tag）/ 类目 / 上架时间 / 操作（按状态：上架 / 下架 / 重新上架 / 变更 / 注销） |
| C 操作留痕区（R15，仅 Q2-A，分页） | 列：动作（封装/变更/上架/下架/强制下架/注销/拒绝/治理查看）/ 摘要（含理由全文与 from→to）/ 操作者 / 时间；空态"暂无操作记录" |
| D 档位提示 | 运营档打开本页 → 列表加载被拒（`1000C0005`）原样展示 + 体验层提示"请切回普通主体档" |

### 6.6 页面 5：目录治理（`/catalog/governance`，平台运营方）

| 区块 | 内容 |
| --- | --- |
| A 直查 | 产品编号输入 +「查看产品治理信息」（R12）；资源编号输入 +「查看资源治理信息」（R13）；两者均为**按编号直查**（治理例外不防枚举：对象缺失 = 404 语义业务码；非法编号 = 平台参数校验） |
| B 产品治理视图 | `ProviderProductView` 全量字段（含定价与来源资源 id）+ 状态 tag + 「强制下架」按钮（弹窗 F） |
| C 资源治理视图 | `DatasetView` 全量字段（含申报字段与已注销对象） |
| D 留痕说明 | 每次查看写 `GOVERNANCE_VIEW`（谁/何时/看了什么）；提供方可在 §6.4 C / §6.5 C 留痕区核对；非 admin 档访问 → 403 原样展示（读面拒绝不留痕，沿 DB-37） |

### 6.7 文案与样式纪律

1. 状态 / 形态 / 定价 / 类型 / 分级 / 留痕动作的中文**一律**取 `constants/catalog.ts`（页面源码不得出现裸枚举码或裸状态中文字面量——由源集守卫断言 T19）；
2. 错误展示统一 `ElMessage.error(err.message)`（后端业务文案），**不得改写、不得吞掉**；
3. 同形拒绝（`1007C0005` / `1007C0011` / `1007C0012`）各自用**同一条**提示常量与样式（防存在性探测口径一致），不得前端区分"不存在 / 无权 / 未上架"；
4. 危险动作（注销资源 / 注销产品 / 强制下架）二次确认；取消 = **零请求**；必填留空 = 前置拦截（零请求）；
5. 写动作**不做乐观更新**：一律以响应为准刷新；被拒后界面状态不变；
6. 空态与无结果使用表格空态文案，不作报错展示；认证失效（`1000C0002`）→ 共享 `authGuide` 引导回登录页。

### 6.8 弹窗清单

| 编号 | 弹窗 | 关键点 |
| --- | --- | --- |
| A | 登记资源（W1） | 名称（必填 ≤128）/ 类型（四选一）/ 简介（必填 ≤512）/ 语义标签（**多选 + 允许自由输入**〔`allow-create`〕——服务端成员校验为判定源，C-3.1 S1-8 步骤承载）/ 分类分级申报（类目 **可搜索 + 允许自由输入**〔S1-9 步骤承载〕、级别 L1~L4、重要数据勾选）/ **所属空间编号（必填，可直填；「从我的空间选择」下拉快捷回填**——非成员/非已启用空间由服务端拒绝，界面不拦截提交）；提交后按返回刷新列表与留痕 |
| B | 变更资源（W2） | 仅简介 / 语义标签 / 分类申报可改（名称与类型只读）；分级只能收紧（下拉仅列 ≥ 当前级别，仍以后端 `1007C0004` 为准） |
| C | 注销资源（W3） | 二次确认明示"注销后不可恢复"；取消 = 零请求；`{confirmCancellation: true}` |
| D | 封装产品（W8） | 来源资源下拉（R1 本人资源，含已注销/已解散空间资源——由服务端拒 `1007C0016`）/ 名称（必填 ≤128）/ 简介（必填 ≤512）/ 形态 + 定价档（必填）/ 价格数值（付费档随档启用；免费档禁用且不提交数值）/ 类目（可选，缺省继承） |
| E | 变更产品（W9） | 简介 / 形态 / 定价档 / 数值 / 类目（名称不可变）；FREE 切档清空数值 |
| F | 强制下架（W12） | 理由必填（1~256）；留空 = 前置拦截（零请求） |
| G | 注销产品（W13） | 二次确认明示不可逆；在架注销被拒 `1007C0019` 原样展示 |

---

## 7. 测试锚点（T1~T20；映射见任务卡 §三）

**后端（仅 Q2-A；Testcontainers MySQL 8 实跑，沿 `SharedMySqlContainer` 先例）**

| 锚点 | 内容 | 规格依据 |
| --- | --- | --- |
| T1 | **R14 权限矩阵与同形拒绝**：本人 200（含四要素与 `from→to`）/ 非本人 → `1007C0005` **同码同文案** + `DENIED_READ` 恰 1 行 / 不存在同码同文案 / admin（治理档）200 且**零新增留痕** / 未认证 401 / 无权限 403 / 分页字段齐备 + 越界 `1000C0001` + 排序稳定（同秒按 id 倒序） | 行为 2 规则 1/2/5、行为 7 规则 3/4 |
| T2 | **R15 权限矩阵与值域对照**：提供方本人 200 且**全值域可见**（`DENIED_*` 与 `GOVERNANCE_VIEW` 在内）/ 非本人 → `1007C0012` + 管理面文案同形 / admin 200 且零新增留痕 / **与 R8 订阅者可见值域（六码）逐字对照**（同一行在 R15 可见、在 R8 不可见） | 行为 4 规则 4/6、行为 7 规则 3/4 |
| T3 | **R16 本人互动留痕**：仅本人行（B 读不到 A 的行——构造两主体行对照）/ 四动作 + `DENIED` 行齐备 / `denyReason` = 错误码尾号 / 字段白名单（无敏感原文）/ 空列表正常空页；**既有 160 例零回归**（Skipped 0） | 行为 6 规则 4、行为 7 规则 4 |

**前端**

| 锚点 | 内容 | 剧本步骤 |
| --- | --- | --- |
| T4 | 门户检索：类目树渲染（2 级）+ 选中父类目传 `categoryCode` + 关键词 ≤64 传参 + 重置 + 空态 + 分页传参刷新 + 越界 `1000C0001` 原样展示 | C-3.2 S1-1/S1-3 |
| T5 | 门户未入驻：`1007C0006` 统一文案**原样展示**（断言不出现"主体不存在"差异表述） | C-3.2 S1-4 |
| T6 | 详情页：元数据逐字段渲染 + **无数据本体段落**（断言无本体占位结构）+ 非在架/不存在分支共用同一提示常量（反向探针：改文案必红） | C-3.2 S1-5/S3-1/S3-4 |
| T7 | 收藏/订阅：成功 + 重复（幂等重放：提示成功且列表不重复）+ 非在架 `1007C0011` 原样 + 运营档动作条不显示（体验层） | C-3.2 S2-1/S2-2/S2-4 |
| T8 | 我的收藏/订阅：`productStatus` 标记（已上架/已下架/已注销三态）+ 空态 + 翻页 | C-3.2 S2-4 |
| T9 | 互动留痕页签：四动作与拒绝行渲染 + 空态 + 翻页 | C-3.2 S2-3（留痕核对面） |
| T10 | 变更记录区：已订阅加载 R8 / 未订阅显示提示零请求 / R8 同形拒绝原样展示 | C-3.2 S2-2 |
| T11 | 资源登记：必填前置（空名称/空空间编号 = **零请求**）+ 提交契约字段逐字（tags 传 `termName`、`declareImportant`）+ 未启用空间**允许提交**并展示 `1007C0002` + 重要数据 `1007C0003` + 词表外 `1007C0009` + 类目外 `1007C0014` + 非成员拒 + 成功刷新 | C-3.1 S1-1/S1-2/S1-3/S1-4/S1-5/S1-8/S1-9 |
| T12 | 资源变更/注销：请求体**只含**可变白名单字段 + 级别下调 `1007C0004` 原样且不乐观更新 + 注销二次确认（取消 = 零请求）+ 引用保护 `1007C0021` 原样 + 已注销行无操作入口 | C-3.1 S2-1/S2-2/S3-1/S3-3/S3-4 |
| T13 | 资源留痕区：四要素 + `from→to` + 拒绝行渲染 + 空态 + 翻页 | C-3.1 S1-4/S2-1/S2-3/S3-5（留痕核对面） |
| T14 | 产品上架列表：全状态渲染 + 来源资源名映射（R1 命中 / 超出页容量回退编号）+ 状态机按钮显隐 + 运营档提示 | C-3.3 S1-5/S2-4/S3-5 |
| T15 | 封装弹窗：来源资源下拉含本人全部资源 + 免费档不提交数值 + 付费档数值前置校验 + 同名 `1007C0017` 原样 + 成功刷新 | C-3.3 S1-1/S1-3/S1-4 |
| T16 | 上架/下架/重新上架/注销：定价不齐备 `1007C0020` 原样 + 下架后状态 + 重新上架 + 在架注销 `1007C0019` + 注销二次确认（取消零请求） | C-3.3 S2-1/S2-2/S3-1/S3-2/S3-5/S3-6 |
| T17 | **演示身份**：普通档 `provider`（**断言不含 `admin`**）/ 运营档 `admin` / 空主体零请求 / `catalogRolesHeader` 单点（源集守卫：`'admin'` 角色头字面量仅限 `stores/demoIdentity.ts` 与 `api/catalog.ts`） | 剧本多主体对照 |
| T18 | 路由与菜单：admin 13 项 / user 5 项；四条新路由权限守卫（user → `/dashboard?denied=1`）；`catalog` 路由 name 与既有断言保持有效 | 界面可达性 |
| T19 | 源集守卫：① 五个视图源码无裸枚举/状态中文字面量（须经 `constants/catalog.ts`）；② 无 `fetch(` 直连；③ 同形提示常量在页面中"只被引用、不被散写"（反向探针） | §6.7；lofi Q1/Q4 |
| T20 | 产品留痕区：全值域渲染（`DENIED_*` / `GOVERNANCE_VIEW` / 强制下架理由全文）+ 空态 + 翻页 | C-3.3 S3-3/S3-4（留痕核对面） |

---

## 8. 边界值与异常行为

1. **必填前置（零请求）**：演示身份主体编号（各 API 封装统一）/ 资源名称 / 资源类型 / 简介 / 语义标签 / 分类分级申报 / 空间编号 / 产品名称 / 产品简介 / 形态 / 定价档 / 来源资源 / 强制下架理由——前端 `maxlength`/必填校验对齐后端口径；
2. **长度门槛**：资源与产品名称 ≤128、简介 ≤512、关键词 ≤64、强制下架理由 1~256（前端 `maxlength` 对齐，服务端为准）；
3. **空态与无结果**：检索空 / 收藏空 / 订阅空 / 互动留痕空 / 资源空 / 产品空 / 留痕空 → 一律空态文案（非报错）；
4. **同形口径**：`1007C0005`（资源）/ `1007C0011`（在架面）/ `1007C0012`（管理面）三类拒绝在界面上各为**同一条**提示，不区分内部差异；
5. **乐观更新禁止面**：所有写动作（登记 / 变更 / 注销 / 封装 / 上下架 / 强制下架 / 收藏订阅）被拒后**不得**改本地数据，一律以响应为准刷新；
6. **档位门槛提示**：运营档打开提供方面页面 → 原样展示 `1000C0005` + 体验层提示切回普通档；普通档打开治理页 → 原样展示 403（或菜单不可见——菜单按演示角色显隐为体验层）；
7. **分页越界**：`pageNum` 超界 → 后端 `1000C0001` 原样展示；
8. **服务不可达**：`1007S0001`（主体服务）/ `1007S0002`（空间服务）原样展示；前端不伪造成功；
9. **401/403**：`1000C0002` → 共享 `authGuide`（提示 + 清登录态 + 回登录页）；`1000C0005` 原样展示；
10. **幂等语义**：登记（W1）与封装（W8）为服务端业务键幂等（ADR-007）——界面重复提交返回首次结果；收藏/订阅为业务态幂等（uk 兜底 + 先查后插），界面不得因重复点击显示"新增失败"；
11. **越权步骤的界面观察点**：非本人/未上架对象在界面上**无入口**（列表恒仅本人、详情同形拒绝）——这正是服务端强制的正确行为；拒绝留痕（写面 `DENIED_*`）由技术侧接口直调产生，提供方可在留痕区（R14/R15）核对（剧本修订承载说明，见 §9）。

---

## 9. 剧本步骤 → 界面入口映射（交付后"界面核对修订"的对照基线）

> 口径：**能界面的走界面**；越权类步骤因"他人对象在界面上无入口"（安全面正确行为）按 **技术侧配合 + 留痕区核对** 承载（沿 C-3.2 S3-2 技术侧配合先例）；业务判定一字不改，修订由 PO 批准。

| 剧本 | 幕 / 步骤 | 本包界面入口（拟定名称）与承载 |
| --- | --- | --- |
| C-3.1 | S1-1 / S1-2 / S1-3 | 「资源登记」→「登记资源」弹窗（C 主体统一文案 / B 非成员拒 / 未启用空间拒——空间编号直填） |
| C-3.1 | S1-4 | 「资源登记」→ 登记成功 → 列表见数据标识 + 弹窗/留痕区见四要素 |
| C-3.1 | S1-5 | 「登记资源」→ 勾选"申报为重要数据"→ 拒收文案 |
| C-3.1 | S1-6 | 「登记资源」→ 同名提交（两路：B 主体 / A 主体含空白）→ 拒或幂等重放，资源总数不增 |
| C-3.1 | S1-7 | **技术侧配合**（同请求标识重放） |
| C-3.1 | S1-8 / S1-9 | 「登记资源」→ 标签自由输入 / 类目自由输入 → 拒且总数不增 |
| C-3.1 | S2-1 / S2-2 | 「资源登记」→ 行「变更」弹窗（含空白/级别下调）→ 成功刷新；级别下调拒原样 |
| C-3.1 | S2-3 | **界面观察点**：D 主体打开「资源登记」无 A 资源入口（服务端强制）；写面拒绝留痕由**技术侧接口直调**产生 → A 在留痕区核对 `DENIED_UPDATE` 行 |
| C-3.1 | S2-4 | 「逻辑空间」冻结（3.2.6 界面）→ 回到「资源登记」提交 → 拒 → 恢复空间 |
| C-3.1 | S3-1 / S3-2 / S3-3 | 「资源登记」→ 行「注销」（二次确认）→ 成功/不可逆提示；同名再登记拒；已注销行无操作 |
| C-3.1 | S3-4 | 「资源登记」→ 对已引用资源「注销」→ `1007C0021` 原样 |
| C-3.1 | S3-5 | 同 S2-3 承载（技术侧直调 + A 留痕区核对拒绝行） |
| C-3.2 | S1-1 / S1-2 / S1-3 | 「数据目录」→ 检索页签（关键词 / 类目 / 翻页；未上架不出现） |
| C-3.2 | S1-4 | 「数据目录」→ 以 C 主体检索 → 统一业务文案 |
| C-3.2 | S1-5 | 「数据目录」→ 行「查看详情」（仅元数据，无本体段落） |
| C-3.2 | S2-1 / S2-2 | 详情页「收藏」（重复幂等）/「订阅」→ 「我的订阅」+「我的互动留痕」页签 |
| C-3.2 | S2-3 | **技术侧配合**（B 代发取消请求）→ B 在「我的互动留痕」核对拒绝行（`DENIED` + 码尾号） |
| C-3.2 | S2-4 | 「我的收藏 / 我的订阅」→ 条目保留且标"已下架"；**新发起**拒绝由技术侧直调（界面无已下架对象入口） |
| C-3.2 | S3-1 | 详情页深链（粘贴地址）→ 同形拒绝「产品不存在或未在架」 |
| C-3.2 | S3-2 | **技术侧配合**（绕过界面直调写面）→ 留痕区核对（产品侧 `DENIED_*` 行） |
| C-3.2 | S3-3 | 「目录治理」→ 产品/资源编号直查 → 允许；留痕（`GOVERNANCE_VIEW`）在提供方留痕区或治理页复读核对 |
| C-3.2 | S3-4 | 详情页/检索结果逐条核对（无本体、无敏感原文） |
| C-3.3 | S1-1 / S1-3 / S1-4 | 「产品上架」→「封装产品」弹窗（成功未上架 / 一资源多产品免费显式 / 同名拒） |
| C-3.3 | S1-2 | 同 C-3.1 S2-3 承载（界面无他人资源入口；`DENIED_CREATE` 资源域留痕由技术侧直调 → A 在资源留痕区核对） |
| C-3.3 | S1-5 | 「封装产品」→ 来源资源选已注销/已解散空间资源 → `1007C0016` 原样 |
| C-3.3 | S1-6 | **技术侧配合**（幂等重放） |
| C-3.3 | S2-1 | 「产品上架」→ 行「上架」→ 门户检索到（目录联动） |
| C-3.3 | S2-2 | 「封装产品」草稿不填定价 → 行「上架」→ `1007C0020` 原样 |
| C-3.3 | S2-3 | 行「变更」→ 简介/定价 → 留痕区见 from→to |
| C-3.3 | S2-4 | 行「上架」已解散空间资源产品 → `1007C0016` 原样 |
| C-3.3 | S2-5 | **待 PO 裁决**（3.3.5 偏差④：属主判定先于资格的统一文案触达路径）→ 本卡核对修订时按裁决结论标注（界面/技术侧） |
| C-3.3 | S3-1 / S3-2 | 行「下架」→ 门户检索不再出现；行「重新上架」→ 恢复可检索 |
| C-3.3 | S3-3 | 「目录治理」→ 直查产品 → 「强制下架」填理由 → 留痕区见理由全文与操作者 |
| C-3.3 | S3-4 | 同 C-3.1 S2-3 承载（技术侧直调 + A 在产品留痕区核对 `DENIED_*`） |
| C-3.3 | S3-5 / S3-6 | 行「注销」（二次确认）成功；在架「注销」→ `1007C0019` 原样 |

> 三剧本**业务判定一字不改**；交付后按实际界面名称/路径 **+ 上表承载口径**核对修订并提示 PO 批准（W10）。

---

## 10. 变更登记

| 面 | 变更 |
| --- | --- |
| 契约 | 零 ADR 变更；Q2-A 时登记新增 3 个只读端点（既有 26 端点零改动） |
| 错误码 | 零新增（复用 `1007C0001`~`1007C0021`、`1000C0001/0002/0005`、`1007S0001/S0002`） |
| 枚举 | 零新增（复用四态 / 四类 / 四档 / L1~L4） |
| 库表 / 迁移 | 零新增、零迁移（Q2-A 仅新增只读分页查询；R14 非本人命中复用既有 `DENIED_READ` 动作码） |
| 依赖 | 前端零新增；后端零新增模块依赖 |
| 配置 | 服务端零改动；`frontend/vite.config.ts` 开发期转发新增 5 键 → 8084；**门禁配置零改动** |
| 前端 | 5 视图（1 替换 + 4 新增）+ 2 模块（api/constants）+ 1 store 扩展 + 1 模块平移（authGuide）+ 3 菜单项/5 路由；既有 2 个 spec 断言同步 + 4 处 import 同步；e2e 零改动 |
| 后端 | 仅 Q2-A：3 只读端点 + 3 视图 DTO + 2 仓储分页方法 + 1 服务内分页映射助手 + 3 组测试；既有 26 端点零改动 |
| 剧本 | 三剧本界面入口核对修订（名称/路径 + 越权类步骤承载说明小修，业务判定不变，提示 PO 审批）；C-3.3 S2-5 以 PO 待裁决结论为准 |
| 债务 | **DB-36（Q5-A）**：维持双轨 + 登记有意分叉（资源域枚举码 / 产品面中文显示名，界面统一中文展示；收敛触发 = 出现界面之外第三消费方时评估）→ 关闭留痕；**DB-39（Q6-A）**：① common `PageResult.map` 沉淀 **不采**（服务内单点助手承载新增面；触发条件 = common 组件下一次变更窗口）② 孪生 DTO **维持现状** → 关闭留痕 |

---

## 11. 实施前置检查项

同 lofi §6 八项（编码会话第一步逐项实测留痕，本节不重复）。

---

## 12. 勘误登记（实施期回填）

**（编码/评审期按 `E1…` 顺延登记，格式沿 `WBS-3.2.6-hifi.md` §12 / `WBS-3.3.5-hifi.md` §11 先例）**

| 编号 | 位置 | 原表述 | 实施口径（澄清，非改需求） | 登记 |
| --- | --- | --- | --- | --- |
| E1 | §6.8 弹窗 B | "分级只能收紧（**下拉仅列 ≥ 当前级别**，仍以后端 `1007C0004` 为准）" | 变更弹窗级别下拉**列全部四档**（当前档预选，低于当前档的选项保留可选并配"分级只能收紧"提示文案）：§9 承载映射明文要求 C-3.1 S2-2"级别下调拒原样"在界面**可尝试执行**，仅列 ≥ 当前档会使该剧本步骤屏幕不可执行；提交后一律以服务端 `1007C0004` 判定为准（体验层提示，不构成拦截）。业务判定零变更 | 2026-10-03 编码会话实测（T12 + 剧本 C-3.1 S2-2 承载核对） |