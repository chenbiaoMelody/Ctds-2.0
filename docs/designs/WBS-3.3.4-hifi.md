# WBS-3.3.4 高保真设计：统一目录服务（hifi）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V0.9（草案，待编排师一次确认）**——确认后转 **V1.0（编码契约）**；确认记录签署于本表版本行与任务卡 §二 |
| 日期 | 2026-10-01 |
| 任务卡 | `docs/tasks/WBS-3.3.4-统一目录服务-2026-10-01.md` |
| 设计输入 | lofi `docs/designs/WBS-3.3.4-lofi.md`（方向；**按建议口径 A 书写，Q1 若改选 B/C 则本设计随确认重写**） |
| 编码基线 | 3.3.3 交付态（`1ccbdf4`）：catalog 主源 42 文件 / 测试 8 文件 / 迁移 V1+V2 / 错误码 1007C0001~0010 + 1007S0001/0002 / 端点 W1~W3、R1~R4 / 测试 80 例基线 |
| 红线复述 | 类目树与语义标签词表**两套受控集合不得混用**（术语/通道/表物理分离）；V1/V2 迁移零改动；既有服务零改动；数据本体零接触；留痕不含敏感原文 |

## 1. 端点契约表（读 6 + 写 4；编号续 R1~R4 / W1~W3）

> 全部挂在既有 `@RequestMapping("/api/v1")` 下；响应包装 `ApiResult<T>`；分页 `PageQuery/PageResult`（`common-pagination`，ADR-005：`pageNum/pageSize` + `total`）；认证与权限点经 `CatalogAccessGuard`（既有）；ADMITTED 判定经 `SubjectAdmissionPort`（既有通道，拒绝口径与 1007C0006 同款统一文案）。

| # | 端点 | 方法 | 门槛（链序固定） | 入参 | 出参 | 失败码 |
| --- | --- | --- | --- | --- | --- | --- |
| R5 | `/catalog/categories` | GET | 认证 + `catalog.read`（无 ADMITTED 门槛，沿词表 Q8-A 先例） | 无（全量树，2 级） | `List<CategoryNodeView>`：`categoryCode`、`categoryName`、`children[]`（同构）；同级按 `sort_order` 升序 | 未认证 401 / 无权限 403 |
| R6 | `/data-products` | GET | 认证 + `catalog.read` + ADMITTED | `categoryCode`（可选）/ `keyword`（可选，≤64 字符）/ `pageNum`/`pageSize` | `PageResult<CatalogProductView>`：`productId`、`productName`、`intro`、`productType`、`pricingModel`、`categoryCode`、`categoryName`、`providerSubjectNo`、`listedAt`；**响应不含 status 字段**（结果恒已上架，规则 1） | 1007C0006（未入驻，防枚举统一文案）/ 1007C0013（参数非法，400）/ 分页非法沿 common |
| R7 | `/data-products/{productId}` | GET | 认证 + `catalog.read` + ADMITTED | 路径 `productId`（long，沿 R2 先例） | `CatalogProductDetail`：R6 字段 + `status`（在架产品恒"已上架"，字段保留为详情契约完整性）+ `intro` 全文 | 1007C0011（不存在/未上架/已下架/已注销**同形**，404） |
| R8 | `/data-products/{productId}/change-logs` | GET | 认证 + `catalog.read` + ADMITTED + **本人订阅者** | 路径 `productId`；`pageNum/pageSize` | `PageResult<ProductChangeLogView>`：`action`、`summary`、`operatorSubjectNo`、`createdAt`；按 `created_at` 倒序 | 1007C0011（产品行不存在，同形）/ 1007C0011（**非订阅者**同码同文案——不暴露订阅关系与产品存在性） |
| R9 | `/catalog/favorites` | GET | 认证 + `catalog.read` | `pageNum/pageSize` | `PageResult<CatalogProductView>` + 每条含 `productStatus`（**读时计算**，Q5-A：已上架/已下架/已注销——条目本身不删）+ `favoritedAt` | 分页非法沿 common |
| R10 | `/catalog/subscriptions` | GET | 认证 + `catalog.read` | `pageNum/pageSize` | `PageResult<CatalogProductView>` + 每条含 `productStatus`（读时计算）+ `subscribedAt` | 分页非法沿 common |
| W4 | `/data-products/{productId}/favorite` | POST | 认证 + `catalog.interact` + ADMITTED | 路径 `productId` | `FavoriteView`：`productId`、`favoritedAt`（首次时间） | 1007C0011（产品非在架**新发起**，404 防枚举同形） |
| W5 | `/data-products/{productId}/favorite` | DELETE | 认证 + `catalog.interact`（无 ADMITTED——本人条目操作，条目产生于 ADMITTED 期） | 路径 `productId` | `FavoriteView`（被删条目） | 1007C0012（本人无此收藏条目，404）+ **DENIED 留痕** |
| W6 | `/data-products/{productId}/subscription` | POST | 认证 + `catalog.interact` + ADMITTED | 路径 `productId` | `SubscriptionView`：`productId`、`subscribedAt` | 1007C0011（同 W4） |
| W7 | `/data-products/{productId}/subscription` | DELETE | 认证 + `catalog.interact`（同 W5） | 路径 `productId` | `SubscriptionView`（被删条目） | 1007C0012（本人无此订阅条目，404）+ **DENIED 留痕** |

