# 质量门禁运行器（第一道防线 · WBS 2.2.2/2.2.4）

## 这是什么

本项目所有代码/文档交付前的**机器门禁**。它是一个独立脚本，不依赖任何 CI 平台——未来接入任何 CI（GitHub Actions、Gitee Go、自建 Jenkins）时，只需让流水线执行本脚本并检查退出码（0=绿灯，1=红灯，2=环境/配置错误），这就是"切换接口预留"（D-3 原则）。

## 怎么运行

在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\run-gates.ps1
```

结束后屏幕会打印一份门禁报告（同时在 `scripts/gates/reports/` 存一份 Markdown，该目录不入库）。报告头带 `RunLabel / Scenario / ExitCode / ConfigPath / ConfigSha256 / Time / Repo`——**任何结论都能被锚定到"哪一次运行、用的哪份配置"**（第三方复核只需这份报告）。

## CI 接入（DB-07，2026-10-16）

`.github/workflows/quality-gate.yml` 把本运行器接入 GitHub Actions（D-3 切换接口的首次落地——**门禁脚本零改造，CI 只调用**）：

- **触发**：`pull_request`（全分支，opened/synchronize/reopened）+ `push`（仅 `main`）+ `workflow_dispatch`（手动）；**不按路径过滤**（文档变更同样要过 devLogNamingCheck / adrFieldsCheck / secretsScan——过滤即放水）；
- **Runner 与工具链**：`windows-latest`（脚本 Windows 定位：`$env:TEMP` / `taskkill /T` / `cmd.exe` 包 mvn）+ `setup-java`（temurin 17，注入 `JAVA_HOME`——脚本 toolchain 段的 `$env:JAVA_HOME` 优先分支读环境变量）+ `setup-node`（**22**——前端依赖实测要求 Node 22+〔`undici`/`webidl.util.markAsUncloneable`〕，V4 复跑 2 取证后由 20 升 22）+ `npm ci`（`frontend/`，失败即红）+ `setup-python`（3.12）+ `pip install semgrep==1.177.0`（**DB-07 V1.2 起 sast 的 CI 主路径**；安装失败不阻塞，由 sast 段记 ERROR 判红）；**runner 的 Docker 已实证不可用**（V4 首跑 `npipe:////./pipe/docker_engine` 不可达）——V1.2 已把 sast 改为**免 Docker 原生形态**（docker 仅作本地回退），Testcontainers 等运行期需求仍由各阶段 ERROR/SKIP 语义承担，**不得以"CI 环境限制"为由关闭任何阶段**；
- **执行**：单 job 全量 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gates/run-gates.ps1`（与本 README 规范调用逐字一致）；该步 `continue-on-error` 仅为取回报告，**终态步骤 fail-closed：退出码 0 → job 绿；1（RED）/ 2（环境/配置错）一律 job 红**（摘要区分语义，不得把 2 放行）；
- **结论三形态**：① job 摘要（`$GITHUB_STEP_SUMMARY`：ExitCode / 五态计数 / RunLabel / ConfigSha256 / 报告文件名）② PR 评论（`actions/github-script`，固定标识 upsert 不刷屏；fork PR 自动跳过——GITHUB_TOKEN 只读）③ artifact 上传（`gate-report-<run_id>`，CI 上 reports 为临时目录，取回供第三方逐字复核）；
- **失败证据取回（V1.2）**：`if: always()` 上传 `gate-evidence-<run_id>`（`%TEMP%\ctds-gate-*-out.log` / `%TEMP%\ctds-gates-selftest-*` / `scripts/gates/reports/`）——报告未写出或阶段失败的原始输出由该 artifact 取回；无文件时 warn 不失败，**不改变任何判定与退出码语义**；
- **并发**：同 ref 新推送取消旧跑（`concurrency`）；
- **秘密**：**零 secret**——仅用自动注入的 `GITHUB_TOKEN`（最小权限 contents: read + pull-requests: write）；
- **依赖解析**：runner 无用户级 `.m2/settings.xml`，直连 Maven Central（镜像为国内网络优化，不为 CI 新增配置文件）；
- **gateSelfTest 启用记录（本卡四件套之一）**：随本次接入 `enabled` false→true（config V1.8 + changeLog V1.8~V1.10 + ADR-018 V1.5 + 本节）；ConfigSha256 基线链 `924f0ca3…dfdd` → `5f600e72…4eb6`（V1.8）→ `4cefc43b…13c`（V1.9 实际值）→ `b7591621…7080`（V1.10）→ **`315264e1…1304`（V1.11，当前）**（**V1.10 勘误（2026-10-17）**：V1.9 段尾登记 `36ed456e…5696` 系自指陷阱失实——把当前文件哈希写入 changeLog 即改变文件本身，登记值随写入失效；V1.9 实际哈希经工作区 `Get-FileHash` + V3.2 报告双源证实 = `4cefc43b…13c`；自 V1.10 起当前哈希不内嵌 changeLog，当前基线一律在本行与 ADR-018 外部登记，第三方可对任意 git 历史版本以 `Get-FileHash` 复算核对）；
- **分支保护（Q6-A）**：main 的 require status checks 由编排师在 GitHub 仓库设置执行（AI 无仓库设置权限——验收通过后呈报待执行，不代设）。
- **V1.2 修复批（2026-10-17，V4 首跑 RED 后编排师两项裁决：修复扩面批准 + Docker 策略 B）**：sast 改**免 Docker 原生形态**（hifi §3-ter：semgrep 原生 CLI 优先、docker 本地回退、判定与解析单实现；**`gates-config.json` 零改动**——ConfigSha256 仍 `b7591621…7080`）；workflow 增 `npm ci` / semgrep 安装 / 失败证据上传；`run-gates.ps1` 两处健壮性（docker 探测不再被 native stderr 中止、兜底报告先建目录）；`selftest.ps1` 新增 S11c（引擎不可用 → sast ERROR + 报告照写 + exit 2，不中止整轮），**125→130 断言**；ADR-018 V1.6 留痕。
- **V5 覆盖率口径（2026-10-17，编排师授权"评估后按合理建议继续执行"；config V1.11 + ADR-018 V1.8）**：runner **无 Linux 容器 Docker**（V4 首跑实证 daemon 不可达）→ Testcontainers 集成测试静默 skip，coverage 实测 45.61% 物理上够不到章程阈值（core≥80 / overall≥70，真值判定面 = 有 Docker 的本地门禁，94.1%）——coverage 段**环境自适应判定**：CI 环境（`GITHUB_ACTIONS`/`CI`=true）且无 Linux Docker（`docker info` OSTYPE≠linux 或不可达）时 jacoco 照常执行、实测值如实入报告，**阈值判定降级为 SKIP**（报告行明示实测值 + 未达标项 + 降级原因 + 权威判定归属本地门禁）；**本地无 Docker 维持 FAIL**（可修复环境不开口子）；执行层失败不降级；未来 CI 换带 Linux Docker 的 runner 时探测自动恢复阈值判定。机器护栏 = selftest **S17** 三探针。**判定阈值 / 阶段范围 / 扫描面 / 退出码语义 / 五态语义零改动**；
- **同仓分支 PR 边界（评审② P2-2 登记）**：`pull_request` 全分支触发下，同仓库分支 PR 的 `GITHUB_TOKEN` 保留 `pull-requests: write`，且 job 执行的是 PR 版本的门禁脚本——属触发模型固有面（fork PR 已按 `head.repo` 同仓判断跳过评论步骤、届时令牌只读）；缓解 = 仓库设置对外部协作者 PR 要求审批（与 Q6-A 一并归编排师执行）。

## 怎么看结果

- 每个检查项五态：**PASS（绿）**、**FAIL（红，必须修复后重交，无特批）**、**SKIP（跳过）**、**ERROR（环境/配置问题）**、**PENDING（待接入）**；
- **SKIP 不是放行**：记 SKIP 的情形有三类，凡涉及**入库**（git 追踪）文件均**逐条列名**备人工复核——① 未入库文件被占用/不可读（`secretsScan.skipped` 行）；② ≥1MB 超大文件不入正则扫描（`secretsScan.oversized` 行，DB-18）；③ NUL 二进制按启发式跳过（`secretsScan.binary` 行，DB-18）；**入库文件不可读一律记 `ERROR`**（`secretsScan.unreadable` 行），目录枚举失败（其下文件根本没进扫描名单）也记 `ERROR`（`secretsScan.enumGap` 行，DB-18）——"没枚举到/扫不了/没扫/扫过且干净"必须分开；
- 头部 `Result` 行汇总五态计数与终态（`-> GREEN / RED / ERROR`）：只有 **GREEN** 才允许提交评审；
- 退出码（WBS-2.2.7 语义，**优先级 1 > 2 > 0**）：
  - `1` = RED（**代码/安全不合格**，必须修复后重交）——**只要存在任一 FAIL 就是 1，即使同时存在 ERROR 行**；报告中会明示"环境/配置错误不得掩盖这条红灯"；
  - `0` = GREEN；
  - `2` = 配置/环境错误（含**脚本自身崩溃**、报告写入失败、配置解析失败，以及阶段级环境问题：`JAVA_HOME` 缺失/无效、`mvn`/`npm` 不在 PATH、`package.json` 缺失、配置项含非法字符、仓库文件枚举失败 `secretsScan.enumGap`——这些一律记 `ERROR` 行）——**`2` 不是质量结论**，须先修环境/配置再重跑；
- **报告必然产出**：任一阶段抛出未预期异常都会被兜底捕获、记 `runner ERROR` 明细并**仍写出报告**；报告目标不可写时记 `runner.reportPath ERROR` 并把报告打印到标准输出。唯一例外：配置文件解析失败——此时无法产报告，直接以退出码 `2` 退出并打印原因。

## 运行器自检（脚本自身防回归 · WBS-2.2.7 交付物⑤）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\selftest.ps1
```

