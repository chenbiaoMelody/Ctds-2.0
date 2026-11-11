# WBS-3.8.6 高保证真设计（hifi）：存证审计测试与集成

- 版本：**V1.0 = 编码契约**（**2026-11-10 编排师一次确认 = Q1~Q8 全采 A + D1 不拆分，本文件即时生效**；V0.9 为待确认稿）
- 日期：2026-11-10｜对应 lofi：`docs/designs/WBS-3.8.6-lofi.md` V1.0
- 性质：**测试与集成类**。**零新端点 / 零新表 / 零迁移 / 零新错误码 / 零新权限点 / 零新第三方依赖 / 零门禁配置改动**（Q4/Q5 采 A）

---

## 1. 测试面：新增与补强用例清单（T1~T10，全部进 CI）

落点目录：`services/evidence-service/src/test/java/com/ctds/evidence/`（T9 落 `services/gateway-service/src/test/java/com/ctds/gateway/`）。

| 编号 | 建议落点（新/改） | 断言（可核） | 债务来源 |
| --- | --- | --- | --- |
| **T1** | 新 `EvidenceStageAggregationIntegrationTest` | 写四类 stageType 各 1 条后，`GET /api/v1/evidence/overview` 的**四环节计数与 `evidence_record` 按 `stage_type` 分组计数逐项相等**（非"存在即可"） | 本包核心（lofi 面 1） |
| **T2** | 同上 | 同一 `(stageType, bizNo)` 重复写 → 幂等返回既有记录，**计数不 +1**（唯一键生效） | DB-110 收敛 |
| **T3** | 同上 | 库内零记录时四环节键**存在且为 0**（不出现 `null`、不省略键） | 本包核心 |
| **T4** | 补强 `EvidenceRetryIntegrationTest` | 链不可达时 retry：状态**不前进**（仍为待上链）、失败原因可读、**不产生第二条记录** | DB-114 ② |
| **T5** | 同上 | `chain-enabled=false` 写入 → 记录落库且状态 = 待上链（**开关被消费**，不是报错也不是假成功） | DB-114 ③ |
| **T6** | 新 `EvidenceChainIntegrityIntegrationTest` | `chain_tx_id IS NULL` 的记录在 overview/anomalies 中按"待上链"呈现，**不计入异常数** | DB-114 ④ |
| **T7** | 同上 | 篡改本地账本（删块）→ `GET /records/{no}/verify` 返回 **MISMATCH**（若既有 `EvidenceVerifyIntegrationTest` 已覆盖则改为补"篡改后 overview 异常计数 +1"） | DB-114 ⑥ |
| **T8** | 新 `EvidenceAuditBenchmarkIntegrationTest` | 造 1 万条存证流水 → 审计 `records` 列表与 `anomalies` 查询 **P95 ≤ 500ms**（5 场景 × 20 轮 + 5 轮预热，P50/P95/max 落 `target/benchmark/evidence-audit.json`） | 性能敏感路径（AGENTS 行 76） |
| **T9** | 补 1 例（gateway 既有路由契约范式） | `GET /api/v1/evidence/overview` 经网关 → 转发成功、响应体透传、**不进交付判定链**（`filters: []`）；evidence 桩不可达 → 网关 502/503 而非 200 假成功 | 立卡实测缺口 ② |
| **T10** | 扩展 `EvidenceArchitectureTest` | 真链可替换性**结构断言**：链网关为单接口 + 单一实现（本地账本），无业务层直接依赖实现类 | DB-115（结构位） |

**测试面纪律**：T1~T10 全部**先写红相**（失败日志归档 `build-output/w386-red-*.log`）再实现；严禁改弱既有 70 例断言。

---

## 2. 探针面：六链真机判定表（不进 CI，出证据）

脚本目录：`build-output/w386-probe/`（gitignored）。起线骨架复用 `build-output/w384-demo/w384-demo-start.ps1`（space 8083 / contract 8085 / delivery 8087 / evidence 8090 + 身份头直连），不新建 `scripts/` 下脚本（沿 3.7.8 先例，避 DB-40 同型风险）。

