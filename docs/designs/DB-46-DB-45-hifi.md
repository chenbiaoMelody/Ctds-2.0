# DB-46+DB-45 高保真设计：门禁可信性（V1.0 编码契约）

> 任务卡：`docs/tasks/DB-46-DB-45-门禁可信性-2026-10-17.md`（**卡 2a = A 组债务本体**；Q1-B / Q2-A / Q3-A / Q4-A + D1 已确认）
> 方向设计：`docs/designs/DB-46-DB-45-lofi.md`（V1.0）
> 状态：**V1.0 编码契约即时生效**（2026-10-17 编排师"确认"回执后随确认批提交）——编码归新会话冷启动，**测试先行、红相先行归档**
> 上游基线：`main == origin/main == f99980d`；`gates-config.json` V1.11（ConfigSha256 `315264e1…1304`，**本卡零改动**）；selftest 十九场景 148 断言；前端 440 例；门禁基线报告 `gate-report-20261017-193026`（RED = DB-46 第二次复现）

---

## 1. 现状盘点（编码前事实，梢 `f99980d` 实测）

| 面 | 事实 | 位置 |
| --- | --- | --- |
| DB-46 测试配置 | `IdempotencyAdviceTest` 持 `PROCESSING_TTL=1s` / `RESULT_EXPIRE_SECONDS=1`（生产默认 = 60s / 600s）；脆性面 = 3 例（:177 / :185 / :302-307，均假设 1s 窗口内二次调用）；`结果过期后同键重新执行`（:225-230）**依赖** 1s 配置（sleep 1.2s 后期望重执行） | `common/idempotency/src/test/java/com/ctds/common/idempotency/IdempotencyAdviceTest.java:50-51,177,185,225-230,302-307` |
| DB-46 产品侧 | `InMemoryIdempotencyStore` 用 `System.currentTimeMillis()` 惰性过期（Q2-A 不碰）；`RedisIdempotencyStore` TTL 全委 Redis（无时钟面）；`IdempotencyProperties` 默认 60s/600s | `common/idempotency/src/main/java/com/ctds/common/idempotency/{InMemoryIdempotencyStore,RedisIdempotencyStore,IdempotencyProperties}.java` |
| DB-46⑩ 记录点 | 计量事件在 `chain.filter(exchange).then(Mono.deferContextual(... record ...))` 续段记录（响应链完成后），与 WebTestClient `exchange()` 返回无时序保证；同类精确计数断言 5 处（:221-222 / :297 / :347-348 / :362）；空断言 4 处（:122 / :220 / :235 / :287） | `services/gateway-service/src/main/java/com/ctds/gateway/metering/DeliveryMeteringGlobalFilter.java:51-60`；`.../test/.../GatewayDeliveryChainIntegrationTest.java` |
| DB-45 清理点 | `Stop-ProcessTree` = `taskkill /PID <pid> /T /F` + `$proc.Kill()`；**仅 3 个调用点、均在超时分支**（`Invoke-MetricProcess:180` / mvn 段 :484 / npm 段 :556）；`$RepoRoot` 为脚本级参数（:56，长路径展开 :96）；mvn 段 `WorkingDirectory = $RepoRoot`（:468）；fixture 运行器以 `-RepoRoot <fixture 目录>` 调用（selftest `Invoke-Runner:172`） | `scripts/gates/run-gates.ps1`；`scripts/gates/selftest.ps1:170-174` |
| selftest 既有场景 | 十九场景 148 断言；S14 = 超时进程树清理探针（stub mvn 起孙进程后挂起，compile `timeoutSeconds=2`，断言 FAIL + out.log `(TIMED OUT)` + 孙进程 PID 消失；孙进程与挂起循环均重定向 nul 不占运行器管道；红相期由 selftest 收杀防泄漏） | `scripts/gates/selftest.ps1:739-802` |
| 变更流程基线 | ADR-018 现行 V1.8；README 变更留痕段 + L95"已知特性"（现文："`taskkill /T` 按父子快照清理，'已脱离父链的孙进程'可能漏网（罕见）"——**本卡须勘正**）；运行器头注 record 段（DB-06 / DB-43 / DB-07 三段先例） | `docs/adr/ADR-018-质量度量门禁接入.md`；`scripts/gates/README.md`；`scripts/gates/run-gates.ps1:1-45` |

## 2. 目标形态（三修复，契约级）

### 2.1 DB-46（Q2-A：测试配置分离，零产品代码）

