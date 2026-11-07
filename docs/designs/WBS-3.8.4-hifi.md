# WBS-3.8.4 监管审计视图 · 高保真设计（编码契约稿）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-11-07 编排师会话回复"全部按建议确认"：Q1~Q10 均采建议口径 A + D1 不拆分 = 编码契约即时生效）**；V0.9 = 立卡草案 |
| 上游 | lofi V1.0（方向定稿）；规格 C-8.1-8.2 V1.1 B5~B8；剧本 C-8.2 V1.1 |
| 实现底线 | 加性只读；3.8.2 既有 5 端点契约 / 内部写面 / 网关 / k8s / 前端 / 门禁配置**零改动**；零新第三方依赖；SM3 只走 `common` `Sm3Service` |

## §1 端点契约（evidence-service 新增 5 端点，统一前缀 `/api/v1/evidence/audit`）

> 出站统一 `ApiResult`；除注明外权限口径 = §5 审计访问守卫（非治理档 → 400 + 1000C0003 + 文案"记录不存在"，与"对象不存在"同码同文）。

| # | 方法与路径 | 入参 | 出参（业务字段） | 边界与错误 |
| --- | --- | --- | --- | --- |
| E1 | GET `/audit/overview` | `from`/`to` 可选（同时给，跨度 ≤90 天） | `matrix`：四环节 × {PENDING, ON_CHAIN, FAILED} 计数；`anomalies`：{pendingCount, failedCount, mismatchCount}；`snapshotAt` | 时间窗非法 → 1012C0002 |
| E2 | GET `/audit/anomalies` | `from`/`to` 可选 + `pageNum`/`pageSize`（沿 PageQuery ≤100） | 分页清单：每条 = 存证记录视图（沿 `EvidenceRecordView` 白名单）+ `anomalyType`（PENDING / FAILED / MISMATCH）+ 最新核验结论与核验人时点 | MISMATCH 判定 = 每条存证最新一条 `action=VERIFIED` 留痕 `detail='MISMATCH'`（§2 查询口径） |
| E3 | GET `/audit/trails` | `businessNo` / `subjectDigest` / `stageType` / `from` / `to` **至少一种**；分页参数 | **单据号命中**：`evidenceRecord` + `evidenceActions[]` + `businessTrail[]`（跨域留痕节点：source 域名 / occurredAt / actorDigest / action / result / summary 白名单字段 / `sourceStatus`=OK\|UNAVAILABLE）+ `timelineAvailable`；**其余条件**：存证清单分页（不内联跨域，附"按单据号展开全链时间线"提示字段） | 无条件 → 1012C0002；窗 >90 天 → 1012C0002；单号不存在 → 400+1000C0003（同码同文）；成功调阅（两种模式）→ 落 1 条调阅留痕 |
| E4 | POST `/audit/reports` | body：`stageType`/`businessNo`/`subjectDigest`/`from`/`to`/`remark`（条件 ≥1；窗 ≤90 天） | `reportNo`（`EV-RPT-yyyymmdd-xxxx`）、范围口径、存证清单条数、核验结论汇总（PASS / MISMATCH / NOT_ON_CHAIN / 未核验分布）、异常项清单条数、内容摘要、生成人、生成时点 | 无条件 / 窗超限 → 1012C0002；清单 >10000 条 → 1012C0002（文案"清单条数超过导出上限 10000"）；重复生成 = 新编号不覆盖 |
| E5 | GET `/audit/reports/{reportNo}/export` | `format` = `csv` \| `json`（默认 json） | 登记快照原样输出（CSV 列白名单：evidenceNo / stageType / businessNo / operationType / subjectDidDigest / occurredAt / status / chainTxId / contentDigest / verifyConclusion / anomalyType） | 报告不存在 → 400+1000C0003（同码同文）；重算 SM3 ≠ 登记 → **1012S0003**（HTTP 500，文案"报告内容完整性校验未通过"，**不输出文件**）；成功 → 落 1 条导出留痕 |

**跨域内部只读端点（新增 4 个，全部 `@RequirePermission`，服务间直连回环）**：

