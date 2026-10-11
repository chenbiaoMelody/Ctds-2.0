# DB-41 高保真设计：前端 api 公用横切四族收敛（V0.9，待确认）

> 任务卡：`docs/tasks/DB-41-前端api横切收敛-2026-10-12.md` ｜ 低保真：`docs/designs/DB-41-lofi.md`
> 状态：**V0.9 待编排师一次确认**（与 lofi 同批；确认后 V1.0 = 编码契约，确认记录落本文件末节）

## 1. 现状盘点（立卡批逐项实证，2026-10-12）

### 1.1 四族证据表

| 族 | 证据（文件:行） | 副本数 / 分裂形态 |
| --- | --- | --- |
| ① 路径段编码分裂 | `did.ts` 8 处全 `encodeURIComponent`（:147/163/172/177/186/195/203 + subjectNo 1 处）；`subject.ts` 10 处 subjectNo 插值仅 :122 一处编码、其余 9 处裸插（:116/126/133/140/150/160/164/174/181）；`contract.ts` templateNo×5 + contractNo×16 = 21 处字符串段裸插；`catalog.ts` vocabularyCode×1 裸插（:327），productId/datasetId 数值型；`space.ts` 全数值型 | did 全编 / subject 1/10 / catalog·space·contract 0 编 |
| ② pageQuery 三副本 + 变体 | `contract.ts:357-360` ≡ `catalog.ts:219-222` ≡ `space.ts:166-169`（逐字同）；`did.ts:126-131` 变体（单对象入参、返 URLSearchParams 无 `?`）；`subject.ts:170` 内联拼 `?pageNum=${}&pageSize={}` | 逐字 3 副本 + 1 变体 + 1 内联 |
| ② 空主体拦截三副本 | `contract.ts:347-355` ≡ `catalog.ts:209-217` ≡ `space.ts:156-164`：`if (!getDemoSubject().trim()) → ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP)` + 页面级头合并 → `apiJson`；配套 `contractHeaders/catalogHeaders/spaceHeaders` 三份同构包装（各仅被本域 json 包装引用） | 逐字 3 副本（did/subject 无此拦截——现状即无） |
| ③ rolesHeader 同构 | `stores/demoIdentity.ts`：`catalogRolesHeader():61` ≡ `contractRolesHeader():66` 函数体逐字同（`getActorMode()==='operator' ? 'admin' : 'provider'`）；`spaceRolesHeader():56` 同构不同值（`'applicant,platform.operator' : 'applicant'`）；另有 `client.ts:24` 全局 `demoRolesHeader()`（**不动**，T18 体系基座） | 第 3 份同构（+space 同构不同值） |
| ④ 分页签名两套 | 单对象 9 函数：contract（listTemplates/listManageTemplates/listTemplateActionLogs/listMyContracts/getUsageSummary/listContractActionLogs/listGovernanceContracts）+ did（fetchDidRecords/fetchVerificationLogs）；位置参数 17 函数：catalog 10 + space 6 + subject 1（fetchReviewQueue） | 两套并存 |

### 1.2 调用面计数（Q2-A 转换工作量底盘）

| 面 | 计数 | 说明 |
| --- | --- | --- |
| 视图调用（17 函数） | catalog 14 / space 7 / subject 1 = **22 处** | `.vue` 内 `grep -c` 实测；contract 9 处已单对象零转换 |
| spec/TS 调用面（17 函数） | **30 处** | `*.spec.ts` + 非 api `.ts` 实测（含各域 api spec 与视图 spec） |
| SourceGuard 约束 | 四件仅锁 `fetch(` 计数=0 | 不锁 pageQuery/编码形态 → 收敛不触雷（T20 相容：零新增 fetch） |

## 2. 目标形态（接口契约）

### 2.1 `client.ts` 新增导出面（唯一新增公共 API，其余零新增）

```ts
/** 分页查询串（全仓唯一副本；extra = 可选过滤项，URLSearchParams 负责值编码）。 */
export function pageQuery(pageNum: number, pageSize: number, extra: Record<string, string> = {}): string

/** 域请求工厂（DB-41②③ 收敛点）：空主体前置拦截（零请求 → ApiError('1000C0002')）
 *  → 附页面级角色头（X-Ctds-Roles = rolesHeader()，调用方显式头可覆盖）→ apiJson。
 *  接入域：contract / catalog / space（现状有拦截三域）；did / subject 不接入（见 §4.2）。 */
export function makeDomainJson(rolesHeader: () => string): <T>(path: string, options?: RequestInit) => Promise<T>
```

