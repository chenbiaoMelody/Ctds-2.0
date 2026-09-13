# WBS-3.1.3 实名认证集成 · 高保真设计

- 型态：界面类 + 非界面类混合（界面部分 = 静态 HTML 原型 + 本文件"界面说明书"节）
- 对应规格：`docs/specs/C-1.1-主体注册与实名认证.md` **V1.1**——行为 2（OCR）、行为 3（法人核验）、行为 4（自动流转/认证失败态）、行为 7（渠道抽象与模拟渠道）；ADR-016 §2.6 归属断言落地
- 方向确认：`docs/designs/WBS-3.1.3-lofi.md`（PO 签署 = 五问答复随签署意见留痕；本文件与 lofi、原型同批提交、一次确认）
- 任务卡：WBS 3.1.3 ｜ 会话预算：1
- 本文件新增契约（1004 段扩展 / V2 库表 / 接口 / std-adapter 认证域）随编码同步固化进 **ADR-016 主体服务契约** 与 **ADR-008 补记**

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；会话回复"**五问均采建议**"——lofi 五问按建议裁决，随一次确认口径本文件全文生效；表格由 AI 按该声明代录留痕） | 2026-09-13 | 无 |

## 行为清单（14 项，逐条对应规格与计划测试）

| 编号 | 行为（业务可读） | 规格出处 | 计划测试 |
| --- | --- | --- | --- |
| B1 | **std-adapter 认证域**：`StdDomain` 增第四值 `CERTIFICATION("certification","实名认证")`；标记子接口 `CertificationStandardApi`（本包冻结 `ocrBusinessLicense` 与 `verifyLegalPerson` 两方法，政务 CA 方法随 3.1.4 同域扩展）；模拟实现 `MockCertificationChannel`（预置规则与剧本附录 A 严格对应）；ADR-008 补记（lofi 待确认 3 口径） | 行为 7 第 1~2 条；ADR-008 | 单测：模拟规则四条（A1 影像识别成功且要素固定 / 其余影像不可识别 / 尾号 8 不通过 / 异常注入抛 1003 段技术异常）；域码与状态探活断言 |
| B2 | **Flyway V2 迁移**：`subject` 加 `applicant` 列（默认回填 `legacy-demo`，lofi 待确认 4）；建 `cert_material`（证照材料）、`cert_verification_log`（核验记录）两表（表结构见"库表设计"） | ADR-009；ADR-016 §2.3/§2.6 | Testcontainers-MySQL：迁移执行成功、列与索引生效、存量回填默认值断言 |
| B3 | **申请人身份与归属断言（ADR-016 §2.6 裁决落地）**：注册建档写入 `applicant`（取 AuthContext 当前身份）；新建 `OwnershipGuard` 组件（application 层），**全部按申请编号操作**（查询/撤销/上传/确认/核验/档案查询/影像查看/结束认证）先断言归属：当前身份 = 申请人 或 持 `subject.review` 权限（审核员豁免，3.1.5 复用）；不匹配 → 403 + 审计 DENIED | ADR-016 §2.6；行为 5 第 3 条同源 | **双向用例**：本人操作通过 + 他人操作 403 且产生拒绝审计；存量端点（查询/撤销）补断言回归 |
| B4 | **证照上传与 OCR 识别**：multipart 上传（≤5MB、jpg/jpeg/png，配置可调）→ 校验归属与格式 → 调认证渠道 OCR → **影像密文 + OCR 原始结果密文即时落库**（SM4，ADR-006 唯一入口）→ 返回识别要素回填；无法识别 → 1004B0002 提示重传，不落部分结果 | 行为 2 第 1~3、5 条 | 集成测试：上传回填成功；超限/非法格式拒绝；**库内直查为密文形态**（行为 2 验收-4）；不可识别不落库 |
| B5 | **证照核对确认与差异阻断**：确认接口比对"确认提交的信用代码 vs OCR 识别值"，不一致 → 1004B0003 阻断并提示差异；一致 → 确认要素落库（明文，组织信息）；**重复上传替换保留最近一次并重置确认状态** | 行为 2 第 3~4 条 | 测试：一致确认成功；不一致阻断（文案含差异字段）；重传后确认状态重置 |
| B6 | **法人实人核验**：前置校验（证照已确认 → 否则 1004B0006；法人姓名/身份证号一致性 → 姓名/证件号校验位合法 + 姓名与证照识别值一致，否则 1004B0004）→ 调渠道 → 留痕（渠道标识/流水号/结论/耗时 + 身份证号密文）→ **通过：自动流转待认证→待审核（触发方=SYSTEM，经 transition() 强制留痕；不自动入驻）**；不通过：计数留痕返回结论 | 行为 3 第 1~2、4 条；行为 4 第 1 条 | 测试：通过→状态自动待审核+留痕四要素；不通过→失败记录带时间结论；法人不一致阻断；未确认证照阻断 |
| B7 | **核验重试上限与跨日恢复**：当日失败（结论=不通过，渠道异常不计）达 `ctds.certification.verify-daily-limit`（默认 5）→ 拒绝 1004B0005"今日核验次数已用完，请次日再试"；时钟注入 `Clock` Bean，次日自动恢复 | 行为 3 第 3 条；§7 Q3 | 测试：5 次失败后第 6 次拒绝；Clock 拨次日可发起；渠道异常后计数不变（结合 B8） |
| B8 | **渠道异常 fail-fast**：渠道抛技术异常 → 转译 1004S0001"认证服务暂不可用，请稍后重试"；落 CHANNEL_ERROR 留痕（`counted=0` 不计次）；主体状态不变、确认状态不变、无其他脏数据 | 行为 3 第 5 条；行为 7 第 4 条 | 测试：注入异常 → 1004S0001 + 状态不变 + 留痕一条且不计次 |
| B9 | **认证进度档案查询**：按申请编号返回证照状态（未上传/已上传未确认/已确认）、确认要素、核验记录列表（结论/时间/失败原因；身份证号不回显）、当日剩余次数、主体当前状态 | 行为 3 第 2 条；行为 4 第 2 条 | 测试：各阶段字段齐全；身份证号不出现在响应 |
| B10 | **影像查看与审计**：按申请编号查看已上传影像 → 归属断言 → 解密返回（base64 数据 URL）→ `AuditRecorder` 留痕"谁在何时查看" | 行为 2 验收-4 | 测试：查看返回可解密内容 + 审计一条；他人查看 403 + 拒绝审计（结合 B3 双向） |
| B11 | **结束认证（lofi 待确认 1 采 A 口径）**：待认证主体申请人可主动"结束认证" → 流转"待认证→认证失败"（触发方=APPLICANT，remark=申请人结束认证）；**认证失败主体可重新发起核验 → 通过 → 流转"认证失败→待审核"** | 行为 4 第 1 条 | 测试：结束认证留痕；认证失败→核验通过→待审核全链路 |
| B12 | **错误码 1004 段扩展 + 配置参数**：新码见"错误码表"；`ctds.certification.*` 参数表见"配置项"；ADR-016 §2.1 补记 | ADR-005；行为 3 第 3 条 | 封套断言：码值+文案+HTTP 映射，不含内部细节 |
| B13 | **界面原型与界面说明书**：静态 HTML 原型（`docs/designs/WBS-3.1.3-原型-注册与认证.html`，纯前端内嵌演示数据，不连后端）+ 本文件"界面说明书"节；真实 Vue 页面随 3.1.5（lofi 待确认 2 口径） | 行为 1/2/3 界面侧 | 人工走查（PO 浏览器打开原型核对交互与文案） |
| B14 | **依赖登记**：新增内部模块依赖 `common-crypto`、`std-adapter`（均 2.0.0-SNAPSHOT，有先例：kms 用 crypto、example-service 用 std-adapter）；multipart 配置随 Boot 内置，无新外部依赖，`docs/dependencies.md` 无需新增条目 | 章程 4.3 | 人工核对（文档级） |

