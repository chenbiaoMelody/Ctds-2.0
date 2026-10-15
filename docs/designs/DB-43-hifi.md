# DB-43 高保真设计（编码契约）：门禁可观测性增强（V1.0 已确认）

> 任务卡：`docs/tasks/DB-43-门禁可观测性增强-2026-10-13.md`；低保真：`docs/designs/DB-43-lofi.md`
> 状态：**V1.0 已确认（2026-10-14 编排师一次确认回执：Q1/Q2 均采建议口径 A + D1 不拆分）**——编码契约即时生效（编码归新会话冷启动）

## §1 现状盘点（逐点实证，对象 = 梢 `9961259`；行号 = 当前 `run-gates.ps1`，690 行）

### 1.1 ① mvn 三段（compile / lint / unitTest）与退出码

- 循环骨架：L261~L319（`foreach ($stageName in @("compile", "lint", "unitTest"))`）；
- 命令形态：`cmd.exe /c mvn -B -ntp <goals>`，工作目录 = 仓库根（L288-294）；超时预算经 `stages.<name>.timeoutSeconds` 覆盖、默认 600s（L295-301，V1.5 留痕）；
- 结果三分支（L305-317）：超时 → FAIL "timed out"（**不落盘、不读流**）；exit 0 → PASS 报告行 = `mvn <goals> exit 0`（**含退出码 ✓**）；exit≠0 → FAIL 报告行 = `mvn <goals> exit N: <tail6>`，**且写 `%TEMP%\ctds-gate-<stage>-out.log`**（L315：仅 stdout+stderr 原文，**无命令/退出码/耗时摘要行**）；
- **缺口判定**：成功态零原始证据（WBS-3.4.7 复审④"0 字节日志"教训的实质）；失败态日志无机器可核摘要行；超时态零证据。
- 核查留痕：L285/L308-317 自 `b93aefbf`（2026-09-06 初次接入）未变（本卡 blame 实证）；台账 DB-43 行"报告面缺机器可核退出码"表述与实际不符——**报告面自始含 exit 码，真实缺口在"日志（原始输出）面"**，本卡按实证勘正（勘正记录入任务卡）。

### 1.2 ② 前后端同形文案双源

- 前端真源清单：`frontend/src/constants/contract.ts:110-119` 共 8 条 TIP（`TEMPLATE_NOT_ACCESSIBLE_TIP` / `TEMPLATE_MAINTAIN_FORBIDDEN_TIP` / `CONTRACT_NOT_ACCESSIBLE_TIP` / `PRODUCT_NOT_DEALABLE_TIP` / `TEMPLATE_NOT_INITIABLE_TIP` / `ADMISSION_REQUIRED_BROWSE_TIP` / `ADMISSION_REQUIRED_INITIATE_TIP` / `ADMISSION_REQUIRED_MAINTAIN_TIP`）；
- 后端真源：`services/contract-service/src/main/java/com/ctds/contract/domain/ContractErrorCodes.java` 对应 8 条 MESSAGE（L16 / L20 / L25 / L27 / L29 / L61 / L67 / L82——逐字映射见 §2.3 表）；**后端为文案真源**（该文件 Javadoc："对外文案为服务端常量"）；
- 现状守卫：`frontend/src/views/contract/ContractSourceGuard.spec.ts` "同形提示守卫"节（L147-185）——8 条断言比对的是**测试内硬编码的第三份字面量**（L149-156）+ 页面散写反向探针（L164-177）；**不读取后端源文件**；
- 盘点实证：全仓检索确认**无"前端测试读后端源 / 后端测试读前端源"的先例**（前端 `node:fs` 零使用；contract 测试零 frontend 引用）——本卡 ② 将引入跨端源码读取（单仓库固定相对结构，风险受控，见 §3-6）；
- 映射实测（本卡盘点逐字比对）：8 对文案当前**逐字一致**（可作为守卫落地时的初始绿基线）。

### 1.3 ③ npm / metric 段失败落盘

