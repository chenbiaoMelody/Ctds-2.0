# WBS-2.4.12 测试基建-E2E（Playwright） · 高保真设计（编码契约）
- 对应低保真：`docs/designs/WBS-2.4.12-lofi.md`（同批提交，一次确认）
- 定稿口径：本文件为编码契约，实现与本文不一致 = 打回项（章程 2.6）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；AskUserQuestion 提请确认，选项"确认，进入编码（推荐）"获即时选定，2026-09-12；本文件即编码契约） | 2026-09-12 | 无 |

## 1. 演示登录页界面说明（业务可读）

| 区块 | 内容 | 交互 |
| --- | --- | --- |
| 品牌 | "C-TDS 数据空间"标题 + 副标题"城市可信数据空间平台" | 静态 |
| 表单 | 用户名输入框、口令输入框（密码态） | 必填；任一为空点"登 录"→ 表单校验拦下（提示"请输入用户名/请输入口令"），不跳转 |
| 登录按钮 | "登 录"主按钮 | 非空校验通过 → 写演示登录态 → 跳工作台（/dashboard） |
| 演示口径提示 | 页面下方说明条："骨架期演示登录：任意非空账号口令即可进入；真实认证/令牌签发待 3.9.1 规格澄清，本页不连接后端" | 静态明示，防误认为真实认证 |
| 布局 | 独立页（不套 MainLayout，无侧边栏/顶栏）；居中卡片 | 已登录访问 /login 自动重定向工作台 |

## 2. 行为清单（B 编号 = 实现与测试的唯一对照）

### 演示登录态（demoAuth.ts，与 demoRole.ts 同模式）

- **B1** 写入：登录成功 → `localStorage['ctds-demo-auth'] = '1'`。
- **B2** 读取：`isDemoAuthed()` 返回该值是否为 `'1'`（缺失/其他值一律视为未登录）。
- **B3** 清除：退出 → 移除该键（**演示角色 `ctds-demo-role` 保留不清**，角色切换是 2.4.9 既有能力，职责不混）。

### 路由与守卫（router/index.ts 改造）

- **B4** 新路由 `/login`（name: login，LoginView；顶层独立路由，不在 MainLayout children 内；`meta.title: '登录'`，**无 menu 标记**——不出现在侧边栏）。
- **B5** 守卫前置（先于既有权限守卫）：未登录访问**除 `/login` 外任何路由** → 重定向 `/login`；已登录访问 `/login` → 重定向 `/dashboard`。
- **B6** 既有权限守卫（demo:admin → 重定向 dashboard?denied=1）**逻辑一字不改**，仅执行顺序置于登录守卫之后。
- **B7** `:pathMatch(.*)*` 404 路由同样受 B5 约束（未登录访问不存在路径 → /login，而非 404 页）。

### 顶栏退出（MainLayout.vue 改造）

- **B8** 顶栏右侧在"演示模式 + 角色切换"之后新增"退出"按钮 → B3 清登录态 → 跳 `/login`。
- **B9** 其余布局行为（菜单驱动、折叠、角色切换、denied 提示）**零改动**。

### E2E 接入（playwright.config.ts + e2e/）

- **B10** `frontend/playwright.config.ts`：`testDir: './e2e'`；`webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: !process.env.CI }`（本机开发复用已起 dev server，CI 态强制新起）；`use: { baseURL: 'http://localhost:5173' }`；仅 Chromium（projects 单项）；`trace: 'retain-on-failure'` + `screenshot: 'only-on-failure'`（失败留痕目录 test-results，gitignore）。
- **B11** npm script：`"e2e": "playwright test"`（不动既有 dev/build/test/lint 五条 script）。
- **B12** 定位纪律（ADR-011 固化）：用 `getByRole`/`getByPlaceholder`/`getByText` 语义定位，**禁 CSS/XPath 选择器**（防实现细节耦合）。
- **B13** 登录冒烟示例用例 = 下表 E1–E5，全部映射 B1–B9。

### E2E 冒烟用例契约（login-smoke.spec.ts）

| 编号 | Given（前置） | When（操作） | Then（断言） | 覆盖 |
| --- | --- | --- | --- | --- |
| E1 | 未登录（全新 storage） | 打开 `/dashboard` | URL 重定向为 `/login`；登录表单（用户名/口令/登录按钮/演示口径提示）可见 | B2/B4/B5 |
| E2 | 未登录，已在登录页 | 用户名填 `demo`，口令留空，点"登 录" | 停留 `/login`（校验提示出现）；不写登录态 | 表单校验 |
| E3 | 未登录，已在登录页 | 用户名 `demo`、口令 `demo123`，点"登 录" | 跳转 `/dashboard`；工作台标题可见；侧边栏菜单（工作台/标准能力/数据目录）渲染 | B1/B5 |
| E4 | 已登录（user 角色），访问 `/admin-only` | 直达 URL | 重定向 `/dashboard` 且 `denied=1` 提示可见（既有权限守卫行为，B6） | B6 |
| E5 | 已登录 | 顶栏点"退出" | 回 `/login`；再访问 `/dashboard` 又被重定向回 `/login` | B3/B8/B5 |