**链序契约（写面 W4/W6，顺序即错误码优先序）**：

```
① 认证（401）→ ② 权限点 catalog.interact（403）→ ③ ADMITTED（1007C0006 统一文案，零副作用不留痕）
→ ④ 幂等判定：本人 uk 条目已存在 → 幂等重放返回首次结果（不新增行、不新增留痕；先于状态门槛——
   下架前已收藏的产品，下架后重复收藏 = 幂等重放而非"新发起"，与行为 6 规则 3"新发起被拒"不冲突）
→ ⑤ 产品状态门槛：status ≠ '已上架' → 1007C0011（防枚举同形，不区分未上架/已下架/已注销/不存在）
   + DENIED 留痕（有明确产品指向的拒绝，行为 7 规则 4 可取证）
→ ⑥ 写入（INSERT，uk 兜底）+ product_interaction_log 留痕 SUCCEEDED（同事务）
```

**W5/W7 链序**：①② 同上 → ③ 本人条目存在性（`uk` 前缀查）→ 无条目：1007C0012 + DENIED 留痕 → 有条目：DELETE + 留痕 SUCCEEDED（同事务）。**不设产品状态门槛**（下架产品的既有条目可取消，规格行为 6 规则 3 仅限"新发起"）。

**R6 检索链**：①②③ 同上（资格拒绝零副作用）→ ④ 参数校验（keyword ≤64 / `categoryCode` 须为类目树内节点〔否则 1007C0013〕）→ ⑤ 子树展开（categoryCode 有值时：一次查询取该节点及后代 code 集）→ ⑥ 查询：`status='已上架'` 硬过滤 + `category_code IN (子树集)` + `keyword` LIKE（名称/简介两字段，OR）+ `ORDER BY listed_at DESC, id DESC`。

**keyword 口径**：长度上限 **64**（超长 1007C0013）；LIKE 转义**复用 catalog 既有 `escapeLike`**（同一套转义符，不新增第二副本——沿 DB-33 登记的收敛方向）；keyword **不做归一化**（模糊匹配非精确判重，不与 `DatasetNameNormalizer` 混用）；通配符字面化探针随测试计划。

**幂等口径**：收藏/订阅幂等 = **DB 唯一约束兜底 + 先查后插**（uk 命中即重放首次结果，沿 ADR-007 重放语义：零新增副作用）；不走 `common/idempotency` 幂等键组件（该组件面向"同请求重放"，收藏/订阅是业务态幂等——沿 3.3.2 登记幂等键组件口径不变，本卡收藏订阅不建幂等键）。

## 2. 错误码表（`CatalogErrorCodes` 顺延，1007 段；既有 12 码零改动）

| 码值 | HTTP | 文案（业务可读，不暴露内部） | 触发面 |
| --- | --- | --- | --- |
| **1007C0011** | 404 | 产品不存在或未在架 | R7 未上架/不存在/已下架/已注销**同形**；W4/W6 新发起对非在架产品；R8 产品行不存在 / **非订阅者**（同码同文案防枚举） |
| **1007C0012** | 404 | 收藏或订阅记录不存在 | W5/W7 本人无条目 |
| **1007C0013** | 400 | 检索参数不合法 | R6 keyword 超长 / `categoryCode` 非类目树节点 |
| **1007C0014** | 400 | 分类申报不在平台受控类目范围内 | W1/W2 类目成员校验失败（不回显申报原文，沿 1007C0009 文案口径） |

