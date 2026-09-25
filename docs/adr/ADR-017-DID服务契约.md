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
- `did_operation_log`（留痕）：`operation`（`ISSUE`/`REISSUE`/`REVOKE`）/`operator`/`reason`（仅 REVOKE）/`key_ref`（签发/重签填）/`status_from`/`status_to`/`occurred_at`——一表承载签发四要素与吊销五要素，nullable 列区分（另有物理索引 `idx_did_op_log_subject`，仅查询性能、无行为差异）。
- **时间戳一律经应用时钟写入**（方法参数 now/occurredAt），不走 DB NOW()（DB-22"两把钟"教训）；**DID 文档与留痕时间固定为秒级 ISO-8601**（应用时钟截断到秒，与 hifi §2.3 示例口径一致）。
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

- 解析/验证端点由 **3.1.9 交付**（契约见 §2.9）；互认/管理界面**不在本 ADR**（3.1.10/3.1.11）；吊销写操作归属本包（Q4 已裁决，3.1.9 只读）。

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
- **内部无鉴权面的后果表征（评审② P2 补记，2026-09-21）**：`POST /api/v1/did/issuances`（无权限注解）与 `POST /api/v1/key-pairs/{keyRef}/signatures`（内部签名面）在回环边界内**无身份门槛**——本机任意进程可为任意主体编号铸造身份、可为任意 DID 密钥请求签名（**签名能力可外借，但私钥本身不可导出**）。故"私钥不出 KMS"**不等于**"身份密钥不可被滥用"：前者的实现级保证（信封托管 + 材料路径 `key_type` 门槛）已落地；后者的解除条件是 **网关 HeaderStripFilter + 真实令牌生效 + 对签发面/签名面补服务间鉴权**（后者登记为 3.5.2/3.9.1 兑现项）。演示机须为可信机器、演示期间不在其上运行不可信程序（沿 ADR-016 §2.7 残余口径）。

### 2.8 私钥零明文（三面锚定，行为 1 规则 3）

- **库表**：`did_identity`/`did_operation_log` 无私钥列（仅公钥 hex + 密钥引用）；KMS `kms_key_version.material_cipher` 存根密钥 SM4 信封（密文非明文，KMS 集成测试以根密钥解信封断言得 32 字节 D 值）。
- **接口**：DID/KMS 接口响应无私钥/材料字段；KMS 无任何返回 SM2 私钥/材料的端点——**由代码门槛强制**（非注释断言）：`KeyManagementService` 的材料读取与轮换路径经 `requireDataKey` 校验 `key_type`，非 `SM4` 一律拒（1002C0001），故既有 `GET /api/v1/keys/{keyRef}/material` 与 `/rotations` **不覆盖 SM2 密钥对**（KMS 集成测试含回归锚点 + 合法 SM4 数据密钥的反向探针）。
- **日志**：密钥编号/公钥可入日志，私钥 D 值禁入（`Sm2KeyPair.toString()` 显式脱敏）。

### 2.9 解析与验证契约（WBS-3.1.9 补记，2026-09-22）

**接口契约**（`services/did` 扩展，统一响应 `ApiResult`）：

| 方法 | 路径 | 权限 | 语义 |
| --- | --- | --- | --- |
| GET | `/api/v1/did/{did}` | 无（只读公开要素，回环边界） | 解析：文档公开要素 + 状态（仅 `ACTIVE`/`REVOKED` 两值）；未登记 → 1005B0003 明确业务答复；解析不留痕 |
| POST | `/api/v1/did/{did}/verifications` | 无（对外验证能力，回环边界） | 验证三查（签名/状态/绑定）：`{data, signature}`（Base64）→ `{did, result, reason, verifiedAt}`；每次验证落痕（含未登记/不可用） |

- **错误码（1005 段顺延，ADR-006 §3.5；评审③P2-1 更正：原引"ADR-005 §3.5"有误，ADR-005 为错误码体系基线）**：`1005B0003`（解析目标未登记，400）、`1005C0004`（验证参数不合法：data/signature 缺失、非法 Base64、空内容或超上限，400）、`1005S0002`（验证内部错误：注册表读取/文档损坏/验签过程非输入类故障，500）。
- **验证结论枚举**（验证结论，**非 DID 状态枚举**——§2.3 两值口径不受影响）：`PASS` / `FAIL`（reason ∈ `SIGNATURE_INVALID` / `REVOKED` / `SUBJECT_BINDING_FAILED` / `NOT_REGISTERED`）/ `UNAVAILABLE`（reason = `BINDING_UNAVAILABLE`，**系统态不冒充"验证不通过"**）。
- **库表**：`ctds_did` Flyway `V2__create_verification_log.sql` 新增 `did_verification_log`（`did` / `result` / `reason` / `occurred_at`）；**不存业务数据原文**（验证通道不是数据存储通道，行为 3 规则 3）。
- **验签**经 common-crypto `Sm2Service.verify` 唯一入口（公钥 = 注册表 130-hex `04‖X‖Y`；签名 = DER，与 KMS 签名面同构）。

