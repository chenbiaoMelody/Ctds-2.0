# ADR-017：DID 密钥管理与签发服务契约（错误码段 1005 / 库表 / 接口 / 触发衔接 / KMS 密钥对托管衔接）

| 字段 | 内容 |
| --- | --- |
| 编号 | ADR-017 |
| 状态 | **已采纳（2026-09-21；WBS-3.1.8 实施，编码契约 = `docs/designs/WBS-3.1.8-hifi.md` 已获 PO 确认签署）** |
| 日期 | 2026-09-21 |
| 关联任务 | WBS-3.1.8（DID 密钥管理与签发服务） |
| 关联规格 | `docs/specs/C-1.2-分布式数字身份DID.md` V1.0（行为 1 全 7 条规则 + 5 条验收标准；行为 4 写操作） |
| 关联设计 | `docs/designs/WBS-3.1.8-lofi.md`（方向，Q1~Q4 已裁决）/ `docs/designs/WBS-3.1.8-hifi.md`（编码契约） |
| 关联决策 | ADR-005（错误码顺延）、ADR-006（国密唯一入口）、ADR-007（幂等，本包以库表唯一键兜底）、ADR-008（did 域收口，互认归 3.1.10）、ADR-009（Flyway）、ADR-015（KMS 密钥托管唯一入口）、ADR-016（§6 衔接入口 = ADMITTED；§2.7 回环边界） |

## 1. 背景与目的

C-1.2 分布式数字身份（DID）的第一个实施包（WBS-3.1.8）新建独立服务 `services/did`，承担**签发**与**吊销/重签**两个写能力，并扩展 `services/kms` 以支持 SM2 密钥对托管与签名（私钥不出 KMS）。沿 ADR-015/016 先例，将编码期新占用的平台级契约（错误码模块位、库表、接口、触发衔接、KMS 密钥对托管衔接、内部服务身份头诚实边界）固化留痕，供下游包（3.1.9 解析与验证、3.1.10 互认、3.1.11 管理界面）遵守。

## 2. 决策内容

### 2.1 错误码模块位：DID 服务占用 1005 段

- 既有占用：1000 平台公共 / 1001 国密 / 1002 幂等锁 + KMS（双重占用，ADR-015）/ 1003 std-adapter / 1004 主体服务（ADR-016）。
- **DID 服务段 = 1005**（九位格式 `1005[CBS]nnnn`），全码表（`DidErrorCodes`）：
  - `1005C0001` DID_PARAM_INVALID（subjectNo 缺失/非法、reason 超长）
  - `1005C0002` DID_REVOKE_REASON_REQUIRED（吊销理由必填）
  - `1005C0003` DID_REVOKE_NOT_ACTIVE（非有效 DID 不可吊销）
  - `1005B0001` DID_NO_PENDING_ISSUANCE（未找到待签发记录）
  - `1005B0002` DID_NO_REVOKED_TO_REISSUE（无已吊销记录可重签）
  - `1005S0001` DID_ISSUANCE_INTERNAL_ERROR（签发内部失败；触发接口将其收敛为 PENDING_ISSUE 业务态，不直出——hifi §5）
- 复用 1000 段通用码（未认证 `1000C0002` / 无权限 `1000C0005` / 参数 `1000C0001`），`common-errorcode` 组件零改动。

### 2.2 库表契约（库 `ctds_did`，Flyway `V1__create_did_tables.sql`）

- `did_identity`（身份注册表，唯一事实源）：`subject_no`/`issuance_seq`（从 1 起，重签 +1，旧标识永不复用）/`did`/`status`（`PENDING_ISSUE` 待签发 / `ACTIVE` 有效 / `REVOKED` 已吊销）/`public_key_hex`/`key_ref`（管理面留痕，非公开）/`document_json`（公开要素，无私钥/L4）/`guard_key`（唯一性守卫列：非吊销行 = subject_no，吊销行 = NULL）。
- 唯一键：`uk_did (did)`、`uk_guard (guard_key)`——**一主体同期唯一有效/待签发身份**由 `uk_guard` 保证（MySQL 唯一索引允许多个 NULL，吊销行 guard_key=NULL 可累积）。
- `did_operation_log`（留痕）：`operation`（`ISSUE`/`REISSUE`/`REVOKE`）/`operator`/`reason`（仅 REVOKE）/`key_ref`（签发/重签填）/`status_from`/`status_to`/`occurred_at`——一表承载签发四要素与吊销五要素，nullable 列区分。
- **时间戳一律经应用时钟写入**（方法参数 now/occurredAt），不走 DB NOW()（DB-22"两把钟"教训）。
- **DID 服务不重复校验主体状态**：主体是否"已入驻"由触发方（subject-service）保证，单一事实源在主体服务（避免两服务复制状态机）。

