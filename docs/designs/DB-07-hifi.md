# DB-07-hifi：CI 门禁自动化 · 编码契约（V0.9）

> 版本：V1.2（2026-10-17 编排师裁决 B 增补 §2-bis / §3-ter 免 Docker SAST 契约；V1.0 已确认正文除增补节与版本头外零改动）
> 前一版：V1.1（2026-10-16 编排师裁定 C 增补 §3-bis 执行器契约）
> 任务卡：`docs/tasks/DB-07-CI门禁自动化-2026-10-16.md`；方向确认见 `DB-07-lofi.md`
> 性质：非界面类编码契约；实现与本契约不一致 = 打回项

## 1. 交付物清单（编码契约）

| # | 交付物 | 落点 | 约束 |
| --- | --- | --- | --- |
| 1 | 门禁工作流（单文件） | `.github/workflows/quality-gate.yml` | 仅 GitHub 官方 action，版本 tag 钉住；零秘密 |
| 2 | 门禁配置一位变更 | `scripts/gates/gates-config.json` `stages.gateSelfTest.enabled` false→true + `changeLog` 增条 + `version` 同步 V1.8 | 除该位与 changeLog/version 外逐字不变；变更流程四件套 |
| 3 | ADR-018 修订记录 | `docs/adr/ADR-018*.md` 修订记录节增 V1.5 | 保持 ADR 七字段模板合规（adrFieldsCheck） |
| 4 | README 补节 | `scripts/gates/README.md` 新增"CI 接入（DB-07）"节 | 触发口径 / 回写三形态 / artifact 取回 / gateSelfTest 启用记录 / 新 ConfigSha256 |
| 5 | 验证产物 | `build-output/db07-*.log`（gitignored，日志记载路径与计数） | 双解释器冒烟 / selftest 回归 / 本地启用复跑 / PR 首跑 |
| 6 | 文档回填 | 任务卡 §四 + 台账 DB-07 行 + 开发日志 | 随批入库 |

## 2. workflow 结构契约（§2 逐条实现，偏离须回卡）

### 2.1 文件头

```yaml
name: quality-gate
on:
  pull_request:
    types: [opened, synchronize, reopened]
  push:
    branches: [main]
  workflow_dispatch:
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
permissions:
  contents: read
  pull-requests: write
```

- `permissions` 取最小集（读内容 + 写 PR 评论）；**不配置任何 secrets**；
- `concurrency`：同 ref 新推送取消旧跑（省额度；main 直推场景稀少，统一语义不特例）。

### 2.2 job 与步骤序（单 job，`runs-on: windows-latest`）

| 序 | 步骤 | action / 命令 | 契约要点 |
| --- | --- | --- | --- |
| 1 | 检出 | `actions/checkout@v4` | 默认 fetch-depth（`git ls-files` 只需索引） |
| 2 | JDK | `actions/setup-java@v4`（distribution: temurin, java-version: '17', cache: maven） | 注入 `JAVA_HOME`——脚本 toolchain 段的 `$env:JAVA_HOME` 优先分支读环境变量，config 的 Windows 路径回退不依赖 |
| 3 | Node | `actions/setup-node@v4`（node-version: '20'） | 前端四段（typecheck/lint/test/e2e-PENDING）npm 运行期 |
| 4 | Docker 探测 | `docker version`（`continue-on-error: true`，结果仅记入摘要） | 探测不判定——sast/Testcontainers 的可用性由门禁段自身 ERROR/SKIP 语义承担 |
| 5 | 跑门禁 | `shell: pwsh` + `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gates/run-gates.ps1`；`continue-on-error: true`；步骤末 `"exit_code=$LASTEXITCODE" >> $env:GITHUB_OUTPUT` | 与 README 规范调用逐字一致；**非 0 也继续**（回写需要报告）；退出码入 output 供终态步骤判定 |
| 6 | 写 job 摘要 | `if: always()`；PowerShell 取最新 `scripts/gates/reports/gate-report-*.md` 头部字段 + 尾部结论行 + `$env:GITHUB_OUTPUT` 的 exit_code，写入 `$env:GITHUB_STEP_SUMMARY` | 摘要含：ExitCode / 五态计数 / RunLabel / ConfigSha256 / 报告文件名；退出码 2 时注明"环境/配置错误（非质量结论）" |
| 7 | PR 评论 | `if: always() && github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository`；`actions/github-script@v7` | upsert 语义：按固定标识搜索本 PR 已有评论则更新、否则新建（不刷屏）；内容 = 摘要同款字段 + 报告尾部结论行；**不含密钥/敏感信息**（报告本身已受该约束） |
| 8 | 上传 artifact | `if: always()`；`actions/upload-artifact@v4`，name: `gate-report-${{ github.run_id }}`，path: `scripts/gates/reports/*.md` | CI 上 reports 为临时目录（脚本报告目标区自动创建）；取回供第三方逐字复核 |
| 9 | 终态判定 | `if: always()`；读步骤 5 的 `exit_code`：0 → 成功结束；非 0 → `exit 1`（job 红 = PR check 红） | **fail-closed**：退出码 1（RED）与 2（环境/配置错）一律判 job 失败；摘要已区分语义，不得把 2 放行 |

