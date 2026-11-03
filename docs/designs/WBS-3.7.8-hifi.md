# WBS-3.7.8 高保真设计（hifi V1.0，2026-11-03）

> **编码契约（V1.0 = 已确认，2026-11-03 编排师"确认"后即时生效）**。本卡**测试与集成类**，契约主体 = ①DB-104 守卫契约 ②对账断言映射 ③基准契约 ④探针链判定表 ⑤测试锚点清单。
> **纪律**：实现必须与本文逐条一致；差异登记在 §9"实现差异登记表"（编码段回填 E 编号），不得在实现中私自补需求。

---

## 1. 接口契约表

### 1-A DB-104 查询参数守卫（唯一后端主代码改动）

| 项 | 契约 |
| --- | --- |
| 端点 | `GET /api/v1/metering/flows/governance`（R-M4，3.7.7 交付，本卡不改路径） |
| 参数 | `contractNo` / `requesterNo` / `sourceType` / `from` / `to` / `pageNum` / `pageSize` |
| 新增守卫 | `requesterNo`：`trim()` 后为空 → 视为未传（不过滤）；非空时长度 **6~32**、字符集 **`^[A-Za-z0-9_.-]+$`**，不满足 → `1000C0001`（通用参数错误，文案沿既有 `ParamInvalid`） |
| 既有守卫 | `contractNo` 正则 `^[A-Z]{2}\d{6}$` / `sourceType` 枚举 / `from`·`to` 格式 / `from > to` / 分页上下限（`PageQuery` 1~10000 / 1~100）——**零改动** |
| 语义边界 | 仅收紧"非法输入直接 400"，不改变"合法输入 → 过滤结果"与"空 → 不过滤"；不新增错误码、不改响应形状 |
| 落点 | `services/metering-billing/src/main/java/com/ctds/metering/interfaces/MeteringController.java`（`governanceFlows` 守卫段，现有 `:114-158` 内追加 requesterNo 分支；具体行号编码段实测回填） |

### 1-B 对账断言映射（B11 硬约束 → 七类差异 → 断言）

| # | 差异类型常量 | 业务含义 | 现有测试引用（立卡实测） | 本卡处置 |
| --- | --- | --- | --- | --- |
| 1 | `COUNTER_VS_USAGE_LOG_ALL` | 合约计数余额 vs 执行流水全量 | `BillingAutoReconcileIntegrationTest` 1 处 | 复核断言强度（定位字段齐备） |
| 2 | `USAGE_LOG_VS_METERING_ALL` | 执行流水全量 vs 计量流水全量 | 同上 1 处 | 同上 |
| 3 | `USAGE_LOG_VS_METERING_PERIOD` | 本期流水 vs 本期计量 | 同上 1 处 | 同上 |
| 4 | `BILL_QUANTITY_VS_METERING` | 账单数量 vs 本期计量数 | 同上 2 处 | 同上 |
| 5 | `AMOUNT_RECOMPUTE` | 账单金额 vs 按快照独立复算 | 同上 1 处 | 同上 |
| 6 | `MISSING_IN_BILL` | 计量明细缺失于账单 | 同上 1 处 | 同上 |
| 7 | **`EXTRA_IN_BILL`** | 账单明细多出（**测试零引用**） | **0 处** | **新增 1 例**（补覆盖，非新行为） |

**B11 验收 1~4 → 断言清单**

| 规格验收 | 断言要求 |
| --- | --- |
| B11-1 正向 | 四方数值逐项一致（`counterUsedCount` / `usageLogAllowedCount` / `usageLogAllowedAllTime` / `meteringFlowCount` / `meteringFlowAllTime` / `billQuantity` / `billTotalAmount` / `recomputedAmount`）→ `conclusion = PASS`、`differences` 空、留痕（执行人 / 时点 / 数值）非空 |
| B11-2 负向 | 构造差异后 `conclusion = DIFFERENCE`、`differences` 非空、**差异类型可定位**（七类逐类 1 例，含 `EXTRA_IN_BILL`），每项含 `type / leg / expected / actual / detail` |
| B11-3 fail-closed | 差异态账单 `status = FROZEN`；确认 → 409 `1011C0011`；还原后再次对账 → `PASS` 且状态恢复、确认 200 |
| B11-4 零自动改数 | 对账前后：账单金额 / 明细逐字不变、计量流水行数与关键列不变、除状态流转与留痕表外零写入（SQL 前后快照对照断言） |

