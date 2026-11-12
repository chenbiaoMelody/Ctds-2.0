# 门禁超时配置变更申请（WBS-3.8.6 承接）

- 申请日期：2026-11-11
- 申请人：AI 编码会话（冷启动承接 `docs/logs/Ctds-项目开发日志-26-11-11-1508.md` 续点）
- 关联任务卡：`docs/tasks/WBS-3.8.6-存证审计测试与集成-2026-11-10.md`
- 变更对象：`scripts/gates/gates-config.json` 中的**阶段超时预算**（`timeoutSeconds`）
- 状态：**✅ 编排师裁定 A 已落地（2026-11-11）**——三处超时预算变更已生效（V1.13），全仓门禁复跑 GREEN，详见 §七 裁定结果

## 一、为什么提这份申请（业务可读）

本包的代码检查（"门禁"）已经连跑两轮，两轮结论都是"不合格"，但**没有任何一条测试失败**——失败的全部是"时长到点被掐断"：

| 轮次 | 演示服务状态 | 结论 | 被掐断的环节 |
| --- | --- | --- | --- |
| 第 1 轮（14:52 报告） | 8 个演示服务在跑 | RED（2 项超时） | 全仓单元测试（30 分钟上限）、覆盖率测量（30 分钟上限） |
| 第 2 轮（16:34 报告，已停 8 服务） | 全停，仅保留 3 个数据库容器 | RED（3 项超时） | 全仓单元测试 1803 秒（30 分钟上限）、前端测试 303 秒（5 分钟上限）、覆盖率测量 1804 秒（30 分钟上限） |

第 2 轮停掉演示服务后，进度确实更靠后了（第 1 轮掐断在 21/22 个模块的"计费"模块，第 2 轮掐断在 22/22 的最后一个"存证"模块尾部），说明腾资源有效；但**全仓测试总量已经贴住或超过 30 分钟**，靠腾资源已解决不了。

## 二、证据（可复核）

- 报告：`scripts/gates/reports/gate-report-20261111-163425.md`（RunLabel `20261111-151921-4957`，`ExitCode: 1`，PASS=12 / FAIL=3 / SKIP=2 / ERROR=0 / PENDING=5）
- 阶段原始输出（本机 `%TEMP%`，文件名固定）：`ctds-gate-unitTest-out.log` / `ctds-gate-frontendTest-out.log` / `ctds-gate-coverage-out.log`；执行日志 `build-output/w386-gates-rerun2.log`
- 已完成用例 **0 failures / 0 errors**：unitTest 与 coverage 两轮的 `Tests run` 行全部 0 失败；掐断点位 = evidence-service 的 `EvidenceStateWriteFailureIntegrationTest`（15:51:52 启动）/ `EvidenceArchitectureTest` 之后
- 配置零改动自证：`ConfigSha256 955bdbba…2ee2` 与基线逐字一致（两轮均一致）
- 其它阶段全部 PASS：`gateSelfTest` 196 断言 0 失败（106 秒）、`secretsScan` 2706 文件 0 命中、`compile` / `lint` / `frontendTypeCheck` / `frontendLint` / `mutationTest`（did 69.34%）/ `sast`（0 findings）

## 三、根因（两个结构性因素）

1. **全仓测试要跑两遍**：`unitTest` 一遍、`coverage` 的 `prepare-agent test` 再一遍，两个阶段各自 1800 秒上限。本包新增存证测试 **78 例**（含万条流水基准）与网关 **49 例**，全仓测试总量上升后，单遍已逼近 30 分钟。
2. **前端测试预算 300 秒偏紧**：前端全量 29 文件 304 例，本机实测波动已触及 300 秒上限（第 1 轮 PASS、第 2 轮 303 秒超时），属**边界抖动**而非用例变慢。

## 四、可选项（业务影响对比，供编排师选择）