### 2.3 明确不做（契约边界）

- 不拆多 job、不用 matrix、不引三方 action（含 actionlint）、不配 secrets、不改门禁脚本与配置其他位；
- 不按路径过滤触发（文档变更同样要过 devLogNamingCheck / adrFieldsCheck / secretsScan——过滤即放水）；
- 不在 workflow 内复刻任何判定逻辑（判定只归 run-gates.ps1，D-3 单一事实源）。

## 2-bis. workflow V1.2 增补契约（2026-10-17 编排师裁决：修复扩面批准项）

| # | 增补 | 落点 | 契约要点 |
| --- | --- | --- | --- |
| 1 | 前端依赖安装 | workflow，`Set up Node 20` 之后、Docker 探测之前 | `working-directory: frontend` + `npm ci --no-audit --no-fund`；**失败即红**（依赖未装则前端三段必假红，提前失败给出真实原因）；不引入 `npm install` 回退 |
| 2 | semgrep 原生安装 | workflow，紧接前端依赖安装 | `actions/setup-python@v5`（python-version '3.12'）+ `python -m pip install semgrep==1.177.0`；**版本与 docker 镜像 tag 逐字一致**（同一引擎版，规则语义不变）；安装步骤 `continue-on-error: true`——失败不阻塞门禁，由 sast 段按"工具缺失"记 ERROR 并判红（fail-closed，且保证其它阶段证据可取回） |
| 3 | 失败证据上传 | workflow，`Upload gate report artifact` 之后、终态判定之前 | `if: always()`；artifact 名 `gate-evidence-<run_id>`；paths = `%TEMP%\ctds-gate-*-out.log` + `%TEMP%\ctds-gates-selftest-*` + `scripts/gates/reports/`；`if-no-files-found: warn`（不因缺文件改判）；用于 V4 首跑三项待定性失败的取证 |
| 4 | Docker 探测措辞 | workflow 既有步骤注释 | sast 已不依赖 Docker；探测仅记录 Testcontainers 等可能的运行期需求；**探测仍不判定** |

- Docker 探测步骤保留（信息性），**不得**因本增补删除或改为判定；
- 失败证据上传只做"取回证据"，**不改变任何阶段判定与退出码语义**。

## 3. 门禁配置变更契约（gateSelfTest 启用 · 变更流程四件套）

