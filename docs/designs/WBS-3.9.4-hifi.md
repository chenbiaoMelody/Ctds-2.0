# WBS-3.9.4 高保真设计：监控运维集成 — V1.0（✅ 2026-11-14 一次确认生效 = 编码契约）

- 来源：lofi V1.0（同批确认）+ 任务卡 `docs/tasks/WBS-3.9.4-监控运维集成-2026-11-14.md` §二 Q1~Q8 + D1；规格 V1.2 B5~B8（`:117-171`）+ 非目标 5（`:179`）+ 边界 8（`:213`）；剧本 V1.1 S1/S2；ADR-014 :34-36/:43-44/:46/:54/:64-68/:90
- 性质：✅ **2026-11-14 编排师一次确认**（会话回复"全部按建议确认" = Q1~Q8 均采建议口径 A + D1 不拆分）；**V1.0 = 编码契约**，实现与本文不一致 = 打回项；实测不符时按 §9 E-勘误位回填（纯勘误，不补需求）

## §1 组件与文件落点

| 组件 | 模块 / 文件（新增除注明外） | 职责 |
| --- | --- | --- |
| 指标接入（12 服务，范围随 Q2） | 各服务 `pom.xml`（actuator + micrometer-registry-prometheus 两依赖）+ `application.yml`（`management.endpoints.web.exposure.include: health,prometheus`） | did/kms/subject 半接入补齐（加 micrometer + exposure 改写）；space/catalog/contract/delivery/connector/metering-billing/evidence/gateway 全量接入；**example 零改动**（唯一样板） |
| 抓取注解 | `deploy/k8s/` 七组 deployment（space/catalog/contract/delivery/metering-billing/evidence/gateway）Pod 注解三条（`prometheus.io/scrape`/`port`/`path`，沿 backend-deployment L29-31） | kubernetes_sd 注解发现，**零 Prometheus 配置改动**（ADR-014 :36）；backend 已有；subject/connector/did/kms 集群外口径 runbook 注明 |
| Prometheus 客户端 | `evidence-service` `infrastructure/monitoring/PrometheusClient`（JDK HttpClient，connect 2s/read 3s 沿 `InternalTrailClientSupport` 常量） | `/api/v1/query` `/api/v1/rules` `/api/v1/alerts` 封装；BASEURL 空/不可达 → 抛数据源不可用异常（fail-closed） |
| Alertmanager 客户端 | `evidence-service` `infrastructure/monitoring/AlertmanagerClient`（同款 HttpClient） | `POST /api/v2/silences`（处置静默链路唯一依赖；列表读面**不依赖**本客户端） |
| 监控配置属性 | `evidence-service` `MonitorProperties`（前缀 `ctds.evidence.monitor`） | 见 §3 配置键表 |
| 监控读数与告警列表 | `evidence-service` `interfaces/PlatformMonitorController`（`GET /api/v1/platform/metrics` + `GET /api/v1/platform/alerts`，挂 `@RequirePermission("platform.monitor.read")`） | 契约见 §2；沿 `DomainGovernanceController` 惯例 |
| 运维处置 | 同 controller `POST /api/v1/platform/alerts/actions`（挂 `@RequirePermission("platform.alert.handle")`） | 契约见 §2；处置通道唯一性入口（B8-R1） |
| 读数组装 | `evidence-service` `application/MonitorQueryService` | 逐服务四区块查询组装 + 未接入判定（无 series → NOT_INTEGRATED） |
| 告警映射 | `evidence-service` `application/AlertQueryService` | rules 态 → 告警项七要素 + 状态映射（Q4-A）+ 阈值登记映射（§3） |
| 处置服务 | `evidence-service` `application/AlertDisposeService` | 三动作分发（静默 → AlertmanagerClient；确认/升级 → 留痕型）+ `AuditRecorder` 事件 `alert.dispose` + 处置对象封闭校验 |
| 错误码 | `evidence-service` `domain/MonitorErrorCodes` | `1013C0005` ALERT_NOT_DEFINED / `1013S0003` MONITORING_SOURCE_UNAVAILABLE（+ MESSAGE 常量成对，沿 `RbacErrorCodes` 写法） |
| 错误码映射 | `evidence-service` `EvidenceExceptionHandler` 增映射 | `1013S0003` → 503（沿 `1013S0002`→503 先例） |
| V5 迁移 | `evidence-service` `db/migration/V5__grant_monitor_permissions.sql` | `platform.monitor.read` + `platform.alert.handle` 授 `platform.sysadmin` + `role_permission_change_log` 留痕 ×2（沿 V4 写法，列清单以 P3 实测为准） |
| 连接器三态 | `connector-service` `ConnectorQueryService` 增 `healthStatus()` 派生 + `ConnectorView` 加性字段 `healthStatus` | 三态显式（NEVER_REPORTED/OFFLINE/ONLINE）；既有 `online` 布尔**零破坏** |
| runbook | `deploy/runbook.md` §3.3 监控运维操作段（沿 §3.1/§3.2 标题惯例） | 读面访问与注入（BASEURL env + 端口转发 19090/19093）、告警演练引用 monitoring.ps1 S6、处置演示口径与留痕核对、集群外服务口径 |
| 剧本回填 | `docs/manuals/C-10.2-监控运维-验收剧本.md` V1.1 → V1.2 | 操作路径说明表回填 + 陈旧事实勘正 2 处（见 §7，业务判定与步骤编号零变更，**须 PO 批准**） |