| 段 | 位置 | 现状失败处理 | 原始输出 |
| --- | --- | --- | --- |
| frontendTypeCheck / frontendLint / frontendTest / frontendE2E | L323-376 | 超时 L364-366（不读流）；失败 L369-375（tail6 入报告） | **零落盘** |
| coverage | L405-468 | 超时 L419-420 / exit≠0 L421-423（tail5）；exit 0 无报告 L427-428；阈值不达标 L463 | **零落盘** |
| mutationTest | L470-542 | bundle 失败 L489-490（ERROR）；子运行失败聚合 `$badMods`（L517-533）；段结果 L536-538 | **零落盘** |
| sast | L544-612 | 超时 L582-583；exit≠0 L584-586（tail4）；JSON 不可解析 L589-590；scan errors L594-599；findings>0 L602-605 | **零落盘** |

- 通用辅助 `Invoke-MetricProcess`（L380-400）：超时分支（L394-397）返回前**丢弃已读流**（Output 仅"timed out"说明）——须一并修复（§2.5）。
- 实证损失案例：DB-44 首跑 RED 的 `frontendTest` 偶发失败"错误正文未取得"（2026-10-13，`gate-report-20261013-220932.md`）。

### 1.4 报告与自检基线

- 报告五态语义（PASS/FAIL/SKIP/ERROR/PENDING）与退出码 0/1/2 优先级（RED > ERROR）**本卡零改动**；
- `selftest.ps1`：12 场景（S1~S11b）、70 断言；stub 机制先例 = S10（stub typecheck）/ S11b（stub mvn exit 3）；fixture 配置由真实 `gates-config.json` 派生；
- 最近门禁 GREEN 报告：`gate-report-20261013-223506.md`（ConfigSha256 `29782aad…42c9`，PASS=14 / FAIL=0）。

## §2 目标形态（改动设计）

### 2.1 统一落盘 helper（新增，置于 `Invoke-MetricProcess` 定义附近）

```powershell
# DB-43: 阶段原始输出落盘（失败/超时取证；mvn 三段另含成功态——① WBS-3.4.7"0 字节日志"教训）
function Write-StageOutLog([string]$stage, [string]$command, [string]$body, [int]$exitCode, [bool]$timedOut, [int]$elapsedSec) {
    $path = Join-Path $env:TEMP ("ctds-gate-" + $stage + "-out.log")
    $head = "# ctds-gate stage output" + [Environment]::NewLine
        + ("stage: " + $stage) + [Environment]::NewLine
        + ("command: " + $command) + [Environment]::NewLine
        + ("exitCode: " + $exitCode + $(if ($timedOut) { " (TIMED OUT)" } else { "" })) + [Environment]::NewLine
        + ("elapsedSec: " + $elapsedSec) + [Environment]::NewLine
        + ("time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")) + [Environment]::NewLine
        + (([string][char]0x2500) * 40) + [Environment]::NewLine          # 分隔线（可用 '----' 等价，编码段定稿）
    [System.IO.File]::WriteAllText($path, ($head + $body))
    return $path
}
```

- 命名沿用既有 `%TEMP%\ctds-gate-<stage>-out.log`（固定名 = "最近一次该阶段运行"覆盖式记录，沿惯例零新面；Q2-B 若不采则改 `build-output/`）；
- 摘要行字段：`stage / command / exitCode（超时标注）/ elapsedSec / time`——"0 字节日志"因摘要头而**结构上不可能**（即使 body 为空，头部即有 exitCode 行）；
- 所有段落盘统一走本 helper（含改造既有 maven 段 L315 的直接 `WriteAllText`）。

### 2.2 ① mvn 三段三态落盘（L305-317 改造）

