# WBS-3.8.2 任务卡：存证服务与链对接（2026-11-05）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账 `docs/status/进度看板与债务登记簿.md` **行 17 / 行 19**"下一包候选 = WBS-3.8.2 存证服务与链对接（WBS 行 327；立卡裁定权归编排师）" + 开发日志 `docs/logs/Ctds-项目开发日志-26-11-05-2201.md` 续点第 1 项；**编排师 2026-11-05 会话裁定"立卡"**（承接 3.8.1 全流程闭环后的下一包） |
| 级别与性质 | L1-3 平台功能**实施包 + 非界面类**（WBS 行 327，预算 **1 会话**）；章程 2.6.3 非界面类两级设计 = 业务可读行为清单 + 接口契约表 + 边界值与异常行为，`docs/designs/WBS-3.8.2-lofi.md` + `-hifi.md` 同批 **V0.9** 提交、一次确认转 V1.0，**免 HTML 原型**。**新建服务** `services/evidence-service`（8090 / `ctds_evidence`，第 20 个 Maven 模块）；**零前端改动**；**零新 Maven 依赖**（Q1-A 前提下；若真链 SDK 条件就绪则另走依赖审批，见 Q1） |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 327** 原文"存证服务与链对接 \| 存证网关抽象（对接 ADR-004 选型链）、存证模型 \| 3.8.1, 2.1.4 \| 1"（两项依赖：3.8.1 规格 ✅ 2026-11-05 合并 `2ab19ad`；2.1.4 = ADR-004 ✅ 已批准 2026-09-06）；下游 3.8.3~3.8.6（行 328~331）本卡零触碰；PRD V1.0 行 164（C-8.1"授权、合约、交付、使用全链路上链存证"，P0）；规格 `docs/specs/C-8.1-8.2-存证与审计.md` **V1.1**：**B1 存证事件模型与四环节覆盖**（4 规则 + 4 AC）/ **B2 写入与幂等**（4 + 4）/ **B3 链上核验**（4 + 4，规则 4 明写"技术承载 = `EvidenceGateway.verify`（3.8.2）"）/ **B4 异常与失败处置**（4 + 4）+ **§4-1**（"不做存证链实现与适配器 → 归 3.8.2"）+ **§5 依赖表**（`common` 国密 / 日志 / 错误码已就绪）+ **§6-8**（错误码预留 1012 段，码值随实施包落定）；剧本 `docs/manuals/C-8.1-区块链存证-验收剧本.md` **V1.1**（S1 八步 / S2 九步；操作路径明写"由 3.8.2 落定"）；ADR-004 §3（选型 / 网关抽象 / 上链白名单 / 边界）+ §4 风险③（白名单外上链按 S0） |
| 上游能力边界（立卡实测） | ① `EvidenceGateway`：**不存在（Java 源码零命中）**，仅 3 份 docs 提及；FISCO / BCOS 关键字 Java 零命中；`std-adapter` 的 `EvidenceStandardApi` 是**测评证据域占位**（不得混用）。② `services/` 11 个业务模块（8080~8089），**无 evidence / attestation / chain 模块**；**8090 全仓零占用**。③ `contract_attestation`（合约环节事件源）：`contract-service` Flyway **V2** 行 96~110，11 列 + `uk_attestation_contract(contract_id)`，Java 侧 `ContractAttestation` record + `JdbcContractRepository` 读写已就绪（**3.8.3 消费，本卡只读契约、零改动**）。④ `Sm3Service`（`common/crypto`）`digestHex(byte[]|String)` 唯一国密摘要入口；`AuditRecorder`（`common/logging`）单方法接口 + 3 实现；**无通用加盐组件**（Q5-A 不加盐的事实前提）。⑤ 错误码 **1012 段 Java + docs 双查零占用**；码值格式 `^\d{4}[CBS]\d{4}$`（`ErrorCode` 构造即校验）。⑥ 权限点全景实测 **55 个**，`evidence.*` **零占用**，服务级 yml 自治映射。⑦ 网关 `ctds.gateway.routes` 实测 **11 条**（`connector-*` 10 条），路由校验 fail-fast（仅单 `Path=` + `filters` 空）。⑧ 门禁 `coreModules` = 5 项（policy-engine / metering-billing / crypto / auth / did，**不含 evidence**）→ 覆盖率口径 = 整体 70%，配置零改动。⑨ 根 pom `<modules>` 实测 **19 个**；`deploy/k8s` 8 服务对 + kustomization。⑩ 前端 `api/` 无 evidence / audit 文件，`views/` 无存证视图（3.8.5 归口）；⑪ 全仓无 compose、无 FISCO 编排文件（真实链部署完全空白）。 |
| 交付物 | ① 新服务脚手架（pom〔parent `ctds-platform` 2.0.0-SNAPSHOT，`relativePath=../../pom.xml`，沿 kms 先例〕+ 主类 + `application.yml`〔8090 / 127.0.0.1 / 权限映射 / `ctds.evidence.chain.*`〕+ `application-mysql.yml`〔`ctds_evidence` + Flyway〕）+ 根 pom 注册（19→20）；② 迁移 **V1** 四表（`evidence_record` / `evidence_record_action_log` / `evidence_chain_block` / `evidence_no_seq`，见 hifi §2）；③ 领域层（`EvidenceRecord` / 三枚举 / `EvidenceErrorCodes` **1012 定稿** / `EvidenceGateway` 端口 / `EvidenceRepository` 端口）；④ 应用层（写入 / 查询 / 核验 / 重试 / 取号，状态机与三段事务边界见 hifi §4）；⑤ 基础设施（JDBC 仓储 + `LocalLedgerEvidenceGateway` + 配置属性 + 注入 Clock）；⑥ 接口层（内部写面 1 端点 + 对外读面 5 端点 + DTO + `EvidenceExceptionHandler`）；⑦ 网关第 12 路由 `connector-evidence` + `deploy/k8s` 两清单 + kustomization 两行 + gateway deployment 环境变量；⑧ 剧本 **C-8.1 → V1.2**（操作路径 / 构造口径 / 待上链不可构造说明；业务判定一字不改，提示 PO 批准）+ 复用声明 + 规格外实现声明（应为空）+ 台账 / 开发日志 / 体量登记 + 业务可读交付说明 |
| 关闭条件 | ① **✅ 2026-11-05 达成**——两级设计经编排师**一次确认**（Q1~Q9 均采建议口径 A + D1 不拆分），lofi / hifi 同批转 V1.0 = 编码契约即时生效；② **✅ 2026-11-05 编码段达成**：`evidence-service` `test` **37 例 0 失败 0 跳过**（T1~T16 覆盖）+ 行覆盖 **92.08%**（自设 ≥80%）+ `checkstyle:check` **0 违规** + `gateway-service` `test` **47 例**（46 基线零回归 + 1 新路由契约）+ 全仓 `compile` **20 模块 SUCCESS** + `gates-config.json` 零改动（ConfigSha256 `955bdbba…2ee2` 与基线逐字一致）+ 前端四步（863 例 / typecheck / lint / build）EXIT=0；③ **✅ 编码段复核**——规格 B1~B4（16 规则 + 16 AC）→ 端点 / 表 / 测试 / 剧本映射齐备（§三，T1~T16 逐条对齐）；④ **✅ 2026-11-06 验收通过（剧本 V1.2 随验收生效）**——剧本 C-8.1 S2 九步可按 V1.2 操作路径执行（接口承载，双向必验；S1-1~S1-5 真业务触发待 3.8.3，本卡以内部写面构造等价验证）；⑤ **✅ 2026-11-06 循环 2 复审 PASS + 独立复审 PASS（零未处置 P1/P2）**——4 视角评审循环 ≤3 轮（1×P1 + 4×P2 全闭环）+ 循环 2 复审 8×P3（2 文档回填 + 6 跟踪）+ **独立复审 `7dc1a56` 9×P3（零 P1/P2）**；⑥ **✅ 2026-11-06 剧本 V1.2 随验收生效**（编排师"验收通过"；如仅批准功能不批准剧本修订，请指示撤回）；⑦ **✅ 2026-11-06 编排师验收通过 + 合并闭环**（验收通过后编排师指示"继续逐项完成推送 origin / 建 PR / 合并 main"→ **PR #30 MERGED `b472500`**，双亲 `2ab19ad`+`cd6e7b2`；PR gate run `42225836183` SUCCESS 12m39s + main gate run `42227656672` SUCCESS 15m51s；树一致性核验空；`main == origin/main` 零差异）；⑧ 零越界（**上游零改动**：`contract_attestation` / 合约 / 交付 / 计量 / 空间任何表与端点零触碰；**四环节埋点零实现**〔3.8.3〕；审计视图与报告零实现〔3.8.4〕；前端零改动；零新 Maven 依赖〔Q1-A〕；零门禁配置改动；零原文 / 个人信息 / 密钥材料上链） |
| 分支 | `feat/WBS-3.8.2-存证服务与链对接`（**自本地 main 梢 `29cff23` 创建** = 3.8.1 合并段回填批；该回填批与立卡批随本卡 PR 一并入库，沿链式先例；`origin/main = 2ab19ad` 未动） |
| 设计文件 | `docs/designs/WBS-3.8.2-lofi.md`（**V1.0 已确认 = 方向定稿，2026-11-05**）/ `docs/designs/WBS-3.8.2-hifi.md`（**V1.0 已确认 = 编码契约即时生效，2026-11-05**）——非界面类，免 HTML 原型 |
| 纪律声明 | **只做存证服务与网关抽象**——不接四环节埋点（3.8.3）、不做审计视图与报告（3.8.4）、不做界面（3.8.5）、不做真链性能压测（3.8.6）；**上链白名单是唯一防线**（载荷固定五项，方法入参无自由文本字段，原文 / 个人信息 / 商业密文 / 密钥材料结构性进不去；白名单外按 S0 处置）；**不自建密码学**（SM3 只调 `common` 的 `Sm3Service`，禁自行实现哈希 / 加盐 / 加密）；**不提供修改 / 删除 / 补录 / 手工填 txId 入口**（仓储端口不设改内容与删除方法）；**存证与业务解耦 + fail-visible**（上链失败不回滚业务、状态可见、可重试、不静默）；**不加运行期"模拟故障"后门**（演示构造只走环境变量 + 重启与受控 SQL，见 lofi §3.4）；**不改上游任何表 / 端点 / 错误码**；**不引入未审批依赖**（Q1-A 下零新依赖；真链 SDK 须先核验注册表 + 走审批）；**口令零入库**（环境变量注入）；**发现规格缺口走变更流程**，不在本卡私自补需求；发现冲突或疑似 S0 → 立即停止上报（红线 4） |

