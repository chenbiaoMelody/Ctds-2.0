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
| T3 | 两态响应逐字比对：HTTP 状态 + code/message 逐字 + JSON 根字段恰为 `code,message,traceId,data`，两态 `data` 均为 `null`（traceId 允许逐请求不同，不参与存在性判定） | 新增 |
| T4 | 状态门槛回归：已注销资源 / 已解散空间资源封装 → 409 + `1007C0016` 不变（既有 :148-163 保持） | 回归 |
| T5 | 留痕：T1 场景 `dataset_action_log` 恰 1 行 DENIED_CREATE + **reason=`C0003`、result=`DENIED`、created_at 非空**；预置历史 `C0006` 行并断言请求后逐字段不变 | 反转+增强 |
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
| 产品上架资格复核 | `ProductCommandService.java:227`（`publish` javadoc"1007C0006 同族统一文案"） | 产品属主动作的资格门槛语义（上架属主门槛另走 `1007C0015`），非资源 id 引用型——零变化 |
| 异常映射 | `CatalogExceptionHandler.java:26/:44` | `1007C0005`→404 / `1007C0006`→403 映射保留（服务治理面/资格门槛族）——本卡零改动 |

结论：**产品面无第二处"资源 id 引用型"同型出站**——封装来源检查是唯一改造面；其余全部为资格门槛族（Q8-A 独立设计）或注释字面。零扩面、零新增申报项。

## 7. 测评面影响评估（Q4-A：正式版，2026-11-24）

### 7.1 检索口径与基线

- 基线 = `db885f8`（`af03c3e` 的父提交，即 DB-139 实现前）；当前 = **修复候选工作区**（提交 `f28d23f` + 本轮封套/留痕断言强化；生产主源与 `f28d23f` 一致）。
- 检索式 = `1007C0005|1007C0006|1000C0003`，辅以 `data-products` 与 `POST /api/v1/data-products`；计数口径 = **匹配行数**，三码合并表按行去重，单码表因同一行可含多码而不可相加。
- 基线由 `git grep` 直接读取 `db885f8` 提交树；当前读取 Git 跟踪文件的工作区内容，排除 gitignored `build-output/`。端点基数按可执行代码范围单列 `services`、`frontend`、`connector-sdk`，不把历史日志/设计文档混入消费方计数。

### 7.2 分类命中基数（三码合并、按行去重）

| 类别 | 基线 | 当前 | 结论 |
| --- | ---: | ---: | --- |
| 服务 Java 主源（`services/**/src/main/**/*.java`） | 53 行 / 26 文件 | 53 行 / 26 文件 | 总数不变，仅封装来源检查发生预期码值替换 |
| 服务 Java 测试（`services/**/src/test/**/*.java`） | 81 行 / 32 文件 | 80 行 / 32 文件 | 减少 1 行重复完整码值断言；新增固定封套与留痕字段断言，文件数不变 |
| 前端全部（含 spec） | 30 行 / 14 文件 | 30 行 / 14 文件 | 零改动；其中生产代码 9 行 / 6 文件，前后一致 |
| `std-adapter` | 0 | 0 | 无相关消费 |
| `connector-sdk` | 0 | 0 | 三码零命中；但 `data-products` 为 11 行 / 6 文件，直接消费 W8，详见 §7.3 |
| 迁移 SQL | 1 行 / 1 文件 | 1 行 / 1 文件 | `V4` 注释中的 `1007C0006`，非 W8 出站契约 |
| `scripts` / `deploy` | 0 | 0 | 无固定旧预期制品 |
| 服务配置（`application*.yml/yaml/properties`） | 1 行 / 1 文件 | 1 行 / 1 文件 | `services/did/.../application.yml:52` 的 `1000C0003` 注释，非 W8 固定预期 |
| `common` | 2 行 / 2 文件 | 2 行 / 2 文件 | 公共码定义与全局处理器测试，零改动 |
| 规格（`docs/specs`） | 6 行 / 3 文件 | 6 行 / 3 文件 | 均属其他已批准场景；C-3.x 封装两态无固定码值 |
| 验收剧本（`docs/manuals`） | 20 行 / 5 文件 | 21 行 / 5 文件 | 新增 1 行 DB-139 维护说明；C-3.3 正文无 W8 两态旧预期 |

