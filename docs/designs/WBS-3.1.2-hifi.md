# WBS-3.1.2 主体数据模型与注册服务 · 高保真设计

- 型态：非界面类（任务卡已标注）
- 对应规格：`docs/specs/C-1.1-主体注册与实名认证.md` V1.0（已确认 2026-09-13）——行为 1（主体注册）、行为 4（状态机与流转留痕）、行为 2 第 5 条（L4 介质取舍供 3.1.3 执行）、行为 5 第 3 条（权限机制同源）
- 方向确认：`docs/designs/WBS-3.1.2-lofi.md`（PO 签署 = 五问答复随签署意见留痕；本文件与 lofi 同批提交、一次确认）
- 任务卡：WBS 3.1.2 ｜ 会话预算：1
- 本文件新增契约（错误码段 1004 / 库表 / 接口）将随编码同步固化进 **ADR-016 主体服务契约**（沿 ADR-015 先例）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | （待确认） |  |  |  |

## 行为清单（10 项，逐条对应规格与计划测试）

| 编号 | 行为（业务可读） | 规格出处 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 新建业务服务 `services/subject-service`（artifactId `subject-service`，注册进根 pom modules），四层结构 `interfaces/application/domain/infrastructure`（沿 kms 先例），ArchUnit 分层守卫测试 | lofi 场景判定-1 | ArchUnitTest：Controller 不触仓库层、跨层依赖方向断言 |
| B2 | Flyway `V1__create_subject.sql`：建 `subject`（主体）、`subject_status_log`（流转留痕）、`subject_daily_seq`（申请编号当日序号）三表于库 `ctds_subject`（表结构见"库表设计"） | 行为 1/4；ADR-009 | Testcontainers-MySQL 集成测试：迁移执行成功、约束生效（重复 uscc 唯一键拒绝） |
| B3 | **注册服务**：必填与格式校验（逐字段提示，不产生半成品档案）→ 统一社会信用代码唯一校验（已存在非可重报状态主体 → 提示"该主体已注册"，不泄露已有账号信息）→ 建档（状态=待认证）→ 生成申请编号 → 流转留痕（无→待认证，触发方=申请人）→ 返回申请编号；驳回终态主体同代码重报 = 复用记录、更新可变信息、状态重置待认证并留痕（lofi Q3-A/Q4 裁决口径） | 行为 1 第 1/2/4 条 | 集成测试逐条对应行为 1 验收标准 4 条（含必填缺失逐字段提示、重复注册拒绝、无半成品档案） |
| B4 | **注册防重复提交**：应用服务方法标 `@Idempotent(key = "#req.uscc")`（common-idempotency 模式 B）——同信用代码重复请求返回首次结果，不重复建档；演示/单测走 memory 模式 | 行为 1 第 3 条；ADR-007 | 集成测试：同请求到达两次 → 一份档案、两次同响应；并发同代码 20 线程 → 恰一份 |
| B5 | **撤销重报**：待认证主体可撤销（校验当前状态，非待认证拒绝）→ 留痕（触发方=申请人）；撤销后重新注册 = 同一记录重报路径（B3 驳回重报同机制） | 行为 1 第 5 条 | 测试：待认证撤销成功+留痕；待审核/已入驻撤销被拒（1004C0001） |
| B6 | **进度查询**：按申请编号返回注册信息（联系电话脱敏展示：保留前 3 后 4）、当前状态、流转记录列表（前状态/后状态/触发方/时间/备注） | 行为 4 第 2 条；分级规范 | 测试：查询返回四要素齐全的流转记录；响应中电话为脱敏形态 |
| B7 | **状态机与流转服务**：状态枚举锁定规格 V1.0 最小集（PENDING_CERT/PENDING_REVIEW/ADMITTED/CERT_FAILED/REJECTED，中文对照见库表注释），**不增删**；一切流转经 `SubjectStatusService.transition()` 统一执行并强制落留痕；本包实际启用"注册→待认证"与"撤销留痕"，其余流转触发随 3.1.3/3.1.5 | 行为 4 第 1/2/3 条 | 单测：流转服务四要素落库断言；非法状态值无法构造（枚举封闭） |
| B8 | **错误码段 1004**（主体服务段，占用以 ADR-016 留痕）：新码见"错误码表"；既有 1000/1001/1002 段与 common-errorcode 组件零改动 | ADR-005；规格上游契约 | 封套断言：码值+对外文案+HTTP 映射（C/B→400）+不含内部细节 |
| B9 | **权限与审计**：端点标 `@RequirePermission`（subject.register / subject.read / subject.cancel），角色映射走配置（演示角色 applicant）；注册/撤销/越权访问记 `AuditRecorder` 审计 | 行为 5 第 3 条同源机制 | 测试：无权限调用 → 1000C0005 + 审计 DENIED 一条；正常注册 → 审计 SUCCESS |
| B10 | **依赖登记**：`spring-boot-starter-validation` 走"核验（官方注册表实测）→ 登记 docs/dependencies.md（审批栏注明 PO 预授权 + 本设计确认记录）→ 引入"（版本 Boot BOM 管，无需锁版）；其余依赖均有先例 | lofi 待确认 5 | 人工核对（文档级） |