## 库表设计（编码契约 = 本节定稿；Flyway `V2__add_certification_tables.sql`，库 `ctds_subject`）

```sql
-- 申请人身份列（ADR-016 §2.6 裁决方案①：归属断言数据基础）
ALTER TABLE subject
    ADD COLUMN applicant VARCHAR(64) NOT NULL DEFAULT 'legacy-demo'
    COMMENT '申请人身份（注册建档取 AuthContext 当前身份；归属断言依据）' AFTER admin_account;

-- 证照材料（规格行为 2；一行=一张影像及 OCR 结果；重复上传替换保留最近一次）
CREATE TABLE cert_material (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_id        BIGINT       NOT NULL COMMENT '主体 id（subject.id）',
    material_type     VARCHAR(32)  NOT NULL COMMENT '材料类型：BUSINESS_LICENSE 营业执照',
    file_name         VARCHAR(256) NOT NULL COMMENT '上传文件名',
    content_sm3       CHAR(64)     NOT NULL COMMENT '影像 SM3 摘要（完整性锚点，唯一入口 common-crypto，ADR-006 Q2 裁决；4 视角评审补正）',
    content_cipher    LONGBLOB     NOT NULL COMMENT '影像 SM4 密文（ADR-006 唯一入口加密后落库）',
    ocr_raw_cipher    LONGBLOB     NULL COMMENT 'OCR 原始识别结果 JSON 的 SM4 密文（行为 2 第 5 条双要素之二）',
    ocr_uscc          VARCHAR(18)  NULL COMMENT 'OCR 识别的统一社会信用代码（差异比对锚点，组织信息非 L4）',
    ocr_legal_person  VARCHAR(64)  NULL COMMENT 'OCR 识别的法定代表人姓名（核验一致性比对锚点）',
    ocr_recognizable  TINYINT      NOT NULL DEFAULT 1 COMMENT '渠道是否识别成功（0=不可识别，不产生部分结果）',
    confirmed_name    VARCHAR(128) NULL COMMENT '申请人确认后的主体名称（核对确认值，明文）',
    confirmed_uscc    VARCHAR(18)  NULL COMMENT '确认后的统一社会信用代码',
    confirmed_legal_person VARCHAR(64) NULL COMMENT '确认后的法定代表人姓名',
    confirmed_reg_address  VARCHAR(256) NULL COMMENT '确认后的注册地址',
    confirmed_at      DATETIME     NULL COMMENT '确认时间',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_subject_type (subject_id, material_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '证照材料（影像与 OCR 原始结果按 L4 管控 SM4 加密存储）';

-- 法人核验记录（规格行为 3 / 行为 7 第 3 条；一行=一次渠道调用）
CREATE TABLE cert_verification_log (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_id            BIGINT       NOT NULL COMMENT '主体 id（subject.id）',
    verify_type           VARCHAR(32)  NOT NULL COMMENT '调用类型：LEGAL_PERSON 法人核验 / OCR_LICENSE 证照 OCR（4 视角评审补正：渠道调用全程留痕含 OCR）',
    channel_code          VARCHAR(32)  NOT NULL COMMENT '渠道标识（mock-certification）',
    channel_request_no    VARCHAR(64)  NOT NULL COMMENT '渠道请求流水号',
    legal_person_name     VARCHAR(64)  NOT NULL COMMENT '提交的法人姓名',
    legal_person_id_cipher VARCHAR(512) NOT NULL COMMENT '提交的身份证号 SM4 密文（L4）',
    conclusion            VARCHAR(16)  NOT NULL COMMENT '结论：PASS通过/FAIL不通过/CHANNEL_ERROR渠道异常',
    fail_reason           VARCHAR(256) NULL COMMENT '失败原因（业务可读）',
    cost_ms               INT          NOT NULL COMMENT '渠道调用耗时毫秒',
    counted               TINYINT      NOT NULL DEFAULT 1 COMMENT '是否计入当日失败次数（渠道异常=0，行为 7 第 4 条）',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    PRIMARY KEY (id),
    KEY idx_subject_day (subject_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '法人核验记录（渠道标识/流水号/结论三要素 + 耗时全程留痕）';
```