| 服务 | 路径 | 入参 | 出参（白名单） |
| --- | --- | --- | --- |
| space | GET `/api/v1/data-spaces/internal/audit-trails` | `spaceId` + `admissionId` \| `actionLogId`（二选一） | `admission`（id/subjectNo/type/status/operator/reason/createdAt，subjectNo 由聚合层摘要化）+ `actionLogs[]`（id/targetType/targetId/action/operator/fromValue/toValue/result/reason/createdAt） |
| contract | GET `/api/v1/contracts/internal/{contractNo}/audit-trail` | 路径参数 contractNo | `attestation`（contractNo/contentHash/双方 DID/双方签署时点/attestedAt）+ `actionLogs[]`（同构白名单） |
| contract | GET `/api/v1/contracts/internal/usage-logs/{usageLogId}/audit-trail` | 路径参数 usageLogId | `usageLog`（id/contractNo/actionType/outcome/reasonCode/usedCount/occurredAt；requesterNo 由聚合层摘要化） |
| delivery | GET `/api/v1/deliveries/internal/{deliveryNo}/audit-trail` | 路径参数 deliveryNo | `delivery`（deliveryNo/contractNo/deliveryType/result/fileName/fileSize/createdAt）+ `actionLogs[]`（operatorNo 摘要化） |

## §2 数据模型与迁移（evidence V2__create_evidence_audit_tables.sql，3 表）

| 表 | 要点 |
| --- | --- |
| `evidence_access_log` | `id` PK AI；`access_kind` VARCHAR(16)（`TRAIL_ACCESS` / `REPORT_EXPORT`）；`accessed_by` VARCHAR(128)；`conditions_json` VARCHAR(1024)（脱敏后键值，键 ≤20 值 ≤512 沿审计契约）；`result_count` INT；`purpose` VARCHAR(64) 可空（"结论用途"，请求可传）；`created_at` DATETIME(6)；索引 `(accessed_by, created_at)`、`(access_kind, created_at)`。**append-only** |
| `evidence_audit_report` | `id` PK AI；`report_no` VARCHAR(32) **UK**；`scope_json` VARCHAR(1024)；`record_count` INT；`conclusion_summary_json` VARCHAR(1024)；`anomaly_count` INT；`report_content_json` MEDIUMTEXT（生成时快照，仅存证要素/摘要/元数据，无原文）；`content_digest` CHAR(64)（SM3 hex）；`remark` VARCHAR(255) 可空；`generated_by` VARCHAR(128)；`generated_at` DATETIME(6)。**append-only**（无 update/delete 端口方法） |
| `evidence_report_no_seq` | `seq_date` DATE PK；`next_val` INT —— 沿 `evidence_no_seq`（V1:9-13）取号范式 |

- **迁移结构机器断言**：沿 `EvidenceMigrationStructureIntegrationTest.java:41-81` 先例扩展（3 表 + 唯一键逐项断言）。
- **businessNo 反解规则**（`EvidenceBusinessNoRouter`，五类封闭，前缀不识别 → 仅存证侧 + `sourceStatus=UNAVAILABLE` 说明）：`SPACE{s}:ADMISSION{a}` → space（spaceId=s, admissionId=a）；`SPACE{s}:POLICY-AL{g}` → space（spaceId=s, actionLogId=g）；`CO######` → contract（contractNo）；`DL######` → delivery（deliveryNo）；`USG{12位数字}` → contract（usageLogId）。
- **摘要对齐口径**：跨域返回的 DID / 主体编号原文由 evidence 聚合层统一 `Sm3Service.digestHex` 摘要化出站；**算法须与 `evidence_record.subject_did_digest` 生成算法逐字对齐**（§7 前置检查 P1 实测 `EvidenceDigests`）。

## §3 各服务改动清单

