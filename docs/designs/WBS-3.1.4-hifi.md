# WBS-3.1.4 政务 CA 接入适配 · 高保真设计

- 型态：非界面类（行为清单 + 接口契约 + 边界值；无新增页面，静态原型政务分支随 3.1.5 补——lofi 待确认问题口径）
- 对应规格：`docs/specs/C-1.1-主体注册与实名认证.md` V1.1 行为 6 / 行为 7（政务部分）；验收剧本 S3 + 附录 A
- 任务卡：WBS 3.1.4｜会话预算：1（两级一并提交、一次确认）
- 关联设计：`docs/designs/WBS-3.1.4-lofi.md`（同批提交；五问采建议口径见确认记录）
- 编码契约 = 本文件定稿；实现与本文不一致 = 打回项

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | （待签署） | | | |

## 行为清单（9 项，逐条对应规格与计划测试）

| # | 行为 | 规格依据 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 政务部门主体提交政务 CA 证书（multipart），渠道校验有效期/证书链/单位身份要素 | 行为 6 第 1~2 条 | 单测：提交→渠道调用参数正确；集成：A3 全链路 |
| B2 | 验证通过 → 认证视为通过，自动流转"待审核"（触发方=SYSTEM，留痕备注"政务 CA 证书验证通过，自动流转"）；**不自动入驻** | 行为 6 第 2~3 条 + Q2 裁决 | 集成：A3 后响应状态=PENDING_REVIEW 且**直查库 subject.status 列=PENDING_REVIEW**（3.1.3 教训：持久化状态断言强制） |
| B3 | 验证失败 → 返回明确失败原因，主体停留"待认证"，**可重新提交换证**（替换材料保留最近一次） | 行为 6 第 2 条 | 集成：A4 → FAIL + 状态保持 PENDING_CERT（直查库）+ 原因文案；重新提交 A3 → 通过 |
| B4 | 渠道调用全程留痕：verify_type=GOV_CA，渠道标识/流水号取自接口出口、结论、耗时、失败原因；渠道技术异常落 CHANNEL_ERROR 行（不计失败） | 行为 7 第 3~4 条 | 集成：三要素断言；渠道异常 503 + CHANNEL_ERROR 留痕 + 无脏数据直查库 |
| B5 | 通道互斥：GOV 主体调企业认证端点（上传执照/核对确认/法人核验）→ 400 + 1004B0007；企业/机构主体调政务端点 → 同码拒绝 | 行为 6 第 1 条 + 验收"全程不出现" | 集成：双向互斥各 1 例 + 拒绝留痕断言 |
| B6 | 证书文件 L4 落库：SM4 密文（content_cipher）+ SM3 摘要（content_sm3）；渠道返回的验证原始要素 JSON 密文（ocr_raw_cipher）；明文不出库 | 行为 6 第 5 条 | 集成：直查库断言密文形态（非明文前缀）+ SM3 与文件字节一致 + 解密往返 |
| B7 | 归属断言：政务端点沿 OwnershipGuard（本人或 reviewer 豁免）；不匹配出站与"不存在"同形（400 + 1000C0003），DENIED 审计照落 | ADR-016 §2.6 | 集成：他人提交 400 同形 + 审计断言；reviewer 可查档案 |
| B8 | 档案查询适配：GOV 主体返回 govCa 段（是否已上传/文件名/最近结论/最近失败原因/最近提交时间），remainingAttemptsToday 不返回（null）；企业主体 govCa 为 null | 行为 6 验收-3（流程可见性） | 集成：政务主体档案段断言 + 企业主体不受影响回归 |
| B9 | 模拟渠道政务规则：文件名含 A3 → 通过（单位"市大数据管理局"+ 渠道流水号）；含 A4 → 拒绝"证书已过期"；其余 → 拒绝"证书无法识别或验证不通过"；channel-error 开关注入技术异常 | 剧本附录 A | 单测：MockCertificationChannel 政务规则四例 |

## 库表设计（编码契约 = 本节定稿）

**零迁移（Flyway 无 V3）**。复用既有两表新增值，DDL 不变：

| 表 | 变化 | 口径 |
| --- | --- | --- |
| `cert_material` | `material_type` 新增值 **`GOV_CA_CERT`** | `uk_subject_type (subject_id, material_type)` 唯一键天然保证"一主体一证书"；重复提交 = 删旧插新保留最近一次（`replaceMaterial` 既有语义）；`content_cipher` = 证书文件 SM4 密文；`content_sm3` = 证书文件 SM3 摘要；`ocr_raw_cipher` = 渠道返回验证要素 JSON（unitName/unitCode/message）的 SM4 密文；`ocr_recognizable` 复用为"渠道验证通过"布尔；`ocr_uscc`/`ocr_legal_person` 等确认类列对 GOV_CA_CERT 恒为 NULL（政务无核对环节） |
| `cert_verification_log` | `verify_type` 新增值 **`GOV_CA`** | 结论枚举沿用 PASS/FAIL/CHANNEL_ERROR；`counted` 恒 0（政务无失败次数概念，lofi Q3-A 裁决）；`legal_person_*` 两列恒 NULL |
| `subject` | 无变化 | 注册端点 3.1.2 已支持 GOV 类型，本包不动注册 |

