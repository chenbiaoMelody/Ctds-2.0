# WBS-3.4.2 高保真设计：合约模板库服务（hifi）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（编码契约，2026-10-05 编排师"都按建议"一次确认）**——**已确认、正文口径全部生效**（Q1~Q8 均采建议口径 A + D1 不拆分，确认记录落本文件末节）；实现与本文逐条一致（章程 2.6.3 / AGENTS §5 自检项 1）；此前 V0.9 草案随立卡批 `6bccc63` 落盘 |
| 低保真 | `docs/designs/WBS-3.4.2-lofi.md` V0.9（方向，同批确认） |
| 规格锚点 | 规格 C-4.1~4.3 V1.0 行为 1（7 规则 5 验收标准）；错误码 1008 段（§7 Q8 预留） |
| 技术栈 | JDK 17 + Spring Boot 3.5.x + `spring-boot-starter-jdbc` + Flyway + MySQL 8（ADR-001 冻结栈）；零新依赖（client = JDK HttpClient） |

---

## 1. 模块骨架与落位

```
services/contract-service/
  pom.xml                          # parent = 根 pom（relativePath ../../pom.xml），artifactId contract-service，2.0.0-SNAPSHOT
  src/main/java/com/ctds/contract/
    ContractServiceApplication.java
    config/                        # RestTemplate/HttpClient Bean、yml 属性绑定（松弛绑定驼峰对应；Duration 加 @DurationUnit(SECONDS)）
    interfaces/
      ContractTemplateOpsController.java     # 运营方写面 3 端点（admin 档）
      ContractTemplateManageController.java  # 运营方读面 3 端点（admin 档）
      ContractTemplateBrowseController.java  # 已入驻读面 2 端点
      dto/                                   # 请求/响应 DTO（记录类）
    application/
      ContractTemplateAppService.java        # 写面用例编排（新增/修订/启停 + 留痕）
      TemplateQueryService.java              # 读面 + 发起侧校验方法（供 3.4.3 同服务调用）
      TemplateNoGenerator.java               # 模板编号生成（序号表原子自增）
    domain/
      ContractTemplate.java                  # 模板聚合根
      TemplateVersion.java                   # 版本实体（含条款框架载体）
      ClauseFramework.java                   # 条款框架值对象（槽位校验逻辑）
      TemplateType.java / TemplateStatus.java
      ContractTemplateRepository.java        # 仓储接口（无 UPDATE version 方法——快照不可变编译期保证）
    infrastructure/
      jdbc/ContractTemplateJdbcRepository.java
      client/SubjectAdmissionClient.java     # JDK HttpClient，三态判定（沿 catalog 同名先例）
  src/main/resources/
    application.yml                          # 端口 8085 / 库 ctds_contract / flyway / subject baseUrl
    db/migration/V1__create_contract_tables.sql   # 4 表 + 三类模板种子（见 §4/§5）
  src/test/java/com/ctds/contract/...        # 见 §7 测试计划
```

> **表数勘误（沿 3.3.2 先例）**：任务卡交付物 ② 记"3 表"，本 hifi 定稿 **4 表**（+ `contract_template_no_seq` 模板编号序号表——沿 subject `nextDailySeq` / catalog `dataset_no_seq` 先例，原子自增取号；模板为低频运营对象，采用**全局 6 位序号不按日重置**，序号表恒 1 行）。

## 2. 端点契约表（ADR-005：资源复数命名 / 动词子资源 / 分页 / 写操作幂等）

### 2.1 运营方写面（admin 档；`@RequirePermission("contract.template.manage")`）

