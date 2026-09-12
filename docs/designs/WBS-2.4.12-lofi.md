# WBS-2.4.12 测试基建-E2E（Playwright） · 低保真设计
- 型态：非界面类为主（基建任务；含一个骨架期演示登录页，界面说明随高保真行为清单给出）
- 对应规格：制度依据（基建任务，无 C-x.y 规格）=《C-TDS项目总体实施计划与WBS》2.4.12 产出定义（"E2E 框架选型接入 + 登录冒烟示例"，工作量 1 天）+ ADR-001（技术栈冻结：Vue 3 + TS + Element Plus + Vite 前端栈在栈内）+ WBS-2.4.9 设计留痕（登录交互划归 2.4.12、前端门禁扩展拟随 2.4.12 落地）+ 章程 4.1（技术栈平庸化：选最主流实现）
- 任务卡：WBS 2.4.12 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为低保真；高保真 = `docs/designs/WBS-2.4.12-hifi.md`（同批提交，一次确认）
- 前置核对（2026-09-12 实测）：2.4.9 前端骨架 dev server 5173（/api 代理 8080）、路由守卫（演示角色权限重定向）、顶栏演示角色切换均已交付；**无登录页**（2.4.9 设计明确"不做真实登录，登录交互属 2.4.12"）；本机 Node v24.14.0 / npm 11.9.0。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；AskUserQuestion 提请确认，选项"确认，进入编码（推荐）"获即时选定，2026-09-12；6 项判定按本文件"问题确认"节留痕） | 2026-09-12 | 无 |
| 2 | 验收通过 | 项目主导者（兼任 PO；2026-09-12 会话裁决"验收通过，合并"，ADR-011 §7 已同步签署） | 2026-09-12 | 无 |

## 低保真内容（结构草图）

### 场景判定（决定框架选型，结论先行）

E2E 测试要验证"真实浏览器里页面渲染 + 路由跳转 + 用户交互"全链路正确。现状（2.4.9 交付）：前端验证 = Vitest + jsdom 单测——jsdom 是内存模拟环境，能验证组件逻辑与守卫函数，**不能**验证真实浏览器中的渲染布局、Element Plus 真实交互（点击/输入/下拉）、路由 history 行为。**缺口**：页面在真实浏览器里能不能走通，目前只能人工目测，无自动回归。

**选 Playwright（@playwright/test）**（微软官方维护，Vue/Vite 官方文档 E2E 章节推荐方案，前端 E2E 事实标准）。理由：
1. **生态事实标准**：Vite/Vue 官方文档 E2E 测试章节即以 Playwright 为推荐方案（栈内配套，ADR-001 冻结栈不破坏）；npm `@playwright/test` latest=1.63.0（npmmirror 官方注册表 2026-09-12 实测核验），engines `node>=20`（本机 Node 24.14 满足），Apache-2.0，**无 peer 依赖**（与既有 eslint 9 / vitest 5 / vue 3.5 无冲突面）；
2. **自动等待 + 内置断言**：`expect(locator).toHaveText()` 等自动轮询等待，不写人工 sleep——AI 生成质量高、剧本可读性好（章程 4.1 第一准则）；
3. **webServer 自动起停**：Playwright 配置 `webServer` 可自动拉起 `vite dev`（5173）、测试完自动关停——E2E 不依赖人工先起服务，`npm run e2e` 一条命令闭环（与 2.4.11 Testcontainers"测试自足"同一理念）；
4. **单浏览器最小实现**：默认仅装 Chromium（`npx playwright install chromium`，二进制经 npmmirror 镜像下载），不装全家桶——冒烟示例够用，多浏览器矩阵留观察项。

**备选未采用**：Cypress（曾并列主流，但对多标签页/多域名架构受限，商业化许可模式近年频繁变动，国内无官方下载镜像）；WebdriverIO（配置面大、文档对新手不如 Playwright 友好）。

### 登录冒烟的对象判定（本任务关键决策）

2.4.9 设计留痕：骨架期**不做真实登录/令牌签发**（归属待 3.9.1 规格澄清），"登录交互属 2.4.12 E2E 与 3.9.1 归属"、"2.4.12 E2E 的'登录冒烟'是它自己的产出，不提前实现"。因此：

- **本任务新建"骨架期演示登录页"**（`/login`，前端占位非真实认证）：账号/口令表单 + 非空校验 + 登录成功写演示登录态 → 进入工作台；顶栏新增"退出"清登录态回登录页。页面明示"骨架期演示登录，真实认证待 3.9.1"，**不臆造后端接口、不做令牌签发**（沿 2.4.9 同一口径）；
- **登录冒烟示例 = E2E 用例覆盖该链路**：未登录访问 → 重定向登录页 → 登录 → 工作台 → 角色权限重定向 → 退出。演示角色切换（2.4.9 既有顶栏能力）保留不动，登录页只管"进没进"，角色切换职责不混入；
- **既有骨架行为适配**（业务可见变化，逐条留痕）：路由守卫增加"未登录访问 → 重定向 /login"（先于既有权限守卫）；已登录访问 /login → 重定向工作台；2.4.9 既有单测（router.spec、MainLayout.spec）同步适配——守卫前置条件变化，断言口径不变。