## 库表设计（编码契约 = 本节定稿）

```sql
-- 库：ctds_subject（application-mysql.yml 配置，沿 kms 先例；口令只走 ${CTDS_DB_PASSWORD:}）

CREATE TABLE subject (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '技术主键',
  subject_no    VARCHAR(24)  NOT NULL COMMENT '申请编号（业务标识，S+日期+6位当日序号）',
  subject_name  VARCHAR(128) NOT NULL COMMENT '主体名称',
  uscc          VARCHAR(18)  NOT NULL COMMENT '统一社会信用代码（全平台唯一，唯一索引兜底）',
  subject_type  VARCHAR(16)  NOT NULL COMMENT '主体类型：ENTERPRISE企业/INSTITUTION机构/GOV政府部门',
  reg_address   VARCHAR(256) NOT NULL COMMENT '注册地址',
  contact_name  VARCHAR(64)  NOT NULL COMMENT '联系人姓名',
  contact_phone VARCHAR(32)  NOT NULL COMMENT '联系电话（演示期明文存储，展示层脱敏；L4 字段加密口径见 3.1.3）',
  admin_account VARCHAR(64)  NOT NULL COMMENT '管理员账号（V1.0 仅信息收集，不涉账号开通）',
  status        VARCHAR(20)  NOT NULL COMMENT '状态：PENDING_CERT待认证/PENDING_REVIEW待审核/ADMITTED已入驻/CERT_FAILED认证失败/REJECTED已驳回',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_subject_no (subject_no),
  UNIQUE KEY uk_uscc (uscc),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主体表（一行=一个主体，重报复用行不新建，见流转留痕）';

CREATE TABLE subject_status_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '技术主键',
  subject_id   BIGINT      NOT NULL COMMENT '主体 id（subject.id）',
  from_status  VARCHAR(20) NOT NULL COMMENT '流转前状态（注册建档为 NONE）',
  to_status    VARCHAR(20) NOT NULL COMMENT '流转后状态',
  trigger_role VARCHAR(16) NOT NULL COMMENT '触发方：APPLICANT申请人/SYSTEM系统/REVIEWER审核员',
  operator     VARCHAR(64) NOT NULL COMMENT '操作人标识（AuthContext.subject()）',
  remark       VARCHAR(256)          DEFAULT NULL COMMENT '备注（如撤销重报说明）',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '流转时间',
  KEY idx_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主体状态流转留痕（四要素+：前后状态/触发方/时间）';

CREATE TABLE subject_daily_seq (
  seq_date DATE    NOT NULL PRIMARY KEY COMMENT '序号日期',
  seq_val  INT     NOT NULL COMMENT '当日已发序号'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申请编号当日序号（行级原子自增）';
```

- **唯一性双保险**：`uk_uscc` 唯一索引兜底并发窗口 + 服务层预检（给出业务文案）。因重报复用行（lofi Q3-A），一行一个代码终身唯一，唯一索引与"驳回终态可重报"不冲突。
- **申请编号生成**：`S + yyyyMMdd + 6位序号`——`INSERT INTO subject_daily_seq ... ON DUPLICATE KEY UPDATE seq_val = seq_val + 1` 原子取号（MySQL 主流写法），左补零。
- **本包无 L4 字段**：证照影像/身份证号等 L4 字段随 3.1.3 建表；介质取舍结论 = **SM4 加密落库（LONGBLOB 密文列，复用 Sm4Service）**（lofi 待确认 2 采 A 后生效）。