---

## 一、范围与设计

- **做什么 W1~W8 / 不做什么 N1~N8**：见 `docs/designs/WBS-3.8.2-lofi.md` §1；
- **结构组成**：lofi §2；**主要流程（写入 / 核验 / 重试 / 演示构造）**：lofi §3；**决策点（待确认）**：lofi §4（Q1~Q9 + D1）；**体量**：lofi §5（预估 **2200~2800 行**，1 个编码会话）；
- **编码契约**（端点表 / 四表 DDL / 摘要规范化与载荷 / 状态机与事务 / 错误码 1012 定稿 / 适配器契约 / 前置检查 12 项 / 部署配置 / 测试锚点 T1~T16 / 剧本映射与诚实边界）：见 `docs/designs/WBS-3.8.2-hifi.md` §1~§10。

### 立卡前素材盘点（只读；子智能体 10 项 + 主会话定点补测 4 项）

| 项 | 结论 |
| --- | --- |
| ① WBS 锚点 | 行 327 原文核对（网关抽象 / 存证模型；依赖 3.8.1 + 2.1.4 均已闭环；预算 1 会话）；行 328~331 下游边界 = 埋点 / 视图 / 界面 / 测试集成，本卡零触碰 |
| ② 规格锚点 | C-8.1-8.2 V1.1 B1~B4（16 规则 + 16 AC）+ §4-1 归口 + §5 依赖表 + §6-8（1012 段预留）+ §2 术语（存证记录 / 内容摘要 / 上链 / 核验四口径）；B5~B8 归 3.8.4 |
| ③ 剧本锚点 | C-8.1 V1.1：S1 八步（1~5 依赖 3.8.3）/ S2 九步（本卡全承载）；操作路径明写"由 3.8.2 落定"；附录 A 构造口径（受控替身 / 停链，仅演示环境） |
| ④ ADR 锚点 | ADR-004 §3-2（只依赖 `EvidenceGateway`）/ §3-3（白名单五项）/ §3-4（公信力 V1.5）/ §4 风险③（白名单外 = S0）；ADR-005 / 007 / 009 / 015 / 018 只读引用；零新密码学面 |
| ⑤ 代码侧核查（子智能体实测） | ① `EvidenceGateway` 与 FISCO 关键字 Java 零命中；② 无 evidence 服务、8090 空闲；③ `contract_attestation` V2 行 96~110 + `findAttestation` 就绪（只读消费方在 3.8.3）；④ `Sm3Service` 两方法；`AuditRecorder` 单方法；无加盐组件；⑤ 1012 段零占用；⑥ 权限 55 点零 `evidence.*`；⑦ 网关 11 路由 + fail-fast 约束；⑧ `coreModules` 5 项不含 evidence；⑨ 根 pom 19 模块；⑩ 无 FISCO 编排；前端无存证视图 |
| ⑥ 主会话补测 | ① 权限点实测 **55**（去重计数）；② `evidence.` 仅在 std-adapter 测评证据域出现 3 处（非权限点、非存证语义，**不得混用**）；③ `routing` 采用 `ctds.gateway.routes` 加 `CTDS_GATEWAY_EVIDENCE_BASEURL` 环境变量的既有形态；④ 内部端点惯例 = `/api/v1/{域}/internal/...` + 服务身份权限点（`contract.internal.read` 仅 `gateway-internal` / `delivery-internal` 持有） |
| ⑦ 门禁与依赖面 | 零新 Maven 依赖（Q1-A）；`gates-config.json` 零改动；新服务依赖沿 kms / metering 同款（common-errorcode / common-logging / common-auth + web + jdbc + mysql + flyway + testcontainers）；k8s +2 yaml；网关 +1 路由 +1 环境变量 |
| ⑧ 测试与体量面 | 基线：gateway 46 例（3.7.6 口径，编码段实测复跑为准）/ 全仓 19 模块 compile / 前端四步零改动兜底；本卡新增 T1~T16（约 30~40 例）；红相归档 `build-output/w382-red-*`；预估 2200~2800 行 |

