# WBS-3.7.5 低保真设计：计量采集服务（V1.0 已确认 = 方向定稿，2026-11-01）

> 派生自 `docs/specs/C-7.1-7.2-门户与计费.md` V1.0 行为 B7 + B8；任务卡 `docs/tasks/WBS-3.7.5-计量采集服务-2026-11-01.md`。
> 发现规格缺口走变更流程，本设计不私自补需求。**V1.0 = 2026-11-01 编排师一次确认（"全部按建议"：Q1~Q8 采 A + D1 不拆分）后的方向定稿。**
> 非界面类任务：本设计 = 业务可读行为清单 + 流程 + 决策点；接口契约与边界值见 hifi。免 HTML 原型（读面以接口承载演示——剧本 C-7.2 演示前提⑤既有裁决）。

## 1. 做什么 / 不做什么

### W（做什么，8 项）

| # | 项 | 说明 |
| --- | --- | --- |
| W1 | 新服务脚手架 | `services/metering-billing`（8089 / ctds_metering 库）：pom〔parent=ctds-platform 2.0.0-SNAPSHOT + relativePath=../../pom.xml，沿 kms 先例〕+ 主类 + application.yml（8089/127.0.0.1/权限映射）+ application-mysql.yml（数据源 + Flyway）；根 pom 注册第 17 模块 |
| W2 | 迁移 V1 单表 | `metering_flow` 统一计量流水表（uk(source_type, source_no) 幂等锚；无物化聚合表——Q2） |
| W3 | 计量域与采集器 | MeteringFlow 聚合 / MeteringSourceType 四源 / MeteringErrorCodes（1011C0008+）/ 仓储；采集器 = 交付源（delivery FILE/SANDBOX_EXPORT 且 SUCCESS）+ 沙箱源（sandbox_job SUCCEEDED 经 sandbox_task JOIN 合约归属）——跨 schema 只读，upsert 幂等 |
| W4 | 读面 4 端点 | R-M0 触发采集（运营）/ R-M1 本方合约流水 / R-M2 本方合约按自然月聚合 / R-M3 四方勾稽（运营）——详见 hifi §1；归属校验实时查 contract 双方（B8-4） |
| W5 | common-logging 落库实现 | `JdbcMeteringAuditRecorder`：action=gateway.delivery.metering 白名单 → 异步 upsert metering_flow（detail 契约键按 ADR-022 §2.6-2）；`ctds.audit.sink=metering-db` 条件装配（默认 file 零影响其他服务）；**file+jdbc 双写**（文件存证职能不中断） |
| W6 | 网关装配与路由 | pom +spring-boot-starter-jdbc +mysql-connector-j（B7-3 落库替换必需）；yml + sink 配置与 ctds_metering 只写数据源（**调用点/过滤器/错误码零改动**）；`connector-metering` 路由 → 8089 + 路由测试 |
| W7 | 部署登记 | `deploy/k8s/metering-billing-deployment.yaml` + `-service.yaml` + kustomization 登记（沿既有 7 服务对先例） |
| W8 | 剧本修订 | C-7.2 转 V1.1——S1 操作路径核对（接口承载注记 + 运营触发采集步 + curl 示例）；业务判定一字不改，PO 批准；S2 零改动 |

### N（不做什么，8 项）