1. **`IdempotencyAdviceTest`（主类）窗口改持生产默认**：配置 Bean **移除 `processingTtlSeconds` / `defaultExpireSeconds` 两个覆写**（保留 `mode=memory` + `auditEnabled=false`）——窗口即 `IdempotencyProperties` 默认 60s/600s，测试不再复刻可能漂移的字面量；类注释补一句"去重语义断言不依赖窗口长度（窗口取生产默认）"；
2. **脆性 3 例随之确定性化**（:177 / :185 / :302-307）：同一上下文内二次调用必在 60s 窗口内 → 命中缓存 → 原断言原文不动（`expected: 1 but was: 2` 类失败在物理上不可达——除非机器停顿 >60s，届时全仓测试皆无意义）；
3. **`结果过期后同键重新执行` 独立成类**：新文件 `IdempotencyAdviceExpiryTest`（同包，package-private，`@SpringJUnitConfig` + `@EnableAspectJAutoProxy`），自持 1s/1s 配置（**显式覆写**，与主类形成对照：主类验"窗口内去重"、本类验"窗口过期重执行"）；最小自含夹具（仅 `submit` 计数方法 + store + properties + advice 四个 Bean，不抽取共享文件、不改主类夹具）；测试体原文迁移（sleep 1.2s 的 over-wait 写法保留——只会多等不会少等，方向稳健）；
4. **⑨ 窗口核对（A2）**：编码段产出"窗口核对清单"登任务卡 §四——逐例说明修复后每例的通过与墙钟假设的关系（主类：零时间假设；过期类：仅 over-wait sleep，方向稳健；并发处理中类（:199）： latch 驱动且 60s 处理窗口消除"首请求 acquiring 标记在二次调用前过期"的潜在脆性）；
5. **主类 `setUp` 与键清理不变**（`store.release("A"/"B")` 对 600s 结果 TTL 同样成立——release 清 acquiring + results 两键）。

### 2.2 DB-46⑩（最终一致断言，零产品代码）

1. **新增两个有界等待助手**（测试类私有）：`awaitEventsOf(action, expected)` / `awaitAllEvents(expected)`——截止期轮询（预算 5s，步进 20ms），到点未达即**带实际计数失败**（绝不等同于放宽：等不到照样红）；
2. **改造 5 处精确计数断言点为"先等待后断言"**：:221-222（denied 1）、:297（metering 1）、:347-348（metering 3 + denied 2）、:362（all-events 1）——**断言语义原文不动**（`hasSize(n)` 与后续字段断言一字不减），仅把数据来源从即时快照改为有界等待结果；
3. **空断言（:122 / :220 / :235 / :287）不改**——不变式 = "每个产事件的测试在结束前都已等待其事件到达"（本条由 ① 保证），故后续测试的空断言不可能被上一测试的迟到事件污染；该不变式写入类注释；
4. **等待预算 5s 的理由**：录制替身是进程内内存列表（`RecordingAuditRecorder.record` synchronized），等待只覆盖"响应链续段尚未执行"的调度窗口；5s 远超任何合理调度延迟，又不至拖慢套件（正常路径首次轮询即达）。

### 2.3 DB-45（Q3-A：双特征窄匹配兜底清扫）

