# WBS-3.4.5 任务卡：策略执行引擎（2026-10-07 立卡）

| 项 | 内容 |
| --- | --- |
| 任务卡 / 需求编号 | **WBS-3.4.5 策略执行引擎**（WBS 行 279；实施规格 `docs/specs/C-4.1-4.3-数字合约与使用控制.md` V1.0 **行为 5：策略执行与绕过拒绝（C-4.3）**——本卡承载面 = 规则 1/2/3/5/6 全部引擎实现；规则 4 双向用例的**剧本级验证**归 3.4.6/3.4.8，本卡以测试矩阵承载等价判定） |
| 上游能力边界 | **3.4.4 交付（已合并 main `aca3aca`）**：`UsagePolicyDslParser` 校验单点（R1~R8）+ **`PolicyElementCatalog` 五要素注册表含判定语义标注**（本卡引擎直接消费的契约面：次数上限 / 期限内 / 用途精确等值 / 域内精确等值 / 再分发禁止）+ `UsageControlPolicy` 纯数据值对象 + ADR-019《策略 DSL 契约》（§5 迁移触发条件：引擎独立部署时按变更流程抽 common）；**3.4.3 交付**：**QC1 `ContractQueryService.loadEffectiveStrategy(contractNo)`**（返回 `ContractStrategySnapshot{contractNo, status, effectiveAt, strategy}`——生效中 = 策略 + 生效时间；未生效 = 空策略；已终止/已完结 = 状态可判，引擎侧失效判定归本卡）+ contract-service 宿主（8085/`ctds_contract`，迁移 V1/V2 已落，下一迁移 = **V3**）+ 留痕与错误码体系（1008 段 C0001~C0019 + S0001~S0003 已占用，**下一空位 = C0020**）+ `contract_action_log` 治理动作留痕先例；**下游均未开工**：C-5.x 交付链（策略执行落点）/ 3.5.2 网关（网络级控制，规格 §6-1 诚实边界）/ 3.4.6 模拟器 / 3.7.x 计费（按次配额对账）/ 3.8.3 存证埋点；**common 组件**：鉴权 / 错误码 / 国密（本卡**零加解密改动**——执行记录不含条款原文与敏感文本）/ 幂等；**`Clock` 注入先例**（DB-09/DB-22 两把钟教训——判定用注入时钟，不直取 `LocalDate.now()`）；本卡既有服务改动 = **零**（仅 contract-service 包内新增；零跨服务、零新依赖、零门禁配置改动） |
| 交付物 | ① **`PolicyExecutionService` 判定入口**（应用层方法，同宿主直调沿 QC1 先例）：`check(contractNo, UsageRequest)` → 放行（含使用计数）或拒绝（含触发要素明细 + 拒绝留痕）；判定顺序 = 合约状态门槛〔QC1 消费，未生效/终止/完结 → 策略失效拦截〕→ 空策略/显式无限制放行不计数 → 期限/用途/域/再分发**全查明细**（拒绝零副作用）→ 配额**判检一体原子递增**（放行才计数）；② **迁移 V3 两表**：`contract_usage_counter`（uk contract_no + used_count，原子递增）+ `contract_usage_log`（放行/拒绝统一执行记录：谁/何时/哪份合约/动作类型/触发要素/结果——留痕四要素口径，不含请求原文）；③ **R12 读端点**：本合约使用摘要（计数 + 放行/拒绝统计 + 记录分页；参与方 + 治理可见，沿 R6~R11 防枚举口径）——规格行为 5 规则 5"双方可查执行记录摘要"承载；④ **错误码 1008C0020**（POLICY_USAGE_DENIED，五类拦截统一一码，触发要素入留痕与日志，HTTP 403）+ 状态失效拦截复用 1008C0013；⑤ **ADR-020《策略执行引擎契约》**（判定顺序 / 计数口径 / 五类拦截语义 / 集成面契约〔供 3.5.2/C-5.x 接入〕/ 诚实边界——随编码批落稿）；⑥ 测试（判定矩阵单测〔五要素 × 生效/越界**双向** + 边界：第 N/N+1 次、期限首末日、显式无限制不计数〕+ 真并发配额用例〔判检一体防超卖，沿 3.4.3 真并发先例〕+ 全链集成〔生效→放行计数可查→耗尽拒绝留痕 / 终止→失效 S3-7 / 再分发拦截 / R12 权限矩阵〕+ 既有 158 例零回归）；⑦ 本任务卡 + lofi/hifi + 台账与日志 |
| 关闭条件 | ① 两级设计经编排师**一次确认**（lofi/hifi 确认记录签署，实现与设计逐条一致）；② 本地门禁全绿（contract 模块 + 全仓回归 + checkstyle 0）；③ 规格行为 5 六条验收标准（100 次放行/101 次拒绝、期限、用途、域、再分发 + 计数可查）→ 引擎/测试映射表齐备（本卡 §三）；④ **承接 3.4.4 移交-1 + 3.4.3 移交-5 兑现**：引擎消费 `PolicyElementCatalog` 判定语义标注 + QC1 消费 + 终止/完结 → 策略失效判定 + 四项未定义项落定（计数口径 Q3 / 用途·域·相对期限 Q5）；⑤ 五类拦截**双向判定**（生效放行 + 绕过被拒）测试全绿——规格规则 4 硬约束在本卡的等价承载（剧本级双向用例归 3.4.6/3.4.8，剧本同步建议随交付说明登记）；⑥ 4 视角评审通过 + 修复批复审；⑦ 编排师验收通过 |
| 分支 | `feat/C-4.3-策略执行引擎`（自 `main` = `148c8d3`〔3.4.4 合并段终章提交，代码树与 `aca3aca` 相同——评审①勘误；`main == origin/main` 零差异〕，沿同域命名先例——本包实施规格 C-4.3 行为 5） |
| 纪律声明 | **只做策略执行引擎**（判定入口 / 配额计数 / 期限控制 / 用途·域校验 / 再分发拦截 / 执行记录与摘要读面）；**"与网关集成"的 V1.0 落地口径 = 应用层判定入口 + 集成契约登记（ADR-020）**——网关本体归 3.5.2、交付链归 C-5.x，本卡**零网关代码、零跨服务改动、零新 HTTP 内部端点**（消费方未开工，不提前暴露）；模拟器与测试台归 3.4.6；策略配置界面归 3.4.7；剧本级双向用例与覆盖率达标归 3.4.6/3.4.8（本卡测试矩阵承载等价判定）；计费对账归 3.7.x；存证埋点归 3.8.3；**执行记录不含条款原文与请求文本原文**（留痕四要素纪律，规避 L3 分级纠缠）；**判定与计数用注入 `Clock`**（两把钟教训）；**DDL = 仅 V3 两表**；**零新依赖**；**零门禁配置改动**；**错误码仅新占 C0020 一位**（状态失效复用 C0013）；**禁止两处并行定义**（判定语义唯一权威 = `PolicyElementCatalog`，引擎只消费不复制）；既有合约域代码**仅新增不改写**（QC1/解析器/值对象零改动——上游已验收面零回归）；发现规格缺口一律走变更流程（红线 2/章程 2.6）；WBS 预算 **2 会话**（编码段 1 + 评审修复与走查段 1） |

