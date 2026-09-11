# WBS-2.4.9 前端框架骨架 · 高保真设计
- 型态：界面类（任务卡已标注；基建骨架 + 界面产出）
- 对应规格：制度依据 =《C-TDS项目总体实施计划与WBS》2.4.9 产出定义（"布局/路由/权限菜单/组件库/主题 + 示例页"，工作量 1 天）+ ADR-001 §3（前端技术栈冻结：Vue 3 + TypeScript + Element Plus + Vite）+ ADR-002 §3（`frontend/` 目录归属）+ ADR-005（示例页对接后端演示端点用）；方向确认 = `docs/designs/WBS-2.4.9-lofi.md`（PO 2026-09-10 签署，五问答复：1 认可、2 认可、3 认可、4 认可、5 认可）
- 任务卡：WBS 2.4.9 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为高保真（= 编码契约）；低保真 = `docs/designs/WBS-2.4.9-lofi.md`；原型 = `docs/designs/WBS-2.4.9/prototype/index.html`
- 本文件新引入 npm 依赖将随编码登记 `docs/dependencies.md`（PO 授权留痕见 lofi 确认记录问题 1）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | PO | 2026-09-10 | 与 lofi 同批两级一并确认（补审③发现"本表未回录确认事实"后补录，确认事实见 lofi 确认记录与日志 26-09-10-2153） |

## 行为清单（9 项，逐条对应 lofi 已确认方向与计划测试）

| 编号 | 行为（业务可读） | lofi 出处 | 计划测试 |
| --- | --- | --- | --- |
| H1 | 前端工程落位 `frontend/`：Vite + Vue 3 + TypeScript 标准工程（官方 create-vite vue-ts 模板起步，版本组合按官方模板锁定 + 逐项核验登记 dependencies.md），含 `package.json`、`vite.config.ts`（含 dev proxy → 8080）、`tsconfig*.json`、入口 `index.html`/`src/main.ts`/`src/App.vue`；不进 Maven、不进根 pom | lofi 做什么-1 + 结构组成 | npm install 成功 + build 通过 |
| H2 | 三区布局骨架 `src/layouts/`：左侧菜单栏（折叠展开）+ 顶部栏（面包屑/演示模式标识/角色显示）+ 主内容区（router-view），Element Plus 布局组件实现；一套布局全平台页面共用 | lofi 做什么-2 + 原型 | 布局组件渲染冒烟测试 |
| H3 | 路由表 `src/router/`：Vue Router 4/5 集中管理；每条业务路由必含 meta（标题/菜单属性/可选权限点）；含路由守卫骨架（权限点校验占位） | lofi 做什么-3 + 待确认 2 | 路由表结构断言（每条必含标题与菜单属性）+ 守卫行为单测（放行/拒绝） |
| H4 | 权限菜单机制：菜单由路由表驱动（声明菜单属性即出现在侧边栏）；权限点机制演示——路由 meta.permission 声明所需权限点，守卫按当前演示角色（localStorage 占位）比对，无权限隐藏菜单 + 拒绝访问；不接真实授权数据源、不臆造后端接口 | lofi 做什么-4 + 待确认 2 | H3 守卫测试 + 菜单渲染测试（管理员可见/普通用户隐藏） |
| H5 | 组件库：Element Plus 全量注册（含图标）；入口 main.ts 统一挂载，业务页面直接使用 | lofi 做什么-5 | 组件渲染冒烟（按钮/表格/表单在示例页出现） |
| H6 | 主题：品牌色统一配置（Element Plus CSS 变量覆盖，一处修改全局生效）；`src/styles/` 集中 | lofi 做什么-6 | 构建通过 + 人工浏览器核对主色 |
| H7 | 工作台示例页 `src/views/dashboard/`：统计卡片 + 表格 + 表单的综合静态示例，全部 Element Plus 组件 | lofi 做什么-7a | 渲染冒烟 + 关键文案断言 |
| H8 | 标准能力示例页 `src/views/std-capabilities/`：真实调用 example-service `GET /api/v1/std-capabilities`（Vite dev proxy 转发本地 8080），表格展示三域（互联互通/跨空间身份互认/测评证据）"未开放"状态；含加载/错误态处理 | lofi 做什么-7b + 待确认 3 | 组件渲染冒烟（mock fetch）+ 集成验证（npm run dev + 后端运行，人工浏览器核对） |
| H9 | 工程化四命令：`dev` / `build` / `test`（Vitest）/ `lint`（ESLint），本地全绿闭环；不改门禁脚本（红线 3，前端门禁扩展走变更流程） | lofi 做什么-8 + 待确认 5 | 四命令实测 + 交付说明附结果 |