| # | 端点 | 请求体 | 成功响应 | 幂等口径 |
| --- | --- | --- | --- | --- |
| W1 | `POST /api/v1/contract-templates` 新增模板（含首版本） | `{name, type, clauseFramework}` | 201 `{templateNo, versionNo: 1, status: "ENABLED"}` | 服务端派生幂等键 = `adminNo + type + 归一化名称`；同键重放返回首次结果、模板数不变（沿 catalog V1.1 口径） |
| W2 | `POST /api/v1/contract-templates/{templateNo}/revisions` 修订出新版本 | `{clauseFramework}` | 201 `{templateNo, versionNo: Vn, previousVersionNo: Vn-1}` | 服务端派生幂等键 = `adminNo + templateNo + 框架归一化哈希`；重放返回首次新版本、不产生额外版本行 |
| W3 | `POST /api/v1/contract-templates/{templateNo}/disable` 停用 | 空 | 200 `{templateNo, status: "DISABLED"}` | 同态重复停用 = 1008C0007 拒绝 + 留痕（非幂等重放——状态机门槛） |
| W4 | `POST /api/v1/contract-templates/{templateNo}/enable` 启用 | 空 | 200 `{templateNo, status: "ENABLED"}` | 同态重复启用 = 1008C0007 拒绝 + 留痕 |

> 修订（W2）不对状态设门槛——停用模板可修订（修订出新版本不改变停用态；重新启用后以最新版本呈现）。该口径为规格未定义项的本卡落定（最小限制原则），随 Q3/Q6 一并确认。

### 2.2 运营方读面（admin 档；`@RequirePermission("contract.template.manage")`）

| # | 端点 | 响应 | 说明 |
| --- | --- | --- | --- |
| R1 | `GET /api/v1/contract-templates/manage?pageNum=&pageSize=&status=&type=` | 分页全量列表（含停用，含当前版本号与状态） | 运营维护视图（Q6：停用模板此处可查，支持重新启用） |
| R2 | `GET /api/v1/contract-templates/manage/{templateNo}/versions` | 版本历史列表（版本号 / 发布人 / 发布时间 / 框架全文） | 规则 3"旧版本保留可查"承载（Q8-A：归运营读面） |
| R3 | `GET /api/v1/contract-templates/manage/action-logs?templateNo=&pageNum=&pageSize=` | 留痕分页（四要素 + from→to + 拒绝理由码） | 规则 7 / 剧本 S3-3；不含敏感原文 |

### 2.3 已入驻读面（`@RequirePermission("contract.template.read")`；先经 SubjectAdmissionClient 资格门槛）

| # | 端点 | 响应 | 边界 |
| --- | --- | --- | --- |
| R4 | `GET /api/v1/contract-templates?pageNum=&pageSize=&type=` | 分页列表——**仅启用中**（模板编号/名称/类型/当前版本号） | 未入驻/不存在场景不在列表语义内（列表恒可看空页）；资格门槛拒绝见 §3 错误码 |
| R5 | `GET /api/v1/contract-templates/{templateNo}` | 详情（当前版本条款框架全文：槽位键/名称/必填性/填写说明） | **不存在 / 已停用 → 同一错误码同一文案**（Q6 最严口径，防枚举）；响应不含任何数据本体与敏感原文（剧本 S1-2） |

### 2.4 发起侧校验能力（**应用服务层方法，无 HTTP 端点**）

| # | 方法（`TemplateQueryService`） | 语义 |
| --- | --- | --- |
| QV1 | `validateForInitiation(templateNo, versionNo)` | 返回三态：有效〔模板存在 + 版本存在 + 模板启用中〕/ 无效（含原因：不存在 / 版本不存在 / 已停用）/ 供 3.4.3 发起流程在锁定版本快照前调用 |
| QV2 | `loadFramework(templateNo, versionNo)` | 读取指定版本条款框架全文（3.4.3 锁定快照时的内容来源） |

> **为何无 internal HTTP 端点**：合约域 3.4.2~3.4.8 共用 contract-service 宿主（Q1-A），发起 / 策略引擎等同服务直调应用服务方法，不走 HTTP；未来跨服务消费方（如交付链）出现时按 ADR-016 §6 先例补 internal 端点（届时另卡留痕）。任务卡交付物 ④"端点形态与路径随 hifi 定稿"即此落定。

## 3. 错误码表（1008 段，`ContractErrorCodes` 常量类定稿；C=客户端 / S=系统，沿 1007 段两型先例）