- **L4 边界**：身份证号、证照影像、OCR 原始结果三项密文；`ocr_uscc`/`ocr_legal_person` 为组织/姓名比对锚点明文（SQL 比对需要，非证件号码）；影像原文完整性锚点用 SM3（唯一入口 common-crypto，ADR-006 Q2 裁决）。
- **当日失败计数 SQL 口径**：`SELECT COUNT(*) FROM cert_verification_log WHERE subject_id=? AND conclusion='FAIL' AND counted=1 AND created_at >= 当日00:00`（当日取 `Clock` 系统时区）。
- **替换语义**：同主体同 `material_type` 重复上传 = 删除旧行插入新行（确认字段随之清空，须重新确认）；`subject.applicant` 存量回填 `legacy-demo`（lofi 待确认 4）。

> **4 视角评审补正（2026-09-13，实现与本文定稿的偏差以此为准，ADR-016 §2.2/§2.3 同步）**：
> ① `cert_verification_log` 为**认证渠道调用记录**（不只法人核验）：`verify_type` 含 `LEGAL_PERSON`/`OCR_LICENSE`，`conclusion` 增 `UNRECOGNIZABLE`，`channel_request_no`/`legal_person_name`/`legal_person_id_cipher` 允许 NULL（OCR 调用无身份证号），`counted DEFAULT 0`，渠道标识与流水号一律取自渠道接口出口（ADR-008 §7 接口补齐）；
> ② `cert_material.content_sha256` 更名 `content_sm3`（SM3 摘要）并增 `UNIQUE KEY uk_subject_type (subject_id, material_type)` 兜底并发替换；
> ③ 错误码表增 `1004C0002 CERT_STATE_NOT_ALLOWED`（状态门槛，见错误码表节补记行）；
> ④ `transition()` 实现为"更新 subject.status 列 + from_status 乐观门槛 + 落留痕"同事务（原边界值表"状态门槛拒绝"至此有真实实现）；核验通过后库内 `subject.status` 即为 PENDING_REVIEW（集成测试断言持久化状态）；
> ⑤ 归属断言出站统一 400 + 1000C0003（与"不存在"同形防枚举探测，ADR-016 §2.6 补正）；
> ⑥ 身份证号按 GB 11643 校验位验证（B6 兑现）；配置项增 `ctds.certification.material-key-ref` 与 `reviewer` 角色映射（subject.review 归属豁免用）；界面说明书"缩略图/剩余次数常驻/尾 4 位掩码"三处以原型实际交互为准（原型不展示身份证号，脱敏更严）。

