# DB-44 高保真设计：合约双视图分区复制收敛（V1.0 已确认）

> 任务卡：`docs/tasks/DB-44-合约双视图分区复制收敛-2026-10-12.md`；低保真：`docs/designs/DB-44-lofi.md`
> 型态：非界面类（无原型 HTML；界面差异清单见 §4.2）
> 状态：**V1.0 已确认（2026-10-12 编排师会话回复"待确认 5 项均按照你建议口径 A"）→ 编码契约生效**（确认记录见末节）
> 基线：`main == origin/main == 6383a80`；前端 **419 例**（复审后）+ typecheck/lint/build 零错

## 1. 现状盘点（立卡批逐项实证，2026-10-12，梢 `6383a80`）

### 1.1 复制块逐字比对（`GovernanceView.vue` 抽屉 ≡ `ContractDetailView.vue` 分区）

| 分区 | 详情侧（`ContractDetailView.vue`） | 治理侧（`GovernanceView.vue` 抽屉） | 差异判定 |
| --- | --- | --- | --- |
| 终止提示 alert | 总览之后，`type="error"`，文案逐字同 | 抽屉顶部（总览之前），同文案 | 位置差异（**保留**） |
| A 总览 | `el-descriptions title="合约总览" :column="3" border`；12 项：编号 / 状态（`el-tag` 带色）/ 当前条款版本 / 产品名称 / 定价快照 / 模板引用（"版本快照 V{n}"）/ 提供方 / 需求方 / 内容哈希（tooltip）/ 生效时间 / 完结时间 / 创建-更新 | `:column="2" border size="small"`；10 项：编号 / 状态（纯文本）/ 产品名称 / 定价快照 / 提供方 / 需求方 / 模板引用（"（V{n}）"）/ 当前版本 / 内容哈希（tooltip）/ 生效-完结合并一行 | 字段集与列数差异（**保留**，见 §4.2） |
| B 当前条款全文 | `el-card` header「当前条款全文（V{{currentClauseVersion}}）」；`:column="1" border`；`label = slotName(String(key))` | `<h4>当前条款全文</h4>`；同 column/边框；`label = String(key)` | **漂移 1**（标签口径；本卡收敛） |
| F 策略五要素 | `<h4>` + `:column="2" border` + 六项（quota / term / purpose / territory 经 `strategyLine()`；noRedistribution；noRestrictionDeclared） | 同（六项模板逐字相同） | 同构（**收敛单点**） |
| 策略行函数 | `strategyLine()`：`detail.value?.clauseValues.strategy[e]` | `strategyLine()`：`detail.value?.detail.clauseValues.strategy[e]` | 逻辑同、**取值路径异**（包装层）；收敛后由父视图解包 |
| C 条款版本链 | `el-card`；展开列（`key：value`）/ 版本号 / 提案方 / 提案时间 / **双方确认时间** / 变更明细（`min-width 200`）；`:size` 默认 | `<h4>`；展开列 / 版本号 / 提案方 / 提案时间 / 变更明细（`min-width 180`）；**无双方确认时间**；`:size="small"` | **漂移 2**（缺列；本卡立项）+ 样式差异 |
| H 合约留痕 | `el-card`；动作（120）/ 版本号（80）/ 操作者主体编号（170）/ 时间（180）/ 从何值→到何值（180）/ 拒绝码（90）；分页 `logsPage/logsTotal/logsPageSize` | `<h4>`；动作（110）/ 80 / 160 / 170 / 160 / 90；分页 `drawerLogsPage/drawerLogsTotal/drawerLogsPageSize` | 同构（列宽差异，功能等价）+ 分页态变量命名各一 |
| D 动作条 / E 签署存证 / G 策略与使用 | 有 | 无（§6.5-B 复用口径 = A~C、F、H） | 有意差异（**保留**） |
| 治理动作（强制终止 + 弹窗） | 无 | 有 | 有意差异（**保留**） |

### 1.2 守卫锚现状（本卡"不削弱"的对照基线）

