# WBS-3.7.5 高保真设计：计量采集服务（V0.9 待一次确认，2026-11-01）

> 承 `WBS-3.7.5-lofi.md` V0.9（同批提请确认）；**本文 V0.9 = 立卡段编码契约建议稿，编排师一次确认后转 V1.0 即时生效**。规格派生：C-7.1-7.2 V1.0 B7 / B8 / §6-3 / §6-4。
> 勘误编号自 **E1** 起（§10）；实现与本文不一致 = 打回项。

## 1. 端点契约表（4 个，全部 `/api/v1/metering` 前缀，ADR-005 统一响应）

| # | 方法与路径 | 权限点 | 请求 | 响应 data | 错误码 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| R-M0 | `POST /api/v1/metering/collect` | `metering.governance`（仅 admin） | 无 body | `{ scannedTotal, collectedTotal, bySource: { fileDelivery: {scanned, collected}, sandboxExport: {scanned, collected}, sandboxJob: {scanned, collected} } }`（collected = 本次新增行数；重复触发 collected=0 幂等） | 1000C0001（无参场景不触发）/ 权限层 403 | 触发通道 2 增量采集（§3）；可重复触发；单事务，任一源失败整体失败可重触发（幂等安全）；**不删除**（事件源删除不回溯——V1.0 演示无删除场景，登记演进） |
| R-M1 | `GET /api/v1/metering/contracts/{contractNo}/flows` | `metering.read` | 路径参数 contractNo | `{ contractNo, requesterNo, providerNo, items: [{ sourceType, sourceNo, action, resource, occurredAt }] }`（occurredAt 倒序全列不分页——演示量级，沿 3.7.4 R-EV2 先例登记演进；requesterNo/providerNo 来自 contract 表实时读，展示归属） | 1011C0008（404 同形：合约不存在与非本方同码同文防枚举）/ 1000C0001（contractNo 空白或非法格式） | 本方合约计量流水（B7-1 读面 + B8-4 归属校验服务端强制）；S1-1 / S1-7 承载 |
| R-M2 | `GET /api/v1/metering/contracts/{contractNo}/summary` | `metering.read` | 路径参数 contractNo | `{ contractNo, periods: [{ period: "YYYY-MM", flowCount, lastEventAt }] }`（period 自然月降序） | 1011C0008（404 同形）/ 1000C0001 | 本方合约按自然月聚合（B8-1 查询承载，Q2-A/Q3-A）；S1-6"计量聚合 = N"读数承载 |
| R-M3 | `GET /api/v1/metering/contracts/{contractNo}/reconcile` | `metering.governance`（仅 admin） | 路径参数 contractNo | `{ contractNo, deliveryFileCount, usageLogAllowedCount, usageCounterUsedCount, meteringFlowCount, meteringFlowByPeriod: [{ period, flowCount }] }` | 1000C0001 | **四方勾稽读数**（运营）：① 交付文件腿 = delivery(FILE/SANDBOX_EXPORT, SUCCESS) 计数；② 策略判定腿 = usage_log(ALLOWED) 计数；③ 余额口径 = usage_counter.used_count；④ 计量流水计数 + 分期聚合。**纯读数展示，无差异处置语义**（B11 对账动作归 3.7.6/3.7.8——本端点不判"通过/差异"，越界声明）；S1-3 三方勾稽（①②④）与 S1-6（③ vs ④）承载 |

> 非 admin 调 R-M0/R-M3：权限层 403（既有权限组件口径）；contractNo 格式非法：1000C0001（参数通用码沿 common，3.7.3 勘误 E6 同口径）。
> **R-M3 不做归属校验**（运营治理读面，B12-2 运营全量语义；metering.read 两端点才做 B8-4 归属校验——权限面与归属面分离清晰）。

## 2. 表结构与采集查询契约（迁移 V1，metering-billing，`V1__create_metering_flow.sql`）