## 接口契约（编码契约 = 本表定稿）

统一响应结构沿 ADR-005；本节端点全部经 `OwnershipGuard` 归属断言（B3）。

### REST 端点

| 端点 | 方法 | 权限 | 语义 |
| --- | --- | --- | --- |
| `/api/v1/subject/registrations/{subjectNo}/certification/license` | POST（multipart，参数名 `file`） | `subject.certify` | 上传营业执照并触发 OCR 识别（B4） |
| `/api/v1/subject/registrations/{subjectNo}/certification/license/confirmation` | POST | `subject.certify` | 核对确认回填要素（B5，信用代码不一致阻断） |
| `/api/v1/subject/registrations/{subjectNo}/certification/legal-person-verifications` | POST | `subject.certify` | 发起法人核验（B6~B8；**不挂 @Idempotent**——失败重试须真执行，防重复点击由次数上限与留痕兜底） |
| `/api/v1/subject/registrations/{subjectNo}/certification` | GET | `subject.read` | 认证进度档案（B9） |
| `/api/v1/subject/registrations/{subjectNo}/certification/license/image` | GET | `subject.read` | 查看影像（解密返回，审计留痕，B10） |
| `/api/v1/subject/registrations/{subjectNo}/certification/abandonment` | POST | `subject.certify` | 结束认证：待认证→认证失败（B11，lofi 待确认 1 采 A 口径） |

存量端点 `GET /registrations/{subjectNo}`、`POST .../cancellation` 本包补归属断言，路径与契约不变。

### 请求/响应（JSON）

**POST .../license**（multipart）响应 data：
```json
{ "materialId": 1, "fileName": "A1.jpg", "recognizable": true,
  "ocrResult": { "subjectName": "蓝天数据科技有限公司", "uscc": "91330100MA27XW123X", "legalPerson": "张伟", "regAddress": "杭州市XX区XX路88号" } }
```
不可识别 → 400 `1004B0002`（证照影像无法识别，请重传），库中不落部分结果。