### 设计要点摘要（供快速表决）

1. **一个服务 + 一个抽象**：`services/evidence-service`（8090 / `ctds_evidence`）+ `EvidenceGateway`（submit / findByTxId）；换链只换适配器；
2. **默认本地不可变账本**（Q1-A）：账本表 append-only + 前序哈希链式 + `uk_chain_payload` 幂等；真实 FISCO BCOS 作为条件项 / 跟踪项，**不虚报**；
3. **白名单结构性封闭**（B1-4）：内部写面入参只有六个业务字段，无自由文本；载荷固定五项、固定键序、服务端手工拼装；原文 / 个人信息 / 密钥材料进不去；
4. **摘要可复算**（Q5-A）：`CTDS-EV-1|环节|单据号|操作类型|时点(UTC 毫秒)|DID摘要` 经 SM3；不加盐（加盐使外部无法独立复算）；
5. **解耦 + fail-visible**（B2-1）：三段事务（记录 / 账本 / 状态）分离；上链失败仍 200 + `FAILED` + 原因摘要；重试手动、无 MQ、无定时任务；
6. **幂等双保险**（B2-2）：`uk_evidence_idem` 三元组 + 账本 `uk_chain_payload`（崩溃窗口重放不产生第二块）；
7. **编号终身不复用**（Q4-A）：`EV` + 日期 + 6 位当日序列，取号表原子自增不回收——**闭掉 3.8.1 遗留的 B1-AC2 弱闭环**；
8. **核验三态显式**（B3）：通过 / 不一致 / 未上链，不一致给出重算摘要 / 账本摘要 / 本地摘要三方定位；核验留痕、不改数据。