- 未入驻拒绝**复用既有 1007C0006**（文案统一防枚举，不新增码）；S 型码**零新增**（资格通道复用既有 client，UNAVAILABLE 语义沿用既有 1007S0001/0002）；
- 1007C0011 文案**不区分**"不存在 / 未上架 / 已下架 / 已注销 / 非订阅者"（防枚举，行为 7 规则 2 同源口径）。

## 3. 表结构与约束（迁移 `V3__create_catalog_service_tables.sql`，库 `ctds_catalog`，MySQL 8；V1/V2 零改动）

| 表 | 列（类型） | 约束/索引 |
| --- | --- | --- |
| `category_node` | id BIGINT PK 自增 / category_code VARCHAR(32) NOT NULL / category_name VARCHAR(64) NOT NULL / normalized_name VARCHAR(64) NOT NULL / parent_code VARCHAR(32) NULL（NULL=一级）/ sort_order INT NOT NULL DEFAULT 0 / created_at DATETIME NOT NULL | `uk_category_code`；种子类目随 V3 `INSERT` 内置（建议集见下，**终稿随 Q3 定稿**） |
| `data_product` | id BIGINT PK 自增 / product_name VARCHAR(128) NOT NULL / intro VARCHAR(512) NULL / product_type VARCHAR(16) NOT NULL / pricing_model VARCHAR(16) NOT NULL / status VARCHAR(16) NOT NULL / provider_subject_no VARCHAR(32) NOT NULL / dataset_id BIGINT NOT NULL / category_code VARCHAR(32) NULL / listed_at DATETIME NULL / created_at DATETIME NOT NULL / updated_at DATETIME NOT NULL | `uk_provider_product_name(provider_subject_no, product_name)`（§7 Q3-A）；`idx_status_category(status, category_code)`；`idx_listed_at`；status ∈ {DRAFT〔未上架〕/ LISTED〔已上架〕/ DELISTED〔已下架〕/ CANCELLED〔已注销〕}（枚举封闭，测试探针锚定）；product_type ∈ {API/DATASET/REPORT/MODEL}（沿资源四类同款）；pricing_model ∈ {FREE/PER_CALL/MONTHLY/REVENUE_SHARE}（§7 Q4-A 四档） |
| `product_favorite` | id BIGINT PK 自增 / subject_no VARCHAR(32) NOT NULL / product_id BIGINT NOT NULL / created_at DATETIME NOT NULL | `uk_subject_product(subject_no, product_id)`；`idx_product_id` |
| `product_subscription` | id BIGINT PK 自增 / subject_no VARCHAR(32) NOT NULL / product_id BIGINT NOT NULL / created_at DATETIME NOT NULL | `uk_subject_product(subject_no, product_id)`；`idx_product_id` |
| `product_interaction_log` | id BIGINT PK 自增 / subject_no VARCHAR(32) NOT NULL / product_id BIGINT NOT NULL / action VARCHAR(16) NOT NULL / outcome VARCHAR(16) NOT NULL / deny_reason VARCHAR(32) NULL / created_at DATETIME NOT NULL | action ∈ {FAVORITE/UNFAVORITE/SUBSCRIBE/UNSUBSCRIBE}；outcome ∈ {SUCCEEDED/DENIED}；`idx_subject_created(subject_no, created_at)`；沿 `dataset_action_log` 四要素模式，**不含敏感原文** |
| `product_action_log` | id BIGINT PK 自增 / product_id BIGINT NOT NULL / action VARCHAR(32) NOT NULL / operator_subject_no VARCHAR(32) NOT NULL / summary VARCHAR(512) NULL / created_at DATETIME NOT NULL | `idx_product_created(product_id, created_at)`；**载体本卡建、写入归 3.3.5**（动作码集合随 3.3.5 封装写面登记，沿 3.2.3 V2"留痕动作码登记"先例）；本卡测试以自造行验证读面 |

**种子类目建议集（Q3-A，终稿随确认定稿或增删；code 用语义码小写连字符，沿 ADR-005 命名）**：