1. 记 `$sw = [System.Diagnostics.Stopwatch]::StartNew()`（进程启动前）；记 `$cmdLine = "mvn " + $toolMavenArgs + " " + $s.goals`；
2. 超时分支：`Stop-ProcessTree $proc`（§2.6，视 Q1）→ `$outTask.Wait(5000)`、`$errTask.Wait(5000)` → `$p = Write-StageOutLog $stageName $cmdLine ($outTask.Result + $errTask.Result) -1 $true $sw.Elapsed.TotalSeconds` → FAIL 行尾附 `" | full output: " + $p`；
3. PASS 分支：`$outTask.Wait(10000)`、`$errTask.Wait(10000)` → **落盘**（exitCode=0，timedOut=$false）→ PASS 报告行**保持原文** `mvn <goals> exit 0`（不加路径——成功态的可发现性由固定命名 + README 说明承载，最小改动）；
4. FAIL 分支：落盘（exitCode=$proc.ExitCode）→ FAIL 行 = 原文 tail6 + `" | full output: " + $p`；
5. 步骤 2~4 包裹 try/catch：**落盘失败绝不影响段判定**（catch 后照常 Add-Result；落盘失败不另记 ERROR——避免"记录失败"污染质量结论；如发生，编码段评估是否加 `runner` 注记，默认不加）。

### 2.3 ② 前端双源守卫升级（`ContractSourceGuard.spec.ts` **新增** describe，既有断言零删改）

新增块（文件末节追加）：

```ts
// ---- 同形文案双源守卫（DB-43：真读后端真源，消灭"人工比对"依赖）----
import { readFileSync } from 'node:fs'
const BACKEND_SOURCE = new URL(
  '../../../../services/contract-service/src/main/java/com/ctds/contract/domain/ContractErrorCodes.java',
  import.meta.url)
const TIP_TO_MESSAGE: Array<[string, string]> = [
  ['TEMPLATE_NOT_ACCESSIBLE_TIP', 'TEMPLATE_NOT_FOUND_OR_UNAVAILABLE_MESSAGE'],
  ['TEMPLATE_MAINTAIN_FORBIDDEN_TIP', 'TEMPLATE_FORBIDDEN_MESSAGE'],
  ['ADMISSION_REQUIRED_BROWSE_TIP', 'BROWSE_ADMISSION_REQUIRED_MESSAGE'],
  ['ADMISSION_REQUIRED_INITIATE_TIP', 'INITIATE_ADMISSION_REQUIRED_MESSAGE'],
  ['ADMISSION_REQUIRED_MAINTAIN_TIP', 'MAINTAIN_ADMISSION_REQUIRED_MESSAGE'],
  ['CONTRACT_NOT_ACCESSIBLE_TIP', 'CONTRACT_NOT_VISIBLE_MESSAGE'],
  ['PRODUCT_NOT_DEALABLE_TIP', 'PRODUCT_NOT_AVAILABLE_FOR_DEAL_MESSAGE'],
  ['TEMPLATE_NOT_INITIABLE_TIP', 'TEMPLATE_NOT_AVAILABLE_FOR_INITIATION_MESSAGE'],
]
```

- 解析（纯函数，供探针复用）：TS 侧 `export const (\w+_TIP) = '([^']*)'`；Java 侧 `public static final String (\w+_MESSAGE) = "([^"]*)";`；
- 断言组：
  1. 解析非空锚：后端源码解析出的 MESSAGE 条目**必须包含映射表全部 8 个名字**（防解析失效/常量改名后静默空转）；
  2. 双源逐字相等：8 对 TIP 值 === MESSAGE 值，失败信息给出"常量名 / 后端真源值 / 前端副本值"；
  3. 映射完备锚：`TIP_TO_MESSAGE.length === 8`（防悄悄删映射；**新增同形文案必须同步扩表**——扩表规则写入本设计 + 任务卡映射表）；
  4. 反向探针（防恒红/恒绿）：比对逻辑抽为纯函数 `diffTips(tips, messages, mapping)`——喂"故意漂移样本"必产出差异（非空转）；喂"一致样本"必产出空；