**不改**：判定内核（`DefaultAccessControl`/`PermissionInterceptor`/`JdbcRolePermissionMapper`）与 V1~V4 迁移零改动；`AuditOutcome` 枚举不加值（沿 3.9.3 E-2 先例 outcome=SUCCESS）；前端零改动；门禁配置零改动；监控栈部署面零改动（`scripts/deploy/monitoring.ps1` 与 `deploy/k8s-monitoring/` 九清单不碰——含规则文件，两规则封闭）；example-service 接入样板零改动。

## §2 接口契约表

### GET /api/v1/platform/metrics（监控读数读面）

| 项 | 契约 |
| --- | --- |
| 权限 | `@RequirePermission("platform.monitor.read")`（仅 `platform.sysadmin`，V5 授予）；未认证 401 + `1000C0002`；无权限 403 + `1000C0005`（B3-2 同码同文） |
| 请求 | 无参数、无请求体（只读） |
| 成功响应 | 沿既有 `ApiResult` 包装；`data` = `services` 数组，每行 = `service`（服务标识）/ `job`（Prometheus job 标签值）/ `integrated`（`INTEGRATED`｜`NOT_INTEGRATED`）/ 四区块字段：`alive`（UP/DOWN，未接入 = null）/ `heapUsedBytes` + `heapMaxBytes` + `gcPauseSecondsRate5m`（JVM 堆内存与 GC）/ `httpRatePerSec1m`（HTTP 速率）/ `errorRate5xx5m` + `p95LatencyMs5m`（5xx 错误率与 P95 时延）/ `note`（未接入行 = "未接入指标采集，读数不可用"——**显式文字标注，值恒 null 不显示 0**） |
| 数据源 | Prometheus `/api/v1/query`（四区块 PromQL 以看板/ADR-014 §3.5 表达式为准，指标名 P5 实测回填）；未接入判定 = `up{job="<job>"}` 无 series → `NOT_INTEGRATED`；up==0 → `alive=DOWN`（已接入但不可达，其余读数如实呈现） |
| 抽查一致（B5-2） | 读面与 `/actuator/prometheus` 同源（服务 → Prometheus 抓取 → 读面查询），同源构造保证一致；抓取间隔 15s 内差异由窗口口径说明承载 |
| 错误 | `1013S0003` + 503：BASEURL 未配置或 Prometheus 不可达（fail-closed，不伪造空数据/零值） |

### GET /api/v1/platform/alerts（告警列表读面）

| 项 | 契约 |
| --- | --- |
| 权限 | 同上（`platform.monitor.read`） |
| 成功响应 | `data` = `alerts` 数组（**封闭两规则固定入列，规则即告警项**）：每项 = `alertName` / `severity`（critical·warning）/ `target`（job 标签，对象）/ `triggeredAt`（活跃实例 `activeAt`，未触发 = null）/ `currentValue`（规则表达式实时查询值）/ `threshold`（登记映射文案，§3）/ `state`（`firing` 已触发｜`resolved` 已恢复）+ `stateNote`（raw state 透传：pending = "条件满足等待窗口，未正式触发"） |
| 状态映射（B7-R1 落定） | Prometheus `/api/v1/rules` 规则态为权威：`firing` → `firing`；`pending`/`inactive` → `resolved`（未触发）；`triggeredAt` = `/api/v1/alerts` 活跃实例 `activeAt` |
| 错误 | `1013S0003` + 503（数据源不可用）；列表读面**不依赖 Alertmanager 存活**（Q4-A） |