单码迁移进一步确认变化只落在预期位置：

| 范围 | `1007C0005` | `1007C0006` | `1000C0003` |
| --- | ---: | ---: | ---: |
| 服务主源：基线 → 当前 | 5 → 4 | 13 → 12 | 36 → 38 |
| 服务测试：基线 → 当前 | 5 → 3 | 16 → 13 | 60 → 64 |
| 前端：基线 → 当前 | 3 → 3 | 7 → 7 | 20 → 20 |
| 迁移 SQL：基线 → 当前 | 0 → 0 | 1 → 1 | 0 → 0 |
| 剧本：基线 → 当前 | 4 → 4 | 4 → 5 | 17 → 18 |

封装端点未变：`ProductCommandController` 的类级 `/api/v1` 与方法级 `POST /data-products` 仍组合为 `POST /api/v1/data-products`；前端 `createProduct` 与 connector-sdk `publishProduct` 仍调用同一路径。宽口径 `data-products` 在可执行代码范围前后均为 **95 行 / 41 文件**：services 62/30、frontend 22/5、connector-sdk 11/6；未新增或删除消费入口。

### 7.3 外部行为变化与消费方清单

唯一行为变化 = W8 封装来源检查的两种拒绝：不存在由 404 + `1007C0005` + “资源不存在或无权访问”、存在非属主由 403 + `1007C0006` + “仅登记主体本人可执行该资源操作”，统一为 **400 + `1000C0003` + “资源不存在”**；DENIED_CREATE 留痕保留，新行 reason_code 为 `C0003`。成功封装、状态门槛、入驻门槛、治理读面、资源域读/写面均不变。

| 消费方 | 核查证据 | 影响与处置 |
| --- | --- | --- |
| 前端产品管理 | `frontend/src/api/catalog.ts:339-344` 调用 W8；`frontend/src/api/client.ts:56-59` 将非成功码原样抛为 `ApiError`；`frontend/src/views/catalog/ProductManageView.vue:97-100` 原样展示 `message` | 不按三码分支，无专有旧文案常量；**零改动、无需前端复测** |
| 前端其他目录场景 | `frontend/src/constants/catalog.ts` 的 `1007C0005/0006` 属资源同形/未入驻语义；相关 spec 已独立锁定 | 非 W8 两态消费，**零影响** |
| connector-sdk | `DefaultProductCapability.java:24` 直接 POST W8；`PlatformClient.java:122-134` 将非 2xx 统一封套解析后原码原文透传，不按三码分支；`CapabilityRoutingTest.java:118-141` 锁定产品封装路由/载荷 | 无固定旧码预期，拒绝语义继续透传；**零改动、无新增强制复测项** |
| `std-adapter` | 三码与 `data-products` 均零命中 | 无适配器契约或转换逻辑，**零影响** |
| 服务间联调 | 服务 Java 主源内 W8 创建端点仅 `ProductCommandController` 一处；contract/delivery 的产品客户端消费内部产品读端点，不调用 W8；SDK 消费另列上项 | 仓内服务无固定旧预期的 W8 调用方，**零影响** |
| 验收剧本 | C-3.3 S1-2 只判定“被拒绝且留痕”；S2-5 的 `1007C0006` 是未入驻门槛，先于 W8 两态 | 正文零改动；仅补维护说明对照注记 |
| 测评/联调制品 | `scripts`、`deploy` 三码与端点零命中；服务配置仅 `services/did/src/main/resources/application.yml:52` 注释命中 `1000C0003`；仓内无独立测评脚本固定 W8 旧码 | **仓内强制复测项 = 0**；仓外制品若固定旧预期，按交付说明通知其改用新口径 |
| 历史设计文档 | `WBS-3.3.5-hifi.md` W8 行与 `CHG-C-10.1-V1.3-hifi.md:30,63,78` 仍列旧码 | 属历史契约，现行编码/验收契约以 DB-139 hifi V1.0 为准；登记 §9 E-2 观察项，不在本卡扩面改写 |

### 7.4 结论与复测项登记