- 既有 L147-185 全部保留（零删改）；本块新增 ~45 行；**不引入任何第三方依赖**（`node:fs` 为 Node 内置；vitest 环境可用，盘点已确认前端 spec 现有 `?raw` 机制不覆盖项目外文件，故用 fs + `import.meta.url`（不依赖 cwd））；
- 语义边界：只守 **8 条同形文案**；`SIMULATION_HONESTY_TIP` / `EXECUTION_DEMO_TIP`（诚实边界）与 `OPERATOR_MODE_PARTICIPANT_TIP` 为**纯界面文案（后端无对应常量）**，不在本守卫范围（保留既有字面量断言）；
- 扩表规则（给后续卡的行为约定）：后端新增"同形对外文案"且前端建副本时，须同步在 `TIP_TO_MESSAGE` 加行——否则"副本 vs 真源"无守卫；该规则在 README/本设计留痕（非机械强制，属评审检查点）。

### 2.4 ③ npm 段落盘（L323-376 改造）

1. 超时分支（L364-366）：`Stop-ProcessTree`（视 Q1）→ 等流 → `Write-StageOutLog <stage> ("npm " + $s.goals) (out+err) -1 $true $elapsed` → FAIL 行尾附 `" | full output: " + $p`；
2. 失败分支（L369-375）：等流 → 落盘（exitCode）→ FAIL 行 = 原文 tail6 + `" | full output: " + $p`；
3. PASS 分支不动（成功态不落盘——③ 的处置要求原文 = "失败时落盘原始输出"）；
4. 落盘文件名 stage 名 = 四个 npm 阶段名（各自独立文件），与 maven 段命名规则一致。

### 2.5 ③ metric 段落盘（coverage / mutationTest / sast）+ `Invoke-MetricProcess` 超时流修复

1. **`Invoke-MetricProcess` 超时分支（L394-397）修复**：kill（视 Q1 走 `Stop-ProcessTree`）→ `$outTask.Wait(5000)` / `$errTask.Wait(5000)` → `return @{ ExitCode = -1; Output = ("<timed out after " + $timeoutSec + "s>") + [Environment]::NewLine + $outTask.Result + $errTask.Result }`——超时也保有已产出的原始输出；
2. **coverage**：凡该段判定 FAIL（超时 / exit≠0 / 无报告 / 阈值不达标）或 ERROR → 落盘 `$r.Output` → 段内报告行尾附 `" | full output: " + $p`（各分支统一一处调用，路径入该段结果行）；
3. **mutationTest**：
   - 新增 `$mutLogs = @()`；在① bundle 组装失败（L489-490）② classpath 生成失败（L510）③ 子运行超时（L517-518）④ 无突变报告（L520-523）四处把 `$r.Output` 追加进 `$mutLogs`；
   - 段末（L536-538）当段结果为 FAIL/ERROR 或 `$mutLogs` 非空 → 汇总落盘 `%TEMP%\ctds-gate-mutationTest-out.log`（拼接带 `==== <子运行名> ====` 分隔）→ 报告行尾附路径；
   - 阈值不达标失败（L533）**无原始输出可落**（PIT 报告 JSON/XML 本身即产物）——此分支只附"报告产物位于各模块 `target/pit-reports/`"的文字说明（诚实：该形态不需要额外落盘）；
4. **sast**：凡该段判定 FAIL/ERROR（含超时、exit≠0、JSON 不可解析、scan errors、findings>0）→ 落盘 `$r.Output` → 报告行尾附路径；`scanTemp` 清理逻辑（L609）不动（落盘的是进程输出，非扫描副本）。

### 2.6 超时后清理进程树（**视 Q1**——采 A 则纳入本卡）

- 新增 helper：

```powershell
# DB-43: 阶段超时后清理整个进程树（跟踪-23 剩余子项；Windows 定位脚本，taskkill /T 为系统内置）
function Stop-ProcessTree([System.Diagnostics.Process]$proc) {
    try { $null = & taskkill /PID $proc.Id /T /F 2>&1 } catch { }
    try { if (-not $proc.HasExited) { $proc.Kill() } } catch { }
}
```

