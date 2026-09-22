# WBS-3.1.8 DID 密钥管理与签发服务 · 高保真设计

- 型态：**非界面类**（任务卡已标注）
- 对应规格：`docs/specs/C-1.2-分布式数字身份DID.md`（V1.0 已确认）——**行为 1 全 7 条规则 + 5 条验收标准**；**行为 4 写操作**（吊销/重签，Q4 已裁决归本包）
- 任务卡：WBS-3.1.8 ｜ 方向已确认（`docs/designs/WBS-3.1.8-lofi.md` 确认记录节：Q1~Q4 均采建议口径，2026-09-20）
- 编码契约：本表定稿并经 PO 确认后，任何与本表不一致的实现 = 打回项（章程 2.6）
- 关联设计：`docs/designs/WBS-3.1.8-lofi.md`（方向）；规格行为 1/2/4；ADR-015/016/005/006/007/008/009/010

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | **确认** | 项目主导者（兼任 PO；会话回复"**确认**"，本表由 AI 按该声明代录留痕，沿 WBS-3.1.2/3.1.6/3.1.7 先例） | 2026-09-20 | 无 |

**签署效力**：本表即**编码契约**——实现必须与本表逐条一致，偏离即打回项（章程 2.6.1 铁律③）。编码在新会话冷启动，必读本文件；实施前置检查项见 §10。

---

## 1. 总体方案（承接 lofi 裁决）

新建独立服务 **`services/did`**（artifactId `did-service`，包 `com.ctds.did`，四层结构），承担**签发**与**吊销/重签**两个写能力；`services/kms` 扩展**SM2 密钥对托管 + 签名**（私钥不出 KMS）；`services/subject-service` 在审核通过（已入驻）**事务提交后**触发签发。端口分派：主体 8080 / KMS **8081** / DID **8082**，三者 mysql profile 均绑定 `server.address=127.0.0.1`。错误码：DID 服务占 **1005 段**。零新增第三方依赖。

## 2. 数据结构（库 `ctds_did`，Flyway `V1__create_did_tables.sql`）

### 2.1 did_identity（身份注册表；唯一事实源）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT AI PK | 主键 |
| subject_no | VARCHAR(32) NOT NULL | 关联主体申请编号（绑定主体） |
| issuance_seq | INT NOT NULL | 签发序号，从 1 起；吊销后重签 +1（旧标识永不复用） |
| did | VARCHAR(128) NULL | DID 标识；待签发行为 NULL（尚无标识） |
| status | VARCHAR(16) NOT NULL | 记录状态：`PENDING_ISSUE`（待签发）/`ACTIVE`（有效）/`REVOKED`（已吊销）。**对外 DID 状态只映射两值**（ACTIVE→有效、REVOKED→已吊销）；PENDING_ISSUE 是签发记录中间态，不属 DID 状态（规格行为 2 规则 1） |
| public_key_hex | VARCHAR(130) NULL | SM2 公钥非压缩点 hex（130 字符，`04‖X‖Y`） |
| key_ref | VARCHAR(64) NULL | KMS 密钥引用（**管理面留痕用，非公开**；公开文档不含） |
| document_json | VARCHAR(2048) NULL | DID 文档（公开要素 JSON，见 §2.3）；不含私钥、密钥引用、L4 信息 |
| guard_key | VARCHAR(32) NULL | **唯一性守卫列**：非吊销行 = `subject_no`，吊销行 = `NULL` |
| created_at / updated_at | DATETIME NOT NULL | 时间戳（经应用时钟写入） |

- 唯一键：`UNIQUE uk_did (did)`、`UNIQUE uk_guard (guard_key)`。
- **一主体同期唯一有效/待签发身份**由 `uk_guard` 保证：同一 `subject_no` 的非吊销行（待签发或有效）至多一条（MySQL 唯一索引允许多个 NULL，吊销行 guard_key=NULL 不受限，历史吊销行可累积）。
- **DID 服务不重复校验主体状态**：主体是否"已入驻"由触发方（subject-service）保证，单一事实源在主体服务——避免两服务复制状态机（该口径登记于 ADR-017）。

### 2.2 did_operation_log（留痕）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT AI PK | 主键 |
| did | VARCHAR(128) NULL | DID 标识（待签发重试前可为 NULL） |
| subject_no | VARCHAR(32) NOT NULL | 关联主体 |
| operation | VARCHAR(16) NOT NULL | `ISSUE` / `REISSUE` / `REVOKE` |
| operator | VARCHAR(128) NOT NULL | 触发方：`SYSTEM`（入驻自动）或运营管理员 |
| reason | VARCHAR(256) NULL | 吊销理由（仅 REVOKE） |
| key_ref | VARCHAR(64) NULL | 密钥引用（签发/重签时填；吊销不填） |
| status_from / status_to | VARCHAR(16) NULL | 状态变更（吊销：ACTIVE→REVOKED；重签留 REISSUE 语义） |
| occurred_at | DATETIME NOT NULL | 发生时间（经应用时钟） |

