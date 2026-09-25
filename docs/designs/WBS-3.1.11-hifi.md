# WBS-3.1.11 DID 管理界面 · 高保真设计（编码契约）

- 低保真：`docs/designs/WBS-3.1.11-lofi.md`（Q1~Q8 裁决口径生效后本文才成立）
- 线框原型：`docs/designs/WBS-3.1.11-原型-DID管理界面.html`（信息架构与操作流的低保真线框，非视觉定稿）
- 规格锚点：`docs/specs/C-1.2-分布式数字身份DID.md` **行为 1（规则 3/4/5）、行为 2（规则 1~4）、行为 3（规则 3~4）、行为 4（规则 1~4）**、§6.6
- 剧本锚点：`docs/manuals/C-1.2-分布式数字身份DID-验收剧本.md` **S1 步骤 1~6 / S2 步骤 1~6 / S3 步骤 1~6**
- 契约锚点：`docs/adr/ADR-017-DID服务契约.md`（§2.4 / §2.9 / §2.2 应用时钟 / **§3 后续包约束**）、`ADR-005`（REST 封套 / 九位错误码 / 分页 §3.2 / 401/403 文案 / 审计契约）、`ADR-015`、`ADR-001`（前端栈）
- 上游能力：3.1.8（签发/重试/重签/吊销 + `did_identity`、`did_operation_log`）、3.1.9（解析/验证 + `did_verification_log`）、3.1.10（互认，`2c714f2`）——**只读复用**

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 编码契约生效 | **已确认（2026-09-25）**：编排师会话回复"**都按照建议口径推进**"（与 lofi Q1~Q8 同批一次确认，章程 2.6.3）——本文件 §1~§10 即编码契约，含接口契约表（§2）、边界值表（§7）、测试锚点 T1~T17（§8）与实施前置检查项（§10） |
| 确认后状态 | **可进入编码**（测试先行 RED → 实现 GREEN；编码会话第一步 = §10 前置检查项七项，逐项实测留痕） |

---

## 1. 行为清单（逐条对应规格与剧本）

| 编号 | 行为 | 规格 / 剧本来源 |
| --- | --- | --- |
| B1 | 记录列表：按主体申请编号 / 记录状态筛选 + 分页；每行含主体申请编号、签发序号、DID、记录状态、密钥引用、签发时间 | 行为 1 规则 4；剧本 S1 步骤 3/4 |
| B2 | 未入驻主体查不到有效记录（筛选无结果 → 明确空态文案，非报错） | 行为 1 规则 1、验收标准 4；剧本 S1 步骤 1/6 |
| B3 | 记录状态展示复用既有三值：`ACTIVE` 有效 / `REVOKED` 已吊销 / `PENDING_ISSUE` 待签发（**记录中间态**，界面文案显式标注）；解析端口径仍为两值 | 行为 2 规则 1；ADR-017 §2.3/§3 |
| B4 | 详情可见 DID 文档**公开要素**与状态；**不出现任何明文私钥**（只见 KMS 密钥引用） | 行为 1 规则 3、行为 2 规则 2；剧本 S1 步骤 4 |
| B5 | 吊销：理由必填（前端前置拦截 + 后端 `1005C0002` 兜底） | 行为 4 规则 2、验收标准 2；剧本 S3 步骤 2 |
| B6 | 吊销：提交后二次确认，弹窗**明示"吊销不可逆"**；选"取消" → 状态不变、**不产生留痕**（且不发起请求） | 行为 4 规则 2、验收标准 3；剧本 S3 步骤 3 |
| B7 | 吊销：确认后状态即时变"已吊销"，操作留痕五要素（操作人 / 时间 / 理由 / DID / 状态变更）在详情区可见 | 行为 4 规则 2~3、验收标准 1；剧本 S3 步骤 3 |
| B8 | 已吊销 DID：解析可见状态"已吊销"；验证一律不通过且原因为状态已吊销 | 行为 2 规则 4、行为 3 三查②；剧本 S3 步骤 4/5 |
| B9 | **无"恢复"操作**；重签由运营管理员在管理页发起 → 新序号 + 全新密钥对 + 新 DID，旧记录保留、旧标识永不复用 | 行为 4 规则 4、验收标准 5；剧本 S3 步骤 6 |
| B10 | 待签发记录可由运营在本页重试（成功后补齐留痕）；无待签发对象时如实提示 | 行为 1 规则 5 |
| B11 | 解析区：显示状态与公开要素；未登记 DID → 以**业务答复**样式明确提示"未登记该 DID"（非系统报错样式） | 行为 2 规则 3、验收标准 3；剧本 S2 步骤 5/6 |
| B12 | 演示代签：仅演示/调试期可用（**默认关闭的代码门槛**，关闭时明确提示）；对已登记 DID 生成 SM2 签名（**真实签名**，公私钥全在 KMS） | 规格 §6.6；剧本 S2 步骤 1、S3 步骤 1 |
| B13 | 验证：展示三查结论与失败原因（原因枚举复用 3.1.9 五值，不新增）；系统态"不可用"与业务态"不通过"**分开展示**（不冒充） | 行为 3 规则 2；剧本 S2 步骤 2/3、S3 步骤 5 |
| B14 | 验证留痕列表：时间 / DID / 结果 / 原因，分页；**不含任何数据原文** | 行为 3 规则 3、验收标准 4；剧本 S2 步骤 4 |
| B15 | 权限：管理面端点（记录 / 留痕 / 代签）一律 `did.admin`；无身份 `1000C0002`（401）、无权限 `1000C0005`（403），界面如实展示 | 规格 §5 外部依赖（RBAC 复用）；ADR-005 §3 |