| # | 项 | 归属/理由 |
| --- | --- | --- |
| N1 | 网关调用点/过滤器逻辑/错误码改动、合约/交付/沙箱任何端点与表结构改动 | 规格 B7-3 明文"不改上游"——只做装配与依赖扩展（落库实现替换的字面路径） |
| N2 | 计费引擎/账单/对账处置/计费读面（B9~B12） | 归 3.7.6/3.7.7/3.7.8（规格 §4-3）；本卡只交付计量流水与聚合查询 |
| N3 | 计费金额列、免费档计费判定 | B8-2 计费语义归 3.7.6（按 pricing_model 快照判定）；流水只承载事件事实 |
| N4 | MQ/事件总线/定时任务/调度面 | 全仓零 MQ（实测）；采集 = 手动触发端点（Q6-A 最小）；周期自动出账与聚合物化归 3.7.6 演进 |
| N5 | 前端界面与 npm 依赖 | 计量/账单管理界面归 3.7.7（剧本演示前提⑤）；本卡接口承载演示 |
| N6 | 物化聚合表、采集水位表 | Q2-A 查询聚合承载 B8-1；演示量级全量 upsert 幂等无需水位 |
| N7 | 跨服务内部通道（/internal/**）、上游服务代码改动 | 采集 = 只读 SELECT 事件源表（同实例跨 schema），零内部面调用 |
| N8 | mutationTest 纳入、gates-config 改动 | mutationTest 显式清单演进项（登记）；coverage 尾名映射自动纳入零配置改动 |

## 2. 结构组成

```
services/metering-billing（8089，新建）
├─ pom.xml                parent=ctds-platform 2.0.0-SNAPSHOT / relativePath=../../pom.xml（kms 先例）
├─ db/migration/V1__create_metering_flow.sql        ← W2 单表
├─ domain/metering/       MeteringFlow / MeteringSourceType / MeteringErrorCodes(1011C0008+) / MeteringFlowRepository
├─ infrastructure/metering/
│   ├─ JdbcMeteringFlowRepository（upsert / 按合约流水 / GROUP BY 聚合）
│   ├─ DeliveryMeteringSourceReader（只读 ctds_delivery.delivery FILE/SANDBOX_EXPORT SUCCESS）
│   ├─ SandboxMeteringSourceReader（只读 ctds_delivery.sandbox_job SUCCEEDED JOIN sandbox_task 得合约）
│   └─ ContractOwnershipReader（只读 ctds_contract.contract 双方字段——归属校验权威）
│   └─ UsageCounterReader（只读 ctds_contract.contract_usage_log ALLOWED / contract_usage_counter——勾稽用）
├─ application/metering/  MeteringCollectService（触发采集编排）
│                         MeteringQueryService（流水 / 聚合 / 归属校验）
│                         MeteringReconcileService（四方勾稽）
└─ interfaces/            MeteringCollectController（R-M0）+ MeteringQueryController（R-M1/R-M2）+ MeteringReconcileController（R-M3）

common/logging（既有模块，新增 2 类 + 装配扩展）
├─ JdbcMeteringAuditRecorder   action 白名单 → 异步 upsert metering_flow（W5）
└─ LoggingAutoConfiguration    ctds.audit.sink=metering-db 条件分支（默认 file 行为零变）；AuditProperties +sink/jdbc 配置

gateway-service（8086，装配与路由增量；调用点/过滤器/错误码零改动）
├─ pom + spring-boot-starter-jdbc + mysql-connector-j（B7-3 字面路径必需）
├─ application.yml + ctds.audit.sink=metering-db + 审计专用数据源（只写 ctds_metering）+ connector-metering 路由
└─ 路由测试扩 connector-metering 契约

deploy/k8s/  metering-billing-deployment.yaml + metering-billing-service.yaml + kustomization +2 行（W7）
```

## 3. 主要流程（业务语言）

### 3.1 通道 1：网关调用事件落库（S1-1 / S1-2）

需方经网关发起交付调用 → 网关判定链放行（POLICY_ALLOWED）且转发链完成 → 既有过滤器记 1 条 `gateway.delivery.metering`（SUCCESS）审计事件（**调用点零改动**）→ `JdbcMeteringAuditRecorder` 命中 action 白名单 → 异步按 detail 契约键（contractNo/subject/resource）upsert `metering_flow`（source_type=GATEWAY_API，source_no=eventId，occurred_at=事件时点）→ 同步照常写审计文件（双写，存证职能不中断）。**越界被拒**：判定 DENIED → 网关走 `gateway.delivery.denied` 拒绝留痕（不命中白名单，照常落文件）→ **计量流水零新增**（拒绝零计量，结构性——白名单在装配层，非查询过滤）。

### 3.2 通道 2：交付与沙箱源采集（S1-3 / S1-4）

运营触发 R-M0 → `MeteringCollectService` 执行两路只读增量采集：① 交付源 = `ctds_delivery.delivery` 中 delivery_type ∈ {FILE, SANDBOX_EXPORT} 且 result='SUCCESS' 的行（**排除 API 行——API 计量源唯一 = 网关事件，不双计**）→ upsert metering_flow（source_type=FILE_DELIVERY/SANDBOX_EXPORT，source_no=delivery_no）；② 沙箱源 = `ctds_delivery.sandbox_job` 中 status='SUCCEEDED' 的行，经 sandbox_no JOIN `sandbox_task` 得 contract_no（ADR-024 一行 = 一次数据使用）→ upsert（source_type=SANDBOX_JOB，source_no=job_no）。**导出与作业不叠加**（导出在 delivery 表、作业在 sandbox_job 表，各计各的一次——ADR-025 单一导出事件源）；重复触发按源单号唯一键幂等零重计。

### 3.3 计量模型聚合与读面（S1-1 / S1-5 / S1-6 / S1-7）

需方/供方经 R-M1 查本方合约计量流水（按合约 + 时点倒序；**归属校验实时查 contract 双方字段**——非本方 404 同形业务可读拒绝）；R-M2 返回本方合约按自然月聚合（期间、事件数、末次事件时点——B8-1"合约 × 计量周期"由查询承载）；运营经 R-M3 对同一合约做四方勾稽（交付文件腿计数 / 策略判定 ALLOWED 计数 / 合约计数表余额 / 计量流水计数——S1-3 三方勾稽与 S1-6 计数 vs 聚合一个端点承载）。**免费档**（B8-2）：其调用事件照常进入流水（留痕完整），流水无计费金额语义（计费判定归 3.7.6）。**模拟器/测试台**（B7-2/ADR-021）：自检零落库 → 事件源表零新行 → 流水零新增（结构性，无需专门过滤）。

## 4. 决策点（Q1~Q8 + D1，全文与备选业务影响）

| 编号 | 问题 | 方案 A（建议） | 方案 B / C（不采理由） | 业务影响 |
| --- | --- | --- | --- | --- |
| **Q1** | 采集与落库路径（架构核心） | **A**：双通道——① 网关事件经 common-logging `JdbcMeteringAuditRecorder` 落库实现替换（白名单 action → 异步 upsert metering_flow；file+jdbc 双写；`ctds.audit.sink=metering-db` 条件装配默认 file）；② 交付/沙箱源表跨 schema 只读 SELECT 采集。规格 B7-3 字面路径（"落库实现替换不改网关调用点"）；网关新增依赖 = spring-boot-starter-jdbc + mysql-connector-j（B7-3 落库替换必需，ADR-001 技术栈内既有组件） | B：全 JSONL 文件采集（k8s 分 Pod 部署文件不可达——架构断裂；轮转/断点管理复杂）；C：HTTP 事件推送（新网络通道 + 失败补偿 + 鉴权面，改动面最大）；D：网关侧新建计量表落库（ADR-022 备选 B4 **已明确不采**） | A 零上游业务改动 + 拒绝零计量结构性 + 同源勾稽；B 部署形态断裂；C 面最大；D 违 ADR |
| **Q2** | 计量模型聚合形态 | **A**：查询聚合——仓储 GROUP BY（合约 × 自然月）承载 B8-1；无物化表、零一致性维护、聚合口径单点 | B：物化 summary 表 + 落行同步维护（多一表与一致性面；V1.0 无独立消费方——3.7.6 计费若需物化届时演进登记） | A 最小实现且 S1-6 可验；B 提前背负一致性成本 |
| **Q3** | 计量周期口径 | **A**：自然月（对齐 B10 出账周期 §7 Q6-A 既有裁决"默认自然月"——计量聚合与出账同周期闭账） | B：订单周年月（引入订单依赖与口径分叉，超本卡；如需改走规格变更流程） | A 与出账对齐，对账（B11）同周期可勾稽 |
| **Q4** | 权限点选型 | **A**：新增 `metering.read`（requester/provider/admin）+ `metering.governance`（仅 admin）——metering 新域 48 点全景零 metering.\*（实测）；服务级 yml 自治映射无平台登记面 | B：复用 contract.read / delivery.governance（跨域语义错位：合约读 ≠ 计量读，治理域混淆；读面归属已有服务端硬校验，权限点=角色粗门槛） | A 沿命名制度清晰可审计；B 语义错位埋混权隐患 |
| **Q5** | 免费档承载 | **A**：流水无差别落（调用事件照常留痕——B8-2"保证对账数据源完整性"字面）；流水表无计费金额列（计费判定归 3.7.6） | B：流水排除免费档（违背 B8-2 留痕完整）；C：流水标计费金额（越界 3.7.6 计费域） | A 域边界清晰；对账数据源完整 |
| **Q6** | 通道 2 采集触发 | **A**：运营手动触发端点 R-M0（metering.governance；返回采集计数）——确定性演示、无定时任务、无调度面；S1 操作路径显式含触发步（剧本 V1.1，业务判定不变） | B：读面查询前自动增量（读路径写副作用）；C：定时任务（调度面 + 演示时序不可控） | A 最小且可演示；B/C 引入额外机制面 |
| **Q7** | 剧本修订与验收范围 | **A**：C-7.2 转 V1.1——S1 操作路径按实测更新（接口承载注记 + R-M0 触发步 + curl 示例）；S2 零改动；业务判定一字不改，PO 批准；本卡验收 = S1 七步接口走查（沿 C-6.x 接口承载先例） | B：不改剧本（与剧本维护明示的"3.7.5 交付后操作形态核对修订义务"冲突） | A 兑现可演示定义与修订义务；B 留失实操作路径 |
| **Q8** | 采集幂等与防重 | **A**：uk(source_type, source_no) + upsert 天然幂等——网关事件重放（队列重试/重启排空）与采集重复执行零重计；采集 = 系统内部动作，**@Idempotent 请求级组件不适用**（零新增幂等组件接入） | B：@Idempotent/Redis 接入（对系统内部 upsert 属冗余，新增组件接入核验与配置成本） | A 语义完整零组件面；B 冗余 |
| **D1** | 体量/拆分 | **不拆分**：预估 2200~2600 行，1 个编码会话（WBS 行 317 = 1 人日）；采集（两通道）+ 计量模型 + 读面 = 同一条 S1 七步走查链，拆则"有流水无读面/有读面无采集"中间态；沿 3.5.x/3.6.x 服务包先例豁免 400 行上限（登记制） | B：拆分（新服务脚手架与采集/读面不可分；中间态无验收价值） | 超限登记沿先例，编排师在 D1 表态 |

## 5. 体量预估

- 有效变更 **2200~2600 行**（分解见任务卡 §五）；lofi/hifi/任务卡 3 文档约 500~600 行（立卡段）；
- 1 个编码会话；零前端 / 零 npm / 零 MQ / 零定时任务；新增迁移 1 件（单表）；新权限点 2 个；网关侧新 Maven 依赖 2 项（jdbc + mysql 驱动——B7-3 字面路径必需）；
- 测试增量：metering-billing 新建 25~35 例 + common-logging 3~5 例 + gateway 路由测试随例；既有基线零回归。
