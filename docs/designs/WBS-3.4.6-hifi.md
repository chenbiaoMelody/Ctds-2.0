# WBS-3.4.6 高保真设计（hifi）：策略模拟器与测试台

> **版本**：**V1.0（编码契约，2026-10-08 编排师"Q1~Q7 + D1 均按建议"一次确认）**——**Q1~Q7 均采建议口径 A + D1 不拆分**（确认记录落本文件末节；此前 V0.9 草案随立卡批 `66297f9` 落盘）｜ 落点任务卡：`docs/tasks/WBS-3.4.6-策略模拟器与测试台-2026-10-08.md` ｜ 规格：`docs/specs/C-4.1-4.3-数字合约与使用控制.md` V1.0 行为 5 规则 4（双向用例）+ 尾注未定义项"双向用例的模拟器形态（3.4.6）" ｜ 上游：`ADR-020`（引擎契约）、`ADR-019`（DSL 与要素目录）
> **确认状态**：✅ 已确认（2026-10-08，编排师"Q1~Q7 + D1 均按建议"）——本文件为**编码契约**，实现与设计逐条一致（评审①锚）

---

## 1. 包骨架与落点（Q1-A）

```
services/contract-service/src/main/java/com/ctds/contract/
├── domain/policy/
│   ├── PolicyJudge.java              // 【改·只增】新增 judgeQuota(policy, assumedUsedCount) 纯函数（既有 judge(...) 零改动）
│   ├── SimulationContext.java        // 【新】假想上下文值对象（assumedUsedCount / assumedDate + 缺省填充工厂）
│   └──（零改动：UsageRequest / UsageActionType / UsageVerdict / PolicyViolation / UsageLogEntry / UsagePolicyDslParser / PolicyElementCatalog）
├── application/
│   ├── PolicySimulationService.java  // 【新】模拟试算 + 测试台编排（只读事务；可见性 → QC1 → 草稿解析(可选) → 判定镜像 → 装配视图）
│   ├── PolicyTestbenchScenarios.java // 【新】内置场景集定义（目录驱动 11 条；场景构造与预期）
│   ├── ContractVisibilityGuard.java  // 【新】可见性守卫（参与方 / 参与方+治理 两种口径 + DENIED_ACCESS 留痕单点）
│   ├── UsageQueryService.java        // 【改·等价重构】R12 改用共享守卫（行为零变更，回归保护）
│   └──（零改动：PolicyExecutionService / ContractQueryService / ContractCommandService / 模板侧）
├── interfaces/
│   ├── ContractPolicySimulationController.java  // 【新】模拟试算 + 测试台两端点
│   ├── ContractUsageExecutionController.java    // 【新】受控执行端点（S2/S3 演示入口）
│   └── dto/SimulationViews.java                 // 【新】模拟结论 / 测试台报告 / 受控执行出站视图
└── infrastructure/
    └──（零改动：UsageCounterStore〔只读 currentCount 沿用〕/ UsageLogRepository / 迁移 V1~V3）
```

- **零 DDL**：复用 V3 两表（`contract_usage_counter` / `contract_usage_log`）；**零新迁移**（下一可用版本仍为 V4，本卡不用）。
- **既有控制器 diff 为空**：三个端点全部落在**两个新控制器**，既有 7 个控制器不改（评审① 可核）。
- **分层纪律**：Controller → `PolicySimulationService` / `PolicyExecutionService` → 端口；判定纯函数（`PolicyJudge`）无 IO；模拟服务不做任何写操作。

## 2. 模拟试算端点契约（Q2-A）

`POST /api/v1/contracts/{contractNo}/policy-simulations`｜`@RequirePermission("contract.deal")`｜可见性 = 参与方 + 治理（沿 R12 口径）

**请求 `PolicySimulationRequest`**（入参白名单——无任何"跳过判定/强制放行"字段）