- 工厂体内逻辑 = 现三域副本逐字等价搬移（拦截条件、错误码、文案、头合并优先级均不变）；
- `DEMO_SUBJECT_REQUIRED_TIP` 改由 `client.ts` 从 `../constants/space` 导入（文案落点不动），三域文件该 import 删除。

### 2.2 `demoIdentity.ts` 私有工厂（导出面不变）

```ts
function pageRolesHeader(operatorRoles: string, normalRoles: string): string {
  return getActorMode() === 'operator' ? operatorRoles : normalRoles
}
export function spaceRolesHeader(): string    { return pageRolesHeader('applicant,platform.operator', 'applicant') }
export function catalogRolesHeader(): string  { return pageRolesHeader('admin', 'provider') }
export function contractRolesHeader(): string { return pageRolesHeader('admin', 'provider') }
```

- 三个导出名与签名不变 → 视图与 api 模块调用面**零变更**；`client.ts:24` 全局 `demoRolesHeader()` **不动**。

### 2.3 三域文件改造样例（contract；catalog/space 同形）

```ts
// 改造前（contract.ts:341-367）：contractHeaders + contractJson + pageQuery 三段
// 改造后：
const contractJson = makeDomainJson(contractRolesHeader)
function postJson<T>(path: string, body?: unknown): Promise<T> { /* 不变 */ }
```

- 域文件 import 收缩为 `{ makeDomainJson, pageQuery }`（`apiJson`/`ApiError`/`getDemoSubject`/`DEMO_SUBJECT_REQUIRED_TIP`/`xxxHeaders` 全部退场）；
- `contractJson/catalogJson/spaceJson` 变量名保留（域内 postJson 与端点函数调用面零变更）。

## 3. 收敛项明细

### 3.1 ①编码统一清单（Q1-A 口径：字符串型路径段一律 `encodeURIComponent`，数值型免）

| 文件 | 改造点 | 数量 |
| --- | --- | --- |
| `contract.ts` | templateNo（getTemplate/listTemplateVersions/reviseTemplate/disableTemplate/enableTemplate）+ contractNo（proposeClauses/confirmClauses/terminateNegotiation/signContract/refuseSignature/releaseConsent/getContractDetail/listClauseVersions/verifySignatures/getUsageSummary/simulatePolicy/runPolicyTestbench/executeUsage/listContractActionLogs/getGovernanceDetail/forceTerminate） | 21 |
| `subject.ts` | subjectNo 裸插 9 处（fetchSubjectDetail/fetchProfile/uploadLicense/confirmLicense/verifyLegalPerson/submitGovCertificate/fetchLicenseImage/approveSubject/rejectSubject；fetchRegistrationProgress 已编码不动） | 9 |
| `catalog.ts` | vocabularyCode（listTerms）；productId/datasetId 数值型免 | 1 |
| `space.ts` | 全数值型免 | 0 |
| `did.ts` | 已全编码不动 | 0 |

**零变更锚**：现行全部字符串编号为字母数字（`CT000003`/`CO000013`/`1001C0002`/`TV001` 等），`encodeURIComponent` 为 no-op → URL 逐位不变；did 域与 subject 进度查询本就编码，形态不变。**did 串含 `:` 已由既有编码承载，既有 spec 断言编码后形态，保持不动。**

### 3.2 ②pageQuery 收编清单（唯一副本落 `client.ts`）

| 文件 | 动作 |
| --- | --- |
| `contract.ts` / `catalog.ts` / `space.ts` | 删本地 `pageQuery`，改 import（调用处实参不变：contract 单对象解构传参 `pageQuery(query.pageNum, query.pageSize, extra)` 与现状一致） |
| `did.ts` | 删变体 `pageQuery(params)`，改用共用版：条件过滤项先归入 `extra` 对象再传入（URL 输出逐位不变：`?pageNum=..&pageSize=..&...`） |
| `subject.ts` | `fetchReviewQueue` 内联串改 `pageQuery(query.pageNum, query.pageSize)`（URL 不变） |