---

## 一、范围与设计

- **做什么 / 不做什么**：见 lofi「做什么 / 不做什么」两节与 hifi 判定契约表/判定顺序/计数与并发/测试计划；规格行为 5 规则 1/2/3/5/6 逐条落地，六条验收标准逐条有测试锚；**规格未定义项三项**（执行入口与集成形态、计数口径、记录与摘要形态）随两级设计定稿（lofi §八 Q 清单，编排师确认即生效）。
- **两级设计一并提交**（章程 2.6.3；本卡预估 >1 天〔预算 2 会话〕，但设计面单卡闭合、无合理拆分切面——D1 论证见 lofi §八）：lofi = 方向（引擎落点 / 入口与集成形态 / 计数口径 / 未定义项落定 / Q 清单），hifi = 编码契约（判定入口 API / 判定顺序表 / 五类拦截判定表 / V3 两表 / R12 契约 / 并发与幂等口径 / 测试计划 / ADR-020 大纲）。

**设计要点摘要（供快速表决）**

1. **落点**：contract-service 同宿主（`domain.policy` 引擎件 + `application` 判定服务 + `interfaces` R12 读端点）；**零跨服务改动**；ADR-019 §2.3 迁移触发条件兑现（引擎独立部署时再抽 common）；
2. **入口 = 应用层方法**（Q2-A）：`PolicyExecutionService.check(...)`，沿 QC1 同宿主直调先例；**网关上线前执行结论仅覆盖应用层拦截判定**（规格 §6-1 诚实边界原文）；集成契约（方法签名 + 判定结果对象）落 ADR-020，3.5.2/C-5.x 开工时按契约接入；
3. **计数口径 = 按调用**（Q3-A，移交-1 ①落定）：每次经判定入口的使用尝试，**放行才计数 +1**、拒绝零计数；规格验收标准"第 1~100 次放行、第 101 次拒绝"与按调用天然一致；
4. **配额判检一体**（Q4-A）：单条 `UPDATE ... SET used_count = used_count + 1 WHERE contract_no = ? AND used_count < ?`，影响行数 = 0 即耗尽——数据库单语句原子性防"先查后增"竞态超卖，无应用层锁；
5. **判定顺序**：状态门槛（QC1：未生效/终止/完结 → C0013 失效拦截，S3-7 联动）→ 空策略/显式无限制（放行不计数）→ 期限/用途/域/再分发全查明细（**拒绝零副作用**）→ 全过才配额判检 → 放行递增 + 双方记录；
6. **四项未定义项**（Q5-A，移交-1 ②③④落定）：用途/域维持**精确等值匹配**（3.4.4 判定语义标注口径），词表化/多值/地域编码化**登记不做**（无消费方，C-4.6/V1.5 再议）；相对期限自动换算**登记不做**（演示载荷按绝对日期构造——3.4.4 已定口径）；
7. **记录与读面**（Q7-A）：V3 新表 `contract_usage_log`（放行/拒绝统一执行记录，只记要素码与结果、不含原文）+ R12 摘要端点（参与方 + 治理，防枚举沿 R6~R11）；
8. **ADR-020**：随编码批落稿——判定顺序 / 计数口径 / 五类拦截语义 / 集成面契约 / 诚实边界，决策内容 = 本卡 §二 确认口径。

## 二、待确认决策点（请一次确认；章程 2.6.3）