### 1-C 计量流水性能基准契约

| 项 | 契约 |
| --- | --- |
| 类 | `services/metering-billing/src/test/java/com/ctds/metering/MeteringFlowBenchmarkIntegrationTest.java`（`@SpringBootTest` + `@Testcontainers(disabledWithoutDocker = true)` + 独立库名，沿 `SharedMySqlContainer` 形态 B） |
| 数据集 | 测试库内批量插入 **10000** 条 `metering_flow`（20 合约 × 500 条，`occurred_at` 跨 12 个自然月均匀分布，`source_type` 四值轮转，`source_no` 唯一，`requester_no` 取 20 个主体号） |
| 场景 | ① R-M1 本方逐条分页（`pageSize=20`，第 1 页）② R-M2 分期汇总（单合约全周期）③ R-M4 运营全量分页（`pageSize=20` 第 1 页）④ R-M4 合约 + 时间区间过滤 ⑤ R-M4 越页（`pageNum=999`） |
| 轮次与统计 | 每场景 **50 轮**（前 5 轮预热不计入），记录 ms，算 **P50 / P95 / max** |
| 达标线 | **P95 ≤ 500ms**（章程性能锚，容器内单机口径） |
| 输出 | `services/metering-billing/target/benchmark/w378-bench-<时间戳>.json`（`target/` 不入库）+ 数字摘要入日志与任务卡 |
| 诚实边界 | 单机容器、无并发加压、资格门为真实服务（非 stub）；门户 ≥1000 并发与网关 ≥2000 TPS 归 4.2.2 / 4.2.3 |

### 1-D 端到端探针链契约（API 级；证据落 `build-output/w378-probe-chain{ABC}.log`，不入库）

| 链 | 判定点（计数） | 承载剧本步骤 |
| --- | --- | --- |
| **A 计量不双计** | 7：S1-1 流水条数 = N / S1-2 拒绝后仍 N 且拒绝留痕独立 / S1-3 四方各 1 条不双计 / S1-4 沙箱与导出各 1 条不叠加 + 自检零新增 / S1-5 免费档零账单（`1011C0015`）/ S1-6 计数 = 计量 = N / S1-7 主体 C 404 `1011C0008` 同码同文 | C-7.2 计量 S1-1~S1-7 |
| **B 出账与对账双向** | 14：S2-1 三模式各 1（3）/ S2-2 重复出账 409 `1011C0010` / S2-5 明细复算 = 金额 / S2-6 改价后原单不变 / S2-7 无修改端点 + 更正单 201 且原单不变 / S2-9 供需双方各仅本方 / S2-10 主体 C 404 `1011C0009`；核对 S1-1 对账 PASS / S1-3 需方读结论；核对 S2-2 差异必报 / S2-3 冻结确认 409 `1011C0011` / S2-5 还原后 PASS + 解冻 + 确认 200 | C-7.2 计量 S2-1~S2-10 + 账单核对 S1-1~S1-3 / S2-2~S2-5 |
| **C 读面权限与防枚举** | 8：R-M4 运营 200 / 普通档 403 / 主体 C 直连 404 同码同文（若端点按权限先拒则 403，判定以"不可读"为准，编码段实测登记口径）/ 非法 `contractNo` 400 / 非法 `sourceType` 400 / 时间格式 400 / `from > to` 400 / `requesterNo` 超长 400 `1000C0001`（DB-104 兑现） | B12-1 / B12-3 + DB-104 |

> 探针一律回环直连 `http://127.0.0.1:8089` + 身份头（沿剧本操作路径表口径，ADR-016 §2.7 信任边界）；网关 8086 代理腿可验则补验 1 组（JWT 可得时），不可得则如实登记"未验"。

---

## 2. 模块改动清单（逐文件，编码段按此对账；★ = 新文件）

