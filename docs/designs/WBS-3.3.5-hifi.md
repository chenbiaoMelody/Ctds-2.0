# WBS-3.3.5 高保真设计：产品封装与上下架服务（hifi）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（已确认，编码契约**——2026-10-02 14:4x 编排师会话回复"**都按建议**"：Q1~Q9 均采建议口径 A + D1 不拆分豁免，确认记录随任务卡 §二 回填；草案 V0.9 2026-10-02 同日提交） |
| 日期 | 2026-10-02 |
| 上游 | lofi `docs/designs/WBS-3.3.5-lofi.md`（Q1~Q9 + D1 与本文件一一对应）；规格 V1.0 行为 3/4/2/7；3.3.4 hifi §3/§4/§10 移交登记 |
| 宿主 | `catalog-service`（8084 / `ctds_catalog`）续建；迁移 **V4**；既有服务与模块零触碰 |

## 1. 端点契约表（写 6 + 读 2；编号续 W4~W7 / R5~R10）

> 认证 = `common` 鉴权（RBAC）；`subject` = `AuthContext.subject()`（实际登录主体）；分页沿 `common-pagination`。写面路径参数沿 R2/R7 先例用自增 id（Q1-A，零取号构件）。

| 编号 | 端点 | 角色/权限点 | 请求（关键字段） | 响应（关键字段） | 主要错误码 |
| --- | --- | --- | --- | --- | --- |
| **W8** | `POST /api/v1/data-products` | provider + `catalog.product` | `datasetId`*、`productName`*（≤128）、`intro`*（≤512）、`productType`*（四枚举）、`pricingModel`*（四档枚举）、`priceAmount`（Q2-A 语义）、`categoryCode`（可选）＋ `X-Idempotency-Key`*（ADR-007） | `productId`、`productName`、`status`="未上架"、`pricingModel`、`priceAmount`、`categoryCode`、`createdAt` | 1007C0016/0017/0018/0020、1007C0006、1007C0014、1007C0005、1007C0006(ADMITTED 同款) |
| **W9** | `PUT /api/v1/data-products/{productId}` | provider + `catalog.product`（**仅提供方本人**） | `intro`*、`productType`*、`pricingModel`*、`priceAmount`、`categoryCode`（**名称不可变**——沿 dataset 名称不可变先例，防"改头换面"绕过命名唯一） | 产品视图（变更后）+ `updatedAt` | 1007C0012、1007C0015、1007C0019（已注销）、1007C0020、1007C0014 |
| **W10** | `POST /api/v1/data-products/{productId}/publish` | provider + `catalog.product`（仅本人） | 无 body | 产品视图（`status`="已上架"、`listedAt`） | 1007C0012、1007C0015、1007C0019、**1007C0020（定价不齐备）**、1007C0016（资源/空间态失效） |
| **W11** | `POST /api/v1/data-products/{productId}/delist` | provider + `catalog.product`（仅本人） | 无 body | 产品视图（`status`="已下架"、`listedAt`=NULL） | 1007C0012、1007C0015、1007C0019（非在架下架） |
| **W12** | `POST /api/v1/data-products/{productId}/force-delist` | **admin + `catalog.governance`** | `forceReason`*（1~256 字） | 产品视图（`status`="已下架"）+ 留痕含理由 | 1007C0012、1007C0019、1006 平台 PARAM_INVALID（理由缺失） |
| **W13** | `POST /api/v1/data-products/{productId}/cancellation` | provider + `catalog.product`（仅本人） | `confirmCancellation`*（须 true——沿 W3 `CancellationRequest` 先例） | 注销确认视图（不可逆声明） | 1007C0012、1007C0015、1007C0019（**在架注销拒绝**）、1006 平台 PARAM_INVALID（缺二次确认） |
| **R11** | `GET /api/v1/data-products/mine` | provider + `catalog.product` | `pageNum/pageSize` | 本人**全部状态**产品分页（管理视图：含 status/listedAt/定价） | 1006 平台分页参数 |
| **R12** | `GET /api/v1/data-products/{productId}/governance` | **admin + `catalog.governance`** | — | 产品**全量治理信息**（任意状态：含未上架/已下架/已注销；含定价与来源资源）＋ 每次查看写 `GOVERNANCE_VIEW` 留痕 | 1007C0012 |
| **R13** | `GET /api/v1/datasets/{datasetId}/governance` | **admin + `catalog.governance`** | — | 资源全量治理信息（含申报字段与已注销对象）＋ 每次查看写 `GOVERNANCE_VIEW` 留痕 | 1007C0005 |