**POST .../license/confirmation** 请求体（四字段均必填）：
```json
{ "subjectName": "蓝天数据科技有限公司", "uscc": "91330100MA27XW123X", "legalPerson": "张伟", "regAddress": "杭州市XX区XX路88号" }
```
响应 data：`{ "subjectNo": "S20260913000001", "confirmed": true, "nextStep": "LEGAL_PERSON_VERIFICATION" }`
`uscc` 与 OCR 识别值不一致 → 400 `1004B0003`（文案含"统一社会信用代码与证照识别结果不一致"）。

**POST .../legal-person-verifications** 请求体：`{ "legalPersonName": "张伟", "legalPersonIdNo": "3301..." }`
响应 data：
```json
{ "subjectNo": "S20260913000001", "conclusion": "PASS", "status": "PENDING_REVIEW",
  "remainingAttemptsToday": 5 }
```
不通过时 `conclusion=FAIL`、`status` 保持不变、`failReason` 给业务原因；`legalPersonIdNo` 仅写入不回显。

**GET .../certification** 响应 data：
```json
{ "subjectNo": "S20260913000001", "status": "PENDING_CERT",
  "license": { "uploaded": true, "confirmed": true, "confirmedAt": "2026-09-13T14:00:00",
               "confirmedResult": { "subjectName": "...", "uscc": "...", "legalPerson": "张伟", "regAddress": "..." } },
  "verifications": [ { "conclusion": "FAIL", "failReason": "身份要素不匹配", "createdAt": "2026-09-13T13:30:00" } ],
  "remainingAttemptsToday": 4 }
```

**GET .../license/image** 响应 data：`{ "fileName": "A1.jpg", "dataUrl": "data:image/jpeg;base64,..." }`（解密返回；每次调用产生一条查看审计）

**POST .../abandonment** 响应 data：`{ "subjectNo": "S20260913000001", "status": "CERT_FAILED" }`（流转留痕：PENDING_CERT→CERT_FAILED，触发方=APPLICANT，remark=申请人结束认证）

## 错误码表（1004 段扩展，ADR-016 §2.1 补记）

| 码 | 常量 | 类型→HTTP | 对外文案 | 触发场景 |
| --- | --- | --- | --- | --- |
| `1004B0002` | `CERT_LICENSE_UNRECOGNIZABLE` | B→400 | 证照影像无法识别，请重传 | 渠道 OCR 返回不可识别（行为 2 第 3 条） |
| `1004B0003` | `CERT_USCC_MISMATCH` | B→400 | 统一社会信用代码与证照识别结果不一致，请修正后提交 | 确认值 vs OCR 值差异阻断（行为 2 第 4 条） |
| `1004B0004` | `CERT_LEGAL_PERSON_MISMATCH` | B→400 | 法人信息与证照识别结果不一致，请先修正后再发起核验 | 核验前置一致性校验（行为 3 第 4 条） |
| `1004B0005` | `CERT_VERIFY_LIMIT_REACHED` | B→400 | 今日核验次数已用完，请次日再试 | 当日失败达上限（行为 3 第 3 条） |
| `1004B0006` | `CERT_LICENSE_NOT_CONFIRMED` | B→400 | 请先完成证照上传与核对确认 | 未确认即发起核验（流程前置） |
| `1004C0002` | `CERT_STATE_NOT_ALLOWED` | C→400 | 当前状态不允许执行认证操作 | 状态门槛拒绝（上传/确认仅待认证；核验限待认证/认证失败；并发重复流转乐观门槛）——**4 视角评审补记（2026-09-13）** |
| `1004S0001` | `CERT_CHANNEL_UNAVAILABLE` | S→503 | 认证服务暂不可用，请稍后重试 | 渠道技术异常 fail-fast（行为 3 第 5 条；S 型默认 500 全局脱敏，本码经本地处理器精确映射 503 并保留业务文案） |

既有 `1004B0001`/`1004C0001` 与 1000 段复用不变；全部经 `BizException` 抛出，对外文案为服务端常量。

## std-adapter 认证域接口（编码契约 = 本节定稿）