- 替换三处 `try { $proc.Kill() } catch { }`：mvn 段（L306）/ npm 段（L365）/ `Invoke-MetricProcess`（L395）；
- 背景：2026-10-09 实测"unitTest 超时被杀后残留 cmd 孤儿进程"污染 coverage/frontendTest（跟踪-23 ① ② 两条实证）；
- 边界（诚实登记）：taskkill `/T` 按父子快照清理，"已脱离父链的孙进程"可能漏网（罕见）；本机与未来 CI 均 Windows（脚本本身 Windows 定位）——若未来迁 Linux 须同步改（登记于 README 变更条目）；实测若发现 fixture 不稳定则如实降级登记（不硬上，见 §4.1-S14）。

### 2.7 报告与既有行为不变量（硬约束）

| 不变量 | 约束 |
| --- | --- |
| 判定强度 | 阈值六项 / 阶段启停 / 扫描面 / 五态语义 / 退出码 0-1-2 优先级 **零改动** |
| 报告头 | RunLabel / Scenario / ExitCode / ConfigPath / ConfigSha256 / Time / Repo **零改动** |
| 报告行格式 | 仅"失败/超时行尾部允许追加 `\| full output: <path>`"；成功行与 PENDING/SKIP 行格式零改动 |
| 配置 | `gates-config.json` **零改动**（不加阶段/不改值）——ConfigSha256 应与基线逐字一致（"零放水"自证） |
| 命令执行面 | mvn/npm 命令拼装、allowlist、工作目录、超时预算语义零改动 |
| 业务面 | 零 Java/Python 主源集、零前端实现文件、零依赖、零迁移 |

## §3 边界与异常

1. **落盘失败不影响判定**：helper 调用处 try/catch 包裹，任何落盘异常不得改变段 verdict / 报告产出 / 退出码（"记录是加菜，不是判定"）；
2. 报告行长度：失败行追加路径后可达 ~300 字符（表格行无长度限制，既有 tail6 行已 ~200 字符，接受）；
3. 同名文件覆盖语义：`%TEMP%\ctds-gate-<stage>-out.log` = **最近一次该阶段运行**的记录（含 selftest fixture 运行——**已知特性**，写入 README 说明；§4.1 场景结束时清理 fixture 产物）；
4. 字符编码：mvn 段（Java 工具链）沿既有平台编码口径不变；npm/metric 段本就 UTF-8 解码（L355-359 / L388-390），落盘继承解码后文本；
5. `elapsedSec` 为整秒（`[int]` 截断）——够用（取证目的是"跑了多久"量级），避免浮点格式化噪音；
6. ② 的跨端读取脆弱性：单仓库固定相对结构（`frontend/` 与 `services/` 同级），用 `import.meta.url` 消除 cwd 依赖；若未来目录结构变更，该 spec 会**红**（这正是守卫目的——红灯即提示同步调整，不静默）；解析正则只认规整形态（checkstyle 强制 `public static final String X_MESSAGE = "...";` 单行形态）；
7. ② 不覆盖的诚实面：映射表是"人工维护的 8 对清单"——后端**新增**同形文案且前端建副本时，若忘记扩表则不红（§2.3 扩表规则为约定 + 评审检查点；完全机械化的"全自动发现"需后端导出清单，超出本卡最小范围，登记为观察项）；
8. sast findings 分支落盘的 JSON 体积（数百 KB 级）可接受（%TEMP%，无入库）。

## §4 测试与验证计划（编码段执行）

### 4.1 `selftest.ps1` 场景增补（强度只增不减；先红后绿）