在 `%TEMP%` 下构造**沙箱 fixture 仓库**（`git init` + 精简阶段配置，配置由真实 `gates-config.json` 派生，因此练的是**真实排除项**），可复现地注入故障并断言，全部通过才退出 0：

| 场景 | 注入的故障 | 期望结论 |
| --- | --- | --- |
| S1 | **未入库**文件被独占锁定 | 记 SKIP、**门禁继续**（后续阶段仍跑）、报告产出、退出码 0 |
| S2 | **入库**文件被独占锁定 | 记 ERROR、退出码 2（入库文件绝不允许被跳过） |
| S3 | `JAVA_HOME` 指向无效路径 | 记 ERROR、退出码 2；**反向探针**：同 fixture 关掉工具链阶段 → 退出码 0（证明 ERROR 由注入的故障引起，而不是 harness 本身坏了） |
| S4 | 报告路径不可写 | 记 ERROR、退出码 2，且报告仍打印到标准输出 |
| S5 | 排除项命中入库路径 | 护栏记 ERROR、退出码 2（禁止借排除项规避扫描） |
| S6 | 在 `docs/logs/` 放一条"假凭据" | 记 FAIL 并点名该文件、退出码 1（**入库开发日志必须被扫描** —— DB-16 回归锚点） |
| S7 | 某阶段抛异常（ADR 文件被锁定） | 外层兜底记 `runner ERROR`、报告**仍然产出**、退出码 2（崩溃绝不能被误报成"代码不合格=1"） |
| S8 | 日志命名违规（FAIL）+ 无效 `JAVA_HOME`（ERROR）并存 | 退出码 **1**、结论 `RED (also N ERROR...)`（真红灯绝不能被环境/配置错误掩盖 —— R2 锚点，DB-17） |
| S9 | Maven `compile.goals` 注入非法字符（`&`） | 该阶段记 ERROR 且行文含 **"stage SKIPPED, command NOT executed"**（非法配置绝不启动命令 —— R5 锚点，DB-17） |
| S10 | 前端 `typecheck` stub 退出 1 / 退出 0（红/绿双探针） | 退出 1 → FAIL/RED/退出码 1；退出 0 → PASS/GREEN/0（类型失败绝不能绿 —— V1.3 防假绿灯锚） |
| S11 | 裸 fixture 中启用 coverage / mutationTest / sast 三段（探针调用前显式剥离 `GITHUB_ACTIONS`/`CI` 环境变量——DB-07 V5：否则 CI 形态下 coverage 触发降级 SKIP、极性探针失效；降级三形态由 S17 锚定） | 三段 fail-visible（FAIL/ERROR 行、绝无静默 PASS）+ coverage 阈值极性探针（10% < 70% 必 FAIL）+ 反向探针（段关掉 → GREEN）——V1.4 |
| S11b | stub `mvn` 退出 3 | coverage FAIL 且行文点名 `mvn coverage exit 3`（"退出非 0 → FAIL"分支探针，V1.4 复审尾巴修复） |
| S11c | stub `docker`（写 stderr 后退出 1，模拟 daemon 不可达 / 镜像缺失） | sast 记 **ERROR** 行、**报告照常写出**、退出码 2，且报告无 `runner` 崩溃行——V1.2 锚定 V4 首跑"裸 `docker image inspect` 在 Stop 策略下中止整轮、报告与证据全丢"的回归（载体自适应：装有 semgrep 的机器走原生分支，同样要求不中止 + ERROR + fail-closed） |
| S12 | stub `mvn` 退出 0 + 标记输出 | compile PASS **且** `%TEMP%\ctds-gate-compile-out.log` 存在，含 `stage: compile` / `exitCode: 0` 摘要行与完整原始输出；PASS 报告行保持原格式（DB-43 ①：成功态也要有机器可核证据，"0 字节日志"教训根治） |
| S13 | stub `npm`（typecheck）退出 1 + stdout/stderr 标记 | frontendTypeCheck FAIL、out.log 含 `exitCode: 1` 与两个标记、报告 FAIL 行含 `full output:`（DB-43 ③：DB-44"错误正文未取得"损失场景的正面锚） |
| S14 | stub `mvn` 启动孙进程（写 PID 入文件）后挂起，compile `timeoutSeconds=2` | 报告 FAIL "timed out"、out.log 含 `(TIMED OUT)` 与超时前部分输出、**孙进程 PID 已不存在**（taskkill `/T`；旧实现仅杀父进程、孙进程幸存 = 红相锚——DB-43 Q1-A / 跟踪-23 剩余兑现） |
| S15 | fixture 内 `gateSelfTest.enabled=true` + stub `selftest.ps1` 退出 0 / 1 / 2，tool 指向不存在文件，stub 置于带空格目录（`scripts/gates dir/`），及挂起 stub（`timeoutSeconds=2`） | 退出 0 → `gateSelfTest PASS` 行 + 退出码 0（详情摘录断言摘要、**无 PENDING 行**、**行序位于 secretsScan 之前**）；**空格路径 → PASS**（锚定执行器 `-File` 引号——仓库根路径含空格，未加引号会被命令行解析截断）；退出 1 → FAIL 行 + 退出码 1（"runner self-check FAILED" + `full output:` 路径）；退出 2 → ERROR 行 + 退出码 2（harness error，非代码结论）；tool 缺失 → ERROR 行 + 退出码 2；**挂起 → FAIL "timed out after 2s"**（超时分支锚 + out.log 取证深锚：stage 摘要行 / `exitCode: -1 (TIMED OUT)` / kill 前 stub 输出）——六探针 + 行序锚 + 收尾递归守卫机器扫描（非 S15 fixture 配置必须保持关闭态）（DB-07 编排师裁定 C；stub 而非真实 selftest，因 fixture 内跑真实脚本会递归） |
| S16 | 同 S6 的假凭据 fixture，但 runner 以 **8.3 短路径**形式收到 `-RepoRoot`（CI 的 `%TEMP%` 即短路径：`C:\Users\RUNNER~1\...`；8.3 别名不可用的卷上自动退化为长路径运行，不失真） | 扫描命中行**仍是仓库相对路径**（`Pattern hit in docs/logs/...`）+ 退出码 1 + RED——入口必须把仓库根展开成长路径（`Get-Item -LiteralPath`），否则前缀剥离失配、命中行变全路径、入库文件被判"未入库"（V4 复跑 3 的 S2×4+S6×1 失败形态；`cmd /c "cd && cd"` 不展开短名——本地实测原样回显） |
| S17 | coverage 环境自适应判定（DB-07 V5）：stub `mvn.cmd`（exit 0 + 写 45% jacoco.xml，模块名 `fake` 非 coreModules 仅触发 overall 判定）+ stub `docker.cmd`（exit 1 = 无 daemon / 输出 `linux` = 可用；PATH 前插 fixture `bin` 遮蔽宿主机真工具）三探针 | **CI（注入 `GITHUB_ACTIONS=true`）+ 无 Docker** → coverage **SKIP** 行（实测值 `overall line 45% (45/100)` + 降级原因 + 权威判定归属本地门禁均在行内）+ 退出码 0 + GREEN；**本地 + 无 Docker** → **FAIL** + 退出码 1 + RED 且**无降级字样**（本地判定语义不变锚）；**CI + Linux Docker 可用** → FAIL + 退出码 1（有 Docker 不降级锚）——反向验证：暂存运行器修复后恰 S17-a 五断言失败 |