## 界面说明书（逐页，对应原型 `prototype/index.html`）

### 页面清单与跳转草图

```
侧边栏菜单（路由表驱动）
├── 工作台          → /dashboard          （H7 示例页）
├── 标准能力        → /std-capabilities   （H8 示例页，对接 2.4.8 演示端点）
├── 数据目录        → /catalog（占位路由，骨架期显示"建设中"占位页）
└── 仅管理员可见    → /admin-only         （meta.permission="demo:admin"，权限演示；普通用户菜单隐藏+路由拒绝）
```

### 布局骨架（H2，全局）

| 区域 | 说明 |
| --- | --- |
| 左侧菜单栏 | 宽 220px（可折叠）；顶部平台名"C-TDS 数据空间"；菜单项由路由表 meta.menu 驱动渲染；当前页高亮 |
| 顶部栏 | 左：面包屑（首页 / 当前页）；右："演示模式"标签 + 当前角色显示（骨架期静态，不做登录表单） |
| 主内容区 | `<router-view>` 承载当前页面；页面标题 + 描述行由路由 meta 提供 |

### 工作台示例页（H7，/dashboard）

| 要素 | 内容 |
| --- | --- |
| 统计卡片区 | 三张卡片：标准能力域 3 / 已开放能力 0 / 示例页面 1（静态值，示意统计组件用法） |
| 表格区 | "标准能力状态"表（同 H8 数据形态的静态示例，表头：能力域/状态/说明） |
| 组件示例区 | 按钮组（主要/次要）、输入框（占位）、开关（占位）——展示组件库可用性 |
| 权限演示提示条 | 说明"切换到管理员角色，左侧菜单出现'仅管理员可见'"（对应原型演示交互） |

### 标准能力示例页（H8，/std-capabilities）

| 要素 | 内容 |
| --- | --- |
| 数据来源 | `GET /api/v1/std-capabilities`（真实调用，经 Vite dev proxy 转发 localhost:8080） |
| 页面状态 | 加载中（el-skeleton/loading 态）→ 成功（表格三行：互联互通/跨空间身份互认/测评证据，状态徽标"未开放"）→ 失败（el-alert 错误提示"标准能力服务暂不可用，请稍后重试"，中性文案不暴露内部实现） |
| 字段映射 | 响应 data[].code → 能力域；data[].name → 中文名；data[].implemented → 状态徽标（false=未开放）；data[].message → 说明列 |
| 边界值 | 后端未启动 → 显示错误提示（可重试）；封套 code≠"0" → 按错误提示展示；列表为空 → 空态提示"暂无标准能力域" |

### 权限演示（H4，/admin-only）

| 要素 | 内容 |
| --- | --- |
| 机制 | 路由 meta.permission="demo:admin"；守卫读取演示角色（localStorage key 占位），角色≠admin → 菜单隐藏 + 访问重定向/拒绝提示 |
| 演示入口 | 顶部栏角色切换下拉（普通用户/管理员，骨架期静态两角色，模拟角色来源——真实授权数据源待 3.9.x 接入，本设计不臆造接口） |
| 页面内容 | 占位页文案："该页面仅管理员可见——权限机制演示" |

## 技术契约（编码契约 = 本表定稿）

### 目录结构（frontend/）

```
frontend/
├── package.json              # 脚本四命令 + 依赖（H9）
├── vite.config.ts            # dev server + proxy /api → localhost:8080（H8）
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json   # create-vite 模板标准
├── index.html                # 入口 HTML（挂载点 + 标题）
├── eslint.config.js          # ESLint flat config（Vue 推荐规则集，H9）
├── src/
│   ├── main.ts               # 应用入口：createApp + Element Plus + 图标 + router + 主题
│   ├── App.vue               # 根组件（挂 router-view）
│   ├── styles/theme.css      # 品牌色 CSS 变量覆盖（H6）
│   ├── router/index.ts       # 路由表 + 守卫（H3/H4）
│   ├── layouts/MainLayout.vue  # 三区布局（H2）
│   ├── views/
│   │   ├── dashboard/IndexView.vue        # 工作台示例页（H7）
│   │   ├── std-capabilities/IndexView.vue # 标准能力示例页（H8）
│   │   ├── catalog/IndexView.vue          # 数据目录占位页（"建设中"）
│   │   └── admin-only/IndexView.vue       # 仅管理员可见占位页（H4）
│   └── stores/demoRole.ts    # 演示角色占位（localStorage 读写；骨架期最小实现，非 Pinia）
└── src/__tests__/ 或 src/**/*.spec.ts      # Vitest 单测（H1–H9 对应）
```

