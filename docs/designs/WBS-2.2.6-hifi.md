# WBS-2.2.6 制品与镜像流水线 —— 高保真设计（定稿 = 编码契约）

- 承接：`docs/designs/WBS-2.2.6-lofi.md`（方向确认后定稿）
- 交付物清单：`scripts/pipeline/nightly-build.ps1`、`scripts/pipeline/image-tag.ps1`、`scripts/pipeline/README.md`、`docs/adr/ADR-012-制品与镜像流水线规范.md`、frontend/package.json（+cyclonedx-npm devDep）、`docs/dependencies.md`（+2 行）、`.gitignore`（+build-output）。**根 pom 不改**（cyclonedx-maven-plugin 以全坐标带版本命令行调用，不进 build 生命周期，见 §4）。既有门禁 `scripts/gates/*` 零改动（nightly 直接以子进程复用其入口）。
- 状态：**已确认（2026-09-12，与 lofi 同一次 AskUserQuestion 选定"确认进入编码"，确认记录见 lofi §6；确认后补正：交付物清单中"根 pom 不改"口径已生效，cyclonedx-maven-plugin 不进 build 生命周期）**

## 7. 补正说明（编码与实测期）

- **根 pom 不改**（原交付物清单提及"根 pom（+cyclonedx-maven-plugin）"作废）：cyclonedx-maven-plugin 以全坐标带版本命令行调用，不进 build 生命周期（§4 已载明，此处修正清单措辞）。
- **门禁 1 处增量配置**（原"既有门禁 scripts/gates/* 零改动"表述修正）：`gates-config.json` secretsExcludePaths 追加 `build-output`。实测发现：secretsScan 全仓枚举 <1MB 文件逐个 ReadAllBytes，会读到 nightly 正在写入的阶段日志（文件被打开写入）→ 独占冲突崩溃误报 FAIL；且未来每次 nightly 对上一轮构建产物（jar/SBOM/日志，gitignore 零源码）扫描只会产生假阳性。与 node_modules 排除先例（b7330f5）同口径，任何检测阶段行为不变。留痕：ADR-012 §5。
- **PS 5.1 实测教训（已固化进脚本注释）**：① `$ErrorActionPreference=Stop` 下原生命令向 stderr 输出（vite/npm 进度）会被升级为终止性 NativeCommandError（N4 曾假失败——vite 实际构建成功）；② `Select-Object -First 1` 会提前终止上游原生命令管道使 `$LASTEXITCODE` 变 -1（N1 探测曾全数误报）；③ 函数内 Write-Output 会污染返回值，调用方布尔判定变数组恒真、fail-fast 静默失效（第一轮曾"假 PASS"）——进展输出一律改 Write-Host，阶段体参数化传值。

## 1. 业务可读行为清单（N1–N7 逐条，可验收口径）

**N1 环境自检**
- N1-1 依次探测 java（17）、mvn、node（≥20）、npm、git：缺失或版本低于要求 → 汇总列出全部缺失项后以退出码 2 结束（一次性报全，不逐个撞墙）。
- N1-2 docker 仅探测并记录"可用/不可用"到报告（本任务不构建镜像，不可用不阻断）。
- N1-3 自检通过 → 创建本次输出目录 `build-output/<yyyymmdd-hhmmss>/`（秒级时间戳，下称 OUT）。

**N2 质量门禁**
- N2-1 调用 `scripts/gates/run-gates.ps1`（既有入口、既有配置，一字不改其阶段行为）；退出码非 0 → nightly 立即停止，退出码 1，报告记 FAIL。
- N2-2 门禁报告（若生成）复制一份进 `OUT/reports/`，汇总报告标注门禁结论与耗时。

**N3 后端打包**
- N3-1 根目录执行 `mvn -B -ntp package -DskipTests`（测试与检查已由 N2 门禁完整跑过，此处只产构件）；失败即停（退出码 1）。
- N3-2 收集全部模块 jar（不含 sources/javadoc）到 `OUT/backend/<模块名>/`；example-service 的可运行 jar 额外在汇总报告中标注"可运行"。

**N4 前端打包**
- N4-1 `frontend/` 内执行 `npm run build`（vue-tsc 类型检查 + vite 构建）；失败即停。
- N4-2 收集 `frontend/dist/` 到 `OUT/frontend/dist/`（不含 node_modules）。

**N5 SBOM 生成**
- N5-1 后端：根 pom 执行 `mvn -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DskipTests`，产出聚合 BOM（XML+JSON）；收集到 `OUT/sbom/backend/`。
- N5-2 前端：`frontend/` 内执行 `npx @cyclonedx/cyclonedx-npm --output-file <OUT>/sbom/frontend/bom.json`（另有 `--package-lock-only` 以锁定文件为准，不触发安装）；同命令补出 XML。失败即停。
- N5-3 对每个 SBOM 文件计算 sha256 写入 `OUT/sbom/SHA256SUMS.txt`（对账口径：报告引用此文件）。

**N6 镜像 tag 计算**
- N6-1 `image-tag.ps1 -ModuleName <模块>`：按 ADR-012 规范输出 canonical tag（stdout 单行，便于 2.5.1 脚本/编排模板直接消费）；`-All` 输出全部模块清单到 `OUT/image-tags.txt`。
- N6-2 规范：`ctds/<模块名>:<版本>-<yyyymmdd-hhmm>-<git短哈希>`；git 工作区有未提交改动追加 `-dirty`；版本来源：Maven 模块取根 pom `2.0.0-SNAPSHOT`，frontend 取 `package.json` version，connector-sdk（独立仓库策略，ADR-002）列名占位并标注"SDK 仓库另计"。