| 字段 | 类型 | 必填 | 约束与语义 |
| --- | --- | --- | --- |
| `actionType` | 字符串（`USE` / `REDISTRIBUTE`） | 是 | 映射 `UsageActionType`；取值非法 → `1008C0008` |
| `purpose` | 字符串 | 否 | 本次声明的用途（与约定值精确等值比较）；长度上限沿策略文本既有上限常量（编码段对齐） |
| `territory` | 字符串 | 否 | 本次声明的域（同上） |
| `assumedUsedCount` | 整数 | 否 | **假想已用次数**：`0 ≤ 值 ≤ 1_000_000`，越界 → `1008C0008`；**缺省 = 真实 `used_count`**（无计数记录 = 0） |
| `assumedDate` | 字符串（ISO-8601 日期） | 否 | **假想判定日期**；格式非法 → `1008C0008`；**缺省 = 注入 Clock 当天** |
| `strategyDocument` | JSON 对象 | 否 | **策略草稿试算**：非空时经 `UsagePolicyDslParser.parse(document, 注入 Clock 当天)` 单点校验，`violations` 非空 → `1008C0015`；缺省 = 合约当前生效策略（QC1） |

**响应 200 `PolicySimulationView`**

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `contractNo` | 字符串 | 合约编号 |
| `contractStatus` | 字符串 | 合约状态（未生效 / 已生效 / 已终止 / 已完结…，沿 QC1 快照） |
| `strategySource` | 枚举 | `EFFECTIVE`（合约当前生效策略）/ `DRAFT`（调用方提交的草稿） |
| `strategyEffective` | 布尔 | 策略是否有效（**否** = 合约未生效/已终止/已完结 → `allowed=false`、`violations=[]`、`policySnapshot=null`） |
| `allowed` | 布尔 | 模拟判定结论（放行 / 拒绝） |
| `violations` | 字符串数组 | 触发要素枚举名（全查明细——一次触发多项全部列出）；`QUOTA_EXHAUSTED` 仅当四要素全过且配额耗尽 |
| `assumedUsedCount` / `assumedDate` | 整数 / 日期 | 本次实际使用的假想上下文（回显，便于界面与报告核对） |
| `policySnapshot` | 对象 | 参与判定的要素取值快照（要素键 → 取值文本；仅要素启用项） |

**判定顺序：引擎判定顺序表的镜像**（ADR-020 §2.2 步 1~9 的只读等价）

1. 合约定位（QC1；不存在/不可见 → `1008C0012` 防枚举同形）；
2. 状态门槛：合约非生效 → `strategyEffective=false`、`allowed=false`（**不抛异常**，以数据表达）；
3. 空策略 / 显式无限制 → `allowed=true`（标注无限制来源）；
4. 四要素全查（`PolicyJudge.judge(policy, request, assumedDate)`）：有触发 → 拒绝 + `violations` 全查明细，**不再判配额**（与真实执行腿步 8 一致）；
5. 四要素全过 → 配额**纯判定**（`PolicyJudge.judgeQuota(policy, assumedUsedCount)`）→ 耗尽则拒绝并报告 `QUOTA_EXHAUSTED`。

**语义要点**：① 判定结论**以数据返回（HTTP 200 + `allowed=false`）**——模拟拒绝不是系统错误（与执行通道的异常语义分离，避免界面/报告把"如期被拒"显示成报错）；参数/权限/不存在才走异常；② **零副作用**：不写计数、不写执行记录、不落库（§8）。

## 3. 测试台端点契约与场景集（Q4-A）

`POST /api/v1/contracts/{contractNo}/policy-testbench-runs`（无请求体）｜`@RequirePermission("contract.deal")`｜可见性 = 参与方 + 治理

**响应 200 `PolicyTestbenchReport`**

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `contractNo` / `contractStatus` / `strategyEffective` | 字符串 / 字符串 / 布尔 | 被测合约与策略状态 |
| `runAt` | 时间 | 执行时点（注入 Clock） |
| `scenarios[]` | 数组 | 逐条场景：`code` / `elementKey` / `direction`（`ALLOW` / `DENY`）/ `expectation`（期望文本）/ `outcome`（`PASS` / `FAIL` / `SKIPPED`）/ `actual{allowed, violations[]}` / `note` |
| `summary` | 对象 | `total` / `pass` / `fail` / `skipped` |

**场景集（内置 11 条，目录驱动；场景构造一律经模拟通道，零副作用）**

