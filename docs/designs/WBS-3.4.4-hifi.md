# WBS-3.4.4 高保真设计（hifi）：策略 DSL 定义与解析器

> **版本**：V0.9（草案，随立卡批落盘）｜ 落点任务卡：`docs/tasks/WBS-3.4.4-策略DSL定义与解析器-2026-10-06.md` ｜ 上游设计：lofi V0.9（同批）；3.4.3 hifi §6.3（承载现状）/ §10-7（衔接义务）
> **确认状态**：待编排师一次确认（Q1~Q5 + D1）；确认后本文件与 lofi 同批转 **V1.0（编码契约）**
> **本卡性质**：零 DDL / 零跨服务改动 / 零新依赖 / 零门禁配置改动 / 零加解密改动——纯 contract-service 包内新增与收敛

---

## 1. 模块骨架与落位（既有 `contract-service` 宿主内新增）

```
com.ctds.contract.domain.policy        ← 新包（包名踏勘空闲）
  ├─ UsagePolicyDsl          DSL 版本与文档字段名契约常量（dslVersion "1.0" + 字段名常量）
  ├─ PolicyElementCatalog    策略要素目录注册表（要素集合与元数据权威定义点）
  └─ UsagePolicyDslParser    解析器（校验规则单点）
com.ctds.contract.domain
  └─ UsageControlPolicy      ← 收敛改造：三件校验迁出；保留纯数据 + 序列化
```

- 既有类改动 = `UsageControlPolicy`（校验方法移除）+ `ClauseValues`（结构校验调用点委托）+ `ContractCommandService`（150/265/548 三处调用点委托）；**其余类零触碰**。
- 目录/解析器均为纯函数式静态组件（沿空间 `PolicyCatalog` 静态注册表先例），无状态、无 I/O、无 Spring 装配——领域层组件，测试直调。

## 2. DSL v1.0 文档规范（策略文档的权威定义）

### 2.1 文档结构（载荷形态沿 3.4.3 固定字段序，兼容零破坏）

```json
{
  "dslVersion": "1.0",              // 可选；缺省 = "1.0"；未知值拒绝（Q4-①）
  "quota":             { "enabled": true,  "maxCount": 100 },
  "term":              { "enabled": true,  "startDate": "2026-10-07", "endDate": "2026-11-05" },
  "purpose":           { "enabled": true,  "text": "风控建模" },
  "territory":         { "enabled": true,  "text": "本市域" },
  "noRedistribution":  { "enabled": true },
  "noRestrictionDeclared": false
}
```

- 字段名与取值形态**与 3.4.3 hifi §6.2 完全一致**（已验收面零变更）；本卡新增的只有 `dslVersion` 一个字段（可选）。
- 禁用要素仅落 `enabled` 布尔（沿 3.4.3 口径）；`noRestrictionDeclared` 为布尔声明位，不是要素（不入目录键集）。

### 2.2 要素目录表（DSL v1.0 五要素权威定稿——规格行为 4 规则 2"字段归 3.4.4"的落点）

| 目录键（对齐空间命名规范） | 文档字段名（兼容层） | 显示名 | 值类型 | 约束 | 判定语义标注（供 3.4.5，本卡只标注不实现） |
| --- | --- | --- | --- | --- | --- |
| `usage.quota` | `quota.maxCount` | 使用次数上限 | integer | ≥ 1 | 使用次数上限；计数口径（按调用/按交付）= 3.4.5 未定义项 |
| `usage.term` | `term.startDate` / `term.endDate` | 使用期限 | date-range（ISO 本地日期） | 起止有序；**起始日 ≥ 提交日**（Q4-④） | 期限内有效；相对期限（自生效起 N 天）自动换算 = 3.4.5 未定义项，演示载荷由技术侧按绝对日期构造 |
| `usage.purpose` | `purpose.text` | 用途限定 | text | trim 后非空 | 精确等值匹配（约定外用途拒绝）；词表化/多值 = 3.4.5 未定义项 |
| `usage.territory` | `territory.text` | 域内使用 | text | trim 后非空 | 精确等值匹配（域外拒绝）；地域编码化 = 3.4.5 未定义项 |
| `usage.no_redistribution` | `noRedistribution.enabled` | 禁止再分发 | boolean | `enabled=true` 即禁止 | 再分发动作（转授/转售/对外提供）拦截 |