| 件 | 内容 |
| --- | --- |
| ① changeLog | 增条 **V1.8（2026-10-16，DB-07 CI 门禁自动化·编排师一次确认，留痕载体 = 本任务卡 + 开发日志 + ADR-018 V1.5）**：`stages.gateSelfTest.enabled` false→true——依据该段注记"未接入 CI 前固定 PENDING…接入 CI 后改 enabled=true"（本卡 = WBS 行 178 CI 流水线落地，条件达成）；selftest.ps1 十五场景 94 断言自此随每轮门禁执行（分钟级，只写 `%TEMP%`）；**强度只增不减：判定阈值 / 阶段范围 / 扫描面 / 退出码语义零改动**；`version` 字段同步 V1.8 |
| ② ADR-018 修订记录 | 修订记录节增 V1.5（七字段模板内：背景 = DB-07 CI 接入；决策 = 启用；理由 = config 注记既定条件 + 每轮自检把"运行器自身回归"纳入门禁；备选 = 不启用/另卡；业务影响说明 = 每轮 +分钟级、报告 PENDING 行 -1；影响范围 = gates-config 一位 + 每轮运行；可替换性 = 改回 enabled=false 即退回） |
| ③ ConfigSha256 基线 | 改前基线（当前 HEAD 值）与改后新值**逐字记录**于任务卡 §四 + 开发日志 + README 补节；adrFieldsCheck/structureCheck 等阶段不受影响 |
| ④ 编排师确认 | 随本卡 Q5 一次确认覆盖（Q5-A）——确认留痕落任务卡 §二 确认留痕块 |

## 3-bis. gateSelfTest 执行器契约（V1.1 增补 · 2026-10-16 编排师裁定 C 授权）

> 增补缘由：V1.0 §3① 假设"翻 `enabled=true` 即随每轮门禁执行 selftest"，运行器无此代码路径（停报证据链见开发日志 `docs/logs/Ctds-项目开发日志-26-10-16-2022.md`）；编排师裁定 C（卡内改运行器）后，本节为执行器的编码契约，与 §2 / §3 同级效力。授权留痕见任务卡文末边界变更记录。

| 项 | 契约 |
| --- | --- |
| 落点 | `run-gates.ps1` 新增 `gateSelfTest` 段（本卡唯一功能性改动；既有行零改动） |
| 位置 | **全阶段之首**（secretsScan 之前）——selftest 开场/收尾清理及其 fixture 运行均写 `%TEMP%\ctds-gate-*-out.log` 固定名，置于首位避免删除/覆盖本运行先前阶段的失败取证（DB-43 取证链） |
| 触发 | `stages.gateSelfTest.enabled -eq $true` 时执行；`false` 时由既有 PENDING 循环出可见行（不留静默关闭） |
| 调用 | `powershell -NoProfile -ExecutionPolicy Bypass -File <RepoRoot>/<stages.gateSelfTest.tool>`（tool 缺省 `scripts/gates/selftest.ps1`；README 规范调用形态；经 `Invoke-MetricProcess` 直传参数、无 shell 注入面） |
| 前置探测 | 脚本文件不存在 → ERROR 行（配置/环境问题）；`powershell` 不在 PATH → ERROR 行（同 npm 段前置探测口径） |
| 超时 | 读 `stages.gateSelfTest.timeoutSeconds`（config 设 **300**；实测全量 32s ≈ 9 倍余量，CI 硬件慢于本机）；超时 = **FAIL** + 完整输出落盘（DB-43 口径） |
| 退出码→五态 | 0 → **PASS**（详情摘录 `SELFTEST PASS - N assertion(s), 0 failure(s)` 摘要行 + 耗时秒数）；1 → **FAIL**（断言失败 = 门禁自身不可信，红灯必须修；完整输出落盘 + 行尾附路径）；2 → **ERROR**（harness/环境问题，非代码结论）；超时 → FAIL |
| 递归守卫 | `selftest.ps1` `New-FixtureConfig` 对所有 fixture 强制 `gateSelfTest.enabled=false`——真实 config 翻位后 fixture 不再继承 true，杜绝"门禁→selftest→fixture 门禁→selftest"无限递归；S15 显式改回 true 并配 stub 验证接线 |
| S15 新场景 | fixture 内 `gateSelfTest.enabled=true` + stub `selftest.ps1`：退出 0 → `gateSelfTest PASS` 行 + 退出码 0 + 详情含摘要解析值 + **无 PENDING 行**（执行器已跑的正面锚）；**stub 置于带空格目录**（tool = `scripts/gates dir/selftest.ps1`）→ 仍 PASS（锚定 `-File` 路径引号——仓库根路径含空格，未加引号会被 powershell 命令行解析截断）；退出 1 → FAIL 行 + 退出码 1 + 行尾 `full output:` 路径；退出 2 → ERROR 行 + 退出码 2；tool 指向不存在文件 → ERROR 行 + 退出码 2（绿/空格/红/ERROR/缺文件五探针） |
| S1 标签勘误 | fixture 强制 disabled 后 PENDING 行仍在，S1 断言保留；标签由"selftest not wired into CI is visible in every report"勘误为"fixture configs (gateSelfTest forced off) still show the PENDING row" |
| 辅助函数 | `Invoke-MetricProcess` 由原位置**纯上移**至阶段代码之前（内容零改动——执行器置首须可调用；diff 中呈现为迁移而非改写） |
| 证据 | 启用后本地全量门禁报告 gateSelfTest 段 PASS（真实链首验）；selftest 全量 94+ 断言 0 失败；V3 报告 + `build-output/db07-local-gate-after-enable.log` |