| 文件 | 改动 |
| --- | --- |
| `services/metering-billing/.../interfaces/MeteringController.java` | 1-A 守卫（+10~20 行） |
| `services/metering-billing/src/test/.../interfaces/MeteringGovernanceIntegrationTest.java` | T10 追加 2 例（超长 33 / 非法字符；同库名，零新增容器） |
| ★ `services/metering-billing/src/test/.../BillingReconciliationAssertionIntegrationTest.java` | T6~T8（正向 / 七类负向 / 冻结解冻 / 零自动改数），约 8~10 例 |
| ★ `services/metering-billing/src/test/.../MeteringFlowBenchmarkIntegrationTest.java` | 1-C 基准（5 场景 × 50 轮） |
| `services/metering-billing/src/test/.../BillingOutbillIntegrationTest.java` | 视盘点补 T4/T5 缺口（三模式复算 / 更正不改原单） |
| `services/metering-billing/src/test/.../BillingReadIntegrationTest.java` | 视盘点补 T9 缺口（过滤与归属） |
| `frontend/src/**/*.spec.ts` | 仅当 W3 盘点确认展示面缺口时补 1~3 例；**界面代码零改动** |
| `docs/manuals/C-7.2-计量计费中心-验收剧本.md` / `C-7.2-账单核对-验收剧本.md` | 维护说明追加"3.7.8 自动化覆盖对照"章节（业务判定一字不改） |
| `docs/status/进度看板与债务登记簿.md` | DB-104 转"待验证 → 已关闭"（验收段） |
| `docs/tasks/WBS-3.7.8-…-2026-11-03.md` / `docs/logs/…` | 执行记录 / 体量 / 日志 |

**零改动面**：`scripts/gates/**`、`frontend/src/views/**`、其他服务主代码、所有迁移文件。

---

## 3. 边界值与异常行为表（E1~E12）

| 编号 | 场景 | 期望行为 |
| --- | --- | --- |
| E1 | `requesterNo` 未传 | 不过滤（200） |
| E2 | `requesterNo=`（空串）或纯空格 | trim 后为空 → 不过滤（200） |
| E3 | 长度 = 6 / = 32（边界内） | 允许（200，可能空结果） |
| E4 | 长度 = 33 | 400 `1000C0001` |
| E5 | 长度 = 5 | 400 `1000C0001`（演示主体号实为 15 位，安全） |
| E6 | 含空格 / 中文 / `;` / `%` | 400 `1000C0001` |
| E7 | 合法形态但无匹配（如 `S99999999999999`） | 200 + 空列表 + `total = 0` |
| E8 | 其余六参数既有守卫 | 零回归（`contractNo` / `sourceType` / `from`·`to` / `from > to` / `pageSize > 100` / `pageNum > 10000`） |
| E9 | 对账：该合约该期无账单 | **实测 = 404 `1011C0009`**（账单不可见码，同码同文；非 `1011C0014` 合约不存在码）；断言见 `BillingReconciliationAssertionIntegrationTest.reconcileWithoutBillIsNotVisible` |
| E10 | 冻结态确认 | 409 `1011C0011` |
| E11 | 差异未澄清再次对账 | 仍 `DIFFERENCE`，差异项不叠加重复（幂等口径，编码段实测登记） |
| E12 | 基准越页（1 万条中 `pageNum=999`） | 200 + 空列表 + `total` 真实（10000 口径） |

---

## 4. 测试锚点（测试先行：红相归档 → 实现 → 绿相）

| 锚 | 内容 | 落点 | 例数（预估） | 红相预期 |
| --- | --- | --- | --- | --- |
| **T1** | 计量不双计与拒绝零计量（B7-1~4） | `MeteringCollectReconcileIntegrationTest` 补强 | 0~2 | 补覆盖类（不造红） |
| **T2** | 免费档零计费（B8-2） | 既有覆盖复核 | 0 | — |
| **T3** | 归属防枚举（B8-4 / B12-3） | 既有覆盖复核（3.7.5 口径零改动） | 0 | — |
| **T4** | 三模式可复算（B9-1~4） | `BillingOutbillIntegrationTest` 补强 | 0~2 | 补覆盖类 |
| **T5** | 出账幂等 / 不可篡改 / 更正不改原单（B10-1~4） | 同上 | 0~2 | 补覆盖类 |
| **T6** | 对账正向四方一致（B11-1） | ★ 对账专项类 | 2 | **红**（新类未建） |
| **T7** | 七类差异逐类定位（B11-2，含 `EXTRA_IN_BILL`） | ★ 同上 | 7~8 | 6 类可绿（行为已实现）、`EXTRA_IN_BILL` 为补覆盖 |
| **T8** | 冻结 fail-closed + 零自动改数（B11-3/4） | ★ 同上 | 3~4 | **红**（新类未建） |
| **T9** | 读面过滤与归属（B12-1/2） | `BillingReadIntegrationTest` 补强 | 0~2 | 补覆盖类 |
| **T10** | R-M4 守卫（DB-104 + 既有四参数回归） | `MeteringGovernanceIntegrationTest` | +2 | **真红**（当前 33 位 / 非法字符返回 200） |
| **T11** | 探针三链 A/B/C（7 / 14 / 8 判定点） | `build-output/w378-probe-chain*` | — | 执行态，非红相 |
| **T12** | 基准五场景 × 50 轮 | ★ 基准类 | 1 | 执行态 |