```sql
CREATE TABLE metering_flow (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    contract_no  VARCHAR(32)  NOT NULL COMMENT '合约编号（勾稽与归属锚；CT+6位，沿 contract uk_contract_no 同域）',
    requester_no VARCHAR(32)  NOT NULL COMMENT '计量主体（网关事件 subject / 交付需求方快照）',
    source_type  VARCHAR(24)  NOT NULL COMMENT 'GATEWAY_API / FILE_DELIVERY / SANDBOX_JOB / SANDBOX_EXPORT（四源枚举，MeteringSourceType）',
    source_no    VARCHAR(64)  NOT NULL COMMENT '源单号（网关 eventId / delivery_no / job_no）——幂等锚',
    action       VARCHAR(48)  NOT NULL COMMENT '动作名（gateway.delivery.metering / delivery.file / delivery.sandbox_export / sandbox.job——采集常量映射）',
    resource     VARCHAR(128) NULL     COMMENT '被交付资源（网关腿 = routeId+方法+路径模板；文件/沙箱腿 NULL）',
    occurred_at  DATETIME     NOT NULL COMMENT '事件时点（事件源时点，非采集时点）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集落库时点',
    PRIMARY KEY (id),
    UNIQUE KEY uk_metering_source (source_type, source_no),
    KEY idx_flow_contract (contract_no, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统一计量流水（四源事件 1:1 映射；uk 幂等；ADR-022 §2.6-2 最小必要字段，零敏感原文列）';
```

- **单表无物化聚合**（Q2-A）：聚合 = `SELECT DATE_FORMAT(occurred_at,'%Y-%m') AS period, COUNT(*) AS flow_count, MAX(occurred_at) AS last_event_at FROM metering_flow WHERE contract_no=? GROUP BY DATE_FORMAT(occurred_at,'%Y-%m') ORDER BY period DESC`（idx_flow_contract 前缀可覆盖，演示量级不加新索引）；
- **采集查询契约（全部只读 SELECT，跨 schema 同实例，Q1-A 通道 2）**：
  1. 交付腿：`SELECT delivery_no, contract_no, requester_no, delivery_type, created_at FROM ctds_delivery.delivery WHERE result='SUCCESS' AND delivery_type IN ('FILE','SANDBOX_EXPORT')`——**排除 API 行**（API 计量源唯一 = 网关事件，B7-3 不双计）；映射 FILE→FILE_DELIVERY / SANDBOX_EXPORT→SANDBOX_EXPORT，action 常量 `delivery.file` / `delivery.sandbox_export`，occurred_at=created_at；
  2. 沙箱腿：`SELECT j.job_no, t.contract_no, j.created_at FROM ctds_delivery.sandbox_job j JOIN ctds_delivery.sandbox_task t ON j.sandbox_no = t.sandbox_no WHERE j.status='SUCCEEDED'`——映射 SANDBOX_JOB，action 常量 `sandbox.job`，occurred_at=j.created_at，requester_no=t 侧需求方（sandbox_task 需求方字段，编码段按列名实测对齐）；
  3. 归属校验：`SELECT requester_subject_no, provider_subject_no FROM ctds_contract.contract WHERE contract_no=?`（单一权威，实时读；流水表不冗余双方）；
  4. 勾稽读数：`SELECT COUNT(*) FROM ctds_contract.contract_usage_log WHERE contract_no=? AND outcome='ALLOWED'` + `SELECT used_count FROM ctds_contract.contract_usage_counter WHERE contract_no=?` + delivery 腿计数（同查询 1 计数版）+ 本表 GROUP BY——**四条只读，零写零改**；
- 跨 schema 库名前缀（`ctds_delivery.` / `ctds_contract.`）为**配置化常量**（application-mysql.yml `ctds.metering.source-schema.delivery / .contract`，默认值即上述库名——演示环境改名不改代码）；
- **事件源零结构改动**（B7-3）：上列查询为既有列直读，迁移仅落 metering 库本表。

## 3. 采集事务与幂等（R-M0 编排，Q6-A/Q8-A）