## 接口契约（编码契约 = 本表定稿）

统一响应结构与封套沿 ADR-005（common-errorcode 全局映射，服务零改动）。

### REST 端点

| 端点 | 方法 | 权限 | 幂等 | 语义 |
| --- | --- | --- | --- | --- |
| `/api/v1/subject/registrations` | POST | `subject.register` | `@Idempotent(key="#req.uscc")` | 提交注册（B3/B4） |
| `/api/v1/subject/registrations/{subjectNo}` | GET | `subject.read` | 天然幂等 | 查询进度（B6；不存在 → 1000C0003） |
| `/api/v1/subject/registrations/{subjectNo}/cancellation` | POST | `subject.cancel` | 天然幂等（状态门槛兜底） | 撤销申请（B5） |

### 请求/响应（JSON）

**POST /registrations** 请求体（全部必填，`@Valid` + Jakarta Validation 注解逐字段校验）：

```json
{
  "subjectName": "示例数据科技有限公司",
  "uscc": "91330100MA27X8XXXX",
  "subjectType": "ENTERPRISE",
  "regAddress": "杭州市XX区XX路88号",
  "contactName": "张三",
  "contactPhone": "13800001234",
  "adminAccount": "admin001"
}
```

响应 data：`{ "subjectNo": "S20260913000001", "status": "PENDING_CERT" }`

**GET /registrations/{subjectNo}** 响应 data：

```json
{
  "subjectNo": "S20260913000001",
  "subjectName": "示例数据科技有限公司",
  "uscc": "91330100MA27X8XXXX",
  "subjectType": "ENTERPRISE",
  "regAddress": "杭州市XX区XX路88号",
  "contactName": "张三",
  "contactPhone": "138****1234",
  "status": "PENDING_CERT",
  "statusLogs": [
    { "fromStatus": "NONE", "toStatus": "PENDING_CERT", "triggerRole": "APPLICANT", "operator": "applicant-01", "remark": null, "createdAt": "2026-09-13T13:00:00" }
  ]
}
```

**POST /cancellation** 响应 data：`{ "subjectNo": "S20260913000001", "status": "PENDING_CERT", "cancelled": true }`（撤销留痕：from=to=PENDING_CERT，remark=申请人撤销，触发方=APPLICANT）

- 校验失败：`@Valid` 触发 → 统一返回 `1000C0001`，message 为逐字段原因拼接（如"统一社会信用代码格式不正确；联系电话不能为空"）；若 common GlobalExceptionHandler 未覆盖校验异常类型，在本服务内补 handler（复用 1000C0001），**common 组件零改动**。
- 重报路径（同 uscc 且状态=REJECTED）：POST /registrations 正常处理——更新可变字段（名称/地址/联系人/电话/管理员账号），状态重置 PENDING_CERT，流转留痕 remark="驳回后重新申请"。申请编号不变（复用行）。

## 错误码表（SubjectErrorCodes，新段 1004，9 位格式沿 1000~1003 段）

| 码 | 常量 | 类型→HTTP | 对外文案 | 触发场景 |
| --- | --- | --- | --- | --- |
| `1004B0001` | `SUBJECT_ALREADY_REGISTERED` | B→400 | 该主体已注册 | 信用代码已存在且当前状态不可重报（不泄露已有账号任何信息） |
| `1004C0001` | `SUBJECT_CANCEL_NOT_ALLOWED` | C→400 | 当前状态不可撤销 | 撤销时状态非待认证 |

- 其余复用 1000 段：1000C0001（参数/校验）、1000C0003（申请编号不存在）、1000C0002/1000C0005（未认证/无权限，common-auth）、1000S9999（内部错误兜底）。
- 全部经 `BizException` 抛出，对外文案为服务端常量，不回显输入、不暴露内部实现。

## 配置项（application.yml，沿 kms 先例）

