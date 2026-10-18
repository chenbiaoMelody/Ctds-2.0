# 卡 2b 高保真设计：门禁可信性·B/C 组承接清算（V1.0 编码契约）

> 任务卡：`docs/tasks/DB-46-DB-45-门禁可信性-卡2b-2026-10-18.md`
> 方向设计：`docs/designs/DB-46-DB-45-卡2b-lofi.md`（V1.0，2026-10-18 编排师一次确认 Q1~Q5 + D1 全按建议）
> 上游基线：`main == origin/main == bf6e4df`；`gates-config.json` V1.11（ConfigSha256 `315264e1…1304`）；selftest 二十场景 **163 断言**；门禁基线 `gate-report-20261018-150540.md`（GREEN）
> 状态：**V1.0 编码契约（待编排师确认后生效）**——编码段归新会话冷启动，测试先行、红相先行归档
> 范围：23 项（B 组 10 + C 组 8 + 新候选 5），逐项处置见 §3；**零业务代码**（N2 仅测试代码）、**零新依赖**、**判定阈值/阶段范围/扫描面/五态与退出码语义零改动**

---

## 1. 编码前事实盘点（2026-10-18，分支 `feat/DB-46-DB-45-门禁可信性-卡2b` 实测）

| 面 | 事实 | 位置/证据 |
| --- | --- | --- |
| 配置基线 | V1.11；19 个阶段键齐全；`gateSelfTest.note` 仍写"十九场景 148 断言"，缺 V3.3 的 125 断言/58s 第三数据点；`coverage.note` 已用 `OSType` | `scripts/gates/gates-config.json` |
| 运行器 | mvn/npm 两段各持一份内联 `ProcessStartInfo`；`Invoke-MetricProcess` 内 `ExitCode=-1` 兼作超时哨兵；阶段循环对缺段/关闭态静默 `continue`；`gateSelfTest.tool` 无字符白名单；`Stop-DetachedStageProcesses` 只做双特征 + 祖先链排除 | `scripts/gates/run-gates.ps1` |
| selftest | S1~S18（含 S11b/S11c）+ S15 收尾守卫；163 断言；S18 的 fixture 配置误用尚未定义的 `$realJavaHome`（S18 在 S12 之前执行 → 传空）；开场无残留进程清扫 | `scripts/gates/selftest.ps1` |
| workflow | 6 个 action 仍用 tag（`@v4/@v5/@v7`）；`listComments` 已 `per_page: 100`（B5 已满足）；失败证据上传 14 天；仓库为 **PUBLIC** | `.github/workflows/quality-gate.yml`；`gh repo view` 2026-10-18 |
| 网关等待助手 | `awaitEventsOf` / `awaitAllEvents` 有界等待（5s/20ms），到点返回当前快照、由调用方断言失败；无"等不到必红"的机器锚 | `services/gateway-service/src/test/java/com/ctds/gateway/GatewayDeliveryChainIntegrationTest.java` |
| 文档 | README 的 CI 接入节无"承接方显式声明"、无 artifact 暴露面/action SHA/node 白名单登记；V5 selftest 时长数据点缺 34s；`docs/dependencies.md` 无本地工具节 | `scripts/gates/README.md`；`docs/dependencies.md` |

## 2. 目标形态（一句话）

**B 组**：把运行器剩余的四类"静默/歧义"面收敛为机器可见（tool 白名单、超时语义、缺段/关闭态、内联调用）；**C 组**：补齐 CI 降级口径的承接声明与探针（执行层失败不降级 / 大小写 / CI 单形态），并完成 config V1.12 口径批；**N 组**：给误伤面加"运行前快照 + 启动时间窗"排除、给等待助手补"等不到必红"负向锚、给 selftest 补开场自愈清扫与 fixture javaHome 自洽。

## 3. 23 项逐项处置表（项 → 动作 → 文件/位置 → 验证锚 → 登记理由）

> 约定：A = 本卡实现；B = 复核/登记（零行为变更）；C = 已满足（现状核对）。**零遗漏**。

### B 组（DB-07 评审循环 1 登记 10 项）