> 全部 7 项（Q1~Q7）+ D1 全文与备选影响见 **lofi「待确认问题」节**；下表为摘要，**确认后 lofi/hifi 确认记录签署、进入编码**。
>
> **✅ 确认留痕（2026-10-07，编排师会话回复"都按建议"）**：**Q1~Q7 均采建议口径 A + D1 不拆分**——lofi/hifi 同批转 **V1.0（编码契约）**，确认记录签署落两文件末节；本表全部建议口径生效，进入编码段（测试先行，新会话冷启动）。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 引擎落点 | **A**：**contract-service 同宿主**（`domain.policy` 引擎件 + `application` 判定服务 + `interfaces` R12）——沿 QC1/解析器同宿主先例；ADR-019 §2.3 迁移触发条件（引擎独立部署 → 抽 common）兑现 | 备选 B：独立 policy-engine 新服务——WBS 行 279 未要求新服务，演示期无跨服务消费方，新增服务 = 部署/端口/门禁面全面膨胀；C：内嵌网关进程——3.5.2 未开工，无宿主可落 |
| **Q2** | 执行入口与"与网关集成"口径 | **A**：**应用层判定入口**（无新 HTTP 端点）+ 集成契约登记 ADR-020——消费方（C-5.x/3.5.2）均未开工，端点提前暴露无真实调用方；**网关上线前执行结论仅覆盖应用层拦截**（规格 §6-1 诚实边界原文）；服务端强制（规格行为 5 规则 3"直接调用接口同样被拦截"= 判定入口在服务端唯一权威） | 备选 B：现在即建 HTTP 内部端点（沿 catalog internal 先例）——无消费方，属提前暴露；契约未经验证先固化接口形态风险高；C：只做引擎库不接入口——无法演示、无法验证闭环（违反"可演示"原则） |
| **Q3** | 配额计数口径（**移交-1 ①落定**，规格行为 5 规则 6） | **A**：**按调用计数**——每次经判定入口的使用尝试，放行才 +1、拒绝零计数；与规格验收标准"第 1~100 次合规使用放行、第 101 次拒绝"天然一致；WBS 行 279"调用拦截、配额计数"同链路；按交付口径在交付链（C-5.x）开工前无法验证 | 备选 B：按交付计数（每次数据交付行为）——交付形态未定，口径悬空；C-5.x 开工后如需切换走变更流程（ADR-020 登记该触发条件） |
| **Q4** | 计数存储与并发 | **A**：**V3 新表 `contract_usage_counter` + 判检一体原子递增**（单条 UPDATE 带配额条件，影响行数 = 0 即耗尽拒绝）——数据库单语句原子性天然防 100/101 并发交错超卖，无应用层锁、无 SELECT FOR UPDATE 热路径持锁 | 备选 B：SELECT FOR UPDATE 行锁（沿 3.4.3 复判先例）——可行但使用判定是准热路径，判检一体更简洁；C：common 分布式锁组件——单库单实例演示期无收益，锁粒度大于必要 |
| **Q5** | 用途词表化/多值、地域编码化、相对期限换算（**移交-1 ②③④落定**） | **A**：**三项均登记不做**——用途/域维持 3.4.4 判定语义标注的**精确等值匹配**（单值文本；词表化/多值无上游词表权威、无消费方，C-4.6/V1.5 出现需求再走变更）；相对期限不做自动换算（3.4.4 已定演示载荷按绝对日期构造；"自生效起 N 天"依赖生效时点语义，无真实载荷） | 备选 B：本卡实现词表化/多值——无消费方即超范围实现（违反最小实现原则）；C：仅做相对期限换算——同一无载荷理由，不做 |
| **Q6** | 错误码 | **A**：**新占 1008C0020**（POLICY_USAGE_DENIED，HTTP 403）承载**五类拦截**（触发要素明细入拒绝留痕与服务端日志——沿"常量文案 + 明细入日志"先例）；**合约状态失效拦截复用 1008C0013**（未生效/终止/完结 = "合约当前状态不允许该操作"同语义域） | 备选 B：五类各占一码（C0020~C0024）——码位膨胀，触发要素留痕已承载区分度；C：全复用 C0013——执行拒绝与状态门槛语义域不同，混用削弱排障与对账（3.7.x 按次对账需识别拦截类） |
| **Q7** | 执行记录与摘要读面 | **A**：**V3 新表 `contract_usage_log`**（放行/拒绝统一执行记录：谁/何时/哪份合约/动作类型〔使用/再分发〕/触发要素/结果——规格行为 5 规则 5 留痕四要素口径，**不含条款原文与请求文本**）+ **R12 摘要端点**（本合约：已用次数/上限 + 放行/拒绝计数 + 记录分页；参与方 + 治理可见，防枚举沿 R6~R11 同码同文口径） | 备选 B：复用 `contract_action_log`（治理动作留痕）——生命周期动作与使用执行记录是两类业务对象，字段语义混载、查询面互相污染；C：只存计数不存记录——规则 5"执行记录摘要双方可查"无承载，拒绝留痕四要素缺腿 |
| **D1** | 体量 / 是否拆分 | **A**：不拆分（预估 ~1600~2000 行：引擎 ~350 + V3 两表与仓储 ~250 + R12 ~120 + 测试 ~800 + ADR-020 ~150 + 文档）；"判定-计数-记录-读面"四件互相为证，拆分产生"引擎无计数可判 / 计数无记录可查 / 记录无读面可验"中间态；WBS 预算 2 会话 = 编码段 1 + 评审修复与走查段 1（沿 3.4.x D1 先例） | 拆分方案无合理切面（判定与计数同语句、记录与判定同事务语义） |

## 三、规格行为 → 引擎/测试映射表（草案，随 hifi 定稿）

**行为 5 策略执行与绕过拒绝（C-4.3）——本卡承载面（规则 1/2/3/5/6；规则 4 双向用例的剧本级验证归 3.4.6/3.4.8，本卡以测试矩阵承载等价判定）**

| 规则 | 承载 | 关键测试（实测锚——回填明细见表后"实测锚回填"块） |
| --- | --- | --- |
| 规则 1 执行点（调用链路上执行，归 3.4.5） | `PolicyExecutionService.check` 应用层判定入口（Q1/Q2-A）；ADR-020 集成契约供 3.5.2/C-5.x | `checkEntryIsApplicationLayerMethod`（结构锚：无新 HTTP 端点、判定在服务端）；集成锚 `t11_effectiveContractUseAllowedAndCounted`（生效合约放行 + 计数可查） |
| 规则 2 五类拦截（一律服务端拒绝 + 拒绝留痕） | 判定顺序表 + 要素判定：次数耗尽（判检一体）/ 期限届满（注入 Clock 日期比较）/ 用途不符（精确等值）/ 域外使用（精确等值）/ 再分发动作（actionType = 再分发 + noRedistribution.enabled）→ 统一 C0020 + `contract_usage_log` 拒绝留痕（含触发要素） | 五类各"生效放行 + 越界拒绝"**双向锚**：`t12_quotaExhaustedAtLimitPlusOne`（N 次放行 + 第 N+1 次拒绝且留痕含触发要素——验收标准 1 直译）/ `t13_termBoundary`（期限内放行 + 期外拒绝）/ `t14_purposeMismatch` / `t15_territoryMismatch` / `t16_redistributionBlocked`（再分发动作拒绝 + 留痕——验收标准 5） |
| 规则 3 服务端强制（直接调用同样被拦截） | 判定唯一权威 = 服务端引擎（无客户端判定路径）；界面提示不构成执行 | 结构锚 `noClientSideJudgmentPath`（判定逻辑仅在 contract-service）；集成锚：绕过尝试（越界参数直调判定入口）同样被拒——`t12`~`t16` 拒绝腿即承载 |
| 规则 4 双向用例（策略生效 + 绕过被拒） | **本卡测试矩阵承载等价判定**（五类 × 双向，见规则 2 行）；剧本级双向用例归 3.4.6/3.4.8 | 映射表每要素双向锚齐备（规则 2 行 + hifi §6 测试计划）；剧本同步建议随交付说明登记（本卡不擅自改剧本） |
| 规则 5 执行与拒绝记录（放行产生使用计数，双方可查摘要；拒绝留痕四要素） | V3 `contract_usage_log` + `contract_usage_counter` + R12 摘要端点（参与方 + 治理，防枚举） | `t11`（放行后摘要可查：计数 + 放行数）；`t17_usageSummaryVisibility`（参与方过 / 治理过 / 非参与方防枚举 404 同形——沿 R6~R11 口径）；拒绝留痕四要素断言（谁/何时/合约/要素/结果） |
| 规则 6 计数口径随本卡设计落定 | **Q3-A 按调用计数**（放行才计数；ADR-020 登记） | `t12` 计数语义断言（第 N 次放行后 used_count = N；拒绝腿 used_count 不变）；`t18_rejectedAttemptDoesNotConsumeQuota`（用途不符拒绝不烧次数） |
| QC1 消费 + 终止/完结 → 策略失效（**移交-5 承接**，剧本 C-4.3 S3-7 联动） | 判定第一步消费 `loadEffectiveStrategy`：未生效/已终止/已完结 → C0013 拦截（策略同步失效） | `t19_terminatedContractStrategyIneffective`（强制终止后使用 → 拒绝 C0013 + 留痕）；`t20_notYetEffectiveContractRejected`（未生效合约 → 拒绝） |
| 四项未定义项落定（**移交-1 承接**） | Q3（计数口径）+ Q5（用途/域精确等值、相对期限不做）——ADR-020 登记闭环 | 等值判定锚（`t14`/`t15` 含 trim 后等值比较）；不做项 = 登记断言（设计文档 + ADR-020 落笔，无对应实现面） |