| 码位 | HTTP | 文案/语义 | 落点 |
| --- | --- | --- | --- |
| `1008C0001` | 404 | `模板不存在或不可用`（统一防枚举文案） | R5：模板不存在 / 已停用（浏览者视角同形逐字） |
| `1008C0002` | 403 | `无权进行模板维护操作`（+ 拒绝留痕） | W1~W4 / R1~R3 非 admin 档（规则 1 服务端强制） |
| `1008C0003` | 404 | `主体未入驻或不存在，无法浏览模板`（统一防枚举文案，对齐 C-1.1 出站口径） | R4/R5 资格门槛拒绝（NOT_ADMITTED 与主体不存在同形） |
| `1008C0004` | 400 | `条款框架不符合模板规范`（逐槽位明细随响应返回） | W1/W2：缺必填槽位 / 未知槽位键 / 结构非法 |
| `1008C0005` | 409 | `同类型下模板名称已存在` | W1：（type+归一化名称）唯一约束 |
| `1008C0006` | 404 | `模板不存在或不可用`（运营面视角） | R2/W2/W3/W4 运营面模板号不存在（运营面不防枚举，直述） |
| `1008C0007` | 409 | `模板已处于目标状态` | W3/W4 同态重复启停（+ 留痕） |
| `1008C0008` | 400 | `请求参数不合法`（逐字段） | 通用参数校验（缺 name/type/超长等） |
| `1008C0009` | 409 | `模板正在被其他操作修改，请重试` | W2 并发修订撞（template_id, version_no）唯一索引兜底 |
| `1008S0001` | 503 | `主体资格服务暂不可用，请稍后重试`（UNAVAILABLE 统一文案，不冒充无权限/不存在） | SubjectAdmissionClient 不可达/超时（沿 3.3.2 三态先例） |

> 拒绝留痕理由码复用上表码位常量（沿 catalog "留痕拒绝理由列"口径；DB-31 债务的分叉教训——本卡留痕 reason 列直接存 1008 码位字符串，不另设第二套理由枚举）。

## 4. 表结构与约束（MySQL 8；命名与审计字段沿 catalog 先例）

```sql
-- 1. 模板主表
CREATE TABLE contract_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_no   VARCHAR(32)  NOT NULL COMMENT '业务编号 CT+6位全局序号',
  template_name VARCHAR(128) NOT NULL COMMENT '模板名称',
  template_type VARCHAR(32)  NOT NULL COMMENT 'PUBLIC_DATA_AUTHORIZATION / API_CALL / PRIVACY_COMPUTING',
  current_version INT        NOT NULL DEFAULT 1 COMMENT '当前版本号（指向版本表）',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED',
  created_by    VARCHAR(24)  NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_no (template_no),
  UNIQUE KEY uk_type_norm_name (template_type, template_name_norm)  -- 归一化列：生成列 LOWER(TRIM(name))，沿 catalog uk_space_norm_name 先例
) COMMENT='合约模板主表';

-- 2. 模板版本表（行级版本化 = 版本快照载体；无 updated_at——不可变）
CREATE TABLE contract_template_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id   BIGINT NOT NULL,
  version_no    INT    NOT NULL COMMENT '模板内递增，从 1 起',
  clause_framework JSON NOT NULL COMMENT '条款框架：槽位键/名称/必填性/填写说明（lofi §3 槽位集合实例化）',
  published_by  VARCHAR(24) NOT NULL,
  published_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_version (template_id, version_no)
) COMMENT='合约模板版本表（版本行不可变）';

-- 3. 模板留痕表（沿 catalog action_log 先例）
CREATE TABLE contract_template_action_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_no   VARCHAR(32) NOT NULL,
  version_no    INT         NULL COMMENT '动作涉及版本（CREATE/REVISE 必有；启停记当前版本）',
  action        VARCHAR(32) NOT NULL COMMENT 'CREATE / REVISE / ENABLE / DISABLE / DENIED_MANAGE / DENIED_READ / DENIED_STATE',
  actor_subject_no VARCHAR(24) NOT NULL,
  reason_code   VARCHAR(16) NULL COMMENT '拒绝类动作存 1008 码位（DB-31 口径：直接存码位，不设第二套枚举）',
  from_value    VARCHAR(64) NULL COMMENT 'REVISE: 旧版本号；启停: 旧状态',
  to_value      VARCHAR(64) NULL COMMENT 'REVISE: 新版本号；启停: 新状态',
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_log_template (template_no, created_at)
) COMMENT='合约模板留痕表';

-- 4. 模板编号序号表（全局 1 行，原子自增不按日重置）
CREATE TABLE contract_template_no_seq (
  id TINYINT PRIMARY KEY,
  next_no INT NOT NULL
) COMMENT='模板编号序号表';
```