| 场景码 | 要素 | 方向 | 构造（假想上下文） | 期望（合约生效且要素启用时） |
| --- | --- | --- | --- | --- |
| U1 | `usage.quota` | ALLOW | 假想计数 = 上限 − 1 | 放行 |
| U2 | `usage.quota` | DENY | 假想计数 = 上限 | 拒绝，触发要素 = 次数耗尽 |
| U3 | `usage.term` | ALLOW | 假想日期 = 起始日 | 放行 |
| U4 | `usage.term` | DENY | 假想日期 = 截止日 + 1 天 | 拒绝，触发要素 = 期限届满 |
| U5 | `usage.purpose` | ALLOW | 用途 = 约定值 | 放行 |
| U6 | `usage.purpose` | DENY | 用途 = 约定值 + `-越界`（≠） | 拒绝，触发要素 = 用途不符 |
| U7 | `usage.territory` | ALLOW | 域 = 约定值 | 放行 |
| U8 | `usage.territory` | DENY | 域 = 约定值 + `-越界`（≠） | 拒绝，触发要素 = 域外使用 |
| U9 | `usage.no_redistribution` | DENY | 动作 = `REDISTRIBUTE` | 拒绝，触发要素 = 禁止再分发 |
| U10 | `usage.no_redistribution` | ALLOW | 动作 = `USE` | 放行 |
| U11 | 空策略 / 显式无限制 | ALLOW | 无启用要素的合约 | 放行且不计数（`usedCount` 不变） |

**三态判定规则（诚实不假绿）**

1. **要素未启用** → 该要素的两条场景 `SKIPPED`（既不判通过也不判失败）；
2. **合约非生效态** → 全部场景 `expectation` = "拒绝（策略失效）"，`actual.allowed=true` 记 `FAIL`（S3-7 联动；报告 `strategyEffective=false` 显式标注）；
3. **生效且要素启用** → 按上表判定；`PASS` = 实际与期望一致（放行/拒绝 + 触发要素集合一致）。
4. 空策略合约（显式无限制）→ U1~U10 全 `SKIPPED`，U11 `PASS`。

**同源约束**（承接 3.4.4 移交-2）：要素清单取自 `PolicyElementCatalog.keys()`，场景构造与预期按 `ElementDefinition.judgmentSemantics()` 解释——**场景集不复制要素语义**；结构锚测试断言"目录中每个要素都有 ALLOW / DENY 两条场景"（目录扩要素而漏配场景 → 测试红）。

**零落库**：报告**不写** `contract_usage_log` / `contract_usage_counter`（自检动作不是业务使用事件；归档需求登记移交-3）。

## 4. 受控执行端点契约（Q5-A：S2 / S3 演示入口）

`POST /api/v1/contracts/{contractNo}/usage-executions`｜`@RequirePermission("contract.deal")`｜可见性 = **仅参与方**（治理方不发起使用动作——防止治理"代他人使用"污染计数与流水；治理的对照面经摘要读面 R12 与模拟/测试台承载）

**请求 `UsageExecutionRequest`**：`actionType`（必填，同枚举）/ `purpose`（可空）/ `territory`（可空）——**发起方 = `AuthContext.subject()`**（不接受"以他人身份发起"参数）。

**响应与拒绝**

| 情形 | 出口 |
| --- | --- |
| 放行 | HTTP 200 + `{contractNo, allowed=true, usedCount, occurredAt}`（`usedCount` = 递增后真实值） |
| 越界使用 | `1008C0020` → HTTP 403（既有处理器映射；触发要素入拒绝留痕与服务端日志） |
| 合约状态失效（未生效/终止/完结） | `1008C0013` → HTTP 409 |
| 合约不存在 / 非参与方 | `1008C0012` → HTTP 404（**同码同文**，防枚举）+ `DENIED_ACCESS` 留痕 |
| 参数非法 | `1008C0008` → HTTP 400 |

**编排**：可见性守卫（仅参与方）→ `PolicyExecutionService.check(contractNo, new UsageRequest(subject, actionType, purpose, territory))`（**零改动**：计数递增、放行记录、拒绝留痕、配额判检一体原子递增全部沿用 3.4.5 链路）。

**幂等**：**刻意不设幂等键**（沿 ADR-020 §2.7："按调用计数"语义下重试 = 新的一次调用；需求方如需请求级幂等在其调用侧承接）。