> **实测锚回填（编码段 2026-10-07）**：本卡新增 **37 例**（`PolicyJudgeTest` 10 + `PolicyExecutionServiceTest` 12 + `PolicyEngineStructureTest` 2 + `ContractPolicyExecutionIntegrationTest` 13），contract 模块合计 **195 例 0 失败 0 跳过**、行覆盖率 **93.45%**、checkstyle **0 违规**。计划锚 → 实测锚对应：
>
> | 计划锚（立卡草案） | 实测锚（测试方法 / 文件） |
> | --- | --- |
> | `checkEntryIsApplicationLayerMethod` | `PolicyEngineStructureTest.checkEntryIsApplicationLayerMethodWithoutHttpEndpoint`（非控制器 + 零 HTTP 映射 + `check` 入口签名） |
> | `noClientSideJudgmentPath` | `PolicyEngineStructureTest.judgeIsStatelessPureFunctionWithoutIo`（`PolicyJudge` final + 零字段 + 静态纯函数 = 判定单点在服务端） |
> | `t11_effectiveContractUseAllowedAndCounted` | `ContractPolicyExecutionIntegrationTest.t11_effectiveContractUseAllowedCountedAndVisibleInSummary`（第 1~100 次放行 + 计数 + R12 摘要 + 记录行零请求原文） |
> | `t12_quotaExhaustedAtLimitPlusOne` | 同 `t11_...` 第 101 次拒绝腿（C0020 + `QUOTA_EXHAUSTED` 留痕 + 计数不变）+ `t22_concurrentQuotaLimitPlusOneExactlyOneDenied` |
> | `t13_termBoundary` | `t13_termWithinRangeAllowedOutsideRangeRejectedWithTermExpired`（期内放行 / 未到起始日拒绝）+ `PolicyJudgeTest.termWithinRangeIncludingBothBoundaryDaysPasses` · `termOutsideRangeOnDayBeforeStartOrAfterEndTriggersTermExpired`（首末日边界） |
> | `t14_purposeMismatch` | `t14_purposeMismatchRejectedAndAgreedPurposeAllowed` + `PolicyJudgeTest.purposeExactMatchPassesAndMismatchOrAbsentTriggers` · `purposeComparisonTrimsRequestSideAndIsCaseSensitive` |
> | `t15_territoryMismatch` | `t15_territoryMismatchRejectedAndInTerritoryAllowed` + `PolicyJudgeTest.territoryExactMatchPassesAndMismatchOrAbsentTriggers` · `territoryComparisonTrimsRequestSide` |
> | `t16_redistributionBlocked` | `t16_redistributionBlockedWithDeniedLogAndUseAllowed` + `t16_allFiveElementsEnabledContractDecidesByEachElement`（多要素全查明细）+ `PolicyJudgeTest.fullScanReportsAllTriggeredViolationsInJudgmentOrder` |
> | `t17_usageSummaryVisibility` | `t17_usageSummaryVisibilityMatrix`（参与方双方过 / 治理过 / 非参与方与不存在同码同文 + DENIED_ACCESS 留痕） |
> | `t18_rejectedAttemptDoesNotConsumeQuota` | `t18_rejectedAttemptDoesNotConsumeQuota`（拒绝后计数 0 → 正确用途连续放行） |
> | `t19_terminatedContractStrategyIneffective` | `t19_terminatedContractStrategyIneffective`（终止 → C0013 + 留痕 + 记录仍可查、quota 视图转 null） |
> | `t20_notYetEffectiveContractRejected` | `t20_notYetEffectiveContractRejected` |
> | （空策略 / 显式无限制放行不计数） | `PolicyExecutionServiceTest.emptyStrategyAllowedWithoutCounting` · `explicitNoRestrictionDeclarationAllowedWithoutCounting` + `t21_explicitNoRestrictionDeclarationAllowedWithoutCounting`（零计数行） |
> | （编排层门槛 / 耗尽 / 偏移钟） | `PolicyExecutionServiceTest.notExistingContractThrowsC0012WithoutAnySideEffect` · `notYetEffectiveContractThrowsC0013WithDeniedLog` · `terminated-` · `completed-` · `purposeMismatchThrowsC0020WithViolationAndWithoutTouchingCounter` · `quotaExhaustedThrowsC0020WithQuotaExhaustedViolationAndCurrentCount` · `offsetClockAcrossTermBoundaryDecidesByInjectedDate` |
> | （禁用要素不参与判定） | `PolicyJudgeTest.disabledElementsDoNotParticipateInJudgment` · `redistributionNotBlockedWhenDisabledOrActionIsUse` |
>
> **C-4.3 剧本判定面承载核对**：S1 幕（策略配置与生效）判定面 3.4.3/3.4.4 已闭环（本卡零触碰，回归保护）；S2/S3 幕（策略生效正向 / 绕过被拒）判定面 = 本卡引擎承载**判定能力**、3.4.6 模拟器承载**演示入口**、3.4.8 承载**测试与联调集成**——本卡交付后 S2/S3 的服务端判定链路即具备，剧本演示待 3.4.6 界面/入口交付后执行；S3-7（强制终止 → 策略失效）= 本卡 `t19` 承载。规格行为 5 六条验收标准全覆盖（映射见规则 2/5/6 行）。

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | 🟡 **评审修复批完成（2026-10-07 23:5x）**——评审循环 1 处置裁定"都按建议"（2026-10-07 22:3x 到达）→ 修复批闭环（P1×1 + 随批 11 项 + 登记 5 项，见"评审修复批"行）；**contract 196 例 0 失败 0 错误 0 跳过（基线 195 + t23 新增 1）+ 行覆盖 93.50%（1871/2001）+ checkstyle 0 + 全仓门禁复跑 GREEN**（`gate-report-20261007-231854.md`：PASS=14 / FAIL=0 / SKIP=2 / ERROR=0 / PENDING=6，ExitCode 0；coverage overall 94.21% / sast 0）；**下一步 = 独立复审（另会/只读子智能体）→ 编排师验收**（"验收通过" ≠ 合并授权）。**（历史留痕·评审循环 1）**🔴 ①②③ PASS、④ 测试质量 FAIL（1×P1 + 1×P2）→ 合并判定 = 打回。**（历史留痕·编码段 2026-10-07）**编码段完成——测试先行（红 22 例 → 绿）→ 绿相实现（V3 两表 + `domain.policy` 六件 + 判定服务 + 两仓储 + R12 读面 + 错误码 C0020）→ 并发两口径 + 超卖红相验证 → 全链集成 13 例 + 结构锚 2 例；**contract 195 例 0 失败 0 跳过、行覆盖 93.45%、checkstyle 0**；零改动承诺 diff 为空；ADR-020 + 分级规范 §6.1 回写随批；**全仓门禁复跑 GREEN**（`scripts/gates/reports/gate-report-20261007-184650.md`：PASS=14 / FAIL=0 / SKIP=2 / ERROR=0 / PENDING=6，ExitCode 0；coverage overall line 94.2%〔7870/8355〕、core〔auth 92.02% / crypto 89.74% / did 96.22%〕、mutation 68.18%〔90/132〕、sast 0 findings）——**首跑红灯闭合经**：14:07 后台首跑报告 `-142749` 为 RED（仅 `coverage` 段 `mvn coverage exit 1`，明细仅捕获 stderr 末 5 行 JVM 警告＝度量段完整输出未落盘的运行器证据缺口，见跟踪-15）；根因不可回溯（产物被覆盖），以"同命令隔离复跑 exit 0〔`build-output/w345-coverage-isolated-20261007-1705.log`，14 模块全 SUCCESS / contract 195 例 0 失败〕+ **单发干净全仓复跑 GREEN**"判定**环境性红灯**（沿 3.3.7 口径；隔离复跑产物 provenance 存疑，见跟踪-16，本节结论以本卡 GREEN 报告为准）。**（历史留痕·确认段 2026-10-07）**——两级设计一次确认闭环（编排师"都按建议" = Q1~Q7 均采建议口径 A + D1 不拆分）；lofi/hifi 转 **V1.0（编码契约）** + 确认记录签署（两文件末节）；确认批提交并**推送 origin**（立卡批 `bef5e0a` + 确认批）；**下一步 = 编码段（测试先行，待新会话冷启动——第一动作 = 判定矩阵单测先红）**。**（2026-10-07 立卡段，历史留痕）**：任务卡 + lofi/hifi V0.9 落盘，待编排师一次确认（Q1~Q7 + D1）；立卡批提交未推送（沿"确认后推送分支"先例）；分支 `feat/C-4.3-策略执行引擎` 自 `aca3aca` |
| 立卡段（本会话） | ① 冷启动读取链：`AGENTS.md` → 最新日志 `-1105`（3.4.4 合并段终章）→ 台账下一包/待编排师行 → 3.4.4 任务卡（移交-1 原文）+ 3.4.4 hifi（要素目录判定语义标注 + ADR-019 大纲）→ 3.4.3 任务卡（移交-5 原文）+ 3.4.3 hifi §2.4（QC1 契约）→ 规格 C-4.1~4.3 V1.0（行为 5 全文 + §4 非目标 + §6 边界声明 1/4 + §7 Q7 + 尾注未定义项）→ contract-service 代码踏勘（`loadEffectiveStrategy:187` / `UsagePolicyDslParser` / 1008 段码位 C0001~C0019+S0001~S0003 / 迁移 V1/V2 现状 → V3 可用 / `ContractActionLog` 留痕先例）；② 交付物：本任务卡 + `docs/designs/WBS-3.4.5-lofi.md`（V0.9）+ `docs/designs/WBS-3.4.5-hifi.md`（V0.9）；③ 台账四处更新（进行中 / 待编排师 / 下一包 / 事件行）；④ 本会话开发日志随批落盘 |