### POST /api/v1/platform/alerts/actions（运维处置，唯一鉴权 + 留痕入口）

| 项 | 契约 |
| --- | --- |
| 权限 | `@RequirePermission("platform.alert.handle")`（仅 `platform.sysadmin`，V5 授予）；未认证 401 + `1000C0002`；无权限 403 + `1000C0005`（B8-3 双向：secadmin/audadmin/admin/业务角色均被拒——均不持该点） |
| 请求体 | `alertName`（封闭两规则名，未知名 → 400 + `1013C0005`）/ `action`（`ACKNOWLEDGE` 确认｜`SILENCE` 静默｜`ESCALATE` 升级）/ `note`（处置结论说明，可选）/ `durationMinutes`（仅静默，可选，缺省 30，上限 1440，越界 400 + `1013C0001` 复用不新开码） |
| 行为 | `SILENCE` = AlertmanagerClient → `POST /api/v2/silences`（matcher = alertname，startsAt = now，endsAt = now+duration）；`ACKNOWLEDGE`/`ESCALATE` = 平台留痕型（不调告警引擎、不改变状态）；三动作**均**落审计事件 |
| 成功响应 | `alertName` / `action` / `result=OK` / `silenceId`（仅静默）/ `recordedAt`；处置留痕 = 审计事件 `alert.dispose`：actor = 处置人（`X-Ctds-Subject` 头）/ eventTime = 时点（审计载体自带）/ targetId = 告警名 / detail = 动作·结论（note）·静默时长·silenceId（四要素：处置人/时点/对象/结论） |
| 诚实边界 | 确认/升级不改变告警引擎状态；升级的实际通知效果 = "待生效"（v1 路由空 + 外发未接 G-04，B8-R1 原文兼容，剧本 S2-6"任一可行者"） |
| 错误 | `1013C0005` + 400（对象未定义）；`1013S0003` + 503（Alertmanager 不可达且动作 = 静默）；留痕载体缺位（`ctds.audit.enabled=false`）→ 处置照常执行 + 显式 WARN"留痕载体缺位"（沿 3.9.3 E-7 口径：不阻断 + 可观测，交付说明如实登记） |

## §3 配置键表与登记映射

| 键 | 载体 / 缺省 | 说明 |
| --- | --- | --- |
| `ctds.evidence.monitor.prometheus.base-url` | evidence `application.yml`；env `CTDS_EVIDENCE_PROMETHEUS_BASEURL`；**缺省空** | Prometheus API 基址（演示链经 monitoring.ps1 端口转发 19090）；空 = 数据源未配置 → 503 + `1013S0003` |
| `ctds.evidence.monitor.alertmanager.base-url` | 同上；env `CTDS_EVIDENCE_ALERTMANAGER_BASEURL`；**缺省空** | Alertmanager API 基址（端口转发 19093）；仅处置静默链路消费 |
| `ctds.evidence.monitor.service-jobs` | evidence `application.yml` 缺省 12 项映射（Q2-A；B 案 = 11 项） | `服务标识:job 标签值` 逗号分隔（如 `example:ctds-backend,space:ctds-space-service,subject:ctds-subject-service,…`，全集以 P1 实测 deployment 标签为准） |
| `ctds.evidence.monitor.silence-default-minutes` | 缺省 30 | 静默缺省时长；请求可覆盖，上限 1440 |

- **阈值登记映射**（封闭两规则，Q4-A 承载；P5 以规则文件原文核对）：`CtdsBackendDown` → 阈值文案"up == 0（服务不可达/副本下线）"，当前值 = up 值；`CtdsBackendHighErrorRate` → "5xx 占比 > 5%（5m 窗口）"，当前值 = 5m 窗口 5xx 占比。
- **容量类阈值取值落定登记**（Q5-A 承载，B7-R4"归 3.9.4 落定"；**未接告警规则 = 待生效如实标注**）：① 连接器规模：在册连接器数 > 500（warning；来源 = connector 在册计数，业务库数据非 Prometheus 指标）；② 调用量：业务服务聚合 HTTP 速率 > 1000 req/s 持续 5m（warning；来源 = `http_server_requests_seconds_count` 聚合，接入后指标已采集）；③ 存储水位：主库磁盘使用率 > 85%（warning；来源 = 集群/主机层指标——v1 不含集群层指标 ADR-014 :66，指标未采集 + 规则未建双待生效）。三项取值仅供未来扩规则时按届时任务卡引用，本卡不实现。
- k8s/演示注入沿 `CTDS_<服务>_<目标>_BASEURL` env 惯例；本卡**不预注入**任何 k8s 清单 env 改动（读面 BASEURL 由演示/部署环境注入，runbook §3.3 承载）。