| 服务 | 改动 | 明细 |
| --- | --- | --- |
| evidence | 新增 15 文件左右 | controller 1 + exceptionHandler 扩展 + guard 1 + service 3 + 路由 1 + 端口 1 + 客户端 3 + 仓储 3 + 视图 records 若干 + yml 2 行 |
| space | 新增 3~4 文件 | `SpaceInternalAuditController` + service 查询（复用既有仓储查询能力，按 admissionId/actionLogId 锚点）+ 视图 record；`application.yml` 角色映射 +1 行（`evidence-internal: space.internal.read`） |
| contract | 新增 4~5 文件 | `ContractInternalAuditController`（两路径）+ service 查询（attestation + action_log + usage_log 锚点查询）+ 视图 record；yml +1 行（`evidence-internal: contract.internal.read`） |
| delivery | 新增 3~4 文件 | `DeliveryInternalAuditController` + service 查询 + 视图 record；**新增权限点 `delivery.internal.read`** + yml 角色映射（`evidence-internal: delivery.internal.read`）——delivery 首个内部端点，沿各域 InternalXxxController 范式 |
| 零改动面 | — | 网关 / k8s / 前端 / `gates-config.json` / 3.8.2 既有端点与服务 / 根 pom（无新模块） |

## §4 事务与幂等

1. **调阅留痕**：聚合查询完成后独立短事务插入（沿 `EvidenceRegistrationWriter` 分段写口先例）；留痕失败不阻断调阅返回？——**不**：留痕失败 = 抛 1012S0002（调阅留痕是 B6-3 硬要求，与"不吞异常"一致；不采 fail-open）。
2. **报告生成**：取号（`UPDATE ... next_val = next_val + 1` 单行原子，沿 `JdbcEvidenceNoGenerator`）→ 同一事务内落 `evidence_audit_report` 单行；**查询快照在事务外先行**（读多行），落库失败 = 1012S0002，可重试（重试 = 新编号，符合"重复生成新编号"）。
3. **只插不改**：`AccessLogRepository` / `AuditReportRepository` 端口仅 insert + select；ArchUnit 断言（沿 `EvidenceArchitectureTest` 扩展）。
4. **跨域客户端**：connect 2s / read 3s；**失败不重试**（与 3.8.3 客户端口径一致）→ 时间线节点标 `UNAVAILABLE` + WARN 结构化日志（只含域与单号，不含 DID 与原文）。

## §5 权限与错误码

1. **审计访问守卫**（Q4-A）：5 个审计端点**不挂** `@RequirePermission`（避免 403 抢先）；`EvidenceAuditAccessGuard` 在方法首行调 `AccessControl.require("evidence.governance")`，捕获 `AuthException` → 抛 `BizException(ErrorCodes.RESOURCE_NOT_FOUND /* 1000C0003 */, "记录不存在")`——与"对象不存在"**同码同文**（subject-service `OwnershipGuard.java:45-53` 先例同型；拒绝同时由 `DefaultAccessControl` 落 rbac DENIED 审计，留痕自然发生）。
2. **"对象不存在"出站**：trails 单号查无 / 报告编号查无 → 同样 400 + 1000C0003 + "记录不存在"（防枚举，S1-7 双向参照）。
3. **3.8.2 既有端点零改动**：`1012C0001`（404 记录不存在）等五码语义与 HTTP 映射不动；新旧两族口径差异在交付说明留痕。
4. **新错误码**：`1012S0003`（HTTP 500，报告完整性校验未通过）——`EvidenceErrorCodes.java` 按留白序启用；其余全部复用（1012C0001 不用于新族 / 1012C0002 参数与上限 / 1012S0002 取号落库失败）。
5. **跨域权限点**：`space.internal.read` / `contract.internal.read` 已存在（仅加 evidence-internal 映射行）；`delivery.internal.read` 新增（delivery yml `ctds.auth.permissions` 段）。

## §6 配置与部署