**列名语义补记（随 ADR-016 §2.3 留痕）**：`ocr_*` 列名对 GOV_CA_CERT 材料语义泛化为"渠道原始要素/渠道结论"，不重命名（3.1.3 已冻结库表契约，改名即破坏性变更）。

## 接口契约（编码契约 = 本表定稿）

### REST 端点（挂在既有 CertificationController，基路径 `/api/v1/subject/registrations/{subjectNo}/certification`）

| 端点 | 方法 | 权限 | 说明 |
| --- | --- | --- | --- |
| `/gov-ca-certificate` | POST（multipart，字段名 `file`） | `subject.certify` | **新增**。政务 CA 证书提交与验证一步完成（lofi Q2-A）：前置 = 申请编号格式 → 归属断言 → 状态门槛（PENDING_CERT 或 CERT_FAILED）→ 主体类型 = GOV（否则 1004B0007）→ 文件校验 → 渠道验证 → 留痕 → 通过自动流转 |
| 既有 4 端点（license 上传 / confirmation / legal-person-verifications / license/image） | — | — | **行为变化仅一处**：主体类型 = GOV 时返回 400 + 1004B0007（通道互斥，B5）；其余口径不变 |
| GET 档案（`/`） | GET | `subject.read` | 响应结构扩展 `govCa` 段（见下）；GOV 主体 `remainingAttemptsToday` 返回 null |
| `/abandonment` | POST | `subject.certify` | 不变（通道无关的通用动作，政务主体保持可用） |

### 请求/响应（JSON）

**POST /gov-ca-certificate**（multipart/form-data：`file` = 证书文件字节）

```json
// 验证通过（HTTP 200）
{ "code": "0000000000", "data": {
    "subjectNo": "S20260913000001",
    "conclusion": "PASS",
    "status": "PENDING_REVIEW",
    "failReason": null } }
// 验证不通过（HTTP 200，业务结论非异常；与法人核验 FAIL 同构）
{ "code": "0000000000", "data": {
    "subjectNo": "S20260913000002",
    "conclusion": "FAIL",
    "status": "PENDING_CERT",
    "failReason": "证书已过期（模拟渠道预置 A4）" } }
```

**GET 档案响应新增 `govCa` 段**（企业主体为 null；GOV 主体 `license` 段为空态、`remainingAttemptsToday` 为 null）：

```json
{ "govCa": { "uploaded": true, "fileName": "A3.cer",
    "lastConclusion": "PASS", "lastFailReason": null,
    "lastSubmittedAt": "2026-09-13T20:00:00" } }
```

### 响应记录（应用层）

- `GovCaCertificationResult(subjectNo, conclusion, status, failReason)`
- `CertificationProfile` 扩展：`remainingAttemptsToday` 类型 `int` → `Integer`（GOV 为 null）；新增 `govCa` 字段（`GovCaProfile(boolean uploaded, String fileName, String lastConclusion, String lastFailReason, LocalDateTime lastSubmittedAt)`，未上传为 null）

## 错误码表（1004 段扩展 1 码，占用留痕 ADR-016 §2.1 补记）

| 码 | 常量 | 文案 | HTTP | 说明 |
| --- | --- | --- | --- | --- |
| 1004B0007 | `CERT_CHANNEL_TYPE_MISMATCH` | 主体类型与认证流程不匹配，请使用对应主体的认证方式 | 400 | 通道互斥（B5，双向同码）；归属断言在前，能触达者已是本人或 reviewer |

既有复用（文案与 HTTP 映射不变）：1000C0002 参数类（编号格式/文件为空/超限/格式不符）、1000C0003 资源不存在（编号不存在与归属不匹配同形）、1004C0002 状态门槛、1004S0001 渠道不可用（503，"认证服务暂不可用，请稍后重试"，政务通道同口径）。政务验证不通过**不是错误码**（业务结论 FAIL + failReason，同法人核验）。

## std-adapter 认证域接口扩展（编码契约 = 本节定稿；ADR-008 §7 再补记随包提交）

```java
/** 政务 CA 证书验证（行为 6 第 2 条）：有效期/证书链/单位身份要素由渠道校验。 */
GovCaVerification verifyGovCaCertificate(byte[] certBytes, String fileName);
```

