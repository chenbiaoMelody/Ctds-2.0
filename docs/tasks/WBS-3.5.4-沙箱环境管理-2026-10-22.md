# WBS-3.5.4 任务卡：沙箱环境管理（2026-10-22）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账/上一日志续点“下一候选包待编排师指示”；编排师 2026-10-22 会话选择“**启动 WBS-3.5.4 立卡段**”；冷启动承接 `docs/logs/Ctds-项目开发日志-26-10-22-2103.md`（WBS-3.5.3 收口段；PR #8 MERGED `d25e781`）。 |
| 级别与性质 | L1-3 平台功能**实施包**（WBS 行 291，预算 **2 会话**）；**非界面类**；≤2 天 → lofi + hifi 同批 **V0.9** 提交、一次确认转 V1.0（沿 3.5.2 / 3.5.3 先例）；**既有模块扩展**（`delivery-service` 内新增沙箱子域）+ Flyway V2 + 容器运行时适配器；**零新 Maven 依赖**；预计新增 1 条**容器基础镜像**（Q3-A，需登记 + 审批）。 |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 291**（本卡：`沙箱环境管理 \| 容器隔离环境、资源配额、生命周期管理 \| 3.5.1 \| 2`）+ 行 288~294（3.5 段上下文与下游 3.5.5 / 3.5.6 / 3.5.7）；规格 `docs/specs/C-5.1-5.2-交付与沙箱.md` **V1.1 行为 1（交付准入与凭据，L45~L62）/ 行为 4（沙箱申请与开通，L101~L119）/ 行为 5（沙箱内使用与隔离，L121~L137）/ 行为 7（访问边界与治理例外，L159~L176）** + §4-1（数据模型与端点归 3.5.2~3.5.5，L180）+ §6-3（“原始数据不出域”的平台侧口径与诚实边界，L211）+ §7 **Q5-A（平台侧容器隔离）/ Q7-A（策略消费不复制、放行产生事件）/ Q8-A（`sandboxes` 命名 + 1009 段位）/ G1-A（沙箱形态口径，2026-10-22 PO 批准）**；PRD V1.0 行 137（C-5.2 数据沙箱 P0）+ V2.0 行 190；剧本 `docs/manuals/C-5.2-数据沙箱-验收剧本.md` **V1.1**（S1 / S2 / S3；G1 留痕）；ADR-016 §2.7（演示期仅本机可达）/ §6（消费方登记）、ADR-005（API 规范 / 错误码 / 幂等头 / 分页）、ADR-007（幂等）、ADR-020 §2.8（策略集成面）、ADR-013（容器化模板 / 基础镜像显式 tag）、ADR-001（技术基线）。 |
| 上游能力边界 | **3.5.1 规格 + 两剧本 V1.0 已闭环 `8a1ae98`**；**3.5.2 网关交付链已闭环 `b689f67`**；**3.5.3 交付记录宿主 + 文件交付已闭环 `d25e781`**——`delivery-service`（Servlet，8087，`ctds_delivery`，Flyway V1）已有：交付记录 `deliveries`、配置化文件源 `ctds.delivery.files[]`（fileId→productId / 相对路径 / 展示名，启动 + 请求时 realpath 复核）、`ContractInternalClient`（内部只读摘要 + `check`，`delivery-internal` 已授权）、`CatalogInternalClient`（`productType`）、`DeliveryExceptionHandler`（1009 段 0005~0009 / S0002~0003）、`DeliveryAuditService`、`Clock` Bean、`common-*` 六组件；contract 内部摘要 / `check` 与 catalog `productType` **本卡零改动**（同服务身份，ADR-016 §6 不新增）；测试基线 delivery **56**（1 例 Windows 符号链接权限跳过）/ contract **244** / catalog **182** / gateway **35** / 前端 **440**；端口 **8087** 不变；`gates-config.json` **V1.12**（ConfigSha256 `955bdbba…2ee2`，本卡预计零改动）；**本机 Docker CLI 可用但 daemon 当前未运行（2026-10-22 实测）**；全仓无 `@Scheduled`、无 `ProcessBuilder` 生产用法；`FileSourceRegistry` 无“按产品列举”方法（本卡加性）。 |
| 交付物 | ① **沙箱子域**（`com.ctds.delivery.sandbox.*`）：任务 / 作业 / 留痕模型、准入链、配额、生命周期、到期回收、启动对账 ② **DB V2**：`sandbox_task` / `sandbox_job` / `sandbox_action_log` / `sandbox_no_seq` / `sandbox_job_no_seq` ③ **运行时适配器**：`SandboxRuntime` 端口 + `DockerCliSandboxRuntime`（argv、无 shell、安全基线）+ 工作区存储（输入副本只读 / 输出可写 / 结果元数据）④ **受控作业**：配置化作业清单 + 合约 `check`（一次使用一次计数）+ 使用留痕 ⑤ **数据面零出口**：`/data/**` 一律 403 + 留痕；结果物仅元数据 ⑥ **读面**：参与者 / 治理 / 作业历史 / 结果元数据；非参与方同码同文 ⑦ **错误码** 1009C0010~0014 / 1009S0004~0005 + 出站映射 ⑧ **测试**（红相先行、双向用例、Fake 运行时 + Testcontainers；真 Docker 用例默认跳过）⑨ **契约** ADR-024 沙箱环境契约 + `dependencies.md` 容器基础镜像 1 行（Q3-A）⑩ **部署**：k8s delivery 不注入 docker socket、沙箱运行时默认关闭（fail-closed）⑪ 任务卡 / lofi / hifi / 台账 / 开发日志 ⑫ **剧本答案（G0）**：C-5.2 剧本 **无需修订**（界面入口仍待 3.5.6；作业 / 数据面属接口实现面）；G1 批准后剧本转 **V1.1**，仅维护说明第 6 条留痕（步骤与业务判定一字未改）。 |
| 关闭条件 | ① 两级设计获编排师一次确认（**Q1~Q9 + G1 + D1**）——✅ 2026-10-22 21:25 达成；② 规格行为 1 / 4 / 5 / 7 逐条有承载与测试（§三映射表回填）；**G1 经 PO 批准或明确登记**——✅ 2026-10-22 PO 批准（规格 V1.1）；③ 用例只增不减 + checkstyle 0 + 全仓门禁 **GREEN**（`gates-config.json` 零改动，ConfigSha256 与基线逐字一致）；④ 4 视角评审 + 独立复审 PASS；⑤ 真链走查（Docker 环境）§11 七组判定表全 PASS；⑥ 编排师验收通过；⑦ **剧本答案**：C-5.2 **无需修订**，其余剧本无需更新；⑧ 零越界（交付域外零改动、零新 Maven 依赖、无内容 / 路径 / 容器 / 镜像 / 命令 / 密钥入日志·DB·审计）。 |
| 分支（建议） | `feat/WBS-3.5.4-沙箱环境管理`（已自当前梢 `43a8d10` 创建；承接 3.5.3 收口批 `4d80845` 与推送补记，随本卡 PR 一并入库；**立卡与确认批 `8103d84` 已提交并推送 origin（本地 == origin），`main` 未动、未建 PR**） |
| 设计文件 | `docs/designs/WBS-3.5.4-lofi.md`（**V1.0 已确认，2026-10-22 即时生效**）/ `docs/designs/WBS-3.5.4-hifi.md`（**V1.0 编码契约，2026-10-22 即时生效**）；非界面类，无原型 HTML |
| 纪律声明 | 只做 WBS 行 291 的容器隔离环境 / 资源配额 / 生命周期管理 + 行为 5 的接口承载；**不实现规格外功能**（审核导出归 3.5.5、界面归 3.5.6、计量归 3.7.5、上链归 3.8.3、连接器归 3.6.x）；**不自行实现密码学**（SM3 一律 `common-crypto`）；**禁止内容 / 路径 / 容器 / 镜像 / 命令 / 密钥入日志、DB、审计与提示词**；**不改既有端点语义 / 既有错误码码值 / 门禁配置 / ADR-001 技术基线条目**；**生产 k8s 不注入 docker socket**；**规格缺口走变更流程（G1）**，不在本卡私自补需求；发现冲突或疑似 S0 安全问题 → **立即停止上报**（红线 4）。 |