`selftest.ps1` 只读运行器、只写 `%TEMP%`；全部通过后自动清理沙箱目录（`-KeepFixture` 可保留以便排查）。其运行状态在配置中登记为 `stages.gateSelfTest`：**DB-07 起（2026-10-16，编排师裁定 C）`enabled=true`——`run-gates.ps1` 执行器（全阶段之首）每轮门禁自动实跑本脚本**（S1~S14 + S11b + S11c + S15 + S16 + S17 共十九场景 **148 断言**；时长多口径：独立单发实测 32s〔94 断言基线〕/ 全量门禁内实测 41s〔V3，115 断言〕/ V1.2 修复批独立单发 31s〔130 断言〕/ V1.3 修正批独立单发 33s〔133 断言〕/ V5 口径批独立单发实测〔148 断言〕），退出码 0/1/2 分别记 PASS/FAIL/ERROR 报告行，超时记 FAIL；`enabled=false` 时每份报告显示"自检未跑"（PENDING 行）。fixture 配置一律强制关闭该段并补回 `status:PENDING-SELFTEST`（递归守卫，`New-FixtureConfig`——演练"关闭态可见性"；收尾另有全量扫描的机器护栏），S15 在自己的 fixture 里显式开启并以 stub 验证执行器接线（六探针 + 行序锚）。