- **规格符合性**：C-3.x 只要求他人资源封装被拒绝且留痕，未固定出站码；本次在规格内将出站口径向 ADR-016 反枚举方向收敛，规格零改动。
- **测评影响**：仓内前端、connector-sdk、std-adapter、服务间联调、迁移、脚本与剧本均无阻塞性旧预期；SDK 直接消费 W8，但拒绝原码原文透传且无旧码固定分支，已登记 §7.3；既有 T1~T9、catalog 全量、SDK 全量、当前修复候选树全仓门禁 GREEN（`gate-report-20261124-123218`）与真机 5/5 已覆盖新口径。
- **强制复测项**：**0 项**。仓外联调/测评通知列为交付注意事项，不虚构仓外核查结果。
- **剧本维护回答**：C-3.3 正文零改动；S1-2 判定面不变，S2-5 未入驻门槛不受影响，维护说明补 DB-139 对照注记。

## 8. 验收基准对照

- 规格 C-3.x 行为 3 AC（:96"被拒绝且留痕"）+ 规则 1（:86 封装前提）字面即本卡验收基准；出站统一为 Q1-A1 裁决口径（规格内设计自由度收敛，规格零改动）；
- 剧本 C-3.3 r2 正文预计零改动（S2-5 仅未入驻场景；无两态固定码值/文案预期——立卡段 grep 实证）；验收后对照节注记回填；
- 防枚举回归锚 = DB-132 读面 + DB-135 写面交付口径（T8），三卡合观 = 资源域读+写+产品封装引用**响应封套**单一字典；同步审计时序/故障残余按 §9 E-3 / DB-134 跟踪。

## 9. 勘误位与观察项

| 编号 | 级别 | 内容 | 处置 |
| --- | --- | --- | --- |
| E-1 | 观察项（行号漂移，零行为影响） | §2 3-4 引用"类头 ：23-24（W8 描述"④资源存在且本人（1007C0005/0006 复用…）"）"——编码段实读：类头 javadoc（ProductCommandService.java:22-33）**无该表述**，"④资源存在且本人（1007C0005/0006 复用…）"实际位于 **create 方法 javadoc :85-87**；按内容落位执行（create javadoc ④ 行 + :110 行内注释两处已改，类头无需改动） | ✅ 已按内容落位完成（2026-11-22 编码段）；纯引用行号勘误，设计实质零变更 |
| E-2 | P3 观察项（历史设计契约未同步，零运行影响） | `WBS-3.3.5-hifi.md` §1 W8 行与 `CHG-C-10.1-V1.3-hifi.md:30,63,78` 仍列改造前 `1007C0005/1007C0006`；两份文件均为历史设计契约，非测评脚本，现行契约以 DB-139 hifi V1.0 为准 | 2026-11-24 测评面评估登记；仓内零强制复测项，不在本卡扩面改写历史设计，后续文档维护批评估补“已被 DB-139 取代”注记 |
| E-3 | P2-3 处置（转既有 DB-134 跟踪） | 响应封套枚举差异已关闭；非属主同步 `DENIED_CREATE` 留痕仍比不存在分支多一次 INSERT，保留时序/DB 负载差，审计写入故障时亦可能出现 500/400 差异。该模式与 DB-132 读面、DB-135 写面同源，规格要求拒绝留痕，本卡未引入 | 2026-11-24 第 1 轮评审修复：不改变同步审计设计、不声称消除时序/故障侧信道；并入既有债务 DB-134（台账 :169），由后续专项评估异步留痕/去重/限流。演示期回环绑定为现状缓解；第 2 轮安全视角已复核 PASS-附观察项，DB-134 风险仍开放 |

---

**两级设计 V1.0 已生效（2026-11-22 编排师定稿确认，指示原文"设计定稿确认"；编码契约即时生效；Q1~Q4 已按建议口径一次确认）**；编码、全仓门禁、真机复验与测评面影响评估已完成；2026-11-24 第 1 轮评审修复已落盘（SDK 消费方补查、封套/留痕断言强化、P2-3 转 DB-134），目标类 18/18、catalog 207/207、SDK 112/112、checkstyle 0 已通过，全仓门禁 **GREEN**（`gate-report-20261124-123218`：PASS=15/FAIL=0、ConfigSha256 逐字一致、coverage 92.52%）；第 2 轮四视角评审已 PASS-附观察项（0 P0/P1/P2）；独立复审 PASS（2026-11-24，0 P0/P1/P2，2 项非阻塞 P3，日志 -1321）；编排师验收通过（2026-11-24）；提交/推送/PR/合并已获显式授权。