| 编码段（2026-10-07） | ① 冷启动复述续点（编码契约 = hifi V1.0）→ **红相**：引擎件骨架 + 判定矩阵单测（`PolicyJudgeTest` 10 + `PolicyExecutionServiceTest` 12）先红（22 例全失败），红相证据归档 `build-output/w345-red-phase-20261007-1210.txt`（gitignored）；② **绿相实现**：迁移 `V3__create_policy_execution_tables.sql`（两表 DDL 按 hifi §5）+ `domain/policy` 六件（`UsageRequest`/`UsageActionType`/`UsageVerdict`/`PolicyViolation`/`PolicyJudge`/`UsageLogEntry`）+ `PolicyExecutionService`（判定顺序步 1~10）+ `UsageCounterStore`/`JdbcUsageCounterStore` + `UsageLogRepository`/`JdbcUsageLogRepository`（拒绝留痕 REQUIRES_NEW / 放行记录 REQUIRED）+ `UsageQueryService` + `ContractUsageController` + `dto.UsageViews` + 错误码 `1008C0020`（`ContractErrorCodes` 新增 + `ContractExceptionHandler` 映射与状态同步 + 一致性测试计数 22→23）；③ **两处实测缺陷修复**（均被测试捕获，非事后发现）：并发首用 `INSERT IGNORE` 共享锁与条件 UPDATE 排他锁形成 S→X 升级**死锁**（`CannotAcquireLockException`）→ 行初始化改 no-op upsert（排他锁路径，同合约并发串行排队）；`ApiResult` 成功码非 `"OK"` → t17 断言改 HTTP 200 + 数据锚；④ **超卖红相验证**（hifi §9 ②）：临时改"先查后增"→ 上限 5 并发 6 实测放行 6 次（超卖 1），证据 `build-output/w345-concurrency-oversell-20261007-1310.txt`，验证后**立即还原**判检一体实现并复跑全绿；⑤ 验证：`mvn -B -ntp -pl services/contract-service … test jacoco:report checkstyle:check` → 195 例 0 失败 0 跳过 + checkstyle 0 + BUILD SUCCESS + 行覆盖 93.45%（证据 `build-output/w345-final-verify-20261007-1420.txt`）；⑥ **零改动承诺 diff 为空**（QC1/解析器/值对象/要素目录/既有控制器，对基线 `aca3aca`）；⑦ ADR-020《策略执行引擎契约》落稿 + 分级规范 §6.1 回写两行（V3 两表 L1，含与含主体编号先例 L2 的口径提示供评审①复核）；⑧ 全仓门禁复跑单发后台（`build-output/w345-gates-out-20261007-1430.log`） |