| # | 项 | 处置 | 动作与文件/位置 | 验证锚 |
| --- | --- | --- | --- | --- |
| B1 | `gateSelfTest.tool` R5 白名单 | **A** | `run-gates.ps1` gateSelfTest 段：读取 tool 后、`Join-Path`/`Test-Path` 前，校验 `^[A-Za-z0-9:._\-\s/\\]+$`；非法 → `gateSelfTest ERROR "Illegal characters ... stage SKIPPED, command NOT executed"` | `selftest.ps1` S15 新增 `illegal` 探针（tool 含 `&`）：exit 2 + ERROR 行 + "NOT executed"；旧运行器该探针失败（红相） |
| B2 | mvn·npm 内联进程调用收敛 | **A** | `run-gates.ps1`：mvn 三段循环与 npm 四段循环改调 `Invoke-MetricProcess`；helper 新增 `-PlatformEncoding`（mvn 段用，保持 GBK/平台解码）、返回 `TimedOut`/`RawOutput`；npm 段保持 UTF-8；**报告行/退出码/超时判定零变更** | selftest 全量 0 失败（S10~S14/S12/S13/S18 覆盖 mvn/npm 成功/失败/超时/日志）；全仓门禁 GREEN；**若实测出现行为差异 → 停报改登记（红线 4 精神）** |
| B3 | WBS-2.2.x 引用改锚 | **A** | 当前锚点改 `DB-07 卡 + ADR-018`：`run-gates.ps1` 头注/阶段注释、`selftest.ps1` 头注、`README` 标题/退出码/自检/扫描范围节；README 顶部加"历史条目 WBS 编号保留原文"说明；**不动 `docs/C-TDS项目总体实施计划与WBS.md`**；config 当前 note 随 V1.12 改锚 | `git diff` 仅门禁文档/脚本；WBS 文件零改动；README 顶部说明可见 |
| B4 | 不留静默关闭强化余部 | **A** | `run-gates.ps1` 配置校验块：19 个已知阶段中，`enabled=false` 且 `status` 非 `PENDING*` → Add-Result `<name>.disabled SKIP "enabled=false without a PENDING status - visible by policy ..."`；不再静默 | 新 **S19-b**：普通 fixture（多阶段关闭且无 status）→ 出现 `frontendTest.disabled | SKIP` + 仍 GREEN/0；真实 config 零新增行（所有关闭段均有 PENDING 状态） |
| B5 | upsert 分页 100 | **C** | 现状已满足：workflow `issues.listComments` 已 `per_page: 100`；本卡零改动 | 静态核对 workflow 该行；README V1.12 留痕条目注明"已满足（DB-07 实现批起）" |
| B6 | 超时哨兵 `-1` 语义区分 | **A** | `run-gates.ps1`：`Invoke-MetricProcess` 超时返回 `TimedOut=$true` + `RawOutput`；所有超时分支改判 `$r.TimedOut`（gateSelfTest/coverage/mutation/sast/docker 探测/mvn/npm）；超时报告行追加 `(timeout sentinel -1 is NOT a process exit code)`；out.log 头保持 `exitCode: -1 (TIMED OUT)` 并追加 `[sentinel: -1 is not a process exit code]` | S14/S15-hang/S18 的 "timed out after 2s" 与 out.log `(TIMED OUT)` 断言仍过；新增断言（S14 或 S15）确认哨兵注记出现在超时行/日志头 |
| B7 | action SHA 钉住 | **A** | `.github/workflows/quality-gate.yml` 六处 `uses:` 改 commit SHA + `# v4/v5/v7` 注释（§4 表）；README 登记核验来源/日期与"手工升级"残余风险 | workflow YAML 结构校验（pyyaml）；`git grep` 无裸 tag `@v[0-9]`；SHA 与 §4 表逐字一致 |
| B8 | artifact 暴露面复核 | **B** | 复核结论：仓库 PUBLIC → `gate-report-*`（默认保留期）与 `gate-evidence-*`（14 天）随公开读权限可见；内容 = 报告/失败日志/fixture 产物，**零 secret**；报告 detail 含绝对路径/失败尾部，不含密钥/业务载荷（secretsScan 命中仅 `content withheld`）。README CI 接入节登记；零行为变更 | README 新增"artifact 暴露面"条目；ADR-018 V1.10 残余风险行；不新增/减少 artifact 步骤 |
| B9 | `docs/dependencies.md` 本地工具节 | **A** | `docs/dependencies.md` 新增"本地开发工具（非项目依赖）"节：JDK 17 / Maven 3.9.16 / Node 24.14（CI 22）/ npm / Git 2.47.1 / PowerShell 5.1 + pwsh 7.6.7 / gh 2.102.0 / semgrep 1.177.0 / Docker Desktop（可选）；注明不入 pom/lock、CI 版本以 workflow 为准 | 文档节存在且逐项可核；不触发依赖变更流程（非项目依赖） |
| B10 | 配置缺段报告可见行 | **A** | `run-gates.ps1` 配置校验块（与 B4 同一块）：`stages` 缺失 → `config.stages ERROR`；单个阶段缺段或缺 `enabled` → `config.stages.<name> ERROR`（退出码 2）；**不再静默 skip** | 新 **S19-a**：fixture 删除 `stages.structureCheck` → `config.stages.structureCheck | ERROR` + exit 2 + 报告照写；真实 config 零新增行 |

