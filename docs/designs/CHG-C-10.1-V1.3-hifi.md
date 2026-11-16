# CHG-C-10.1-V1.3 资源域属主读面出站口径改造 · 高保真设计（编码契约稿）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-11-16 编排师会话指示"全部按建议确认"：Q1~Q6 均采建议口径 A = 编码契约即时生效）**；V0.9 = 立卡草案 |
| 上游 | lofi V1.0（方向定稿）；规格 C-10.1-10.2 V1.2 B3-R2/B3-AC2/B4-R2/B4-AC2；CHG-C-10.1-V1.3 §7 裁决 A 案；剧本 C-10.1 V1.6 S2-3/S2-4/S2-5 |
| 实现底线 | 只改 `DatasetQueryService` R2/R14 两分支；共享常量 / 写面 / 治理面 / 产品面 / 前端 / 门禁配置 / 规格 / 剧本正文**零改动**；零新错误码 / 端点 / 权限点 / 迁移 / 依赖 |
| 判定基准 | 剧本 S2-4 正文预期字面 = **400 + `1000C0003`**；三态（非属主 / 跨空间 / 不存在）**同码同文** |

## §1 端点契约（改造前 → 改造后）

> 出站统一 `ApiResult`（`code / message / traceId / data`）。仅列拒绝面；成功面（200 + 资源元数据 / 留痕分页）零变化。

| # | 方法与路径 | 场景 | 改造前 | 改造后 |
| --- | --- | --- | --- | --- |
| R2 | GET `/api/v1/datasets/{datasetId}` | 非属主（同空间其他主体） | 404 + `1007C0005` + "资源不存在或无权访问" + `data=null` | **400 + `1000C0003` + "资源不存在" + `data=null`** |
| R2 | 同上 | 跨空间主体 | 同上（实现上同属非属主分支） | **同上新口径** |
| R2 | 同上 | 对象不存在 | 同上 | **同上新口径（与上两行逐字一致）** |
| R2 | 同上 | 本人 | 200 + `DatasetView` | 200 + `DatasetView`（不变） |
| R14 | GET `/api/v1/datasets/{datasetId}/action-logs` | 非属主且非治理档 | 404 + `1007C0005` + "资源不存在或无权访问" + `data=null` | **400 + `1000C0003` + "资源不存在" + `data=null`** |
| R14 | 同上 | 对象不存在 | 同上 | **同上新口径（与上一行逐字一致）** |
| R14 | 同上 | 属主本人 / 治理例外（`catalog.governance`） | 200 + 留痕分页 / 治理例外零新增留痕 | 不变 |

**逐字一致定义**：`code` = `1000C0003`、`message` = `资源不存在`、`data` = `null`、封套字段集合 = `{code,message,traceId,data}`；`traceId` 为链路标识，允许逐请求不同，不参与存在性判定。

## §2 实现契约（文件与代码形状）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `services/catalog-service/src/main/java/com/ctds/catalog/domain/CatalogErrorCodes.java` | **新增 1 个文案常量** | `DATASET_READ_NOT_FOUND_MESSAGE = "资源不存在"`——属主读面三态统一文案；与平台通用 `NoResourceFound` 字面一致。**不改** `DATASET_NOT_FOUND_OR_NO_ACCESS` / `..._MESSAGE`（写面 / R13 / 产品面继续使用） |
| `services/catalog-service/src/main/java/com/ctds/catalog/application/DatasetQueryService.java` | 改 R2 `detail` / R14 `actionLogs` 的两个拒绝出口 + 删除私有 `notFoundOrNoAccess()` | 不存在 → `readNotFound()`；非属主（R14 且非治理）→ 先写 `DENIED_READ` 留痕再 `readNotFound()`；reason_code = `CatalogErrorCodes.tailOf(ErrorCodes.RESOURCE_NOT_FOUND)` = **`C0003`** |
| 同上（新增私有出口） | `private static BizException readNotFound()` | `new BizException(ErrorCodes.RESOURCE_NOT_FOUND /* 1000C0003 */, CatalogErrorCodes.DATASET_READ_NOT_FOUND_MESSAGE)` |
| `services/catalog-service/src/main/java/com/ctds/catalog/interfaces/DatasetController.java` | 仅 Javadoc 注释更新 | R2/R14 注释由 `1007C0005` 改为 `1000C0003` 新口径；**无逻辑改动** |
| `CatalogExceptionHandler` / `MAPPED_CODES` | **零改动** | `1000C0003` = `ErrorType.CLIENT` → 全局 `GlobalExceptionHandler` 默认 400；不进 1007 段精确映射集 |
| 前端 / std-adapter / 网关 / 迁移 / 依赖 / 门禁配置 | **零改动** | 见 §5 / §6 |