> **引用保护（行为 2 规则 2 + 3.3.2 移交）不在新端点承载**：W3 注销前置检查已接线（`DatasetCommandService` L205），本卡将 `NoopProductReferenceGuard` 替换为实体检查实现（查 `data_product` 中 `dataset_id` 命中且 `status≠CANCELLED` 的行）——命中拒绝 **1007C0021**；C-3.1 剧本 S3-4 兑现。

## 2. 错误码表（`CatalogErrorCodes` 顺延；既有 16 码零改动）

| 码值 | 常量 | HTTP | 文案口径（防枚举与同形沿既有码风格） |
| --- | --- | --- | --- |
| 1007C0015 | `PRODUCT_FORBIDDEN` | 403 | 无权操作该产品（非提供方本人——服务端强制；文案与"产品不存在"差异化=权限语义，沿 1007C0006 模式） |
| 1007C0016 | `PRODUCT_DATASET_STATE_FORBIDDEN` | 409 | 来源资源当前状态不可封装/上架（已注销 / 已解散空间——沿 1007C0002"状态不可登记"同款表意） |
| 1007C0017 | `PRODUCT_NAME_DUPLICATED` | 409 | 同一提供方下已存在同名产品（归一化判定；并发命中唯一键转译本码——**新写面从第一天消除 DB-34 同款 500 态**） |
| 1007C0018 | `PRODUCT_NAME_INVALID` | 400 | 产品名称为空/超长/含控制字符（归一化后判定，沿 1007C0008 口径） |
| 1007C0019 | `PRODUCT_STATE_FORBIDDEN` | 409 | 产品当前状态不允许该操作（状态机矩阵封闭：在架注销/DRAFT 下架/CANCELLED 一切动作/DELISTED→非重新上架动作等） |
| 1007C0020 | `PRODUCT_PRICE_INCOMPLETE` | 409 | 定价信息不齐备（付费档数值缺失/非法、免费档不得携带数值、分成比例超 0~100）——上架门槛主码（C-3.3 剧本 S2-2 判定面） |
| 1007C0021 | `PRODUCT_DATASET_REFERENCED` | 409 | 资源存在未注销产品引用、不得注销（先处理产品——3.3.2 移交拒绝码随本卡定） |
| — | 复用 `1007C0006` | 403 | 封装时非本人资源（资源管理越权语义，文案沿用资源域） |
| — | 复用 `1007C0014` | 400 | 产品 `categoryCode` 非受控类目（沿 3.3.4 类目成员校验） |
| — | 复用 `1006` 平台码 | 400 | 缺二次确认（沿 W3 `PARAM_INVALID` 先例）/ 分页参数非法 / 强制下架理由缺失 |

> 新增 7 码（0015~0021），HTTP 映射沿 3.3.4 口径（400 参数类 / 403 越权 / 409 状态与冲突）；`CatalogExceptionHandlerCodeConsistencyTest` 码数锚 16→23 同步（既有测试合法同步，沿 3.3.4 先例）。

## 3. 表结构与约束（迁移 `V4__extend_data_product_and_log_registry.sql`；V1/V2/V3 既有列与约束零改动）

### 3.1 `data_product` 增列（3.3.4 Q1-A 授权"其迁移增列"）

```sql
ALTER TABLE data_product
    ADD COLUMN price_amount DECIMAL(12,2) NULL
        COMMENT '定价数值（Q2-A：按次=元/次、包月=元/月、分成=百分比0~100、免费档恒NULL——语义随 pricing_model；付费档未上架草稿态允许缺失，上架强制齐备 1007C0020）' AFTER pricing_model,
    ADD COLUMN normalized_product_name VARCHAR(128) NOT NULL
        COMMENT '归一化产品名（应用侧 DatasetNameNormalizer 产出；同提供方唯一性判定口径——沿 dataset.normalized_name 同款）' AFTER product_name,
    ADD UNIQUE KEY uk_provider_norm_name (provider_subject_no, normalized_product_name);
```

- 既有 `uk_provider_product_name`（原始名）**保留**（双键并存更严、零破坏；归一化键 = 判定口径唯一来源，沿 V1 `uk_space_norm_name` 先例）；
- `listed_at` 维护口径：上架写当前时点、下架/强制下架清 NULL、重新上架更新（V3 注释"非在架态为 NULL"锚定）；
- 数据分级维持 **CAT-03 L1**（Q9-A；V3 表注释已锚，V4 不改表注释级别）。