### 3.3 ③rolesHeader 收敛

见 §2.2；`catalog`/`contract` 两函数体逐字同构消失，space 以参数表达差异值。

### 3.4 ④分页签名统一转换表（Q2-A：统一单对象；函数名与 URL 不变）

| 文件 | 函数（旧位置参数 → 新单对象 `query`） |
| --- | --- |
| `catalog.ts` | `searchProducts(pageNum, pageSize, keyword?, categoryCode?)` → `(query: { pageNum; pageSize; keyword?; categoryCode? })`；`getProductChangeLogs(productId, pageNum, pageSize)` → `(query: { productId; pageNum; pageSize })`；`listFavorites(pageNum, pageSize)` / `listSubscriptions(pageNum, pageSize)` / `listMyProducts(pageNum, pageSize)` / `listInteractionLogs(pageNum, pageSize)` → `(query: { pageNum; pageSize })`；`listMyDatasets(pageNum, pageSize, spaceId?)` → `(query: { pageNum; pageSize; spaceId? })`；`listTerms(vocabularyCode, pageNum, pageSize, keyword?)` → `(query: { vocabularyCode; pageNum; pageSize; keyword? })`；`listDatasetActionLogs(datasetId, pageNum, pageSize)` → `(query: { datasetId; pageNum; pageSize })`；`listProductActionLogs(productId, pageNum, pageSize)` → `(query: { productId; pageNum; pageSize })` |
| `space.ts` | `listSpaces(pageNum, pageSize, keyword?)` → `(query: { pageNum; pageSize; keyword? })`；`listAdmissions(spaceId, pageNum, pageSize, status?)` → `(query: { spaceId; pageNum; pageSize; status? })`；`listMyAdmissions(pageNum, pageSize)` → `(query: { pageNum; pageSize })`；`listMembers(spaceId, pageNum, pageSize)` → `(query: { spaceId; pageNum; pageSize })`；`listPlatformPolicies(pageNum, pageSize)` → `(query: { pageNum; pageSize })`；`listActionLogs(spaceId, pageNum, pageSize)` → `(query: { spaceId; pageNum; pageSize })` |
| `subject.ts` | `fetchReviewQueue(pageNum, pageSize)` → `(query: { pageNum; pageSize })` |
| `contract.ts` / `did.ts` | 已单对象，零转换 |

**转换规则**：仅分页型函数（签名含 pageNum/pageSize）转换；路径标识与过滤项一并装入 `query` 对象（沿 contract `getUsageSummary` 先例）；非分页函数（如 `getProductDetail(productId)`、`getContractDetail(contractNo)`）签名**不动**。配套调用面转换：22 处视图 + 30 处 spec/TS（机械改写 `f(a, b, c)` → `f({ a, b, c })`）。

## 4. 边界值与异常行为

### 4.1 零行为变更锚（关闭条件②）

1. **请求 URL 逐位不变**（§3.1 编码 no-op + §3.2/3.4 URL 构造不变）→ 三域 api spec 的 URL 断言**零改动**即天然回归锚；
2. **错误行为不变**：空主体拦截三域行为逐字等价（拦截时机/错误码/文案/零 fetch）；did/subject 无拦截现状保持；
3. **界面零变化**：渲染与交互不动（本卡零 `.vue` 逻辑改动，仅调用形态机械转换）。

### 4.2 有意不收敛点（留痕，评审与后人可查）

| 点 | 理由 |
| --- | --- |
| did/subject 不接入 `makeDomainJson`（不新增空主体拦截） | 补拦截 = 新增"零请求拒绝"行为 → 违反零变更锚；两域为 3.1.x 早期界面化口径，收敛属行为升级，如需另立卡 |
| `spaceRolesHeader` 与 catalog/contract 不做值合并 | 角色值不同是业务口径（space 普通档 `applicant`），工厂以参数表达，不消除差异 |
| `demoRolesHeader()`（client 全局）不并入工厂 | T18 体系基座（apiJson 默认头），与页面级头是覆盖关系，合并会改变优先级语义 |
| `constants/space` 的 `DEMO_SUBJECT_REQUIRED_TIP` 落点不动 | 仅改 import 方向；文案搬家属口径外夹带 |