**N7 汇总报告**
- N7-1 `OUT/nightly-report.md`：每阶段 PASS/FAIL + 耗时 + 产物清单（相对路径）+ SBOM 校验文件引用 + 镜像 tag 清单引用 + 环境信息（工具版本）；业务可读（与门禁报告同风格）。
- N7-2 全流程成功 → 控制台打印 OUT 路径与"PASS"结论，退出码 0。

**通用行为**
- G-1 失败即停：任一阶段非零退出 → 立即停止后续阶段，已产出的部分保留现场，汇总报告标注失败阶段与原因（最后 30 行错误摘录）。
- G-2 输出目录 `build-output/` 加入 .gitignore（产物不入库，报告口径同门禁"本地 gitignore 目录"惯例）。
- G-3 幂等重跑：每次新建独立时间戳目录，不清理历史（人工按需删）；同秒冲突（理论边界）自动追加 `-1` 序号。

## 2. 接口契约表（对 2.5.1/2.5.2 消费方）

| 契约 | 定义 |
| --- | --- |
| tag 计算命令 | `pwsh/powershell scripts/pipeline/image-tag.ps1 -ModuleName <模块名>` → stdout 单行 canonical tag；`-All` → 全清单 |
| tag 命名规范 | `ctds/<模块名>:<版本>-<yyyymmdd-hhmm>-<git短哈希>[-dirty]`（ADR-012 §命名） |
| 输出目录契约 | `build-output/<时间戳>/{backend/<模块>/, frontend/dist/, sbom/{backend,frontend}, reports/, image-tags.txt, nightly-report.md}`（ADR-012 §目录） |
| 模块清单 | Maven：6 common + std-adapter + example-service；前端：frontend；占位：connector-sdk |
| nightly 入口 | `powershell scripts/pipeline/nightly-build.ps1`；退出码 0=全绿 / 1=阶段失败 / 2=环境自检失败（与门禁 0/1/2 契约同形） |

## 3. 边界值与异常行为

| # | 场景 | 期望行为 |
| --- | --- | --- |
| B-1 | mvn/node 等工具缺失 | N1 汇总全部缺失项，退出码 2，不产生输出目录 |
| B-2 | 门禁非 GREEN（含 FAIL/PENDING 状态定义按 run-gates 既有契约） | 停在 N2，退出码 1，报告记门禁结论 |
| B-3 | 后端/前端构建失败（实测项：制造前端构建失败一次） | 停在对应阶段，退出码 1，报告含最后 30 行错误摘录，已产出部分保留 |
| B-4 | git 工作区脏 | tag 追加 `-dirty`，流程不阻断 |
| B-5 | docker 不可用 | 仅记录，不阻断 |
| B-6 | 重复执行 | 各自独立时间戳目录，互不覆盖 |
| B-7 | `frontend/package-lock.json` 与 package.json 不同步 | cyclonedx-npm 以 lock 为准并按其内置行为告警/失败（失败即停，提示先同步锁文件） |

## 4. 依赖核验与登记（引入前 4 步，章程 3.5）

| 依赖 | 版本 | 核验 | 拟登记行 |
| --- | --- | --- | --- |
| `org.cyclonedx:cyclonedx-maven-plugin` | 2.9.3 | repo1.maven.org 官方 maven-metadata.xml（latest/release=2.9.3，2026-09-12 实测）；Apache-2.0 | 根 pom 无需显式锁版（命令行插件直接带版本号调用，版本唯一出处=nightly 脚本+本设计+dependencies.md；若评审要求 build 插件化则改根 pom build/plugins 显式锁版） |
| `@cyclonedx/cyclonedx-npm` | 6.0.1 | npmmirror 官方注册表 latest=6.0.1（2026-09-12 实测）；Apache-2.0；engines node≥20.18（本机 24.14 满足）；无 peerDependencies | frontend devDependencies 显式锁 6.0.1 + package-lock.json 随分支提交 |

- 审批口径：PO 预授权（lofi 问题 2/3 确认记录），沿 2.4.5 起惯例。

## 5. 验收剧本更新建议（业务语言）

- 建议增补"制品包装线演示"节点：运行 `powershell scripts/pipeline/nightly-build.ps1` → 打开报告中的 build-output 目录，看"后端 jar / 前端页面产物 / 两份材料清单（SBOM）/ 镜像命名清单 / 汇总报告"五样齐全；再打开 SBOM 文件检索 `element-plus` 或 `bouncycastle` 能命中（证明清单真实列出了第三方组件）。既有节点无需修改。

## 6. 映射表（规格验收标准 → 设计 → 验证）

| WBS 2.2.6 产出定义 | 对应设计 | 验证方式 |
| --- | --- | --- |
| nightly 构建 | N1–N4 + G-1/G-2（本地脚本版，调度 PENDING-CI 留痕） | 实跑全流程 + B-3 失败即停实测 |
| 镜像 tag | N6 + 契约表 + ADR-012 §命名 | `-All` 清单核对 + 脏工作区 B-4 |
| SBOM 产出 | N5 + ADR-012 §SBOM 范围 | 产物存在 + sha256 + 内容抽查命中已登记依赖 |