**诚实边界（三写）**：① 本端点是**演示期受控调用入口**，不是交付链本体——C-5.x 上线后由 3.4.8 复核"本端点 ≡ 交付链判定面"并评估保留/收敛（移交-2）；② 网关未上线前，判定仅覆盖**应用层拦截**（规格 §6-1 原文）；③ "下载后线下扩散"不在承诺内（规格 §6-4 原文）。

## 5. 配额纯判定与两腿同源（Q3-A）

```java
// domain/policy/PolicyJudge.java（只增；既有 judge(...) 零改动）
/**
 * 配额纯判定（模拟通道与对照锚使用；不触库、无副作用）。
 * 语义 = 限值来源（策略 quota.limit）+ 耗尽判据（assumedUsedCount >= limit），
 * 与真实执行腿 UsageCounterStore.tryIncrement 的条件 UPDATE 同源（ADR-021 登记）。
 */
public static List<PolicyViolation> judgeQuota(UsageControlPolicy policy, int assumedUsedCount);
```

- 要素未启用（或策略为空/显式无限制）→ 返回空列表；
- `assumedUsedCount >= 限值` → 返回 `[QUOTA_EXHAUSTED]`；
- **入参合法性**由应用层校验（`0 ≤ 值 ≤ 1_000_000`，非法 → `1008C0008`），纯函数不承担参数错误表达。

**两腿同源声明（落 ADR-021）**：配额语义在平台内只有一套——"限值取自策略、耗尽即 `已用 ≥ 限值`"；真实执行腿以数据库单语句条件 UPDATE 实现（并发安全），模拟腿以纯函数实现（可零副作用试算）；两者不得出现第二套限值来源或第二套耗尽判据（**禁止两处并行定义**的要素语义面继续由 `PolicyElementCatalog` 单点承载）。

**对照一致性锚（测试，硬性）**：当假想上下文 = 真实状态时（`assumedUsedCount` = 真实 `used_count`、`assumedDate` = 注入 Clock 当天），**模拟结论必须与真实执行结论逐项一致**（放行/拒绝 + 触发要素集合），覆盖 100/101 边界与各要素越界腿——这是"同源"的可执行证据。

## 6. 可见性守卫抽取契约（Q6-A，跟踪-17 兑现）

```java
// application/ContractVisibilityGuard.java（新）
// 以 UsageQueryService（R12）既有可见性 + 拒绝留痕形态为基准抽取；错误码/文案/留痕动作逐字一致
public void requireReadable(String contractNo, ContractSnapshot snapshot, String operatorNo, String actionCode);
public void requireParticipant(String contractNo, ContractSnapshot snapshot, String operatorNo, String actionCode);
```

- `requireReadable`：**参与方 + 治理（admin 角色头）** 通过；否则 `1008C0012`（与"合约不存在"**同码同文**）+ `DENIED_ACCESS` 留痕——R12 / 模拟试算 / 测试台使用；
- `requireParticipant`：**仅参与方**通过（治理不例外）——受控执行使用；
- 消费方：R12（`UsageQueryService` 等价重构）+ 模拟 / 测试台 / 受控执行三新端点；
- **行为零变更**：由 R12 既有可见性矩阵锚与防枚举断言回归保护；`ContractQueryService` 既有实现**本卡不动**（合约域守卫统一收敛沿跟踪-17 登记续办）。

## 7. 错误码与 HTTP 语义表（Q7-A：零新码位）

| 情形 | 错误码（既有） | HTTP | 说明 |
| --- | --- | --- | --- |
| 请求体 / 参数非法（动作类型、假想计数越界、日期格式） | `1008C0008` | 400 | 复用 |
| 策略草稿校验不过（模拟试算 `strategyDocument`） | `1008C0015` | 400 | 复用解析器单点结论 |
| 合约不存在 / 不可见（非参与方） | `1008C0012` | 404 | **防枚举同码同文** |
| 受控执行：合约状态失效 | `1008C0013` | 409 | 复用（S3-7 联动） |
| 受控执行：越界使用（五类拦截统一） | `1008C0020` | 403 | 复用；触发要素入留痕与日志 |
| 模拟试算 / 测试台判定为拒绝 | —（非错误） | 200 | **结论以数据返回**（`allowed=false` / 报告 `fail`） |
| 权限点缺失 | common-auth 既有口径（`1000C0005`） | 403 | 零改动 |