### 4.3 风险与兜底

| 风险 | 兜底 |
| --- | --- |
| 签名转换漏点（视图/spec 调用面 52 处） | `vue-tsc` typecheck 全量兜底——漏点即编译错，不可能静默 |
| 视图转换破坏响应式语义（解构时机） | 逐处机械转换（传参形态变化，不触碰取值时机）+ 408 例回归 |
| spec 夹具出现特殊字符编号 → 编码后 URL 变化 → 断言红 | §6 前置检查 4：编码批开工前 grep 三域 spec 夹具实证全字母数字；若存在特殊字符夹具 → 该断言属"暴露既有缺陷"，停止上报编排师裁定（红线 4），不擅自改夹具 |
| 共享 router/module 级污染（工具链坑记忆） | 沿既有用例隔离惯例（每用例独立挂载/独立 router），本卡新用例同口径 |

## 5. 测试计划（测试先行——红相归档 `build-output/db41-red-*`）

### 5.1 新增用例（`client.spec.ts`，红相先行：先落用例见红 → 实现 → 复绿）

| 编号 | 用例 | 断言要点 |
| --- | --- | --- |
| t1 | 工厂空主体拦截 | `getDemoSubject()` 为空白 → rejects `ApiError` 且 `code='1000C0002'`、文案 = `DEMO_SUBJECT_REQUIRED_TIP`，**fetch 零调用** |
| t2 | 工厂角色头合并与覆盖优先级 | 正常主体 → fetch 收到 `X-Ctds-Roles = rolesHeader()`；调用方 `options.headers` 显式同名头**覆盖**页面级头（与现状合并顺序一致） |
| t3 | `pageQuery` 基础与过滤 | 两参基础形态 `?pageNum=1&pageSize=10`；extra 过滤项追加；空值处理与现状逐字一致 |
| t4 | 编码 no-op 锚 | `CO000013`/`CT000003`/`1001C0002`/`TV001` 经 `encodeURIComponent` 逐位不变（守卫锚：防未来"优化"掉编码） |
| t5 | 三域 rolesHeader 导出 | admin 档 → 三函数各返其 operator 值 / user 档 → 各返 normal 值（经 `demoIdentity` 真实导出断言，导出面零变实证） |

### 5.2 回归锚

- **408 例全绿**（URL 断言零改动 = §4.1 零变更实证）；用例数 408 → 408+5（t1~t5），只增不减；
- 三域 api spec（contract/catalog/space）+ did/subject spec + 四 SourceGuard + MainLayout 计数锚零改动（SourceGuard 扫描面不变仍绿）；
- subject 侧：`fetchReviewQueue` 签名转换同步 `QueueView.spec.ts` 调用面；其余 subject spec 的 URL 断言零改动（编码 no-op 实证面）。

## 6. 前置检查项（编码会话第一动作，逐项实测留痕）

1. `git status` 干净且 `main == origin/main == f7ca70c`（或编排师批准的更新基线）；
2. 前端基线复跑：408 例 + typecheck + lint + build 全绿（EXIT=0 实测留痕）；
3. 两设计文件末节确认记录在案（V1.0）+ 任务卡 §二确认留痕（Q1~Q3 + D1）；
4. **spec 夹具特殊字符扫描**：grep 三域 + subject spec 中 subjectNo/contractNo/templateNo/vocabularyCode 夹具值 → 实证全字母数字（§4.3 零变更锚前提）；
5. 分支 `feat/DB-41-前端api横切收敛` 自 `main` 已立。

## 7. 交付门禁（编码交付判定）

1. `npm run test` 全绿（408+5 例，0F/0E/0S）+ `typecheck` + `lint` + `build` 全 EXIT=0；
2. 全仓 `run-gates` GREEN（PASS 数只增不减，基线 PASS=14）；
3. `git diff` 实证：`services/` 零改动、门禁配置零改动、依赖锁定文件零差异；
4. §3 四族收敛逐条核对表（编码会话回填：每族收敛后副本数 = 1 或留痕豁免）；
5. 任务卡 §三映射回填 + §六体量登记；4 视角评审 → 编排师验收。

---

> **确认记录（待编排师签署后 V0.9 → V1.0，编码契约即时生效）**：（待签）