- 签发留痕四要素 = occurred_at + subject_no + did + key_ref；吊销留痕五要素 = operator + occurred_at + reason + did + 状态变更。一表承载，nullable 列区分。

### 2.3 DID 文档（公开要素，无 L4）

```json
{
  "did": "did:ctds:S20260920000001.1",
  "publicKey": { "type": "SM2", "algorithm": "sm2p256v1", "valueHex": "04…（130 hex）" },
  "controller": "S20260920000001",
  "service": [ { "id": "#resolution", "type": "DidResolution", "serviceEndpoint": "/api/v1/did" } ],
  "created": "2026-09-20T20:30:00"
}
```

- **DID 标识格式**：`did:ctds:<主体申请编号>.<签发序号>`（例 `did:ctds:S20260920000001.1`）——可读、可追溯、唯一且单调；重签序号递增、旧标识永不复用。
- 文档**不含**：私钥、密钥引用（key_ref）、状态（状态由注册表当前值随解析返回，行为 2 规则 4 依赖此口径）、任何 L4 信息。`serviceEndpoint` 为解析入口约定路径，3.1.9 交付后生效。

### 2.4 KMS 扩展（Flyway `V2__add_sm2_key_pair.sql`）

`ALTER TABLE kms_key ADD COLUMN key_type VARCHAR(8) NOT NULL DEFAULT 'SM4', ADD COLUMN public_key_hex VARCHAR(130) NULL;`
- 复用 `kms_key`（描述符 + 类型 + 公钥）与 `kms_key_version`（`material_cipher` 存私钥 D 值的根密钥 SM4 信封，长度满足 VARCHAR(128)）。SM2 不提供轮换（重签 = 新 key_ref，非版本轮换）。

## 3. 行为清单（逐条对应规格验收标准 + 计划测试）

| 编号 | 行为（业务可读） | 规格出处 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 主体审核通过变"已入驻"后，subject-service 在**事务提交后**调用 DID 触发接口；DID 服务登记待签发 → 经 KMS 生成 SM2 密钥对（私钥只存 KMS）→ 组 DID 文档 → 转有效 + 留痕四要素。待认证/待审核/已驳回/认证失败主体不触发（由触发方状态机保证） | 行为 1 规则 1、2、7；验收标准 1、4 | 集成：审核通过→did_identity 存在 ACTIVE 行、四要素齐、kms key_pair 已建；驳回不触发（client mock 断言零调用） |
| B2 | **幂等**：重复触发不产生第二个有效 DID；唯一守卫在库表层（`uk_guard`）与应用层（存在即返回既有/就地重试）双保险 | 行为 1 规则 6；验收标准 2 | 集成：同主体触发两次→恰一条 ACTIVE；**反向探针**：直插第二条非吊销行→唯一键拒绝 |
| B3 | **私钥零明文**：库表/接口响应/日志三处不含明文私钥。KMS 无任何返回私钥材料的端点（私钥只在 KMS 内部签名）；DID 文档/记录只出现公钥与密钥引用 | 行为 1 规则 3；验收标准 3 | 接口面：key-pairs 各响应字段集断言（无私钥/材料字段，且不存在取私钥接口）；库表面：签发后扫 did_identity/did_operation_log/kms_key_version 全文本列无 64 位小写 hex 私钥样式 + **反向探针**（植入 64-hex→断言变红） |
| B4 | **失败可重试**：KMS 不可用等导致签发失败→主体状态不受影响、DID 记录停"待签发"；运营重试成功后补齐留痕 | 行为 1 规则 5；验收标准 5 | 集成：KMS 客户端指向不可达/返回失败→记录 PENDING_ISSUE、审核批准仍成功；重试→ACTIVE + 留痕 |
| B5 | **吊销**（`did.admin`）：理由必填（非空白，≤256）→ ACTIVE 转 REVOKED、guard_key 释放、留痕五要素、即时生效；**不可逆**（不提供恢复端点）；无"待吊销"中间态 | 行为 4 规则 1~4；验收标准 4 条 | 集成：理由空→1005C0002；确认→REVOKED+留痕五要素+guard 释放；非 ACTIVE→1005C0003；**不存在**恢复操作 |
| B6 | **重签**（`did.admin`）：对已吊销主体重签→新 issuance_seq + **全新密钥对**（新 key_ref）+ 新 did；旧 did 与记录保留可追溯 | 行为 4 规则 4 | 集成：重签→新 did/新 key_ref/序号+1、旧行仍 REVOKED 保留；无已吊销记录→1005B0002 |
| B7 | **主体服务触发衔接**：`ReviewService.approve` 末尾（事务提交后）经 `DidIssuanceTrigger` 调用 DID；触发失败仅记日志、不改变批准响应与入驻状态 | 行为 1 规则 5；ADR-016 §6 | 集成：批准→client mock 收到 subjectNo；client 抛异常→批准仍 200、状态仍 ADMITTED |