- evidence `application.yml`：无新增配置项（审计端点无开关需求——治理读面本就受权限保护；`ctds.evidence.audit.*` 如需上限参数化，默认值硬编码 + 常量，V1.0 不做配置面——按"最主流"口径：上限常量类 `EvidenceAuditLimits`，避免配置漂移）。
- 三服务 yml 角色映射 +3 行（见 §3）；`CTDS_*_BASEURL` 环境变量：space/contract/delivery 客户端 base-url 默认 `http://127.0.0.1:<port>`（8083/8085/8087），**测试默认指向桩、不依赖 env**；k8s 零改动（同集群 Service DNS 由默认值？——**否**：k8s 内需注入三变量，但本卡 k8s 清单**零改动**为关闭条件⑥……**裁定**：三份 deployment 追加 env 属部署清单变更，若确认零改动则客户端默认值在 k8s 内不可达——**本卡允许三份 k8s deployment 各 +1 env（`CTDS_EVIDENCE_SPACE_BASEURL` / `_CONTRACT_BASEURL` / `_DELIVERY_BASEURL`），属配置补齐非结构变更，在关闭条件⑥中如实列明**；沿 3.8.3 三份 k8s env 先例）。
- 前端 / 网关 / `gates-config.json`：零改动（网关 `Path=/api/v1/evidence/**` 前缀自动覆盖新端点）。

## §7 前置检查（编码会话第一动作，逐项实测留痕；任何一项不成立 → 停止上报）

| # | 检查项 | 实测目标 |
| --- | --- | --- |
| P1 | `EvidenceDigests` / `subject_did_digest` 生成算法 | 确认 SM3 输入构成（DID 原文？），聚合层摘要化逐字对齐 |
| P2 | `AccessControl.require` 抛出类型可捕获性 | `DefaultAccessControl.require` 抛 `AuthException`（非 BizException 子类？）→ guard 捕获方案成立；`ErrorCodes.RESOURCE_NOT_FOUND` 常量存在 |
| P3 | `EvidenceMigrationStructureIntegrationTest` 扩展点 | 断言结构可复制到 V2 三表 |
| P4 | 三域留痕仓储既有查询能力 | space 按 admissionId/actionLogId、contract 按 contractNo/usageLogId、delivery 按 deliveryNo 的查询是否现成，缺则补仓储方法（只读） |
| P5 | 各域 InternalXxxController + yml 角色映射范式 | 路径前缀、`@RequirePermission` 行号、yml 段位（space/contract 加行、delivery 新段） |
| P6 | JDK HttpClient 桩测试范式 | JDK `HttpServer` 桩（3.8.3 T 系先例）复用方式；Testcontainers 基线 42 例复测 0 失败 0 跳过（本机有 Docker） |
| P7 | `PageQuery` / `PageResult` 复用 | 分页上限与白名单正则；trails 清单模式接入方式 |
| P8 | CSV 渲染零依赖可行性 | 手写 CSV 转义（逗号/引号/换行）最小实现；无新依赖 |
| P9 | 测试基线复测 | space 164 / contract 267 / delivery 230 / evidence 42 复跑归绿 |
| P10 | k8s env 变更面核对 | 三份 deployment 追加 env 的 diff 范围（§6 裁定）与 kustomization 零结构变化 |

## §8 测试锚点（T1~T22，测试先行；映射规格 AC 与剧本步骤）