---

## 一、范围与设计

- **做什么 / 不做什么 / 边界值**：见 `docs/designs/WBS-3.5.4-lofi.md` §1、§2、§5；
- **待确认决策点**：lofi §3（**Q1~Q9 + G1 + D1**）；**体量**：lofi §4（预估 **2200~2600 行**有效变更；D1 建议不拆分、2 个编码会话）；
- **编码契约**（模块装配 / 数据模型 / 准入与生命周期 / 运行时适配器 / 工作区 / 作业与策略 / 端点与错误码 / 留痕 / 测试 / 前置检查 / 走查判定表）：见 `docs/designs/WBS-3.5.4-hifi.md` §1~§13。

### 立卡前素材盘点（只读，本会话；定点核查 + 代码侧实测）

| 项 | 结论 |
| --- | --- |
| ① WBS 锚点 | 行 291 原文核对（产出 = 容器隔离环境 / 资源配额 / 生命周期管理，依赖 3.5.1，预算 2 会话）；下游 3.5.5（审核导出）/ 3.5.6（界面）/ 3.5.7（测试集成） |
| ② 规格锚点 | 行为 1 规则 1/2/4（凭生效合约 / 发起人唯一 / 防枚举）、行为 4 规则 1~5（凭合约 / 隔离环境 / 配额与生命周期 / 可查留痕 / 不绕合约）、行为 5 规则 1~5（数据带不出 / 环境隔离 / 使用留痕 / 策略生效 / 配额到顶即止）、行为 7 规则 1/2/3/5（默认拒绝 / 治理例外 / 服务端强制 / 留痕不含原文）；§4-1 端点与模型归实施包；§7 Q5-A/Q7-A/Q8-A 已裁决 |
| ③ 剧本锚点 + **G0 答案** | C-5.2 S1-1~S1-5（申请 / 配额可见 / 环境可用 / 提供方只读 / 治理留痕）、S2-1~S2-4（使用留痕 / 直导出被拒 / 跨沙箱被拒 / 约定外用途被拒）、S3-6（到期后续用被拒）/ S3-7（未生效·已终止）/ S3-8（非参与方）/ S3-9（配额耗尽）/ S3-10（非需求方申请）/ S3-11（全量无本体）**均可由本卡承载**；S2-5~S2-8 与 S3-1~S3-5 归 3.5.5；界面入口为 3.5.6 占位 → **剧本无需修订** |
| ④ ADR 锚点 | ADR-016 §2.7（演示期仅本机可达）/ §6（同服务身份不新增消费方）；ADR-005（统一响应 / 九位错误码 / `X-Idempotency-Key` / 分页）；ADR-007（幂等结果复用 + DB 唯一键）；ADR-020 §2.8（`check` 消费不复制、放行即计数）；ADR-013（基础镜像显式 tag、禁 latest）；ADR-001（技术基线冻结） |
| ⑤ 代码侧核查 | `delivery-service` 40 个 Java 文件 / Flyway V1 / 8087 / `ctds_delivery`；`DeliveryCommandService`（准入链先例）、`DeliveryQueryService`（读面先例）、`DeliveryAuditService`（留痕单点先例）、`DeliveryExceptionHandler`（1009 出站映射）、`FileSourceRegistry`（文件源校验；**无按产品列举方法**）、`DeliveryBeanConfig#clock`；`DeliveryApplication` 无 `@EnableScheduling`；全仓无 `@Scheduled`；Docker CLI 可执行但 **daemon 未运行**；`docker images` 不可查（daemon 不可达） |
| ⑥ 门禁与依赖面 | **零新 Maven 依赖**（复用 common-*、JDK HttpClient、Flyway/MySQL/Testcontainers 既有坐标）；**新增容器基础镜像 `python:3.12-alpine`（Q3-A）须登记 `docs/dependencies.md` 容器基础镜像节并审批**；`gates-config.json` 不列举服务模块 → 既有模块新增测试默认纳入；**SAST 关注 `ProcessBuilder` 规则命中**（hifi §12 处置） |
| ⑦ 测试与体量面 | 基线 delivery **56**（1 跳过）/ contract **244** / catalog **182** / gateway **35** / 前端 **440**（编码段开工逐项复跑实测）；本卡新增沙箱用例约 **45~50**（准入 12 / 生命周期 10 / 作业与策略 10 / 数据面 4 / 运行时 argv 6 / 留痕 4 / 配置 3）+ 真 Docker 4 例（默认跳过）；红相归档 `build-output/w354-red-*` |
| ⑧ 演示面 | 需要：Docker Desktop 在线 + `python:3.12-alpine` 镜像 + 数据集合约（C-4.2 产出 / 演示库已有 CO000026 等）+ 该产品已登记数据文件 + `CTDS_SANDBOX_WORKSPACE_ROOT` / `CTDS_SANDBOX_JOBS_ROOT` / `CTDS_SANDBOX_RUNTIME_ENABLED=true`；演示 TTL 可调短（如 5 分钟）以演示到期；**演示数据清理仍按 3.5.3 收口登记口径，禁跑造数 / 清理脚本** |