### 2.3 DID 标识格式与文档结构

- **DID 标识**：`did:ctds:<主体申请编号>.<签发序号>`（例 `did:ctds:S20260920000001.1`）——可读、可追溯、唯一且单调；重签序号递增、旧标识永不复用。
- **DID 文档**（公开要素 JSON）：`did` / `publicKey`（`type=SM2`、`algorithm=sm2p256v1`、`valueHex=04‖X‖Y` 130 hex）/ `controller`（主体编号）/ `service`（`#resolution` → `/api/v1/did`）/ `created`——**不含**私钥、密钥引用、状态、任何 L4 信息。
- **对外 DID 状态集锁定两值**（有效/已吊销）；`PENDING_ISSUE` 是签发记录中间态，不属 DID 状态（规格行为 2 规则 1）。

### 2.4 接口契约（`services/did`，统一响应 `ApiResult`，鉴权复用 common-auth RBAC）

| 方法 | 路径 | 权限 | 语义 |
| --- | --- | --- | --- |
| POST | `/api/v1/did/issuances` | 无（内部触发，回环边界，登记诚实边界） | 自动签发：幂等；KMS 失败 → `status=PENDING_ISSUE`（业务答复，不抛 5xx） |
| POST | `/api/v1/did/subjects/{subjectNo}/issuance-retries` | `did.admin` | 运营重试（无待签发记录 → 1005B0001） |
| POST | `/api/v1/did/subjects/{subjectNo}/reissuances` | `did.admin` | 运营重签（无已吊销记录 → 1005B0002；新序号 + 全新密钥对 + 新 did） |
| POST | `/api/v1/did/{did}/revocation` | `did.admin` | 吊销（理由必填 1005C0002、非有效 1005C0003；即时生效、不可逆） |

- 解析/验证/互认/管理界面**不在本包**（3.1.9/3.1.10/3.1.11）；吊销写操作归属本包（Q4 已裁决，3.1.9 只读）。

### 2.5 触发衔接（ADR-016 §6 兑现）

- subject-service 审核通过（`ReviewService.approve`，`SubjectStatusService.transition` 事务已提交后）经 `DidIssuanceTrigger`（application）+ `DidIssuancePort`（domain 端口）+ `DidIssuanceClient`（infrastructure，JDK HttpClient，沿 `KmsKeyProvider` 先例）调用 DID 签发接口。
- **失败仅记 WARN 日志、不抛出、不影响批准响应与入驻状态**（行为 1 规则 5）；连接/读取超时取小值（连接 1s、读取 3s）。
- 配置 `ctds.did.issuance.base-url = ${CTDS_DID_ISSUANCE_BASEURL:}`；未配置 = 跳过触发（subject-service 可独立启动）。

### 2.6 KMS 密钥对托管衔接（ADR-015 补记落地）

- KMS 扩展 SM2 密钥对托管 + 内部签名（私钥不出 KMS）：`POST /api/v1/key-pairs`（`kms.admin`，生成密钥对、私钥 D 值经根密钥 SM4 信封落 `kms_key_version.material_cipher`，只返回公钥）、`POST /api/v1/key-pairs/{keyRef}/signatures`（内部签名面，回环边界，`{data: Base64}` → `{signature: Base64}` SM2 DER）。
- `kms_key` 表加列 `key_type`（默认 `SM4`）+ `public_key_hex`（Flyway `V2__add_sm2_key_pair.sql`）；SM2 不提供轮换（重签 = 新 key_ref，非版本轮换）。
- DID 服务的密钥对生成密钥编号约定：`did-<subjectNo>-<issuanceSeq>`（`[A-Za-z0-9._-]+` ≤64）。

### 2.7 内部服务身份头诚实边界

- DID → KMS 调用为内部回环，以服务身份头 `X-Ctds-Subject: did-service` + `X-Ctds-Roles: admin`（kms 映射 `admin → kms.admin`）访问密钥对创建端点。**真实边界 = 网络隔离（回环 127.0.0.1）+ ADR-016 §2.7 三件套（网关 HeaderStripFilter + 真实令牌）**；V1.0 演示期沿用"直连信任身份头"口径（与 ADR-016 §2.7 一致），不虚报已鉴权。

