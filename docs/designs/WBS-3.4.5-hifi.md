# WBS-3.4.5 高保真设计（hifi）：策略执行引擎

> **版本**：**V1.0（编码契约，2026-10-07 编排师"都按建议"一次确认）**——**Q1~Q7 均采建议口径 A + D1 不拆分**（确认记录落本文件末节；此前 V0.9 草案随立卡批 `bef5e0a` 落盘）｜ 落点任务卡：`docs/tasks/WBS-3.4.5-策略执行引擎-2026-10-07.md` ｜ 规格：`docs/specs/C-4.1-4.3-数字合约与使用控制.md` V1.0 行为 5
> **确认状态**：✅ 已确认（2026-10-07，编排师"都按建议"）——本文件为编码契约，实现与设计逐条一致（评审①锚）

---

## 1. 包骨架与落点（Q1-A）

```
services/contract-service/src/main/java/com/ctds/contract/
├── domain/policy/
│   ├── UsageRequest.java            // 使用请求值对象（contractNo/requesterNo/actionType/purpose/territory）
│   ├── UsageActionType.java         // 枚举：USE（使用）/ REDISTRIBUTE（再分发动作）
│   ├── UsageVerdict.java            // 判定结果（allowed + 触发要素明细 + 拒绝原因码）
│   ├── PolicyViolation.java         // 触发要素枚举（QUOTA_EXHAUSTED/TERM_EXPIRED/PURPOSE_MISMATCH/TERRITORY_MISMATCH/REDISTRIBUTION_FORBIDDEN）
│   └── PolicyJudge.java             // 判定纯函数（期限/用途/域/再分发——只消费 PolicyElementCatalog 语义与 UsageControlPolicy 值，无 IO）
├── application/
│   ├── PolicyExecutionService.java  // 判定入口（编排：QC1 消费 → 状态门槛 → 空策略 → PolicyJudge 全查 → 配额判检一体 → 记录）
│   └── UsageQueryService.java       // R12 摘要与记录查询（参与方 + 治理可见性）
├── domain/（既有零改动：ContractQueryService/UsagePolicyDslParser/UsageControlPolicy/PolicyElementCatalog）
├── infrastructure/
│   ├── UsageCounterStore.java       // 端口 + Jdbc 实现（判检一体原子递增 + 计数读取）
│   └── UsageLogRepository.java      // 端口 + Jdbc 实现（执行记录写入 + 分页查询）
└── interfaces/
    └── ContractUsageController.java // R12 读端点（GET /api/contracts/{contractNo}/usage-summary）
```

- **既有代码零改动**（QC1/解析器/值对象/目录 = 上游已验收面）；全部新增件；包名与类名编码段踏勘占用后如有冲突以实测为准（冲突则换名并回填本表）。
- **分层纪律**：Controller 不直连数据层（R12 → UsageQueryService → 仓储端口）；判定纯函数 `PolicyJudge` 无 IO（可单测矩阵全覆盖）。

## 2. 判定入口契约（Q2-A：应用层方法，无 HTTP 端点）

```java
public final class PolicyExecutionService {
    /**
     * 使用判定入口（唯一权威——服务端强制，规格行为 5 规则 3）。
     * 放行：递增计数 + 写放行记录；拒绝：写拒绝留痕（含触发要素），零计数、零计数副作用。
     * @throws ContractBizException(1008C0013) 合约未生效/已终止/已完结（策略失效，S3-7 联动）
     * @throws ContractBizException(1008C0012) 合约不存在/不可见（防枚举同形，沿 R6~R11 口径）
     * @throws ContractBizException(1008C0020) 越界使用（五类拦截，触发要素入留痕与日志）
     */
    public UsageVerdict check(String contractNo, UsageRequest request);
}
```