### 路由表契约（H3/H4，Vue Router）

| 路径 | 组件 | meta.title | meta.menu | meta.permission | 说明 |
| --- | --- | --- | --- | --- | --- |
| `/` | MainLayout 重定向 | 首页 | — | — | 根布局 |
| `/dashboard` | 工作台 | 工作台 | ✓（图标+排序） | 无 | H7 |
| `/std-capabilities` | 标准能力 | 标准能力 | ✓ | 无 | H8 |
| `/catalog` | 数据目录（占位） | 数据目录 | ✓ | 无 | "建设中"占位页 |
| `/admin-only` | 仅管理员可见 | 仅管理员可见 | ✓ | `demo:admin` | H4 权限演示 |
| 404 | NotFound | 页面不存在 | 否 | — | 兜底 |

**守卫规则（骨架期占位）**：对带 `meta.permission` 的路由，读取演示角色；角色不含所需权限点 → 菜单不渲染该入口 + 访问时重定向到工作台并提示"无权限访问"。**明确不做**：真实登录校验、令牌校验、后端接口拉取授权数据（均待 3.9.x / 2.4.12）。

### 主题契约（H6）

- 品牌主色：`#2563eb`（CSS 变量 `--el-color-primary` 覆盖，含 light-3/5/7/9 与 dark-2 渐变系，Element Plus 变量规范）；
- 全站一套主题，修改仅动 `src/styles/theme.css`；
- 骨架期不做暗色模式/换肤 UI（观察项，需求出现走变更流程）。

### 依赖锁定表（H1，npm，已官方注册表实测核验）

| 包 | 锁定版本 | 用途 | 核验来源与日期 | 审批记录 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `vue` | ^3.5.42 | 框架核心 | create-vite 9.2.0 模板（vue ^3.5.41）+ npmmirror 实测 3.5.42，2026-09-10 | PO 授权（lofi 问题 1） | ADR-001 冻结栈 |
| `vue-router` | ^5.3.1 | 路由（peer vue ^3.5.34 || ^4.0.0；pinia peer 为 optional，本工程不引入状态库） | npmmirror 实测 5.3.1 + peerDependencies 核验，2026-09-10 | 同上 | ADR-001 冻结栈 |
| `element-plus` | ^2.14.5 | UI 组件库（ADR-001 冻结） | npmmirror 实测 2.14.5，2026-09-10 | 同上 | — |
| `@element-plus/icons-vue` | ^2.3.2 | 图标（peer vue ^3.2.0） | npmmirror 实测 2.3.2，2026-09-10 | 同上 | — |
| `vite` | ^8.2.2 | 构建/开发服务器 | create-vite 模板（^8.2.2）+ 实测 8.2.2，2026-09-10 | 同上 | — |
| `@vitejs/plugin-vue` | ^6.0.8 | Vue SFC 编译插件（peer vite ^5–^8） | 模板 + 实测 6.0.8，2026-09-10 | 同上 | — |
| `typescript` | ~6.0.2 | TS 语言（模板锁定线；vue-tsc 3.3.11 peer ≥5.0） | 模板锁定 ~6.0.2，2026-09-10 | 同上 | 不追 7.x 最新线，取官方模板兼容线 |
| `vue-tsc` | ^3.3.11 | 类型检查（build 阶段） | 模板 + 实测 3.3.11，2026-09-10 | 同上 | — |
| `@vue/tsconfig` | ^0.9.1 | TS 配置基准（模板自带） | 模板锁定，2026-09-10 | 同上 | — |
| `@types/node` | ^24.13.3 | Node 类型（模板自带） | 模板锁定，2026-09-10 | 同上 | — |
| `vitest` | ^5.0.0 | 单元测试框架（peer vite ^6.4–^8） | npmmirror 实测 5.0.0，2026-09-10 | 同上 | H9 |
| `@vue/test-utils` | ^2.5.0 | 组件挂载测试（peer vue 3.x） | npmmirror 实测 2.5.0，2026-09-10 | 同上 | H9 |
| `jsdom` | ^30.0.1 | Vitest DOM 环境 | npmmirror 实测 30.0.1，2026-09-10 | 同上 | H9 |
| `eslint` | ^9.39.5 | 代码检查（maintenance 稳定线；eslint-plugin-vue 10.11.0 支持 ^9） | npmmirror 实测 9.39.5（dist-tags maintenance），2026-09-10 | 同上 | 不追 10.x，取生态兼容稳定线 |
| `eslint-plugin-vue` | ^10.11.0 | Vue 规则集（peer eslint ^8.57–^10；@stylistic/@typescript-eslint 为 optional 不引入） | npmmirror 实测 10.11.0，2026-09-10 | 同上 | — |
| `vue-eslint-parser` | ^10.4.1 | .vue 文件解析 | npmmirror 实测 10.4.1，2026-09-10 | 同上 | — |
| `@typescript-eslint/parser` | ^8.70.0 | .ts/.vue 内 TS 块解析（vue-eslint-parser 配套，补审①P3-2 lint 覆盖 .ts 引入） | npmmirror 实测 8.70.0，2026-09-11 | PO 预授权（dependencies.md 留痕） | 补审③发现设计表漏登第 18 项，2026-09-11 补登 |