## 3-ter. sast 免 Docker 形态契约（V1.2 增补 · 2026-10-17 编排师裁决 B）

> 增补缘由：V4 真实 PR 首跑（run 39012488535）实证 GitHub 托管 `windows-latest` **无 Docker daemon**（`npipe` 不可达），sast 段在 `docker image inspect` 处以 native stderr 中止整轮（runner ERROR，报告未写出）。编排师裁决 **B = 换免 Docker 的 SAST 形态**，保留免费托管 Windows 跑机；A 自托管（公开仓库外部 PR 执行宿主代码的安全面）/ C 永久红灯（关闭条件不可达）/ D 拆 Linux job（破坏单 job 契约）落选。授权留痕见任务卡边界变更记录 + ADR-018 V1.6。

| 项 | 契约 |
| --- | --- |
| 形态选型 | **semgrep 原生 CLI 优先**（PyPI `semgrep==1.177.0`，官方注册表实测含 `win_amd64` wheel；Semgrep 官方文档平台表列 Windows (beta)）。落选 GitHub CodeQL：查询集与 semgrep `p/java` **规则语义不等价**（扫描面/基线均变，超本卡"扫描面零改动"红线）、Java 需构建、需 `security-events: write` 权限面、与既有 JSON 解析段不兼容（须双实现） |
| 执行路径 | 单实现、双载体：`Get-Command semgrep` 存在 → 原生调用（**CI 路径**）；否则 `Get-Command docker` 存在 → 既有 docker 形态（**本地回退**）；两者皆无 → ERROR。**判定与解析段只有一份**（同一 JSON：`results` / `errors` / 热点=0），载体差异仅在"如何启动 semgrep" |
| 扫描面 | **零改动**：仍先复制 `scanRoots` 下 Java 主源集到 `%TEMP%` 副本（规避仓库路径空格），原生形态直接扫该副本；`copied` 清单、PASS/FAIL 详情文案、错误语义均不变 |
| 判定语义 | **零改动**：退出码 -1（超时）= FAIL；非 0 = ERROR；JSON 不可解析 = ERROR；`errors` 非空 = ERROR（防部分扫描假绿）；`results` 命中数 > 0 = FAIL；0 命中 = PASS |
| 调用参数 | 原生：`semgrep --config <ruleset> --json --quiet "<scanTemp>"`（与 docker 形态同一参数集；路径加引号，`Invoke-MetricProcess` 直传参数无 shell 注入面） |
| Docker 探测健壮性 | 不再用裸 `docker image inspect ... 2>&1`（PS 5.1 `$ErrorActionPreference=Stop` 下 native stderr 会被当错误抛出——V4 首跑 `line 730` 整轮中止的根因）；改经 `cmd.exe /c "... >nul 2>&1"` 判 `$LASTEXITCODE`，失败文案同时点明"daemon 不可达 / 镜像缺失"两种可能，**不猜测具体哪一种** |
| 回退语义 | 删除 workflow 的 semgrep 安装步骤即回到 docker 形态（本地/带 Docker 的 runner 自动走回退分支），无需改运行器；反向删除 docker 回退须另行留痕 |
| 风险登记 | Windows 原生 semgrep 为官方 beta；以 V4 复跑实测为验收锚，若原生形态失败则如实记 ERROR（不伪造 PASS），按门禁红灯流程处置 |