> **两层命名分离**：目录键 = 对齐空间"域.名词小写点分"规范的模型层标识（治理/渲染/语义标注用）；文档字段名 = 载荷兼容层（3.4.3 已验收固定字段序，**不动**）。映射表如上，ADR-019 留痕。**"至少一项启用或显式声明"门槛与互斥校验**作用于文档层字段（见 §4 规则表 R6/R7）。

## 3. `PolicyElementCatalog` 契约（要素目录注册表）

```java
public final class PolicyElementCatalog {
    public record ElementDefinition(String key, String field, String displayName,
            String valueType, String constraint, String judgmentSemantics) { }
    public static Optional<ElementDefinition> find(String key);      // 按目录键取定义
    public static Optional<ElementDefinition> findByField(String field); // 按文档字段名取定义
    public static boolean isDefined(String key);                     // 目录封闭性判定
    public static Set<String> keys();                                // 已注册键集（声明序，稳定排序）
    public static Set<String> fields();                              // 文档字段名集（解析器封闭性判定用）
}
```

- **权威定义点**：五要素在此注册（内容 = §2.2 表逐行）；扩要素 = 注册表追加一行登记（沿空间 PolicyCatalog"扩目录 = 注册表追加行"先例）。
- **同构对齐契约**（Q2-A，对照 `com.ctds.space.domain.PolicyCatalog`）：单点权威注册表 / 键命名规范一致 / 封闭值域（空间 = 封闭枚举+严格度，本域 = 强类型+约束说明）/ 注册表追加式扩展——同构不共码；互不漂移以 **ADR-019 + 4 视角评审①视角对照检查**为锚。
- 解析器对文档内**未知要素键**的拒绝以 `fields()` 封闭集为据（封闭性由目录承载，校验规则由解析器承载——分工见 §4 备忘）。

## 4. `UsagePolicyDslParser` 契约（校验规则单点）

### 4.1 API

```java
public final class UsagePolicyDslParser {
    /** 提交路径：严格解析（版本门槛 / 未知键 / 结构 / 取值 / 互斥 / 规范化 / 时点全查）。 */
    public static ParseResult parse(final JsonNode document);
    /** 读路径：容忍解析（密文回读 / 引擎判定——缺省版本 = 1.0，缺字段 = 禁用；不做提交时点校验）。 */
    public static UsageControlPolicy parseTolerant(final JsonNode document);
    /** 确认锁定门槛（行为 4 规则 2——3.4.3 hasAnyRestrictionOrDeclared 迁入单点）。 */
    public static boolean satisfiesConfirmGate(final UsageControlPolicy policy);
    public record ParseResult(UsageControlPolicy policy, List<String> violations) { }  // violations 空 = 合法
}
```

### 4.2 校验规则表（全部拒绝 → 1008C0015，明细入服务端日志沿 C0004/C0015 先例；提交路径）

| # | 规则 | 口径 | 来源 |
| --- | --- | --- | --- |
| R1 | 文档形态 | 策略节点若非 null 须为 JSON 对象 | 3.4.3 shapeViolations 迁入 |
| R2 | 字段类型 | `noRestrictionDeclared` 须布尔；各要素须为含 `enabled` 布尔的对象；未知要素字段名拒绝（`fields()` 封闭集） | 3.4.3 迁入 + 目录封闭性（新） |
| R3 | 版本门槛 | `dslVersion` 若存在须为字符串且 = "1.0"；**缺省容忍 = "1.0"；未知值拒绝（fail-closed）** | 新增（Q4-①） |
| R4 | 次数取值 | `quota.enabled` 时 `maxCount` 须为 ≥1 整数 | 3.4.3 迁入 |
| R5 | 期限取值 | `term.enabled` 时起止须为 YYYY-MM-DD 字符串、起止有序、**起始日 ≥ 提交日** | 3.4.3 迁入 + 时点（新，Q4-④） |
| R6 | 文本取值 | `purpose`/`territory` 启用时 text trim 后非空（**规范化后落模**——存储与判定用 trim 后值） | 3.4.3 迁入 + trim（新，Q4-③） |
| R7 | 互斥 | `noRestrictionDeclared = true` 时五要素须全禁用（"无使用限制"与任一限制并存 = 语义矛盾） | 新增（Q4-②） |
| R8 | 确认门槛 | 至少一项要素启用 **或** 显式声明（确认锁定时点，W7 应用服务单点调用） | 3.4.3 hasAnyRestrictionOrDeclared 迁入 |

