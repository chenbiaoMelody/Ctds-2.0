# WBS-3.1.9 DID 解析与验证服务 · 高保真设计（编码契约）

- 契约性质：**本文件 = 编码契约**。经 PO 一次确认后生效（本包 ≤1 天，两级同批确认，章程 2.6.3）；实现与本文不一致 = 打回项。
- 定稿口径：按 `docs/designs/WBS-3.1.9-lofi.md` §3 **建议口径定稿**（Q1=A 扩展既有服务 / Q2=A 服务间只读调用 + 不可用如实表征 / Q3=A 验证无鉴权 / Q4=A 解析无鉴权 / Q5=A 解析不留痕 / Q6=A 原因四类 + 不可用类）；若确认时对 Q 点有不同意见，按新口径修订本文后再编码。
- 依据：规格 `docs/specs/C-1.2-分布式数字身份DID.md` 行为 2（4 规则 + 4 验收标准）、行为 3（4 规则 + 5 验收标准）；ADR-017 §2.3/§2.4/§2.9；ADR-006（`Sm2Service.verify`）；ADR-005 §3.5（1005 段顺延）。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | **确认**（与 lofi 同批一次签署） | 项目主导者（兼任 PO；会话回复"**确认**"） | 2026-09-22 | 无（**编码契约生效**；Q1~Q6 采 lofi 建议口径） |

---

## 1. 行为清单（逐条对应验收标准编号）

| 编号 | 行为（编码契约） | 规格出处 |
| --- | --- | --- |
| B1 | **解析有效 DID**：`GET /api/v1/did/{did}` → `{did, status: "ACTIVE", document: {公开要素}}`；`document` 字段集严格 = `did/publicKey/controller/service/created`（取自 `did_identity.document_json`，逐字段断言） | 行为 2 规则 1；验收 1 |
| B2 | **解析已吊销 DID**：同上，`status: "REVOKED"`，文档照常返回（不提供恢复端点） | 行为 2 规则 4；行为 4 验收 4（解析侧） |
| B3 | **解析未登记 DID**：→ `code=1005B0003`（HTTP 400），文案"该 DID 未登记"，**明确业务答复不伪装系统异常** | 行为 2 规则 3；验收 3 |
| B4 | **解析结果无 L4 敏感信息**：响应全文（含 document）不出现法人身份证号/证照/手机号等敏感明文——实现层不引入任何 L4 字段；**测试锚定**：字段集精确断言 + 敏感样式扫描反向探针（植入必变红） | 行为 2 规则 2；验收 4 |
| B5 | **验证通过**：`POST /api/v1/did/{did}/verifications` `{data: Base64, signature: Base64}` → `result="PASS"`（三查全过：SM2 验签过 + ACTIVE + 主体已入驻）；留痕一条 | 行为 3 规则 2；验收 1 |
| B6 | **签名核验失败**：数据被篡改/签名伪造 → `result="FAIL", reason="SIGNATURE_INVALID"`；**验签过程的输入类异常（非法 Base64/空数据/格式非法签名）收敛为同一业务失败类**（不直出 500） | 行为 3 规则 2；验收 2 |
| B7 | **状态核验失败**：已吊销 DID 的真签名 → `result="FAIL", reason="REVOKED"` | 行为 3 规则 2；验收 3；行为 4 验收 4（验证侧） |
| B8 | **主体绑定核验失败**：DID 关联主体当前非"已入驻" → `result="FAIL", reason="SUBJECT_BINDING_FAILED"` | 行为 3 规则 2（三查③） |
| B9 | **绑定核验不可用（边界，规格未覆盖的设计定形）**：主体服务不可达/超时 → `result="UNAVAILABLE", reason="BINDING_UNAVAILABLE"`，**不冒充"验证不通过"**（系统态与身份结论分离）；留痕记 `UNAVAILABLE` | lofi Q2=A（设计边界） |
| B10 | **验证留痕三要素且无数据原文**：每次验证（含 FAIL/UNAVAILABLE/未登记）落 `did_verification_log`：`occurred_at / did / result`（FAIL 另含 reason）；**不含 data/signature 原文**——锚定测试：库表 64-hex 与 Base64 样式扫描 + 数据原文植入反向探针 | 行为 3 规则 3；验收 4 |
| B11 | **验证≠授权（口径声明）**：响应仅含身份结论字段，无任何授权/权限字段；声明写入 ADR-017 补记与交付说明 | 行为 3 规则 4；验收 5 |
| B12 | **验证对未登记 DID**：→ `result="FAIL", reason="NOT_REGISTERED"`（业务结论：身份主张不成立）+ 留痕（与解析侧 B3 的 400 表征差异理由：解析 = 查询对象不存在；验证 = 对主张的判定，见 §6 口径说明） | 行为 3 规则 2（规则外推，登记本表） |