| 链 | 业务动作（真实接口） | 判定（每链 3 条全 PASS 才算过） |
| --- | --- | --- |
| **A 授权** | 空间成员准入（space） | ① `AUTHORIZATION` 计数**恰 +1**；② 新增记录业务单号 = `SPACE{spaceId}:ADMISSION{subjectId}` **逐字一致**；③ 环节/操作类型 = `AUTHORIZATION` |
| **B 合约** | 合约签署生效（contract） | ① `CONTRACT` 恰 +1；② 单号 = `CO` + 6 位**与合约留痕逐字一致**；③ 公共数据授权类合约**授权环节双写 +1**（`DATA_AUTH_CONTRACT_EFFECTIVE`，Q5 双写口径） |
| **C 交付** | FILE 形态交付创建（delivery） | ① `DELIVERY` 恰 +1；② 单号 = `DL` + 6 位逐字一致 |
| **D 使用** | 合约使用放行（contract `check`） | ① `USAGE` 恰 +1；② 单号 = `USG` + 12 位逐字一致；③ **拒绝调用不产生存证事件**（反向：故意越权调用后 USAGE 计数不变） |
| **E 异常与恢复** | `-ChainEnabled false` 重启 evidence 后写入 | ① 产生 1 条 `FAILED`；② 恢复后 retry → 状态转已上链且**不新增记录**；③ overview 异常数回落 |
| **F 审计读面** | C-8.2 S1 三步 | ① 四环节矩阵与环节计数一致；② 五类单号唯一性检索无重复；③ 主体 DID 摘要 + 时间窗过滤结果与 C-8.1 产出逐条对应 |

- 判定表落 `build-output/w386-probe/verdict.md`（每链逐行 PASS/FAIL + 实测数字）。
- 脚本须带 **PASS 计数下限**（六链共 18 条，低于下限 `exit 1`），避免"空跑也绿"。
- 探针只**新增**业务单据，不删改演示库既有数据（DB-64 口径）。

---

## 3. 被消费接口与契约（零新增）

| 用途 | 端点 | 说明 |
| --- | --- | --- |
| 环节计数 | `GET /api/v1/evidence/overview` | 治理读面，T1/T3/探针 A~E 共用 |
| 记录检索 | `GET /api/v1/evidence/records` | T8 基准被测面 |
| 单条核验 | `GET /api/v1/evidence/records/{evidenceNo}/verify` | T7 |
| 重试 | `POST /api/v1/evidence/records/{evidenceNo}/retry` | T4 |
| 审计异常 | `GET /api/v1/evidence/audit/anomalies` | T6/T8 |
| 内部写面 | `POST /api/v1/evidence/internal/records` | T1 造数 |

**权限点**：全部消费既有（不新增）；实际权限点名称以 P5 前置检查实测为准，不得凭记忆写死。

---

## 4. 覆盖率与变异口径（只测量，不开门禁）

| 项 | 口径 |
| --- | --- |
| evidence 行覆盖 | 实测 **≥80%**（基线 90.70%，**不得劣化**）；`com.ctds.evidence.domain` ≥90%（基线 94.44%） |
| 全仓 overall | ≥70%（当前 92.37%） |
| 变异测试 | **维持 did-only 不扩**（Q5-A）；evidence 变异建议登记台账，不走本包 |
| 门禁配置 | `gates-config.json` **零改动**，ConfigSha256 与基线（`955bdbba…2ee2`）逐字一致 |

---

## 5. 债务承接逐项（关闭 / 转态 / 登记）

| 债务 | 本包处置 | 依据 |
| --- | --- | --- |
| **DB-109**（探针工具链混用） | **关闭**：本包探针收敛为 `build-output/w386-probe/` 单一工具链，脚本头部注明替代关系 | 关闭条件 = 统一入口可核 |
| **DB-110**（对账测试类重复实现） | **关闭**：T2 幂等断言与基准造数抽公共工具类，删重复实现 | 关闭条件 = 重复实现 0 处 |
| **DB-115**（真链适配器未接入） | **转"部署期承接"**：本包以 T10 补结构位，关闭条件改为"真链环境就绪后接入并实测"，**不虚报真链已对接** | lofi Q7-A |
| DB-113 / DB-116 / DB-117 | **择可自动化项处置**（与 T4~T7 重叠项合并），其余保持"新登记"并注明承接批次 | 1 会话预算边界 |
| DB-114 | ②③④⑥ 由 T4/T5/T6/T7 处置 | 逐条可核 |