1. **新增函数 `Stop-DetachedStageProcesses`**（`run-gates.ps1`，紧邻 `Stop-ProcessTree`），由 `Stop-ProcessTree` 在 taskkill 之后调用（**单挂接点，3 个超时分支全覆盖**；正常完成路径不调用 → 仅超时触发）；
2. **识别特征（双条件 -like，大小写不敏感）**：命令行**同时**含 ① 本次运行的 `$RepoRoot`（`TrimEnd('\','/')` 后前缀匹配）**且** ② `\target\surefire\`（surefire 分支 JVM 的 booter jar 绝对路径段——jacoco 插桩分支同样命中，因其即 surefire fork）；
3. **保护面**：枚举前先沿 `Win32_Process.ParentProcessId` 上溯构建**自身 + 祖先链**（自 `$PID` 起），链内进程一律不杀（防误杀运行器 / 外层门禁 / selftest / IDE 等同链进程）；
4. **best-effort**：逐进程 `Stop-Process -Force` 包 try/catch，整体再包 try/catch——**任何失败静默**，不影响段判定（超时段结论已由既有逻辑判 FAIL；清扫是保险不是判定，沿"记录是加菜"原则）；**报告行格式零改动**（不往 FAIL 行追加清扫计数——避免行文口径漂移，取证走 selftest 探针）；
5. **诚实边界（README L95 + ADR-018 双处登记）**：非 surefire 形态的脱缰进程（如 detached node/vitest worker）**不在**本特征面内——本卡只兑现已实证造成假红的 surefire/jacoco 形态；其余形态如后续实证需要，另行登记；
6. **PS 5.1 兼容**：只用 `Get-CimInstance Win32_Process`（CIM cmdlets 内置）+ `Stop-Process`，无新模块无新依赖；`$RepoRoot` 为脚本级参数直接可见。

## 3. 编码契约细则（逐文件）

| # | 文件 | 动作 | 契约要点 |
| --- | --- | --- | --- |
| 1 | `common/idempotency/src/test/java/com/ctds/common/idempotency/IdempotencyAdviceTest.java` | 改 | 配置 Bean 移除两个 TTL 覆写 + 类注释补"窗口取生产默认"；删除 `结果过期后同键重新执行` 一例（迁 #2）与随之不用的 `RESULT_EXPIRE_SECONDS` 常量（`PROCESSING_TTL` 同删——主类不再需要 1s 常量）；**其余 14 例断言原文零改动** |
| 2 | `common/idempotency/src/test/java/com/ctds/common/idempotency/IdempotencyAdviceExpiryTest.java` | 新 | 自含配置（1s/1s 显式覆写）+ 最小 `DemoService`（仅 `submit` 计数）+ 迁移测试一例；类注释写明"与主类对照：本类验窗口过期重执行（短窗口 + over-wait sleep，方向稳健）" |
| 3 | `services/gateway-service/src/test/java/com/ctds/gateway/GatewayDeliveryChainIntegrationTest.java` | 改 | 新增 2 个有界等待助手（§2.2-1）+ 改造 5 处计数断言（§2.2-2）+ 类注释补"产事件测试必先等待"不变式；**既有断言语义零删改** |
| 4 | `scripts/gates/run-gates.ps1` | 改 | 新增 `Stop-DetachedStageProcesses`（§2.3）+ `Stop-ProcessTree` 内挂接；头注 record 段新增 DB-46/DB-45 条目（四件套之一） |
| 5 | `scripts/gates/selftest.ps1` | 改 | 新增 **S18** 场景（§5-3 契约）+ 头注场景清单补登；**既有 148 断言零删改** |
| 6 | `scripts/gates/README.md` | 改 | 变更留痕新增条目；**L95"已知特性"勘正**（"可能漏网（罕见）"→ 已实证发生 + 本卡已兑现 surefire 形态兜底 + 非 surefire 形态为诚实边界）；场景表补 S18 |
| 7 | `docs/adr/ADR-018-质量度量门禁接入.md` | 改 | 修订记录 **V1.9**（DB-45 兜底清扫决策 + 强度申辩：判定阈值/阶段范围/扫描面/五态/退出码零改动 + 诚实边界 + config 零改动自证） |
| 8 | `scripts/gates/gates-config.json` | **不改** | ConfigSha256 `315264e1…1304` 逐字不变 = 零放水自证（C4/C5 的 V1.12 变更批归卡 2b） |
| 9 | 任务卡 / 台账 / 日志 | 文档 | §四状态行 / §五体量实测回填；台账三行；日志按附录 G |

## 4. 边界与异常行为

1. **DB-46 修复不放宽断言**：三例脆性断言的断言原文一字不动（`isEqualTo(1)` / `assertEquals("result-1-A", second)` 等）——改的只是"窗口假设的稳健性"；若编码中发现"必须放松断言才能绿"→ 停止上报（红线 4）；
2. **过期测试的 sleep 方向**：`Thread.sleep(TTL + 200)` 只会多等不会少等——负载下更慢也只使过期更确定，无反向脆性；
3. **⑩ 等待不是放宽**：截止期（5s）到点未达预期计数 → 断言失败（红）；助手只吸收"续段尚未执行"的调度窗口；
4. **DB-45 清扫不影响判定**：清扫任何失败（CIM 不可用 / 进程已退 / 权限不足）静默 swallow；超时段结论（FAIL + 退出码 1）由既有逻辑决定，与清扫结果解耦；
5. **清扫不误伤**：双特征（RepoRoot + surefire）+ 自身/祖先链排除；fixture 运行（`-RepoRoot` = fixture 目录）天然隔离——真实门禁运行期间 selftest 先行（gateSelfTest 为全阶段之首），彼时无真实构建进程在跑；
6. **并发处理中测试（:199）的行为保持**：60s 处理窗口下，首请求业务阻塞于 release latch、二次调用得 `IDEMPOTENCY_IN_PROGRESS`——与原断言一致，且消除"1s 窗口下首请求标记先过期"的潜在脆性（⑨ 核对清单注明）；
7. **零新增门禁阶段 / 零改触发模型 / 零新依赖**；报告五态与退出码语义零改动。

## 5. 测试与验证计划（测试先行，红相先行归档）

1. **红相（先红，归档 `build-output/db4645-red-phase-*.log`）**：
   - **S18 对未修运行器先红**（本卡唯一可确定性复现的红）：按 §5-3 构造 fixture 跑旧 `run-gates.ps1` → "脱缰幸存进程被清"断言失败（幸存进程仍在）→ 归档；**若旧实现下该进程并未幸存（脱缰未复现）→ 探针无效，停止上报修订探针构造**（不得伪造红相）；
   - **DB-46 / ⑩ 的红 = 历史实证存档**（两次门禁报告 `gate-report-20261016-114426` / `gate-report-20261017-193026` + CI run `39038393112`，已在库）+ 编码前连跑取证尝试（`mvn -pl common/idempotency test` 与网关测试类连跑 5 次，记录是否复现——竞态不保证复现，以历史证据 + 修复后稳定性为据，**不强造红相**）；
2. **修复后模块级验证**：`mvn -pl common/idempotency test` **连跑 3 次全绿**（含新过期类）+ `mvn -pl services/gateway-service test`（或等效模块命令）**连跑 5 次全绿**（34 例）+ checkstyle 0；
3. **selftest 全量**：S18 正反双向（幸存 stub 被清 / 无关 bystander 不误伤 + selftest 收尾兜杀不留孤儿）+ 全量 **148 + N 断言 0 失败**（十九 → 二十场景，断言只增不减）+ 双解释器（Windows PowerShell 5.1 与 `pwsh`）语法 0 错误；
4. **全仓门禁**：单发干净重跑 → GREEN（报告归档；新红项逐项定性）+ **ConfigSha256 与基线逐字一致**（`315264e1…1304` = config 零改动自证）；
5. **⑨ 窗口核对清单**：按 §2.1-4 逐例核对结论登任务卡 §四（本项无独立测试，是设计符合性证据）。

## 6. 变更流程留痕（红线 3，四件套）

| 件 | 载体 | 内容 |
| --- | --- | --- |
| ① | `run-gates.ps1` 头注 record 段 | DB-46/DB-45 条目（日期 / 依据卡号 / 改动摘要 / 判定零改动声明） |
| ② | `scripts/gates/README.md` | 变更留痕条目 + 场景表 S18 + **L95 已知特性勘正** |
| ③ | `docs/adr/ADR-018` 修订记录 V1.9 | 决策五条款式（背景 / 决策 / 理由 / 备选 / 业务影响说明 / 影响范围 / 可替换性） |
| ④ | `gates-config.json` **零改动** | ConfigSha256 `315264e1…1304` 逐字一致（哈希链五处外部登记点不变） |

## 7. 前置检查（编码段第一动作）

1. `git status` 干净 + 分支 = `feat/DB-46-DB-45-门禁可信性`（自 `f99980d`）；`main == origin/main` 未动；
2. 工具链就绪（JDK 17 / Maven 3.9 / Node / pwsh 与 PS 5.1 双解释器）；
3. 无遗留门禁/测试进程（`java` / `node` 常驻核查，沿 3.4.6 复跑前置先例）；
4. 红相归档目录 `build-output/` 就绪（gitignored，不入库）；
5. S18 fixture 构造所需的 selftest 既有助手（`New-Fixture` / `New-FixtureConfig` / `Invoke-Runner -detached` / `Assert-*`）已实读确认。

## 8. 交付门禁（关闭条件映射）

| 关闭条件 | 承载 |
| --- | --- |
| ① A 组四项闭环 | §5-2/3/4/5 取证 + ⑨ 核对清单（任务卡 §四） |
| ② B/C 移交卡 2b | 任务卡 §六 + 台账"下一包"行 |
| ③ selftest 全绿只增不减 | §5-3 |
| ④ 受影响测试只增不减 + 全仓门禁 GREEN | §5-2/4 |
| ⑤ 变更流程四件套 | §6 |
| ⑥ 4 视角评审 + 独立复审 + 编排师验收 | 收口段 |
| ⑦ 三剧本无需更新 | 任务卡 §四登记说明 |

---

> **确认记录（2026-10-17）**：本文件随编排师"确认"回执批提交，V1.0 即时生效；编码归新会话冷启动（第一动作 = §7 前置检查 + §5-1 红相先行）。