> `run-gates.ps1` 另有两个**仅供 selftest / 变更验证**的参数：`-ConfigPath`（指定另一份配置，报告会写明实际路径与其 SHA256）与 `-Scenario`（运行标签）。**正常交付运行一律使用默认配置、`Scenario=default`**；带标签的运行在报告中一眼可辨。

## 扫描范围与排除项（WBS-2.2.7）

`gates-config.json` 的 `secretsExcludePaths` **只允许覆盖"未入库的构建/运行产物目录"与第三方产物目录**（当前：`.git`、`scripts/gates/reports`、`build-output`、`**/node_modules/**`、`*.min.js`、`*.lock`、`logs`、`**/target/**`、`**/dist/**`、`**/test-results/**`、`**/playwright-report/**`、`**/coverage/**`）。**禁止借排除项规避源码/配置/文档扫描**；新增排除项须经门禁变更流程留痕（`changeLog` 写明理由），配置中另有 `secretsExcludePathsRule` 声明该约束。

**条目语义（V1.2 起）**：

- **不以 `*` 开头** = **仓库根锚定**路径：只匹配仓库根下该路径本身及其子项。例：`logs` 只排仓库根的 `logs/`（运行日志），**不影响 `docs/logs/`**（必须入库的开发日志）；
- **以 `*` 开头** = 通配模式，对仓库相对路径做 `-like` 匹配（`*` 可跨 `/`；以 `**/` 开头时同时匹配仓库根下的同名目录）。例：`**/target/**` 覆盖任意层级的 `target/`，也覆盖仓库根的 `target/`。

**机器护栏（V1.2 起）**：门禁启动时用 `git ls-files` 取入库路径清单，**任一排除项命中入库路径即记 `secretsScan.excludeGuard ERROR` 并以退出码 2 结束**。"排除项不得规避已入库文件"这条约束不再只写在注释里，而是每次运行都在校验——**改动排除项若误伤入库文件，门禁当次即红**。