`MeteringCollectService.collect`（`@Transactional`）：
1. 交付腿采集：执行 §2 查询 1 → 逐行 `INSERT ... ON DUPLICATE KEY UPDATE id=id`（uk_metering_source 兜底）→ 统计 scanned / collected（affected 新增数）；
2. 沙箱腿采集：执行 §2 查询 2 → 同款 upsert → 统计；
3. 返回 bySource 计数（响应契约见 §1 R-M0）；
4. 幂等：重复触发全量重扫、uk 兜底零重计（collected=0）；任一 SQL 异常 → 事务回滚整体失败（500 通用，可重触发）；
5. **不删除不更新**：upsert 仅幂等空转，事件源删除/更正不回溯流水（V1.0 登记演进；对账兜底发现归 B11 既有义务）。

通道 1（网关事件）不在 R-M0 事务内——`JdbcMeteringAuditRecorder` 异步落库（§5），与采集通道互不阻塞。

## 4. 错误码（1011 段续用，规格 §6-3 预留；C0001~C0007 已由订单域/评价域占用）

| 码 | HTTP | 业务语义 | 文案（业务可读） |
| --- | --- | --- | --- |
| 1011C0008 | 404 | 计量数据不存在或非本方（同码同文防枚举，C0002/C0007 同口径） | 计量数据不存在或不可读 |

- 登记位置：`MeteringErrorCodes`（metering-billing domain/metering/）+ 异常处理器沿 delivery/contract 既有 `*ExceptionHandler` 模式（编码段按 metering 侧结构落位，**语义与本文档表逐字一致**）；
- 1000C0001（参数通用）沿 common；采集失败 = 500 通用（系统动作，无业务码）；权限拒绝沿 common-auth 既有 403。

## 5. common-logging 落库实现契约与网关改动清单（Q1-A 通道 1，规格 B7-3 字面路径）

### 5.1 `JdbcMeteringAuditRecorder`（common-logging 新增，计量专用实现）

- **职责**：`AuditRecorder` 实现——action 白名单命中时异步落库 `metering_flow`；**类注释声明**"计量事件落库实现（ADR-022 §2.6 / 规格 B7-3 移交 3.7.5）"，白名单 action 常量 `gateway.delivery.metering` 与 source_type 常量 `GATEWAY_API` 内置（计量专用，不假装通用）；
- **record() 语义**（沿 AsyncFileAuditRecorder 契约：不抛异常、不阻塞业务线程、null 静默）：
  1. action ≠ 白名单 → 直接返回（落库职责不承担；文件由组合实现负责）；
  2. detail 契约键取值：`contractNo` / `subject`（ADR-022 §2.6-2；detail 为防御性拷贝 Map）；`contractNo` 缺失或为 "unknown" → **丢弃 + 计数告警**（限频 ERROR）——无合约归属的事件行无计量意义，不入流水（设计边界：网关 `attributeOrUnknown` 兜底值不入库）；
  3. 命中且键齐 → 入有界队列（容量沿 AuditProperties.queueCapacity，满丢弃计数告警）→ 单后台守护线程 upsert：`INSERT INTO metering_flow (contract_no, requester_no, source_type, source_no, action, resource, occurred_at) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id`（source_no=eventId，occurred_at=eventTime，resource=detail.resource，action=白名单常量）；
  4. SQL 异常 → 60 秒限频 ERROR 告警、事件丢弃（不重排队——V1.0 演示量级接受，文件侧双写存根可人工核，勾稽兜底发现；登记演进：重试/死信）；
- **shutdown()**：最多排空 5 秒（沿文件实现模式）；
- **数据源**：自持（配置键 `ctds.audit.jdbc-url` / `jdbc-username` / `jdbc-password`——URL 指向 `ctds_metering` 库、账号 INSERT-only；口令环境变量注入 `${CTDS_AUDIT_DB_PASSWORD:}` 零入库）；表名配置 `ctds.audit.metering-table`（默认 `metering_flow`）。