**设计要点摘要（供快速表决）**：

1. **落位 `delivery-service`**（Q1-A）：沙箱是交付域子域（规格 Q8-A `sandboxes`），同库同端口、复用准入 / 幂等 / 留痕 / 错误码骨架；零新模块。
2. **运行时用 `docker` CLI 子进程**（Q2-A）：`ProcessBuilder` 仅在 `DefaultProcessRunner` 一处，argv 数组、无 shell、命令与镜像全部来自配置白名单；Docker 不可用 fail-closed。
3. **镜像 `python:3.12-alpine`**（Q3-A）：显式 tag、官方、登记 + 审批；作业脚本可读可维护；备选零新镜像的 shell/awk 方案能力弱。
4. **沙箱内使用 = 受控作业清单**（Q4-A）：请求只给 jobId + 用途 / 地域；不开放任意命令与代码上传（诚实边界，登记演进）。
5. **数据复制进平台工作区**（Q5-A）：只读输入 / 可写输出 / 作业只读三段挂载；不挂交付源目录、不暴露宿主机路径。
6. **平台统一配额 + 到期回收 + 并发上限**（Q6-A）：CPU / 内存 / 时长 / 并发均配置化，申请时不可自选；`@Scheduled` 60s 扫描 + 使用前实时判定 + 启动对账。
7. **一次作业 = 一次数据使用**（Q7-A）：`check` 放行才执行并计数 1 次，拒绝零计数零记录；作业流水 = 3.7.5 事件源。
8. **数据面零出口**（Q8-A）：`/data/**` 一律 403 + 留痕；结果物仅元数据（名称 / 大小 / SM3）；导出归 3.5.5。
9. **安全基线**（Q2/Q4）：`--network none`、`--cap-drop ALL`、`no-new-privileges`、`--read-only`、非 root、PID / 内存硬限、不挂 docker socket、不用 privileged / host 网络。
10. **留痕不含内部细节**（行为 7 规则 5）：DB / 审计 / 日志 / 响应无内容、路径、容器、镜像、命令、密钥；作业 stdout/stderr 不落库。
11. **契约与部署**（Q9-A）：新建 ADR-024；k8s 不注入 socket、运行时默认关闭；ADR-016 §6 不新增。
12. **G1 规格未定义项**：沙箱可接受的合约产品形态未定义 → 建议仅 DATASET/REPORT/MODEL（API 形态拒绝 `1009C0006`），规格补一行（变更流程），C-5.2 剧本无需改。