| 方案 | 做法 | 业务影响 | 风险 |
| --- | --- | --- | --- |
| **A（建议）最小调整** | `unitTest` / `coverage` 时限 1800→**2700 秒**；`frontendTest` 300→**600 秒** | 单轮门禁最长耗时增加约 20~30 分钟（仅在最慢情况下发生）；判定标准、扫描面、阶段范围**零变化** | 单轮门禁更久；CI 单 job 时长上升（windows runner 更慢，需观察是否触及平台上限） |
| B 拆分/分片 | 把全仓测试拆成 2~3 个门禁阶段并行或分模块跑 | 单轮耗时下降 | 属门禁**结构**变更，改动面大、需要重跑自检与 CI 编排，本卡不适合 |
| C 测试提速 | Testcontainers 容器复用、Spring 上下文合并、基准用例移出门禁常态面 | 长期最优 | 需另立卡（涉及测试改造），且会改变证据面，须先设计 |

**建议**：采 **A 案**先行（阈值只增不减、阶段范围与判定标准零变化，属最小改动），并把 **B / C 作为后续技术债**登记（C 优先）。

## 五、本次申请涉及的具体键位（裁定 A 已落地，键位实况如下）

- `stages.unitTest.timeoutSeconds`：1800 → 2700
- `stages.coverage.timeoutSeconds`：1800 → 2700
- `stages.frontendTest.timeoutSeconds`：300 → 600

裁定 A 通过后已按下列要求全部落地（2026-11-11）：① 更新 `gates-config.json` 的 `version`（V1.12→V1.13）/ `changeLog` / `note`；② 更新 `scripts/gates/README.md` 的工具链节与 ADR-018 留痕（V1.11）；③ 记录新的 `ConfigSha256` 基线 `adc3f98b…5cfb`；④ 复跑 `selftest.ps1`（196 断言 0 失败）与全仓门禁确认 GREEN（`gate-report-20261111-224534.md`）。

## 六、本卡声明（申请时点 2026-11-11 裁定前；裁定后实况见 §七）

- **申请时点未改动任何门禁配置**（`ConfigSha256` 两轮均与旧基线 `955bdbba…2ee2` 一致）；裁定 A 后已按 §五 键位落地 V1.13（见 §七）；
- **未降低任何判定标准、未关闭任何阶段、未扩大排除项**；
- 本包**在门禁 GREEN 前不交付、不进入 4 视角评审**（章程原则 3 / 5，红线 3）。

## 七、裁定结果（2026-11-11 编排师回复"A"，已落地）

- **裁定**：编排师 2026-11-11 会话回复 **"A"** = 采 A 案最小调整（阈值只增不减、阶段范围与判定标准零变化）。
- **落地键位**（`gates-config.json` V1.12→**V1.13**）：`stages.unitTest.timeoutSeconds` 1800→**2700**；`stages.coverage.timeoutSeconds` 1800→**2700**；`stages.frontendTest.timeoutSeconds` 300→**600**（新增键）。`run-gates.ps1` 前端四段超时改读 `stages.<name>.timeoutSeconds`，未配键默认 300s 不变（`frontendTypeCheck`/`frontendLint` 行为零变化）。
- **零判定改动实证**：阈值（core 80 / overall 70 / 变异 60）、`coreModules` 5 项、`mutationTest.modules`（did-only）、`secretsExcludePaths`、阶段范围与五态退出码语义与 main **逐字一致**（主会话 `git diff` 键值统计复核，diff 空）。
- **新基线**：`ConfigSha256` **`adc3f98b…5cfb`**（首次登记 `df13795c…3cd5`；2026-11-11 日志更名致 changeLog 文本级漂移，`-224534` 复跑刷新后逐字一致）。
- **验证**：`selftest.ps1` **196 断言 0 失败**；全仓门禁单发 **GREEN** = `scripts/gates/reports/gate-report-20261111-224534.md`（RunLabel `20261111-214941-6429`，PASS=15 / FAIL=0 / SKIP=2 / ERROR=0 / PENDING=5 / ExitCode 0，unitTest 实跑 1170s、coverage overall 92.4%、frontendTest PASS）。
- **留痕载体**：config changeLog V1.13 + `scripts/gates/README.md`（当前版本 / 基线链 / 工具链节 / V1.13 条目）+ `ADR-018` V1.11 + 任务卡 §四"超时预算调整段"/"门禁复跑刷新段" + hifi §9 E10。
- **后续技术债**：B（拆分分片）/ C（测试提速）登记台账，**C 优先**（基准用例移出门禁常态面 / 上下文合并）。
