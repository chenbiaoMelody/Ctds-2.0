# DB-139 高保真设计（hifi）：产品面封装来源存在性枚举通道统一

> 任务卡 `docs/tasks/DB-139-产品面封装来源存在性枚举通道统一-2026-11-22.md`；lofi `docs/designs/DB-139-lofi.md`；Q1~Q4 已按建议口径一次确认（Q1-A1 / Q2-A / Q3-A / Q4-A，2026-11-22 编排师"Q1~Q4 确认"）。本文件定稿（V1.0）= 编码契约；实现与本文件不一致 = 打回项。

## 1. 接口契约表（W8 封装来源检查 × 两态出站）

端点不变：`POST /api/v1/data-products`（`ProductCommandService.create`，W8 校验链）。仅"来源检查"（链位 ④）两分支出站变化：

| # | 场景 | 现状（改造前） | 目标（Q1-A1 / Q2-A） | 留痕（dataset_action_log） |
| --- | --- | --- | --- | --- |
| W1 | 来源资源不存在 | 404 + `1007C0005` + "资源不存在或无权访问" | **400 + `1000C0003` + "资源不存在"** | 零行（不变） |
| W2 | 来源资源存在非属主（含跨空间语义） | 403 + `1007C0006` + "仅登记主体本人可执行该资源操作" | **400 + `1000C0003` + "资源不存在"**（与 W1 逐字同码同文） | DENIED_CREATE 恰 1 行；reason_code：`C0006` → **`C0003`**；历史行不回改 |

封套结构：`1000C0003` 不在 `CatalogExceptionHandler.MAPPED_CODES`（:25-26），走全局处理器 `ErrorType.CLIENT` → HTTP 400——与 DB-132/DB-135 交付口径同一通路；catalog 侧映射**零改动**（`1007C0005`→404 / `1007C0006`→403 映射保留，继续服务治理面/资格门槛族）。

**非改造面契约（零变化锚）**：ADMITTED 门槛（403 + `1007C0006` + `ADMISSION_REQUIRED_MESSAGE`/`CATALOG_ADMISSION_REQUIRED_MESSAGE`，Q8-A）· 状态门槛（409 + `1007C0016`）· 名称（`1007C0018`）/类目（`1007C0014`）/定价（`1007C0020`）/判重（`1007C0017`）· 幂等重放 · 未认证 401 · 无权限点 403 + `1000C0005` · 治理读面（404 + `1007C0005` + 旧文案）· 资源域读/写面（DB-132/DB-135 口径）。

## 2. 实现落位（3-1~3-5）

| # | 落位 | 内容 |
| --- | --- | --- |
| 3-1 | `ProductCommandService` 新增私有方法 `sourceNotFound()` | 统一拒绝构造：`new BizException(ErrorCodes.RESOURCE_NOT_FOUND, CatalogErrorCodes.DATASET_READ_NOT_FOUND_MESSAGE)`——与 DB-135 `DatasetCommandService.writeNotFound()` 完全同款手法（该类已 import `BizException`/`ErrorCodes` :15-16，零新增 import）；两私有方法维持"各服务私有、不抽公共层"镜像裁定（DB-135 评审③先例：common 码表已复用、最小实现、主流写法） |
| 3-2 | `create` :104-107 | `findById(...).orElseThrow(...)` 的 `CatalogBizException(DATASET_NOT_FOUND_OR_NO_ACCESS, ...)` → `orElseThrow(this::sourceNotFound)` |
| 3-3 | `create` :108-119 属主分支 | ① DENIED_CREATE 留痕**保留**（:111-116 落点/动作/结构零变化），仅 reason 实参 `tailOf(CatalogErrorCodes.DATASET_FORBIDDEN)` → `tailOf(ErrorCodes.RESOURCE_NOT_FOUND)`（= `C0003`，Q2-A）；② :117-118 `throw new CatalogBizException(DATASET_FORBIDDEN, ...)` → `throw sourceNotFound()` |
| 3-4 | javadoc 同步 | 类头 :23-24（W8 描述"④资源存在且本人（1007C0005/0006 复用…）"→ 统一口径表述）· create :84-88（④ 行）· :110 行内注释（"码复用 1007C0006"→ reason `C0003` + 统一拒绝说明）——纯措辞，零行为 |
| 3-5 | `CatalogErrorCodes` 注释两处 | :26-27（`DATASET_NOT_FOUND_OR_NO_ACCESS`："治理面/产品面沿用"→"治理面沿用；产品封装来源检查已按 DB-139 改统一口径"）· :34-36（`DATASET_FORBIDDEN` 消费面核对补注：继续服务资格门槛族 `CatalogAccessGuard`/`DatasetRegistrationService`/`publish` 资格复核，封装越权已退出）——**零新增常量、零常量语义变化** |