| 门禁闭合段（2026-10-07 18:2x） | ① 冷启动复述续点 → 取首跑门禁报告：`gate-report-20261007-142749.md` **RED**（`coverage` 段 `mvn coverage exit 1`；PASS=13 / FAIL=1 / SKIP=1 / ERROR=0 / PENDING=6）；② **根因定位**：度量段（coverage/mutationTest/sast）失败时运行器**不落盘完整输出**（`Invoke-MetricProcess` 仅返回字符串、报告仅取末 5 行；且该 5 行来自 stderr＝JVM 警告，真正原因在 stdout 被截断）→ 原跑输出不可回溯（surefire/jacoco 产物已被后续运行覆盖）；③ **隔离复跑核验**：同命令（jacoco 0.8.12 prepare-agent test report）隔离复跑 exit 0 / BUILD SUCCESS / 14 模块全 SUCCESS / contract 195 例 0 失败（`build-output/w345-coverage-isolated-20261007-1705.log`，17:03→17:11 共 8:44）；④ **单发干净全仓复跑 → GREEN**（`gate-report-20261007-184650.md`：PASS=14 / FAIL=0 / SKIP=2 / ERROR=0 / PENDING=6，ExitCode 0，18:27:15→18:46:50 共 19.6 分钟；coverage overall 94.2%、core〔auth 92.02% / crypto 89.74% / did 96.22%〕、mutation 68.18%、sast 0）；⑤ 复跑前核验**无并发 maven/npm 进程**（仅演示环境 6 个 java 进程，10:43 起，`main` 零触碰）、复跑全程**单发不重试叠加**（3.3.7 教训）；⑥ 两项发现登记跟踪（跟踪-15 运行器证据缺口 / 跟踪-16 无日志隔离复跑产物 provenance）；⑦ 首跑残留 `SKIP=1`→复跑 `SKIP=2` 差异 = 首跑时 `build-output/` 尚无 ≥1MB 产物（保密扫描 oversized 腿），非门禁强度变化（`ConfigSha256` 两次一致 `f7e0aed2…`） |

| 4 视角评审循环 1（2026-10-07 19:0x~21:3x） | ① 单发串行四个独立只读子智能体（新鲜上下文、互不通气、零文件改动；对象 = 编码批 `bf61a59`，基线 `aca3aca`；输入包 = `build-output/w345-review-code.diff` 2004 行 = `git diff aca3aca..bf61a59 -- services/contract-service`，逐字节校验一致 + 规格/hifi/ADR-020/任务卡/门禁报告路径）；② 结论 = **①规格与设计符合性 PASS（1×P2 + 8×P3）/ ②安全与供应链 PASS（1×P2 + 4×P3）/ ③一致性与重复 PASS（2×P2 + 8×P3）/ ④测试质量 FAIL（1×P1 + 1×P2 + 4×P3）**——合并判定 = **打回（循环 1）**；③ 关键独立核验全过：规格行为 5 六条验收标准三级映射（①④ 双重复核零虚锚）/ 独立复算 surefire 195 例 0 失败 0 错误 0 跳过 + jacoco 行覆盖 93.45%（①④ 两方独立求和一致）/ 零改动承诺 git 自行核验为空（①③）/ 依赖与加解密零改动 + secretsScan/sast 实读 0 命中且 22 个新增件确定在扫描面内（②）/ 判定语义消费不复制 + R12 与 R6~R11 防枚举逐字同构（③）/ 红相 22 例与超卖红相证据实读成立、并发测试被证明可失效的承重测试（④）；④ 去重后 findings（处置建议待裁定）：**P1×1** = C0013 状态门槛腿拒绝留痕 `usedCount` 硬编码 0 违背设计口径"DENIED 时 = 当前计数不变值"（步 2 应取 `counterStore.currentCount` + t19 补留痕行断言——①②③④ 四方同指，级别取④）/ **P2×4** = t11"记录行不含请求原文"断言空洞（④）+ 端口 javadoc 仍写 INSERT IGNORE 与实现/ADR 矛盾（①②）+ ADR-019 节号引用错误 7 处（"§5 迁移触发条件"实为 §2.3：ADR-020×3 + hifi×1 + 任务卡×3）（③）+ 可见性守卫与拒绝留痕逐字复制 ContractQueryService（③，沉淀建议）/ **P3 若干** = hifi 文档字面 5~7 处（路由缺 /v1、final 字面、§7-2 事务措辞、§1 骨架漂移、§9 并发锚名未回填）+ javadoc `@link` 指向不存在成员 + 结构锚 HTTP 注解枚举不全 + `quotaOf` 分支补测 + 集成期限用例系统钟构造（两把钟口径）+ t19 断言文案错别字 + 日志消形/ADMIN 常量沉淀/门禁范围（coreModules 含 policy-engine 无同名目录、mutationTest 仅 did）登记项 + 分级 L1/L2 口径提示证据集补充（评审①建议：维持 L1 有先例支撑，若就高封顶 L2、不建议 L3，最终分级复核/PO 裁决）；⑤ 四方均实测工作区干净（`git status` 空）、`main` 零触碰、评审段零代码改动 → 门禁 GREEN `gate-report-20261007-184650.md` 继续有效（修复批产生代码/测试改动后必须重跑）；⑥ **处置裁定已到（2026-10-07 编排师会话回复"都按建议"）** = 建议表 18 项全部按建议：#1/#2/#3/#4/#6/#7/#8/#9/#10/#11/#12/#18 随修复批 + #5 按建议 (b) 登记跟踪（不动已验收面）+ #13/#14/#15/#16 登记（跟踪-18/19/20/21）+ #17 备忘（两端口落 infrastructure = 已确认设计，随 ADR-019 §2.3 迁移触发随迁） |