| 一级（code / 名称） | 二级 |
| --- | --- |
| transport / 交通运输 | transport-smart / 智慧交通；transport-logistics / 物流货运 |
| industry / 工业与能源 | industry-manufacturing / 工业制造；industry-energy / 能源电力 |
| agriculture / 农业农村 | agriculture-production / 农业生产；agriculture-rural / 乡村振兴 |
| finance / 金融 | finance-banking / 银行保险；finance-inclusive / 普惠金融 |
| health / 医疗健康 | health-medical / 医疗服务；health-public / 公共卫生 |
| environment / 气象与环境 | environment-weather / 气象服务；environment-ecology / 生态环境 |
| geography / 地理空间 | geography-mapping / 测绘地理；geography-remote-sensing / 遥感影像 |
| culture / 文化与旅游 | culture-service / 文化服务；culture-tourism / 旅游出行 |

- 共 **8 一级 + 16 二级 = 24 条**；`normalized_name` = `DatasetNameNormalizer.normalize(category_name)` 落库（校验比对面）；
- **必含值盘点义务**：走查/测试实际已用的 `declare_category` 申报值（编码会话实测测试字面量与演示库现值后回填本表——若现值为"金融"等即已覆盖；若出现建议集外的现值，种子行随批补入并留痕）；
- **匹配口径（Q2-A 兑现）**：资源申报值经 `DatasetNameNormalizer.normalize` 后与 `category_node.normalized_name` 在 **DB 侧一次比对**（差集下沉 DB，**沿 3.3.3 教训不搞"DB 判定 + Java 原文差集"两道口径**）；落库存申报原文（沿 3.3.2 现状零改动）；产品 `category_code` 挂类目码（写入归 3.3.5）。

## 4. 校验链（含本卡插入点；既有链零改动语义）

### 4.1 资源登记/变更链新增"类目成员校验"（W1/W2，Q2-A 兑现 3.3.2 移交）

```
DatasetRegistrationService.register()（既有链，本卡插入 1 处）
… → 资格三态 → 空间状态 → 重要数据拒收 → 第 6′ 步【语义标签词表校验（3.3.3 既有）→ 类目成员校验（本卡新增，组内后位）】
→ 判重 → 取号 → 落库 + 留痕
DatasetCommandService 变更路径 validTagsJson() 同款组内后位插入（变更含分类分级申报时可变字段集随 3.3.2 落定——
declare_category 若在可变集内则变更路径同校验；若不在则仅登记路径接入，以 3.3.2 实际可变字段集实测为准）
```

- 插入点在**幂等切面之内、资格/空间/重要数据拒收之后、判重取号之前**（沿 3.3.3 修复批 R1 定稿的第 6′ 步同款——非法入参零库内副作用：零资源行、零留痕、零取号）；
- 组内顺序 = 词表校验先、类目校验后（**不扰动 3.3.3 已定稿的错误码优先序**；同一请求双非法时词表码先行）；
- 类目校验失败 → **1007C0014**（400，文案"分类申报不在平台受控类目范围内"，不回显申报原文——沿词表 1007C0009 文案口径；码表见 §2）；
- 只作用新写入、不回填历史（沿 3.3.3 Q6-A）；词表通道 `TagTermPort` 与类目通道 `CategoryPort` 物理分离（两套受控集合红线）。

### 4.2 留痕规则汇总（行为 6 规则 4 + 行为 7 规则 4）

| 动作 | 留痕 | 说明 |
| --- | --- | --- |
| 收藏/订阅成功 | SUCCEEDED | 谁/何时/对哪个产品/动作 |
| 取消收藏/退订成功 | SUCCEEDED | 同上 |
| 重复收藏/重复订阅（幂等重放） | **不新增留痕** | 沿 ADR-007 重放语义（零新增副作用） |
| W4/W6 对非在架产品新发起 | DENIED（deny_reason=1007C0011） | 有产品指向的拒绝，可取证 |
| W5/W7 本人无条目 | DENIED（deny_reason=1007C0012） | 剧本 S2-3"被拒绝且记录拒绝留痕"承载 |
| 资格拒绝（未入驻）/ 无权限 / 未认证 | **零留痕零副作用** | 沿 3.2.7/3.3.3"资格探针零单据"先例 |

## 5. 测试计划（Testcontainers MySQL 8 实跑，沿 `SharedMySqlContainer` + root 建库 GRANT 先例；编号续 3.3.3）