### 5.2 装配改造（LoggingAutoConfiguration + AuditProperties，向后兼容零破坏）

- `AuditProperties` +字段：`sink`（String，默认 null = 文件行为不变）/ `jdbcUrl` / `jdbcUsername` / `jdbcPassword` / `meteringTable`；
- `auditRecorder` Bean 方法内部分支：`sink="metering-db"` → `CompositeAuditRecorder(file, new JdbcMeteringAuditRecorder(...))`（**file+jdbc 双写**：文件存证职能不中断——ADR-022 §2.6-1"出口 = AuditRecorder 落 logs/audit-*.jsonl"语义保持，3.8.3 存证与人工核对面保留）；否则返回 file（**现状路径，其他服务零影响**）；
- `CompositeAuditRecorder`（新增小类）：record 遍历委托、shutdown 级联委托（Bean destroyMethod="shutdown" 兼容）。

### 5.3 网关改动清单（**调用点/过滤器/错误码/表结构零改动**）

| 件 | 改动 |
| --- | --- |
| pom.xml | + `spring-boot-starter-jdbc` + `mysql-connector-j`（runtime）——B7-3 落库实现替换必需，非新框架（ADR-001 既有组件） |
| application.yml | + `ctds.audit.sink: metering-db` + `ctds.audit.jdbc-url/username/password`（口令 `${CTDS_AUDIT_DB_PASSWORD:}`）+ `connector-metering` 路由（`/api/v1/metering/**` → 8089） |
| 代码 | **零改动**（DeliveryMeteringGlobalFilter / GatewayErrorWriter / GatewayUnauthorizedEntryPoint 三个调用点不触碰） |
| 测试 | 既有 44 例零回归（测试不配 sink → 默认 file，装配路径不变）+ 路由测试扩 connector-metering 契约（沿 3.7.4 模式） |

## 6. 动作矩阵与权限（服务级 yml 自治映射；普通档零 admin 混入）

| 动作 | 普通档（requester,provider） | 运营档（admin） | 服务端把关 |
| --- | --- | --- | --- |
| 触发采集 R-M0 | ✗ | ✓ | metering.governance |
| 查本方流水 R-M1 | ✓（归属校验兜底） | ✓（同） | metering.read + contract 双方归属硬校验（B8-4） |
| 查本方聚合 R-M2 | ✓（同） | ✓（同） | 同上 |
| 四方勾稽 R-M3 | ✗ | ✓ | metering.governance（治理读面，不做归属校验） |

- metering-billing application.yml：`ctds.auth.permissions: requester: metering.read / provider: metering.read / admin: metering.read,metering.governance`；
- **新权限点 2 个**（metering.read / metering.governance）——新域零可复用点（48 点全景实测），沿 `域.动作` 命名制；服务级自治映射，无平台登记面（Q4-A）。

## 7. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 `feat/WBS-3.7.5-计量采集服务` 存在、工作树 clean、自 `762fc4b` 梢创建；
2. 端口 8089 空闲复核（netstat 实测，8080~8088 已占用清单比对）；
3. 根 pom `<modules>` 16 项现状 + `gates-config.json` ConfigSha256 复核（基线 `955bdbba…2ee2`）；
4. 1011 段 C0008 起空位复核（Java + docs 双查）；
5. 权限点全景 48 个复核（@RequirePermission 值清单，零 metering.\* 前提）；
6. 事件源表字段亲读复核（本设计 §2 依据）：delivery_type 三值与 result 值域 / sandbox_task 需求方字段名 / contract 双方字段（provider_subject_no/requester_subject_no）/ usage_log outcome 值域 / counter.used_count；
7. `LoggingAutoConfiguration` / `AuditProperties` 现状亲读（sink 接缝基线 + Bean 方法签名）；
8. 网关 9 路由复核（connector-metering 不存在，防撞名）+ 三个 AuditRecorder 调用点亲读（零改动前提留痕）；
9. gateway 测试基线复跑（44 例）；
10. contract / delivery 测试基线实测复跑（任务卡回填实测数：contract 25 类 274 例待复核 / delivery 227 例）；
11. 前端四步复跑（58 文件 749 例零改动兜底基线）；
12. 演示环境 MySQL 可达性 + 三库库名亲读（`ctds_metering` / `ctds_delivery` / `ctds_contract`——跨 schema 采集 SQL 与配置默认值依据）。