```java
public interface CertificationStandardApi extends StdDomainApi {
    /** 营业执照 OCR 识别（行为 2）。不可识别返回 recognizable=false，不抛业务错。 */
    OcrRecognition ocrBusinessLicense(byte[] image, String fileName);
    /** 法人实人核验（行为 3）。渠道技术异常抛 StdAdapterException（1003 段），业务不通过返回 FAIL 结论。 */
    LegalPersonVerification verifyLegalPerson(String legalPersonName, String legalPersonIdNo);
}
// OcrRecognition(boolean recognizable, String subjectName, String uscc, String legalPerson, String regAddress, String message)
// LegalPersonVerification(boolean passed, String channelRequestNo, String failReason)
```

- `MockCertificationChannel` 预置规则（与剧本附录 A 严格对应）：文件名含 `A1` → 固定要素（蓝天数据科技有限公司 / `91330100MA27XW123X` / 张伟 / 杭州市XX区XX路88号）；其余 → `recognizable=false`；身份证号尾号为 `8` → FAIL；构造参数/配置 `channelError=true` → 抛 1003 段技术异常（供 B8 注入测试与演示）。
- 渠道请求流水号由模拟实现生成（`MOCK-` 前缀 + UUID），业务侧原样留痕。
- ADR-008 补记范围：域清单增第四值 + "认证域协议方法随 WBS-3.1.3 冻结、政务 CA 方法随 3.1.4 同域扩展"的特别说明；其余契约（收口纪律/替换规则/错误码段）零改动。

## 界面说明书（静态原型配套，真实 Vue 页面随 3.1.5）

**页面清单**（原型单文件内分步演示）：

| 页面/区块 | 内容与交互 | 脱敏与提示口径 |
| --- | --- | --- |
| 注册表单 | 主体名称/信用代码/类型/地址/联系人/电话/管理员账号；逐字段校验提示；提交成功显示申请编号与"待认证" | 与 3.1.2 已交付接口一致 |
| 认证流程条 | 三步指示：①上传证照 ②核对确认 ③法人核验；顶部常驻当前主体状态 | — |
| 上传证照 | 文件选择 + 格式/大小前端提示（≤5MB、jpg/jpeg/png）；上传后展示"识别成功/无法识别请重传" | 影像缩略图仅本人可见 |
| 核对确认 | OCR 要素回填四字段（可编辑）；提交时前后端双重比对信用代码；不一致红字提示差异并阻断 | 修改过的字段以人工确认为准（行为 2 第 3 条） |
| 法人核验 | 法人姓名 + 身份证号输入（不回显）；结论展示（通过/不通过/次数用完）；失败记录列表（结论+时间+原因）；当日剩余次数展示 | 身份证号全程不回显，记录列表仅显示尾 4 位掩码 |
| 结束认证 | "放弃本次认证"按钮（二次确认）→ 状态"认证失败"；后续可重新发起核验 | 留痕口径提示"该操作将记录认证档案" |

- 原型内嵌演示数据走查剧本 S1/S2 主干（上传 A1 → 回填 → 改码阻断 → 恢复 → 核验通过/失败）；静态原型不含真实请求，接口联调随 3.1.5 前端实现。
- 界面元素名称（按钮文案等）与剧本维护说明的"实际交付核对修订"口径一致。

## 配置项（application.yml，沿 3.1.2 先例）

| 配置 | 值/默认 | 说明 |
| --- | --- | --- |
| `ctds.certification.verify-daily-limit` | `5` | 当日核验失败上限（规格 §7 Q3，业务可调参数） |
| `ctds.certification.upload-max-bytes` | `5242880` | 影像大小上限（lofi 待确认 5） |
| `ctds.certification.upload-allowed-extensions` | `jpg,jpeg,png` | 影像格式白名单 |
| `spring.servlet.multipart.max-file-size / max-request-size` | `6MB` / `7MB` | 略大于业务上限，超限统一转 1004B0002 文案族提示（校验在应用层先行，不暴露容器细节） |
| `ctds.auth.permissions.applicant` | `subject.register,subject.read,subject.cancel,subject.certify` | 演示角色映射扩展（`subject.review` 预留给 3.1.5 审核角色） |
| `ctds.crypto.*`（common-crypto 配置键） | 运行期接本机 KMS（ADR-015），测试用 LocalFileKeyProvider | 密钥文件不入库（.gitignore 已拦截）；具体键名以 common-crypto 源码为准，编码会话核验 |
| `ctds.std.certification.mock.channel-error` | `false` | 模拟渠道异常注入开关（演示 B8 fail-fast 口径） |