| 组 | 覆盖 | 要点（含可证伪锚） |
| --- | --- | --- |
| T1 迁移探针 | V3 六表 | 列齐 / 列注释 / 三处 uk 反向探针（同提供方同名产品拒、同主体同产品重复收藏/订阅拒）/ 枚举值域封闭 / **种子 24 条**且含必含值（盘点回填后锚定具体 code）/ V1/V2 表零改动对照 |
| T2 检索集成（R6/R7） | 行为 5 + 行为 7 规则 2/5 | 正向：分类过滤（父类目含子树）/ keyword 命中名称与简介两字段 / 分页字段齐备翻页一致 / 排序 `listed_at DESC`（同秒按 id 倒序稳定锚）；反向：**未上架/已下架/已注销产品不出现在检索结果**（三态对照）/ 详情同形双响应**逐字对照**（不存在 vs 未上架 vs 非订阅者 R8，沿 3.3.2 T2 先例）/ 资格三态（401/403/未入驻统一文案且**库内零留痕零副作用**）/ **响应字段白名单显式锚定**（无本体无敏感原文无个人信息）/ keyword 超长拒绝 + 通配符字面化探针 / categoryCode 非法拒绝 |
| T3 收藏订阅集成（W4~W7/R9/R10） | 行为 6 全规则 + 行为 7 规则 1/4 | 收藏成功留痕四要素 / **幂等重放**（重复收藏行数不变 + 留痕不新增）/ 越权取消（B 对无条目产品 DELETE → 1007C0012 + DENIED 留痕，S2-3 承载）/ 列表仅本人条目 / **状态联动**（下架后 R9/R10 条目保留且 `productStatus=已下架`；注销同款）/ **新发起拒绝**（非在架产品 W4/W6 → 1007C0011 同形 + DENIED 留痕）/ **幂等先于状态门槛链序锚**（下架前已收藏，下架后重放 = 成功且零新副作用；若无此锚，链序回退为状态门槛先行时本例必红）/ 退订成功 |
| T4 订阅感知集成（R8） | 行为 6 规则 2 | 订阅者可查产品变更留痕（`product_action_log` 测试自造行）/ 非订阅者同形拒绝（1007C0011 同码同文案）/ 留痕不含敏感原文 |
| T5 类目校验集成（W1/W2 插入） | 3.3.2 移交 + Q2-A | 正向（申报值命中种子类目，全半角/首尾空白写法归一化命中）/ 反向（非类目申报 → 1007C0014，**零资源行零留痕零取号三面**）/ **双非法优先序锚**（语义标签与类目双非法 → 1007C0009 先行——若插入点或组内顺序回退本例必红）/ 历史数据零回填（既有申报值不在种子内时既有行为不变锚） |
| T6 既有 80 例零回归 | 3.3.2+3.3.3 基线 | 全量回归（Skipped 0） |

## 6. 数据分级落级（Q9-A 执行）

| 对象 | 落级 | 说明 |
| --- | --- | --- |
| `category_node`（含种子） | **CAT-03 L1** | 平台公开目录词汇（与词表册/词条同款口径） |
| `data_product`（最小载体） | **CAT-03 L1** | 目录元数据（检索 ≠ 可访问；产品完整要素落级复核归 3.3.5，规范 §6.1 CAT-04 已预指） |
| `product_favorite` / `product_subscription` | **CAT-03 L1** | 目录交互关系（主体编号 + 产品指向，无敏感原文） |
| `product_interaction_log` / `product_action_log` | **CAT-03 L1** | 目录域留痕（沿 `dataset_action_log` 既有落级同款；编码会话核对规范 §6.1 实文后回写，口径若有出入以实文为准并留痕） |

- 回写 `docs/domain/数据分类分级规范.md` §6.1（CAT-03 行补一句"3.3.4 目录类目/收藏订阅/交互与产品变更留痕"），沿 3.2.2/3.3.2/3.3.3 先例。

## 7. 部署清单

- **零 deploy 变更**：catalog 已在 `deploy/k8s`（3.3.2 入列）；本卡无新端口、无新中间件、无新环境变量；V3 迁移随包自动应用（ADR-009）。

## 8. 模块骨架（改动面 = catalog-service 模块内；预估新建 ~20 文件 + 既有 4 文件小改）