| 场景 | 注入 | 断言 |
| --- | --- | --- |
| **S12（新增）mvn 成功态落盘** | fixture stub mvn exit 0 + 标记输出 | `%TEMP%\ctds-gate-compile-out.log` 存在；含 `stage: compile`、`exitCode: 0`、stub 标记文本；报告 `compile | PASS` 行**原格式**（不含路径）；场景末清理该文件 |
| **S13（新增）npm 失败落盘取证** | fixture frontend/package.json + stub npm exit 1 + 标记输出（沿 S10 stub 手法） | out.log 存在；含 `exitCode: 1` 与标记输出（**"错误正文可取得"**——DB-44 损失场景的正面锚）；报告 FAIL 行含 `full output:`；场景末清理 |
| **S14（新增，视 Q1）超时进程树清理** | fixture stub：启动子进程（写 PID 入文件）后自身挂起；配置该阶段 `timeoutSeconds=2` | 报告 FAIL "timed out" 行；out.log 存在且含 `(TIMED OUT)`；**子进程 PID 已不存在**（`Get-Process -Id` 判无）；若实证 fixture 不稳定 → 降级为"代码级审查 + 手工证据"并在任务卡如实登记 |

- 场景数 12 → 14（或 15）；既有 12 场景断言零删改（"强度只增不减"）；
- 执行：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\selftest.ps1` → 全绿。

### 4.2 前端双源守卫（②）测试先行

- 红相先行：先加 TIP_TO_MESSAGE 映射与断言（此时若故意把某条 TIP 改假 / 或映射指向不存在常量 → 断言红，红相归档 `build-output/db43-red-*.log`）→ 还原 → 绿；
- 反向探针（§2.3-4）证明非空转；
- 运行面：`npm run test -- src/views/contract/ContractSourceGuard.spec.ts`（定向）+ 全仓 `npm run test`；
- 用例数只增不减（当前基线 **436 例**）。

### 4.3 全仓门禁与行为实证

1. 全仓 `run-gates.ps1` 复跑 → GREEN；**ConfigSha256 与基线 `29782aad…42c9` 逐字一致**（零配置改动自证）；
2. 实证检查（跑完后现场）：`%TEMP%\ctds-gate-compile-out.log` / `-lint-out.log` / `-unitTest-out.log` 三份存在且**摘要行含 exitCode: 0**（① 的成功态证据正面锚）；
3. 失败取证面由 S13（npm）+ selftest fixture 承载（不在真实仓库制造失败）；
4. 报告 diff 检查：失败行格式变化符合 §2.7（PASS 行零变化）；
5. `run-gates.ps1` 与 `selftest.ps1` 的语法自检：`powershell -NoProfile -Command "<script> -WhatIf"` 不可用（脚本无 -WhatIf）——以 selftest 全绿 + 一次真实门禁为语法证据。

### 4.4 评审与验收

- 4 视角评审（单发串行只读子智能体，对象 = `git diff`；评审卡 01~04）——**评审①重点**：与台账三项处置口径逐条符合（含 Q1 范围裁定）；**评审②重点**：`taskkill /T` 供应链面（系统内置零新依赖）+ `%TEMP%` 写入面；**评审④重点**：selftest 新场景非空转（fixture 真注入）、既有断言零删改。

## §5 变更流程留痕清单（红线 3，必做）

| # | 载体 | 内容 |
| --- | --- | --- |
| 1 | `run-gates.ps1` 头部记录段 | 追加 "DB-43（<编码批日期>，门禁变更流程留痕，config 零改动）record: ① mvn 三段三态落盘+摘要行 ② npm/metric 段落盘 ③（视 Q1）超时进程树清理——判定阈值/阶段范围/扫描面零改动" |
| 2 | `scripts/gates/README.md` 变更留痕条目 | 沿 DB-18 先例格式（"**DB-43（日期，门禁变更流程留痕，配置零改动）**：…"）+ 运行说明补"out.log = 最近一次运行记录（含 selftest）" |
| 3 | `ADR-018` 修订记录 **V1.3** | 增强项 + 依据（WBS-3.4.7 遗留 / round2 ③ / DB-44 红灯）+ "配置零改动 / 强度只增不减 / 跟踪-23 剩余（视 Q1）兑现" |
| 4 | `gates-config.json` | **零改动**（不 bump；sha256 不变为自证）——不写 changeLog 条目，理由在 README/ADR 留痕 |
| 5 | 任务卡 §五 + 开发日志 | 变更执行留痕 |
| 6 | **E4 追认补记（2026-10-15）** | `scripts/gates/README.md` 场景表补登 S10~S11b（编码批实施澄清 E4）——原属本 §5 载体清单**外**的文档补登行为，**2026-10-15 编排师追认**（会话回复"1、追认"）后由文档侧补记本行（纯文档零行为；任务卡 §七 E4 行同步登记） |

## §6 前置检查（编码段第一动作，逐项实测留痕）

1. git 基线：分支梢 = 确认批提交（已推 origin）；`main` 零触碰；工作树干净；
2. 门禁基线：最近 GREEN 报告与其 ConfigSha256 记录在案（`gate-report-20261013-223506.md`）；
3. **`selftest.ps1` 基线复跑**：12 场景 70 断言全绿（升级前的绿基线）；
4. 前端基线复跑：全仓 `npm run test` **436 例全绿** + typecheck/lint/build EXIT=0；
5. `run-gates.ps1` 行号复核：本设计引用的行号（L261-319 / L323-376 / L380-400 / L405-612 / L690）与实读一致（漂移则同步修正设计引用）；
6. `%TEMP%\ctds-gate-*.log` 现状快照（记录既有文件时间戳，供"新行为"对比）。

## §7 交付门禁与关闭条件映射

| 关闭条件（任务卡 §关闭条件） | 证据 |
| --- | --- |
| ① 台账三项逐条落实（+Q1 范围裁定） | §2.2/2.3/2.4/2.5/2.6 与台账映射表（任务卡 §三）回填 |
| ② selftest 全绿（含新增场景） | `selftest.ps1` 输出 + 断言计数只增不减 |
| ③ 前端用例只增不减 + typecheck/lint/build 全绿 | 436 → 436+n；EXIT=0 日志 |
| ④ 全仓门禁 GREEN + ConfigSha256 逐字一致 | 报告路径 + sha 对比 |
| ⑤ ② 红相先行 + 反向探针 + 既有断言零删改 | 红相归档 + spec diff |
| ⑥ 变更流程留痕四项齐备（§5） | 脚本头注 / README / ADR-018 V1.3 / config 零改动自证 |
| ⑦ 4 视角评审通过 + 修复批复审 + 编排师验收 | 评审段日志 / 任务卡 §四 |

---

> **确认记录（2026-10-14 编排师一次确认回执：Q1 / Q2 + D1）**：**Q1 采 A**——跟踪-23 剩余子项（阶段超时后清理子进程树加固）随本卡处置（§2.6 `Stop-ProcessTree` / taskkill `/T /F` 纳入，替换三处 `$proc.Kill()`；selftest S14 超时进程树清理探针纳入，不稳定则如实降级登记）；**跟踪-15 由 ① 兑现**（§2.2 即为兑现载体）；跟踪-16 / 25 维持沿续。**Q2 采 A**——③ 落盘位置 = `%TEMP%\ctds-gate-<阶段>-out.log`（§2.1 固定命名；Q2-B 分支作废）。**D1 不拆分**（±250 行）。**本文件 V0.9 → V1.0（编码契约即时生效；正文零改动，确认记录签署于本末节）**；编码归新会话冷启动，第一动作 = §6 前置检查六项逐项实测留痕，测试先行（§4.1 先红归档 + §4.2 红相先行）。

> **E4 追认记录（2026-10-15，DB-43 收口段；编排师会话回复"1、追认"）**：编码批实施澄清 E4（`scripts/gates/README.md` 场景表补登 S10~S11b）属 §5 变更流程留痕载体清单**之外**的文档补登行为；按评审卡 01 第 2 条须先改设计再确认，故列入待裁定项。编排师**予以追认**（纯文档、零行为），本文件 §5 载体表**补记第 6 行**；正文与编码契约语义**零改动**，版本维持 **V1.0**。