**零新码位**：`ContractErrorCodes` / `ContractExceptionHandler` / 一致性测试计数**均不改**（评审①可核）。

## 8. 零副作用、事务与时钟口径

| 项 | 口径 |
| --- | --- |
| 模拟试算 | `@Transactional(readOnly = true)`；只读计数（`UsageCounterStore.currentCount`）——**不调** `tryIncrement`；不写 log |
| 测试台 | 同上（11 条场景共用只读事务）；不写 log / counter；报告不落库 |
| 受控执行 | `check` 自带事务语义（放行腿计数 + 记录同事务；拒绝留痕独立事务 REQUIRES_NEW）——**零改动** |
| 时钟 | 假想日期缺省 = `LocalDate.now(clock)`；`runAt` / `occurredAt` = `LocalDateTime.now(clock)`；**禁止直取 `LocalDate.now()`**（DB-09 / DB-22 两把钟教训）；模拟通道的假想日期为**显式假设口径**（不影响真实执行腿的注入 Clock） |
| 幂等 | 模拟 / 测试台零副作用（无需幂等）；受控执行**不设幂等键**（ADR-020 §2.7 登记） |
| 防绕过 | 三端点入参白名单（无 `force*` / `bypass*` / `skipJudgment` / `assumedAllowed` 类参数）；结构锚测试断言 |
| 敏感面 | 请求侧自由文本不入日志不入库（留痕四要素口径）；模拟与测试台不落任何文本；零加解密改动（不触碰 SM4 密文列） |

## 9. 测试计划（五面，测试先行——新判定与端点先红后绿）

| 面 | 计划锚（方法名，编码段允许按实测微调并回填任务卡 §三） |
| --- | --- |
| ① 模拟判定矩阵（单测） | `simulationFiveElementsBidirectionalMatrix`（五要素 × 双向）；`simulationQuotaBoundaryAtLimitMinusOneLimitAndLimitPlusOne`；`simulationTermBoundaryStartDayEndDayAndBeyond`；`simulationPurposeAndTerritoryExactEquality`；`simulationDraftStrategyValidAndInvalidRejectedC0015`；`simulationEmptyOrExplicitNoRestrictionAllowed`；`simulationAssumedCountOutOfRangeRejectedC0008`；`simulationNonEffectiveContractReportsStrategyIneffective` |
| ② 零副作用锚（集成） | `simulationAndTestbenchLeaveCounterAndUsageLogUntouched`（执行前后 counter 值与 usage_log 行数**零变化**） |
| ③ 对照一致性锚（集成） | `simulationMatchesRealExecutionForAllowedAndDeniedPaths`（假想上下文 = 真实状态；含 100/101 与各要素越界；结论 + 触发要素集合逐项一致） |
| ④ 测试台报告（集成 + 结构锚） | `testbenchAllElementsEnabledAllScenariosPass`（五要素齐全：U1~U10 全 PASS + U11 SKIPPED）；`testbenchPartialElementsSkippedForDisabled`；`testbenchEmptyStrategyOnlyU11Pass`；`testbenchNonEffectiveContractAllScenariosStateDenied`；结构锚 `testbenchScenariosCoverEveryCatalogElement`（目录 ↔ 场景覆盖一致）；结构锚 `endpointsExposeNoBypassParameter`（三端点无跳过判定入参） |
| ⑤ 受控执行（集成） | `usageExecutionAllowedThenCountedAndVisibleInSummary`；`usageExecutionQuotaExhaustedRejectedWithDeniedLog`；`usageExecutionTerminatedContractRejectedC0013`；`usageExecutionNonParticipantRejectedWithEnumerationSafeCode`（与"不存在"同码同文）；`usageExecutionGovernanceRoleCannotExecute`（治理不发起使用） |
| 回归 | 既有 **196 例零回归**（含 R12 可见性矩阵、t11~t23、结构锚、真并发用例）+ `checkstyle:check` **0 违规**；contract 行覆盖保持 ≥80%（预期 ≥93%，以编码段实测为准） |

**门禁与基准**：contract 模块 + 全仓回归 + checkstyle（**零门禁配置改动**，红线 3）；三端点无真实调用链路压力面 → AGENTS §4 基准义务**本卡不触发**（登记，3.4.8 联调期再评估）。