```
services/catalog-service/src/main/java/com/ctds/catalog/
├─ domain/        CategoryNode / DataProduct / ProductFavorite / ProductSubscription /
│                 ProductActionLog / ProductInteractionLog / CategoryPort /
│                 ProductStatus / PricingModel / CatalogCategory 值对象等（含枚举封闭）
├─ application/   ProductCatalogQueryService（R5~R10）/ ProductInteractionService（W4~W7）/
│                 CategoryTreeService（子树展开）
├─ infrastructure/ JdbcCategoryRepository / JdbcDataProductRepository /
│                 JdbcProductFavoriteRepository / JdbcProductSubscriptionRepository /
│                 JdbcProductActionLogRepository / JdbcProductInteractionLogRepository
└─ interfaces/    ProductCatalogController（R5~R10）/ ProductInteractionController（W4~W7）/
                  dto/（视图 DTO 落 interfaces/dto 包——沿 3.3.3 V1.1 归位口径）
既有文件小改（4）：CatalogErrorCodes（顺延 4 码）/ application.yml（权限点映射行）/ DatasetRegistrationService（第 6′ 步组内追加类目校验）/ DatasetCommandService（变更路径同款——以 3.3.2 可变字段集实测为准，可能零改动）
```

- 测试新增预估 5~6 文件（T1~T5 组 + 必要支撑）；**既有 8 测试文件零改动**（零回归锚）。

## 9. 剧本与文档衔接 + 语义检索预留契约（Q7-A / Q10-A）

1. **剧本 C-3.2 判定零变更**：S1（检索与分类五步）/ S2（订阅收藏四步）判定点本卡落地后全部可实测；S1-3"分类过滤 + 关键词 + 翻页"由 R6 承载；S2-3 越权拒绝 + 拒绝留痕由 W5/W7 DENIED 留痕承载；S2-4 状态联动由 R9/R10 读时计算承载；界面入口仍占位（3.3.6）；
2. **剧本 C-3.1 视 Q2 裁决**：若批准类目成员校验 → 增补"以非类目申报提交 → 被拒绝"一步（r3，沿 3.3.3 r2 先例，**PO 批准留痕后落笔**）；规格不改；
3. **走查预置缺口登记**：C-3.2 前提④产品预置依赖 C-3.3 剧本（未开工）——走查期以集成测试同款自造产品数据 + **偏差登记**替代（沿 3.3.3"步骤名被占用改同要素专名"先例）；
4. **语义检索预留契约（3.10.2 基线登记，Q7-A）**：① 检索端点 = `GET /api/v1/data-products`（R6），语义扩展届时以**独立端点**（建议形态 `/api/v1/catalog/semantic-search`，归 3.10.2 定稿）或 R6 扩展参数接入；② R6 响应契约（`CatalogProductView` 字段集与分页结构）+ 类目树词典（R5）= 语义检索结果组装基线；③ 本卡代码零占位（无 AI/向量库依赖包、无占位端点）；④ ADMITTED 门槛与"检索 ≠ 可访问"口径对语义检索同效。

## 10. 勘误与登记备忘（供评审与下游）

1. **种子类目终稿定稿权在编排师**（Q3-A 业务事项）——本设计建议集 24 条随确认定稿；编码期若实测发现"必含值"缺口（走查/测试现用申报值超出建议集），种子行随批补入并留痕，**不视为范围扩张**（Q3-A 内生义务）；
2. **`dataset_no_seq.seq_key` 扩展位保留未用**：V1 注释预留"3.3.3~3.3.5 扩展位"——本卡不取号、零触碰；产品业务编号规则归 3.3.5 落定（届时沿 `DS` 取号先例扩展 `seq_key`）；
3. **keyword 转义沿用既有 `escapeLike`**（DB-33 收敛方向：本卡不新增第二副本；DB-33 沉淀评估仍随 common 统一处置）；
4. **变更留痕写入面移交**：`product_action_log` 写入动作码集合归 3.3.5（封装/上下架/重新上架等动作落定时登记）；本卡读面 R8 对空表天然返回空页（非错误）；
5. **头信任模型**：沿 3.3.3 评审登记的既有部署前提（ADR-016 演示期边界），本卡零新增信任面；
6. **W1/W2 可变字段集实测义务**：`declare_category` 是否在变更路径可变集内，以 3.3.2 实际实现为准（编码会话实测后确定变更路径是否接入同款校验，接入则与登记路径同链序）。