## 2. 接口契约表（`services/did` 扩展，统一响应 `ApiResult`）

### 2.1 本包新增端点

| 方法 | 路径 | 权限 | 入参 | 出参（`data`） | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/did/records` | `did.admin` | query：`subjectNo`(可空,≤32) `status`(可空,∈`ACTIVE`/`REVOKED`/`PENDING_ISSUE`) `pageNum` `pageSize` | `PageResult<DidRecordView>` | `1000C0001`（分页参数非法，`PageQuery` 抛出）、`1005C0001`（`subjectNo`/`status` 非法） |
| GET | `/api/v1/did/records/{did}/operation-logs` | `did.admin` | 路径 `did` | `List<DidOperationLogView>`（**不分页**，理由见 §3） | `1005B0003`（未登记）、`1005C0001`（`did` 非法） |
| GET | `/api/v1/did/verification-logs` | `did.admin` | query：`did`(可空,≤128) `pageNum` `pageSize` | `PageResult<VerificationLogView>` | `1000C0001`、`1005C0001` |
| POST | `/api/v1/did/{did}/demo-signatures` | `did.admin` + **配置门槛**（§5） | body：`{data}`（待签原文，UTF-8，1~1024 字符） | `DemoSignatureView` | `1000C0003`（入口未启用）、`1005B0003`（未登记）、`1005B0001`（记录未完成签发，无密钥引用）、`1005C0001`（`data` 空/超限）、`1005S0002`（KMS 不可用） |

**出参视图字段（record，逐字段即契约）**：

| 视图 | 字段 | 说明 |
| --- | --- | --- |
| `DidRecordView` | `subjectNo`, `issuanceSeq`, `did`, `status`, `keyRef`, `createdAt`, `updatedAt` | `did`/`keyRef` 在 `PENDING_ISSUE` 时为 `null`；`status` 为 `DidStatus` 名（三值）；`createdAt`/`updatedAt` 秒级 ISO-8601（应用时钟） |
| `DidOperationLogView` | `operation`, `operator`, `reason`, `keyRef`, `statusFrom`, `statusTo`, `occurredAt` | `operation` ∈ `ISSUE`/`REISSUE`/`REVOKE`；`reason` 仅吊销非空；`operator` 为 `SYSTEM`（自动签发）或操作人身份（重签/重试/吊销） |
| `VerificationLogView` | `did`, `result`, `reason`, `occurredAt` | `result` ∈ `PASS`/`FAIL`/`UNAVAILABLE`；`reason` 为 3.1.9 五值之一或 `null`；**无任何数据原文字段** |
| `DemoSignatureView` | `did`, `data`, `signature`, `signedAt` | `data` = 原文的 Base64；`signature` = SM2 DER 的 Base64；`signedAt` 秒级 ISO-8601（演示期时间） |

### 2.2 本包调用（**复用，语义零改动**）

| 方法 | 路径 | 权限 | 用途（界面动作） |
| --- | --- | --- | --- |
| POST | `/api/v1/did/{did}/revocation` | `did.admin` | 吊销（B5~B7） |
| POST | `/api/v1/did/subjects/{subjectNo}/reissuances` | `did.admin` | 重签（B9） |
| POST | `/api/v1/did/subjects/{subjectNo}/issuance-retries` | `did.admin` | 重试（B10） |
| GET | `/api/v1/did/{did}` | 无（回环） | 详情与解析区的文档公开要素（B4/B11） |
| POST | `/api/v1/did/{did}/verifications` | 无（回环） | 验证（B13） |

> **不改语义承诺**：上表端点由 3.1.8/3.1.9 交付，本包仅从界面调用与复用既有错误码文案；如需调整语义，走变更流程（不在本包内改）。

## 3. 库表与数据来源（**零新增、零迁移**）

| 表 | 本包用途 | 关键列 |
| --- | --- | --- |
| `did_identity` | 记录列表 / 详情 | `subject_no`, `issuance_seq`, `did`, `status`, `key_ref`, `created_at`, `updated_at`（**无私钥列**） |
| `did_operation_log` | 单 DID 操作留痕（五要素） | `did`, `subject_no`, `operation`, `operator`, `reason`, `key_ref`, `status_from`, `status_to`, `occurred_at` |
| `did_verification_log` | 验证留痕列表 | `did`, `result`, `reason`, `occurred_at`（**无原文、无操作人**——沿 3.1.9 口径） |

- **操作留痕不分页的理由**：单 DID 的留痕天然有界——`did_operation_log` 的写入点仅"签发/重签完成"与"吊销"两处，同一 `did` 至多 2 行（ISSUE/REISSUE 一行 + REVOKE 一行）；分页徒增契约面。**验证留痕必须分页**（可随演示与业务持续增长）。
- 查询 SQL 一律**参数化**；排序/过滤字段如需扩展须走**列名白名单映射**（`PageQuery` 仅做格式闸门，注释已明示持久层责任）。
- 分页参数校验由 `common/pagination.PageQuery.of(...)` 承担（`pageNum≥1`、`1≤pageSize≤100`，越界抛 `1000C0001`），**不重复实现**。

## 4. 错误码（**零新增**，全部复用；登记 ADR-017 §10 补记）

| 码 | 常量 | 本包场景 | HTTP | 归属 |
| --- | --- | --- | --- | --- |
| `1000C0001` | `PARAM_INVALID` | 分页参数越界（`PageQuery` 抛出） | 400 | 复用（common-errorcode / common-pagination） |
| `1005C0001` | `DID_PARAM_INVALID` | `subjectNo`/`status`/`did` 过滤值非法、代签 `data` 空或 >1024 字符 | 400 | 复用（3.1.8） |
| `1005C0002` | `DID_REVOKE_REASON_REQUIRED` | 吊销理由空/空白（界面兜底） | 400 | 复用（3.1.8） |
| `1005C0003` | `DID_REVOKE_NOT_ACTIVE` | 非有效 DID 不可吊销（含重复吊销） | 400 | 复用（3.1.8） |
| `1005B0002` | `DID_NO_REVOKED_TO_REISSUE` | 无可重签对象 | 400 | 复用（3.1.8） |
| `1005B0001` | `DID_NO_PENDING_ISSUANCE` | 重试无待签发对象；**代签对象尚未完成签发（无密钥引用）** | 400 | 复用（3.1.8，代签场景文案明确） |
| `1005B0003` | `DID_NOT_REGISTERED` | 解析/验证/操作留痕/代签的对象未登记 | 400 | 复用（3.1.9） |
| `1005S0002` | `DID_VERIFICATION_INTERNAL_ERROR` | 代签时 KMS 不可达等内部故障 | 500 | 复用（3.1.9） |
| `1000C0003` | `RESOURCE_NOT_FOUND` | **演示签名入口未启用**（生产默认态） | 404 | 复用（common-errorcode） |
| `1000C0002` / `1000C0005` | `UNAUTHORIZED` / `FORBIDDEN` | 管理面未认证 / 无权限 | 401 / 403 | 复用（common-auth） |

- 对外文案一律**服务端常量**，禁止拼接域信息与用户输入（章程 4.3；界面只展示后端 `message`）。
- **`1005B0001` 在代签场景的复用理由**：该码语义为"未找到（可操作的）签发记录"，代签对象尚未完成签发（`key_ref` 为空）时语义一致，文案由服务端给"该主体 DID 尚未完成签发，无法生成演示签名"；**不为此新增错误码**（ADR-017 §3）。

## 5. 配置与装配

| 项 | 内容 |
| --- | --- |
| 新增配置（did 服务） | `ctds.did.demo-signature.enabled: ${CTDS_DID_DEMO_SIGNATURE_ENABLED:false}`——**默认 false = 生产禁用**；演示/调试期以环境变量显式开启。关闭时 `POST /{did}/demo-signatures` 返回 `1000C0003` + 文案"演示签名入口未启用（仅演示/调试期）" |
| 新增依赖（did 服务 pom） | 平台内部模块 `com.ctds:common-pagination`（ADR-005 §3.2 分页契约；**非第三方坐标、无版本变更**） |
| KMS 签名衔接 | `DidKmsClient` 扩展 `String sign(String keyRef, String dataBase64)`；实现 `DidKmsHttpClient` 调 KMS **既有签名面** `POST /api/v1/key-pairs/{keyRef}/signatures`（`{data: Base64}` → `{signature: Base64}`，SM2 DER；ADR-017 §2.6），超时口径沿用既有（连接 1s / 读取 3s） |
| 代签流程 | 记录查找（`findByDid`）→ 校验（已登记、有 `key_ref`）→ 原文 Base64 → KMS 签名 → 返回视图；**全程不接触私钥材料**（私钥不出 KMS，红线 7）；**原文不入库、不落日志**（仅 WARN 级记录 `did` + `keyRef` + 结果，不含原文） |
| 前端装配 | 复用 `apiJson`（`src/api/client.ts`）与既有 `ElMessageBox.confirm` 二次确认范式（`review/DetailView.vue` 先例）；**零新增前端依赖** |
| 门禁 | **零配置改动**（红线 3） |

## 6. 前端设计（真实 Vue 页面 + 界面说明书）

> **界面说明书 = 本节 + 线框原型 `docs/designs/WBS-3.1.11-原型-DID管理界面.html`**（沿 WBS-3.1.5 先例：低保真线框只表达信息架构与操作流，视觉由 Element Plus 与既有 `theme.css` 定稿）。

### 6.1 路由与菜单

| 路由 | name | meta | 说明 |
| --- | --- | --- | --- |
| `/did` | `did-management` | `{ title: 'DID 管理', menu: true, menuOrder: 8, icon: 'Key', permission: 'did.admin' }` | 唯一新增菜单项；排在现有最大序号（`subject/progress` = 7）之后 |
| `/did/demo` | `did-demo` | `{ title: 'DID 演示与验证', permission: 'did.admin' }` | **不占菜单**（`menu` 缺省）；由 `/did` 页内"演示与验证"按钮进入，页面提供"返回 DID 管理" |

- 守卫行为沿既有实现：未登录 → `/login`；无权限 → `/dashboard?denied=1`（普通演示角色可见性由菜单过滤 + 守卫共同保证，admin 才出现该菜单）；
- 既有测试须同步更新：`src/router/router.spec.ts`（菜单路由字段断言、`menuOrder` 最大值 7→8、权限白名单）、`src/layouts/MainLayout.spec.ts`（按角色菜单项**硬计数**：普通用户 **5** 项不变 / admin **7 → 8** 项）。

### 6.2 页面 A：DID 管理（`views/did/IndexView.vue`）

**结构（自上而下）**：

1. **筛选区**（`el-card`）：主体申请编号（`el-input`，`clearable`，占位"如 S20260925000001"）+ 记录状态（`el-select`：全部 / 有效 / 已吊销 / 待签发）+ "查询"（`el-button type="primary"`）+"重置"；回车即查询；
2. **列表区**（`el-table` + `v-loading` + `empty-text`）：列 = 主体申请编号 / 签发序号 / DID 标识（超长省略 + `title` 提示 + 复制按钮）/ 记录状态（`el-tag`）/ 密钥引用 / 签发时间 / 操作；
3. **分页**（`el-pagination`：`layout="total, prev, pager, next"`，`@current-change` 重拉）；
4. **详情抽屉**（`el-drawer`，宽度 60%）：① 记录要素（基础信息 `el-descriptions`）② DID 文档公开要素（点击"查看 DID 文档"→ 调解析端点；展示 `status` 与 `document` 的公钥 / controller / service / created；**固定提示"仅公开要素，界面不展示任何私钥"**）③ 操作留痕（`el-table`：操作类型 / 操作人 / 时间 / 理由 / 状态变更 / 密钥引用）④ 顶部"演示与验证"跳转按钮。

**行内操作（按记录状态派生，不可用动作不渲染）**：

| 记录状态 | 可用操作 |
| --- | --- |
| 有效（`ACTIVE`） | 查看详情 / **吊销** |
| 已吊销（`REVOKED`） | 查看详情 / **重签** |
| 待签发（`PENDING_ISSUE`） | 查看详情 / **重试** |

> **无"恢复"操作**：界面不存在任何恢复/撤销吊销的入口（行为 4 规则 4；源集扫描守卫 T17 可证伪）。

**空态文案**：`未找到符合条件的主体 DID 记录（未入驻主体不签发 DID）`（行为 1 规则 1、验收标准 4；剧本 S1 步骤 1/6 的"不存在有效 DID 记录"以空态呈现，**不是报错**）。

**吊销交互（规格行为 4 规则 2 强制项，逐句对应剧本 S3 步骤 2/3）**：

1. 点击"吊销" → 打开吊销弹窗（`el-dialog`）：标题"DID 吊销"；只读展示目标 DID；理由输入（`el-input type="textarea"`，`maxlength=256`，`show-word-limit`，占位"请填写吊销理由，如：私钥疑似泄露"）；**固定提示文案"吊销不可逆：确认后该 DID 永久失效，不可恢复，需继续使用身份时须重新签发"**；
2. 点击"下一步" → 理由 `trim()` 为空：`ElMessage.warning('吊销理由必填')` 并**不发起任何请求**（后端 `1005C0002` 为第二道兜底）；
3. 通过后弹出二次确认：`ElMessageBox.confirm('吊销后该 DID 永久失效且不可恢复（吊销不可逆）。确认吊销 DID：{did}？', '吊销二次确认', { type: 'warning', confirmButtonText: '确认吊销', cancelButtonText: '取消' })`；
4. **取消分支**：`catch` 后直接 `return` → **不发起请求**（状态不变、无吊销留痕；剧本 S3 步骤 3 的"先取消、再重新提交"由此承载）；
5. **确认分支**：调 `revokeDid(did, reason)` → 成功：`ElMessage.success('已吊销该 DID')` + 关闭弹窗 + **刷新列表与详情**（详情留痕区出现 REVOKE 行，五要素齐备）；
6. 错误展示：`1005C0002` → 如实展示后端文案（"吊销理由必填"）；`1005C0003` → 如实展示（"非有效 DID 不可吊销"）并刷新该行状态；`1000C0005` → "无权限执行该操作"（ADR-005 文案口径）；其它 → "操作失败，请稍后重试"。

**重签 / 重试交互**：

- 重签：`ElMessageBox.confirm('重签将为该主体生成全新的 DID 与密钥对（签发序号 +1）；旧 DID 永久保留且不可复用。确认重签？','重签确认',{type:'warning'})` → 调 `reissueDid(subjectNo)` → 成功提示 + 刷新（列表出现新记录、旧记录仍在）；`1005B0002` 如实展示；
- 重试：`ElMessageBox.confirm('将对该主体的待签发记录重新执行签发，确认重试？','重试确认')` → 调 `retryIssuance(subjectNo)` → 成功提示 + 刷新；`1005B0001` 如实展示。

### 6.3 页面 B：DID 演示与验证（`views/did/DemoView.vue`）

页面顶部提示条（`el-alert type="info"`）："仅演示/调试期使用：平台侧演示签名入口在生产环境默认关闭（规格 §6.6）。"页面自上而下四区：

1. **演示代签（区块 A）**：DID 输入（默认从列表带入的 `?did=`）+ 待签文本（`textarea`，占位示例"蓝天数据科技有限公司确认接入城市可信数据空间"）+ "生成演示签名"按钮 → 结果区展示 `data`（Base64）与 `signature`（Base64）两个只读输入框（**可复制**，满足剧本 S3 步骤 1"留存签名结果"）+ 提示"已自动填入下方验证区"；错误：`1000C0003` → 以 `el-alert type="warning"` 展示"演示签名入口未启用（仅演示/调试期）"（**环境未开启 ≠ 操作失败**，两者样式分开）；
2. **验证（区块 B）**：`data`（Base64）+ `signature`（Base64）输入框（可由 A 自动填充，亦可手工粘贴——承载剧本 S3 步骤 5"用步骤 1 留存的签名复核"）+"验证"按钮 → 结果卡：结果（通过 / 不通过 / 不可用）+ 失败原因中文（签名核验失败 / 状态已吊销 / 主体绑定不成立 / 未登记 / 绑定服务不可用）+ 验证时间；`UNAVAILABLE` 以 `el-alert type="warning"`（**系统态**）呈现，与 `FAIL`（业务态，`type="error"`）**样式分离，不冒充**；
3. **解析（区块 C）**：DID 输入 + "解析"按钮 → 状态（有效 / 已吊销）+ DID 文档公开要素（公钥 / controller / service / created）；**未登记**（`1005B0003`）→ 以 `el-alert type="warning"` 展示后端文案"未登记该 DID"，**不使用报错弹窗/红色错误样式**（行为 2 规则 3："不以系统异常样式呈现"）；
4. **验证留痕（区块 D）**：`el-table`（时间 / DID / 结果 / 原因）+ 分页 + "刷新"；固定说明"留痕仅含时间/DID/结果/原因，不保存任何业务数据原文"（行为 3 规则 3）。

### 6.4 状态与文案映射（`src/constants/did.ts`，集中收口，禁止页面内散写字面量）

| 映射 | 取值 |
| --- | --- |
| `RECORD_STATUS_LABELS` | `ACTIVE`→"有效"；`REVOKED`→"已吊销"；`PENDING_ISSUE`→"待签发（记录中间态）" |
| `RECORD_STATUS_TYPES` | `ACTIVE`→`success`；`REVOKED`→`danger`；`PENDING_ISSUE`→`warning` |
| `OPERATION_LABELS` | `ISSUE`→"签发"；`REISSUE`→"重签"；`REVOKE`→"吊销" |
| `VERIFICATION_RESULT_LABELS` | `PASS`→"通过"；`FAIL`→"不通过"；`UNAVAILABLE`→"不可用（系统态）" |
| `VERIFICATION_REASON_LABELS` | `SIGNATURE_INVALID`→"签名核验失败"；`REVOKED`→"状态已吊销"；`SUBJECT_BINDING_FAILED`→"主体绑定不成立"；`NOT_REGISTERED`→"未登记"；`BINDING_UNAVAILABLE`→"绑定服务不可用" |

- 未感知的取值一律**原样显示后端返回**（不得吞掉、不得显示空白）。

### 6.5 API 模块与示例（`src/api/did.ts`）

- 类型就地定义（逐字段对齐 §2），分页类型复用 `PageData<T>`（与 `common/pagination.PageResult` 字段同构：`list/total/pageNum/pageSize/totalPages`）；
- **角色头（Q7 采用口径）**：本模块所有请求附 `X-Ctds-Roles: 'applicant,reviewer,admin'`（`did` 服务把 `admin` 映射为 `did.admin`）——**不改** `client.ts` 的全局 `demoRolesHeader()`，避免在 example-service 意外激活 `greeting.delete`（最小权限面）；`X-Ctds-Subject` 复用 `getDemoSubject()`；
- 函数清单（命名沿 `api/subject.ts` 风格）：`fetchDidRecords`、`fetchOperationLogs`、`fetchVerificationLogs`、`demoSign`、`revokeDid`、`reissueDid`、`retryIssuance`、`resolveDid`、`verifySignature`。

## 7. 边界值与异常行为

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| E1 | `subjectNo` 过滤值为空串/空白 | 视为不筛选（不报错） |
| E2 | `subjectNo` 超 32 字符 | `1005C0001`（400） |
| E3 | `status` 非三值之一（如 `PENDING`） | `1005C0001`（400） |
| E4 | `pageNum=0` / `pageSize=101` / `pageSize=0` | `1000C0001`（`PageQuery` 抛出，400） |
| E5 | 列表无数据（含未入驻主体） | `total=0`、`list=[]`、界面空态文案（**非错误**） |
| E6 | `subjectNo` 有历史多代 DID（重签过） | 全部记录按签发序号倒序可见（新在前）；旧记录 `REVOKED` 保留 |
| E7 | 待签发记录 | `did`/`keyRef` 为 `null`，状态 `PENDING_ISSUE`，界面显示"待签发（记录中间态）"并提供"重试"，**不提供**吊销/重签 |
| E8 | 操作留痕为 `PENDING_ISSUE` 记录 | 返回空列表（尚未完成签发，无留痕）——界面显示"暂无操作留痕" |
| E9 | 操作留痕对象未登记 | `1005B0003`（400） |
| E10 | 吊销理由空白提交 | 前端拦截（零请求）+ 后端 `1005C0002` 兜底（直连接口时） |
| E11 | 二次确认中选"取消" | **零请求**、状态不变、无留痕 |
| E12 | 吊销理由 >256 字符 | 前端 `maxlength` 截断 + 后端 `1005C0001` 兜底 |
| E13 | 重复吊销同一 DID（并发或旧界面） | `1005C0003` 如实展示 + 刷新行状态 |
| E14 | 重签对象无已吊销记录 | `1005B0002` 如实展示 |
| E15 | 重试对象无待签发记录 | `1005B0001` 如实展示 |
| E16 | 解析未登记 DID | `1005B0003` + 文案"未登记该 DID"，**warning 样式**（业务答复） |
| E17 | 验证失败三态 | `FAIL` + 原因（签名核验失败 / 状态已吊销 / 主体绑定不成立）逐字展示 |
| E18 | 验证通道不可用 | `UNAVAILABLE`（系统态）以 warning 样式展示，**不得显示为"不通过"** |
| E19 | 代签入口未启用（默认态） | `1000C0003` + "演示签名入口未启用（仅演示/调试期）"，且**不产生任何 KMS 调用** |
| E20 | 代签 `data` 为空 / >1024 字符 | `1005C0001`（400） |
| E21 | 代签对象未登记 / 未完成签发 | `1005B0003` / `1005B0001`（文案见 §4） |
| E22 | 代签时 KMS 不可达 | `1005S0002`（文案"签名服务暂不可用，请稍后重试"）；**不落任何留痕** |
| E23 | 管理面未认证 / 无权限 | `1000C0002`（401）/ `1000C0005`（403），界面如实展示 |
| E24 | 任一列表/详情出参 | **不出现任何私钥/材料字段**；验证留痕**不含数据原文**（列级断言） |
| E25 | 前端普通演示角色访问 `/did` | 守卫跳 `/dashboard?denied=1`；菜单不出现该项 |

## 8. 测试锚点（先行 RED → 实现 GREEN）

| 编号 | 锚点 | 类型 |
| --- | --- | --- |
| T1 | 记录列表：分页与过滤（`subjectNo` / `status`）正确，`total`/`totalPages` 正确；默认 `pageNum=1`/`pageSize=10` | 集成 + 单元 |
| T2 | 记录列表权限：无身份 401（`1000C0002`）/ 无权限 403（`1000C0005`） | 集成 |
| T3 | 记录列表含"待签发"记录：`did`/`keyRef` 为 `null`、状态 `PENDING_ISSUE`（`DidStatus` 三值口径，无新增枚举） | 单元 + 集成 |
| T4 | 单 DID 操作留痕：签发 + 吊销后返回两条，**五要素齐备**（操作人 / 时间 / 理由 / DID / 状态变更）；待签发记录返回空列表 | 集成 |
| T5 | 操作留痕对象未登记 → `1005B0003` | 单元 |
| T6 | 验证留痕列表：分页 + `did` 过滤 + **列级断言无原文/无私钥字段** | 集成 |
| T7 | 演示代签（开关开启）：返回 Base64 签名，**用该 DID 文档公钥经 `common-crypto` 验签通过**（真实验签，非格式断言） | 集成 |
| T8 | 演示代签（**默认关闭**）：`1000C0003` + 文案；且**零 KMS 调用**（端口注入计数） | 单元 + 集成 |
| T9 | 代签边界：未登记 `1005B0003` / 未完成签发 `1005B0001` / `data` 空或超限 `1005C0001` / KMS 不可达 `1005S0002` 且**无留痕** | 单元 |
| T10 | 界面列表渲染：筛选、分页、记录状态标签（三值）、**空态文案**、加载与错误态 | 前端单元 |
| T11 | 吊销交互双向：① 理由留空 → warning 且**零请求**；② 填理由 → 调 `ElMessageBox.confirm`；**取消 → 零请求（状态不变、无留痕）**；③ 确认 → 调吊销接口 + 成功提示 + 刷新 | 前端单元 |
| T12 | 后端兜底如实展示：`1005C0002` / `1005C0003` / `1005B0002` / `1005B0001` 逐码断言文案透传（不吞、不改写） | 前端单元 |
| T13 | 重签 / 重试入口：按记录状态派生可用动作（有效→吊销；已吊销→重签；待签发→重试）；重签成功后列表刷新出现新记录且旧记录保留 | 前端单元 |
| T14 | 代签 → 验证链路：代签结果自动填充验证区；验证结论与原因（签名核验失败 / 状态已吊销）逐字展示；`UNAVAILABLE` 与 `FAIL` 样式分离 | 前端单元 |
| T15 | 解析区：有效/已吊销展示状态与公开要素；**未登记以 warning 业务答复样式展示**（非报错弹窗） | 前端单元 |
| T16 | 权限与菜单：admin 可见"DID 管理"菜单（计数 7→8）、普通用户不可见（5 不变）；守卫对 `/did` 的拦截行为 | 前端单元 + 路由 |
| T17 | **防回退守卫（可证伪）**：① `frontend/src/views/did/**` 与 `src/api/did.ts` 不得直接出现 `fetch(`（必须经 `apiJson`）；② 界面源集不得出现"恢复吊销"类动作（`restore`/`unrevoke`/`恢复`）与私钥字样（`privateKey`）；③ 后端 `services/did` 源集不得出现 `demo-signature` 与私钥读材料调用（材料读取路径 `requireDataKey` 仅 SM4） | 源集扫描（守卫） |

> 前端测试与源文件同目录（`views/did/IndexView.spec.ts`、`views/did/DemoView.spec.ts`），Mock 方式沿既有范式（`vi.mock` + `@vue/test-utils` + Element Plus 真实挂载；二次确认路径用 `vi.spyOn(ElementPlus.ElMessageBox, 'confirm')`）。**断言口径**：每条规格验收标准至少一条正向 + 一条"绕过被拒"用例（章程 4.4）。

## 9. 演示口径 · 变更影响与登记

### 9.1 剧本承载对照（界面入口）

| 剧本步骤 | 界面动作 | 判定（不变） |
| --- | --- | --- |
| S1-1 / S1-6 | `/did` 按主体编号筛选（未入驻） | 无有效 DID 记录（空态） |
| S1-3 / S1-4 | 列表行 + 详情（要素、密钥引用；无明文私钥） | 存在"有效"记录且四要素齐备 |
| S1-5 | 重放签发触发后刷新列表 | 有效 DID 仍只有一条 |
| S2-1 | `/did/demo` 区块 A 代签 | 签名生成成功（可复制） |
| S2-2 / S2-3 | 区块 B 验证（原文未改 / 改一字） | 通过 / 不通过 + 签名核验失败 |
| S2-4 | 区块 D 验证留痕 | 含时间/DID/结果/原因，无原文 |
| S2-5 / S2-6 | 区块 C 解析（未登记样例 / 蓝天 DID） | "未登记"业务答复 / 仅公开要素 |
| S3-1 | 区块 A 代签云栖 DID（**吊销前**） | 签名生成成功并留存 |
| S3-2 | 弹窗理由留空提交 | 提示"吊销理由必填" |
| S3-3 | 二次确认先取消、再确认 | 取消：状态不变无留痕；确认：已吊销 + 五要素齐备 |
| S3-4 | 详情"查看 DID 文档" | 文档可见、状态"已吊销" |
| S3-5 | 区块 B 用留存签名验证 | 不通过 + 状态已吊销 |
| S3-6 | 列表可用动作 | 无"恢复"；旧记录保留可追溯 |

### 9.2 走查材料与前置

- 走查材料目录：`build-output/demo-files/did-management/`（gitignored）：界面走查步骤卡（含每步预期与截图位）、演示库现状说明；
- 环境前置：Docker 三容器 + 三服务（8080/8081/8082 回环）+ 前端 dev（5173，**仅本机、禁止 `--host`**，剧本演示前提 L9）；演示签名入口需以环境变量 `CTDS_DID_DEMO_SIGNATURE_ENABLED=true` 启动 did 服务（**默认关闭**）；
- 剧本修订：S1/S2/S3 的界面入口名称按实际交付核对修订（业务判定标准不变），修订后**提示 PO 审批**（规格/剧本同步口径）；S4 仅名称核对。

### 9.3 变更影响与登记

| 影响面 | 登记动作 |
| --- | --- |
| ADR-017 | 新增 **§10 变更补记（WBS-3.1.11，2026-09-2x）**：管理面读数端点契约（记录列表 / 操作留痕 / 验证留痕）、演示签名边界（默认关闭 + 复用 `1000C0003`）、记录状态展示口径（三值 + 中间态标注，解析仍两值）、前端页面级角色头口径 |
| 错误码 | 零新增（§4 复用清单）；ADR 补记登记"复用而非新增"的裁定 |
| 状态枚举 / 库表 | 零新增、零迁移（§3） |
| 依赖 | 新增平台内部模块 `common-pagination`（did-service）；前端零新增 |
| 配置 | did 服务新增 `ctds.did.demo-signature.enabled`（默认 false）；门禁配置零改动 |
| 剧本 | S1~S3 界面入口名称核对修订（业务判定不变，PO 审批提示） |
| 前端 | 1 菜单项 + 2 路由 + 2 视图 + 2 模块；2 处既有测试断言同步 |
| 台账 / 日志 | 立卡、设计确认、编码、评审、走查各自留痕（本卡 §四） |

## 10. 实施前置检查项（编码会话第一步，逐项实测留痕）

1. 冷启动读序：`AGENTS.md` → 最新日志 → 本卡 → lofi/hifi 确认记录 → 台账 → ADR-017 / ADR-005 → 规格 C-1.2 行为 1~4 + §6.6 → 剧本 S1/S2/S3；分支 `feat/C-1.2-DID管理界面` 与工作树状态核验；
2. 环境核验：Docker 三容器 Up + 三服务（8080/8081/8082）`/actuator/health` 200（**带 `CTDS_DB_PASSWORD`**）；
3. **KMS 签名面实测**：`POST /api/v1/key-pairs/{keyRef}/signatures`（`{data: Base64}` → `{signature: Base64}`）可用性 + 口径（DER / Base64）+ **用对应 DID 公钥经 `common-crypto` 验签通过**（真实证据，不只是 200）；
4. 既有能力基线留痕：解析 / 验证 / 吊销 / 重签 / 重试端点的实际行为与错误码（作为"只读复用、语义零改动"的基线）；
5. 分页契约核对：`common/pagination` 的 `PageQuery`/`PageResult` 与前端 `PageData` 字段逐一比对（`list/total/pageNum/pageSize/totalPages`）；
6. 前端基线：`npm run test` 全绿 + 用例数；`router.spec.ts` / `MainLayout.spec.ts` 的菜单计数断言现状（admin 7 / user 5）；
7. 演示库现状：可用的"有效 DID / 已吊销 DID"主体（走查数据准备，不在本包造数）。

## 11. 实施补正说明（2026-09-25 编码会话，逐条留痕；业务判定与契约语义均未变）

| 编号 | 设计原文（本文件内） | 实施实际 | 理由与影响 |
| --- | --- | --- | --- |
| P1 | §4 错误码表：`1000C0003`（演示签名入口未启用）**HTTP 404** | **HTTP 400** | 平台统一按错误类型映射（`common-errorcode.ErrorType`：C/B→400、S→500；404 仅用于未映射路径），主体服务既有 `1000C0003` 亦为 400（`SubjectRegistrationIntegrationTest` 锚定）。**错误码与文案不变**，前端按 `code` 判定不受影响；若单独为此码放行 404 将破坏全平台一致性，故按平台口径实现并留痕 |
| P2 | §4/E22：`1005S0002` 文案"签名服务暂不可用，请稍后重试" | 对外响应文案为平台通用"**系统繁忙，请稍后重试**" | `GlobalExceptionHandler` 对 S 类错误统一遮蔽内部文案（不暴露内部实现，章程 4.3）；服务端仍抛业务文案（单元测试 `DidDemoSignatureServiceTest` 断言其可见）。E22 判定要点"不落留痕 + `1005S0002`"不变 |
| P3 | §8 T17③："后端 `services/did` 源集不得出现 `demo-signature` 与私钥读材料调用" | 落为两条可证伪断言：①主源集不得出现 `requireDataKey`/`material_cipher`/`privateKey`/`PrivateKey`；②`application.yml` 该开关默认必须为 `false` 且主源集不得硬编码开关名 | 字面不判"源集出现 `demo-signature` 字样"——该字样是配置键与端点路径的必要组成，字面判它必然自相矛盾；改判"不得读密钥材料 + 不得硬编码开启"，与红线 7 及 §5 配置门槛同义（守卫测试 `DemoSignatureGuardTest`，含对测试源集的反向探针） |
| P4 | §7 E8："操作留痕为 `PENDING_ISSUE` 记录 → 返回空列表" | 待签发记录**无 DID 值**（`did` 为 `null`），无法按 `{did}` 寻址 → 界面按空态"暂无操作留痕"呈现且**不发请求**；"空列表"路径由"已登记但留痕为空"用例承载 | 记录中间态的 `did` 列本身为空（ADR-017 §2.2），原表述在数据模型上不可达；界面行为（显示"暂无操作留痕"）与判定不变 |
| P5 | §6.2 吊销弹窗固定提示："吊销不可逆：确认后该 DID 永久失效，**不可恢复**，需继续使用身份时须重新签发" | 改为"……永久失效且**不可再次启用**，需继续使用身份时须重新签发" | T17② 守卫禁止界面源集出现回滚类动作字样（含注释），"不可恢复"字面与守卫冲突；**二次确认明示"吊销不可逆"（剧本 S3 步骤 3 强制项）与不可逆语义完整保留** |
| P6 | §9.3 变更影响登记：前端 = 1 菜单 + 2 路由 + 2 视图 + 2 模块（未列开发期转发） | 追加：`frontend/vite.config.ts` 新增 `/api/v1/did` → `http://localhost:8082` 转发（键序在通用 `/api` 之前） | DID 服务独立端口 8082，原配置仅转发 `/api`→8080，界面在 5173 下无法触达 DID 服务（剧本 S1/S2/S3 界面走查则不可执行）。属**开发期转发配置**，不改任何服务契约与门禁配置；如实登记，非静默偏离 |
| P7 | §9.3 ADR 登记动作："新增 §10 变更补记" | 落在 **ADR-017 §9** | ADR-017 实际章节为 §1~§8，补记按文档顺序取 §9（规划稿"§10"为预留序号笔误）；内容与规划一致 |