**DID 状态与验证结果枚举口径**：DID 状态沿用既有两值 `ACTIVE/REVOKED`（零新增，ADR-017 §2.3）；验证结果 `PASS/FAIL/UNAVAILABLE` 与原因码为**验证结论**（非 DID 状态枚举，不违反 §2.9）。

---

## 2. 接口契约表（`services/did` 扩展）

| 方法 | 路径 | 权限 | 入参 | 出参（data） | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/did/{did}` | **无**（只读公开要素；演示期回环边界，Q4=A，登记 ADR-017 补记） | path：完整 DID（`did:ctds:<主体编号>.<序号>`） | `{did, status: ACTIVE\|REVOKED, document: {did, publicKey{type,algorithm,valueHex}, controller, service[], created}}` | 1005B0003（未登记）、1005C0001（DID 格式非法） |
| POST | `/api/v1/did/{did}/verifications` | **无**（对外验证能力；演示期回环边界，Q3=A，登记 ADR-017 补记） | `{data: Base64, signature: Base64}` | `{did, result: PASS\|FAIL\|UNAVAILABLE, reason: null\|SIGNATURE_INVALID\|REVOKED\|SUBJECT_BINDING_FAILED\|NOT_REGISTERED\|BINDING_UNAVAILABLE, verifiedAt}` | 1005C0004（验证参数不合法）、1005S0002（验证内部错误） |

- 统一响应 `ApiResult`；时间戳秒级 ISO-8601（应用时钟，沿 ADR-017 §2.2）。
- **不留"演示专用"分支**：签名由调用方经 KMS 内部签名面（3.1.8 交付）生成后提交，本服务只验签。

## 3. 库表结构（`ctds_did` 库，Flyway `V2__create_verification_log.sql`）

```sql
CREATE TABLE did_verification_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  did         VARCHAR(128) NOT NULL COMMENT '被验证的 DID（未登记也记明）',
  result      VARCHAR(16)  NOT NULL COMMENT 'PASS / FAIL / UNAVAILABLE',
  reason      VARCHAR(32)  NULL     COMMENT 'SIGNATURE_INVALID / REVOKED / SUBJECT_BINDING_FAILED / NOT_REGISTERED / BINDING_UNAVAILABLE',
  occurred_at DATETIME     NOT NULL COMMENT '应用时钟（秒级，DB-22 教训：不由 DB 生成）',
  PRIMARY KEY (id),
  KEY idx_verification_did_time (did, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DID 验证留痕（行为 3 规则 3：不保存业务数据原文）';
```

- 不建外键、不存 data/signature 原文（红线：验证通道不是数据存储通道）。

## 4. 错误码（1005 段顺延，ADR-005 §3.5；登记 ADR-017 补记）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| 1005B0003 | `DID_NOT_REGISTERED` | 解析目标未登记（明确业务答复） | 400 |
| 1005C0004 | `DID_VERIFICATION_INPUT_INVALID` | 验证参数不合法（data/signature 空或非法 Base64） | 400 |
| 1005S0002 | `DID_VERIFICATION_INTERNAL_ERROR` | 验证内部错误（如注册表读取/文档公钥解析异常等非输入类故障） | 500 |

- 复用既有：`1005C0001`（DID 格式非法）、`1005S0001` 不变、DID 状态枚举不变。

## 5. 配置项与服务间衔接

| 服务 | 配置 | 说明 |
| --- | --- | --- |
| did | `ctds.did.subject.base-url: ${CTDS_DID_SUBJECT_BASEURL:}` | 主体绑定核验的调用地址；**未配置 = 绑定核验不可用**（验证返回 UNAVAILABLE，服务可独立启动） |
| did | 超时 | 连接 1s / 读取 3s（沿 `KmsKeyProvider`/`DidIssuanceClient` 先例） |
| subject | `ctds.auth.permissions` 新增一行 `did-internal: subject.read` | 服务间只读衔接的**专用角色**（不冒充 reviewer/applicant；仅只读权限）；did 侧调用头 `X-Ctds-Subject: did-service` + `X-Ctds-Roles: did-internal` |

- 绑定核验调用（**实施修正后口径**，见下）：`GET {base-url}/api/v1/subject/internal/subjects/{subjectNo}/admission` → 解析 `data.status == "ADMITTED"` 为通过；非 ADMITTED 为 B8；**业务码 1000C0003（主体编号不存在）= 绑定不成立（B8）**；其余网络失败/非 200/解析失败/其他业务错误为 B9。**不复制主体状态到 DID 库**（单一事实源）。
- 角色映射新增属配置扩展（不改任何既有鉴权行为与权限点语义），登记 ADR-017 补记。

**实施修正（2026-09-22，前置检查②实测留痕）**：

1. **原定复用既有 `GET /registrations/{subjectNo}` 不可行**：实测该端点带**对象级归属断言**（`OwnershipGuard`，ADR-016 §2.6 防申请编号枚举设计）——仅"申请人本人"或"持 `subject.review` 权限"可读；仅配 `subject.read` 的 `did-internal` 角色被拒为 400/1000C0003（运营证据：前置检查②首跑所得）。
2. **修正方案（最小暴露，不改既有安全口径）**：subject-service **新增内部只读端点** `GET /api/v1/subject/internal/subjects/{subjectNo}/admission`——仅返回 `{subjectNo, status}` 两字段（无注册信息、无脱敏字段、无流转记录）；功能级门槛沿用 `subject.read`（不带头 401 / 无权限 403）；**不落归属断言**（内部只读面，诚实边界登记 ADR-017 补记，沿 3.1.8 签发面/签名面先例）。
3. **不采纳的替代方案**：给 `did-internal` 追加 `subject.review` 权限可复用既有豁免——但该权限同时解锁审核端点（approve/reject），等于把"审批主体"能力授予 DID 服务，**安全面扩大不可接受**；放宽既有归属断言属破坏防枚举设计，同样不采纳。
4. 本修正不改 Q2 方向（绑定核验经服务间只读调用），仅改"调用哪个端点"（实施细节）；已随本文件留痕、登记 ADR-017 补记与交付说明，供 4 视角评审核查。

## 6. 边界值与异常行为

| 场景 | 契约行为 |
| --- | --- |
| did 为 null/空白/非 `did:ctds:` 前缀 | 1005C0001（400，文案"DID 标识不合法"） |
| data 或 signature 为 null/空白/非法 Base64 | 1005C0004（400） |
| data 为空字节（Base64 解码后长度 0） | 1005C0004（400） |
| signature 解码后长度异常（非 DER 结构） | `FAIL/SIGNATURE_INVALID`（业务失败，B6 口径；不 500） |
| 注册表读取异常/文档公钥损坏 | 1005S0002（500） |
| 主体服务不可达/超时/非 200/响应非 JSON | `UNAVAILABLE/BINDING_UNAVAILABLE`（B9；留痕） |
| 未登记 DID 的解析 | 1005B0003（400）；**不留痕**（解析无留痕，Q5=A） |
| 未登记 DID 的验证 | `FAIL/NOT_REGISTERED`（200）+ 留痕（B12） |
| 同一 DID 反复验证 | 每次独立留痕；无幂等语义（只读判定，ADR-007 不适用） |
| PENDING_ISSUE 记录（无 did 值） | 解析按"未登记"处理（该主体尚无对外 DID 标识） |

## 7. 测试锚点（先行 RED → 实现 GREEN）

1. **单元**：`DidResolutionServiceTest`（B1~B4：字段集/状态两值/未登记/格式非法）、`DidVerificationServiceTest`（B5~B9/B12 全分支：三查逐一否定 + 不可用 + 未登记；桩仓储/桩主体客户端）。
2. **集成（Testcontainers `ctds_did`）**：解析全链路（ACTIVE/REVOKED/未登记）；验证全链路（造数：签发 → KMS 真签名 → 验证 PASS；篡改 data → SIGNATURE_INVALID；吊销后 → REVOKED）；留痕落库三要素；**敏感扫描反向探针**（库表植入 64-hex/数据原文 → 命中必变红）。
3. **跨服务真实链路**：`did→subject` 指向不可达端口（沿 `DidIssuanceClientFailureIntegrationTest` 先例）→ UNAVAILABLE 断言；主体非 ADMITTED → B8。
4. **验签 round-trip（前置检查①）**：KMS `/signatures` 真签名 → 本服务验签通过（编码口径实测对齐）。
5. **无鉴权面锚定**：解析/验证无鉴权为**设计决定**，测试锚定"当前无鉴权"（加权限注解必变红的诚实断言，沿 3.1.8 签发面先例）——或至少断言不带身份头可访问（= 当前口径）。
6. **门禁**：checkstyle 0 违规；did 模块行覆盖 ≥80%（瞬时 jacoco 实测）；全量 `run-gates.ps1` GREEN。

## 8. 演示口径（剧本 S2 解析 / S4 验证承载）

- **解析**：`curl.exe -s http://127.0.0.1:8082/api/v1/did/did:ctds:S20260922000001.1` → 文档 + `ACTIVE`；对已吊销样本 `did:ctds:S20260921000001.1` → 文档 + `REVOKED`；构造不存在标识 → `1005B0003` 明确答复。
- **验证（S4）**：① 经 KMS 内部签名面代签（`POST /api/v1/key-pairs/{keyRef}/signatures`，keyRef = DID 的密钥引用）得到 Base64 签名；② 提交 `{data, signature}` → `PASS`；③ 篡改 data 再提交 → `FAIL/SIGNATURE_INVALID`；④ 对已吊销 DID 的真签名 → `FAIL/REVOKED`；⑤ 查 `did_verification_log` 可见留痕三要素。
- 命令原文随交付说明提供（免踩中文/编码坑，统一文件传参口径）。

## 9. 变更影响与登记

| 对象 | 说明 |
| --- | --- |
| ADR-017 补记 | 解析/验证接口契约 + 1005 段新码（B0003/C0004/S0002）+ `did_verification_log` 表 + **无鉴权面诚实边界**（解析/验证在回环内无身份门槛，解除条件 = 网关 + 令牌 + 服务间鉴权）+ `did-internal` 角色映射说明 |
| ADR-006/015/016 | 不动 |
| 剧本 | S2/S4 由本包承载（接口级）；如需补命令原文，随交付说明提供（剧本正文改动须 PO 审批） |
| 台账 | 进行中/下一包行随立卡更新 |

## 10. 实施前置检查项（编码会话第一步，逐项实测留痕）

1. **验签 round-trip 实测**：KMS `/signatures` 出 DER 签名 → `Sm2Service.verify(data, signature, 130-hex 公钥)` 通过（不凭记忆，实测对齐编码口径）。
2. **did→subject 角色映射落地**：subject 配置新增 `did-internal: subject.read`；实测带该头调用 `GET /registrations/{subjectNo}` 返回 200（不带头 → 401/403 反证）。
3. **构件重建**：did/subject 重新打包（承接 3.1.8 验收环境的运行中三服务需重启加载新构件）。