### 2.8 私钥零明文（三面锚定，行为 1 规则 3）

- **库表**：`did_identity`/`did_operation_log` 无私钥列（仅公钥 hex + 密钥引用）；KMS `kms_key_version.material_cipher` 存根密钥 SM4 信封（密文非明文）。
- **接口**：DID/KMS 接口响应无私钥/材料字段；KMS 无任何返回 SM2 私钥/材料的端点（`GET /material` 仅服务既有 SM4 数据密钥）。
- **日志**：密钥编号/公钥可入日志，私钥 D 值禁入（`Sm2KeyPair.toString()` 显式脱敏）。

## 3. 影响

- 后续包约束：3.1.9/3.1.10/3.1.11 不得新建平行错误码段/状态枚举/库表；解析/验证走 DID 文档公开要素 + 注册表当前状态；互认协议收口 std-adapter `did` 域（ADR-008）。
- 根 pom 模块表 +1（`services/did`）；端口分派：主体 8080 / KMS **8081** / DID **8082**，三者 mysql profile 均绑定 `server.address=127.0.0.1`。
- 演示注入方式（剧本 S1 步骤 5）：交付说明提供对同一主体重复调用 `POST /api/v1/did/issuances` 的重放命令（幂等 → 仍只见一条有效 DID），不新增演示专用代码路径。

## 4. 备选方案与取舍（结论均引自 lofi Q1~Q4 裁决与 hifi，无新增裁决）

- 服务形态：A. 独立 `services/did`（**采**，Q1）；B. 并入 subject-service（**不采用**——DID 注册表独立库、独立生命周期，混入主体服务违反单一职责）。
- 端口：A. 8080/8081/8082 全回环（**采**，Q2）；B. 三服务共享 8080（**不采用**——不能并存运行，是 S1 演示硬约束）。
- 触发时点：A. 审核事务提交后调用、失败留"待签发"可重试（**采**，Q3）；B. 审核事务内同步调用（**不采用**——拉长审核事务、KMS 抖动拖垮审核）。
- 吊销归属：A. 写操作归 3.1.8（**采**，Q4）；B. 归 3.1.9（**不采用**——WBS 字面 3.1.9 仅含"吊销状态检查"）。
- 幂等载体：A. 库表唯一键 `uk_guard` + 应用层"存在即返回/就地重试"双保险（**采**）；B. 分布式锁组件（**不采用**——单一事实源在库表，无需额外中间件）。

## 5. 理由与业务影响说明

- **理由**：DID 注册表是跨空间身份互认的"身份事实源"，其标识格式、状态集、库表、错误码段必须集中固化，否则 3.1.9~3.1.11 各自发散将造成身份凭证不可信；私钥托管唯一入口（KMS）+ 私钥零明文是 L4 身份凭证（分级规范行 144：身份凭证被冒用 = 主体被冒名）的底线。
- **业务影响说明**（业务可读）：主体审核通过即自动获得一个"数字身份证"，全程无需申请人操作；吊销理由必填且不可逆，身份凭证疑似泄露可立即作废并留痕可溯；重签换新证、旧证记录不删，审计链完整。

## 6. 影响范围

- 代码：新增 `services/did`（四层）；`services/kms`（SM2 密钥对托管 + 签名端点 + Flyway V2 + 端口 8081/回环）；`services/subject-service`（`DidIssuanceTrigger`/`DidIssuanceClient`/`DidIssuancePort` + `ReviewService.approve` 挂钩 + 配置）；根 pom 模块表 +1。
- 文档：ADR-015 补记（SM2 托管 + 签名 + 8081 回环）、ADR-016 §2.7 补记（KMS 8081、DID 8082 回环落实状态）、台账 DB-03 注记更新（kms 回环落实情况）。

## 7. 可替换性

- KMS 密钥对托管为 ADR-015 扩展，调用方只见 `DidKmsClient` 端口（生成密钥对返回公钥）——未来换商业 KMS 或信封加密模式，替换 `DidKmsHttpClient` 实现，DID 业务代码零改动。
- DID 注册表访问收敛 `DidRepository` 端口，MySQL 实现可替换；链上/分布式账本承载演进（规格 §7 Q2 弃项）如需采用，走技术栈变更流程。
- 触发衔接收敛 `DidIssuancePort` 端口，DID 服务契约变更不影响 subject-service 业务三层。