| 字段（`UsageRequest`） | 类型 | 约束 | 语义 |
| --- | --- | --- | --- |
| `requesterNo` | String | 必填，已入驻主体号 | 谁（留痕四要素之一；R12 可见性判定不依赖——执行记录默认参与方 + 治理可见） |
| `actionType` | `UsageActionType` | 必填 | USE / REDISTRIBUTE（转授/转售/对外提供的统一抽象——平台内动作语义，具体动作面归 C-5.x 映射，ADR-020 登记） |
| `purpose` | String | 可空 | 本次使用声明的用途（与 `purpose.text` 精确等值比较，Q5-A） |
| `territory` | String | 可空 | 本次使用声明的地域（与 `territory.text` 精确等值比较，Q5-A） |

| 字段（`UsageVerdict`） | 类型 | 语义 |
| --- | --- | --- |
| `allowed` | boolean | 放行/拒绝 |
| `usedCount` | int | 放行后已用次数（拒绝时 = 当前计数不变值） |
| `violations` | `List<PolicyViolation>` | 拒绝时触发要素明细（全查口径——一次请求触犯多项全部报告） |
| `occurredAt` | `LocalDateTime` | 判定时点（注入 Clock） |

> **集成面契约（供 3.5.2/C-5.x，ADR-020 §集成面落稿）**：消费方在交付/调用链路调用 `check`；同步判定、同步返回；放行即计数——消费方**不得**在判定与实际交付之间假设持有"放行凭证"（判定时点语义）；网络级控制与限流归 3.5.2，本入口不承担；接入口径（端点形态/超时/降级）由消费方设计定。

## 3. 判定顺序表（编码契约——顺序即行为）

| 步 | 判定 | 输入 | 拒绝口径 | 副作用 |
| --- | --- | --- | --- | --- |
| 1 | 合约定位 | `loadEffectiveStrategy(contractNo)`（QC1 直调，零改动） | 不存在 → **C0012**（防枚举同形——沿 R6~R11"不存在与非参与方同码同文逐字"口径；引擎不做参与方可见性二判，R12 承载可见性） | 无 |
| 2 | 状态门槛（**移交-5 承接**，S3-7） | 快照 status | 未生效 / TERMINATED / COMPLETED → **C0013**（策略随合约同步失效）+ 拒绝留痕（原因 = 状态失效） | 拒绝留痕 |
| 3 | 空策略 / 显式无限制 | strategy = null 或 `noRestrictionDeclared=true`（且五要素全禁用——3.4.4 R7 已保证不可矛盾） | — | 放行 + 放行记录（不计数——无配额可计；usedCount 上限口径 = 无上限） |
| 4 | 期限判定（`usage.term` 启用时） | 注入 Clock 当前日期 vs `[startDate, endDate]` | 当前日期 ∉ 区间（**含首末日**：当日 = 起始日或截止日均有效）→ 触发 `TERM_EXPIRED` | 无（全查） |
| 5 | 用途判定（`usage.purpose` 启用时） | `request.purpose` vs `purpose.text` | 不等值（大小写敏感精确比较——trim 已由 3.4.4 规范化保证可比；请求侧同样 trim 后比较，口径对称）→ 触发 `PURPOSE_MISMATCH` | 无（全查） |
| 6 | 域内判定（`usage.territory` 启用时） | `request.territory` vs `territory.text` | 不等值 → 触发 `TERRITORY_MISMATCH` | 无（全查） |
| 7 | 再分发判定（`usage.noRedistribution` 启用时） | `actionType = REDISTRIBUTE` | 启用且动作 = 再分发 → 触发 `REDISTRIBUTION_FORBIDDEN`（转授/转售/对外提供的统一拦截） | 无（全查） |
| 8 | 步 4~7 有触发 → 拒绝 | — | **C0020** + violations 全量明细 + 拒绝留痕 | 拒绝留痕；**计数零变化**（拒绝不烧次数） |
| 9 | 配额判检一体（`usage.quota` 启用时） | `UPDATE contract_usage_counter SET used_count = used_count + 1, last_used_at = ? WHERE contract_no = ? AND used_count < ?` | 影响行数 = 0 → **C0020**（`QUOTA_EXHAUSTED`）+ 拒绝留痕；quota 未启用 → 不计数直接放行 | 放行：计数 +1（原子） |
| 10 | 放行 | — | — | 放行记录（requesterNo/时点/合约/动作/usedCount） |