## 4. 验证方案（测试先行语义 · 按序执行）

| 序 | 验证 | 期望 | 证据 |
| --- | --- | --- | --- |
| V1 | **双解释器语法冒烟**（编码后首动）：`[System.Management.Automation.Language.Parser]::ParseFile` 对 run-gates.ps1 / selftest.ps1 在 Windows PowerShell 5.1 与 pwsh 7 各解析一次 | 双解释器零语法错误（CI 用 README 规范调用 `powershell`） | `build-output/db07-parser-smoke.log` |
| V2 | **selftest 基线回归**（改 config 前）：本地跑 selftest.ps1 | 15 场景 94 断言 0 失败 | `build-output/db07-selftest-before.log` |
| V3 | **启用后本地全量门禁复跑**（改 config 后，单发） | GREEN（ExitCode 0）且 **selftest 段 PASS**（gateSelfTest 真实链首验——此前仅 fixture） | `build-output/db07-local-gate-after-enable.log` + 报告路径与 RunLabel/新 ConfigSha256 |
| V4 | **真实 PR 首跑**（分支推 origin 后开 PR） | workflow 自动触发 → 报告取回：ExitCode / 五态计数 / RunLabel / ConfigSha256；PR 评论与 job 摘要与 artifact 三态一致 | PR 编号 + 报告 artifact 名 + 首跑日志 `build-output/db07-ci-first-run.log` |
| V5 | **RED 定性（若首跑非 GREEN）** | 逐项定性：本卡引入（必须修复重跑）/ 既有环境性（登记承接，不以"CI 都这样"放行） | 首跑报告 + 定性表入任务卡 §四 |
| V6 | **artifact 取回核验** | 从 run 页面下载报告，与 PR 评论内容一致性人工核对（头部字段 + 结论行） | 核对记录入交付说明 |

**负向锚说明**：workflow 的"非 0 → job 红"由步骤 9 断言；"FAIL+ERROR 并存退出码 1 / 非法配置不执行 / 超时进程树清理"由 selftest S8/S9/S14 既有锚覆盖；若首跑恰为 RED，则自然取得"红 → check 红 → 评论同步"实证（不得人为制造红色提交入库）。

## 5. 边界与异常行为

| 场景 | 行为 |
| --- | --- |
| 门禁 exit 1（RED） | job 红；PR 评论与摘要明示 FAIL 项；artifact 上传 |
| 门禁 exit 2（环境/配置错） | job 红；摘要注明"环境/配置错误（非质量结论）"——**不放行、不冒充质量结论** |
| fork PR（head.repo ≠ 本仓） | 评论步骤跳过（GITHUB_TOKEN 只读）；job 摘要 + artifact 仍回写；登记边界 |
| PR 评论步骤失败（如 API 抖动） | job 失败（回写是交付物，fail-closed）；重跑即恢复 |
| 报告目录不存在（CI 新鲜克隆） | 脚本报告目标区自动创建——零额外处理 |
| 并发推送 | concurrency 取消旧跑；PR 评论 upsert 不刷屏 |
| 额度耗尽 / runner 不可用 | job 以 GitHub 侧失败形态出现——不得据此推断门禁结论；登记环境项 |

## 6. 前置检查项（编码会话第一动作，逐项实测留痕）

1. 本地 PowerShell 5.1 与 pwsh 7 均可用（`$PSVersionTable` 双留痕）；
2. `.github/workflows/` 不存在（已实测，复验）；
3. `gates-config.json` 当前 ConfigSha256 记录（改前基线，`Get-FileHash`）；
4. ADR-018 现有修订记录版本（V1.4）与七字段模板形态核对；
5. README §"这是什么" / §运行器自检 现有措辞（补节不冲突、不改动既有句）；
6. `frontend/package.json` 无 engines（已实测，复验——不在本卡补）；
7. selftest.ps1 本地基线全绿（V2）；
8. git 状态：`main` 梢、工作树干净、分支自 main 开；
9. origin 可达性与 GitHub Actions 可用性（`git ls-remote` + 首跑前确认 Actions 未被仓库设置禁用）。

