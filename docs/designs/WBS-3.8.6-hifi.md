# WBS-3.8.6 高保证真设计（hifi）：存证审计测试与集成

- 版本：**V1.0 = 编码契约**（**2026-11-10 编排师一次确认 = Q1~Q8 全采 A + D1 不拆分，本文件即时生效**；V0.9 为待确认稿）
- 日期：2026-11-10｜对应 lofi：`docs/designs/WBS-3.8.6-lofi.md` V1.0
- 性质：**测试与集成类**。**零新端点 / 零新表 / 零迁移 / 零新错误码 / 零新权限点 / 零新第三方依赖**；**门禁配置变动仅限超时预算**（2026-11-11 编排师裁定 A，V1.13；判定面零改动，见 §9 E10；Q4/Q5 采 A 维持不扩 coreModules/变异）

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
| **T6** | `EvidenceStageAggregationIntegrationTest`（与 T1~T3 同类，见 §9 E11） | `chain_tx_id IS NULL` 的记录在 overview/anomalies 中按"待上链"呈现，**不计入异常数** | DB-114 ④ |
| **T7** | 新 `EvidenceChainIntegrityIntegrationTest` | 篡改本地账本（删块）→ `POST /records/{no}/verify` 返回 **MISMATCH**（若既有 `EvidenceVerifyIntegrationTest` 已覆盖则改为补"篡改后 overview 异常计数 +1"） | DB-114 ⑥ |
| **T8** | 新 `EvidenceAuditBenchmarkIntegrationTest` | 造 1 万条存证流水 → 审计 `records` 列表与 `anomalies` 查询 **P95 ≤ 500ms**（5 场景 × 20 轮 + 5 轮预热，P50/P95/max 落 `target/benchmark/evidence-audit.json`） | 性能敏感路径（AGENTS 行 76） |
| **T9** | 补强既有 `GatewayConnectorRoutesIntegrationTest`（body 逐字透传断言并入 `connectorEvidenceRouteForwardsInstancePath`）+ 新 `GatewayEvidenceUnavailableIntegrationTest`（见 §9 E11） | `GET /api/v1/evidence/overview` 经网关 → 转发成功、**响应体逐字透传（含中文）**、**不进交付判定链**（`filters: []`，既有例已断言）；evidence 桩不可达 → 网关 **5xx** 而非 200 假成功（E7 实测 500） | 立卡缺口 ②（勘误：既有例已断言转发/判定链/计量，真增量 = 响应体逐字透传，见 §9 E11） |
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
| 单条核验 | `POST /api/v1/evidence/records/{evidenceNo}/verify` | T7（实测 `@PostMapping`，见 §9 E11） |
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
| 门禁配置 | **变动仅限超时预算**（裁定 A：`unitTest`/`coverage` 1800→2700s、`frontendTest` 300→600s，V1.13）；判定阈值/阶段范围/扫描面零改动；ConfigSha256 与新基线 `adc3f98b…5cfb` 逐字一致（见 §9 E10） |

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
| `C-8.2` **S1-1~S1-3**（矩阵 / 五类单号 / 主体摘要 + 时间窗） | 探针 F + T1/T8 + 既有审计 CI 用例 | 服务层自动（真机探针 F + CI 背书；界面人工，见 §9 E11） |
| `C-8.2` S1-4~S1-8（调阅 / 拒 / 留痕 / 越权 / 脱敏） | 既有审计 CI 用例（`EvidenceTrail`/`Guard`/`Overview` 等） | 服务层自动（界面人工，见 §9 E11） |

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
| **E8** | **探针面四处实测校正**（18 条判定已按此执行并全 PASS，留痕 `build-output/w386-probe/verdict.md`）：① **A 链单号第 3 段 = 准入单主键**（`SPACE{spaceId}:ADMISSION{admissionId}`），非本文 §2 所写 `{subjectId}`；② **A 链申请人须由探针先签发 DID**——空间 2 全部持 DID 主体均已是成员（无法产生"新增准入"），且 2026-09 及更早的 DID 行 `guard_key` 为空，内部"当前 DID"查询不可见（仅新签发行可见），故 A 链 = 先幂等签发 DID → 空间 2 申请 → owner 审批；③ **B 链双写需 `CT000001`**（公共数据授权类）；`CT000002` 属 `API_CALL`，`isPublicDataAuthorization` 为假 → 不双写；且合约发起按条款指纹幂等，重跑须在条款值内带运行标签才能生成新合约；④ **C 链第 3 条判定 = 环节 / 操作类型**（`DELIVERY/DELIVERY_CREATED`）——本文 §2 的 C 只列 2 条，与"每链 3 条 = 18 条"口径不一致，按 18 条固化 | `SpaceEvidencePublisher:41`（`"SPACE"+spaceId+":ADMISSION"+admissionId`）、`DidJdbcRepository.findActiveOrPending`（`guard_key IS NOT NULL`）、`InternalCurrentDidController:36`、`ContractEvidencePublisher:68-71/95-99`、`ctds_contract.contract_template.template_type`（CT000001 = PUBLIC_DATA_AUTHORIZATION / CT000002 = API_CALL）、实测 `CO000045` 重复发起返回同号 | 探针按 E8 口径实现（`build-output/w386-probe/w386-probe.ps1`）；A 链"干净首跑"约束写进脚本头与脚本内提示（重跑需换申请人） |
| **E9** | **债务处置口径校正（§5 DB-110 = 对象错配，不关闭）**：§5 原拟"DB-110 关闭（T2 幂等断言与基准造数抽公共工具类，删重复实现）"，但台账 DB-110 实为 **metering 对账测试类重复**（`BillingReconciliationAssertionIntegrationTest` / `BillingAutoReconcileIntegrationTest`，承接 = metering 测试硬化批），本包按"零 metering 改动"闸门未触碰 → **不关闭、维持跟踪**（台账行已改注承接对象）。本包 evidence 侧亦未抽公共造数类（仅 `SharedMySqlContainer` 连接上限 +4），不构成"重复实现删除"。**§5 其余处置按实登记**：DB-109 关闭 / DB-115 转部署期（T10 结构位）/ DB-114 ⑥ 关闭 + ④ 部分 / DB-113 P3-8 空集子项关闭 + P3-7 断言层部分 / DB-116·DB-117 无兑现项维持跟踪 / 新增 **DB-121**（E7 网关不可达 500 语义专化观察项） | 台账 `docs/status/进度看板与债务登记簿.md:144`（DB-110 原文对象 = metering）；编码批 `70d8161` git 实证 11 文件全在 evidence / gateway | **不影响 T1~T10 与探针**；关闭条件⑥"债务处置落地"按"可兑现者关闭、不可兑现者如实登记"判为达成（不虚报） |
| **E10** | **门禁配置"零改动"勘误（裁定 A 超时预算变更，评审 P1-1）**：正文 §4 与 lofi/任务卡"零门禁配置改动 / 与旧基线 `955bdbba…2ee2` 逐字一致"已被 2026-11-11 编排师裁定 A 取代——`gates-config.json` V1.12→**V1.13**，仅三处超时预算变更（`unitTest`/`coverage` 1800→**2700s**、`frontendTest` 300→**600s**）+ `run-gates.ps1` 前端四段改读 `stages.<name>.timeoutSeconds`（未配键默认 300s 不变 → `frontendTypeCheck`/`frontendLint` 行为零变化）。**判定面零改动**：阈值（core 80 / overall 70 / 变异 60）、`coreModules` 5 项、`mutationTest.modules`、`secretsExcludePaths`、阶段范围与五态退出码语义与 main **逐字一致**（主会话 `git diff` 键值复核）。授权链 = 申请单 → 编排师 2026-11-11 回复"A" → ADR-018 V1.11 / README / config changeLog V1.13。新基线 `ConfigSha256 adc3f98b…5cfb`（首次登记 `df13795c…3cd5`，日志更名致 changeLog 文本级漂移，`-224534` 复跑刷新逐字一致）。GREEN 证据 = `gate-report-20261111-224534.md`（PASS=15/FAIL=0，unitTest 1170s） | 申请单 §七、ADR-018:16、config:4 changeLog V1.13、README:100、报告:7 | 关闭条件②⑨与纪律声明"零门禁配置"文本同步修订为"变动仅限超时预算（裁定 A），判定面零改动"（**契约文本修订，2026-11-12 编排师会话确认**）；不影响 T1~T10 与探针 |
| **E11** | **评审循环 1 修复段正文校正（P2-1 / P2-6 / P1-4 / P1-5）**：① **单条核验端点 = POST**（§3 原写 GET 笔误，实测 `EvidenceGovernanceController:66 @PostMapping`，不改接口迁就文档）；② **T6 落点 = `EvidenceStageAggregationIntegrationTest`**（§1 原写"新 ChainIntegrity"失实，T6 实在 StageAgg:169-192；T7 才是新 ChainIntegrity）；③ **T9 收敛**：删新类 `GatewayEvidenceRouteTransparencyIntegrationTest`（4 条断言中 3 条与既有 `GatewayConnectorRoutesIntegrationTest:195-208` 重复），真增量"响应体逐字透传（含中文）"并入既有 `connectorEvidenceRouteForwardsInstancePath`；保留 `GatewayEvidenceUnavailableIntegrationTest`（不可达 5xx 真增量）→ gateway **49→48 例**（47 基线 + 1 不可达）；④ **立卡缺口②表述校正**：网关 `connector-evidence` 非"零自动化断言"，既有例已断言 200 + 转发路径 + 判定链零调用 + 零计量，真增量仅响应体逐字透传；⑤ **C-8.2 程度口径统一**：S1-1~S1-3 由"全自动"降为"服务层自动（界面人工）"（真机探针 F + CI 背书，S1-2 真机覆盖 4/5 单号家族、POLICY-AL 由服务层用例补），S1-4~S1-8 既有 CI 用例真实背书维持"服务层自动"（原 hifi/任务卡写"人工"属低估，校正）；两本剧本"人工"定义统一采 C-8.1:71 口径（演示期受控构造或纯观察点） | `EvidenceGovernanceController:66`、`EvidenceStageAggregationIntegrationTest:169-192`、`GatewayConnectorRoutesIntegrationTest:68/:116-132/:195-208`、C-8.1:71 / C-8.2:81 | gateway 例数 49→48（任务卡状态行/编码段、C-8.1 对照同步）；剧本程度三方一致；**契约 T9 落点变更属评审修复，留痕本勘误** |
| **E12** | **红相取证补全（评审 P1-3，A 案真注入 + 其余 B 案豁免）**：原 `w386-red-evidence.log`/`-gateway.log` 实为全绿（78/0/0、49/0/0 + BUILD SUCCESS），未兑现 §1"先红后实现"契约。本修复段以**临时缺陷注入**补三处真红（跑红即还原，`git status services/*/src/main` 零残留）：① **T1** = `EvidenceAuditOverviewService:52` 矩阵计数 `row.count()`→`row.count()+1` → `overviewMatrixMatchesDatabaseGroupByCounts` 红（`w386-red-t1.log`：Failures:1 / expected 1L / BUILD FAILURE）；② **T7** = `EvidenceVerifyService:68` 空账本 `MISMATCH`→`PASS` → `deletedLedgerBlockReportsMismatchAndCountsAsAnomaly` 红（`w386-red-t7.log`：Failures:1 / expected MISMATCH）；③ **T9** = 临时 `TempRedBodyFilter`（Spring 6.1 `writeWith(Publisher)` 响应装饰器）篡改响应体首字节 → `connectorEvidenceRouteForwardsInstancePath` 红（`w386-red-t9.log`：Failures:1 / body `{`→`X`）。三处还原后复跑绿（StageAgg 4 + ChainIntegrity 1 + ConnectorRoutes 8 全 0 失败）。**其余 T 编号 B 案豁免**：本包**零业务代码改动**（测试断言既有正确行为，非新功能实现，"先红后实现"范式按代表点真红 + 断言反证替代）——T4/T5 复用既有 `EvidenceWriteFailureIntegrationTest` 覆盖（E3/E4）；T2 幂等 / T3 空集键 / T6 待上链 / T8 基准 / T10 结构断言均由 T1/T7/T9 三真增量点代表"断言可抓缺陷"，且 T3 缺键盲区已补 `has(status)` 真断言（P2-14）、T6 已改真实写面构造（P2-15） | 三份真红日志 `build-output/w386-red-t{1,7,9}.log`（gitignored，各含 Failures:1 + BUILD FAILURE）；还原后 src/main git 零残留 + 复跑绿 | §1 红相契约兑现（代表点真红 + 零业务改动豁免）；P1-3 关闭 |