## 4. 接口契约表

### 4.1 DID 服务（`services/did`，统一响应 `ApiResult`，错误码段 1005；鉴权复用 common-auth RBAC）

| 方法 | 路径 | 权限 | 入参 | 出参（data） | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/did/issuances` | 无（内部触发，回环网络边界；登记诚实边界） | `{subjectNo(必填), subjectName?, subjectType?}` | `{did?, status, keyRef?, issuedAt?}`——已有 ACTIVE→返回既有；已有 PENDING→就地重试；KMS 失败→`status=PENDING_ISSUE`（业务答复，不抛 5xx） | 1005C0001（subjectNo 缺失/非法） |
| POST | `/api/v1/did/subjects/{subjectNo}/issuance-retries` | `did.admin` | 路径 subjectNo | `{did, status, keyRef, issuedAt}` | 1005B0001（无待签发记录）、1000C0002/1000C0005（鉴权） |
| POST | `/api/v1/did/subjects/{subjectNo}/reissuances` | `did.admin` | 路径 subjectNo | `{did, status, keyRef, issuedAt}` | 1005B0002（无已吊销记录可重签） |
| POST | `/api/v1/did/{did}/revocation` | `did.admin` | `{reason(必填)}` | `{did, status:"REVOKED", revokedAt}` | 1005C0002（理由空）、1005C0003（非有效状态） |

- **演示注入方式（剧本 S1 步骤 5）**：交付说明提供"对同一主体重复调用 `POST /api/v1/did/issuances`"的重放命令（PowerShell `Invoke-RestMethod` 一行）；幂等 → 管理页仍只见一条有效 DID。**不新增演示专用代码路径**（最小实现、零新增攻击面）。
- 解析/验证/互认/管理界面**不在本包**（3.1.9/3.1.10/3.1.11）。

### 4.2 KMS 扩展（`services/kms`）

| 方法 | 路径 | 权限 | 入参 | 出参（data） | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/key-pairs` | `kms.admin` | `{keyRef}`（格式 `[A-Za-z0-9._-]+`≤64） | `{keyRef, publicKeyHex, createdAt}` | 1002B0001（编号已存在）、1002C0001 |
| POST | `/api/v1/key-pairs/{keyRef}/signatures` | 无（内部签名面，回环边界） | `{data: Base64}` | `{keyRef, signature: Base64}`（SM2 DER 签名） | 1002B0002（编号不存在）、1002C0001 |

- **私钥不出 KMS**：不提供任何返回 SM2 私钥/材料的端点（`GET /material` 仅服务既有 SM4 数据密钥，不覆盖 SM2 密钥对）。

### 4.3 subject-service 触发衔接

- 新增 `DidIssuanceTrigger`（application）+ `DidIssuanceClient`（infrastructure，JDK `HttpClient`，沿 `KmsKeyProvider` 先例）；配置 `ctds.did.issuance.base-url: ${CTDS_DID_ISSUANCE_BASEURL:}`。
- 落点：`ReviewService.approve(...)` 末尾（`statusService.transition` 的事务已提交后）调用；**失败仅记 WARN 日志**、不抛出、不影响批准响应；连接/读取超时取小值（连接 1s、读取 3s，同 `KmsKeyProvider` 先例）避免拖慢审核动作。

## 5. 错误码（1005 段，DID 服务 domain 常量类，ADR-017 登记留痕）

| 码 | 类型 | 含义 |
| --- | --- | --- |
| 1005C0001 | C | 参数不合法（subjectNo 缺失/非法、reason 超长） |
| 1005C0002 | C | 吊销理由必填 |
| 1005C0003 | C | 状态门槛（非有效 DID 不可吊销） |
| 1005B0001 | B | 未找到待签发记录 |
| 1005B0002 | B | 无已吊销记录可重签 |
| 1005S0001 | S | 签发内部失败（KMS 不可达等；对外由触发接口收敛为 PENDING_ISSUE 业务态，不直出） |