### C 组（DB-07 V5 评审登记 8 项 = 台账 DB-46 行①~⑧）

| # | 项 | 处置 | 动作与文件/位置 | 验证锚 |
| --- | --- | --- | --- | --- |
| C1 | README CI 接入节"承接方显式声明" | **A** | README CI 接入节新增：CI coverage 阈值为**降级形态**（SKIP），权威判定 = 有 Linux Docker 的本地门禁；承接方必须保留 `Test-CiEnvironment`/`Test-LinuxDockerDaemon` 双条件、S17 探针与 SKIP 四要素；不得关闭 coverage 段或放宽阈值 | README 条目可逐字核对；S17 探针仍全绿 |
| C2 | 「执行层失败不降级」CI 组合探针 | **A** | `selftest.ps1` S17 新增探针 D：CI 环境 + 无 Linux Docker + stub mvn `exit 3` → coverage **FAIL**（不是 SKIP/DEFERRED），exit 1/RED | S17-D 断言：`| coverage | FAIL |`、`mvn coverage exit 3`、无 `DEFERRED`、RED |
| C3 | README selftest 时长 V5 数据点补秒数 | **A** | README 自检段：`V5 口径批独立单发实测〔148 断言〕` → `34s〔148 断言〕`（附 CI 30s 历史数据点） | README 逐字核对；与日志 `-2050` 证据一致（本地 34s / CI 30s） |
| C4 | `gateSelfTest.note` 第三数据点 + 余量口径 | **A** | `gates-config.json` V1.12：note 补 `58s〔125 断言，V3.3〕`；补本卡后最终数据点；余量明示 `300/58=5.2 倍`、最差 `300/70≈4.3 倍`（历史"7 倍以上"不再成立）；场景/断言计数更新为最终值（S1~S19 + S11b/S11c，<N> 断言） | JSON 可解析；note 数字与 selftest 末行一致；ConfigSha256 新基线外部登记 |
| C5 | OSTYPE/OSType 大小写统一 | **A** | 统一为 **`OSType`**（`docker info --format '{{.OSType}}'` 模板字段名）：README 当前 prose 两处改 `OSType`；config 当前 `coverage.note` 已用 `OSType`，V1.12 changeLog 记录统一口径；历史 changeLog 条目保留原文 | `git grep "OSTYPE"` 仅历史条目命中（或零命中）；README 当前 prose 全部 `OSType` |
| C6 | OSType 比较反向锚 | **A** | `selftest.ps1` S17 新增探针 E：CI + docker stub 输出 `Linux`（大小写混写）+ 45% coverage → **FAIL**（`-ieq` 命中，不降级） | S17-E 断言：coverage FAIL、`overall 45% < 70%`、无 `DEFERRED`、RED |
| C7 | CI 变量单形态 OR 语义锚（可选） | **A** | `selftest.ps1` S17 新增探针 F：仅 `CI=true`（无 `GITHUB_ACTIONS`）+ 无 Docker → SKIP/GREEN；与探针 A（仅 `GITHUB_ACTIONS`）共同锚定 OR 语义 | S17-F 断言：coverage SKIP、`threshold verdict DEFERRED`、GREEN/0 |
| C8 | 报告 detail 对外措辞复核 | **B** | 复核结论：`content withheld` / `full output` / `DEFERRED` 等为内部证据口径；报告 detail 不含密钥/业务载荷；仓库为 PUBLIC、fork PR 评论跳过且令牌只读；README + ADR-018 登记"可见性变化时复核"；零行为变更 | README "artifact 暴露面/报告 detail" 条目；ADR-018 V1.10 残余风险 |

### 新候选 5 项（卡 2a 段登记）