> 跨表引用（version.template_id → template.id）为**同库逻辑引用**，沿 catalog 先例不加物理外键（迁移与测试先例一致）；`clause_framework` 用 MySQL 8 `JSON` 类型（结构校验在领域层 `ClauseFramework` 值对象完成，DB 层不做 JSON Schema）。

## 5. 预置三类模板种子（随 V1 迁移落库）

- 三条模板记录（`CT000001` 公共数据授权 / `CT000002` API 调用 / `CT000003` 隐私计算）+ 各自 V1 版本行（条款框架 = lofi §3 槽位集合实例化：公共骨架 7 必填 + 各类差异化槽位）+ 状态 ENABLED；
- 种子数据只插模板三表，不动序号表（序号表初始 `next_no = 4`，运营新增从 CT000004 起）；
- 演示期直接可用（C-4.1 剧本演示前提④"预置三类模板随 3.4.2 落库"兑现）。

## 6. 状态机与事务

| 用例 | 事务（同库同事务，`@Transactional`） | 并发与兜底 |
| --- | --- | --- |
| 新增（W1） | INSERT 主表（取号：序号表原子自增）+ INSERT 版本 V1 + INSERT 留痕 CREATE | 幂等键命中重放返回首次；uk_type_norm_name 兜底 → 1008C0005 |
| 修订（W2） | SELECT 当前版本 → INSERT 版本 Vn+1 → UPDATE 主表 current_version → INSERT 留痕 REVISE（from Vn-1 to Vn） | 并发撞 uk_template_version → 1008C0009（沿 catalog T15 并发兜底先例）；幂等键命中重放返回首次 |
| 启停（W3/W4） | UPDATE 主表 status → INSERT 留痕 ENABLE/DISABLE（from→to） | 同态操作前置校验拒绝 → 1008C0007 + DENIED_STATE 留痕 |
| 拒绝留痕 | 独立事务写 DENIED_* 行（主事务回滚不影响拒绝留痕，沿 catalog 先例） | — |

- **版本行不可变**：仓储接口无 UPDATE version 方法（编译期保证）+ 修订后旧版本行内容逐字不变测试锚（§7 T8）；
- **停用不影响既有**：本卡范围内停用只改状态与浏览可见性、不触碰版本行（历史版本原样保留）——"协商中草案按 V1 快照继续"由 3.4.3 快照机制兑现（移交义务双登记）。

## 7. 测试计划（Testcontainers 实跑；Skipped 0 口径；映射任务卡 §三）