## 二、决策点与确认留痕（待编排师一次确认；章程 2.6.3）

> 决策点全文与备选业务影响见 `docs/designs/WBS-3.5.4-lofi.md` §3；下表为摘要。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 模块与技术落位 | **A 建议**：在既有 `delivery-service` 内新增 `sandbox` 包（同库 `ctds_delivery` + Flyway V2、端口 8087 不变、零新模块 / 零新 Maven 依赖）；B：新建 `sandbox-service`（8088 + 新库，2 人日不可控）；C：塞进网关 / 合约服务（职责冲突 / 越界） |
| **Q2** | 容器运行时接入 | **A 建议**：`docker` CLI 子进程（argv、无 shell、配置白名单、正则校验、逐命令超时）；B：Docker Engine TCP API（需开守护进程 TCP 暴露）；C：引入 `docker-java` SDK（新依赖须变更流程） |
| **Q3** | 沙箱运行镜像 | **A 建议**：`python:3.12-alpine`（显式 tag、官方、登记 `dependencies.md` 容器基础镜像节 + 审批）；B：复用 `nginx:1.29-alpine` + shell/awk（零新镜像但能力弱）；C：自建 `ctds/sandbox-runtime`（超预算） |
| **Q4** | 沙箱内使用形态 | **A 建议**：配置化**受控作业清单**（jobId → 固定 argv + 超时；请求不传命令 / 路径）；B：任意命令执行（滥用面不可控）；C：只起容器不提供使用（行为 5 无承载） |
| **Q5** | 数据注入与工作区 | **A 建议**：开通时把合约产品已登记文件**复制**到平台工作区（输入只读 / 输出可写 / 作业只读三段挂载）；B：直接挂交付源目录（可改源、泄露路径）；C：空沙箱（行为 5 不成立） |
| **Q6** | 配额与生命周期 | **A 建议**：平台统一默认配额（CPU / 内存 / 时长 / 并发，配置化、申请不可自选）+ 60s 到期回收 + 使用前实时判定 + 启动对账；B：用户自选配额（易滥用）；C：不自动回收（违背到期关闭） |
| **Q7** | 使用与策略 / 计量 | **A 建议**：每次作业 = 一次数据使用 → `check`（USE，带用途 / 地域），放行才执行并计数 1 次；拒绝零计数零记录；作业流水 = 3.7.5 事件源；B：开通时 `check` 一次（无法演示约定外用途被拒）；C：不参与策略判定（违反行为 5 规则 4） |
| **Q8** | 数据面出口 / 编号 / 错误码 | **A 建议**：零内容出口（结果物仅元数据）；`/data/**` 一律 403 + 留痕；`SB`+6 位 / `SJ`+6 位；1009C0010~0014 / 1009S0004~0005；`@Idempotent` + DB 唯一键；B：产物可下载（绕过审核）；C：输入可下载（违反行为 5 规则 1） |
| **Q9** | 契约与部署边界 | **A 建议**：新建 **ADR-024 沙箱环境契约**；k8s 不注入 docker socket、沙箱运行时默认关闭（fail-closed 503）；ADR-016 §6 不新增消费方；B：仅 hifi；C：并入 ADR-023（语义污染） |
| **G1** | **规格未定义项：沙箱可接受的合约产品形态（需变更流程）** | **A（PO 已批准，2026-10-22）**：沙箱仅接受 **DATASET / REPORT / MODEL**（有数据载体），API 形态拒绝 `1009C0006`；规格已转 **V1.1**（行为 4 规则 6 + 验收标准 6），C-5.2 剧本步骤与业务判定**一字未改**（仅维护说明第 6 条留痕）；B：不限形态（API 形态空跑）；C：等规格变更（阻塞） |
| **D1** | 体量 / 是否拆分 | **建议不拆分**：预估 **2200~2600 行**有效变更，**2 个编码会话**（WBS 2 人日）；拆则“环境无使用可验、使用无环境可跑”，且 3.5.5 无结果物输入。**实测超 400 行按既有先例登记并请编排师按 D1 确认**。 |