### 边界值与异常行为

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| B-1 | localStorage 登录态值为 `'0'`/任意非 `'1'` | 视为未登录（B2 口径：仅 `'1'` 为已登录） |
| B-2 | 已登录重复访问 /login | 重定向 /dashboard（不留双入口） |
| B-3 | 登录后刷新页面 | 登录态保持（localStorage 持久，非 sessionStorage——演示口径，与 2.4.9 demoRole 同口径） |
| B-4 | 退出后浏览器回退键回到内页 | 守卫再次拦截 → 重定向 /login（守卫在导航层，不依赖页面事件） |
| B-5 | 5173 端口被占用时跑 `npm run e2e` | vite 自动换端口导致 webServer url 探测失败 → Playwright 报 webServer 超时（**预期行为**：先释放 5173 再跑；不复用他端口，避免端口漂移破坏 baseURL）——ADR-011 留观察项（CI 固定端口策略待 2.5.x） |

## 3. 门禁扩展契约（run-gates.ps1 + gates-config.json）

- **G1** stages 新增 `frontendLint`（enabled，`npm --prefix frontend run lint`）、`frontendTest`（enabled，`npm --prefix frontend run test`）、`frontendE2E`（enabled: false，status: PENDING-CI，注记浏览器供给待 2.5.x）。
- **G2** PASS/FAIL 汇总口径与既有阶段一致（非零退出码 = FAIL）；frontendE2E 不计入 FAIL/PASS 门数（PENDING 同 coverage 等既有口径）。
- **G3** 不改任何既有 Java 阶段行为与阈值（红线 3：仅按本设计确认记录 + ADR-011 留痕执行本扩展）。

## 4. 文件契约（落盘清单与关键约束）

| 文件 | 动作 | 关键约束 |
| --- | --- | --- |
| `frontend/src/stores/demoAuth.ts` | 新建 | 仅 B1–B3 三个函数 + 常量 STORAGE_KEY；注释明示"骨架期演示占位，真实认证待 3.9.1，不臆造后端接口" |
| `frontend/src/views/login/LoginView.vue` | 新建 | 独立布局；el-form 校验规则两条非空；页面含演示口径提示文案（§1 表） |
| `frontend/src/router/index.ts` | 改造 | +`/login` 路由（B4）；+登录守卫（B5/B7）；权限守卫逻辑不动（B6） |
| `frontend/src/layouts/MainLayout.vue` | 改造 | 仅 +退出按钮（B8）；其余零改动（B9） |
| `frontend/src/router/router.spec.ts`、`MainLayout.spec.ts` | 适配 | 守卫前置后既有用例补登录态前置（localStorage 预置），**断言口径不变**；新增 B5/B7 守卫用例 |
| `frontend/playwright.config.ts` | 新建 | B10 契约 |
| `frontend/e2e/login-smoke.spec.ts` | 新建 | E1–E5 五用例；B12 定位纪律 |
| `frontend/package.json` | 改造 | +`@playwright/test` 1.63.0（devDependencies）、+`e2e` script（B11） |
| `frontend/.gitignore`（或仓库根 gitignore） | 改造 | +`test-results/`、`playwright-report/`（失败留痕不入库） |
| `docs/adr/ADR-011-端到端测试规范.md` | 新建 | 8 字段全（含"备选""可替换性"）；规则：语义定位纪律（B12）、webServer 自足纪律、仅 Chromium 最小实现、失败留痕不入库、E2E 不替代单测（分层口径）、门禁 E2E 阶段 PENDING-CI 口径 |
| `docs/dependencies.md` | 改造 | 前端 npm 表 +`@playwright/test` 1 行（核验来源 npmmirror 2026-09-12 实测 + 浏览器二进制来源注记） |
| `scripts/gates/gates-config.json`、`run-gates.ps1` | 改造 | G1–G3 |

## 5. 验收剧本更新建议（业务语言）

- 剧本新增"前端演示登录"节点：打开应用 → 见登录页（含演示口径提示）→ 任意非空账号口令登录 → 工作台 → （可选）顶栏切管理员 → "仅管理员可见"菜单出现 → 退出回登录页。
- 既有剧本节点无需改动（登录守卫对已登录用户行为透明）。

## 6. 演示方式（E2E 闭环）

1. `cd frontend && npm run e2e` → 输出 `5 passed`（E1–E5），测试过程自动起停 dev server 与 Chromium；
2. `npm run lint && npm run test` 全绿（既有单测适配后不红）；
3. 门禁 `run-gates.ps1` GREEN（新增 frontendLint/frontendTest 两阶段 PASS）。