## 10. ADR-021《策略模拟器与测试台契约》大纲（随编码批落稿；编号沿 ADR-020 顺延）

1. 背景与目的（规格规则 4 双向用例硬约束 / 尾注未定义项"模拟器形态"兑现 / 3.4.5 移交-2 演示入口缺位）；
2. 决策内容：2.1 形态与落点（同宿主三端点）；2.2 **双通道语义**（模拟 vs 执行：副作用 / 计数来源 / 日期来源 / 策略来源 / 拒绝表达）；2.3 假想上下文口径与边界值；2.4 **配额两腿同源**（限值来源 + 耗尽判据 + 对照一致性锚）；2.5 场景集与要素目录同源（结构锚）；2.6 报告形态与**零落库**理由；2.7 可见性与权限（参与方 / 参与方+治理 / 治理不发起使用）；2.8 **诚实边界**（模拟结论非放行承诺 / 受控执行端点 = 演示期形态 / 网关未上线前仅应用层 / 线下扩散不承诺）；2.9 迁移与退役触发条件（C-5.x 接入后 3.4.8 复核；引擎独立部署时按 ADR-019 §2.3 / ADR-020 §2.1 抽 common）；
3. 理由；4. 备选方案与取舍（独立模拟器服务 / 纯 harness / 模拟不做配额腿 / 另建判定类 / 自定义场景 / 报告落库 / 新码位 / 并入 ADR-020）；5. 业务影响说明；6. 影响范围（新增件 / 改动件 / 零改动清单）；7. 可替换性；8. 修订记录。

## 11. 备忘（设计与实现对照义务）

1. **零改动承诺清单**（评审①锚）：`PolicyJudge.judge(...)` / `PolicyExecutionService.check` / `ContractQueryService`（含 QC1）/ `UsagePolicyDslParser` / `UsageControlPolicy` / `PolicyElementCatalog` / 既有 7 控制器 / `ContractErrorCodes` 与 `ContractExceptionHandler` 既有码位 / 迁移 V1~V3 / 空间域 / 目录域 / 门禁配置 / 加解密组件；
2. **改动清单**（三处等价/只增）：`PolicyJudge` 新增 `judgeQuota`；`UsageQueryService` 可见性改调共享守卫（行为零变更）；`SimulationViews` 等新增件；
3. **下游衔接**：3.4.7 界面消费三端点（模拟按钮 / 使用动作入口 / 测试台报告展示 + R12 摘要）；3.4.8 复核受控执行端点 ≡ 交付链判定面 + 覆盖率达标 + 双向用例脚本级固化；3.7.x 对账仍以 V3 两表为源（模拟与测试台不产生流水）；
4. **剧本同步建议**（跟踪-12）：`C-4.3` **建议无需改动**（演示前提⑤"技术侧以受控调用模拟"由受控执行端点正式承载；S2/S3 步骤、判定与附录 B 预置值均不变；界面入口占位核对仍归 3.4.7）——业务语言文本随交付说明给 PO 裁决，本卡不擅自改剧本；
5. **有意不做**：模拟器独立服务 / 通用规则引擎与表达式求值 / 自定义场景集 / 报告落库 / 放行凭证与令牌 / 请求级幂等（ADR-020 §2.7 沿续）/ 界面 / 网络级控制 / 多渠道交付模拟；
6. **诚实边界三写**：模拟结论 ≠ 放行承诺；受控执行端点 = 演示期形态；网关未上线前不承诺网络级、线下扩散不承诺。

---

## 确认记录

> **确认留痕（2026-10-08，编排师会话回复"Q1~Q7 + D1 均按建议"）**：**Q1~Q7 均采建议口径 A + D1 不拆分**——本文件转 **V1.0（编码契约）**（实现与设计逐条一致，评审①锚）；`docs/designs/WBS-3.4.6-lofi.md` 同批转 **V1.0（方向定稿）**（确认记录亦落其末节）；随后进入编码段（测试先行，新会话冷启动——第一动作 = 模拟判定矩阵单测 + 三端点契约测试先红，红相即时归档 `build-output/w346-red-phase-*.txt`〔3.4.4 教训-1〕）。V0.9 草案随立卡批 `66297f9` 落盘。