> 变更留痕：
> - **DB-07 V1.2（2026-10-17，V4 首跑 RED 修复批，编排师两项裁决：修复扩面批准 + Docker 策略 B）**：① sast 改**免 Docker 原生形态**——`semgrep` 原生 CLI 优先（PyPI `semgrep==1.177.0`，与镜像 tag 同版同 `p/java`）、`docker` 形态为本地回退，判定与 JSON 解析单实现（hifi §3-ter）；**`gates-config.json` 零改动**（ConfigSha256 `b7591621…7080` 逐字不变）；② `.github/workflows/quality-gate.yml`：`npm ci`（`frontend/`，失败即红）+ `setup-python@v5` + `pip install semgrep==1.177.0`（安装失败不阻塞，sast 段 ERROR 判红）+ `gate-evidence-<run_id>` 失败证据上传（`if: always()`）+ Docker 探测措辞更新；③ `run-gates.ps1`：docker 探测改经 `cmd.exe /c "... >nul 2>&1"` 判 `$LASTEXITCODE`（**不再被 native stderr 在 Stop 策略下中止**——V4 首跑 line 730 整轮中止根因）、兜底报告先建 `reports/` 目录（仅兜底分支；显式 `-ReportPath` 的 S4 语义不变）；④ `selftest.ps1` 新增 **S11c**（引擎不可用 → sast ERROR + 报告照写 + exit 2 + 无 runner 崩溃行），125→**130 断言**只增不减；⑤ ADR-018 V1.6 + 任务卡边界变更记录 + `docs/dependencies.md` semgrep 登记勘误。**判定阈值 / 阶段范围 / 扫描面 / 退出码语义 / 报告五态语义零改动**。实测：selftest 130 断言 0 失败（`build-output/db07-selftest-v12c.log`，31s）。
> - **DB-07（2026-10-16，CI 门禁自动化，任务卡 `docs/tasks/DB-07-CI门禁自动化-2026-10-16.md` + 两级设计 `docs/designs/DB-07-{lofi,hifi}.md`（V1.0 一次确认 + V1.1 增补 §3-bis），编排师 Q1~Q7 均采 A + 编码段红线 4 停报后裁定 C）**：① `.github/workflows/quality-gate.yml` 新增（触发 / windows-latest / 单 job 全量 / 结论三形态回写 / fail-closed / 零秘密——见上"CI 接入"节）；② `gates-config.json` V1.8：`stages.gateSelfTest.enabled` false→true + `timeoutSeconds=300` + 移除 `status:PENDING-SELFTEST` + note 更新 + version V1.6→V1.8（ConfigSha256 `924f0ca3…dfdd` → **`5f600e72…4eb6`**）——V1.0 契约"翻 enabled 即随每轮门禁执行 selftest"经编码段实证为**未验证假设**（运行器原无执行器；单翻位 = 不执行 + PENDING 行消失 + selftest 自身 S1 断言失败），经编排师裁定 C 卡内补齐；③ `run-gates.ps1` 新增 gateSelfTest 执行器段（**全阶段之首**——selftest 清理/fixture 均写 `%TEMP%` 固定名 `ctds-gate-*-out.log`，置首保护本运行阶段取证；退出码 0/1/2→PASS/FAIL/ERROR，超时=FAIL；`Invoke-MetricProcess` 纯上移、内容零改动）；④ `selftest.ps1`：`New-FixtureConfig` 递归守卫（fixture 一律强制该段关闭并补回 `status:PENDING-SELFTEST`——演练"关闭态可见性"；该联动缺口由翻位后 selftest 回归实证捕获）+ S15 六探针（绿/空格路径/红/ERROR/hang/缺文件）+ 行序锚 + 收尾递归守卫机器扫描 + S1 标签勘误（94→125 断言**只增不减**，过渡态 111→115→125）；⑤ README 本节 + 场景表 S15 + §运行器自检措辞 + 检查项表 gateSelfTest 行 + 本留痕条目。**强度只增不减：判定阈值、阶段范围、扫描面、退出码语义、报告五态语义零改动**。实测：selftest 全量 **125 断言 0 失败**（评审修复批后；过程 111 → 翻位后回归 FAIL×1〔fixture 补 status〕+ 复核发现空格引号×1 → 115 → 评审批 +10 → 125）；启用后本地全量门禁复跑（V3）GREEN 结论见任务卡 §四。
> - **DB-43（2026-10-14，门禁可观测性增强，任务卡 `docs/tasks/DB-43-门禁可观测性增强-2026-10-13.md` + 两级设计 `docs/designs/DB-43-{lofi,hifi}.md`，编排师一次确认 Q1-A / Q2-A / D1 不拆分；`gates-config.json` 零改动——ConfigSha256 `29782aad…42c9` 逐字不变为"零放水"自证）**：① mvn 三段（compile/lint/unitTest）**成功/失败/超时三态**都落盘 `%TEMP%\ctds-gate-<stage>-out.log`（开头摘要行 `stage/command/exitCode/elapsedSec/time` + 完整原始输出——WBS-3.4.7"0 字节日志"教训的结构性根治：成功态也有机器可核证据）；② npm 段（4 阶段）与 coverage / mutationTest / sast 段**失败或超时**落盘完整原始输出，报告失败/超时行尾附 `| full output: <路径>`（DB-44 首跑 RED"错误正文未取得"损失场景的根治）；`Invoke-MetricProcess` 超时分支不再丢弃已读流；③ 阶段超时后 `Stop-ProcessTree`（`taskkill /PID <pid> /T /F`）清理整个进程树（跟踪-23 剩余子项兑现——2026-10-09"unitTest 超时后残留 cmd 孤儿进程污染 coverage/frontendTest"假红的根治）；mutationTest 多子运行失败汇总落盘（`==== <子运行名> ====` 分隔），阈值不达标分支诚实附"PIT 报告产物位于各模块 `target/pit-reports/`"。**判定阈值 / 阶段范围 / 扫描面 / 报告五态语义 / 退出码语义零改动；PASS 报告行格式零改动；落盘失败不影响段判定**（记录是加菜，不是判定）。selftest 增 S12（mvn 成功态落盘）/ S13（npm 失败落盘取证）/ S14（超时进程树清理，红相期孙进程幸存锚）三场景（十二→十五场景、70→94 断言，只增不减；场景表同步补登 S10~S11b——V1.3/V1.4 留痕条目已述但表格漏登的既有缺口，纯文档补全）；前端 `ContractSourceGuard.spec.ts` 增"同形文案双源守卫"（真读后端 `ContractErrorCodes.java` 逐字比对 8 对文案，漂移即红）。实测全仓门禁 GREEN（RunLabel `20261015-000102-6374`，PASS=14 / FAIL=0 / SKIP=2 / ERROR=0 / PENDING=6，报告 `gate-report-20261015-002442.md`；SKIP=2 = oversized 2 + binary 49，均为未入库产物）。
>   **运行说明（已知特性）**：`%TEMP%\ctds-gate-<stage>-out.log` 为固定名 = **最近一次该阶段运行**的记录（含 selftest fixture 运行——selftest 开场与收尾统一清理该前缀文件，不会把 fixture 产物留在真实门禁的记录位上）；`taskkill /T` 按父子快照清理，"已脱离父链的孙进程"可能漏网（罕见）；本脚本 Windows 定位，若未来迁 Linux 须同步改。
> - **DB-07 V1.3（2026-10-17，V4 复跑 3 取证修正批，任务卡 `docs/tasks/DB-07-CI门禁自动化-2026-10-16.md`，编排师"继续 DB-07 修复批"链上的取证修正）**：V4 复跑 3（run `39018581532`）结论 PASS=13 / FAIL=2（gateSelfTest 5 断言 + coverage 45.61%〔无 Docker 致集成测试 skip，停报裁决〕）——gateSelfTest 仍失败的根因取证 = CI 的 `%TEMP%` 本身是 **8.3 短路径**（`C:\Users\RUNNER~1\...`，GitHub Windows runner 的环境变量即短名），selftest fixture 建于其下，runner 子进程收到的 `-RepoRoot` 是短路径、`Get-ChildItem` 返回长路径 FullName；修复批 `864f9d2` 的 `cmd /c "cd /d <短路径> && cd"` **不展开短名**（cmd 的 cd 无参回显进入时给出的路径原样——本地实测 `cd /d C:\PROGRA~1` 后 `cd` 仍回显 `C:\PROGRA~1`），前缀剥离失配走全路径兜底：入库锁定文件被判"未入库"降级 SKIP（S2 期望 ERROR/exit 2 实得 exit 0）、扫描命中路径不再是仓库相对路径（S6）。**修正** = `run-gates.ps1` 入口 `Resolve-LongDirPath` 改用 `Get-Item -LiteralPath` 的 FullName（文件系统提供程序逐组件解析长名；本地实测 `C:\PROGRA~1`→`C:\Program Files`、嵌套 `C:\PROGRA~1\COMMON~1` 同样成立，PS 5.1 与 pwsh 7 双解释器一致）；`selftest.ps1` 新增 **S16** 场景（短路径 RepoRoot 重放 S6 断言，把该类回归钉成机器断言——**反向验证**：暂存修复恢复旧实现后仅 S16 一条断言失败〔133 断言 1 failure〕，证明该锚本可在 `864f9d2` 推送前拦住无效修复），130→**133 断言**只增不减。**本地复现闭环**：短路径 TEMP 下旧实现复现 CI 同型五失败（S2×4+S6×1）→ 修复后短/常规两种 TEMP 均 133 断言 0 失败；双解释器语法 0 错误。**`gates-config.json` 零改动**（ConfigSha256 `b7591621…7080` 逐字不变）；判定阈值 / 阶段范围 / 扫描面 / 退出码语义 / 报告五态语义零改动；ADR-018 V1.7 留痕。
> - **DB-07 V1.4（2026-10-17，V5 CI 覆盖率口径裁决批，任务卡 `docs/tasks/DB-07-CI门禁自动化-2026-10-16.md` §四，编排师授权"CI 覆盖率口径请你评估后，按照合理建议继续执行"）**：复跑 4 起 CI 唯一红灯 = coverage **45.61%**（无 Linux Docker → Testcontainers 集成测试静默 skip，环境性非代码缺陷）——**coverage 段环境自适应判定**：`run-gates.ps1` 新增 `Test-CiEnvironment` / `Test-LinuxDockerDaemon` 探测（`docker info` OSTYPE=linux 才算可用；非 CI 环境短路不探测），CI 且无 Linux Docker → 阈值判定降级 **SKIP**（jacoco 照常执行、实测值与未达标项与降级原因如实入报告，权威判定 = 本地有 Docker 门禁）；本地无 Docker → 维持 FAIL；执行层失败不降级。`selftest.ps1` 新增 **S17** 三探针（CI+无 Docker=SKIP/GREEN；本地+无 Docker=FAIL/RED；CI+Linux Docker=FAIL——stub mvn/docker 密封，PATH 前插遮蔽宿主机真工具；**反向验证**：暂存运行器修复后恰 S17-a 五断言失败），133→**148 断言**只增不减（十九场景）。`gates-config.json` V1.10→**V1.11**（coverage/gateSelfTest 两 note 增补与勘误 + changeLog——**零键位、零阈值、零阶段范围改动**；ConfigSha256 `b7591621…7080` → **`315264e1…1304`**）；ADR-018 V1.8 留痕（含强度申辩：CI 面 coverage 阈值判定减弱 FAIL→SKIP，全链路强度不减——本地门禁权威判定面不变 + CI 侧 unitTest/mutationTest 兜底 + 双条件机器护栏；残余风险如实登记）。验证：selftest 148 断言 0 失败 + 双解释器语法 0 错误；本地全量门禁复跑与 CI 复跑证据见任务卡 §四。
> - **V1.4（2026-09-26，清债卡1 / DB-06 质量度量门禁接入，任务卡 `docs/tasks/DB-06-23-24-前端类型-清债小卡-2026-09-25.md` 卡1 + ADR-018，编排师裁决 Q1=A）**：coverage / mutationTest / sast 三段 PENDING → enabled——jacoco 0.8.12 命令行形态（整体 ≥70% / 核心 ≥80%，`thresholds` 节自 V1.0 起首次被实际读取）；PIT 1.30.0 CLI + junit5-plugin 1.2.1（did 服务层 132 突变 / 杀除率 68.18% 实测，保守口径）；semgrep docker 形态（p/java 热点=0）；dependencyScan **如实登记维持 PENDING**（NVD 库同步预算不可行，另立卡）。selftest 增 S11（三段在裸 fixture 中 fail-visible + coverage 阈值极性探针 + 反向探针）与 S11b（mvn 退出非 0 FAIL 分支探针，复审尾巴修复）。强度只增不减；既有阶段零改动。
> - **V1.3（2026-09-26，清债卡4，任务卡 `docs/tasks/DB-06-23-24-前端类型-清债小卡-2026-09-25.md`，编排师裁决 Q4=含）**：新增 `frontendTypeCheck` 阶段——`npm run typecheck`（= `vue-tsc -b` 全工程类型检查），走既有前端 npm 循环（goals 白名单 / 300 秒超时 / UTF-8 解码全复用），防 3.1.12 观察到的既有 vue-tsc 类型错误类回归再次阻断生产构建。既有阶段与阈值零改动，强度只增不减；selftest 增 S10 红/绿双探针（typecheck 退出非 0 必 FAIL/RED/退出码 1、退出 0 必 PASS/GREEN/0——防假绿灯；fixture 以 stub 脚本替代 vue-tsc 验证接线与判定，真实链路由全量门禁运行覆盖）。实测全量门禁 GREEN（RunLabel `20260926-100833-7510`，`PASS=11 FAIL=0 SKIP=2 ERROR=0 PENDING=9`，报告 `gate-report-20260926-101728.md`）。上文阶段表与 gateSelfTest 场景数已同步。
> - **DB-18 显性化（2026-09-19，清债小卡 `docs/tasks/DB-17-21-清债小卡-2026-09-19.md`，配置零改动）**：secretsScan 三类"静默逃逸"改为报告可见——目录枚举失败记 `secretsScan.enumGap ERROR`（退出码 2）；≥1MB 超大文件与 NUL 二进制按入库/未入库区分，入库的逐条列名（`secretsScan.oversized` / `secretsScan.binary` SKIP 行），PASS 行"not scanned"细分为 excluded/oversized/binary/unreadable。检查强度只增不减；上文 SKIP 语义句已同步。前端段另设 `StandardOutput/ErrorEncoding = UTF-8`（DB-19，修复报告 FAIL 明细中文乱码，Maven 段不动）。
> - **V1.2（2026-09-15，WBS-2.2.7 第 2 轮，对应债务 DB-16）**：修复 V1.1 的排除项缺陷——`logs` 裸词经"任意层级"匹配规则连带排除了 `docs/logs/`（61 份入库开发日志）与 `services/*/logs/`（24 份运行期日志），**V1.1 中"扫描面未缩减"的声明因此失实**；同时新增上述 `git ls-files` 护栏、`gateSelfTest` 阶段与报告头字段。实测（RunLabel `20260915-160705-4156`，连跑 3 次一致）：GREEN，`PASS=10 FAIL=0 SKIP=0 ERROR=0 PENDING=9`，**扫描 546 个文件、0 命中**；同一工作区按 V1.1 规则复算为 460 个文件，故本轮**扫描面净增 86**（`docs/logs/` 62 份 + `services/*/logs/` 24 份）。
> - **V1.1（2026-09-15，WBS-2.2.7）**：修复"本地后端运行占用 `logs/app.log`，导致脚本 `ReadAllBytes` 抛异常、门禁崩溃不出报告、退出码与真红灯同形"的缺陷（REV-ALL-2026-09-15 DB-01）；其自身记录"扫描文件数由 2335 降至 463"（该说明文字中曾误写 `464`，第 2 轮已更正为 463）。

