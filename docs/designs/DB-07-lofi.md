# DB-07-lofi：CI 门禁自动化 · 方向确认稿（V0.9）

> 版本：V0.9（2026-10-16 立卡段提交，待编排师一次确认转 V1.0）
> 任务卡：`docs/tasks/DB-07-CI门禁自动化-2026-10-16.md`；编码契约见 `DB-07-hifi.md`
> 性质：非界面类（无界面交付）；章程 2.6.3 ≤1 天任务卡两级同批一次确认

## 1. 目标与非目标

**目标（业务语言）**：让"机器门禁"从"靠人记得在本地跑"变成"每次提交 / 每个 PR 自动跑、结论自动回到 PR 上"——交付前绿灯不再依赖人的自觉，第三方（评审、编排师）在 PR 页面即可看到本次门禁结论与完整报告。同时把门禁运行器的自我检查（selftest，15 场景 94 断言）从"从未在真实链路跑过"变成"每轮门禁都跑"。

**非目标（明确不做）**：

- 不改门禁脚本（`run-gates.ps1` / `selftest.ps1` 零改动——CI 只调用、不改造）；
- 不改任何判定阈值 / 阶段范围 / 扫描面 / 退出码语义 / 报告五态语义；
- 不做阶段拆分并行、不迁 Linux、不接 E2E 与 dependencyScan、不设分支保护（GitHub 仓库设置归编排师）；
- 不引入任何新依赖（仅 GitHub 官方 action）；workflow 零秘密。

## 2. 方案对比（方向级）

| 方案 | 做法 | 业务影响 | 结论 |
| --- | --- | --- | --- |
| **A（建议）** | **单 job · windows-latest · 全量**：一个 job 顺序跑 `run-gates.ps1` 全部阶段，退出码即 PR check 结论；concurrency 同 ref 取消旧跑 | 零门禁改动；PR 反馈约 60~100 分钟；结论单一可核 | **建议** |
| B | 分段并行 job（unitTest / coverage / frontend / 度量各一 job） | 反馈快约 3 倍，但须把"一次全量运行"拆成多次部分运行 = 门禁配置/脚本变更（红线 3），且结论聚合逻辑新增 | 不采（超范围） |
| C | ubuntu-latest + 门禁脚本迁 Linux | runner 生态与 Docker 支持更好，但脚本 Windows 定位（`%TEMP%`/`taskkill`/`cmd.exe`/JDK 路径）迁移 = 门禁脚本变更 | 不采（超范围） |
| D | 自建 runner（自有 Windows 机器） | 无额度顾虑、硬件已知，但新增基建面与运维负担（Docker/工具链/可用性） | 不采（本轮无收益） |

## 3. 决策点（Q1~Q7 + D1，待一次确认）

| 编号 | 问题 | 备选与业务影响 |
| --- | --- | --- |
| **Q1** | 触发范围 | **A（建议）**：PR 全分支（opened/synchronize/reopened）+ push 仅 main + 手动 dispatch——每次待合并变更必跑、主干合入后必跑、可手动重跑；**B**：仅 PR——main 合入后无自动验证（如直推 main 或 squash 边缘场景漏验）；**C**：push 全分支 + PR——同一变更双跑，翻倍消耗额度 |
| **Q2** | Runner 与工具链 | **A（建议）**：windows-latest + setup-java temurin 17 + setup-node 20 + runner 自带 Docker（sast 镜像与 Testcontainers 需要，首跑实证）；**B**：自建 runner——硬件/网络可控但新增运维面；**C**：ubuntu + 脚本改造——门禁脚本变更，超范围 |
| **Q3** | 任务组织 | **A（建议）**：单 job 全量 + concurrency cancel-in-progress（同 ref 新推送取消旧跑，省额度）；**B**：分段并行——须拆阶段运行（门禁变更），不采 |
| **Q4** | 结论回写形态 | **A（建议）**：三形态——① job 摘要（run 页面直接看）② PR 评论（退出码语义 + 五态计数 + RunLabel + ConfigSha256，同 PR 更新不刷屏）③ artifact 上传报告（CI 上 reports 目录是临时产物，取回供第三方逐字复核）；**B**：仅 artifact——PR 面不可见，弱；**C**：三方 status 服务——新依赖面 |
| **Q5** | gateSelfTest 启用时机 | **A（建议）**：本卡含启用（config 一位 false→true + 变更流程四件套）——config 注记明文"接入 CI 后改 enabled=true"，本卡即条件达成时点；拖延无收益且每轮报告继续显示"自检未跑"；**B**：另卡启用——多一次立卡/确认/评审往返 |
| **Q6** | main 分支保护 | **A（建议）**：本卡验收通过后由编排师在 GitHub 仓库设置开启"require status checks"（AI 无仓库设置权限——呈报待执行）；**B**：不开启——维持人工把关，CI 结论仅可见不强制 |
| **Q7** | CI 依赖解析镜像 | **A（建议）**：runner 直连 Maven Central（runner 在境外基础设施；阿里云镜像是国内网络优化，为 CI 新增 settings 文件只添配置面）；**B**：仓库内置 CI 专用 settings.xml |
| **D1** | 体量 / 拆分 | **不拆分**：workflow 单文件 + config 一行 + 文档三处 + 验证，≈250~400 行，单目标原子交付 |

## 4. 边界与风险（诚实登记）

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| CI 硬件慢于本机（unitTest 本地 850~900s / coverage·mutation 各 1800s 预算） | 首跑可能触阶段超时 → 环境性 RED | 首跑如实取回报告；触预算 → 走门禁变更流程（V1.8 预算校准 + 编排师批准），**不私自放宽、不以 PENDING 冒充** |
| windows-latest 的 Docker 可用性（sast semgrep 镜像 + Testcontainers MySQL） | 不可用 → sast ERROR / 集成用例 SKIP（既有语义） | 首跑实证；ERROR 按"环境问题"定性（退出码 2），修环境后重跑，不放行 |
| 免费额度（私有库 2000 分钟/月；单跑 ~60~100 分钟） | PR 频繁时月额度可能吃紧 | concurrency 取消旧跑 + 触发范围（Q1-A）控制；额度观测写入首跑交付说明 |
| fork PR 的 GITHUB_TOKEN 只读 | 外部 fork PR 评论步骤 403 | 评论步骤加仓库同源判断（`head.repo.full_name == repository`），fork PR 跳过评论仅 job 摘要 + artifact（登记边界） |
| gateSelfTest 阶段首次在真实运行器执行（此前仅 selftest fixture 验证） | 可能暴露仅真实链路可见的问题 | 启用后**本地全量复跑**即真实链首验（关闭条件③），问题在本卡内修复 |
| workflow YAML 无自动化 lint（不引 actionlint） | 语法错误只能首跑发现 | 编码段前置：本地双解释器语法冒烟（PowerShell 5.1/7）+ 首跑实证；如引 actionlint 须走依赖登记（本卡不引） |

## 5. 体量与里程碑

- 编码段 ≈250~400 行（workflow 80~150 + config 1 行 + README ≈20 + ADR-018 ≈15 + 验证与文档 ≈80）；
- 里程碑：一次确认 → 编码（含本地启用复跑）→ 真实 PR 首跑 → 4 视角评审 → 编排师验收 → 收口；
- 每里程碑结论以开发日志 + 台账承载；首跑报告 = artifact + PR 评论双留痕。

## 6. 确认记录（待编排师填写）

> （一次确认回执将签署于此：Q1~Q7 裁定 + D1；确认后本文件与 hifi 转 V1.0，编码契约即时生效）