---

## 6. 剧本承载对照（业务判定零变更）

| 剧本步骤 | 承载链 | 结论 |
| --- | --- | --- |
| `C-8.1` **S1-1**（四环节基线各记一值） | 探针 A/B/C/D 的"恰 +1" | 自动化可验 |
| `C-8.1` **S1-2~S1-5**（授权/合约/交付/使用 各 +1 + 单号一致） | 探针 A/B/C/D | 自动化可验 |
| `C-8.1` **S1-8**（≥2 条 × 六要素齐备） | T1 + 探针 F | 自动化可验 |
| `C-8.1` 附录 A（链不可达 → FAILED → 恢复） | 探针 E | 自动化可验 |
| `C-8.2` **S1-1~S1-3**（矩阵 / 五类单号 / 主体摘要 + 时间窗） | 探针 F + T8 | 部分自动化 |
| `C-8.2` S1-4 起的界面步骤 | —— | 仍为人工（3.8.5 界面） |

**剧本修订口径（Q8-A）**：C-8.1 V1.4 → **V1.5**、C-8.2 V1.3 → **V1.4**，**仅维护说明追加《自动化覆盖对照》一节**（业务判定与步骤编号一字不改），须 PO 批准。

---

## 7. 编码段第一动作：前置检查 P1~P8（逐项实测留痕，不成立即停）

| 编号 | 检查 | 不成立的处置 |
| --- | --- | --- |
| P1 | evidence 基线 70 例实跑绿 | 停，先查环境 |
| P2 | gateway 基线例数实跑（47 例口径以实测为准） | 同上 |
| P3 | `EvidenceRetryIntegrationTest` 现有覆盖清单（T4/T5 是否已覆盖） | 已覆盖则改为"补反向断言"，不重复造 |
| P4 | `EvidenceVerifyIntegrationTest` 是否已覆盖删块 MISMATCH（T7） | 已覆盖则 T7 改为补"overview 异常计数" |
| P5 | overview 出参**真实结构**与端点**真实权限点**实测 | 与本文不一致 → 以实测为准并回填设计勘误 |
| P6 | 链网关类名与"单一实现"现状实测（T10 可行性） | 结构已达标则 T10 降为注释性断言 |
| P7 | 探针起线脚本可用性（四端口可达 + 身份头） | 不可用 → 修脚本并留痕，不带病开跑 |
| P8 | 演示库 `ctds_evidence` 现有单号段位（避免探针单号冲突） | 冲突 → 换段位并登记 |

---

## 8. 体量与拆分

- 预估：测试代码 **~700 行**（T1~T10 五类新增/补强）+ 探针脚本 **~250 行** + 文档 **~250 行** = **900~1300 行**。
- **D1：不拆分**（1 编码会话）。超 1300 行按登记制豁免并说明超出原因。

## 9. 设计勘误位（编码段回填）

> **2026-11-11 编码段前置检查 P1~P8 实测回填**（留痕 `build-output/w386-precheck-2026-11-11.md`，gitignored；任务卡 §四同步登记）。契约未变更版本号，以下勘误即时生效、优先于正文对应表述。