| 锚 | 现状 | 本卡影响 |
| --- | --- | --- |
| `ContractSourceGuard.spec.ts` 扫描目标 | `viewSources` 4 个视图 + **`componentSources` 2 个** + `api/contract.ts` 1 个 | 组件 2→3（**增强**：新增组件纳入同口径扫描） |
| 同文件 裸字面量守卫 | 17 项中文清单（含 `治理查看` / `域内范围` 等）扫 `views + components + api` | 新组件**自动**纳入扫描（glob `./components/*.vue`）；不放松 |
| 同文件 fetch / admin 字面量守卫 | `fetch(` 0 命中；`'admin'` 仅限 `demoIdentity.ts` + `api/contract.ts` | 新组件同为受管域；不得引入 `fetch(` / `'admin'` |
| 同形提示守卫 | 常量逐字 + 页面不得散写（反向探针） | 零变 |
| `GovernanceView.spec.ts` | 4 组 9 例（基线实测 9 例；原记 8 例，评审修复段补正——列表 / 直查只读分区 + 反向探针无动作条 / 留痕分页 / 强制终止 / 认证引导） | 全部保留；另**新增**锚（§5.2） |
| `ContractDetailView.spec.ts` | 总览 / 条款 / 版本链 / 协商 / 签署 / 策略与使用 / 留痕等 | 全部保留（`vi.mock` 未含 `getTemplate` → framework 恒 null → 走键名回退腿，现状不变） |

> **注（现状实证）**：`ContractDetailView.spec.ts` 的 `vi.mock('../../api/contract')` **未列 `getTemplate`**，故详情页测试中框架恒不可达 → `slotName()` 恒回退键名；"槽位名命中"这一腿当前**无测试覆盖**（本卡补，见 §5.1 t2）。

## 2. 目标形态（组件契约）

### 2.1 新增共享组件 `frontend/src/views/contract/components/ContractReadOnlySections.vue`

**落点理由**：`components/` 目录已是 3.4.7 约定的共享组件位（`ClauseEditor` / `StrategyEditor` 在此），且 `ContractSourceGuard.spec.ts` 的 `import.meta.glob('./components/*.vue')` 会**自动**把新组件纳入裸字面量 / fetch / admin 守卫扫描——复用既有守卫机制，零新增扫描代码。

```ts
// props（纯展示：组件内不发起任何请求）
interface Props {
  variant: 'detail' | 'governance'          // 视角开关（§2.3 差异表）
  detail: ContractDetail                     // 已解包的合约详情（治理侧传 governanceDetail.detail）
  clauseVersions: ClauseVersionItem[]
  framework?: ClauseFramework | null         // 模板条款框架；null/缺省 → 键名回退
  actionLogs: ContractActionLogItem[]
  logsLoading: boolean
  logsTotal: number
  logsPage: number
  logsPageSize: number
}
// emits
'logs-page-change': [page: number]           // 留痕翻页（父视图重新拉取，无乐观更新）
```

**组件内单点实现（本卡核心收敛物）**：

```ts
/** 槽位显示名：框架命中 → "名称（键）"；未命中/框架缺失 → 键名（与 3.4.7 hifi §6.4-B 逐字同口径）。 */
function slotName(key: string): string { /* framework?.slots.find(...) */ }

/** 策略要素行：未启用 → "未启用"；quota → "次数上限 {n|—}"；term → "起 ~ 止"；其余 → text | "已启用"。 */
function strategyLine(element: 'quota' | 'term' | 'purpose' | 'territory'): string { /* 取值走 props.detail */ }
```

### 2.2 两视图改造

| 视图 | 保留在本页 | 改引共享组件 | 解包责任 |
| --- | --- | --- | --- |
| `ContractDetailView.vue` | 动作条 / 签署与存证 / 策略与使用 / 各弹窗 / `isParticipant` / `slotName` 来源改为传 `framework` prop | A、B+F、C、H 四个区块（模板整段替换为 `<ContractReadOnlySections variant="detail" … />`） | 自身 `detail` 直接传 |
| `GovernanceView.vue` | 全量列表 / 强制终止按钮与弹窗 / 抽屉骨架 | A、B+F、C、H 四个区块 | 传 `detail.detail`；**新增** 框架加载（Q3） |

- 视图内 `strategyLine()` **删除**（唯一副本迁入组件）；`slotName()` 详情侧**删除**（迁入组件）；
- 两视图 `showError` / 分页态 / 列表逻辑**不动**（不在本卡范围）。

### 2.3 视角差异开关（`variant`）——本卡保留的有意差异