**红相口径申报**：本卡红相 = T6 / T8（新测试类首次运行必红，行为由既有实现承载）+ **T10 真红**（守卫行为缺失）。T1 / T2 / T3 / T4 / T5 / T7 / T9 中"补覆盖"类用例**不制造假红**，如实登记"补覆盖类、绿相直接通过"，评审不得据此判红相缺失（口径沿 3.7.7 修复批"补覆盖非红相"实践）。

---

## 5. 红相 / 绿相与达标口径

- **红相**：T6 / T8 归档（★ 类首次运行失败日志）+ **T10 强红相**（守卫缺失 → 断言 400 实得 200，日志存档）；
- **绿相**：metering-billing 全量用例 = 既有 57 + 新增（预估 +14~18）全绿；行覆盖复测留痕；前端 863 零回归（若补例则 +N）；全仓 20 模块 compile；checkstyle 0；
- **门禁基线**：`gates-config.json` SHA256 与 V1.12 基线（`955bdbba…2ee2`）逐字一致 = 零改动自证。

---

## 6. 实施前置检查（编码段第一动作，八项逐项实测留痕）

| # | 检查 | 实测结果（2026-11-03 编码段） |
| --- | --- | --- |
| 1 | 分支 `feat/WBS-3.7.8-门户计费测试与集成` 状态、本地 main 梢、工作区 clean | ✅ 分支在、梢 `edd3aae`（确认批 + 日志批）、工作区 clean；本地 main `d932b32`（领先 `origin/main` `2ae103c` 3 笔纯文档） |
| 2 | 基线复跑：metering-billing 例数与结果 / 前端 863 四步（test·typecheck·lint·build）/ 各域模块零回归 | ✅ 后端 **57 例 / 0 失败 / 0 跳过**（`build-output/w378-baseline-backend.log`）；前端 **65 文件 863 例全绿** + typecheck / lint / build 全过（`w378-baseline-frontend.log`）；他域零改动，全仓 20 模块 compile 绿相复跑 SUCCESS |
| 3 | 覆盖复测（metering-billing，jacoco） | ✅ 基线 **95.67%**（covered 5965 / total 6235）；绿相 **95.83%**（5991 / 6252，新代码计入）——均 ≥80% |
| 4 | **R-M4 守卫现状实测**：33 位 `requesterNo` / 含 `;` → 当前返回码（预期 200） | ✅ **实测 200**（T10 两例首跑 `expected 400 but was 200`，`build-output/w378-red-guard.log`）——守卫缺失事实确认 |
| 5 | 对账七类差异"实现存在 ↔ 测试引用"逐类对照（确认 `EXTRA_IN_BILL` 缺口） | ✅ 六类各 1~2 处引用（`BillingAutoReconcileIntegrationTest`），**`EXTRA_IN_BILL` = 0 处**（缺口确认） |
| 6 | 基准可行性：`metering_flow` 列面 / `idx_flow_contract` 索引 / 万级插入耗时 / 容器资源 | ✅ 九列最小投影 + `idx_flow_contract (contract_no, occurred_at)` 实读；万级造数实测 **146.7s**（20 批 × 500，容器自动提交逐批 fsync 口径）；Docker 三容器就绪 |
| 7 | `gates-config.json` SHA256 | ✅ `955bdbba…2ee2` 与基线逐字一致（零改动自证） |
| 8 | 环境：容器三件（mysql / minio / redis）+ 8089 监听 + 起停脚本 `w378-*` 基线 | ⚠️ 三容器 Up；**8089 / 8086 / 5173 未监听**（演示链未启动）→ 探针三链（W5）需先起服务，本段未执行，见 §9 E3 |