## 边界值与异常行为

| 场景 | 行为 |
| --- | --- |
| example-service 未启动时访问 /std-capabilities | 页面显示中性错误提示"标准能力服务暂不可用，请稍后重试"（el-alert，可重试），不白屏、不抛异常、不暴露内部服务名（补审②建议文案中性化，2026-09-11 同步本表） |
| 后端返回封套 code≠"0" | 同错误提示处理（不解析 data，避免误读错误结构） |
| 无权限访问 /admin-only | 菜单不显示入口；直接输入 URL → 守卫拦截，重定向 /dashboard + 提示"无权限访问" |
| 未知路径 | 404 占位页"页面不存在" |
| localStorage 无演示角色 | 默认普通用户（最保守，权限演示默认"仅管理员可见"隐藏） |
| 后端数据为空数组 | 空态提示"暂无标准能力域"（骨架期实际恒三域，防御性处理） |

## 测试计划（映射 H1–H9）

1. `src/router/router.spec.ts`（补审③更正测试文件名）：路由表结构断言（每条必含 meta.title；带 menu 的路由必含 meta.icon 等菜单属性）；守卫行为单测（有权限放行 / 无权限重定向+提示 / 未知路径真实导航落 404）；
2. `layouts/MainLayout.spec.ts`：三区布局渲染冒烟（菜单栏/顶栏/内容区存在，菜单项数量 = 带 menu 路由数）；无权限提示条消费（denied=1 渲染"无权限访问该页面"/关闭后清除 query/无 query 不渲染）；
3. `views/dashboard/IndexView.spec.ts`：关键文案与组件断言（统计卡 3 张、表格存在、按钮存在、权限演示提示条文案）；
4. `views/std-capabilities/IndexView.spec.ts`：mock fetch 加载中 → 骨架屏；成功 → 表格三行且状态"未开放"；失败 → 错误提示出现；封套 code≠0 → 错误提示；空数组 → 空态提示；
5. `views/admin-only/IndexView.spec.ts`：权限演示页文案渲染；
6. 工程命令实测：`npm run lint` / `npm run test` / `npm run build` 三绿（交付说明附输出摘要）；
7. 集成验证：example-service 启动 + `npm run dev`，浏览器人工核对三区布局、菜单跳转、标准能力页真实数据、权限演示（普通用户隐藏 / 管理员可见）——验证命令与截图附交付说明。

## 复用声明（检索过程）

- 技术栈：全部依赖属 ADR-001 冻结栈配套（Vue 3 + TS + Element Plus + Vite），版本按官方 create-vite 模板锁定线 + 官方注册表实测核验，未引入规格外框架/库（Pinia/Vuex/UI 替代品均不引入，理由见 lofi 不做什么）；
- 门禁：不改 `scripts/gates/`（红线 3），前端验证走本地四命令 + 交付说明，前端门禁纳入作为建议随变更流程（拟 2.4.12 落地）；
- 演示端点：标准能力页对接的是 WBS 2.4.8 已交付的 example-service 真实端点（非新造接口）；
- 规格外实现声明：无（本设计全部条目可回溯 WBS 2.4.9 产出定义 + ADR-001/002 + lofi 已确认方向）。

## 变更影响声明

- 验收剧本：现有剧本无前端节点，**无需更新**；新增前端骨架演示建议入剧本（由 PO 定，见交付说明建议文本）；
- 追溯矩阵：基建任务无 C-x.y 规格文件，不涉及；
- 依赖登记簿：`docs/dependencies.md` 新增 18 项 npm 包登记（本文件依赖锁定表为登记数据源；第 18 项 @typescript-eslint/parser 于补审①引入、补审③发现后补登）；
- ADR：不新增 ADR（本任务无新契约级决策需固化；如 PO 认为前端工程基线值得 ADR，可在验收时提出，另走 ADR 变更流程）。