| 编号 | 勘误（以实测为准） | 实测依据 | 对 T 清单的影响 |
| --- | --- | --- | --- |
| **E1** | **四环节矩阵端点更正**：`GET /api/v1/evidence/overview` 实测为**三状态计数**（`onChainCount` / `pendingCount` / `failedCount` / `lastFailedAt`），**无四环节维度**；四环节 × 三状态矩阵在 `GET /api/v1/evidence/audit/overview` | `EvidenceGovernanceController:80-84`、`EvidenceOverviewView:6-11`、`EvidenceAuditController:50-56`、`EvidenceAuditViews:15-26` | **T1 / T3 落点改为 `/api/v1/evidence/audit/overview`**；T1 增量口径 = 与 `evidence_record` 按 `(stage_type,status)` 分组计数**逐项对账**（既有 `overviewMatrixCountsPerStage` 为硬编码期望值，不对账） |
| **E2** | **T6 原文"不计入异常数"与实现语义冲突**：PENDING / FAILED 本身即异常三计数之二（`anomalies.pendingCount` / `failedCount`）；`chain_tx_id IS NULL` 的记录必为 PENDING 或 FAILED | `EvidenceAuditViews.AnomalyCountsView`、`EvidenceAuditOverviewService:38-65` | **T6 断言更正为**：`chain_tx_id IS NULL` 且状态 PENDING → 矩阵 `PENDING` 列 +1、`anomalies.pendingCount` +1、**`mismatchCount` 不变**（不被误判为核验不一致）、异常清单 `anomalyType=PENDING` |
| **E3** | **T5 期望状态与规格/实现冲突**：`chain-enabled=false` 写入实测 = 记录落库 + 状态 **FAILED**（`last_error_summary=存证链暂不可达`）+ 零账本块（规格 B2-1 fail-visible），**不是 PENDING**；且该场景**已被** `EvidenceWriteFailureIntegrationTest.chainDisabledKeepsRecordFailedAndVisible` **完整覆盖** | `EvidenceWriteService:141-155`、`LocalLedgerEvidenceGateway:51-55`、`EvidenceWriteFailureIntegrationTest:83-104` | **T5 不新增用例**（降为既有覆盖登记） |
| **E4** | **T4 已被覆盖**：`EvidenceWriteFailureIntegrationTest.retryWhenChainStillUnavailableReturns503AndStaysFailed` 已断言 503 + `1012S0001` + 保持 FAILED + `retry_count=1` + `RETRY_FAILED` 留痕 | 同上 `:106-128` | **T4 改为补 1 条反向断言**：重试后 `COUNT(1) FROM evidence_record = 1`（不产生第二条记录） |
| **E5** | **T2 并发兜底路径已被覆盖**：`EvidenceIdempotencyRaceIntegrationTest`（预检漏过 + 唯一键冲突 → `duplicate=true`、零新行/留痕/块） | `EvidenceIdempotencyRaceIntegrationTest:80-106` | **T2 增量收敛为正常预检命中路径**：重复写 → `duplicate=true` + 读面（矩阵与 `anomalies`）计数均不 +1 |
| **E6** | **权限点实测登记**（零新增）：治理 5 端点 = `@RequirePermission("evidence.governance")`；审计 5 端点 = `EvidenceAuditAccessGuard` → `AccessControl.require("evidence.governance")`（同权限点，拒绝口径 400 + 1000C0003）；内部写 1 端点 = `@RequirePermission("evidence.internal.write")` | `EvidenceGovernanceController:45/60/67/74/81`、`EvidenceAuditAccessGuard:33-40`、`EvidenceInternalController:31` | T1/T2/T3/T6/T7 沿用既有身份头（`S-admin-evidence` + `admin`）与内部写身份（`X-Ctds-Roles: evidence-internal`），零新增权限点 |
| **E7** | **T9 不可达状态码实测 = 500**（非 502/503）：evidence 基址指向未监听端口时，Netty `AnnotatedConnectException` 经 `common` 全局异常处理器按"未知异常"映射 **500 + 通用文案**（网关侧未做 502/503 语义专化）。**502/503 语义专化 = 改网关错误映射（业务代码变更），本包零业务改动不做**，登记为观察项随缺陷/变更流程 | `common/errorcode/src/main/java/com/ctds/common/web/GlobalExceptionHandler.java:18-19`（未知异常一律 500）、`GatewayEvidenceUnavailableIntegrationTest` 实测日志 `Connection refused` + 5xx | **T9 断言口径 = 5xx 且响应体不含 `"code":"0"`（不假成功）**；不可达构造改用 **`unusedPort()` 空闲端口**（`127.0.0.1:1` 在本机触发 5s 读超时挂起而非确定性拒绝，沿 did / subject 同款先例） |