> **确认留痕（✅ 2026-10-22 21:25 编排师会话回复“全部按建议”）**：**Q1~Q9 均采建议口径 A + G1-A + D1 不拆分**——Q1 同库扩展 `delivery-service` / Q2 Docker CLI 子进程 / Q3 `python:3.12-alpine`（登记 + 审批）/ Q4 受控作业清单 / Q5 复制数据进平台工作区 / Q6 平台统一配额 + 到期回收 / Q7 每次作业一次计数 / Q8 零内容出口 + 1009 段顺延 / Q9 新建 ADR-024 + k8s 运行时默认关闭；**G1-A** = 仅 DATASET/REPORT/MODEL，**PO 已于 2026-10-22 批准，规格 V1.1 已补行为 4 规则 6 / 验收标准 6，C-5.2 剧本仅维护说明留痕**；**D1 = 不拆分**（2200~2600 行，2 个编码会话）。**lofi/hifi 同批转 V1.0（编码契约即时生效）**；确认批提交并推送 origin；**下一关口 = 编码段（新会话冷启动，测试先行，第一动作 = hifi §10 八项前置检查逐项实测留痕）→ 4 视角评审 → 修复循环 ≤3 轮 → 真链走查 → 编排师验收**（“验收通过” ≠ 合并授权；推送 main / 合并须显式指示）。

## 三、规格行为 → 本卡承载 → 剧本步骤 映射表（立卡骨架，编码会话复验回填）

| 规格承载 | 本卡落点（编码交付实测） | 剧本步骤（C-5.2） | 测试锚点（mvn 实测） |
| --- | --- | --- | --- |
| 行为 1 规则 1（凭据 = 已生效合约） | 准入链：合约内部只读摘要（参与方 + EFFECTIVE；未生效 / 终止 / 完结 → `1008C0013` 原码；不存在 / 非参与方 → `1008C0012` 同码同文） | S1-1 / S3-7 | 准入组（状态门槛 3 例 + 防枚举 2 例） |
| 行为 1 规则 2（发起人 = 需求方） | 摘要返回 `applicantNo`；发起人 ≠ 需求方 → 403 + `1009C0003`（先于配额与取号）；提供方 / 运营方代申请同拒 | S3-10 | 准入组（提供方 / 非需求方 2 例） |
| 行为 1 规则 4（防枚举 + 拒绝留痕） | 详情 / 作业 / 结果非参与方与不存在 → 404 + `1009C0010` 同码同文；拒绝落 `sandbox_action_log` + 审计 | S3-8 | 访问组 3 例 |
| 行为 4 规则 1（凭合约申请） | 同行为 1；沙箱任务绑定合约与产品快照 | S1-1 / S3-7 | 准入组（见上） |
| 行为 4 规则 2（隔离环境） | 独立容器 + 安全基线（无网络 / 非 root / 只读根 / 能力全丢 / 仅本任务挂载）；跨沙箱不可见 | S1-3 / S2-3 | 运行时 argv 组 6 例 + 隔离组 4 例（真 Docker） |
| 行为 4 规则 3（配额与生命周期） | 统一配额（CPU / 内存 / 时长 / 并发）+ 到期回收 + 主动关闭 + 启动对账；到期 / 关闭后不可用 | S1-2 / S3-6 / S3-9 | 生命周期组 10 例 |
| 行为 4 规则 4（可查 + 治理） | 参与者读面 / 治理读面（读即留痕）/ 作业历史 / 结果元数据；不含内容 | S1-4 / S1-5 | 访问组 3 例 + 留痕组 4 例 |
| 行为 4 规则 5（不绕合约） | 沙箱只读合约产品已登记文件；不引入合约外数据源；数据文件 0 个 → fail-closed | S1-1 / S2-3 | 准入组（无数据文件 1 例） |
| 行为 5 规则 1（数据带不出） | 输入 / 输出均无内容下载入口；`/data/**` 一律 403 + 留痕 | S2-2 | 数据面组 4 例 |
| 行为 5 规则 2（环境隔离） | 容器无网络、只挂本任务目录、无 docker socket；跨沙箱 / 跨合约不可及 | S2-3 | 隔离组（真 Docker）+ 跨沙箱 1 例 |
| 行为 5 规则 3（使用留痕） | `sandbox_job` + `sandbox_action_log` + 审计 JSONL（谁 / 何时 / 哪个任务 / 哪个作业 / 结果） | S2-1 | 作业组 10 例 + 留痕组 4 例 |
| 行为 5 规则 4（策略仍生效） | 每次作业调 `check`（USE）；越界原码透传（`1008C0020` 等），零计数零记录 | S2-4 | 作业组（用途 / 地域越界 2 例） |
| 行为 5 规则 5（配额到顶即止） | 到期 / 作业超时 / 并发上限均在服务端判定并拒绝 | S3-6 / S3-9 | 生命周期组 + 作业组（超时 1 例） |
| 行为 7 规则 1/2（默认拒绝 + 治理例外） | 参与者读面；admin 治理读面 + `sandbox.governance.read` 留痕；其余 404 | S1-4 / S1-5 / S3-8 | 访问组 3 例 |
| 行为 7 规则 3（服务端强制） | 一切判定在服务端；界面 / 参数 / 直调接口不构成绕过 | S2-2 / S3-6 | 数据面组 + 状态组 |
| 行为 7 规则 4（链路外零接触） | 记录 / 读面 / 审计只含业务要素；内容仅在容器与工作区内 | S3-11 | 字段集断言 4 例 |
| 行为 7 规则 5（留痕不含原文） | 审计字段最小必要；无路径 / 容器 / 镜像 / 命令 / 密钥 | S3-11 | 审计字段集 1 例 |
| WBS 行 317/328（计量 / 存证） | `sandbox_job` = 3.7.5 事件源；契约计数不双计；上链归 3.8.3 | —— | 记录字段集 + 对账说明 |
| §6-3（诚实边界） | 平台侧隔离环境；不作跨机构物理“不出域”承诺；自由编码 / 生产 K8s 编排登记演进 | 剧本维护说明 3 | —— |
| G1（形态口径） | 仅 DATASET/REPORT/MODEL；API 形态 → `1009C0006` | S1-1（主凭据为数据集合约） | 准入组（形态 1 例） |