### 做什么（逐条对应产出定义）

1. **E2E 框架选型接入（产出定义-1）**：frontend 新增 `@playwright/test`（devDependency，1.63.0）+ `playwright.config.ts`（testDir=e2e、webServer 自动起 vite dev、仅 Chromium、失败自动留痕 trace/screenshot）+ npm script `e2e`；写法规范固化新建 `docs/adr/ADR-011-端到端测试规范.md`（沿 2.4.11 ADR-010 先例，含"备选"与"可替换性"两节过 adrFieldsCheck 门禁）；
2. **登录冒烟示例（产出定义-2）**：演示登录页（LoginView.vue + 路由/守卫/顶栏退出适配）+ `frontend/e2e/login-smoke.spec.ts` 冒烟用例（Given/When/Then 见高保真用例表）；
3. **前端门禁扩展（承接 2.4.9 留痕的"拟随 2.4.12 落地"）**：`scripts/gates/gates-config.json` stages 新增 `frontendLint` / `frontendTest`（enabled，对应 `npm run lint` / `npm run test`，快且稳定）与 `frontendE2E`（enabled: false，status: PENDING-CI——浏览器二进制供给与 CI 环境策略待 2.5.x 部署基建评估，本机可用 `npm run e2e` 手动全量）；`run-gates.ps1` 相应扩展；门禁配置变更经本设计确认记录 + ADR-011 留痕执行（红线 3 变更流程留痕载体）；
4. **依赖登记**：`docs/dependencies.md` 前端 npm 表新增 `@playwright/test` 1 行（含浏览器二进制下载核验留痕）。

### 不做什么（V1.0 边界，防蔓延）

- **不做真实认证/令牌签发/后端登录接口**：归属待 3.9.1 规格任务卡（沿 2.4.9 观察项，不臆造接口）；
- **不装多浏览器矩阵**：仅 Chromium（多浏览器/移动视口留观察项）；
- **不做视觉回归测试（截图比对）**：冒烟示例以行为断言为界，视觉基线工具链留观察项；
- **不引入 Pinia/新状态库**：登录态沿 2.4.9 演示占位口径（localStorage 最小实现，与 demoRole 同模式）；
- **不把 E2E 写进验收剧本的每个功能节点**：本任务只交付框架 + 登录冒烟示例，业务功能 E2E 随各规格任务卡扩展。

### 结构组成（涉及文件清单）

- `frontend/playwright.config.ts`（新建：webServer/testDir/Chromium/trace 留痕）；
- `frontend/e2e/login-smoke.spec.ts`（新建：登录冒烟示例用例）；
- `frontend/src/views/login/LoginView.vue`（新建：骨架期演示登录页）；
- `frontend/src/router/index.ts`（改造：/login 路由 + 未登录守卫前置）；
- `frontend/src/stores/demoAuth.ts`（新建：演示登录态存取，与 demoRole.ts 同模式）；
- `frontend/src/layouts/MainLayout.vue`（改造：顶栏"退出"）；
- `frontend/src/router/router.spec.ts`、`frontend/src/layouts/MainLayout.spec.ts`（适配守卫前置变化）；
- `frontend/package.json`（+devDependency、+e2e script）；
- `docs/adr/ADR-011-端到端测试规范.md`（新建，规范正文）；
- `docs/dependencies.md`（前端 npm 表 +1 行）；
- `scripts/gates/gates-config.json`、`scripts/gates/run-gates.ps1`（前端门禁扩展，见"做什么-3"）；
- `docs/designs/WBS-2.4.12-{lofi,hifi}.md`、`docs/logs/Ctds-项目开发日志-*.md`。

### 主要流程（4 步）

1. 开发者写 E2E：真实浏览器链路验证 → 按 ADR-011 写 `frontend/e2e/*.spec.ts`（语义化 role/文本定位、禁 CSS 选择器），随 PR 评审；
2. `npm run e2e`：Playwright 自动拉起 vite dev（5173）→ Chromium 执行冒烟链路（登录/导航/权限/退出）→ 测试完自动关停 dev server 与浏览器；
3. 门禁照常（`run-gates.ps1`）：新增 frontendLint/frontendTest 两阶段随 Java 门禁一起跑，全绿才 GREEN；frontendE2E 登记 PENDING-CI 不阻塞；
4. 业务演示：PO 打开应用见登录页 → 演示账号进入 → 工作台 → 权限重定向演示 → 退出——"骨架期演示登录"即业务可见交付物。