| # | 项 | 处置 | 动作与文件/位置 | 验证锚 |
| --- | --- | --- | --- | --- |
| N1 | 误伤面残余（同仓并行 surefire/jacoco fork 被超时清扫一并强杀） | **A（增强排除 + 残余登记）** | `run-gates.ps1`：运行开始时快照"已存在的双特征 surefire 进程 PID 集"，并记 `$script:runStartedAt`；`Stop-DetachedStageProcesses` 增加排除：① PID 在运行前快照内不杀；② `CreationDate` 早于本次运行开始（-2s 时钟容差）不杀；③ `CreationDate` 为空不杀（无法证明是本轮）；残余 = 本次运行开始后才启动的并行构建仍可能被误杀 → README/ADR-018 登记 | S18 新增"运行前已存在的并行 surefire 形态 bystander"：命令行含 RepoRoot + `\target\surefire\`，运行前启动 → 超时清扫后**必须幸存**（旧运行器该断言失败 = 红相）；命令行有效性锚防恒真 |
| N2 | 等待助手"等不到"负向探针 | **A（测试代码）** | `GatewayDeliveryChainIntegrationTest` 新增一例：无事件时 `assertThatThrownBy(() -> assertThat(awaitAllEvents(1)).hasSize(1)).isInstanceOf(AssertionError.class)`；断言耗时 ∈ [4.5s, 15s]（证明有界等待而非即时快照）+ recorder 为空；**零产品代码** | 新测试例绿灯；反证：临时把 `awaitAllEvents` 改为即时快照 → 耗时断言失败（红相归档后还原） |
| N3 | node bystander 白名单口径 | **B（文档登记）** | README 自检段 + `docs/dependencies.md` 本地工具节：selftest fixture 的 `node` 仅作进程形态替身（S14 孙进程/S18 bystander），与前端测试共用运行时；Node 正式准入仍以 `docs/04-AI工具白名单-评估与发布.md` §2.3 为准（白名单维护责任在安全合规专员，本卡不修改白名单）；零新工具 | README/dependencies 条目可核；白名单文件零改动 |
| N4 | S18 fixture `javaHome` 为空 | **A** | `selftest.ps1` S18：`New-FixtureConfig ... $true $realJavaHome18 $null`（原为尚未定义的 `$realJavaHome`） | S18 在无 `JAVA_HOME` 且 config 有 javaHome 的机器上 fixture 配置含真实 javaHome；javac 探针有效；S18 全绿 |
| N5 | selftest 被外部中断遗留孙进程无兜底 | **A（开场自愈清扫）** | `selftest.ps1` 开场（workDir 创建后、场景前）新增 `Remove-StaleSelftestProcesses`：命令行含 `ctds-gates-selftest-` 且含 `\target\surefire\`，祖先链排除，best-effort 强杀并输出清理计数；并发 selftest 不受支持（诚实边界） | 一次性探针：预置一个匹配双特征的假 stale 进程 → 跑 selftest → 进程被清（旧 selftest 下幸存 = 红相）；日志含清理计数；不误伤 S18 运行前并行 bystander（S18 新锚） |

## 4. B7 action 版本指纹（2026-10-18 官方双源核验）

核验方法：`git ls-remote https://github.com/<repo> refs/tags/<tag>` 与 `gh api repos/<repo>/git/ref/tags/<tag>` 均返回同一 commit；`gh api repos/<repo>/commits/<sha>` 确认存在。**升级须重新核验并走变更流程**；未引入 Dependabot/Renovate（手工升级为残余风险，README 登记）。

| 用途 | 现 tag | 钉住 SHA | 核验 |
| --- | --- | --- | --- |
| checkout | `actions/checkout@v4` | `11d5960a326750d5838078e36cf38b85af677262` | 双源一致（2026-10-18） |
| setup-java | `actions/setup-java@v4` | `cf277c60eb25467037889841efdb72551f06f6c3` | 同上 |
| setup-node | `actions/setup-node@v4` | `49933ea5288caeca8642d1e84afbd3f7d6820020` | 同上 |
| setup-python | `actions/setup-python@v5` | `a26af69be951a213d495a4c3e4e4022e16d87065` | 同上 |
| upload-artifact（×2） | `actions/upload-artifact@v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | 同上 |
| github-script | `actions/github-script@v7` | `f28e40c7f34bde8b3046d885e986cb6290c5673b` | 同上 |

workflow 写法：`uses: actions/checkout@<sha> # v4`（保留 tag 注释便于人工识别）。B5 现状核对：`listComments` 已 `per_page: 100`，本卡零改动。