| 配置 | 值/默认 | 说明 |
| --- | --- | --- |
| `spring.autoconfigure.exclude: DataSourceAutoConfiguration` | 默认 profile | 无库可启动（kms 先例）；`application-mysql.yml` 置空启用 |
| `spring.datasource.url` | `jdbc:mysql://localhost:3306/ctds_subject` | mysql profile；口令 `${CTDS_DB_PASSWORD:}` 零入库 |
| `ctds.auth.permissions.applicant` | `subject.register,subject.read,subject.cancel` | 演示角色映射（模式 A 配置式） |
| `ctds.idempotency.mode` | `memory`（演示）/`redis`（生产） | 注册幂等实现（ADR-007，引入方选择） |
| `management.endpoints.web.exposure.include: health` | — | 沿先例 |

## 边界值与异常行为

| 场景 | 行为 | 依据 |
| --- | --- | --- |
| 必填字段缺失/格式不合法 | 逐字段提示原因（1000C0001），不建任何档案 | 行为 1 验收-4 |
| 信用代码已存在（状态=待认证/待审核/已入驻/认证失败） | 拒绝：1004B0001"该主体已注册"，响应不含已有账号任何信息 | 行为 1 第 2 条 |
| 信用代码已存在（状态=已驳回） | 重报路径：复用记录、更新可变信息、状态重置待认证、留痕 | 行为 4 验收-2 + lofi Q3-A |
| 同一注册请求网络重试到达两次 | 幂等命中：返回首次结果，仅一份档案 | 行为 1 第 3 条（ADR-007 模式 B） |
| 并发同信用代码注册（幂等缓存过期窗口内直插） | `uk_uscc` 唯一键兜底 → 捕获冲突转 1004B0001（不暴露数据库细节） | 双保险设计 |
| 撤销非待认证主体 | 1004C0001"当前状态不可撤销" | B5 |
| 查询不存在的申请编号 | 1000C0003（不复用"已注册"文案，不泄露存在性） | 复用契约 |
| 无权限角色调用端点 | 1000C0005 + 审计 DENIED | 行为 5 第 3 条 |
| 取号/建库等存储异常 | 事务回滚，无半成品档案；S 型出站统一"系统繁忙" | 验证闭环 + 安全默认 |
| 驳回重报时管理员账号被修改 | 允许（重报=信息修正口径，可变字段集合含管理员账号） | B3 重报路径（如 PO 认为管理员账号不可变，签署时注明，实现即排除该字段） |

## 依赖清单

| 坐标 | 版本 | 范围 | 说明 |
| --- | --- | --- | --- |
| `com.ctds:common-errorcode/logging/auth/idempotency` | 2.0.0-SNAPSHOT | compile | 既有组件复用（错误码/审计/RBAC/幂等） |
| `org.springframework.boot:spring-boot-starter-web` | Boot 3.5.16 BOM | compile | 有先例（kms/example-service） |
| `org.springframework.boot:spring-boot-starter-validation` | Boot 3.5.16 BOM | compile | **新引入**：编码会话核验+登记（B10，PO 预授权口径） |
| `org.springframework.boot:spring-boot-starter-jdbc` / `flyway-core` / `flyway-mysql` / `mysql-connector-j` | Boot BOM / runtime | 有先例（kms） |
| `org.springframework.boot:spring-boot-starter-actuator` | Boot BOM | compile | 有先例 |
| `spring-boot-starter-test` / `testcontainers`(junit-jupiter, mysql) / `spring-boot-testcontainers` / `archunit-junit5` | test | 有先例（kms） |

## 规格缺口声明

1. 撤销后状态语义（lofi Q3-A 采 A 后已闭环为"复用重置"口径）；管理员账号是否可变：默认可变，PO 签署时可否决（边界表末行）。
2. 认证失败态再流转路径归 3.1.3 设计（lofi 缺口声明-2，不提前定义）。
3. 1004 段占用与 ADR-016 随编码固化（沿 ADR-015 惯例）。

## 问题确认：
（PO 签署本文件即视为 10 项行为清单 + 库表 + 接口契约 + 错误码表 + 边界值全部确认，lofi 五问视为随签署意见一并答复；如需修改在签署意见中列明）