## 7. 回填与收口

- 编码段完成后：任务卡 §四 编码段行 + §五 体量实测 + 台账 DB-07 行 + 开发日志；
- 验收段：4 视角评审 → 编排师验收（业务口径 = PR 首跑结论 + 三形态证据 + 零放水自证）；
- 收口段：DB-07 关闭判定（七条件）+ 台账六处 + 分支保留（删除须显式指示）。

## 8. 确认记录

> **✅ 2026-10-16 编排师会话回复"一次确认"**：**Q1~Q7 均采建议口径 A + D1 不拆分**（逐项口径见 lofi §6 确认记录：Q1-A 触发范围 / Q2-A `windows-latest` 工具链 / Q3-A 单 job 全量 / Q4-A 三形态回写 / Q5-A gateSelfTest 本卡启用〔四件套〕/ Q6-A 分支保护归编排师在 GitHub 设置执行 / Q7-A 直连 Maven Central）。**本文件编码契约自确认时生效（V1.0）**；实现与本契约不一致 = 打回项。下一关口 = 编码段（第一动作 = §6 前置检查九项逐项实测留痕）。
>
> **增补记录（V1.1，2026-10-16 编排师裁定 C）**：编码段第一日红线 4 停报（运行器无 gateSelfTest 执行器，V1.0 §3① 为未验证假设）后，编排师选定 **C（卡内改运行器）** → 授权增补 **§3-bis 执行器契约**；任务卡纪律声明 / 关闭条件③⑤ / 交付物②同步修订（任务卡文末边界变更记录）。V1.0 已确认正文除本节与版本头外零改动。
>
> **V1.1 勘误行（2026-10-17，4 视角评审循环 1 修复批）**：以下条款**被 §3-bis 与任务卡文末边界变更记录取代**（按"实现与本契约不一致 = 打回项"口径登记；已确认正文不二次改动）：① §1 交付物清单 #2 约束列"除该位与 changeLog/version 外逐字不变"——实际 config 改动面 = `enabled` 一位 + `timeoutSeconds` 新增键 + 移除 `status` + note + changeLog/version（裁定 C 授权范围④）；② §3①"selftest.ps1 十五场景 94 断言自此随每轮门禁执行（分钟级）"——翻位本身不产生执行（须运行器执行器，见 §3-bis），实为**十六场景 125 断言、秒级**（32s 独立单发 / 41s 门禁内双口径）；③ §2.2 步 6/7"报告尾部结论行"——报告实为**头部 `Result` 行**含五态计数与终态（workflow 按头部字段提取，功能等价）；④ §2.2 步 2/步 8 与 §5 的 `run-gates.ps1` 行号引用已随执行器插入与函数上移漂移——一律改**锚点名**（"toolchain 段的 `$env:JAVA_HOME` 优先分支" / "报告目标区的目录自动创建"），行号不再作为契约要素。
>
> **V1.2 变更记录（2026-10-17，编排师裁决：修复扩面批准 + Docker 策略 B）**：V4 真实 PR 首跑（run `39012488535`）结论 RED（`ExitCode 1`，PASS=8 / FAIL=6 / ERROR=1），定性四项根因（前端依赖未装 / sast 裸 `docker image inspect` 在 Stop 策略下中止 / 报告兜底未建目录 / 托管 Windows 无 Docker daemon）+ 三项待定性失败（gateSelfTest 5 断言 / coverage exit 1 / mutationTest classpath）。本版新增 **§2-bis**（workflow 增补：`npm ci`、semgrep 原生安装、失败证据上传、Docker 探测措辞）与 **§3-ter**（sast 免 Docker 形态：原生优先 + docker 本地回退、单解析段、扫描面与判定语义零改动）；同时授权 `run-gates.ps1` 两处健壮性（sast 探测不中止、报告兜底先建目录）。**三项待定性失败不在本版给结论**：按"先取证后修"原则，待 §2-bis 失败证据上传生效后由复跑日志逐条定性再修（不得凭猜改断言）。