| 项 | `detail` | `governance` |
| --- | --- | --- |
| 总览列数 / 字段集 | `column=3`；12 项（含当前条款版本、生效 / 完结分行、创建-更新；状态 `el-tag`） | `column=2`；10 项（无创建 / 更新；生效-完结合并一行；状态纯文本） |
| 条款全文容器 | `el-card` header「当前条款全文（V{n}）」 | `<h4>当前条款全文</h4>` |
| 版本链容器 / `size` | `el-card` / 默认 | `<h4>` / `small` |
| 留痕容器 | `el-card` | `<h4>` |
| 终止 alert 位置 | 总览之后 | 组件之前（治理抽屉顶部，由治理视图自行渲染——**不迁入组件**） |
| 条款全文标签 / 策略行 / 版本链列 / 留痕列 | 两侧同（本卡收敛结果） | 同左 |

> **说明**：容器形态（`el-card` vs `h4`）与总览字段集经 `variant` 保留——**治理只读视角的简化是产品设计**，非脏拷贝；不因抽组件而改变用户看到的字段。列宽差异（留痕 6 列）建议**统一采用详情侧宽度**（纯样式对齐，零业务可见影响；如编排师要求零视觉差异保留，则并入 §4.2 有意差异清单）。

## 3. 收敛明细（台账三项 → 落点）

| 台账口径 | 落点（文件 : 结构） | 判定方式 |
| --- | --- | --- |
| ① 抽取共享组件（五分区 / `strategyLine` 单点） | 新增 `components/ContractReadOnlySections.vue`；两视图模板四个区块替换为组件引用；视图内 `strategyLine` 定义删除 | 源集锚：两视图源码 `strategyLine` 定义命中 0、组件内恰 1（§5.2 g2） |
| ② 治理侧槽位名口径与详情侧对齐 | 漂移 1：治理侧改经 `slotName()`（组件内单点）；框架数据由治理视图加载（视 Q3） | 用例锚：框架命中 → "名称（键）"（§5.1 t2）；治理直查触发 R5 + 失败回退键名（§5.2 g3） |
| ③ 既有守卫锚不削弱 | `ContractSourceGuard.spec.ts` 组件计数 2→3 + 新组件路径断言；裸字面量 / fetch / admin 守卫作用域自动扩至新组件；两视图 spec 断言零删改 | 守卫 spec 复跑全绿 + 断言数只增不减（§5.2 g1） |

## 4. 边界值与异常行为

### 4.1 边界与兜底

| 场景 | 行为 |
| --- | --- |
| `framework` 为 `null`（未传 / 加载失败） | `slotName(key)` 返回键名（现状详情页同款兜底） |
| `framework.slots` 为空 / 无匹配 `key` | 同上（键名回退） |
| `clauseValues.slots` 为空对象 | 条款全文区渲染空 `el-descriptions`（现状行为） |
| `clauseVersions` 为空 | 版本链空态文案「（仅初始版本）」（现状文案，两侧一致） |
| `actionLogs` 为空 | 留痕空态「暂无记录」 |
| 留痕翻页 | 组件 `emit('logs-page-change', n)` → 父视图更新页号并按现有逻辑重新拉取（**不做乐观更新**） |
| 策略要素缺失 / 未启用 / `maxCount` 为空 | `"未启用"` / `"次数上限 —"`（与 `strategyLine` 现口径逐字一致） |
| 治理侧主体未入驻模板域（R5 → `1008C0003`） | **静默**回退键名；不弹错误、不影响其余分区（与详情页框架加载失败的既有处置一致） |

### 4.2 有意不收敛 / 有意差异（留痕，评审与后人可查）

1. **总览字段集与列数**（治理 10 项纯文本 vs 详情 12 项带标签）——治理只读视角的产品设计（Q2-3-A）；
2. **容器形态与字号**（`el-card` vs `h4` / `size`）——保持现状零视觉变更；
3. **终止 alert 位置**（治理抽屉顶部 vs 详情总览后）——各自上下文最优，保留；
4. **治理无动作条 / 详情无强制终止 / 详情独有策略与使用区**——两视角能力差异，设计如此；
5. **版本链「双方确认时间」列**——视 **Q2-2** 判定：A = 对齐补齐（不再是差异）；B = 登记为有意分叉（治理视角不显示）。

### 4.3 服务端可见请求变化声明（须编排师知悉）

- **唯一新增请求**：治理详情抽屉打开时，`getGovernanceDetail()` 之后新增 1 次 `getTemplate(detail.templateNo)`（R5，只读）。
- 详情页请求序列**零变化**（原本就有该调用）；其余接口零触碰。
- 该请求失败（未入驻 / 停用 / 不存在）一律静默回退，**不新增错误提示、不新增留痕**（读面拒绝不留痕，沿既有口径）。
- 若编排师在 Q3 选 B，则本项请求变化清零（但漂移 1 的对齐沦为形式化）。