### 3.2 留痕动作码值域登记（沿 DB-29 V4"仅列注释值域登记"先例——零结构变更）

```sql
ALTER TABLE product_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL
        COMMENT '动作码值域（WBS-3.3.5 登记，沿 3.2.3 V2 先例）：CREATE 封装 / UPDATE 信息与定价变更 / PUBLISH 上架 / DELIST 下架 / FORCE_DELIST 强制下架（summary 含理由） / CANCEL 注销 / DENIED_CREATE / DENIED_UPDATE / DENIED_PUBLISH / DENIED_DELIST / DENIED_FORCE_DELIST / DENIED_CANCEL 拒绝留痕 / GOVERNANCE_VIEW 治理查看留痕——订阅者读面 R8 可见值域 = 前 6 类（DENIED_* 与 GOVERNANCE_VIEW 不可见，Q5-A）';
ALTER TABLE dataset_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL
        COMMENT '动作码值域（WBS-3.3.5 追加登记）：…既有码… / GOVERNANCE_VIEW 运营方治理查看留痕（仅 admin 触发，Q5-A/DB-29 同款）';
```

> 动作码常量集中在应用服务常量区（沿 `DatasetCommandService` `ACTION_*` 先例）；`product_action_log` 结构零变更（`summary` 承载理由与 from→to，≤512）。

## 4. 校验链（顺序即实现序；既有链零改动语义）

**W8 封装**：①认证+权限点 → ②ADMITTED（`SubjectAdmissionPort`，未入驻统一文案防枚举沿 1007C0006）→ ③请求体载体校验（名称归一化判空/超长→0018；简介长度；形态/定价档枚举合法性→平台 400）→ ④资源存在且**本人**（1007C0005/0006 复用）→ ⑤资源 ACTIVE 且所在空间未解散（`SpaceMembershipPort.isSpaceActive()`——空间已解散/资源已注销→0016）→ ⑥类目（可选：显式传入→`CategoryTreeService` 成员校验 1007C0014；缺省→按资源 `declare_category` 匹配类目树继承码值，匹配不到→NULL 不阻断）→ ⑦定价数值合法性（免费档携值→0020；付费档数值<0 或超界→0020；**草稿态允许缺失**）→ ⑧产品名归一化唯一判定（先查→0017）＋ DB `uk_provider_norm_name` 兜底（`DuplicateKeyException`→0017，**并发窗口第一天即转业务码**）→ ⑨幂等组件（ADR-007 重放首次结果）→ ⑩写 `data_product`（status=DRAFT，两写同事务：行+`CREATE` 留痕，沿 `insertWithLog` 3.3.4 先例）。

**W9 变更**：①②同上 → ③产品存在（1007C0012）→ ④**仅提供方本人**（1007C0015）→ ⑤已注销拒绝（0019）→ ⑥可变字段校验（形态/档位枚举、类目成员、定价合法性——同 W8 ⑦）→ ⑦写 + `UPDATE` 留痕（summary 含 from→to）。

**W10 上架**：①②③④同 W9 → ⑤**定价齐备**（付费档 `price_amount` 非空且合法→否则 0020；C-3.3 S2-2 判定面）→ ⑥资源 ACTIVE 且空间未解散（0016）→ ⑦ADMITTED 复核（提供方现值）→ ⑧状态机断言（仅 DRAFT/DELISTED→LISTED；含 1007C0019 拒绝矩阵）→ ⑨写（status=LISTED、`listed_at`=now）+ `PUBLISH` 留痕。

**W11 下架**：同 W10 前四步 → 状态机断言（仅 LISTED→DELISTED，否则 0019）→ 写（status=DELISTED、`listed_at`=NULL）+ `DELIST` 留痕。

**W12 强制下架**：①认证+admin+`catalog.governance` → ②产品存在（0012）→ ③理由必填（平台 400）→ ④状态机断言（仅 LISTED，否则 0019）→ 写（status=DELISTED、`listed_at`=NULL）+ `FORCE_DELIST` 留痕（summary 含理由全文与操作者——行为 4 规则 4）。