## 8. 测试锚点规划（测试先行：红相先行，编码段回填实测类名与例数）

| # | 锚 | 用例要点（Given/When/Then 映射） | 落点（规划） |
| --- | --- | --- | --- |
| T1 | B7-1 正向 | N 条网关事件 upsert（不同 eventId）→ R-M1 流水 = N 条逐条可溯（时点/动作/合约/主体）；同 eventId 重放 → 仍 N（幂等） | metering 集成（SharedMySqlContainer + Flyway V1） |
| T2 | B7-2 负向 | denied 动作事件 → 不入流水（白名单丢弃）；模拟 denied 留痕独立存在（文件面，单测证 record 分支） | common-logging 单测 + metering 集成 |
| T3 | B7-3 三方勾稽 | 造文件交付动作（delivery 1 行 + usage_log ALLOWED 1 行）→ R-M0 采集 → R-M3：①=1 ②=1 ④=1（③ counter 同步 +1）——三方各恰好 1 条不双计 | 同上（跨 schema 造数沿测试库同实例） |
| T4 | B7-4 沙箱 | sandbox_job(SUCCEEDED) 1 + delivery(SANDBOX_EXPORT) 1 → 各计 1 条不叠加；重复 collect → collected=0 零新增 | 同上 |
| T5 | B8-1 聚合 | 跨两个自然月的事件 → R-M2 两期聚合（period 边界正确、降序、lastEventAt 准确） | 同上 |
| T6 | B8-2 免费档 | FREE 合约（pricing_model='FREE' 快照）调用事件 → 流水照常落、R-M1/R-M2 可查完整 | 同上 |
| T7 | B8-3 负向核对 | N 次放行（counter=N）+ 1 次越界（usage_log DENIED 行、counter 不变）→ R-M3：③=N ④=N（拒绝未触碰任何一侧） | 同上 |
| T8 | B8-4 负向 | 主体 C 查 A 合约流水/聚合 → 404 + 1011C0008 业务可读；A 查 → 200；合约不存在 → 同码同文 404（防枚举） | 同上 |
| T9 | 结构锚 | V1 单表 + uk_metering_source + idx_flow_contract 结构；列面 = ADR-022 §2.6-2 最小必要（零请求体/响应体/原文/令牌列——静态断言） | 迁移测试（沿 3.7.4 T9 同型） |
| T10 | 采集幂等 | 同源重复 collect 全量重扫零重计；uk 兜底 DuplicateKey 空转验证 | metering 集成 |
| TC1~TC4 | common-logging 单测 | 白名单命中/未命中分支；detail 缺 contractNo 丢弃+告警；SQL upsert 幂等语义（mock JdbcTemplate）；sink 分支装配（默认 file / metering-db 双写组合、shutdown 级联） | common-logging 测试（沿既有测试模式） |
| TG1 | 网关路由 | connector-metering 路由契约（七门面沿 3.7.3 模式）+ 既有 44 例零回归 | gateway-service |

- 红相先行归档：`build-output/w375-red-backend.log`（+common 单测红相同批）；
- **网关事件端到端链**（网关→Jdbc 落库）由 TC1~TC4（实现单测）+ 演示走查承载，metering 集成测试以事件行造数隔离（不起网关全链——测试边界声明，评审可议）。

## 9. 剧本映射（C-7.2 V1.0 → V1.1 核对修订范围）