**校验链不变量**（编码段自检）：①认证 → ②ADMITTED → ③载体+定价（先于存在性查询，省一次查询）→ ④来源检查（本卡）→ ⑤状态门槛 `1007C0016` → ⑥类目 → ⑦幂等——链序与门槛优先序逐项不变。

## 3. 留痕契约（Q2-A）

| 项 | 口径 |
| --- | --- |
| 落点 | 资源域 `dataset_action_log`（不变——封装越权属资源域动作） |
| 动作/结果 | `DENIED_CREATE` / `DENIED`（不变） |
| reason_code | 新行记 `tailOf(1000C0003)` = **`C0003`**（随出站口径）；历史行不回改 |
| 触发 | 仅"存在非属主"分支恰 1 行；"不存在"零行（orElseThrow 先于留痕，不变） |

## 4. 测试设计（T1~T9 定稿；承载 `CatalogProductEncapsulationIntegrationTest`，沿 Testcontainers+MockMvc 既有范式与类内 helpers）

> 断言风格沿本类既有精确断言惯例（status/codeOf/message 逐字 + 留痕 SQL 计数），**不新建第二份 envelope helper**（DB-140 同构未收敛教训）；响应结构一致性以 T3 字段集合比对承载。

| # | 用例（Given/When/Then 摘要） | 性质 |
| --- | --- | --- |
| T1 | 他人登记资源封装（`createProductFromOthersDatasetDeniedWithResourceLog` 反转）→ 400 + `1000C0003` + "资源不存在" | 反转 |
| T2 | 不存在资源封装（`createProductFromMissingDatasetKeepsLegacyNotFoundShape` 反转，方法名改 `createProductFromMissingDatasetUsesUnifiedNotFoundShape`，注释 :138 同步）→ 同码同文 | 反转 |
| T3 | 两态响应逐字比对：HTTP 状态 + code/message 逐字 + JSON 根字段集合一致（白名单，不新增存在性差异字段） | 新增 |
| T4 | 状态门槛回归：已注销资源 / 已解散空间资源封装 → 409 + `1007C0016` 不变（既有 :148-163 保持） | 回归 |
| T5 | 留痕：T1 场景 `dataset_action_log` 恰 1 行 DENIED_CREATE + **reason=`C0003` 断言新增**（现状 :128-131 仅计数） | 反转+增强 |
| T6 | 留痕：T2 场景零行断言新增（现状已成立，补自动化锚） | 增强 |
| T7 | 属主正常封装成功 + 同请求幂等重放不变（既有用例保持） | 回归 |
| T8 | 非改造面锚：未入驻统一文案（:341-353 保持）+ 治理读面 `CatalogGovernanceIntegrationTest` :124-130 保持 + 资源登记门槛保持 + 资源域读/写面 DB-132/135 口径保持 | 回归 |
| T9 | 封装未认证 401 既有行为（沿 DB-135 T10 手法；类内若无既有未认证用例则新增 1 例） | 新增/回归 |

**红相预测**（编码段验证口径）：T1/T2（旧断言 403/404/`1007C0005`/`1007C0006` 失败于新期望）、T3（旧实现两态不一致）、T5（reason `C0006`≠`C0003`）先失败；T4/T6/T7/T8/T9 与既有其余用例绿——B3 资格门槛隔离线（:341-353 等）零误伤实证。类 javadoc :32-38 反转措辞同步（"非本人资源拒绝（1007C0006 + …）"→ 统一口径表述）。

**模块复跑口径**：目标类红相 → 实现 → 绿相 → `mvn -B -ntp clean test checkstyle:check`（catalog 全量 + 全仓门禁单发另跑，验收标准⑥）。

## 5. 规格 → 剧本 → 测试映射（定稿，替代任务卡 §三 预告编号）