### 编码段实测锚点（2026-10-22，`mvn -pl services/delivery-service test` → **122 例 / 0 失败 / 5 跳过**；基线 56 → 122）

| 规格承载 | 实测用例（类#方法，均为 `SandboxIntegrationTest` 除注明） | 例数 |
| --- | --- | --- |
| 行为 1 规则 1（生效合约） | `nonEffectiveContractPassThrough`（1008C0013/409）+ `nonParticipantContractPassThrough`（1008C0012/404） | 2 |
| 行为 1 规则 2（发起人 = 需求方） | `providerCannotCreateSandbox`（1009C0003/403）+ `invalidParamsRejected`（1009C0005/400） | 2 |
| 行为 1 规则 4（防枚举 + 拒绝留痕） | `nonParticipantDetailIsNotFound` / `nonParticipantCloseIsNotFound` / `crossSandboxJobIsNotFound`（均 1009C0010 同码同文）+ `participantListShowsOwnSandboxesOnly`（非参与方空列表） | 4 |
| 行为 4 规则 1（凭合约申请） | `createSandboxCopiesInputsAndLaunchesContainer`（任务绑定合约 + 产品快照）、`idempotentReplayReturnsSameSandboxWithoutSecondLaunch` | 2 |
| 行为 4 规则 2（隔离环境） | `DockerCommandBuilderTest`（7 例 argv 基线）+ `SandboxDockerE2eTest`（4 例真 Docker，默认跳过、走查启用） | 11 |
| 行为 4 规则 3（配额与生命周期） | `closeStopsContainerAndMarksStopped` / `closeIsIdempotent` / `providerCannotClose` / `sweepExpiresOverdueTask` / `reconcileMarksMissingContainerStopped` / `requesterQuotaExceeded`（429）/ `runJobAfterCloseRejected` / `runJobAfterExpiryRejectsAndMarksExpired` | 8 |
| 行为 4 规则 4（可查 + 治理） | `resultsExposeMetadataOnlyWithSm3` / `governanceReadLeavesTrace` / `detailResponseHasNoInternalDetails` / `participantListShowsOwnSandboxesOnly` | 4 |
| 行为 4 规则 5（不绕合约） | `noRegisteredDataFilesFailsClosed`（1009S0004） | 1 |
| 行为 5 规则 1（数据带不出） | `dataInputDirectAccessDenied` / `dataOutputDirectAccessDenied` / `dataPathTraversalDenied` / `dataAccessByNonParticipantIsNotFound` | 4 |
| 行为 5 规则 2（环境隔离） | `SandboxDockerE2eTest#inputMountIsReadOnly` / `#containerHasNoNetwork`（走查启用）+ `DockerCommandBuilderTest`（mount 三段、无 socket / host 网络） | 4 |
| 行为 5 规则 3（使用留痕） | `runJobSucceedsAndCountsOnce` / `actionLogKeepsBusinessFieldsOnly` | 2 |
| 行为 5 规则 4（策略仍生效） | `policyViolationPassThroughWithoutRecord`（1008C0020 零计数零记录）/ `contractUnavailableLeavesNoJobRecord`（1009S0002 零记录）/ `jobIdempotentReplayDoesNotDoubleCount` | 3 |
| 行为 5 规则 5（配额到顶即止） | `jobTimeoutRecorded`（TIMEOUT）+ `requesterQuotaExceeded` | 2 |
| 行为 7 规则 1/2（默认拒绝 + 治理例外） | `nonParticipantDetailIsNotFound` + `governanceReadLeavesTrace`（读即留痕） | 2 |
| 行为 7 规则 3（服务端强制） | `dataPathTraversalDenied` + `runJobAfterExpiryRejectsAndMarksExpired` | 2 |
| 行为 7 规则 4/5（留痕不含原文） | `actionLogKeepsBusinessFieldsOnly`（无命令 / 镜像 / 容器 / 路径）+ `detailResponseHasNoInternalDetails` | 2 |
| WBS 行 317/328（计量 / 存证） | `runJobSucceedsAndCountsOnce`（check 计数 1）+ `jobIdempotentReplayDoesNotDoubleCount`（重放不重复计数）；`sandbox_job` = 3.7.5 事件源 | 2 |
| G1（形态口径） | `apiProductFormRejectedByG1Rule`（API → 1009C0006/400） | 1 |
| 配置 fail-fast（hifi §1.2） | `SandboxPropertiesTest`（10 例：配额 / 镜像 tag / 作业清单 / 运行时空根） | 10 |
| 工作区与数据注入（hifi §5） | `SandboxWorkspaceStoreTest`（6 例：fileId 命名 / manifest 无路径 / 上限 / 结果元数据 SM3 / 幂等清理） | 6 |
| 错误码出站映射（hifi §7.2） | `DeliveryExceptionHandlerTest`（码表一致性 + 沙箱 7 码 HTTP 映射） | 2 |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 立卡段（2026-10-22 21:2x） | 🟡 **立卡段完成**：任务卡 + lofi **V0.9** + hifi **V0.9** 落盘；素材盘点八项（含 **G1 规格未定义项** + **G0 剧本答案 = 无需修订**）；**待编排师一次确认 Q1~Q9 + G1 + D1**；确认后 lofi/hifi 转 **V1.0（编码契约即时生效）**、分支推送 origin → 编码段（测试先行，另会冷启动，第一动作 = hifi §10 八项前置检查逐项实测留痕）。**本段零代码、零依赖、零门禁配置改动，未动 `main`**。台账“进行中”行暂未改（沿 3.5.3 先例，编码段更新）。 |
| 确认段（2026-10-22 21:25） | ✅ **编排师一次确认 Q1~Q9 全采建议 A + G1-A + D1 不拆分**：lofi/hifi 同批转 **V1.0（编码契约即时生效）**；**G1-A** = 仅 DATASET/REPORT/MODEL，规格澄清补丁**待 PO 批准**（变更流程，本卡不擅自改规格）；**D1 不拆分**（2200~2600 行，2 个编码会话）。确认批提交并推送 origin；**下一关口 = 编码段（新会话冷启动，测试先行，第一动作 = hifi §10 八项前置检查逐项实测留痕）→ 4 视角评审 → 修复循环 ≤3 轮 → 真链走查 → 编排师验收**。本段零代码/零配置/零门禁改动。 |
| G1 落地段（2026-10-22 21:3x） | ✅ **PO 批准 G1**（会话原文：“批准 G1：沙箱只接受数据集 / 报告 / 模型形态的合约，API 形态拒绝”）→ 规格转 **V1.1**（行为 4 规则 6 + 验收标准 6 + §7 G1 行 + 版本头 + 关联剧本版本）；C-5.2 剧本转 **V1.1**（步骤与业务判定一字未改；维护说明第 6 条留痕 + 演示前提 ④ 补“数据集形态”）；任务卡 G1 状态 / 关闭条件② / 设计 G1 备注同步。**零代码 / 零配置 / 零门禁改动** |
| 编码段（2026-10-22 22:0x） | 🟢 **编码与测试完成（单会话）**：hifi §10 八项前置检查逐项实测（Docker 29.8.1/OSType=linux、镜像已拉取 digest `92abd2ba…fd656`、`--mount` 挂载冒烟通过、`ctds_delivery` + Flyway V1、gates V1.12 `955bdbba…2ee2`）；红相归档 `build-output/w354-red-1.log`（4 类找不到符号）→ 实现 `com.ctds.delivery.sandbox` 子域 + Flyway V2 + `deploy/sandbox/jobs/*.py` + ADR-024 + `dependencies.md` 镜像 1 行 + k8s 沙箱环境变量（默认关闭）；测试 **56 → 122 例**（0 失败 / 5 跳过 = 1 Windows 符号链接 + 4 真 Docker）；checkstyle **0 违规**；真 Docker 走查 4 例实跑全绿（安全基线 / 输入只读 / 作业产物 SM3 / 无网络）。**未推送、未建 PR**；下一关口 = 全仓门禁 + 4 视角评审 + API 级真链走查 + 编排师验收 |

