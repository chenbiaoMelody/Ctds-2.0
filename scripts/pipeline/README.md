# 制品与镜像流水线（WBS 2.2.6，规范载体 ADR-012）

本目录是"出厂包装线"：一条命令把整个项目完整构建一遍并自动产出"五件套"（后端 jar、前端页面产物、两份第三方组件材料清单 SBOM、镜像命名清单），附一份业务可读的汇总报告。**质量把关不在这里**——包装线第一步先跑既有质量门禁（`scripts/gates/`），全绿才继续打包。

## 日常使用（业务可读）

| 你想做什么 | 命令（仓库根目录执行） | 看什么 |
| --- | --- | --- |
| 跑一次完整构建 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pipeline\nightly-build.ps1` | 结尾输出 `[NIGHTLY] PASS`；报告在 `build-output/<时间戳>/nightly-report.md` |
| 算某个模块的镜像名 | `powershell ... scripts\pipeline\image-tag.ps1 -ModuleName example-service` | stdout 一行，如 `ctds/example-service:2.0.0-SNAPSHOT-20260912-1430-1bfb279` |
| 算全部模块镜像名 | 同上换 `-All` | 每模块一行 |

- 退出码：0=全绿，1=某阶段失败（报告含失败阶段与最后 30 行错误摘录，已产出部分保留现场），2=环境自检失败（java/mvn/node/npm/git 缺失会一次性列全）。
- `build-output/` 整目录不入库（.gitignore）。
- 镜像本身**不在本任务构建**（容器化是 2.5.1）；本目录只定命名规则并算出名字。

## 阶段一览

N1 环境自检 → N2 质量门禁（复用 run-gates.ps1，非 GREEN 即停）→ N3 后端打包（`mvn package -DskipTests`，测试已由门禁跑过）→ N4 前端构建（`npm run build`）→ N5 SBOM（CycloneDX：后端聚合 BOM + 前端按锁定文件出 BOM，JSON+XML，附 sha256 对账文件）→ N6 镜像 tag 计算 → N7 汇总报告。

## 边界与限制（留痕）

- 定时调度与远程制品库推送：**PENDING-CI**（与 frontendE2E 同口径，待 2.5.x CI 接入时平移）。
- docker 不可用不阻断（本任务不构建镜像，仅探测记录）。
- 前端 SBOM 以 `frontend/package-lock.json` 为准（--package-lock-only）；若 lock 与 package.json 不同步，cyclonedx-npm 的失败或告警行为以其内置口径为准——脚本按"命令退出码非零即停"实现，若该工具对轻度不同步只告警不退出，则不会阻断流水线（假设留痕，评审④）。
- 版本锁定：`cyclonedx-maven-plugin` 2.9.3（脚本内显式坐标）、`@cyclonedx/cyclonedx-npm` 6.0.1（frontend devDependencies）；升级走依赖变更流程并同步本 README 与 dependencies.md。