**异常路径（已核查）**：

```
DatasetQueryService.detail / actionLogs
  └─ throw BizException(ErrorCodes.RESOURCE_NOT_FOUND, "资源不存在")
       └─ CatalogExceptionHandler.onCatalogBizException 不匹配（仅 CatalogBizException）
            └─ GlobalExceptionHandler.onBizException
                 ├─ ErrorType.CLIENT → HTTP 400
                 └─ message 透传（仅 SYSTEM 型脱敏）
```

- 采用 `BizException` 直接出口而非 `CatalogBizException`：与 `EvidenceAuditAccessGuard`（审计反枚举读面）既有先例同型，避免"catalog 处理器捕获后再 rethrow 全局"的二段路径；`MAPPED_CODES` 保持"1007 段精确映射"不变量。
- 读面拒绝不落 `rbac.check` 之外的新审计面；既有内部 `dataset_action_log.DENIED_READ` 保持（行为 7 规则 4）。

## §3 边界值与异常行为

| 情形 | 期望 |
| --- | --- |
| 未认证（无身份头） | 401 + `1000C0002`（`CatalogAccessGuard.requireSubject` 前置，不变） |
| 有身份但角色无 `dataset.read` | 403 + `1000C0005`（`@RequirePermission` 前置，不变） |
| R2/R14 非属主 | 400 + `1000C0003` + "资源不存在"；R2/R14 各落 1 行 `DENIED_READ`（不重复写） |
| R2/R14 不存在 | 400 + `1000C0003` + "资源不存在"；**零留痕**（无对象指向） |
| R14 治理例外 | 200；零新增留痕（避免"看留痕产生留痕"递归） |
| 分页参数越界（R14） | 400 + `1000C0001`（`common-pagination`，不变） |
| 写面非属主 / 写面不存在 | 403 + `1007C0006` / 404 + `1007C0005`（共享常量，不变） |
| R13 治理读面不存在 | 404 + `1007C0005`（`ProductGovernanceService`，不变） |
| 产品封装来源资源不存在 | 404 + `1007C0005`（`ProductCommandService`，不变） |
| 历史 `dataset_action_log` 行 | reason_code 不回改；新行记 `C0003` |
| 响应结构 | 不新增存在性差异字段；`data` 恒 `null`，字段集合固定 |

## §4 测试契约（T1~T9，测试先行）