> 任一项与本文不符 → 登记 E 编号并在编码前回填本文（沿 3.6.8 / 3.7.5 先例）。

---

## 7. 决策点全文与备选（业务影响）

见 `docs/designs/WBS-3.7.8-lofi.md` §4（Q1~Q8 + D1 摘要）。业务影响补充：

- **Q2 若选 B（造数脚本入 `scripts/benchmark/`）**：多一份可在演示库被误执行的破坏性脚本——DB-40 已登记同类风险，收益仅是"脚本可复用"，不采；
- **Q4 若选 B（仅长度上限）**：字符集面留到后续硬化批，但 DB-104 关闭条件写的是"长度与字符集守卫"，选 B 等于部分关闭，须改台账口径；
- **Q6 若选 B（复用既有对账用例）**：`EXTRA_IN_BILL` 保持零覆盖，B11-2"缺失 / 多出可定位"的"多出"腿仍是假绿——对账硬约束存在缺口，不采。

---

## 8. 红线与边界复述（自查清单）

1. 资金语义红线：不新增任何资金动作；账单金额与明细不可改（本卡只加断言，不加写面）；
2. 不改上游：3.7.5 / 3.7.6 / 3.7.7 端点、表、错误码、权限点零改动（W1 仅收紧参数校验，复用 `1000C0001`）；
3. 零门禁配置改动（红线 3）；变异范围不扩；覆盖只测量不开门禁；
4. 演示库零触碰：基准与差异构造全部在**测试容器库**内完成；演示库差异构造仍由剧本附录 A 人工执行；
5. 零新依赖 / 镜像 / 迁移 / 权限点 / 错误码；
6. 剧本业务判定一字不改；
7. 规格缺口（如 E9 无账单口径不明）→ 实测登记为主，若构成规格歧义则停止并上报，不在本卡私自定口径。

---

## 9. 交付物与证据清单 + 实现差异登记表

**证据**（`build-output/` 不入库，路径写入日志与任务卡）：
`w378-red-{reconcile,guard}.log` / `w378-probe-chain{A,B,C}.log` / `w378-bench-<ts>.json` / 覆盖复测 `jacoco.csv` 摘要 / 前端与全仓复跑日志。

**实现差异登记表（编码段回填）**

| 编号 | 差异 | 理由 | 状态 |
| --- | --- | --- | --- |
| **E1** | 基准场景① R-M1 **无分页参数**——§1-C 原文写"`pageSize=20` 第 1 页"，与实际端点形状不符；实测按"单合约全量 500 条"计时 | R-M1 端点（3.7.5 交付）不接受分页入参，本卡零改动面不得加参数；"逐条读面"的真实负载即单合约全量，硬套 pageSize 会变成另一个端点（R-M4）而与场景③重复 | ✅ 已按端点真实形状实施（`MeteringFlowBenchmarkIntegrationTest.ownFlowsScenario`） |
| **E2** | 对账专项差异构造为"用例内直接改测试库账单数量 / 金额（同步复算一致）"与"插入他合约流水 id 到账单明细"，用于**隔离单腿** | 既有 TB9 用例的构造会同时命中多腿（数量 + 缺失），无法证明"逐类可定位、无假绿"；隔离构造只在测试容器库内执行，不改生产代码、不改既有用例 | ✅ 已实施（`BillingReconciliationAssertionIntegrationTest` 七类各 1 例，每例断言 `differences` 恰 1 项） |
| **E3** | **探针三链（W5 / 链 A7·B14·C8）本段未执行** | 编码段开工时 8089 / 8086 / 5173 均未监听，且链 A/B 需网关、交付、沙箱全链真实事件（九服务 + 演示数据构造）；按会话停机纪律不在末段启动全链 | ⏳ **顺延**：列为续点第 0 项，执行前先 `w375-accept-start.ps1` 起链并重建 metering-billing jar（教训：旧 jar 会让端点整片 404） |
| **E4** | §3 E9"无账单"口径实测 = **404 `1011C0009`**（非 `1011C0014`） | 实现走 `BillingReconcileService.notVisible()`（账单不可见码），与"合约不存在"分支（`1011C0014`）不同；同码同文防枚举口径不变 | ✅ 已回填并加断言（`reconcileWithoutBillIsNotVisible`） |