**主体绑定核验衔接（服务间，Q2 落地）**：

- subject-service 新增**内部只读端点** `GET /api/v1/subject/internal/subjects/{subjectNo}/admission`——仅返回 `{subjectNo, status}` 两字段（最小暴露）；功能级门槛 = **服务身份专用权限点 `subject.internal.read`**（**评审②P2-1 收敛**：仅 `did-internal: subject.internal.read` 持有，申请人/审核员均不持有，二者访问一律 403；不冒充 reviewer/applicant）；**不落对象级归属断言**。
- **实施修正留痕（2026-09-22 前置检查②）**：原定复用既有 `GET /registrations/{subjectNo}` 不可行——该端点带防枚举归属断言（ADR-016 §2.6，仅"本人或持 `subject.review`"可读）；给 `did-internal` 追加 `subject.review` 会同时解锁审核端点（误授"审批主体"能力，安全面扩大），故以新增内部端点落地。细节见 `docs/designs/WBS-3.1.9-hifi.md` §5。
- did 侧配置 `ctds.did.subject.base-url`（`application.yml` 显式声明为 `${CTDS_DID_SUBJECT_BASEURL:}`，与 `kms.base-url` 同形——占位符按环境变量名精确匹配，不依赖 `@Value` 松散绑定；评审②P3-1） ；未配置/不可达/超时/**非 200（即使 body 形似成功）**/响应不可解析/其他业务错误 → `UNAVAILABLE`；业务码 `1000C0003`（主体编号不存在）= `SUBJECT_BINDING_FAILED`（绑定不成立，非"不可用"）。**不复制主体状态到 DID 库**（单一事实源在 subject-service，§2.2 口径）。

**内部无鉴权面诚实边界（补 §2.7 口径）**：`GET /api/v1/did/{did}` 与 `POST /api/v1/did/{did}/verifications` 在回环边界内**无身份门槛**——本机任意进程可解析任意 DID、可发起任意验证请求；验证为只读判定 + 留痕（无状态写入、结论不冒充授权，行为 3 规则 4）。解除条件同 §2.7（网关 HeaderStripFilter + 真实令牌 + 服务间鉴权，3.5.2/3.9.1 兑现项）；演示机须为可信机器。

**后续包约束重申**（与 §3 一致）：3.1.10/3.1.11 不得新建平行错误码段/状态枚举/库表；解析/验证复用本契约定形。

## 3. 影响

- 后续包约束（解析/验证契约见 **§2.9**，本包 3.1.9 已交付）：3.1.10/3.1.11 不得新建平行错误码段/状态枚举/库表；互认协议收口 std-adapter `did` 域（ADR-008）。
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

## 8. 变更补记（WBS-3.1.10，2026-09-25）

- **互认端点契约（`services/did` 扩展，前缀 `/api/v1/did-interop`）**：来访验证 `POST /inbound-verifications`（入参 `peerSpace` / `did` / `data` / `signature`，Base64）、出向验证 `POST /outbound-verifications`（入参 `did` = 本空间 DID）、样例清单 `GET /samples`（只读）。出参与错误码见 `docs/designs/WBS-3.1.10-hifi.md` §2/§4。
- **错误码零新增**：复用 `1005C0004`（互认入参不合法）/ `1005S0002`（互认内部错误）；"该标准互联功能尚未开放"（`1003C0001`）属 std-adapter 骨架期占位语义，本包交付后 did 域已开放，占位答复契约保留在 std-adapter（ADR-008 §9）。
- **库表**：`ctds_did` 新增 **V3 迁移** `did_interop_log`（方向 / 对端空间标识 / DID / 结果 / 原因 / 时间；**不保存业务数据原文**；不改 3.1.9 的 `did_verification_log`）。满足"不得新建平行库表"约束：本表为互认独立业务留痕表，不与解析/验证契约重叠。
- **状态枚举零新增**：出向验证读取本空间状态经 std-adapter 的 `LocalDidStatusPort`（`LocalDidStatus{registered, effective}` 业务口径两布尔表达），端口实现**进程内委托** §2.9 解析能力（无 HTTP 自调用、不复制状态）；未新增 DID 状态枚举。
- **留痕口径**：业务结论（通过 / 不通过 / 不可用）一律留痕（方向 + 对端空间标识 + DID + 结果 + 原因 + 时间）；**输入类拒绝与内部错误不留痕**；超长入参（`peerSpace` > 64、`did` > 128）按输入类拒绝（防落库异常）。
- **系统态诚实表征**：互认通道取数异常（含本空间 DID 文档损坏、端口异常）→ `UNAVAILABLE` + `BINDING_UNAVAILABLE` 并留痕，**不冒充"验证不通过"**。
- **诚实边界（沿 §2.7/§2.9 口径）**：互认三端点演示期**无鉴权**——调用方为对端系统，演示期回环网络隔离是真实边界；解除条件同 §2.7（网关 HeaderStripFilter + 真实令牌 + 服务间鉴权）；**本包为"业务口径互认 + 模拟对端"，非信通院协议实现**（真实协议对接归 C-9.1）。
- **依赖**：`services/did` 新增 `com.ctds:std-adapter` 内部模块依赖（互认协议实现收口唯一落点，ADR-008 §3.1）；无新第三方坐标。