**W13 注销**：①②③④同 W9 → ⑤二次确认（`confirmCancellation` 非 true→平台 400，沿 W3 文案先例）→ ⑥状态机断言（仅 DRAFT/DELISTED→CANCELLED；**在架注销→0019**，C-3.3 S3-6 判定面）→ 写（status=CANCELLED）+ `CANCEL` 留痕（不可逆）。

**W3 引用保护（既有接线实体化）**：`JdbcProductReferenceGuard`（新）替换 `NoopProductReferenceGuard`（删除）——`SELECT EXISTS(data_product WHERE dataset_id=? AND status<>'CANCELLED')` → 命中抛 `1007C0021`（`DatasetCommandService` L205 既有分支零改动）。

**R12/R13 治理详情**：认证+admin+`catalog.governance` → 对象存在（0012/0005，**不防枚举**——治理例外显式语义，对象缺失=404 语义业务码）→ 读全量 → 写 `GOVERNANCE_VIEW` 留痕（operator=实际登录主体，Q5-A/DB-29 Q3-A 同款）→ 响应。**非 admin 访问治理端点 = 权限拒绝（403），不写留痕**（沿 DB-29 Q4-A"仅例外动作留痕"）。

## 5. 测试计划（Testcontainers MySQL 8，`SharedMySqlContainer` 先例；编号续 3.3.4 T5 → T6~T10；预估 45~55 例）

| 组 | 覆盖（规格锚点） |
| --- | --- |
| **T6 迁移探针** | V4 增列/唯一键存在与注释锚；动作码值域注释登记锚；既有列零改动探针（V1/V2/V3 结构哈希比对惯例） |
| **T7 封装全链（行为 3）** | 正向（未上架初始态+留痕四要素）/ 非本人资源拒（1007C0006+DENIED 留痕）/ 已注销资源拒、已解散空间资源拒（0016，S1-5 判定面）/ 同名拒（归一化含首尾空白，S1-4）/ 一资源多产品（S1-3 免费显式档）/ 幂等重放（S1-6）/ 类目缺省继承与显式校验 / 名称边界（空/超长/控制字符 0018）/ 并发唯一键转译 0017（**DB-34 同款手法新写面锚**） |
| **T8 上下架与变更（行为 4）** | 上架成功+目录联动可检索（S2-1）/ 定价不齐备拒（0020，S2-2）/ 变更含 from→to 留痕（S2-3）/ 已解散空间资源产品上架拒（S2-4，0016）/ 未入驻主体上架拒（S2-5 防枚举统一文案）/ 下架→目录不再呈现→重新上架恢复（S3-1/S3-2）/ 强制下架理由留痕（S3-3）/ 非提供方下架/改价拒（1007C0015+DENIED 留痕，S3-4）/ 在架注销拒（0019，S3-6）/ 下架后注销成功不可逆（S3-5）/ DRAFT 直接注销 / CANCELLED 后一切动作拒 / `listed_at` 三态维护锚 |
| **T9 引用保护（行为 2 规则 2）** | 存在未注销产品引用的资源注销拒（1007C0021——**C-3.1 剧本 S3-4 兑现**）/ 产品注销后资源可注销（双向）/ 无引用资源注销零回归（既有 3.3.2 用例回归） |
| **T10 治理例外与债务收口** | admin 治理详情允许+`GOVERNANCE_VIEW` 留痕四要素（产品侧+资源侧，C-3.2 S3-3 判定面）/ 非 admin 拒且零留痕（DB-29 Q4-A 负向锚）/ 未上架对象治理可读 / R8 订阅者可见值域过滤（DENIED_*/GOVERNANCE_VIEW 不出现）/ **DB-34 收口**：预插行后收藏/订阅→幂等成功不 500 / **DB-38 补测份额**：CANCELLED 产品新发起收藏/订阅专属用例 ×1 |
| **回归** | catalog 既有 **118 例零回归**（Skipped 0）+ `CatalogExceptionHandlerCodeConsistencyTest` 码数锚 16→23 合法同步 + 全仓 compile |

## 6. 分级落级（Q9-A 执行）

`data_product`（含 `price_amount`/`normalized_product_name` 新增列）与 `product_action_log` 维持 **CAT-03 L1**（V3 已锚；目录公开元数据、检索≠可访问、留痕不含敏感原文——定价数值随目录公开展示为目录业务本意，真敏感载体 CAT-04 L3 = 合约/账单归 C-4.x/3.7.6）；`dataset_action_log` 维持既有落级不变。复核结论回写 `docs/domain/数据分类分级规范.md` §6.1（登记"WBS-3.3.5 复核：数据产品目录条目维持 CAT-03 L1，规格锚 CAT-04 的 L3 行为合约/账单等交易载体"）。