**已知观察项（不在本卡范围，登记待收敛）**：V1.2 下 `services/*/logs/`（服务运行期日志，共 24 个未入库文件）重新进入扫描范围。它们**未入库**，被占用时只记 SKIP、不影响绿灯判定，代价仅是每轮多扫若干日志文本。若要重新排除，必须写成带模块前缀的形式（如 `services/**/logs/**`）——**写成 `**/logs/**` 会被上述护栏直接拦下**（它会命中入库的 `docs/logs/`）。建议随 DB-12（运行期数据置于项目目录外）一并收敛。

## 当前检查项（版本随 `gates-config.json`，当前 V1.11）

| 检查项 | 状态 | 说明 |
| --- | --- | --- |
| secretsScan 密钥扫描 | ✅ 已启用 | 内置规则扫描私钥/密钥/口令模式（gitleaks 的过渡替代），命中即红 |
| secretsScan.excludeGuard 排除项护栏 | ✅ 已启用 | 排除项命中 `git ls-files` 入库路径即 ERROR（退出码 2） |
| structureCheck 结构检查 | ✅ 已启用 | AGENTS.md/章程/ADR/日志等必备文件齐备 |
| devLogNamingCheck 日志命名 | ✅ 已启用 | `docs/logs/` 文件名符合 `Ctds-项目开发日志-yy-mm-dd-hhss.md` 规范 |
| adrFieldsCheck ADR 字段 | ✅ 已启用 | 每份 ADR 必含：背景/决策/理由/备选/业务影响说明/影响范围/可替换性（章程 4.4 + D-3） |
| compile 编译 | ✅ 已启用（WBS 2.4.1 接入） | `mvn -B -ntp compile`，零错误 |
| lint 格式与风格 | ✅ 已启用（WBS 2.4.1 接入） | `mvn -B -ntp checkstyle:check`（config/checkstyle/checkstyle.xml），零违规 |
| unitTest 单元测试 | ✅ 已启用（WBS 2.4.1 接入） | `mvn -B -ntp test`（JUnit5 + ArchUnit 分层规则），全部通过 |
| frontendTypeCheck 前端类型检查 | ✅ 已启用（清债卡4 接入） | `npm run typecheck`（= `vue-tsc -b` 全工程类型检查），零类型错误——防既有类型错误类回归再次阻断生产构建 |
| frontendLint 前端格式与风格 | ✅ 已启用（WBS 2.4.12 接入） | `npm run lint`（ESLint 9，覆盖 src 与 e2e），零错误 |
| frontendTest 前端单元测试 | ✅ 已启用（WBS 2.4.12 接入） | `npm run test`（Vitest + jsdom），全部通过 |
| frontendE2E 前端端到端测试 | ⏸ PENDING-CI（WBS 2.4.12 登记） | `npm run e2e`（Playwright + Chromium）；CI 环境浏览器二进制供给待 2.5.x 评估，本机可手动全量 |
| gateSelfTest 运行器自检 | ✅ 已启用（DB-07 接入，2026-10-16 编排师裁定 C） | `selftest.ps1` 十九场景（S1~S14 + S11b + S11c + S15 + S16 + S17，**148 断言**；S15 = 执行器接线六探针〔绿/空格路径/红/ERROR/hang/缺文件〕+ 行序锚 + 收尾递归守卫机器扫描；S11c = sast 引擎不可用不中止整轮，V1.2；S16 = 短路径〔8.3〕RepoRoot 下扫描命中仍为仓库相对路径，V1.3；S17 = coverage 环境自适应判定三探针〔CI+无 Docker=SKIP / 本地+无 Docker=FAIL / CI+Linux Docker=FAIL〕，V5）；执行器位于**全阶段之首**，每轮门禁实跑（独立单发 32s〔94 断言〕/ V3 门禁内 41s〔115 断言〕/ V1.2 独立单发 31s〔130 断言〕/ V1.3 独立单发 33s〔133 断言〕/ V5 独立单发〔148 断言〕）；退出码 0/1/2→PASS/FAIL/ERROR，超时=FAIL；`enabled=false` 时回到 PENDING 可见行 |
| coverage 覆盖率 | ✅ 已启用（清债卡1/DB-06 接入，ADR-018；**DB-07 V5 环境自适应判定**） | jacoco 0.8.12 命令行形态（零 pom 变更）；解析各模块 jacoco.xml，整体 ≥70%、coreModules 核心 ≥80%（thresholds 首次被实际读取）；**CI 环境且无 Linux 容器 Docker 时阈值判定降级 SKIP**（实测值如实入报告、权威判定 = 本地有 Docker 门禁；本地无 Docker 维持 FAIL；执行层失败不降级——ADR-018 V1.8，护栏 selftest S17） |
| mutationTest 变异测试 | ✅ 已启用（清债卡1/DB-06 接入，ADR-018） | PIT 1.30.0 CLI + junit5-plugin 1.2.1（bundle 聚合至 target/precheck/pit-bundle）；范围 = `stages.mutationTest.modules` 独立清单（当前 did 服务层——coreModules 其余核心模块的变异扩展在 modules 表加行即可）；杀除率 ≥60%（保守口径：NO_COVERAGE 计入分母） |
| sast 静态安全扫描 | ✅ 已启用（清债卡1/DB-06 接入，ADR-018；DB-07 V1.2 改免 Docker 形态） | **semgrep 原生 CLI 优先（CI 主路径，PyPI 1.177.0）+ docker 官方镜像回退（本地）**——同引擎同版本同 `p/java` 规则集、同一 JSON 解析与判定（单实现双载体）；扫描 Java 主源集副本（规避仓库路径空格）；热点 = 0；引擎不可用 = ERROR（不中止整轮）；semgrep `errors` 非空 = ERROR（防部分扫描假绿） |
| dependencyScan 依赖扫描 | ⏸ PENDING-TOOLCHAIN（DB-06 如实登记） | OWASP Dependency-Check：首次运行需 GB 级 NVD 库同步（数小时），本机预算不可行——另立卡评估镜像/离线库方案 |
| duplication 重复度 | ⏸ PENDING | PMD CPD，新增重复行 = 0 |
| complexity 复杂度 | ⏸ PENDING | SonarQube，圈复杂度 >15 打回 |
| moduleDependency 模块依赖 | ⏸ PENDING | 跨模块依赖规则（ArchUnit 跨模块检查随多模块出现后启用） |