## 5. 测试计划（测试先行；红相归档 `build-output/db44-red-*`）

### 5.1 新增用例 `components/ContractReadOnlySections.spec.ts`（先落用例见红 → 实现 → 复绿）

| 编号 | 覆盖行为 | 断言要点（可证伪） |
| --- | --- | --- |
| t1 | 总览两视角 | `detail`：12 项含「创建 / 更新」、状态渲染为 `el-tag`（断言 tag 元素 / class 存在）；`governance`：无「创建 / 更新」标签、状态为纯文本（断言同 fixture 下两 variant 文本集**不同**——反向探针证明 variant 生效） |
| t2 | 条款全文标签两腿 | 提供 `framework`（`subject_matter` → 名称「标的」）→ 断言 `标的（subject_matter）`；`framework=null` 或键未命中 → 断言 `subject_matter` 且**不含**「（subject_matter）」括号形态 |
| t3 | 策略五要素 | 未启用 → `未启用`；quota 有值 → `次数上限 100`；quota 空值 → `次数上限 —`；term → `2026-10-08 ~ 2026-11-07`；purpose/territory → text；要素启用但无 text → `已启用`；noRedistribution 两态；noRestrictionDeclared 两态 |
| t4 | 版本链 | 两视角均渲染「双方确认时间」（Q2-2-A 口径，含 `—` 空值腿）——**若采 B 则改为断言 governance 不含该列**；展开行「键：值」；变更明细 `slot：from → to`；空态「（仅初始版本）」 |
| t5 | 留痕区 | 六列渲染（动作经常量中文映射、版本号、操作者、时间、from→to、拒绝码）；空态「暂无记录」；翻页按钮触发 `logs-page-change` 事件且载荷 = 目标页号 |
| t6 | variant 反向探针 | 同一 fixture 下两 variant 渲染文本不相等（防"开关空转"——若 `variant` 被忽略，t6 必红） |

### 5.2 守卫锚与视图级新增锚（只增不减）

| 编号 | 文件 | 锚 |
| --- | --- | --- |
| g1 | `ContractSourceGuard.spec.ts` | 组件计数 2→**3** + 断言路径含 `ContractReadOnlySections.vue`（增强，不放松其它断言） |
| g2 | `ContractSourceGuard.spec.ts` | **单点锚**：两视图源码 `strategyLine` 定义命中 = 0；`ContractReadOnlySections.vue` 内命中 = 1；两视图源码均引用 `ContractReadOnlySections`（缺引用 = 抽了组件却没用 → 必红） |
| g3 | `GovernanceView.spec.ts` | 治理直查触发 `getTemplate(templateNo)`（mock 断言）；框架不可达（mock reject）→ 静默回退键名且**不弹错误**（断言无错误提示文本） |
| g4 | `GovernanceView.spec.ts` | 既有 9 例**逐例保留**（基线实测 9 例；原记 8 例，评审修复段补正；列表 / 直查只读分区 + 反向探针 / 留痕分页 / 强制终止 / 认证引导） |

### 5.3 回归与门禁

- 全仓 `npm run test`：**419 → ≥419 + 新增**（用例数只增不减）；`npm run typecheck` / `lint` / `build` EXIT=0；
- 全仓 `run-gates.ps1` 单发复跑 **GREEN**（单发等待，不重试叠加——沿 DB-41 教训）。

## 6. 前置检查项（编码会话第一动作，逐项实测留痕）

| 项 | 检查 |
| --- | --- |
| 1 | git 基线：分支梢 = 确认批；`main == origin/main == 6383a80`（或确认批后梢）；工作树干净 |
| 2 | 前端基线复跑：**419 例** + typecheck / lint / build 全 EXIT=0（归档 `build-output/db44/pre-baseline-*.log`） |
| 3 | 两设计 V1.0 确认记录在案（lofi / hifi 末节签署） |
| 4 | 现状比对复核：本文件 §1.1 / §1.2 逐行与源码一致（行号可漂，结构不可漂） |
| 5 | 守卫现状确认：`ContractSourceGuard.spec.ts` 组件计数断言 = 2、`import.meta.glob('./components/*.vue')` 覆盖新组件路径 |
| 6 | 治理侧 R5 可达性**实测留痕**（演示环境）：运营档直查治理详情 → 记录 `getTemplate` 实际返回（200 或 `1008C0003`），写入编码批留痕（用于解释演示现场标签口径） |