## 9. 变更补记（WBS-3.1.11 DID 管理界面，2026-09-25）

> 编码契约 = `docs/designs/WBS-3.1.11-hifi.md`（Q1~Q8 一次确认，体量 D1 不拆分）。本节固化本包新占用/新澄清的契约，供下游包遵守；**§3 后续包约束（不得新建平行错误码段/状态枚举/库表）在本包继续有效并已由测试守卫兑现**。

**管理面读数端点（`services/did` 扩展，前缀 `/api/v1/did`，一律 `did.admin`）**：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/records` | 签发记录列表：分页（`pageNum`/`pageSize`，复用 `common-pagination`，越界 `1000C0001`）+ `subjectNo`（≤32）/`status`（∈既有三值）过滤；最新签发在前（`created_at DESC, id DESC`）；无匹配返回空列表（**非错误**） |
| GET | `/records/{did}/operation-logs` | 单 DID 操作留痕（**不分页**：写入点仅签发/重签与吊销，同一 DID 至多两行），时间正序；未登记 → `1005B0003` |
| GET | `/verification-logs` | 验证留痕列表：分页 + 可选 `did`（≤128）过滤，最新在前；**只含时间/DID/结果/原因，无数据原文** |
| POST | `/{did}/demo-signatures` | 演示代签：入参 `{data}` = 待签原文（1~1024 字符），出参 `{did, data(原文 Base64), signature(SM2 DER Base64), signedAt}`；**配置门槛默认关闭** |

- **出参视图字段即契约**：`DidRecordView{subjectNo, issuanceSeq, did, status, keyRef, createdAt, updatedAt}`（`did`/`keyRef` 在待签发记录为 `null`）、`DidOperationLogView{operation, operator, reason, keyRef, statusFrom, statusTo, occurredAt}`、`VerificationLogView{did, result, reason, occurredAt}`、`DemoSignatureView{did, data, signature, signedAt}`。各视图**不含**私钥、公钥 hex、文档原文（行为 1 规则 3）。
- **记录状态展示口径**：管理面列表复用 `DidStatus` 三值并对 `PENDING_ISSUE` 显式标注"待签发（记录中间态）"；**解析端点对外口径仍锁 `ACTIVE`/`REVOKED` 两值**（§2.3 不受影响）。
- **演示签名入口边界（规格 §6 第 6 条）**：配置 `ctds.did.demo-signature.enabled`（`CTDS_DID_DEMO_SIGNATURE_ENABLED`，**默认 false = 生产禁用**）；关闭时返回既有 `1000C0003`（**不新增错误码**）且**不产生任何 KMS 调用**、不区分目标是否存在（不构成状态枚举通道）。开启期为演示/调试专用：签名经 KMS 内部签名面 `POST /api/v1/key-pairs/{keyRef}/signatures`（§2.6），私钥不出 KMS；**原文与签名不入库、不落日志**（仅 WARN 级记录 DID + 密钥引用 + 结果）。
- **错误码与库表**：**零新增错误码**（复用 `1000C0001`/`1000C0003`/`1005B0001`/`1005B0003`/`1005C0001`/`1005S0002` 与鉴权 `1000C0002`/`1000C0005`）；**零新增库表与迁移**（读数复用 `did_identity`/`did_operation_log`/`did_verification_log`）。
- **依赖**：`services/did` 新增平台内部模块 `com.ctds:common-pagination`（ADR-005 §3.2 分页契约）；无新第三方坐标，前端零新增依赖。
- **前端页面级角色头口径**：DID 管理面请求在 `frontend/src/api/did.ts` 内附 `X-Ctds-Roles: applicant,reviewer,admin`，**不动全局 `demoRolesHeader()`**（避免在 example-service 意外激活 `greeting.delete`，最小权限面）。
- **诚实边界补充（沿 §2.7/§2.9 口径）**：演示签名入口开启期**签名能力可外借**（回环边界内无服务间鉴权）——生产禁用 + 演示机须为可信机器；解除条件同 §2.7（网关 HeaderStripFilter + 真实令牌 + 服务间鉴权，3.5.2/3.9.1 兑现项）。