## 5. 运行器改动契约（run-gates.ps1）

### 5.1 配置校验块（B4 + B10，新增，位于 config 解析/Add-Result 定义之后、主 try 之前）

1. 已知阶段列表（19）：`gateSelfTest, secretsScan, structureCheck, devLogNamingCheck, adrFieldsCheck, compile, lint, unitTest, frontendTypeCheck, frontendLint, frontendTest, frontendE2E, coverage, mutationTest, sast, dependencyScan, duplication, complexity, moduleDependency`。
2. `$cfg.stages` 为 `$null` → `config.stages ERROR`（一次性行，列出缺段面）；否则逐阶段：
   - 缺段 → `config.stages.<name> ERROR`（"missing stage section ... never silently skipped"）；
   - 有段缺 `enabled` 属性 → `config.stages.<name> ERROR`（"no enabled flag ..."）；
   - `enabled=false` 且 `status` 非 `PENDING*` → `<name>.disabled SKIP`（"visible by policy; enable it or register PENDING-*"）。
3. 校验块自身包 try/catch：失败 → `config.stages.guard ERROR`（不得无报告崩溃）。
4. **真实 config 预期新增 0 行**（19 段齐全；5 个关闭段均有 PENDING*）；fixture 会新增若干 `<name>.disabled SKIP`（SKIP 不影响 GREEN）。

### 5.2 超时语义（B6）

1. `Invoke-MetricProcess` 返回对象增加 `TimedOut`（bool）与 `RawOutput`（不含超时前缀的原始输出）；超时分支 `Output` 前缀改为 `<timed out after Ns; timeout sentinel -1 is NOT a process exit code>`。
2. 全部超时分支改判 `TimedOut`：gateSelfTest、coverage、mutationTest 模块、sast、docker 探测、mvn/npm 收敛段；**不得再以 `ExitCode -eq -1` 推断超时**。
3. 超时报告行追加 `(timeout sentinel -1 is NOT a process exit code)`；`Write-StageOutLog` 头保持 `exitCode: -1 (TIMED OUT)` 并在其后追加 `[sentinel: -1 is not a process exit code]`（保留既有断言子串）。
4. 正常退出码 -1（若出现）按普通失败处理（FAIL），不再误判为超时。

### 5.3 进程调用收敛（B2）

1. mvn 三段循环：删除内联 `ProcessStartInfo`/`Process.Start`/`WaitForExit` 块，改调
   `$r = Invoke-MetricProcess "cmd.exe" ("/c " + $toolMavenBin + " " + $toolMavenArgs + " " + $s.goals) $RepoRoot $stageTimeoutSec $stageName -PlatformEncoding`；
   保留 `$env:JAVA_HOME` 设置/恢复、`$cmdLine` 日志标签、三态（超时/0/非 0）报告行与 out.log 落盘；out.log 用 `$r.RawOutput`。
2. npm 四段循环：删除内联块，改调 `Invoke-MetricProcess "cmd.exe" ("/c npm " + $s.goals) $workdir 300 $stageName`（保持 UTF-8）；三态报告/日志与现行为一致。
3. `-PlatformEncoding` 仅 mvn 段使用：helper 不设置 `StandardOutputEncoding/ErrorEncoding`，保持 JDK 平台编码（DB-19 口径）；npm 段保持 UTF-8。
4. **零行为变更**：报告行文本（PASS/FAIL）、退出码、判定分支、超时触发点不变；仅实现载体收敛。实测任何差异 → 停报登记。

### 5.4 gateSelfTest.tool 白名单（B1）

1. tool 读取后立即校验 `^[A-Za-z0-9:._\-\s/\\]+$`；非法 → `gateSelfTest ERROR`（含 "Illegal characters in stages.gateSelfTest.tool" + "stage SKIPPED, command NOT executed"），**不 Join-Path、不 Test-Path、不启动进程**。
2. 合法路径逻辑保持现状（缺文件 ERROR / 缺 powershell ERROR / 执行器语义不变）。

### 5.5 误伤面增强排除（N1）