> **判定语义唯一权威**：步 4~7 的语义逐条来自 `PolicyElementCatalog.judgmentSemantics()`（3.4.4 交付的契约面）——引擎**消费不复制**（评审锚：grep 引擎件不得出现第二套五要素语义定义）。
> **时钟口径**（两把钟教训）：判定日期/时点、记录写入时点统一取注入 `Clock`（bean `systemDefaultZone`，生产行为与系统时钟一致；测试可注入偏移钟）——禁止直取 `LocalDate.now()`/`LocalDateTime.now()`。

## 4. 五类拦截判定表（规格行为 5 规则 2 ↔ 判定步映射）

| 拦截类 | 规格 | 判定步 | PolicyViolation | 验收标准直译锚 |
| --- | --- | --- | --- | --- |
| 次数耗尽 | 超出合约次数上限 | 步 9 | `QUOTA_EXHAUSTED` | 第 1~N 次放行且计数可查；第 N+1 次拒绝且留痕含触发要素（规格验收标准 1） |
| 期限届满 | 超期使用 | 步 4 | `TERM_EXPIRED` | 期限内放行 / 届满后拒绝（验收标准 2） |
| 用途不符 | 约定用途之外的使用 | 步 5 | `PURPOSE_MISMATCH` | 约定用途放行 / 约定外拒绝（验收标准 3） |
| 域外使用 | 约定地域范围之外 | 步 6 | `TERRITORY_MISMATCH` | 域内放行 / 域外拒绝（验收标准 4） |
| 再分发动作 | 禁止再分发时的转授/转售/对外提供 | 步 7 | `REDISTRIBUTION_FORBIDDEN` | 再分发动作拒绝且留痕（验收标准 5） |

统一出站：`1008C0020`（HTTP 403）常量文案「越界使用被拒绝，触发要素见留痕」——明细入拒绝留痕与服务端日志（沿 C0015"常量文案 + 明细入日志"先例）。

## 5. 迁移 V3 两表（Q4/Q7-A：DDL 契约）