## 7. 部署清单（沿 3.3.4 口径）

`catalog-service` 单模块重部署（8084）；迁移 V4 随 Flyway 自动执行；`application.yml` 权限点两行扩展：`provider` 行追加 `catalog.product`、`admin` 行追加 `catalog.governance`（实际新增枚，不动既有点）；无新端口、无新依赖、无新配置项。

## 8. 模块骨架（改动面 = catalog-service 模块内；预估新建 ~14 文件 + 既有 ~6 文件小改）

- **新建**：应用 `ProductCommandService`（W8~W13 校验链与留痕）/ `ProductGovernanceService`（R12/R13+留痕）；仓储 `JdbcProductReferenceGuard`（实体检查）；接口 `ProductCommandController` / `ProductGovernanceController`；DTO `CreateProductRequest`/`UpdateProductRequest`/`ForceDelistRequest`/`ProviderProductView`/`GovernedProductView`/`GovernedDatasetView` 等 ~8；
- **既有小改**：`CatalogErrorCodes`（+7 码）；`application.yml`（权限点两行）；`ProductInteractionService`（DB-34 收口，catch DuplicateKey 转重放）；`R8` 查询（可见值域过滤，`ProductCatalogQueryService`）；`ProductCatalogController`/`DatasetController`（各自挂治理端点或独立控制器承载）；删除 `NoopProductReferenceGuard`；测试侧码数锚同步。

## 9. 剧本与文档衔接（Q 全部落定后执行）

- 剧本零改动；走查期 C-3.3 剧本 S1~S3 直调接口执行（界面占位沿 3.3.4 走查先例——"接口 + 库内三面"）；C-3.1 S3-4 / C-3.2 S3-3 转 PASS 复验；
- 分级规范 §6.1 回写；DB-34/35/36/37/38/39 处置结论在交付说明逐条登记（关闭或收窄移交）；
- 规格/ADR/门禁配置零改动；不新增剧本步骤（无新增判定面——全部判定面剧本已有）。

## 10. 登记备忘

- 3.3.4 移交四件全部落定：data_product 写面（§1/§4）、product_action_log 写入面与动作码集合（§3.2）、产品业务编号规则（Q1-A：不取号沿 id）、NoopProductReferenceGuard 实体化（§4）；
- 规格授权落定五件：产品字段明细（§3.1 增列定稿）、形态一致性（Q3-A）、数量上限（Q4-A：不设）、定价数值字段（Q2-A）、治理查看留痕机制复用形态（Q5-A：DB-29 同款复用、零新表）；
- DB 承接：DB-34 收口（Q6-A）/ DB-35 评估结论留痕（Q7-A）/ DB-36 收窄移交 3.3.6（Q8-A）/ DB-37 落定（Q5-A 内）/ DB-38 补测份额（T10）/ DB-39 孪生 DTO 裁决 = 维持现状留痕（R9/R10 已交付读面零改动，合并评估随 3.3.6 契约定稿）。

## 11. 勘误与实现期微调（V1.0 编码期，业务判定零变更；供评审）

1. **`normalized_product_name` 列 NOT NULL → NULL 允许**（§3.1 勘误）：3.3.4 期测试/预置直造行无归一化值，NOT NULL 会破坏既有用例生态；NULL 行不参与唯一判定（MySQL 唯一键多 NULL 共存），封装写面写入恒非空，并发防重兜底不受影响（写面行归一化值非空，后插入方撞 `uk_provider_norm_name`）——V4 迁移列注释已锚定该口径；
2. **W8 判重预检移入幂等切面之内**（§4 步骤 ⑧⑨ 顺序勘误）：预检若在切面之外，幂等重放会被 1007C0017 挡住（重放必须先于判重命中）——沿 dataset register 同款链位（判重在 @Idempotent 方法体内，`DatasetRegistrationService.register` 先例）；幂等键语义 = 提供方+来源资源+归一化名，同键重放返回首次结果、跨资源同名（不同键）判重拒绝——T7 两用例分别锚定；
3. **W9~W13 校验链顺序微调**：终态/状态机断言先于定价与要素校验（沿 dataset `requireActive` 先于字段校验同款先例——已注销语义压倒定价缺失）；W12 维持 hifi 原序（存在 0012 → 理由 400 → 状态机 0019）；
4. **R8 可见值域**以封闭常量集承载（`ProductActionLog.SUBSCRIBER_VISIBLE_ACTIONS` 六码）——新增动作码默认不可见，须显式纳入（封闭值域原则，沿 3.3.4 status 枚举封闭先例）；
5. **既有测试合法同步 3 处**（沿 3.3.2/3.3.3/3.3.4 先例）：`CatalogExceptionHandlerCodeConsistencyTest` 码数锚 16→23；`CatalogProductChangeLogIntegrationTest` 自造行动作码字面量 LIST→PUBLISH（值域定稿后旧字面量不在值域）；`CatalogDirectoryMigrationIntegrationTest` data_product 列序锚增补 V4 两列。