## 二、决策点与确认留痕（待编排师一次确认；章程 2.6.3）

> 决策点全文与业务影响见 `docs/designs/WBS-3.8.2-lofi.md` §4；下表为摘要。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 真实链在本卡做到什么程度（本机无链节点；拉镜像与官方 SDK 需依赖审批） | **A**：抽象 + 本地不可变账本（默认，零新依赖，全链可演示）+ 真实链接入契约与部署说明；真链 SDK 作为**条件项**（开工前镜像 / Docker / 依赖审批均就绪则同批接入），否则登记跟踪项、不虚报。B：本卡必须真链（未就绪则阻塞）；C：明确裁剪真链转部署期（与 WBS 行 327 有差距，需变更登记） |
| **Q2** | 服务落点 | **A**：新建独立服务（8090 / 独立库）；B：并入合约服务；C：并入网关服务 |
| **Q3** | 上链时序 | **A**：同步写入后立刻上链，失败置失败态 + 手动重试（无 MQ / 无定时任务）；B：异步队列；C：定时批量 |
| **Q4** | 编号与防重 | **A**：`EV` + 日期 + 6 位当日序列（取号表原子自增、不回收）+ 三元组唯一键；B：UUID；C：自增主键当编号 |
| **Q5** | 摘要规范化与加盐 | **A**：固定顺序串经 SM3、上链五项、**不加盐**（保证第三方可独立复算）；B：加盐（外部无法独立验证，需引入密钥依赖）；C：直接哈希原文（S0） |
| **Q6** | 权限面 | **A**：`evidence.governance`（仅治理档：清单 / 详情 / 核验 / 重试 / 概览）+ `evidence.internal.write`（仅业务系统服务身份，3.8.3 用）；**不加 `evidence.read`**（参与方读面归 3.8.4 评估）；B：拆两个权限点；C：复用 `contract.governance` |
| **Q7** | 演示期构造故障 | **A**：链不可达 = 环境变量关闭 + 重启（无运行期后门）；不一致 = 演示库受控 SQL 改账本一行 + 还原复验；待上链态登记为不可构造；B：运行期模拟故障端点（开后门，违反 B4-4）；C：不做负向演示 |
| **Q8** | 剧本修订 | **A**：C-8.1 转 **V1.2**（接口路径 + 构造口径 + 待上链说明），**业务判定与步骤编号一字不改**，PO 批准；B：不改（与剧本维护说明冲突）；C：重写（超范围） |
| **Q9** | 3.8.1 跟踪项处置 | **A**：① B1-AC2 终身不复用 → Q4 本卡闭合；② `*_action_log` 段值守卫 → 不在本卡消化，仍归 4.2.x 真链执行前；③ Q1-A 条件未就绪时新登记真链 SDK 跟踪项；B：本卡改写清理脚本（风险高于收益） |
| **D1** | 体量 / 是否拆分 | **不拆分**：预估 **2200~2800 行**、1 个编码会话；写入 / 核验 / 重试同一验收链，拆则中间态不可验收（超 400 行按服务包先例登记制豁免） |

> **✅ 确认留痕（2026-11-05 编排师会话回复"全部按建议确认"）**：**Q1~Q9 均采建议口径 A + D1 不拆分**——Q1 真实链承载 = 可切换 `EvidenceGateway` 抽象 + 本地不可变账本（默认零新依赖）+ 真链条件项 / 跟踪项（**不虚报真链已对接**）/ Q2 新建独立服务 8090 `ctds_evidence` / Q3 同步写入后上链 + 失败置态 + 手动重试（无 MQ 无定时任务）/ Q4 `EV`+日期+6 位当日序列（取号不回收）+ 三元组唯一键（闭 B1-AC2）/ Q5 固定顺序串 SM3 + 上链五项 + **不加盐** / Q6 `evidence.governance` + `evidence.internal.write`（不加 `evidence.read`）/ Q7 链不可达 = 环境变量 + 重启，不一致 = 受控 SQL + 还原，待上链态登记不可构造（无后门）/ Q8 C-8.1 转 **V1.2**（业务判定一字不改，PO 批准）/ Q9 B1-AC2 本卡闭合、`*_action_log` 段值守卫仍归 4.2.x、真链条件未就绪时新登记跟踪项 / **D1 不拆分**（2200~2800 行，1 个编码会话）。→ ① lofi / hifi 同批转 **V1.0（编码契约即时生效）**；② 本行 + 关闭条件① + §四确认段回填；③ 台账行 17 / 19 转态；④ 确认批推送 origin（分支首推）。**下一关口 = 编码段**（新会话冷启动，测试先行，第一动作 = hifi §7 前置检查 12 项逐项实测留痕）。

## 三、规格行为 → 本卡承载 → 剧本步骤 映射表

