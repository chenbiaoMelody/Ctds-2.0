# WBS-3.8.4 监管审计视图 · 低保真设计（方向确认稿）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-11-07 编排师会话回复"全部按建议确认"：Q1~Q10 均采建议口径 A + D1 不拆分 = 方向定稿）**；V0.9 = 立卡草案 |
| 任务卡 | `docs/tasks/WBS-3.8.4-监管审计视图-2026-11-07.md` |
| 规格 | `docs/specs/C-8.1-8.2-存证与审计.md` V1.1 行为 B5~B8（C-8.2 合规审计） |
| 剧本 | `docs/manuals/C-8.2-合规审计-验收剧本.md` V1.1（本卡修订为 V1.2：操作路径落定，业务判定一字不改） |
| 性质 | 非界面类两级设计（章程 2.6.3）：业务可读行为清单 + 接口契约表 + 边界值与异常行为；免 HTML 原型；界面归 3.8.5 |

## §1 做什么（W）与不做什么（N）

**做（W1~W8）**：

| # | 交付 | 一句话口径 |
| --- | --- | --- |
| W1 | 四环节存证总览端点 | `GET /api/v1/evidence/audit/overview`：四环节 × 状态（待上链/已上链/上链失败）计数矩阵 + 异常项计数（含核验不一致）+ 快照时点；支持可选时间窗（≤90 天） |
| W2 | 异常项清单端点 | `GET /api/v1/evidence/audit/anomalies`：待上链 / 上链失败 / **核验不一致**三类清单（分页）；核验不一致 = 每条存证最新 VERIFIED 留痕结论为 MISMATCH 的跨笔查询 |
| W3 | 全链时间线与日志调阅端点 | `GET /api/v1/evidence/audit/trails`：≥1 条件（业务单据号 / 主体 DID 摘要 / 环节 / 时间窗）；单据号精确命中 = **单笔全链时间线**（存证事件 + evidence 留痕 + 跨域业务留痕，统一脱敏）；其余条件 = 存证清单模式（分页，不内联跨域）；每次成功调阅落 1 条调阅留痕 |
| W4 | 审计报告生成端点 | `POST /api/v1/evidence/audit/reports`：按条件（≥1）生成报告——报告编号唯一、范围口径、存证清单、核验结论汇总、异常项清单、SM3 内容摘要随行登记；重复生成新编号不覆盖 |
| W5 | 审计报告下载端点 | `GET /api/v1/evidence/audit/reports/{reportNo}/export?format=csv|json`：按登记快照输出；**下载前重算 SM3 与登记比对**（不一致 = 1012S0003 显式报错）；导出动作留痕 |
| W6 | 跨域内部只读端点 4 个 + 客户端 3 个 | space 1（准入 / 策略留痕两类锚点）、contract 2 路径（合约〔attestation+留痕〕/ 放行留痕）、delivery 1（交付 + 留痕）；evidence 侧 3 个 JDK HttpClient 客户端（connect 2s / read 3s）+ 三服务角色映射行（delivery 新增权限点 `delivery.internal.read`） |
| W7 | evidence V2 迁移 3 表 + 新错误码 | `evidence_access_log`（调阅/导出留痕）、`evidence_audit_report`（报告登记，只插不改）、`evidence_report_no_seq`（取号）；新码 **1012S0003**（报告完整性校验未通过，1012 段留白内按序启用） |
| W8 | 剧本 C-8.2 V1.2 + 文档批 | 操作路径与端点表落定（界面归 3.8.5 不变；业务判定与步骤编号一字不改，提示 PO 批准）+ 台账 / 开发日志 / 体量登记 / 业务可读交付说明 |

**不做（N1~N9）**：

| # | 不做 | 归属 / 理由 |
| --- | --- | --- |
| N1 | 审计查询页面 / 存证核验工具界面 | 3.8.5（WBS 行 330） |
| N2 | 参与方（需方/供方）本方证据读面 | Q5-A 评估结论：V1.0 不开放，登记跟踪项 |
| N3 | PDF / 报表模板导出 | Q6-A 评估结论：不引入 PDF 库（ADR-001 冻结 + 零新依赖） |
| N4 | JSONL 结构化审计事件调阅 | Q3-A：集中日志未建（G-07），调阅面仅覆盖存证事件 + 各域业务留痕，能力标"待生效" |
| N5 | 3.8.2 既有 5 端点契约改动 / 内部写面改动 / 网关 / k8s / 前端改动 | 加性读面，零触碰（网关同前缀自动覆盖） |
| N6 | 集中日志平台与留存期限配置 | G-07（部署期立项），留存 ≥6 个月标"待生效" |
| N7 | 外部监管机构账号 / 三员分立 | 3.9.2（WBS 行 338）；V1.0 监管视角 = 运营方治理档（规格 Q6-A） |
| N8 | 存证/留痕/报告的修改、删除、补录、重放入口 | B5-3 / B8-3 只读铁律；仓储端口无写法 |
| N9 | 自建密码学 / 新第三方依赖 / 跨链互认 | SM3 走 `common`（ADR-006）；ADR-001 冻结；非目标 |