### 待确认问题（需要你裁决）

1. 【登录冒烟对象】新建**骨架期演示登录页**（非真实认证，真实认证待 3.9.1；页面明示演示口径）作为登录冒烟的对象；既有骨架守卫增加"未登录重定向 /login"前置、顶栏加退出。**是否认可**？（备选：不建登录页，冒烟只测既有导航——"登录冒烟示例"名不副实，且与 2.4.9"登录交互属 2.4.12"划归留痕相悖）
2. 【框架选型】E2E 框架选 **Playwright**（@playwright/test 1.63.0，devDependency，Apache-2.0，无 peer 依赖，npmmirror 已核验）。**是否认可**？（备选：Cypress——架构受限 + 许可模式变动；WebdriverIO——配置重）
3. 【规范载体】E2E 写法规范固化进新建 **ADR-011 端到端测试规范**（沿 2.4.11 ADR-010 惯例）。**是否认可**？（备选：只写设计文档——后续业务功能 E2E 引用不便）
4. 【新依赖预授权】引入 `@playwright/test` 1.63.0（devDependency）+ Chromium 浏览器二进制（`npx playwright install chromium`，经 npmmirror 镜像，不入库）。**是否认可**？
5. 【门禁扩展】前端 `frontendLint`/`frontendTest` 两阶段**本任务纳入门禁**（enabled），`frontendE2E` 登记 PENDING-CI（浏览器供给待 2.5.x 评估）；门禁配置变更经本设计确认 + ADR-011 留痕。**是否认可**？（备选 a：门禁全不动另行任务——2.4.9 已预告随 2.4.12 落地，违背预告；备选 b：E2E 也立即 enabled——首次落地稳定性未经验证 + CI 浏览器供给未决，违背"门禁必须稳定绿"）
6. 【验证方式】E2E 真实执行需本机一次性下载 Chromium 二进制（约百余 MB，npmmirror 镜像）；演示时可 `npm run e2e` 看冒烟全绿。**是否认可**？

### 规格缺口声明

1. 制度依据缺口：无。基建任务以 WBS 2.4.12 产出定义 + ADR-001 + WBS-2.4.9 设计留痕 + 章程 4.1 为依据；行为清单与边界值随高保真定稿，E2E 写法契约同步固化进 ADR-011。
2. 观察项（不阻塞本任务）：① 真实认证/令牌签发归属（沿 2.4.9 观察项，待 3.9.1）；② 多浏览器/移动视口矩阵；③ 视觉回归（截图比对）工具链；④ frontendE2E 门禁阶段启用与 CI 浏览器供给（待 2.5.x）；⑤ E2E 与后端联调（登录页对接真实接口）随 3.9.x。

### 问题确认：

PO 预授权（用户画像既定惯例："技术选型以判断原则+预授权下放，判定留痕即可，核验+登记义务不免"；本会话指令"继续完成下一步任务"承接 2.4.12）。AI 复评结论：**6 项中 2/3/4/6 无业务影响分歧项**（纯工程内部验证能力增强，最终用户不可见）；**1、5 有业务可见面**，判定与依据如下（如 PO 有异议可在验收时打回）：

| 问题 | 判定 | 判定原则（为何无需 PO 二选一） |
| --- | --- | --- |
| 1 登录冒烟对象 | 新建骨架期演示登录页 | **业务可见**（打开应用先见登录页），但非规格外：WBS 产出定义字面"登录冒烟示例"+ 2.4.9 设计两次留痕"登录交互属 2.4.12"构成规格依据；页面明示演示口径、不臆造后端，真实认证归属不变 |
| 2 框架选型 | Playwright | 章程 4.1 技术栈平庸化：Vite/Vue 官方文档推荐方案即默认项；Cypress 许可模式变动 + 架构受限，WebdriverIO 配置重，均无栈内优势 |
| 3 规范载体 | ADR-011 | 沿 2.4.11 ADR-010"边界进 ADR"既有惯例，无新惯例创设 |
| 4 新依赖 | 引入 | 前端 E2E 生态必配（无栈内替代）、devDependency 不进业务制品；核验+登记义务照常履行 |
| 5 门禁扩展 | lint/test 纳入 + E2E 登记 PENDING | **业务可见面弱**（门禁内容变化不改业务功能）；沿 2.4.9 已获认可预告"随 2.4.12 落地"；E2E 阶段不启用是"门禁必须稳定绿"的保护性默认（2.4.11 PENDING 阶段同口径）；变更留痕载体 = 本设计确认记录 + ADR-011 |
| 6 验证方式 | 一次性 Chromium 下载 + npm run e2e | 沿 2.4.10/2.4.11 惯例（本机环境可用已声明）；二进制不入库（.gitignore），无密钥风险 |