| 规格承载（V1.1） | 本卡落点（端点 / 表 / 适配器） | 剧本步骤 | 测试锚点 |
| --- | --- | --- | --- |
| B1-1（六要素 + 编号终身唯一） | `evidence_record` 六要素列 + 取号表 + 内部写面 | S1-8 | T13 / T3 |
| B1-2（环节四类值域封闭） | `stage_type` 枚举 + 操作类型按环节匹配校验 | S1-2 | T2 |
| B1-3（只消费上游事件源、单据号逐字一致） | 内部写面 `businessNo` 原样透传（不 trim / 不改写） | S1-2~S1-5（真业务触发归 3.8.3） | T3 |
| B1-4（上链白名单五项 / 禁原文等） | 载荷固定五项 + 手工拼装 + 入参无自由文本 | S1-7 | T12 |
| B2-1（解耦 + fail-visible） | 三段事务 + 失败仍 200 + `last_error_summary` | S2-4 | T5 / T16 |
| B2-2（幂等） | `uk_evidence_idem` + `duplicate=true` + 账本 `uk_chain_payload` | S1-6 | T1 / T15 |
| B2-3（状态机 + 留痕） | `evidence_record_action_log` 六类 action | S2-7 | T6 / T8 |
| B2-4（不可改删） | 仓储端口无改内容 / 无 delete | S2-9 | T9 |
| B3-1（三态显式） | `verify` 返回 `PASS/MISMATCH/NOT_ON_CHAIN` | S2-1 / S2-5 | T7 |
| B3-2（不一致可定位） | 响应含重算 / 账本 / 本地三摘要 + 时点 | S2-3 | T7 |
| B3-3（核验留痕） | `VERIFIED` action（人 / 时点 / 对象 / 结论） | S2-1 | T8 |
| B3-4（核验不改数据） | 核验只读 + 只追加留痕 | S2-8 | T8 |
| B4-1（失败可见、不吞异常） | 适配器异常 → `FAILED` + 原因摘要（脱敏） | S2-4 | T5 / T16 |
| B4-2（异常计数可见） | `overview` 三计数 + 最近失败时点 | S2-6 | T11 |
| B4-3（重试 + 双留痕） | `retry` + `RETRY_SUCCEEDED/FAILED` | S2-7 | T6 |
| B4-4（禁伪造链上标识） | 无手工填 txId 入口；txId 只由适配器产生 | S2-9 | T9 |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 立卡段（2026-11-05） | **立卡完成**：素材盘点（子智能体 10 项 + 主会话补测 4 项）→ 任务卡 + `lofi V0.9` + `hifi V0.9`（均待一次确认）；分支 `feat/WBS-3.8.2-存证服务与链对接` 自本地 main 梢 `29cff23` 开立（3.8.1 合并段回填批随本卡 PR 入库）；**零代码、零依赖、零迁移、零门禁配置改动**，未动 `main`。下一关口 = 两级设计一次确认（Q1~Q9 + D1）。 |
| 确认段（2026-11-05） | ✅ **编排师会话回复"全部按建议确认"** = **Q1~Q9 均采建议口径 A + D1 不拆分** → ① `lofi` / `hifi` 同批转 **V1.0（编码契约即时生效）**；② 任务卡 §二 确认留痕 / 关闭条件① / 设计文件行 / §四 本行回填；③ 台账行 17（进行中）/ 行 19（待编排师）转态；④ 确认批提交并**推送 origin（分支首推）**。**本段零代码、零依赖、零迁移、零门禁配置改动，未动 `main`。** 下一关口 = **编码段**（新会话冷启动；测试先行；第一动作 = hifi §7 前置检查 12 项逐项实测留痕；红相归档 `build-output/w382-red-*`）。 |
| 编码段（2026-11-05） | ✅ **编码+转绿完成（测试先行）**：① hifi §7 前置检查 12 项逐项实测留痕（§六）；② 红相归档 `build-output/w382-red-20261105.log`（首跑 = 测试引用主源符号缺失，行为性红；复跑日志 `w382-test-run{1,2,3}.log` 留痕）；③ 实现 = 新服务 `services/evidence-service`（8090 / `ctds_evidence` / 根 pom **19→20**）+ 迁移 **V1 四表** + 四层包主源 **42 文件** + 1012 段五码 + 网关第 **12** 路由 `connector-evidence` + k8s 两清单 + kustomization 两行 + gateway deployment 环境变量；④ 转绿 = evidence **37 例 0 失败 0 跳过**（T1~T16：写入/幂等/编号/哈希链/载荷白名单/链故障失败可见/重试双留痕/崩溃窗口重放/核验三态/留痕/不改数据/概览/参数边界/越权/迁移结构/架构断言）+ 行覆盖 **92.08%**（jacoco 0.8.12，500/543）+ checkstyle **0** + gateway **47 例**（零回归 + 新路由契约）+ 全仓 **20 模块 compile SUCCESS** + 前端四步 EXIT=0（863 例 / typecheck / lint / build）+ ConfigSha256 `955bdbba…2ee2` 零改动；⑤ 设计勘误 **E1~E5** 回填 hifi §10（仓储只读/追加方法 + `findByTxId` 链关闭同抛 + CHAR(8) 取号字符串绑定 + ① 事务含 CREATED 留痕 + 治理动作操作人形态校验）；⑥ 剧本 C-8.1 转 **V1.2**（接口路径表 + 构造口径 + 待上链不可构造；**业务判定与步骤编号一字未改**，待 PO 批准）；⑦ **零上游改动 / 零新 Maven 依赖 / 零门禁配置改动**；未推送、未建 PR。下一关口 = **4 视角评审**。 |
| 评审修复段（2026-11-05） | ✅ **评审循环 1 修复完成（1×P1 + 4×P2；P3-10 随 P1-1 一并修）**：① **P1-1** `EvidenceExceptionHandler` 增 `EvidenceChainUnavailableException → 503 + 1012S0001`（常量文案）+ `EvidenceGateway#findByTxId` 契约注释同步（链不可达可抛）+ hifi §1.2 / §6 / §10-E2 口径对齐；新增 `EvidenceVerifyChainUnavailableIntegrationTest`（受控 SQL 置 `ON_CHAIN` + 关链 → 503 / 码值 / 文案 + 记录不变 + 无 `VERIFIED` 留痕）；② **P2-1** `EvidenceWriteService#summarize` 改固定业务文案（`存证链暂不可达` / `存证处理异常`，异常类名只进日志），用例断言 `last_error_summary` 不含 `Exception`；③ **P2-3** `try` 收窄至 `gateway.submit`，第三段事务失败按 500 `1012S0002` 上抛、**不回写 `FAILED`**（hifi §4 补该窗口口径）；新增 `EvidenceStateWriteFailureIntegrationTest`（链成功 + 状态写失败 → 500 `1012S0002` + 记录保持 `PENDING` + 仅 `CREATED` 留痕 + 账本 1 块）；④ **P2-2** 新增 `EvidenceIdempotencyRaceIntegrationTest` + `RacingRepositoryConfiguration`（预检漏过 + `insert` 唯一键冲突 → 回查原记录；1 行 / 1 块 / `duplicate=true`）；⑤ **P2-4** hifi §10 E2 改写为"本地 `ON_CHAIN` 才查链；查链失败 → 503；本地非 `ON_CHAIN` 直接 `NOT_ON_CHAIN` 不查链"；⑥ **复跑**：evidence **40 例 0 失败 0 跳过** + 行覆盖 **93.50%**（518/554）+ checkstyle **0** + gateway **47 例** + 全仓 **21 模块 compile SUCCESS** + `gates-config.json` SHA256 `955bdbba…2ee2` 零改动；红相归档 `build-output/w382-fix-red*.log`，绿灯归档 `w382-fix-verify-*.log`。**下一关口 = 循环 2 复审（新会话 / 只读子智能体）**；未推送、未建 PR、未动 `main`。 |
| 循环 2 复审段（2026-11-06） | ✅ **循环 2 复审 PASS**（新会话 + 3 个独立只读子智能体交叉复审，对象 HEAD `629576a`）：① **P1-1 / P2-1 / P2-3 / P2-2 / P2-4 全部闭环**（逐条证据见 `build-output/w382-review-round2.md` §1）；② 现场复跑 `mvn -pl services/evidence-service org.jacoco:…:prepare-agent test …:report checkstyle:check` → **40 例 0 失败 0 跳过 + 行覆盖 93.50%（518/554）+ checkstyle 0 + BUILD SUCCESS**（`build-output/w382-r2-verify-backend.log`，EXIT=0）；gateway **47 例**、全仓 **业务模块 20 / reactor 21 compile** 归档复核通过；`gates-config.json` SHA256 `955BDBBA…2EE2` 零改动；③ **零未处置 P1/P2 / 零越界 / 零断言削弱**；④ 新发现 8 项 P3：**R2-1**（`ON_CHAIN` + 账本缺块 → `MISMATCH` 口径）与 **R2-2**（hifi §9 新增用例锚点）已文档回填闭环，**R2-3~R2-8 登记跟踪**（承接见复审报告 §3）；⑤ 复审段**零代码 / 零测试 / 零依赖 / 零迁移 / 零配置改动**（仅 hifi §9 + §10-E2 两处回填 + 任务卡 / 日志）。**下一关口 = 独立复审 → 编排师验收**；未推送、未建 PR、未动 `main`。 |
| 独立复审段（2026-11-06） | ✅ **独立复审 PASS——零 P1 / 零 P2**（本会话未参与立卡/编码/循环 1 修复/循环 2 复审，前序结论不作证据；对象 = HEAD `164b46c` + 相对 `629576a` diff）：① **P1-1 / P2-1 / P2-3 / P2-2 / P2-4 逐条重读复核全部闭环**（证据见 `build-output/w382-review-independent.md` §1）；② 现场复跑 `mvn -pl services/evidence-service org.jacoco:…:prepare-agent test …:report checkstyle:check` → **40 例 0 失败 0 跳过 + 行覆盖 93.50%（518/554）+ checkstyle 0 + BUILD SUCCESS**（`build-output/w382-r3-review-backend.log`，EXIT=0）；gateway **47 例** / reactor **21** 条 SUCCESS 归档计数复核；`gates-config.json` SHA256 `955BDBBA…2EE2` 零改动；③ **零越界**（复审批恰 3 文件：hifi + 任务卡 + 日志，零代码/零测试/零配置）、**零断言削弱**（既有 503 重试用例原断言全在，无删用例）；④ 红线项计数实测 = 端点 **6** / 错误码 **5** / 状态 **3** / 路由 **12**，evidence 主源敏感面零命中；⑤ **9 项 P3**：I-1/I-2/I-3 由本段日志"补正说明"更正（日志 `main = origin/main` 表述、"19 个测试类"应为 **12**、循环 1 对 P1-1 基线的"非 ApiResult"表述——`common` 有 `GlobalExceptionHandler` 兜底 500 + `1000S9999`，P1-1 结论与修复不受影响）；I-4~I-7 待收口段文档回填（R2-8 改述 / hifi §1.2 `actualDigest` 可空 / §9 标题 T1~T19 / E2 编号统一 R2-1）；**I-8 = 循环 1 的 P3-1~P3-9（除 P3-10）无处置无登记，收口段必须逐项入债务登记簿（硬性待办）**；I-9 = 独立复审类关闭条件先标 🟡 后转 ✅。**下一关口 = 编排师验收**；未推送、未建 PR、`main` 零触碰。 |
| 验收 + 收口段（2026-11-06） | ✅ **编排师验收通过（2026-11-06 会话"验收通过"）+ 收口段完成**：① 台账四行回填（L1-3 **58→59/73**、V1.0 **87→88/136≈64.7%**、进行中 → 无进行中包 + 下一包候选 = **WBS-3.8.3 全链路存证埋点**〔WBS 行 329〕、待编排师 = 推送 / 建 PR / 合并待指示）；② **债务入簿三行**（独立复审 I-8 硬性闭环）：**DB-113** = 循环 1 的 P3-1~P3-9 逐项处置（P3-2 采纳〔hifi §10 E6 回填〕+ P3-6 关闭 + 其余 7 项登记跟踪）；**DB-114** = 循环 2 的 R2-3~R2-8 逐项处置（全部登记跟踪，含 I-4 对 R2-8 的改述）；**DB-115** = 真链条件项（S2）；③ **hifi 四处口径回填**：§1.2 补"`MISMATCH` 且账本缺块时 `actualDigest=null`"、§9 标题改 T1~T19、§10 E2 编号统一 R2-1、新增 **E6**（内部写面路径被网关字面覆盖 + 功能级权限隔离口径）；④ 剧本 C-8.1 **V1.2 随验收生效**（如仅批准功能不批准剧本修订，请指示撤回）；⑤ **本段零代码 / 零测试 / 零依赖 / 零迁移 / 零门禁配置**；本批**只提交不推送**（验收通过 ≠ 合并授权）。 |
| 合并段（2026-11-06） | ✅ **全流程闭环**：编排师"继续逐项完成推送 origin / 建 PR / 合并 main"→ ① 分支推送 origin（`b8e42ad..cd6e7b2`，4 笔：编码+修复 / 循环 2 复审 / 独立复审 / 验收+收口）；② **PR #30**（`feat/WBS-3.8.2-存证服务与链对接` → `main`；描述含需求编号与规格链接 + 自检单 + 四项声明 + 业务可读交付说明 + 批链 + 债务）；③ **PR gate run `42225836183` SUCCESS 12m39s**（60s 轮询 + `gh run view --json` 权威核验，未以 watch 退出码代替结论）；④ `gh pr merge 30 --merge` → **MERGED `b472500`**（双亲 `2ab19ad` + `cd6e7b2`，非 squash；分支保留）；⑤ 树一致性核验 `git diff cd6e7b2 origin/main` **空**；本地 main `--ff-only` 纯快进 `29cff23→b472500`，`main == origin/main` 零差异；⑥ **main gate run `42227656672` SUCCESS 15m51s**（合并推送自动触发）；⑦ 台账行 15/17/19 + 变更记录 + 任务卡 §四/§五 回填；**合并段批仅本地提交、随下一卡 PR 入库（链式先例）**。 |