| # | 承载类 / 用例 | 断言 |
| --- | --- | --- |
| T1 | `CatalogDatasetLifecycleIntegrationTest.detailIsOwnerOnlyAndSameShapeAsNotFound` | R2 非属主 → 400 + `1000C0003` + **字面**"资源不存在"（修复批改为字面断言，避免测试自引生产常量）；`DENIED_READ` 恰 1 行且 `reason_code='C0003'` |
| T2 | 同上（不存在分支） | R2 `999999` → 同码同文；零留痕 |
| T3 | 同上（逐字比对） | 两响应 `code` / `message` / `data=null` / 字段集合一致（traceId 除外） |
| T4 | 同上（跨空间主体分支） | 第二主体（跨空间语义）→ 与 T1/T2 同码同文；`DENIED_READ` 1 行 |
| T5 | `CatalogActionLogsIntegrationTest.r14NonOwnerRejectedSameShapeWithExactlyOneDeniedReadLog` | R14 非属主 → 400 + `1000C0003` + 统一文案；`data=null` + 封套白名单；新增 `DENIED_READ` 恰 1 行、`reason_code='C0003'`、actor = 越权者 |
| T6 | `...r14MissingDatasetSameShapeWithoutLog` | R14 不存在 → 同码同文；`data=null` + 封套白名单；零留痕 |
| T7 | `...r14GovernanceExceptionReadsWithoutNewLog` | 既有 200 + 零新增留痕（回归不变） |
| T8 | 三处回归断言 | ① `CatalogDatasetLifecycleIntegrationTest` 写面不存在 → 404 + `1007C0005` + 旧文案；② `CatalogGovernanceIntegrationTest` R13 不存在 → 404 + `1007C0005` + 旧文案；③ `CatalogProductEncapsulationIntegrationTest` 来源资源不存在 → 404 + `1007C0005` + 旧文案；写面非属主 403 + `1007C0006` 由既有用例锚定 |
| T9 | `CatalogDatasetLifecycleIntegrationTest.detailUnauthenticatedAndPermissionDenied` | R2 未认证 → 401 + `1000C0002`；有身份但无 `dataset.read` → 403 + `1000C0005`（评审④ P2-3 修复批新增的鉴权边界锚） |

## §5 前端影响复核（Q4-A 落地证据）

- 全仓 `RESOURCE_NOT_ACCESSIBLE_TIP` 引用面 = `frontend/src/constants/catalog.ts:169`（定义）+ `frontend/src/views/catalog/CatalogSourceGuard.spec.ts`（常量值守卫）；**无视图引用**。
- 视图错误文案来源 = `ApiError.message` 原样展示；无按 `1007C0005` / `1000C0003` 码值分支的资源详情逻辑。
- 结论：前端**零改动**；旧常量当前**无视图消费**（仅定义 + 值守卫用例），保留不删；后续视图若引用须按新口径评估（评审③ P2-3 更正表述）。

## §6 测评面影响评估（Q5-A）

| 项 | 结论 |
| --- | --- |
| 变化面 | 资源域属主读面 R2/R14 的**拒绝响应**：HTTP 404 → 400、code `1007C0005` → `1000C0003`、文案 "资源不存在或无权访问" → "资源不存在"。成功面零变化 |
| 全仓引用核查 | `1007C0005` 主源 = catalog-service（写面 / R13 / 产品面 / R14 旧断言）+ 前端常量 + 文档；**std-adapter 零引用**；无其他服务内部调用 R2/R14 |
| 外部消费方 | 前端（零码值分支）、验收剧本（S2-4 即新口径）、信通院测评接口清单（如含资源详情拒绝样例，需按新字典复核） |
| 复测项 | 若测评/外部联调固定了旧响应（404 + `1007C0005`），登记为改造后复测项；本卡交付说明显式披露 |
| 处置 | 文档评估 + 留痕；不新建测评回归脚本（Q5-B 不采） |

## §7 追溯矩阵（编码契约层）

| 层 | 锚点 |
| --- | --- |
| PRD | C-10.1 多租户与三员分立（P0；B4 空间隔离） |
| 规格 | `docs/specs/C-10.1-10.2-系统管理.md` V1.2：B3-R2（:74）/ B3-AC2（:80）/ B4-R2（:88）/ B4-AC2（:94） |
| ADR | ADR-005 出站口径 / ADR-016 §2.6 反枚举读面（400 + `1000C0003`） |
| 变更 | CHG-C-10.1-V1.3（§7 裁决 A 案） |
| 任务 | `docs/tasks/CHG-C-10.1-V1.3-资源域出站口径改造-2026-11-16.md` |
| 设计 | 本文件 + lofi V1.0 |
| 代码 | `DatasetQueryService`（R2/R14）/ `CatalogErrorCodes`（读面文案）/ `DatasetController`（注释） |
| 测试 | T1~T9（§4；T9 = 修复批新增鉴权边界锚） |
| 剧本 | C-10.1 V1.6 S2-3/S2-4/S2-5（正文零改动；验收后对照节状态注记回填） |