| 剧本步骤 | 本卡承载 | 修订内容（V1.1） |
| --- | --- | --- |
| S1-1 | R-M1 本方流水（N 次放行 = N 条逐条可溯） | 操作路径 = 接口调用（curl 示例 + 网关前缀 `http://127.0.0.1:8086/api/v1/metering/...` + 角色头说明）；业务判定一字不改 |
| S1-2 | 越界被拒（沿 C-4.3 构造）→ R-M1 仍 N 条 + 拒绝留痕独立（网关审计文件 `gateway.delivery.denied`） | 同上 + "拒绝留痕独立存在"的查看路径（审计文件/日志说明） |
| S1-3 | R-M0 触发采集 → R-M3 四方读数（文件交付动作 ①=1 ②=1 ④=1） | **新增操作步**：运营先触发采集（R-M0）再读勾稽（R-M3）——操作形态新增，判定"三方各恰好 1 条"一字不改 |
| S1-4 | R-M0 采集后 R-M1/R-M3 见沙箱 1 + 导出 1；模拟器自检后 R-M0/R-M1 零新增 | 同上（采集触发步） |
| S1-5 | R-M1/R-M2 查 P0 免费档合约（事件留痕完整）；计费金额 = 0 无明细行 = 3.7.6 交付后补验（本步前半） | 承载注记：计费金额腿归 3.7.6（剧本既有"下游核对预告"一致） |
| S1-6 | R-M3：counter.used_count = N vs meteringFlowCount = N（R-M2 聚合同数） | 同上（读数路径接口化） |
| S1-7 | 主体 C 直调 R-M1/R-M2 → 404 + 1011C0008 业务可读 | 同上 + 错误文案展示 |
| S2 全幕 | 3.7.6 范围 | **零改动** |
| 演示前提⑤ | 接口承载清单（4 端点 + 角色头 + curl 惯例） | 按 3.7.5 交付实测更新注记 |

业务判定一字不改；V1.1 版本行如实记述修订范围；提示 PO 批准。

## 10. 边界、复用声明与勘误预留

- **复用声明**：common-logging `AuditRecorder` 接缝与 `AsyncFileAuditRecorder` 队列/限频/shutdown 模式；common-auth `@RequirePermission` + 服务级角色映射；common-errorcode ADR-005 统一响应 / 1000C0001；ADR-009 Flyway 迁移规范；ADR-016 回环绑定；ADR-007 幂等（uk + upsert 语义）；kms 脚手架先例（parent/relativePath/profile 模式）；SharedMySqlContainer 模块级单例模式；k8s deployment+service+kustomization 结构；网关路由测试模式。**新 Maven 依赖仅网关侧 spring-boot-starter-jdbc + mysql-connector-j**（B7-3 字面路径必需——依赖审批声明随 PR）；
- **规格外实现声明**：应为空（本设计全部条目可回溯至 B7/B8 与 Q1~Q8+D1 建议口径）；
- **勘误预留**：E1 起编号；立卡段自勘一处（初稿 §1 R-M3 曾含 `consistent` 布尔结论字段——与 B11 对账动作语义越界冲突，已更正为纯读数展示，V0.9 为更正后稿）；
- **ADR-022 行 100 次序边缘注记**（设计边界）：政策判定 ALLOWED 后交付域拒绝（"发起人 ≠ 需求方"）的场景下，usage_log 已留 ALLOWED 行但网关计量事件不产生（转发链未完成）——该边缘使 ② 策略腿与 ④ 计量腿可能差 1（已登记观察项，规格 §6-4 维持现状口径）；S1 演示不构造该场景；如需改判定次序走变更流程（关联 3.7.5 计量口径复核义务——本设计已按现状口径实现，复核结论 = 无改）；
- **演示环境配置义务**（交付说明承载，非代码）：ctds_metering 建库；网关审计账号（INSERT ON ctds_metering.metering_flow）；metering 采集账号（SELECT ON ctds_contract.{contract, contract_usage_log, contract_usage_counter} + ctds_delivery.{delivery, sandbox_job, sandbox_task}；读写 ctds_metering）；口令全部环境变量注入。