- 复用 1000 段（未认证 `1000C0002`、无权限 `1000C0005`、参数 `1000C0001`）；KMS 复用 1002 段。`common-errorcode` 组件零改动，占段走 ADR-017 留痕（沿 ADR-015/016 先例）。

## 6. 边界值与异常行为

- subjectNo：非空、长度 ≤32；缺失/非法 → 1005C0001（400）。
- reason（吊销）：非空白、≤256；空/纯空白 → 1005C0002（400）；超长 → 1005C0001。
- keyRef（KMS）：`[A-Za-z0-9._-]+` ≤64，重复 → 1002B0001。
- 重复触发：幂等（返回既有/就地重试），不报错、不重复建行。
- 对非 ACTIVE DID 吊销 → 1005C0003；重签但无已吊销记录 → 1005B0002；重试但无待签发记录 → 1005B0001。
- 未认证/无权限 → common-auth 统一 401/403（1000C0002/1000C0005），不留业务留痕。
- KMS 不可达 → 触发接口返回 `status=PENDING_ISSUE`（业务答复），不抛 5xx；DID 服务自身日志记录原因。
- 并发：同一主体并发触发/重签由 `uk_guard` 兜底（唯一键拒绝重复非吊销行），败者收敛为"幂等返回"或 1005S0001。

## 7. 配置项

| 服务 | 键 | 值 | 说明 |
| --- | --- | --- | --- |
| kms | `server.port` / `server.address`（mysql） | `8081` / `127.0.0.1` | 端口分派 + 回环（ADR-016 §2.7 落实，前置检查项） |
| kms | `CTDS_KMS_ROOT_KEY` | 环境变量 | 既有根密钥（不变） |
| did | `server.port` / `server.address`（mysql） | `8082` / `127.0.0.1` | 回环 |
| did | `CTDS_DB_URL/USER/PASSWORD` | 库 `ctds_did` | 独立库 |
| did | `ctds.auth.permissions.admin` | `did.admin` | 运营管理员权限 |
| did | `ctds.did.kms.base-url` | `${CTDS_DID_KMS_BASEURL:}` | DID→KMS 调用 |
| subject | `ctds.did.issuance.base-url` | `${CTDS_DID_ISSUANCE_BASEURL:}` | 触发签发调用 |

## 8. 测试计划与达标线

- 行为清单 B1~B7 全覆盖（单测 + Testcontainers 集成，ADR-010：`*Test` 命名、显式镜像 `mysql:8.0`、`disabledWithoutDocker`）；**每处"库表/接口/日志"安全断言配反向探针**（删实现必变红，沿 DB-05/DB-20 先例）。
- 核心模块（`did`）行覆盖 ≥80%（章程 4.2；gates-config `coreModules` 已含 `did`）。
- 门禁：`mvn -B -ntp compile/test/checkstyle:check` 全绿；前端不受影响。
- 私钥零明文的三面锚定见 B3。

## 9. 变更影响声明

| 对象 | 变更 |
| --- | --- |
| ADR | **新增 ADR-017《DID 服务契约》**（库表/接口/1005 段/触发衔接/安全边界）；**ADR-015 补记**（SM2 密钥对托管 + 签名端点 + 端口 8081 回环）；**ADR-016 §2.7 补记**（KMS 端口 8081、DID 8082 回环落实状态） |
| 验收剧本 | **不改正文**；S1 步骤 5 注入方式由本包交付说明提供（剧本已预留该口径）；S1 步骤 2/3/5 由本包承载（步骤 4 展示层、步骤 6 前置数据） |
| 台账 | DB-03 注记随实施更新（kms 回环落实情况）；本包新增债务零预期（如发现按纪律登记） |
| 追溯矩阵 | DB-14 未建，本包按"验收标准→测试→剧本步骤"映射表随交付说明提供 |

## 10. 实施前置检查项（进入编码前完成）

1. `services/kms` 补 `server.port: 8081` + mysql profile `server.address: 127.0.0.1`，重建构件。
2. 实测 KMS **默认 profile** 能否启动（既有疑点：排除 DataSourceAutoConfiguration 但 `KeyJdbcRepository` 构造器依赖 `JdbcClient`）——结论如实登记；演示与服务一律 mysql profile。
3. 复核 `ReviewService.approve` 事务边界（本表 §4.3 落点前提：transition 事务已提交），若实现会话发现偏差按 §4.3 语义调整并补测。