| # | 用例 | 断言要点 | 规格/剧本 |
| --- | --- | --- | --- |
| T1 | 总览矩阵 | 四环节各 1 条 → 矩阵计数正确 + snapshotAt 可读 | B5-2① / S1-1 |
| T2 | 总览异常计数 | 构造 FAILED + MISMATCH → 三计数正确 | B4-2 / B5-2② / S1-1 |
| T3 | 异常清单三类 | PENDING/FAILED/MISMATCH 各 ≥1，类型标记正确 | B5-2② |
| T4 | CO 号时间线 | attestation + contract_action_log 内联 + DID 摘要化 | B5-2③ / B6-1 / S1-2·S1-4 |
| T5 | DL 号时间线 | delivery + delivery_action_log 内联 | 同上 |
| T6 | USG 号时间线 | usage_log 行内联 + requesterNo 摘要化 | 同上 |
| T7 | SPACE 两类时间线 | ADMISSION / POLICY-AL 两锚点反解正确 | 同上 |
| T8 | 主体过滤 | subjectDigest 等值过滤命中/不命中 | B5-2① / S1-3 |
| T9 | 无条件调阅拒 | 1012C0002 | B6-2 / S1-5① |
| T10 | 宽窗调阅拒 | >90 天 → 1012C0002 | B6-2 / S1-5② |
| T11 | 调阅留痕 | 成功 1 次 → access_log 1 行（调阅人/时点/条件/条数） | B6-3 / S1-6 |
| T12 | 越权同码同文 | 非治理档 ↔ 不存在单号，**code/message/HTTP 三逐字一致**（双向参照） | B5-4 / S1-7 |
| T13 | 报告生成 + 脱敏负向 | 要素齐 + 编号唯一 + 报告/导出内容零原文零个人信息（负向字段扫描） | B7-1 / B6-4 / S2-1·S2-6 |
| T14 | 同快照对照 | 同条件报告条数/结论分布 = 视图读数 | B7-1 / S2-2 |
| T15 | 重复生成 | 新编号不覆盖，两份均可读 | B7-2 / S2-3 |
| T16 | 下载 CSV/JSON | 形态正确 + 导出留痕 1 行 | B7-3·B7-4 / S2-4 |
| T17 | 完整性核验 | 正常下载一致；**篡改库内 content → 1012S0003 且不输出** | B7-2 / S2-7 |
| T18 | 报告超限拒 | 无条件 / >90 天 / >10000 条 → 1012C0002 | B6-2 |
| T19 | 跨域降级 | 桩超时 → `sourceStatus=UNAVAILABLE` + WARN + 时间线其余段正常 | B2-1 fail-visible 同型 |
| T20 | 客户端超时与权限 | 桩 2s/3s 超时配置；三域内部端点各自集成测试（含无权限 403——内部端点沿注解式） | 范式一致性 |
| T21 | 迁移结构 + ArchUnit | V2 三表机器断言；只读铁律断言扩展 | B5-3 / B8-3 |
| T22 | 既有零回归 | evidence 42 例 + space/contract/delivery 全量基线零回归 | 关闭条件② |

## §9 剧本 C-8.2 V1.2 映射与诚实边界

- **操作路径落定（V1.2 修订范围）**：界面入口行改为"审计界面归 3.8.5；本剧本以服务接口承载演示（操作路径表随 V1.2 附：E1~E5 端点 + 请求示例 + 身份口径：监管档 = 治理角色身份，普通主体 = 无治理角色身份；直连 8090 + 身份头示例为主，沿 C-7.2 V1.1 修订先例）"；"操作路径说明（占位）"段替换为实测端点表；**S1-1~S2-8 步骤编号与业务判定一字不改**；提示 PO 批准。
- **S2-7 双路**：界面无入口 = 前端零审计入口（实测留痕）；接口直调被拒 = E4/E5 无"改/删"语义 + 仓储只插不改。
- **S2-8 摘录文本**：三句逐字沿规格 B8（留存 ≥6 个月目标、G-07 待生效、自建链不承诺对外出证）——交付说明同步附。

## §10 诚实边界（交付说明与剧本摘录共用口径）

1. **跨域降级**：跨域留痕不可达时时间线显式标 `UNAVAILABLE`，不伪装连续；调用侧无补偿队列（沿 3.8.3 跟踪项同型登记）。
2. **JSONL 审计事件不在调阅面**（Q3-A；G-07 待生效）；留存 ≥6 个月为"待生效"目标（B8-2）。
3. **自建链公信力**：不以存证单独承担对外出证责任（ADR-004 §4 风险①；V1.5 增强）。
4. **参与方读面不开放**（Q5-A，跟踪项）；**PDF 不做**（Q6-A 评估结论留痕）。
5. **新旧"不存在"口径并存**：3.8.2 族 404+1012C0001 / 审计族 400+1000C0003（同码同文防枚举），差异与理由在交付说明留痕。
6. **导出上限 10000 条**为 V1.0 常量（`EvidenceAuditLimits`），不配置化。