> 读路径（`parseTolerant`）仅做 R1/R2 的容忍形态（缺字段 = 禁用、缺版本 = 1.0），**不做** R5 时点/R7 互斥等提交时点校验——存量数据不回溯拒绝；`fromJson` 容忍读语义由解析器接管后，值对象序列化方法保留但校验职责清零。

### 4.3 调用点收敛清单（"禁止两处并行定义"兑现动作）

| 调用点 | 现状（3.4.3） | 收敛后 |
| --- | --- | --- |
| `ClauseValues` 提交载荷解析（§97~101） | `UsageControlPolicy.shapeViolations(strategyNode)` + `fromJson` | `UsagePolicyDslParser.parse(strategyNode)`（R1~R7 全查；违规清单并入槽位校验违规清单 → C0014/C0015 现有分流不变） |
| `ContractCommandService` 发起（150 行） | `strategy().basicValueViolations()` | 随 `parse` 单点全查（发起路径无需二次取值校验——删除重复调用，违规以解析结果为准） |
| `ContractCommandService` 提案（548 行） | 同上 | 同上 |
| `ContractCommandService` 确认门槛（265 行） | `strategy().hasAnyRestrictionOrDeclared()` | `UsagePolicyDslParser.satisfiesConfirmGate(...)`（R8） |
| 密文回读 / QC1 / 视图（`fromJson` 容忍读各处） | `UsageControlPolicy.fromJson` | `UsagePolicyDslParser.parseTolerant`（`fromJson` 可保留为解析器内部实现细节或收敛删除——随编码批定，对外行为零变更） |

- `UsageControlPolicy` 收敛后保留：record 结构 / `toJson`（固定字段序序列化）/ `empty()` / Element 静态工厂；**校验方法全部移除**。全仓 grep 无 `shapeViolations`/`basicValueViolations`/`hasAnyRestrictionOrDeclared` 残留调用 = 收敛完成判据（评审锚）。

## 5. 兼容映射矩阵

| 场景 | 口径 |
| --- | --- |
| 3.4.3 存量合约（版本行密文内策略文档无 `dslVersion`） | 读路径容忍缺省 = 1.0（`parseTolerant`）；**零迁移、零 DDL** |
| 3.4.3 既有测试夹具 / 剧本走查载荷（无 `dslVersion`） | Q4-A 缺省容忍口径下**零破坏**（B 口径则须补字段——列为 Q4 备选影响） |
| 新写路径（W5 发起 / W6 提案） | `parse` 严格路径全查（R1~R7）；既有合法载荷语义不变（新增拒绝面仅为四类增强） |
| 未知 `dslVersion`（如 "2.0"） | 提交路径拒绝（C0015）；读路径 fail-closed 拒绝解析（不冒充空策略——沿"不冒充"口径） |
| 演示库存量（CO000002~CO000007 等） | 零触碰；下次演示前统一清理（3.3.7 先例沿台账登记） |

## 6. 测试计划（测试先行；先红后绿 = 新增校验规则）