| 评审修复批（2026-10-07 22:4x~23:5x） | ① **测试先行（红相）**：单测 `terminatedContractThrowsC0013WithDeniedLog` stub 历史用量 3 + 断言留痕计数快照 = 3、集成 t19 补断言留痕行 `used_count = 1` → 两断言精确失败（`expected 3/1 but was 0`，证据 `build-output/w345-fix-red-20261007-2300.txt`，其余 11 例通过）；② **修复 #1（P1）**：`PolicyExecutionService` 步 2 状态门槛腿拒绝留痕改取 `counterStore.currentCount(contractNo)`（"DENIED 时 = 当前计数不变值"口径落地——已终止合约有历史用量时留痕行记真实值）→ 复跑全绿；③ **随批代码/测试项**：#3 端口 javadoc 勘正（INSERT IGNORE → no-op upsert + ADR-020 §2.5 引注）/ #7 javadoc `@link` 改指 `PolicyElementCatalog.ElementDefinition#judgmentSemantics()`（`PolicyJudge`/`PolicyViolation` 两处）/ #8 t19 断言文案"不布"→"零" / #9 结构锚补 Put/Delete/Patch 注解检查 / #2 t11 循环 100 次均携带用途原文 → "记录行零请求原文"断言真实生效 / #10 新增 t23（quota 要素未启用 → 摘要 quota=null——`UsageQueryService.quotaOf` 未覆盖分支补测）/ #11 集成测试注入固定钟（嵌套 `@TestConfiguration` + `@Primary Clock`，期限造数与引擎判定同源同钟消除跨午夜竞态；幂等组件走系统钟不受影响、主代码无时间戳排序比较——经核查零风险）；④ **文档项**：#4 ADR-019 节号勘正 **4 处**（ADR-020 §2.1/§7 + hifi §10-7 + 任务卡 §一/§二；评审报 7 处以实测 4 处为准）+ #6 hifi 九处字面对齐（§1 骨架三处 / §2 final / §3 语义表述 / §6 路由 /v1 / §7-2 事务措辞 / §9 锚名回填）+ 末节修订留痕 + #12 ADR-020 §2.9 补 R5 期限不变式登记 + 修订记录 V1.1 + #18 任务卡分支基线勘误（`148c8d3`）；⑤ **登记**：跟踪-17（可见性守卫沉淀，裁定采 (b)）/ 跟踪-18（日志消形）/ 跟踪-19（ADMIN 常量）/ 跟踪-20（门禁范围）/ 跟踪-21（分级 L1/L2 口径——PO/分级复核裁决）；⑥ **验证**：`mvn -B -ntp -pl services/contract-service org.jacoco:jacoco-maven-plugin:prepare-agent test org.jacoco:jacoco-maven-plugin:report checkstyle:check` → **196 例 0 失败 0 错误 0 跳过 + 行覆盖 93.50%（1871/2001，UsageQueryService 28/29）+ checkstyle 0 + BUILD SUCCESS**（证据 `build-output/w345-fix-verify-20261007-2320.txt`）→ **全仓门禁单发复跑 GREEN**（`gate-report-20261007-231854.md`，ExitCode 0；首启误传 `-RunLabel` 参数立即纠停、无假报告产生，沿正确调用单发重启——运行器参数为自动生成）；门禁后仅任务卡/台账/日志文档回填（沿门禁回填先例，代码与测试零触碰）；⑦ 备忘（评审③ #17）：两端口落 `infrastructure` 包 = 已确认 hifi §1 口径非缺陷，随 ADR-019 §2.3 迁移触发时端口随迁 |

## 五、移交与跟踪义务登记