## §2 结构组成

```
evidence-service（新增"监管审计读面"垂直切片，全部 /api/v1/evidence/audit/ 前缀）
├─ interfaces
│   ├─ EvidenceAuditController          ← 5 端点（overview / anomalies / trails / reports / export）
│   └─ EvidenceAuditExceptionHandler   ← 沿 EvidenceExceptionHandler 范式扩展（1012S0003 等）
├─ application
│   ├─ EvidenceAuditAccessGuard        ← 服务内守卫：evidence.governance 校验；失败 = 400+1000C0003 同码同文（Q4-A）
│   ├─ EvidenceAuditOverviewService    ← W1/W2（矩阵聚合 + 异常清单，含 MISMATCH 跨笔查询）
│   ├─ EvidenceTrailService            ← W3（businessNo 前缀反解 → 跨域编排 → 脱敏聚合 → 调阅留痕）
│   ├─ EvidenceReportService           ← W4/W5（快照登记 / CSV·JSON 渲染 / 摘要重算核验 / 导出留痕）
│   └─ view records（OverviewMatrix / AnomalyItem / TrailNode / ReportView / AccessLogView …）
├─ domain
│   ├─ EvidenceBusinessNoRouter        ← 五类前缀反解规则（SPACE{}:ADMISSION{} / SPACE{}:POLICY-AL{} / CO / DL / USG）
│   ├─ AuditTrailPort                  ← 跨域留痕端口（space / contract / delivery 三实现客户端）
│   └─ AccessLogRepository / AuditReportRepository 端口（只插不改；报告只读）
├─ infrastructure
│   ├─ SpaceTrailClient / ContractTrailClient / DeliveryTrailClient   ← JDK HttpClient（2s/3s，X-Ctds-Subject/Roles: evidence-internal）
│   └─ JdbcAccessLogRepository / JdbcAuditReportRepository / JdbcReportNoGenerator
└─ db/migration  V2__create_evidence_audit_tables.sql  ← 3 表（access_log / audit_report / report_no_seq）

space-service / contract-service / delivery-service（各新增 1 个内部只读端点 + 角色映射行）
└─ XxxInternalAuditController（@RequirePermission(xxx.internal.read)，按锚点返回留痕白名单字段）
```

## §3 主要流程（业务语言）

1. **总览流（S1-1）**：治理档打开审计视图 → `overview` → 返回四环节计数矩阵 + 异常计数 + 快照时点 → 记录为基线。
2. **单笔调阅流（S1-2 / S1-4）**：按单据号检索 → 守卫校验（非监管 = 400+1000C0003 同码同文）→ 命中存证事件 → 前缀反解（如 `CO000123` → 合约域）→ 调对应域 audit-trail 只读端点 → 聚合层把各域留痕与 evidence 留痕按时间排序、DID/主体号摘要化 → 返回连续时间线 + 落 1 条调阅留痕；**跨域不可达 = 时间线照常返回，但该段显式标 `UNAVAILABLE` + WARN**（不伪装连续、不静默）。
3. **条件调阅流（S1-3 / S1-5）**：主体/环节/时间窗过滤 → 存证清单模式（分页，提示"按单据号展开全链时间线"）；**无条件 / 最宽时间窗 >90 天 → 拒（1012C0002，沿 3.8.2 同口径）**。
4. **报告生成流（S2-1 ~ S2-3）**：按条件生成 → 校验（≥1 条件、窗 ≤90 天、清单 ≤10000 条）→ 查询快照（存证清单 + 核验结论汇总 + 异常项清单）→ 取报告编号（`EV-RPT-yyyymmdd-xxxx`）→ 内容 JSON + SM3 摘要 + 范围口径落 `evidence_audit_report`（只插不改）→ 返回报告要素；同条件再生成 = 新编号，不覆盖。
5. **报告下载流（S2-4）**：按报告编号下载 → 重算内容 SM3 与登记比对（不一致 = 1012S0003 显式报错，不输出文件）→ 渲染 CSV / JSON → 落 1 条导出留痕。
6. **越权拒绝流（S1-7 / S2-5）**：非治理档角色访问任意审计端点 → 守卫拒 → **400 + 1000C0003 + 与"对象不存在"同码同文**；先以监管角色检索不存在单据号取得参照提示，两提示逐字一致（防枚举）。