1. 运行器启动时（任何阶段前）记录 `$script:runStartedAt = Get-Date`；枚举 `Win32_Process`，把命令行同时含 `<RepoRoot>\` 段与 `\target\surefire\` 的**已存在** PID 存入 `$script:preExistingSurefirePids`（best-effort，失败为空集）。
2. `Stop-DetachedStageProcesses` 在现有双特征 + 祖先链排除之外增加三个排除：PID 在运行前快照内；`CreationDate` 早于 `runStartedAt - 2s`；`CreationDate` 为空（无法证明是本轮）。三者任一命中 → 不杀。
3. 残余风险（登记 README/ADR-018）：本次运行开始后才启动的同仓并行 surefire/jacoco fork 仍可能被超时清扫误杀。

## 6. selftest 改动契约（selftest.ps1）

> **断言只增不减**：现有 163 断言零删改；新增断言预计 +30 左右，最终数 `<N>` 以实测末行为准；场景 S1~S18 保留，新增 **S19**（B4/B10）。

### 6.1 开场自愈清扫（N5）

1. 新增 `Remove-StaleSelftestProcesses`：枚举 `Win32_Process`，匹配命令行含 `ctds-gates-selftest-` **且** 含 `\target\surefire\`；沿 `ParentProcessId` 上溯排除自身/祖先链（上限 64 层）；逐进程 `Stop-Process -Force` best-effort；返回清理数。
2. 开场（workDir 创建后、S1 前）调用；清理数 >0 时输出 `opening self-heal sweep: cleaned <N> stale selftest process(es)`。
3. 诚实边界：并发 selftest 运行不受支持（可能互相清扫 Boot）；只匹配 surefire 形态，非 surefire 残留不在本卡范围。

### 6.2 S15 新增 `illegal` 探针（B1）

- tool 设为 `scripts/gates/selftest.ps1 & whoami`（含非法 `&`）；
- 断言：exit 2、`| gateSelfTest | ERROR |`、`Illegal characters`、`stage SKIPPED, command NOT executed`；
- 不放置 stub（非法校验须先于文件存在性检查）。

### 6.3 S17 新增探针 D/E/F（C2/C6/C7）

| 探针 | 环境 | stub | 期望 |
| --- | --- | --- | --- |
| D | `GITHUB_ACTIONS=true` + 无 Docker | mvn exit 3 + docker exit 1 | coverage **FAIL**（`mvn coverage exit 3`，无 `DEFERRED`）、exit 1/RED |
| E | `GITHUB_ACTIONS=true` + Docker 输出 `Linux`（混写） | mvn 45% + docker `Linux` exit 0 | coverage **FAIL**（45%<70%，无 `DEFERRED`）、exit 1/RED（`-ieq` 大小写反向锚） |
| F | 仅 `CI=true`（无 `GITHUB_ACTIONS`）+ 无 Docker | mvn 45% + docker exit 1 | coverage **SKIP**（`threshold verdict DEFERRED`）、exit 0/GREEN（OR 单形态锚） |

- 每个探针后恢复 `PATH`/`GITHUB_ACTIONS`/`CI`；finally 清理新增 out.log。
- 探针 A（仅 `GITHUB_ACTIONS`）保留，与 F 共同覆盖 OR 两形态。

### 6.4 S18 扩展（N1 + N4）

1. **N4**：`New-FixtureConfig $fix "cfg-s18.json" $true $realJavaHome18 $null`（取代 `$realJavaHome`）。
2. **N1 并行 bystander**：在 `<fixture>\target\surefire\parallel.js` 写 `setInterval(...)`；在调用运行器**之前**用 `Start-Process node` 启动（命令行天然含 RepoRoot + `\target\surefire\`）；记录 PID。
3. 断言：运行器超时清扫后该进程**必须幸存**；另加 CIM 命令行有效性锚（含 RepoRoot 段且含 surefire 段），防恒真；收尾由 selftest 强杀。
4. 既有孙进程被清 / 普通 bystander 幸存 / 收尾兜杀断言保持不变。

### 6.5 新增 S19（B4 + B10）

1. **S19-a（B10）**：fixture 配置删除 `stages.structureCheck` 段 → 运行器报告 `config.stages.structureCheck | ERROR` + `missing stage section`，exit 2，报告照写。
2. **S19-b（B4）**：普通 fixture 配置（`New-FixtureConfig` 已关闭多段且无 status）→ 报告出现 `frontendTest.disabled | SKIP` + `visible by policy`，exit 0/GREEN。
3. 断言约 6 条；S19 收尾清理 out.log。

### 6.6 计数与时长

- 最终断言数 `<N>` = 163 + S15 illegal + S17 D/E/F + S18 N1 + S19（以实测末行为准）；场景数 = 21（S1~S18 + S19，S11b/S11c 作为子场景）。
- 本卡后独立单发实测 `<T>s`；config V1.12 note 与 README 同步该数据点。

## 7. 其他文件改动契约

### 7.1 `.github/workflows/quality-gate.yml`（B7/B5）

- 六处 `uses:` 改 §4 SHA；`listComments` 保持 `per_page: 100`；其余触发/权限/步骤/判定零改动。
- 文件头补一行：action 版本指纹核验日期与 README 指针; 保留 "零 secret、零三方 action" 表述（三方 action 仍为官方 action，仅改为 SHA 钉住）。

### 7.2 `services/gateway-service/src/test/java/com/ctds/gateway/GatewayDeliveryChainIntegrationTest.java`（N2，仅测试）

- 新增一例负向探针（§3 N2）；import `assertThatThrownBy` 与 `TimeUnit`；测试约 5s。
- 不改产品代码、不改既有断言、不改等待助手实现。

### 7.3 `scripts/gates/README.md`（B3/B8/B9/C1/C3/C5/C8/N1/N3/N5 + 变更留痕）

- 标题/退出码/自检/扫描范围当前锚点改 DB-07 + ADR-018；顶部加历史 WBS 编号保留说明。
- CI 接入节新增：承接方显式声明（C1）、artifact 暴露面（B8）、报告 detail 措辞（C8）、action SHA 指纹表（B7）、node 使用口径（N3）。
- 自检节：V5 时长补 34s（C3）；场景表补 S19；S17 探针表补 D/E/F；S18 补并行 bystander；S15 补 illegal；断言/场景计数更新为最终值。
- 已知特性段：N1 残余（运行开始后启动的并行构建仍可能误杀）、N5 并发 selftest 不支持、B6 超时哨兵语义、B4/B10 配置校验。
- 变更留痕新增卡 2b 条目（含 config V1.12、ConfigSha256 新基线、零阈值/阶段/扫描面声明）。

### 7.4 `docs/adr/ADR-018-质量度量门禁接入.md`

- 新增修订记录 **V1.10**（卡 2b）：背景/决策/理由/备选/业务影响说明/影响范围/可替换性七字段；内容覆盖 config V1.12（C4/C5 note 级）、B6 超时语义、B4/B10 配置校验、B7 SHA 钉住、B8/C8 artifact 暴露面、N1 残余、N5 并发边界；**强度申辩** = 判定阈值/阶段范围/扫描面/五态/退出码零改动（B4 新增 SKIP 可见行不改变 GREEN 语义；B10 新增 ERROR 行仅对缺段配置 fail-closed）。
- 登记 ConfigSha256 新基线（外部登记，changeLog 不内嵌）。

### 7.5 `scripts/gates/gates-config.json`（V1.12，仅 note/changeLog/version）

- `version`: `V1.12`。
- `changeLog`: 新条目置首（C4/C5 + B3 当前 note 改锚 + 零判定面改动声明 + 当前哈希不内嵌原则）。
- `stages.gateSelfTest.note`: 当前 note 的"WBS-2.2.7"改"DB-07"；场景/断言计数改最终值；补 `58s〔125 断言〕`/`70s〔163 断言〕`/本卡 `<T>s〔<N> 断言〕`；余量 `5.2 倍（58s）/约 4.3 倍（70s）`。
- `stages.coverage.note`: 保持 `OSType`；S17 护栏描述补 D/E/F（执行层失败不降级/大小写/单形态 OR）。
- **零键位、零阈值、零阶段范围、零扫描面、零五态/退出码语义改动**；ConfigSha256 新基线在 README/ADR-018/任务卡/日志四处登记。

### 7.6 `docs/dependencies.md`（B9/N3）

- 新增"本地开发工具（非项目依赖）"节（§3 B9），含 node 口径（N3）。

## 8. 测试先行与验证计划

1. **红相先行（可确定性复现的项）**：
   - B1：S15 illegal 探针在旧运行器上失败（无 "Illegal characters"）→ 归档 `build-output/card2b-red-b1-*.log`；
   - N1：S18 并行 bystander 在旧运行器上被杀 → 断言失败，归档 `build-output/card2b-red-n1-*.log`；
   - N5：一次性探针预置 stale 进程 → 旧 selftest 不清理、进程幸存 → 归档 `build-output/card2b-red-n5-*.log`；
   - N2：临时把 `awaitAllEvents` 改为即时快照 → 新测试耗时断言失败 → 归档后还原（不得提交临时改动）；
   - B10/B4：S19-a/S19-b 在旧运行器上失败（无 ERROR/SKIP 行）→ 归档（若旧行为已满足，则记录"锚定非红相"并如实说明）。
2. **实现顺序**：B6 helper 语义 → B2 收敛（先跑 selftest 基线）→ B1 → B4/B10 → N1 → N5 → N4 → S15/S17/S18/S19 探针 → N2 → B7 workflow → 文档/配置。
3. **回归口径**：
   - `selftest.ps1` 全量 0 失败，断言 163 → `<N>` 只增不减；双解释器语法 0 错误（PS 5.1 + pwsh 7 ParseFile）；
   - 全仓门禁单发 GREEN（`run-gates.ps1`），新红项逐项定性；ConfigSha256 与 V1.12 登记值逐字一致；
   - workflow YAML 结构校验（pyyaml 或等效）+ 六处 SHA 与 §4 表逐字一致；
   - 网关测试类（含 N2 新例）全绿；`mvn -pl services/gateway-service test` 或等效命令；
   - `git grep "OSTYPE"` 仅历史条目；`git grep "@v4\|@v5\|@v7"` 仅注释/无裸 tag。
4. **证据归档**：红相/绿相日志落 `build-output/`（gitignored）；门禁报告落 `scripts/gates/reports/`（gitignored）；日志/任务卡记载路径与计数。

## 9. 边界与异常行为

1. **零业务代码**：N2 仅测试类；其余为门禁脚本/config/文档；不碰产品端点/库表/前端。
2. **零判定面改动**：阈值、阶段范围、扫描面、五态、退出码语义不变；B4 的 SKIP 可见行与 B10 的缺段 ERROR 是**可见性**增强（缺段配置 fail-closed），不放宽任何判定。
3. **B2 是唯一可能触达行为面的重构**：以"零行为变更 + selftest 全回归 + 全仓 GREEN"为硬门槛；任何差异停报改登记。
4. **config V1.12 仅 note/changeLog/version**：若改动越出该面 → 红线 3 停报。
5. **B7 SHA 不可核验**（网络/注册表异常）→ 该 action 保持 tag 并登记原因；本设计已双源核验，编码段只做复核。
6. **N1 残余**：运行开始后启动的并行 surefire fork 仍可能误杀；不以"增强排除"冒充"零误伤"。
7. **N5 边界**：并发 selftest 不受支持；仅清 surefire 形态；非 surefire 残留另行登记。
8. **artifact 暴露面**：仓库 PUBLIC → 报告/证据公开可读；零 secret 是硬约束；未来引入敏感 detail 必须先脱敏。
9. **不夹带**：WBS 业务包、4.2.1 性能数据集、frontendE2E/dependencyScan 接入、白名单修改均不做。

## 10. 前置检查（编码段第一动作）

1. `git status` 干净；分支 = `feat/DB-46-DB-45-门禁可信性-卡2b`；`main == origin/main == bf6e4df` 未动；
2. 工具链：JDK 17 / Maven 3.9 / Node / PS 5.1 + pwsh 7；
3. 无遗留 selftest/java/node 异常进程（N5 清扫实现后首跑即验证）；
4. `build-output/` 红相归档目录就绪；
5. 复核 §4 SHA（`git ls-remote` + `gh api`，当日双源）；
6. 实读 S15/S17/S18/S19 助手用法（`New-FixtureConfig` 递归守卫、`Invoke-Runner -detached`、`Assert-*`）。

## 11. 交付门禁（关闭条件映射）

| 关闭条件 | 承载 |
| --- | --- |
| ① 23 项逐项有处置结论（零遗漏） | §3 全表（A/B/C 逐项） |
| ② selftest 全量 0 失败且断言只增不减；全仓门禁 GREEN | §6 / §8-3 |
| ③ config 变更四件套 + ConfigSha256 新基线外部登记 | §7.3/§7.4/§7.5 + 日志 |
| ④ 4 视角评审 + 独立复审 PASS | 编码段收口 |
| ⑤ 编排师验收 | 收口段 |
| ⑥ 三剧本无需更新（零业务面） | 本卡业务可读总结 |

---

> **确认记录（待编排师）**：本文件随确认段批提交（`feat/DB-46-DB-45-门禁可信性-卡2b`）；编排师确认 hifi V1.0 后，编码段归新会话冷启动（第一动作 = §10 前置检查 + §8-1 红相先行）。