## §8 诚实边界

1. **S2-5 非资源域腿**：contract/delivery 初检无空间归属承载字段；本卡只复验资源域控制腿，非资源域腿按实测如实登记（Q6-A），不擅自扩面。
2. **防枚举 vs 状态码**：资源域读面业务拒绝经 `BizException` + `ErrorType.CLIENT` → **400**；框架层未匹配路由的 `NoResourceFoundException` 仍为 **404 + `1000C0003` + "资源不存在"**（全局既有行为，非本卡改造面），二者需在测评/联调中区分。三态同码同文仍是防枚举的实质保障（评审③ P2-5 更正表述）。
3. **历史留痕**：既有 `reason_code='C0005'` 行不回改，审计解释按"历史口径 + 改造时点"标注。

## §9 勘误位（编码段 / 评审段回填）

| # | 日期 | 勘误 | 触发 |
| --- | --- | --- | --- |
| E1 | 2026-11-16 | **`reason_code='C0003'` 码域歧义**：`tailOf` 取末 5 位，`1000C0003` 与既有 `1007C0003`（DATASET_IMPORTANT_REJECTED）同尾；留痕字段无法独立区分模块段。Q3-A 口径维持（不改设计契约），审计解读需结合动作/对象上下文；登记 **DB-133** 观察项。 | 评审③ P2-4 |
| E2 | 2026-11-16 | **400 与 404 的精确边界**：本卡业务读面拒绝 = 400；`GlobalExceptionHandler.NoResourceFoundException`（路由未匹配）仍 404 + `1000C0003` + "资源不存在"，属全局既有行为、非本卡面。§8 原"全局出站口径统一"表述收窄；common 文案字面重复为沉淀建议（随 DB-133 登记）。 | 评审③ P2-5 |
| E3 | 2026-11-16 | **前端旧常量消费方事实更正**：`RESOURCE_NOT_ACCESSIBLE_TIP` 全仓仅定义 + `CatalogSourceGuard.spec.ts` 值守卫，**无视图消费**；原"继续服务写面/治理面提示"不成立。常量保留不删，后续引用须按新口径评估。 | 评审③ P2-3 |
| E4 | 2026-11-16 | **跨空间证据语义**：R2 仅按 owner 判定、不读取空间上下文；"跨空间"与"同空间非属主"在实现上同一边界。T4/真机探针仅证明该分支同码同文，**不作独立空间归属证明**；B4-AC2 语义由非属主分支承载。 | 评审③ P2-7 / ④ P2-4 |
| E5 | 2026-11-16 | **DENIED_READ 残余侧信道**：非属主分支先 INSERT 再抛 400，不存在分支仅 SELECT，存在可统计的时序/DB 负载差；且已知存在 id 可重复生成留痕（写放大）。规格要求拒绝留痕，本卡不改；登记 **DB-134** 观察项。 | 评审② P2-1 |
| E6 | 2026-11-16 | **写面剩余存在性枚举通道（范围外）**：写面 `load()` 先判存在（404 + `1007C0005`）再判属主（403 + `1007C0006`），可区分 id 是否存在；本卡范围明确排除，按红线 4 登记 **DB-135**，待编排师裁定是否另立卡。 | 评审② P2-2 |
| E7 | 2026-11-16 | **测试契约同步修复批**：T1/T5/T6/T8 文案断言由生产常量改为**字面**（"资源不存在" / "资源不存在或无权访问"），避免自引用；T9 增补 R2 鉴权边界（401 + `1000C0002` / 403 + `1000C0005`）；T1~T9 标题与追溯表同步。行为值零变更。 | 重审④ P2-1/P2-2 |