| 锚 | 覆盖 | 关键断言 |
| --- | --- | --- |
| T0 迁移与种子探针 | 规则 2 | 4 表结构齐；三类模板各 1 条 + V1 版本行 + ENABLED；序号表初始 next_no=4 |
| T1 新增 | 规则 1/7；W1 | admin 新增成功：CT 编号 + V1 + 留痕四要素；幂等重放返回首次、模板数不变；同类型同名 → 1008C0005 |
| T2 修订与版本化 | 规则 3；W2 | 修订产生 V2、V1 保留可查（R2 历史含全文）、当前版本指针前移、留痕 from→to 逐字；幂等重放不产生 V3 |
| T3 维护权矩阵 | 规则 1；剧本 S3-1 | admin 过；provider 档 / 普通档 / 无头 → 1008C0002 + DENIED_MANAGE 留痕；**直调接口变体同拒**（同矩阵复跑） |
| T4 浏览边界防枚举 | 规则 5；剧本 S1-3/S3-2 | 未入驻与主体不存在响应**逐字相同**（1008C0003）；client 不可达 → 1008S0001 不冒充；浏览者查停用模板 = 1008C0001 与不存在**逐字相同** |
| T5 浏览列表与详情 | 规则 5；剧本 S1-1/S1-2 | 已入驻列表仅含启用中；详情返回当前版本条款框架；响应字段集显式锚定（无数据本体） |
| T6 启停 | 规则 4；剧本 S2-4/S2-6 | 停用后：退出浏览列表（R4 不含）、QV1 返回"已停用"无效态、重复停用 1008C0007+留痕；重新启用恢复全部语义；启停留痕 from→to |
| T7 版本历史与留痕查询 | 规则 3/7；剧本 S3-3 | R2 历史逐版本全文；R3 留痕四要素 + 无敏感原文（字段集锚定） |
| T8 快照不可变反向探针 | 规则 3 | 修订 V2 后 V1 版本行内容逐字不变；仓储接口无 UPDATE version 方法（架构锚） |
| T9 槽位框架校验 | 规则 6 前置 | 缺必填槽位 / 未知槽位键 / 结构非法 → 1008C0004 逐槽位明细；**对照组**：合法框架放行 |
| T10 发起侧校验方法 | 规则 4/6；QV1/QV2 | 存在+版本有效+启用三条件逐项反证（不存在/版本不存在/已停用三态）；loadFramework 返回指定版本全文 |

> 单元测试另覆盖：`ClauseFramework` 值对象校验、`TemplateNoGenerator` 序号原子自增与并发、枚举序列化；`SubjectAdmissionClient` 三态单测沿 catalog 双 client 单测先例。

## 8. 数据分级落级表（hifi 定稿 + 分级规范 §6.1 回写同步行）

| 表 | 级别 | 依据 |
| --- | --- | --- |
| contract_template / contract_template_version / contract_template_no_seq | **L1** | 条款框架 = 业务配置文案，不含个人信息与业务数据本体 |
| contract_template_action_log | **L3** | 含操作者主体编号（沿 catalog action_log = CAT-06 L3 先例） |

> 回写分级规范 §6.1 补四行（同步动作非需求变更，沿 3.2.2 Q9 / 3.3.2 Q8 先例）。

## 9. 配置与部署

- `application.yml`：`server.port=8085`、库 `ctds_contract`、Flyway 启用；subject 侧 baseUrl 配置项 `ctds.contract.subject-base-url`（松弛绑定驼峰对应；环境变量 `CTDS_CONTRACT_SUBJECT_BASEURL`，沿 catalog 环境变量命名先例——**catalog 服务重启须三环境变量的教训**：本服务交付起补环境变量登记，部署清单同步注明）；
- **subject 侧唯一改动**：`application.yml` 客户端授权清单加 1 行 `contract-internal`（沿 catalog-internal 先例，ADR-016 §6 授权模式）；
- deploy/k8s：追加 `contract-deployment.yaml` + `contract-service.yaml` 两件并入 kustomization（沿 3.3.2 先例）。

## 10. 备忘（设计与实现对照义务）

1. 任务卡"3 表"勘误为 4 表（§1 头注，沿 3.3.2 表数勘误先例）；
2. 幂等键一律**服务端派生**（沿 catalog V1.1 口径，不设客户端幂等头）；
3. 浏览者视角"停用 = 不存在"同形是 Q6-A 最严口径的契约表达（1008C0001 单码承载）；
4. O1 / O2 观察项（C-4.1 S2-3 括注 / C-4.3 S1-3 载体补注）**不在本卡落笔**——随本域交付期剧本修订经 PO 批准统一处理（台账跟踪项，勿漏）；
5. 本卡零门禁配置改动、零 ADR 改动、零新依赖；subject 侧改动仅 yml 1 行授权；
6. 性能敏感路径豁免：模板库为低频运营对象，无网关/检索/计量类热路径（AGENTS §4 基准义务不触发）。

---

## 确认记录

> **确认留痕（2026-10-05 17:0x，编排师会话回复"**都按建议**"）**：**Q1~Q8 均采建议口径 A + D1 不拆分**——本文件转 **V1.0（编码契约）**（首部"若改选须重写"注记同步改为"已确认、正文口径全部生效"），lofi 同批转 V1.0，实现进入编码阶段（测试先行）。