- `GovCaVerification(boolean passed, String channelRequestNo, String failReason, String unitName, String unitCode, String message)`——`passed=false` 时 `failReason` 必填（业务可读）；`channelRequestNo` 由渠道产生；单位要素供留痕密文落库（组织信息，明文仅存密文 JSON）。
- 校验口径与既有两方法一致：参数缺失抛 `IllegalArgumentException`（编程错误不转译）；渠道技术异常抛 `CHANNEL_UNAVAILABLE`（调用方转译 fail-fast）。
- `MockCertificationChannel` 政务规则（与剧本附录 A 严格对应，预置标记与 A1/A2 同构）：文件名含 `A3` → 通过（unitName=市大数据管理局）；含 `A4` → 拒绝"证书已过期（模拟渠道预置 A4）"；其余 → 拒绝"证书无法识别或验证不通过"；`channel-error` 开关对三方法统一生效。

## 配置项（application.yml，沿 3.1.3 先例；前缀 `ctds.certification`）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `gov-cert-max-bytes` | 2097152（2MB） | 证书文件大小上限（lofi Q4-A） |
| `gov-cert-allowed-extensions` | cer, crt, pem | 证书文件格式白名单（lofi Q4-A） |

既有参数（verifyDailyLimit / uploadMaxBytes / uploadAllowedExtensions / materialKeyRef / `ctds.std.certification.mock.channel-error`）不变；政务通道**不新增**失败上限参数（lofi Q3-A）。

## 边界值与异常行为

| 场景 | 行为 |
| --- | --- |
| 状态门槛：主体已 PENDING_REVIEW / ADMITTED / REJECTED 时提交政务证书 | 400 + 1004C0002（经 transition 乐观门槛拒绝，无脏数据） |
| CERT_FAILED 政务主体重新提交（曾误走"结束认证"） | 允许（同 verifyLegalPerson 的 CERT_FAILED→PENDING_REVIEW 边），通过后直接待审核 |
| 文件为空 / 超过 2MB / 扩展名非 cer/crt/pem / 文件名缺失或 >256 字符 | 400 + 参数类文案（逐项明确，不产生半成品材料） |
| 渠道技术异常（channel-error 开关） | 503 + 1004S0001 fail-fast；CHANNEL_ERROR 留痕行落库；材料不落（无半完成状态）；直查库断言 |
| 并发重复提交 | `uk_subject_type` 唯一键 + `replaceMaterial` 既有同构语义兜底；流转经 transition 乐观门槛（0 行即拒）——并发穿透为 3.1.3 登记观察项②的同类口径，随 3.1.5 前补并发用例（本包不重复登记） |
| GOV 主体上传营业执照 / 企业主体提交政务证书 | 400 + 1004B0007（互斥双向，B5）；DENIED 审计 |
| 他人（非申请人非 reviewer）提交/查询 | 400 + 1000C0003（与"不存在"同形，防探测）+ DENIED 审计（ADR-016 §2.6 补正口径） |
| 证书文件密文直查 | `content_cipher` 非明文（SM4 密文形态）；`content_sm3` = 明文字节 SM3；解密往返一致（B6） |

## 依赖清单

**零新外部依赖**（全部复用 common-crypto / common-auth / common-errorcode / common-logging / std-adapter 既有依赖与组件）；`dependencies.md` 无需更新。

## 映射表（规格验收标准 → 设计行为 → 测试 → 剧本）

| 规格行为 6 验收标准 | 设计行为 | 测试 | 剧本步骤 |
| --- | --- | --- | --- |
| 有效证书 → 验证通过、直接待审核、档案记录验证流水 | B1/B2/B4 | 集成 A3 全链路（直查库） | S3 步骤 1/2 |
| 过期/无效证书 → 拒绝 + 明确原因、停留待认证 | B3 | 集成 A4 + 重新提交 | S3 步骤 3 |
| 政务主体全程不出现 OCR 与法人核验 | B5（通道互斥） | 集成双向互斥 | S3 步骤 1（流程走查） |
| 审核员可见验证流水、通过后入驻 | B8（档案段）；入驻归 3.1.5 | 集成 reviewer 查档案 | S3 步骤 4（前半，后半归 3.1.5） |

## 规格缺口声明

1. 政务验证失败次数上限：规格未定义，采"不设上限"（lofi Q3-A，PO 确认后生效）；如需上限走规格变更流程。
2. 本包不改验收剧本（S3 步骤与本设计契约一致；唯一提示：S3 步骤 3 预期"主体停留待认证"与接口响应 `status=PENDING_CERT` 对齐，无需文本变更）——交付说明中按章程回答"剧本是否需要更新：不需要"。
3. ADR 补记三项随包提交：ADR-008 §7（接口扩展）、ADR-016 §2.1（1004B0007）、§2.3（复用口径 + verify_type/GOV_CA + ocr_* 语义泛化）。

## 问题确认：
（待 PO 签署）