## 工具链

Maven 阶段需要 JDK 17 与 Maven 3.9+：脚本优先读环境变量 `JAVA_HOME`（toolchain 段的优先分支），缺省回退到 `gates-config.json` 的 `toolchain.javaHome`（当前本机 `C:\Program Files\Java\jdk-17`）；`mavenBin`/`mavenArgs` 同理可覆盖。依赖解析走用户级 `%USERPROFILE%\.m2\settings.xml`（阿里云镜像）。单阶段超时：Java 阶段默认 600 秒（config `timeoutSeconds` 可覆盖，**V1.7 起 `unitTest` 设为 1800 秒**——全仓实测波动 850~900+ 秒，跟踪-23/编排师裁决 A→A2→V1.7）；前端阶段 300 秒 / 质量度量段独立预算（**coverage 1800 秒**〔V1.7〕/ mutationTest 1800 秒 / sast 900 秒，ADR-018）/ gateSelfTest 300 秒（DB-07，实测 32~41s）。前端阶段（WBS 2.4.12 接入）在 `frontend/` 目录执行 npm 命令（workdir 可按阶段在 gates-config.json 配置）。

**工具前置探测（V1.2 起）**：`mvn` / `npm` 不在 PATH 时记 `ERROR`（退出码 2）而不是让阶段以"命令不存在"的失败面目出现——工具缺失是环境问题，不是代码结论。配置值含非法字符时**整个阶段跳过、不执行任何命令**（不得"检出后仍启动命令"）。

## 阈值管理

所有阈值集中在 `gates-config.json`（对应章程 4.2 表：核心模块行覆盖 ≥80%、整体 ≥70%、变异杀除率 ≥60%、圈复杂度 >15 打回、新增重复行=0）。**修改门禁配置属架构变更，只能经章程第 7 章流程留痕执行，禁止绕过或放宽**（`AGENTS §8 第 3 条`）。