## 7. 交付门禁（编码交付判定）

1. §3 台账三项逐条落实且可判定（源集锚 + 用例锚 + 守卫复跑）；
2. 行为差异清单（§2.3 / §4.2）逐条与实现一致，无未登记差异；
3. 前端用例只增不减、typecheck / lint / build 全绿；
4. 全仓 `run-gates` GREEN；
5. 4 视角评审（含复审）通过 + 编排师验收通过；
6. 三份验收剧本是否需更新的判定（预期：**无需更新**——本卡唯一可见差异为治理抽屉标签口径与（视 Q2-2）版本链增列，均在技术侧走查覆盖范围内；若判定需更新即在交付说明中给出建议文本）。

---

## 附：变更影响声明

- 验收剧本（C-4.1 / C-4.2 / C-4.3）：**预期无需更新**（无业务规则、无接口、无错误码变化）；如 Q2-2 采 A（治理侧补列），剧本中若存在"治理详情字段清单"描述需核对，交付时给结论。
- 追溯矩阵：本卡无规格触点（台账派生），不产生新条目。
- WBS-3.4.7 hifi §12 E6 登记行：本卡完成后**就地补登记**"已由 DB-44 收敛"（文档同步，属本卡收尾动作）。

---

> **确认记录（✅ 2026-10-12 编排师会话回复"待确认 5 项均按照你建议口径 A；执行立卡批的分支 feat/DB-44-合约双视图分区收敛"）**：**Q1 采 A**（单一共享组件落点 = `frontend/src/views/contract/components/ContractReadOnlySections.vue`，纯展示 + `variant` 开关）/ **Q2-2 采 A**（§2.3 / §4.2-5：版本链「双方确认时间」列**两视角齐备**，t4 按 A 口径断言；原 B 口径分支不再适用）/ **Q2-3 采 A**（§2.3 总览差异保留——治理 2 列 10 项纯文本 vs 详情 3 列 12 项带标签）/ **Q3 采 A**（§4.3：治理直查新增 1 次只读 R5，失败静默回退键名——**编排师已明确知悉**）；**D1 不拆分**。**本文件 V0.9 → V1.0（编码契约即时生效，正文零改动）**；编码归新会话冷启动，测试先行（§5.1 红相 `build-output/db44-red-*`）。

---

## 附：实施澄清登记（编码批 2026-10-12；沿 WBS-3.4.7-hifi §12 E 系列先例——澄清，非改需求）

| 编号 | 位置 | 原表述 | 实施口径（澄清） | 登记 |
| --- | --- | --- | --- | --- |
| E1 | §2.2 两视图改造 | "四个区块（A、B+F、C、H）模板整段替换为 `<ContractReadOnlySections variant="detail" … />`" | 组件新增**默认插槽**：详情视角把"签署与存证 / 策略与使用"两块置入插槽（位置 = 版本链之后、留痕之前）——保持详情页既有区块顺序**零变更**；治理视角不传插槽（其顺序本就与组件顺序一致） | 2026-10-12 编码批 |
| E2 | §2.2 两视图改造 | 同上 | 组件新增具名插槽 **`#before-clause`**：详情视角把"协商动作条"置于总览（含终止提示）之后、条款全文之前——保持既有顺序**零变更**；治理视角不传 | 2026-10-12 编码批 |
| E3 | §2.1 组件契约 | `clauseVersions` / `actionLogs` 为必填 props | 实施取 `withDefaults` 给两者**空数组缺省**（纯展示组件对入参健壮；消除运行期 prop 类型告警），业务语义不变 | 2026-10-12 编码批 |
| E4 | §6 前置检查第 6 项 | 治理侧 R5 可达性"实测留痕" | 演示环境停止态下**未做端到端实测**，改为静态权限口径核验（R5 = 认证 + `contract.template.read` + ADMITTED；失败静默回退）并登记为交付观察项；端到端观察随真机走查补 | 2026-10-12 编码批 |
| E5 | §2.3 note（"列宽差异（留痕 6 列）建议统一采用详情侧宽度"） | 仅点名留痕 6 列 | **版本链列宽一并随组件统一为详情侧**（提案方 160→170 / 提案时间 170→180 / 变更明细 `min-width` 180→200），治理侧出现可见样式微调、**零业务影响**——按 §2.3 note 同口径登记为有意统一（评审① P3 闭环） | 2026-10-13 评审修复段 |