### 11.1 修复批勘误（评审循环 1 后；评审结论 ①FAIL + ②③④PASS-with-notes，4×P1 + 9×P2 + 17×P3 全闭环）

6. **§1 契约文本修正四处（§1 原文未就地改动，以本条为准）**（S6/T7）：W8 幂等机制 = ADR-007 **业务键**（提供方+来源资源+归一化产品名，切面 SpEL 派生）而非 X-Idempotency-Key 请求头（头为装饰性、实现不读取——控制器 javadoc 同步修正）；W9 响应无 updatedAt 字段（§1 该字段以本条为准不承载）；W13 响应 = 标准 ProviderProductView（status=已注销），无独立"不可逆声明"字段；付费档数值边界统一为"须为正数"（0 值拒绝——0020 分语境文案常量三枚登记于码表）；
7. **空间门槛口径**（S5/C5）：W8/W10 的空间判定 = `SpaceMembershipPort.check()` + `spaceStatus == DISSOLVED` 直比（常量上收 `SpaceMembership.SPACE_STATUS_DISSOLVED`），**非** `isSpaceActive()`（该法要求 ACTIVE，会误拒 FROZEN/CREATED 空间——规格行为 3 规则 1 仅要求"未解散"）；NONE〔空间不存在〕随放行、由资源行存在性兜底；§1/§4/任务卡 §三 的 isSpaceActive() 表述失准以此为准；
8. **W9/W11/W13 的 ADMITTED 现值复核**（SEC3）：规格行为 4 规则 2 仅上架明文要求 ADMITTED，W9/W11/W13 的资格复核为已确认 hifi §4 的**从紧选择**（"①②同上/同 W10 前四步"字面），实现按设计补齐；
9. **W9 空载体 400 先于存在性查询**（C11）：省一次查询，业务判定影响小（全 null 请求本非法）；W9 切换付费档未携数值 → 400"切换付费档位时须同时提供价格数值"（S7-① 参数完备性防御）；FREE 切档清空 price_amount 时 UPDATE 留痕以 "priceAmount:旧值→（清空）" 补记（S7-②）；
10. **DTO 命名分轨**（C4）：接口层请求体 = `*Request`（沿 RegisterDatasetRequest 家族先例），应用层命令 = `*Command`（CreateProductCommand/ProductUpdateCommand）；§8 骨架中 GovernedProductView/GovernedDatasetView 裁撤（治理面复用 ProviderProductView/DatasetView——字段集未越界）；R8 可见值域过滤落点 = 仓储层（JdbcProductActionLogRepository.pageByProduct，非查询服务层）；
11. **留痕单值截断**（SEC1/P1 修复）：W9 变更留痕 summary 的 from/to 值各截断 ≤64 字符（超长以"…(n 字符)"尾注），summary 恒 ≤512——§4"≤512 由字段值域保证"的原文论证不成立，以此勘误为准；updateFields 增设乐观门槛 `AND status <> 'CANCELLED'`（0 行 → 1007C0019，SEC5 并发终态保护）；产品侧留痕单写通道（ProductActionLogRepository.insert 死代码删除——C3）；
12. **文档数字口径**（C7/C8/C9）：本卡端点数 = 写 6 + **读 3**（R11/R12/R13）；既有基线 = **17 端点**（读 10 写 7）；体量统计口径 = services/catalog-service 范围（不含本卡自产任务卡/设计/日志）；复用声明更正 = "JdbcDatasetRepository.create/updateFields 两写同事务先例 + 本卡新增 transitionStatus（乐观门槛沿 cancel WHERE status 先例）"。