## 五、体量登记（立卡时填写）

| 项 | 数值 / 说明 |
| --- | --- |
| 实测（立卡段） | 任务卡 **112 行**；`docs/designs/WBS-3.5.4-lofi.md` **156 行**；`docs/designs/WBS-3.5.4-hifi.md` **430 行** → 合计 **698 行**（行数口径 = 文件实际行数实测）；**无代码、无测试、零 Maven 依赖引入** |
| 实测（确认段） | 任务卡 **114 行**（+2 = §四 确认段行 + §五 确认段行）；lofi **156 行**（等量替换）；hifi **430 行**（等量替换）→ 合计 **700 行**；**无代码、无测试、零 Maven 依赖引入** |
| 实测（G1 落地段） | 任务卡 **116 行**（+2 = §四 G1 落地段行 + §五 本行）；lofi **156 行**；hifi **430 行**；规格 `docs/specs/C-5.1-5.2-交付与沙箱.md` **244 行**（+4 = 行为 4 规则 6 / 验收标准 6 / §7 G1 行 / 补定义留痕）；C-5.2 剧本 **98 行**（+1 = 维护说明第 6 条）；本段**零代码 / 零配置 / 零门禁改动** |
| 实测（编码段） | `git diff --numstat`（含新增文件）= **+4358 / −12 行**：主代码 **2456 行**（34 文件：领域 10 / 仓储 2 / 运行时与工作区 6 / 应用 7 / 接口与 DTO 5 / 审计 2 / 配置 1 / 异常 1，含既有类加性改动 5 处）、测试 **1046 行**（6 文件 / 66 新例）、Flyway V2 **83 行**、作业脚本 **74 行**、文档 **≈300 行**（ADR-024 89 + 本节与 §三/§四 回填 + 日志）。**超出立卡预估 2200~2600 约 68%**：① 测试占比 24%（hifi 预估 45~50 例，实测 66 例，含 15 例"绕过/越界/留痕"反向锚与 4 例真 Docker）；② 新子域跨 8 层（模型 / 仓储 / 运行时 / 工作区 / 应用 / 接口 / 审计 / 配置）文件数结构性偏高。**D1 已确认不拆分（2 个编码会话）**，本会话一次完成编码 + 测试 + 文档；后续评审修复批另计 |
| 预估（编码段） | 有效变更 **2200~2600 行**：领域 / 仓储 ≈400 + 运行时 / 工作区 ≈350 + 准入 / 配额 / 生命周期 ≈450 + 作业 / 策略 / 留痕 ≈350 + 接口 / DTO / 错误码 ≈200 + 测试 ≈700~900 + 迁移 / 部署 / 文档 ≈300；**2 个编码会话**；零新 Maven 依赖；新增 1 条容器基础镜像（Q3-A） |
| 超 400 行指引说明 | 本卡为沙箱环境管理的原子集（容器环境 + 配额 + 生命周期 + 使用承载 + 数据面出口）；拆分将导致“环境无使用可验、使用无环境可跑”，且 3.5.5 无结果物输入；请编排师在 **D1** 一并表态 |

---

> **边界复述（红线自查）**：本卡**不自行实现密码学**（SM3 一律 `common-crypto`）；**内容 / 路径 / 容器 / 镜像 / 命令 / 密钥零入日志、DB、审计、提示词**；**不改既有端点语义、既有错误码码值、门禁配置、ADR-001 技术基线条目**；**判定语义只消费不复制**（合约域 C 型码原码透传）；**不做规格外功能**（审核导出 / 界面 / 计量流水 / 上链 / 连接器 / 任意命令 / 代码上传 / 生产 K8s 编排均登记承接）；**规格缺口走变更流程（G1）**；发现冲突或疑似 S0 安全问题立即停止上报（红线 4）。