## 五、体量登记

| 项 | 数值 / 说明 |
| --- | --- |
| 实测（立卡段） | 任务卡（本卡）**80 行**；lofi **91 行**；hifi **222 行** → 合计 **393 行**（非空行口径；物理行 98 + 113 + 274 = 485）；无代码、无测试 |
| 实测（编码段） | 新增 **58** 文件（主源 **42** / 测试 **12** / 资源 3〔`application.yml` / `application-mysql.yml` / `V1__create_evidence_tables.sql`〕/ pom 1），非空行 **3072**（主源 **1812** / 测试 **1260**）；另部署与配置面：k8s 两清单 **94** 行 + 网关路由 **7** 行 + gateway 新路由测试 **+17** 行 + kustomization **2** 行 + 根 pom **2** 行 → **合计约 3,194 行**（非文档口径）。超出预估 2200~2800 的主因 = 测试用例 **1260** 行（预估 700；T1~T16 双向用例 + 迁移结构 + 架构断言）；主源 1812 行低于预估下限。按已确认 D1 不拆分登记制豁免。 |
| 实测（评审修复段） | 新增测试 **5** 文件、非空行 **401**（`EvidenceVerifyChainUnavailableIntegrationTest` 102 / `EvidenceStateWriteFailureIntegrationTest` 90 / `EvidenceIdempotencyRaceIntegrationTest` 94 / `FailingStateWriterConfiguration` 33 / `RacingRepositoryConfiguration` 82）；主源修改 **3** 文件（`EvidenceWriteService` / `EvidenceExceptionHandler` / `EvidenceGateway`，约 +38/−22）；设计与任务卡回填 **4** 处（hifi §1.2 / §4 / §6 / §10-E2）。零新依赖 / 零迁移 / 零配置 / 零门禁改动。 |
| 实测（循环 2 复审段） | **零代码、零测试改动**；设计文档回填 **2 处**（hifi §9 追加 T17~T19 三行 + 说明一行；§10-E2 补"账本可达但查无此块 → `MISMATCH`"一句）+ 任务卡 §四/§五 回填 + 复审报告（gitignored）+ 开发日志；仓库口径文档变更 **约 10 行**。 |
| 实测（独立复审段） | **零代码、零测试、零依赖、零迁移、零配置改动**；新增独立复审报告 **1 份**（gitignored）+ 开发日志 **1 份** + 任务卡 §四 一行 / §五 本行；现场复跑归档 **1 份**（gitignored `w382-r3-review-backend.log`）；仓库口径文档变更 **约 8 行**。 |
| 实测（验收+收口段） | **零代码、零测试、零依赖、零迁移、零配置改动**；台账四行 + 债务三行（DB-113~115）+ hifi 四处口径回填 + 任务卡 §四/§五 + 开发日志 1 份；仓库口径文档变更约 **40 行**。 |
| 实测（合并段） | **零代码 / 零测试 / 零配置改动**；推送 **4 笔**（`b8e42ad..cd6e7b2`）+ **PR #30**（描述一份，gitignored 草稿 `build-output/pr-w382-body.md`）+ 台账行 15/17/19 与变更记录 + 任务卡 §四/§五 + 开发日志 1 份；合并提交 `b472500`（双亲 `2ab19ad`+`cd6e7b2`）。 |