```sql
-- V3__create_policy_execution_tables.sql
CREATE TABLE contract_usage_counter (
  contract_no   VARCHAR(20)  NOT NULL,
  used_count    INT          NOT NULL DEFAULT 0,
  last_used_at  DATETIME     NULL,
  PRIMARY KEY (contract_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_usage_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  contract_no   VARCHAR(20)  NOT NULL,
  requester_no  VARCHAR(20)  NOT NULL,
  action_type   VARCHAR(16)  NOT NULL,            -- USE / REDISTRIBUTE
  outcome       VARCHAR(8)   NOT NULL,            -- ALLOWED / DENIED
  reason_code   VARCHAR(16)  NULL,                -- DENIED 时：C0020 / C0013
  violations    VARCHAR(128) NULL,                -- DENIED 时：触发要素码逗号分隔（枚举名，非用户文本）
  used_count    INT          NULL,                -- ALLOWED 时：递增后计数；DENIED 时：当前不变值
  occurred_at   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_usage_log_contract (contract_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **分级**：两表均 **L1**（业务元数据；不含条款原文/请求文本原文——`violations` 只落枚举名，留痕四要素纪律）；分级规范 §6.1 回写两行随编码批。
- **不建的索引/约束**：counter 无需乐观锁版本列（判检一体单语句）；log 不加 reason 外键/触发器（平庸实现）；`requester_no` 不校验入驻状态（消费方在真实链路负责身份，引擎只记"谁发起的这次判定"）。
- **对账数据源**（移交-3 预登记）：counter = 余额口径、log = 流水口径——3.7.x 对账断言以两表交叉验证。

## 6. R12 摘要端点契约

| 项 | 契约 |
| --- | --- |
| 路由 | `GET /api/contracts/{contractNo}/usage-summary`（分页参数沿合约域既有分页口径） |
| 可见性 | 合约参与方（提供方/需求方）+ 治理（admin 角色头）——沿 R6~R11 权限口径；**非参与方与不存在同码同文 C0012 逐字**（防枚举） |
| 响应 | `{contractNo, quota: {limit, used} \| null, allowedCount, deniedCount, records: [{requesterNo, actionType, outcome, reasonCode, violations, usedCount, occurredAt}] 分页}` |
| 权限点 | 复用合约域既有权限点（参与方可见性判定沿 3.4.3 读面实现，不新增权限点面） |
| 错误码 | C0012（防枚举）复用；零新码位（C0020 只在判定入口出站） |

## 7. 并发与事务口径

1. **配额判检一体**（Q4-A）：单条条件 UPDATE 的数据库单语句原子性——两个并发"第 N 次"只有一方影响行数 = 1；无 SELECT FOR UPDATE、无应用层锁（准热路径，锁粒度最小化）。
2. **记录写入与判定同事务**：放行记录/拒绝留痕与计数递增同一事务提交（拒绝腿事务内只有插入 log——计数零变化由"步 9 之前不触碰 counter"结构性保证）。
3. **真并发测试口径**（沿 3.4.3 R2 先例）：并发 N 线程对上限 N 的合约各发起一次使用 → 断言恰好放行 N 次、拒绝 0 次、used_count = N；并发 N+1 线程对上限 N → 放行 N + 拒绝 1 + used_count = N（红相验证判检一体的必要性：如先查后增，超卖可复现）。
4. **幂等口径（登记不做）**：判定入口**不设幂等键**——"按调用计数"语义下，调用方重试即新的一次调用（计数口径 Q3-A 的直接推论）；消费方（C-5.x/3.4.6）需要请求级幂等由其在调用侧承接——ADR-020 登记，避免双处语义分叉。

## 8. 错误码表（1008 段续延一位 + 复用两位）

| 码位 | HTTP | 文案/语义 | 落点 |
| --- | --- | --- | --- |
| `1008C0020`（新占） | 403 | `越界使用被拒绝`（五类拦截统一；触发要素明细入拒绝留痕与服务端日志；`ContractErrorCodes` + `ContractExceptionHandler.MAPPED_CODES`/映射同步） | 步 8/9 |
| `1008C0013`（复用） | 409 | `合约当前状态不允许该操作`（未生效/已终止/已完结 → 策略失效；语义域同"状态门槛"） | 步 2 |
| `1008C0012`（复用） | 404 | `合约不存在或不可见`（防枚举同形） | 步 1 / R12 |

## 9. 测试计划（四面，测试先行——新判定先红后绿）

| 面 | 内容 | 锚（编码段回填实测名） |
| --- | --- | --- |
| ① 判定矩阵单测（`PolicyJudgeTest` / `PolicyExecutionServiceTest`） | 五要素 × 生效/越界**双向**；边界：期限首末日有效/期外首日拒绝、第 N/N+1 次、显式无限制放行不计数、空策略放行不计数、拒绝不烧次数（用途不符后计数不变）、全查明细（一次请求触犯期限+用途 → violations 含两项）、请求侧 trim 对称、注入偏移钟跨期限边界 | `t12`~`t18` 族（映射表 §三） |
| ② 真并发配额 | §7-3 两口径（N/N 与 N+1/N）；红相验证（临时改先查后增复现超卖 → 还原） | `concurrentQuotaNoOversell` / `concurrentQuotaLimitPlusOne` |
| ③ 全链集成（`ContractPolicyExecutionIntegrationTest`，沿既有容器基座） | 发起→确认→双签→生效→放行计数可查（R12）→耗尽拒绝留痕四要素断言；终止→失效（S3-7）；再分发拦截；未生效拒绝；R12 权限矩阵（参与方过/治理过/非参与方 C0012 同形）；QC1/解析器既有锚零回归 | `t11` / `t16` / `t19` / `t20` / `t17` |
| ④ 回归 | contract 既有 158 例全绿（上游已验收面零触碰——QC1/解析器/值对象 diff 应为空，评审①锚） | 既有锚断言零变更 |

> 红相即时归档（3.4.4 教训-1）：先红证据落 `build-output/w345-red-phase-*.txt`。

## 10. ADR-020《策略执行引擎契约》大纲（随编码批落稿；编号沿 ADR-019 顺延）

1. 决策背景与备选（本卡 §二 Q1~Q7 确认口径）；
2. 判定契约：入口签名 / 判定顺序表 / 五类拦截判定表（= 本 hifi §2~§4 定稿）；
3. 计数口径：按调用、放行才计数、拒绝不烧次数（Q3-A）+ 切换触发条件（C-5.x 交付形态落定后如需按交付 → 变更流程）；
4. 未定义项落定登记：用途/域精确等值（词表化·多值·编码化不做）/ 相对期限不做（Q5-A）——出现真实需求走变更；
5. 集成面契约（供 3.5.2/C-5.x）：同步判定语义 / 放行时点语义（无放行凭证）/ 网络级控制不承担 / 接入口径由消费方设计定；
6. 诚实边界：应用层拦截覆盖（规格 §6-1/§6-4 原文引注）；
7. 与 ADR-019 的衔接：引擎消费目录判定语义（禁止两处并行定义的沿伸——判定语义不复制）；迁移触发条件沿 ADR-019 §5。

## 11. 备忘（设计与实现对照义务）

1. **零改动承诺清单**（评审①锚）：`ContractQueryService` / `UsagePolicyDslParser` / `UsageControlPolicy` / `PolicyElementCatalog` / 既有控制器与错误码既有码位——diff 为空；
2. **下游衔接**：3.4.6 模拟器消费 `check` 构造双向演示；3.4.7 界面消费 R12；3.7.x 对账消费 V3 两表；3.8.3 存证消费 log 口径；C-5.x/3.5.2 按 ADR-020 §5 接入；
3. **剧本同步建议**（跟踪-12）：S2/S3 判定点服务端链路已具备、演示入口待 3.4.6——随交付说明给业务语言文本，PO 裁决，本卡不擅自改剧本；
4. **性能豁免**：无真实调用链路可基准——AGENTS §4 基准义务本卡不触发，3.4.8 联调期再评估（登记）；
5. **密码学零改动**：执行记录不含条款原文（SM4 密文列零触碰）；
6. **过度设计有意不做**：通用规则引擎/表达式求值（五要素直写判定 = 最平庸实现）；放行凭证/令牌（判定时点语义足够）；异步事件总线（无消费方）；请求级幂等（§7-4 登记）；
7. **门禁与 CI**：零配置改动（红线 3）；演示环境零触碰（本卡纯代码与测试交付，走查段才起环境）。

---

## 确认记录

> **确认留痕（2026-10-07，编排师会话回复"**都按建议**"）**：**Q1~Q7 均采建议口径 A + D1 不拆分**——本文件转 **V1.0（编码契约）**（正文口径全部生效：落点 = contract-service 同宿主 / 判定入口 = 应用层方法 + 集成契约 ADR-020 / 计数口径 = 按调用〔放行才计数、拒绝不烧次数〕/ V3 counter 表判检一体原子递增 / 用途·域精确等值 + 相对期限不做 / 错误码新占 C0020 + 状态失效复用 C0013 / V3 usage_log + R12 摘要端点 / 不拆分），lofi 同批转 V1.0（方向定稿）；实现进入编码阶段（测试先行，新会话冷启动——第一动作 = 判定矩阵单测先红，红相即时归档 `build-output/w345-red-phase-*.txt`）。

> **草案留痕（2026-10-07 立卡批 `bef5e0a`）**：V0.9 随立卡批提交；踏勘口径（QC1 `loadEffectiveStrategy:187` 现状 / 1008 段下一空位 = C0020 / 迁移 V3 可用 / `domain.policy` 引擎件类名占用待编码段复核）已并入正文。
