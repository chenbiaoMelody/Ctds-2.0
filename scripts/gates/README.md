# 质量门禁运行器（第一道防线 · WBS 2.2.2/2.2.4）

## 这是什么

本项目所有代码/文档交付前的**机器门禁**。它是一个独立脚本，不依赖任何 CI 平台——未来接入任何 CI（GitHub Actions、Gitee Go、自建 Jenkins）时，只需让流水线执行本脚本并检查退出码（0=绿灯，1=红灯），这就是"切换接口预留"（D-3 原则）。

## 怎么运行

在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\gates\run-gates.ps1
```

结束后屏幕会打印一份门禁报告（同时在 `scripts/gates/reports/` 存一份 Markdown，该目录不入库）。

## 怎么看结果

- 每个检查项三态：**PASS（绿）**、**FAIL（红，必须修复后重交，无特批）**、**PENDING（待接入）**；
- 最后一行汇总 `-> GREEN/RED`：只有 **GREEN** 才允许提交评审；
- 退出码：`0`=GREEN，`1`=RED，`2`=配置错误。

## 当前检查项（V1.0）

| 检查项 | 状态 | 说明 |
| --- | --- | --- |
| secretsScan 密钥扫描 | ✅ 已启用 | 内置规则扫描私钥/密钥/口令模式（gitleaks 的过渡替代），命中即红 |
| structureCheck 结构检查 | ✅ 已启用 | AGENTS.md/章程/ADR/日志等必备文件齐备 |
| devLogNamingCheck 日志命名 | ✅ 已启用 | `docs/logs/` 文件名符合 `Ctds-项目开发日志-yy-mm-dd-hhss.md` 规范 |
| adrFieldsCheck ADR 字段 | ✅ 已启用 | 每份 ADR 必含：背景/决策/理由/备选/业务影响说明/影响范围/可替换性（章程 4.4 + D-3） |
| compile 编译 | ✅ 已启用（WBS 2.4.1 接入） | `mvn -B -ntp compile`，零错误 |
| lint 格式与风格 | ✅ 已启用（WBS 2.4.1 接入） | `mvn -B -ntp checkstyle:check`（config/checkstyle/checkstyle.xml），零违规 |
| unitTest 单元测试 | ✅ 已启用（WBS 2.4.1 接入） | `mvn -B -ntp test`（JUnit5 + ArchUnit 分层规则），全部通过 |
| frontendLint 前端格式与风格 | ✅ 已启用（WBS 2.4.12 接入） | `npm run lint`（ESLint 9，覆盖 src 与 e2e），零错误 |
| frontendTest 前端单元测试 | ✅ 已启用（WBS 2.4.12 接入） | `npm run test`（Vitest + jsdom），全部通过 |
| frontendE2E 前端端到端测试 | ⏸ PENDING-CI（WBS 2.4.12 登记） | `npm run e2e`（Playwright + Chromium）；CI 环境浏览器二进制供给待 2.5.x 评估，本机可手动全量 |
| coverage 覆盖率 | ⏸ PENDING | JaCoCo 行/分支覆盖（核心 ≥80%、整体 ≥70%，章程 4.2），接入属工具链变更走 ADR |
| mutationTest 变异测试 | ⏸ PENDING | 核心模块出现后接入（pitest），阈值 60% |
| duplication 重复度 | ⏸ PENDING | PMD CPD，新增重复行 = 0 |
| complexity 复杂度 | ⏸ PENDING | SonarQube，圈复杂度 >15 打回 |
| sast 静态安全扫描 | ⏸ PENDING | Semgrep，安全热点 = 0 |
| dependencyScan 依赖扫描 | ⏸ PENDING | OWASP Dependency-Check，高危 = 0 |
| moduleDependency 模块依赖 | ⏸ PENDING | 跨模块依赖规则（ArchUnit 跨模块检查随多模块出现后启用） |

## 工具链

Maven 阶段需要 JDK 17 与 Maven 3.9+：脚本优先读环境变量 `JAVA_HOME`，缺省回退到 `gates-config.json` 的 `toolchain.javaHome`（当前本机 `C:\Program Files\Java\jdk-17`）；`mavenBin`/`mavenArgs` 同理可覆盖。依赖解析走用户级 `%USERPROFILE%\.m2\settings.xml`（阿里云镜像）。单阶段超时：Java 阶段 600 秒 / 前端阶段 300 秒。前端阶段（WBS 2.4.12 接入）在 `frontend/` 目录执行 npm 命令（workdir 可按阶段在 gates-config.json 配置）。

## 阈值管理

所有阈值集中在 `gates-config.json`（对应章程 4.2 表：核心模块行覆盖 ≥80%、整体 ≥70%、变异杀除率 ≥60%、圈复杂度 >15 打回、新增重复行=0）。**修改门禁配置属架构变更，只能经章程第 7 章流程留痕执行，禁止绕过或放宽**（红线 8.3）。