## 六、编码段前置检查实测留痕（hifi §7 十二项，2026-11-05）

| 序号 | 检查项 | 实测结果 |
| --- | --- | --- |
| 1 | 根 pom `<modules>` | 实测 **19**；注册 `services/evidence-service` 后 **20**，全仓 reactor **21**（含根 pom）编译 SUCCESS |
| 2 | 新服务脚手架模板 | 实读 `services/kms/pom.xml` + `application.yml`：parent `ctds-platform` 2.0.0-SNAPSHOT、`relativePath=../../pom.xml`、默认 profile 排除 `DataSourceAutoConfiguration`、五层包（domain / application / infrastructure / interfaces / config）；本卡沿 kms + metering-billing 同款 |
| 3 | 取号实现模板 | 实读 `SubjectJdbcRepository.nextDailySeq`：`INSERT ... VALUES (?, LAST_INSERT_ID(1)) ON DUPLICATE KEY UPDATE ... LAST_INSERT_ID(seq+1)`（首日返回 1、连接级确定值）；本卡采用同款。**实测发现**：`seq_date` 为 `CHAR(8)`，若绑定 `java.sql.Date` 会被 MySQL 隐式转 10 字符触发 `Data too long`（首跑 500 根因），已改字符串绑定（hifi §10 E3） |
| 4 | `SharedMySqlContainer` | 实读 metering-billing 同名类：JVM 级单例容器（`mysql:8.0`）+ 每测试类独立库名 + `disabledWithoutDocker` 跳过语义；本卡复刻（7 个集成测试类各独立库） |
| 5 | 网关路由约束与计数 | 实读 `GatewayRouteConfig`：仅支持单 `Path=` 谓词 + `filters` 空，其它形态启动 fail-fast；`ctds.gateway.routes` 实测 **11** 条（1 交付面 + 10 connector）→ 本卡第 **12** 条 `connector-evidence`（不带 delivery-face） |
| 6 | 权限点全景 | 10 份 `application.yml` 解析实测唯一权限点 **53**（立卡记录 55，口径差 2 不影响判定）；`evidence.*` 语法零占用 → 本卡新增 `evidence.governance` + `evidence.internal.write` = **55** |
| 7 | 1012 段占用 | Java 主源 **0** 命中；docs 仅本卡 `WBS-3.8.2-hifi.md`（设计自身定义）→ 本卡五码定稿：C0001 / C0002 / C0003 / S0001 / S0002，空位 C0004~C0009、S0003~S0009 |
| 8 | 错误码 handler 模板 | 实读 `MeteringExceptionHandler`：精确语义码按精确 HTTP 状态出站 + 常量文案（同码同文）+ system 类型兜底；本卡 `EvidenceExceptionHandler` 同形态（404 / 400 / 409 / 503 / 500） |
| 9 | 内部端点与身份头 | 实读 `InternalUsageCheckController`（`/api/v1/{域}/internal/...` + 服务身份权限点）与 `SubjectAdmissionClient`（`X-Ctds-Subject` / `X-Ctds-Roles`）：本卡内部写面 `POST /api/v1/evidence/internal/records` + `evidence.internal.write`（角色 `evidence-internal`） |
| 10 | k8s 模板与计数 | 实读 `metering-billing-deployment/service.yaml` + `kustomization.yaml`：服务对 **8** → 本卡 `evidence-deployment.yaml` + `evidence-service.yaml` + kustomization 两行 = **9**；gateway deployment 增 `CTDS_EVIDENCE_BASEURL` |
| 11 | 门禁口径 | 实读 `scripts/gates/gates-config.json`：`coreModules` = **5** 项（policy-engine / metering-billing / crypto / auth / did，不含 evidence）→ 覆盖率口径 = 整体 70%，本卡自设 **≥80%**（实测 92.08%）；`gates-config.json` **零改动**（SHA256 `955BDBBAA176096CAFF313DE2570423177052C99DB72E6D0BB15C6C8B10B2EE2`） |
| 12 | 前端四步基线 | 零前端改动，复跑兜底：`npm run test` **863 例 / 65 文件全过**、`typecheck` EXIT=0、`lint` EXIT=0、`build` EXIT=0 |