## §4 决策点全文（Q1~Q10 + D1，**已全部确认采建议口径 A + D1 不拆分**）

> 与任务卡 §二 摘要表一一对应；此处为业务影响全文。

- **Q1 时间线跨域留痕组织**。业务事实：四环节留痕分属 4 个库，evidence 库查不到。A（建议）：evidence 唯一检索面 + 4 个新增域内只读 audit-trail 端点 + 3 个客户端聚合，出站统一脱敏——满足"单笔全链时间线"单端点语义与 B6-1"时间线连续"；代价 = 跨 4 服务的加性端点（约 470 行）。B：只展示存证侧——B5-2③/B6-1 验收标准直接不满足，剧本 S1-2/S1-4 无法通过。C：调用方（界面/剧本）拼装——各域既有留痕端点无统一单号/时间窗口径，且治理查看会触发 GOVERNANCE_VIEW 写留痕副作用（调阅动作反被污染），无统一调阅面与留痕口径。
- **Q2 主体过滤口径**。A（建议）：DID 摘要等值——存证六要素唯一口径、零跨服务、DID 换代不漏历史；剧本 S1-3 操作路径注明"主体 = DID 摘要"。B：主体编号换算当前 DID——需跨服务且换代后历史记录查不到（语义错误）。C：双口径——扩面非最小。
- **Q3 JSONL 审计事件调阅**。A（建议）：V1.0 不纳入，登记 G-07"待生效"（沿 B8-2 诚实条款同款）；B6-1 验收标准由存证事件 + 各域留痕全承载。B：文件检索——路径耦合、多主机语义缺失、无索引全扫，且与"只读既有留痕、不建第二事实源"边界模糊。
- **Q4 越权出站口径**。A（建议）：审计读面族服务内守卫，失败 = 400 + 1000C0003 + 同文案（ADR-016 §2.6 与 subject-service `OwnershipGuard` 先例同型；"功能级无权限 403 前置"沿旧——3.8.2 既有 5 端点口径零改动）；剧本 S1-7"同码同文"逐字满足。B：@RequirePermission 403——与剧本判定冲突，须改剧本 = 变更流程。
- **Q5 参与方本方读面**。A（建议）：V1.0 不开放 + 跟踪项（承接 = 3.8.5 评估或另立卡）；`application.yml:28` 预留钩子按评估结论回填。B：本卡开放——归属过滤 + 脱敏子集显著扩面，非最小实现。
- **Q6 PDF 评估结论**。A（建议）：不做——PDF 库 = 未审批新依赖，违反 ADR-001 冻结；CSV/JSON 已满足 B7 全部验收标准（规格 Q8 已裁）。B：引入 PDF 库——不采。
- **Q7 报告不可篡改承载**。A（建议）：新表登记（编号唯一 + 内容快照 + SM3 摘要 + 下载重算 + 只插不改）；B：现生成现返回——不满足 B7-2"摘要随报告登记，可后续核验""重复生成不覆盖、可追溯"。
- **Q8 调阅/导出留痕落库**。A（建议）：新表 `evidence_access_log`（两类 kind）——剧本 S1-6 / S2-4 可查库核对；B：JSONL——不可查询，判定无从核对。
- **Q9 剧本修订**。A（建议）：V1.2——界面入口行 + 操作路径说明落定，业务判定与步骤编号一字不改，提示 PO 批准（沿 C-8.1 V1.3 先例）；B：不改——S1/S2 操作路径占位无法逐步执行。
- **Q10 上限取值**。A（建议）：全沿 3.8.2 既有值（≥1 条件 / 窗 ≤90 天 / 页 ≤100 / 拒 1012C0002）+ 报告清单上限 10000 条；B：另设新值——无业务理由。
- **D1 体量/拆分**。不拆分：预估 2200~2700 行（含测试），1 编码会话；总览/调阅/报告是 C-8.2 剧本一次跑通的同一验收链，拆则"有视图无报告"中间态不可验收；超 400 行按服务包先例登记制豁免（沿 3.8.3）。

## §5 体量预估

| 面 | 预估（行） |
| --- | --- |
| evidence 主源（controller / 守卫 / 3 服务 / 路由 / 3 客户端 / 仓储扩展 / 视图 / yml） | ~1100 |
| space / contract / delivery 内部端点（含各自测试） | ~370 + ~260 |
| V2 迁移（3 表） | ~75 |
| evidence 测试（Testcontainers 集成为主，T1~T22） | ~900 |
| **合计** | **~2200~2700（1 编码会话；D1 不拆分 + 登记制豁免）** |