## §4 边界值与异常行为

| # | 场景 | 行为 |
| --- | --- | --- |
| 1 | Prometheus BASEURL 未配置 / 不可达 | metrics 与 alerts 读面 503 + `1013S0003`（fail-closed，不伪造空读数/零值） |
| 2 | 服务 `up` series 不存在 | 该行 `integrated=NOT_INTEGRATED` + note 显式"未接入"（值 null，不显示 0/绿，B5-4） |
| 3 | 服务 `up==0`（series 存在） | `integrated=INTEGRATED` + `alive=DOWN`（已接入但不可达；其余读数如实呈现最后采样） |
| 4 | 规则态 pending | `state=resolved` + `stateNote="条件满足等待窗口，未正式触发"`（B7-1 状态二值不破，raw 透传诚实） |
| 5 | 规则态 firing / inactive | `state=firing`（含 activeAt）/ `state=resolved`（triggeredAt=null） |
| 6 | 处置对象非两规则名 | 400 + `1013C0005`（"告警对象未定义"） |
| 7 | 静默 durationMinutes <1 或 >1440 | 400 + `1013C0001`（复用 PARAM_INVALID，不新开码） |
| 8 | 处置时 Alertmanager 不可达（SILENCE） | 503 + `1013S0003`；不落"OK"留痕（处置未成功不留痕成功结论） |
| 9 | AuditRecorder 缺位（`ctds.audit.enabled=false`） | 处置照常执行 + 显式 WARN"留痕载体缺位"（沿 3.9.3 E-7：不阻断 + 可观测） |
| 10 | 无权限访问读面 / 处置 | 403 + `1000C0005` 同码同文；未认证 401 + `1000C0002` |
| 11 | 连接器 lastHeartbeatAt == null | `healthStatus=NEVER_REPORTED`（后端显式，不再依赖前端「—」空值） |
| 12 | 连接器心跳超阈值 / 阈值内 | `OFFLINE` / `ONLINE`；`online` 布尔既有语义零变化（加性回归断言） |
| 13 | 全仓结构 | metrics/alerts 读面仅 GET；全卡零 DELETE 映射；告警规则文件零改动（T3/T3b/规则封闭守卫） |

## §5 测试清单（先红后绿；映射任务卡 §三）