| 规格行为 / 验收标准 | 剧本步骤 | 承载 |
| --- | --- | --- |
| 行为 3 AC（:96）他人资源被拒且留痕 | 封装幕判定面（被拒绝，无固定码值） | T1 / T3 / T5 |
| 行为 3 规则 1（:86）+ AC（:97）状态门槛 | 同上（回归） | T4 |
| 行为 3 规则 6（:91）幂等 + 留痕四要素 | S1-6 / 留痕核对 | T5 / T6 / T7 |
| 防枚举意图（两态同码同文） | —（Q1-A1 字面） | T1~T3（逐字 + 白名单） |
| 非改造面零变化 | —（回归） | T4 / T8 |
| 鉴权边界 | —（回归） | T9 |

## 6. Q3-A 静态核查登记（不扩面，红线 4 申报兜底；2026-11-22 设计段实证）

| 消费点 | 落位 | 核查结论 |
| --- | --- | --- |
| 入驻资格门槛 | `CatalogAccessGuard.java:42-50`（:48 throw `DATASET_FORBIDDEN`） | Q8-A 独立设计（主体未入驻语义 + 统一文案），非资源 id 引用型出站——零变化 |
| 资源登记门槛 | `DatasetRegistrationService.java:108/:132-134`（资格/非成员 throw + `insertDenied` DENIED 留痕） | Q8-A 同族（登记资格语义），零变化 |
| 产品目录查询 | `ProductCatalogQueryService.java:24` | **类 javadoc 注释字面**（R6~R8 资格门槛说明），非出站消费——零变化 |
| 产品交互 | `ProductInteractionService.java:22` | 类 javadoc 注释字面，非出站消费——零变化 |
| 产品上架资格复核 | `ProductCommandService.java:218`（`publish` javadoc"1007C0006 同族统一文案"） | 产品属主动作的资格门槛语义（上架属主门槛另走 `1007C0015`），非资源 id 引用型——零变化 |
| 异常映射 | `CatalogExceptionHandler.java:26/:44` | `1007C0005`→404 / `1007C0006`→403 映射保留（服务治理面/资格门槛族）——本卡零改动 |

结论：**产品面无第二处"资源 id 引用型"同型出站**——封装来源检查是唯一改造面；其余全部为资格门槛族（Q8-A 独立设计）或注释字面。零扩面、零新增申报项。

## 7. 测评面影响评估框架（Q4-A：编码段落盘正式版）

- 全仓 grep 基数（改造前后对照）：`1007C0005`/`1007C0006`/`1000C0003` + 封装端点，主源/测试/前端/std-adapter/迁移 SQL/剧本分列；
- 外部行为变化 = 仅 W8 封装两态拒绝（status 404/403 → 400、码 → `1000C0003`、文案统一"资源不存在"）；消费方：前端（零分支——零影响）、剧本（无固定预期——正文预计零改动）、联调（如固定旧预期需复核，登记观察项）、测评（如固定旧预期 → 登记复测项）；
- 结论模板：规格符合性收敛（向 ADR-016 反枚举方向），零强制复测项（以编码段实测回填）。

## 8. 验收基准对照

- 规格 C-3.x 行为 3 AC（:96"被拒绝且留痕"）+ 规则 1（:86 封装前提）字面即本卡验收基准；出站统一为 Q1-A1 裁决口径（规格内设计自由度收敛，规格零改动）；
- 剧本 C-3.3 r2 正文预计零改动（S1-5 仅未入驻场景；无两态固定码值/文案预期——立卡段 grep 实证）；验收后对照节注记回填；
- 防枚举回归锚 = DB-132 读面 + DB-135 写面交付口径（T8），三卡合观 = 资源域读+写+产品封装引用出站单一字典。

## 9. 勘误位与观察项

| 编号 | 级别 | 内容 | 处置 |
| --- | --- | --- | --- |
| E-1 | 观察项（行号漂移，零行为影响） | §2 3-4 引用"类头 ：23-24（W8 描述"④资源存在且本人（1007C0005/0006 复用…）"）"——编码段实读：类头 javadoc（ProductCommandService.java:22-33）**无该表述**，"④资源存在且本人（1007C0005/0006 复用…）"实际位于 **create 方法 javadoc :85-87**；按内容落位执行（create javadoc ④ 行 + :110 行内注释两处已改，类头无需改动） | ✅ 已按内容落位完成（2026-11-22 编码段）；纯引用行号勘误，设计实质零变更 |

---

**两级设计 V1.0 已生效（2026-11-22 编排师定稿确认，指示原文"设计定稿确认"；编码契约即时生效；Q1~Q4 已按建议口径一次确认）**；下一关口 = 编码段建分支/提交授权（须编排师显式指示）。