| 项 | 内容 | 归属 |
| --- | --- | --- |
| 承接-1（来自 3.4.4 移交-1） | 引擎消费 `PolicyElementCatalog` 判定语义标注 + 四项未定义项落定（计数口径 → Q3 / 用途词表化·多值 → Q5 / 地域编码化 → Q5 / 相对期限换算 → Q5）——**本卡核心承接**（关闭条件④） | 本卡 |
| 承接-2（来自 3.4.3 移交-5） | QC1 消费（`loadEffectiveStrategy` 零改动直调）+ 终止/完结 → 策略失效的引擎侧判定（剧本 C-4.3 S3-7 联动）——**本卡核心承接**（关闭条件④） | 本卡 |
| 移交-1 | 集成面消费：3.5.2 网关 / C-5.x 交付链接入 `PolicyExecutionService.check`（契约 = ADR-020 集成面节：方法签名 + 判定结果对象 + 错误码语义 + 诚实边界）；接入口径（同步判定 / 异步埋点 / 网关侧缓存与否）由其设计定 | 3.5.2 / C-5.x（各自设计期） |
| 移交-2 | 模拟器与测试台消费引擎判定入口构造双向用例演示（S2/S3 幕演示入口 + 双向用例硬约束承载面） | 3.4.6 |
| 移交-3 | 按次配额与计费流水对账（`contract_usage_counter`/`contract_usage_log` 为对账数据源；对账断言要求） | 3.7.x（含 3.4.8 协同） |
| 移交-4 | 存证事件埋点消费（"合约"环节执行记录的存证口径对接） | 3.8.3 |
| 移交-5 | 执行记录摘要的界面呈现（策略视图/使用摘要页面入口） | 3.4.7 |
| 跟踪-12 | **剧本同步建议**：C-4.3 S2/S3 幕判定点在引擎交付后服务端链路已具备、演示入口待 3.4.6——随交付说明给业务语言文本，经 PO 批准落笔（本卡不擅自改剧本） | 本卡交付说明（PO 裁决） |
| 跟踪-13 | 3.4.4 沿续跟踪项沿台账登记：跟踪-9（C-4.3 附录 B DSL 形态标注，PO 裁决）/ 跟踪-10 全组（移交-6 界面核对修订归 3.4.7 / 跟踪-3 变更流程深化 / 跟踪-4 O1/O2 / 跟踪-6 换驱动回归清单 / 跟踪-7 client 副本计数 / 跟踪-8 contract SharedContainerGuardTest / 跟踪-11 PIT 报告）/ 复审-1 三项 P3 / 处置-3 两项（#3 日志消形 / #8 边界补测——其中 #8 边界补测若与引擎判定边界重叠，本卡测试矩阵可顺带覆盖，**以登记不夹带为原则**，确有覆盖则随编码段登记）——**均不在本卡范围** | 各归属卡（台账沿续） |
| 跟踪-14 | 演示数据留置沿续：CO000008~10 + 2 条误触留痕（3.4.4 走查留置）+ 本卡走查将新增（生效合约 + 使用计数/记录）——下次演示/走查前统一清理 | 台账沿续登记 |
| 跟踪-15 | **门禁运行器证据缺口（门禁本体，非本卡代码）**：`run-gates.ps1` 度量段（`coverage` / `mutationTest` / `sast`）经 `Invoke-MetricProcess` 只取回输出字符串、失败时不落盘完整日志（对比 maven/npm 段在失败时写 `%TEMP%\ctds-gate-<阶段>-out.log`），报告仅取"末 5 行且 stdout 先于 stderr 拼接"→ 末 5 行恒为 stderr 的 JVM 警告，**红灯原因不进入报告**，人工无法复核"为什么红"（本次 14:27 RED 即因此不可回溯）。修复 = 给度量段补"失败时落盘完整输出 + 报告取 stdout 末尾关键行"——**属门禁变更（红线 3：不绕过/不修改门禁配置，配置与运行器变更须走门禁变更流程留痕）**，本卡**不自行修改**，仅登记+建议 | 门禁增强（独立卡，走门禁变更流程；建议随下次门禁变更一并落地） |
| 跟踪-16 | **无日志隔离复跑产物（流程留痕缺口）**：`build-output/w345-coverage-isolated-20261007-1705.{log,exit}`（17:03→17:11，exit=0）存在于 14:21（`-1415` 日志落盘）之后，但该时段**无任何开发日志/文档记录**（`docs/logs` 与任务卡 mtime 均止于 14:2x）；按会话纪律"未写入日志与仓库文件的信息一律视为不存在"，该产物**不作为交付证据**（本卡门禁结论只认 `gate-report-20261007-184650.md`），仅登记 provenance 存疑；教训 = 长任务诊断过程亦须即时落盘，禁止"跑了不留痕" | 本卡交付说明 + 台账沿续登记 |
| 跟踪-17 | **可见性守卫沉淀（评审③ P2-1，裁定采登记）**：`UsageQueryService` 可见性条件与拒绝留痕（L51-53/78-86）与 `ContractQueryService.requireVisibleForReadWithAdmin`/`denyAccessAndLog`（L240-258）逐字同构（非复用）——行为由 t17 锚定无漂移、错误码/文案/留痕均取共享常量；沉淀 = 抽取应用层共享可见性守卫（放宽既有方法为包内可复用） | contract 收敛卡（随 3.4.6~3.4.8 交付期统一） |
| 跟踪-18 | **日志标识符消形沉淀（评审② P3-4，沿 3.4.4 评审 #3 同车）**：平台无 CR/LF 消形组件，本批新增 3 条 log.warn 占位符写调用方可控标识（contractNo/requesterNo；请求侧自由文本不入日志不入库）——与既有先例一致非本批引入，建议 common-logging 统一标识符校验/消形 | common 沉淀评估（收敛卡/门禁增强卡） |
| 跟踪-19 | **ADMIN_ROLE 常量沉淀（评审③ P3-6）**：`"admin"` 字面量第 4 份副本（`UsageQueryService` 新增，既有 `ContractQueryService`/`ContractCommandService`/`ContractTemplateAppService` 三份沿先例）——建议沉淀合约域单一常量 | contract 收敛卡 |
| 跟踪-20 | **门禁范围观察（评审① P3-9 / ④ P3-6）**：gates-config `coreModules` 含 `policy-engine` 但仓库无同名模块目录（引擎落 contract-service）→ 引擎既非 coverage 核心判定对象也不在 mutationTest.modules（仅 did）——AGENTS §4"核心模块变异杀除率 ≥60%"对策略引擎暂无已配置门禁；本批 contract 行覆盖 93.50% 已超核心阈值 80%，无违规；建议将 contract 判定件纳入核心清单与 PIT 范围 | 门禁增强（独立卡，走门禁变更流程；与跟踪-15 运行器证据缺口同车） |
| 跟踪-21 | **V3 两表分级 L1/L2 口径（评审① P3-6）**：`contract_usage_log` 含发起主体编号落 L1（CAT-01），与同域留痕先例（合约模板留痕/操作留痕 = L3）存在张力——评审①建议：**维持 L1 有真实先例支撑**（合约主表 :148、产品行为留痕 :141）；若就高**封顶 L2**（需补读取侧审计留痕——现读面仅拒绝腿 DENIED_ACCESS 留痕）；**不建议 L3**（L3 触发 SM4 存储加密义务，该表明文列仅主体编号 + 枚举码，无 L3 型文本） | 本卡交付说明（分级复核/PO 裁决，不在编码段私自改设计） |

---

> **边界复述（红线自查）**：本卡不做网关本体与网络级控制（归 3.5.2）、不做数据交付与沙箱执行（归 C-5.x）、不做模拟器/测试台（归 3.4.6）、不做界面（归 3.4.7）、不做覆盖率达标与剧本级双向用例/联调（归 3.4.8）、不做计费对账（归 3.7.x）、不做存证埋点（归 3.8.3）、不做 AI 生成（V1.5）；**策略拦截覆盖平台交付与调用链路内的使用行为**（规格 §6-4 诚实边界——"下载后线下扩散"不在承诺内）；既有合约域代码仅新增不改写（QC1/解析器/值对象零改动）；判定语义唯一权威 = `PolicyElementCatalog`（禁止两处并行定义）；执行记录不含条款原文与请求文本；判定与计数用注入 Clock（两把钟教训）；DDL 仅 V3 两表；零新依赖；零门禁配置改动；零加解密改动；错误码仅新占 C0020；发现规格缺口一律走变更流程（红线 2/章程 2.6）。