| 用例 | 断言要点 | 红/**绿** |
| --- | --- | --- |
| T1 接入结构断言 | 12 服务 pom 两依赖 + exposure 精确值 `health,prometheus` + 无其他端点开放（扫描式测试） | 红（未接入）→ 绿 |
| T2 读数正向 | sysadmin 读四区块齐备（stub Prometheus：存活/堆内存与GC/速率/5xx+P95）；同源口径断言 | 红（端点不存在）→ 绿 |
| T3 指标只读结构断言 | metrics/alerts 读面无写方法；全仓无指标写入端点 | 红（端点不存在）→ 绿（回归护栏） |
| T3b 无删除结构断言 | 全卡零 DELETE 映射；监控/告警/留痕无删除路径 | 保持绿（回归护栏） |
| T4 未接入标注 | stub 返回无该服务 series → `NOT_INTEGRATED` + note 显式标注 + 值 null | 红 → 绿 |
| T5 三态派生 | connector `healthStatus` 三值 + 既有 `online` 布尔零变化回归 | 红（字段不存在）→ 绿 |
| T6 阈值派生翻转 | 改 `offline-after` 属性注入 → 同一心跳数据派生翻转；还原复验（双向） | 红 → 绿 |
| T7 健康读面负向 | 无 `connector.health.read` 403+`1000C0005`（既有用例回归 + 新面同口径） | 红 → 绿 |
| T8 告警要素 | stub rules/alerts → 七要素齐备 + 阈值登记映射文案精确 | 红 → 绿 |
| T9 状态映射双向 | firing→已触发（activeAt 非空）/ inactive→已恢复（triggeredAt null）/ pending→未触发 raw 透传 | 红 → 绿 |
| T10 处置三动作 | 确认/升级留痕型（`alert.dispose` 事件四要素断言）+ 静默 stub Alertmanager 返回 silenceId + 响应回显；未知对象 400+`1013C0005`；时长越界 400+`1013C0001` | 红 → 绿 |
| T11 处置写面双向 | sysadmin 成功 / secadmin+audadmin+admin+user 403+`1000C0005` 同码同文 | 红 → 绿 |
| T12 护栏扩展 | `PermissionPointRegistryTest` 纳入 V5（seed 并集 :79）；两端点注解值 ⊆ seed | 红（V5 未落）→ 绿 |

覆盖要求：行覆盖 overall ≥70%（evidence/connector/gateway 非 coreModules）；禁止空洞断言与仅 happy-path（T4/T7/T9/T11 负向腿 + T3/T3b 结构护栏齐备）。

## §6 前置检查（编码段第一动作，P1~P8 逐项实测留痕 `build-output/w394-precheck-*.md`，gitignored）

| # | 检查项 | 不成立则 |
| --- | --- | --- |
| P1 | 12 服务 pom/yml 接入点精确盘点（did/kms/subject 半接入行；8 服务插入位；example 样板行）；`deploy/k8s` 全部 deployment 的 `app.kubernetes.io/name` 标签值全集（service-jobs 缺省清单依据） | 停机上报告 |
| P2 | `ApiResult` 成功/错误字段名实读；gateway reactive 栈 actuator+micrometer 行为实测（Q2-A 落地前提；起 gateway curl `/actuator/prometheus`） | 停机上报告 |
| P3 | V4 迁移与 `role_permission_change_log` 精确列名/事务写法（V5 编写依据） | 停机上报告 |
| P4 | `PermissionPointRegistryTest` seed 并集扩展点（:79 List 加 V5）；新注解值进主源码扫描面确认；`RbacThreeAdminMatrixTest` 对 V5 新点零涉及确认 | 停机上报告 |
| P5 | Prometheus API 形状实测（`/api/v1/rules` `/api/v1/alerts` `/api/v1/query` 响应字段名）；看板四区块 PromQL 原文提取（grafana-config.json）；micrometer 指标名实测（起 example curl `/actuator/prometheus`：`jvm_memory_used_bytes` / `jvm_gc_pause_seconds_sum` / `http_server_requests_seconds_count` / `http_server_requests_seconds_bucket`） | 停机上报告 |
| P6 | `EvidenceExceptionHandler` 503 映射扩展点；`AuditEvent` 字段与 detail 限制（20 键/512 字符——处置 detail 键数预算）；`AuditOutcome` 沿 E-2 先例 outcome=SUCCESS | 停机上报告 |
| P7 | Alertmanager `/api/v2/silences` 请求体形状核对（v0.34 官方文档，不实跑真调）；演示链注入方式（env + monitoring.ps1 端口转发 19090/19093）；读面网关可达确认（connector-platform 路由覆盖 `/api/v1/platform/**`） | 停机上报告 |
| P8 | 工作树干净 / 端口占用 / Docker 可用（Testcontainers 实跑） | 停机上报告 |

## §7 剧本回填草案（V1.1 → V1.2，业务判定与步骤编号零变更；须 PO 批准）

1. **操作路径说明表回填**（5 行）：S1-1 补对照载体 = `GET /api/v1/platform/metrics`（`platform.monitor.read` = 系统管理员）；S1-3 = 同端点未接入标注行；S2-1 = `GET /api/v1/platform/alerts`；S2-3~S2-5 = Prometheus 权威源（读面 + `/api/v1/rules`；演练引用 monitoring.ps1 S6）；S2-6 = `POST /api/v1/platform/alerts/actions`（处置人 = `X-Ctds-Subject` 头；留痕 = 审计 JSONL `alert.dispose` + 响应回显）；S2-7 = `platform.alert.handle` 双向口径。
2. **陈旧事实勘正**（2 处，立卡盘点实测）：前提⑤"立卡时点仅 example-service 暴露 `/actuator/prometheus`，11 个业务服务未接入指标（规格 §7 Q5-A）"→ 更新为交付后事实（12 服务接入在库，采集状态以读面实测为准）；S1-3 判定后半句"与立卡事实一致（11 个业务服务未接入，规格 §7 Q5-A）"→"与监控读面'未接入'标注一致（未接入项不以 0 或绿呈现）"。**业务判定（B5-4 语义）与步骤编号零变更**（沿 3.9.3 Q7 勘正先例）。
3. **维护说明**补 V1.2 段（沿 V1.0/V1.1 段落惯例）。

## §8 规格码值登记（边界 8 义务，本卡执行）

- 权限点：**`platform.monitor.read`**（监控读数只读）+ **`platform.alert.handle`**（运维处置写面），均授 `platform.sysadmin`——本任务卡登记 + V5 seed + change_log 留痕 + §5 T12 护栏；
- 错误码：**`1013C0005`**（ALERT_NOT_DEFINED）/ **`1013S0003`**（MONITORING_SOURCE_UNAVAILABLE，503）——本任务卡登记 + `MonitorErrorCodes` 常量类 + ExceptionHandler 映射；
- 规格 B7-R1"状态映射归 3.9.4 落定"/ B7-R4"容量类阈值取值归 3.9.4 落定"/ 边界 8"码值随实施包落定并登记"由此闭环；规格文本不改（1013 段预留语义已覆盖）。

## §9 E-勘误位（前置检查回填区，V1.0 生效后仅纯勘误登记）

| 编号 | 勘误内容 | 性质（纯勘误 / 裁定呈报） |
| --- | --- | --- |
| E-1 | P2 实测：gateway 自有鉴权链 `ctds.auth.gateway.permit-paths` 原为 `[]`（3.5.2 时代注释"本卡不引入 actuator"）拦截 `/actuator/**` → 401，与 ADR-014 :35"指标端点禁新增鉴权"及 11 个 servlet 服务的 `/actuator/**` 缺省豁免不一致。修复 = permit-paths 注入 `"/actuator/**"`（gateway application.yml 配置 + 过时注释更新；Q2-A 接入口径内，方向 = 解除对指标端点的既有拦截，非新增鉴权面） | 纯勘误（配置补齐） |
| E-2 | 既有守卫测试两处随已确认设计扩展：① `DomainGovernanceContractTest` platformRequestMapping 断言（"/api/v1/platform 命名空间唯一持有者" → 封闭两持有者集合 = DomainGovernanceController + PlatformMonitorController，Q1-A 第二读面落点）；② 同文件 noOnlineLifecycleWriteEndpointsInAnyMainSource 对 `PlatformMonitorController.java` 单文件豁免（规格 B8-R1 点名的处置写端点非部署域生命周期动作；其余文件写端点禁令不变）。守卫语义保持封闭集断言（强度不降） | 纯勘误（守卫扩展留痕） |
| E-3 | 读数数值口径细化（P5 真机实测）：① heapMaxBytes = `max(jvm_memory_max_bytes{area="heap"})` 且数据源未定义值（G1 未定义区 = -1）→ null（不显示负值）；② errorRate5xx5m / p95LatencyMs5m 在无流量 / 无样本（0/0 = NaN）→ null——"不显示 0"精神（B5-4）在已接入但无样本场景的同口径延伸；契约字段名与含义不变 | 纯勘误（口径细化） |
| （E-4 起） | 前置检查其余各项均与 V1.0 一致（P1/P3/P4/P5/P6/P7/P8 无勘误；详见 build-output/w394-precheck-*.md） | — |

## §10 纪律与豁免登记

- 零新第三方依赖（actuator/micrometer-registry-prometheus = dependencies.md :24-25 已登记 PO 预授权复用；客户端 JDK HttpClient；测试 stub JDK HttpServer）/ 零告警规则扩改（deploy/k8s-monitoring 与 monitoring.ps1 零改动）/ 零前端改动 / 零门禁配置改动 / 零删除入口 / 零登录与令牌改动 / 零既有判定改写（判定内核、健康度 `online` 布尔、V1~V4 迁移、`AuditOutcome` 枚举零变化——全部加性原则）；
- PR ≤400 行有效变更按登记制豁免（D1 不拆分，沿 3.8.6 / 3.9.2 / 3.9.3 先例，随 PR 描述登记）；
- G-04（外发未接）/ G-07（留存未建）/ 数据不持久化 / 单副本 / 无集群层指标 = "待生效"诚实标注，不虚报（B7-R3 / B8-R4）；
- 剧本回填业务判定与步骤编号零变更（须 PO 批准）；规格文本零改动；发现规格缺口一律停机走变更流程。