| 面 | 内容 |
| --- | --- |
| 解析器单测（新，`UsagePolicyDslParserTest`） | 合法矩阵：五要素全启用（附录 B 预置值同构）/ 部分启用 / 显式无限制 + 全禁用 / 无策略节点 / 带版本；非法矩阵：R2 未知字段名 / R3 未知版本 / R4 次数 0 与负 / R5 起止倒置与起始早于提交日 / R6 纯空白文本 / R7 互斥矛盾；`satisfiesConfirmGate` 两臂；trim 规范化落模断言 |
| 目录单测（新，`PolicyElementCatalogTest`） | 五要素键集与规格一一对应；键命名规范匹配（`usage.` 前缀点分）；`fields()` 与文档字段名映射一致；封闭性判定 |
| 收敛回归（既有锚**断言零变更**） | 3.4.3 领域单测 12 例中策略两道校验矩阵（T8 族）——调用改为委托后全绿；contract 全模块 + catalog/did/subject 回归 |
| 集成锚（新增少量，入既有生命周期集成测试类或新增策略专项类） | W5 提交非法策略被拒（C0015 + 留痕四要素）/ W7 互斥矛盾确认拒绝 / 存量兼容读探针（无版本密文回读成功）/ trim 后值落库探针 |
| 门禁 | `mvn -B -ntp compile` / `test` / `checkstyle:check`——contract 全绿 + 全仓既有模块回归（Skipped 0 口径） |

## 7. ADR-019《策略 DSL 契约》大纲（随编码批落稿；决策内容 = 本卡 §二 确认口径）

1. 决策：结构化声明式策略 DSL（Q1-A）——理由与文本语法不做的边界；
2. 五要素字段定稿表（hifi §2.2 引用）+ 目录键与文档字段名两层映射；
3. 与空间 `PolicyCatalog` 同构对齐契约（Q2-A）：同构不共码 + 互不漂移评审锚 + 扩要素登记规范；
4. 解析器落点与收敛声明（Q3-A）：校验单点 = `UsagePolicyDslParser`；3.4.5 同宿主消费；**迁移触发条件** = 引擎独立部署时按变更流程抽 common；
5. 版本演进规则（Q4-①）：缺省容忍 1.0 / 未知值 fail-closed / 演进 = 新版本值 + 解析器多版本分支 + 兼容映射批；
6. 未定义项移交：计数口径 / 用途词表化 / 地域编码化 / 相对期限换算 → 3.4.5 设计期；
7. 编号沿 `docs/adr/ADR-018` 之后顺延 = **ADR-019**（现录 ADR-001~018）。

## 8. 配置与部署

零改动（无新配置项、无新 env、无部署清单变更、无迁移）。

## 9. 备忘（设计与实现对照义务）

1. **下游衔接**：3.4.5（引擎消费判定语义标注 + 解析产物；QC1 零改动沿 3.4.3 交付）/ 3.4.6（模拟器消费）/ 3.4.7（界面按目录渲染——要素集合零自持）/ C-4.6（V1.5 草稿载体）；
2. **剧本同步建议**（交付说明给业务语言文本，PO 裁决，本卡不擅自改剧本）：C-4.3 附录 B 预置值补 DSL 形态标注；新判定面（版本/互斥/时点）判定点增补建议；
3. **性能豁免**：解析器在合约写路径（低频业务对象），非网关/检索/计量热路径——AGENTS §4 基准义务不触发（沿 3.4.3 口径）；
4. **密码学零改动**：策略文档随条款值在 SM4 密文列（3.4.3 已落），本卡不新增任何加解密入口（红线 7）；
5. **过度设计有意不做**：元数据驱动的通用校验引擎（五要素静态已知，直写类型化校验 = 最平庸实现）；文本语法表层（无消费方）；dslVersion 多版本分流框架（V1.0 单版本，仅留版本门槛与演进规则）；
6. **错误码**：复用 1008C0015，零新码位；`ContractErrorCodes` 零改动；
7. **门禁与 CI**：零配置改动（红线 3）；本卡零 ADR 正文改动（ADR-019 为新增文件，非既有 ADR 修订）。

---

## 确认记录

> **草案留痕（2026-10-06 18:1x，立卡批）**：V0.9 随立卡批提交；踏勘口径（`UsageControlPolicy` 承载现状与三件校验调用点 150/265/548 / 空间 `PolicyCatalog` 同构对照面 / `domain.policy` 包名空闲 / 1008 段码位现状 / C-4.6 草稿载体定位）已并入正文与 Q1~Q5。**待编排师一次确认（Q1~Q5 + D1）后转 V1.0 编码契约。**