## 边界值与异常行为

| 场景 | 行为 | 依据 |
| --- | --- | --- |
| 影像 >5MB 或非白名单格式 | 400，提示大小/格式要求，不落库不调渠道 | lofi 待确认 5 |
| 不可识别影像上传 | 1004B0002 提示重传；影像密文可留痕（material 行 `ocr_recognizable=0`）但不产生任何识别要素 | 行为 2 第 3 条（上传材料不丢，识别不静默放行） |
| 确认时信用代码与 OCR 值不一致 | 1004B0003 阻断，文案明确差异字段 | 行为 2 第 4 条 |
| 证照未确认即发起核验 | 1004B0006 | 流程前置（行为 2 → 行为 3 顺序） |
| 法人姓名与证照识别值不一致 | 1004B0004 阻断（政务 CA 通道豁免随 3.1.4，本包不涉及） | 行为 3 第 4 条 |
| 当日失败达 5 次 | 1004B0005；次日 Clock 恢复可发起 | 行为 3 第 3 条 + §7 Q3 |
| 渠道技术异常 | 1004S0001 fail-fast；CHANNEL_ERROR 留痕不计次；主体/确认状态不变、无脏数据 | 行为 3 第 5 条 + 行为 7 第 4 条 |
| 核验通过但主体已是待审核/已入驻 | 不重复流转：transition() 状态门槛拒绝，幂等返回当前状态（重复回调窗口） | ADR-016 §2.2 状态机锁定 |
| 待审核/已入驻/已驳回主体调用认证端点 | 按状态门槛拒绝（仅待认证/认证失败可发起核验与结束认证；仅待认证可上传/确认） | 状态机 V1.0 最小集 |
| 他人申请编号操作（全部端点） | 403 + 审计 DENIED；响应不泄露对方档案任何字段 | ADR-016 §2.6 双向用例 |
| 重复上传 | 替换保留最近一次，确认状态重置 | 库表设计替换语义 |
| 核验请求并发重复点击 | 各自真执行并逐条留痕计数（不挂幂等，重试须真执行）；由当日上限兜底防刷 | 接口契约说明 |
| CERT_FAILED 主体重新核验通过 | 流转认证失败→待审核（触发方=SYSTEM），不回到待认证 | lofi 待确认 1 采 A |
| KMS/密钥不可用（加密失败） | fail-fast 1000S9999 族系统错误，不落明文、不留半密文 | 安全默认（章程原则 7） |

## 依赖清单

| 坐标 | 版本 | 范围 | 说明 |
| --- | --- | --- | --- |
| `com.ctds:common-crypto` | 2.0.0-SNAPSHOT | compile | **新增引用**（内部模块，有先例 kms）：SM4 唯一入口 |
| `com.ctds:std-adapter` | 2.0.0-SNAPSHOT | compile | **新增引用**（内部模块，有先例 example-service）：认证渠道收口 |
| `com.ctds:common-errorcode/logging/auth/idempotency` | 2.0.0-SNAPSHOT | compile | 既有复用（幂等仅注册端点沿用，本包新端点不挂） |
| 既有 web/validation/jdbc/flyway/mysql/actuator/test 全套 | 不变 | — | 沿 3.1.2 已登记清单，无新外部依赖 |

## 规格缺口声明

1. 认证失败态再流转路径：lofi 待确认 1 采 A 后闭环（主动结束触发 + 重新核验通过→待审核）；"结束认证"入口为规格行为 4 括号提法的落地，PO 签署即确认。
2. OCR 置信度数值体系：模拟渠道两态口径（可识别/不可识别），真实渠道接入时随适配包定义阈值参数（lofi"不做什么"声明）。
3. 1004 段扩展与 ADR-016 补记、ADR-008 补记随编码固化（沿 ADR-015 惯例）。

## 问题确认：
（PO 签署本文件即视为 14 项行为清单 + 库表 + 接口契约 + 错误码表 + std-adapter 认证域接口 + 界面说明书 + 配置项 + 边界值全部确认，lofi 五问视为随签署意见一并答复；如需修改在签署意见中列明）
