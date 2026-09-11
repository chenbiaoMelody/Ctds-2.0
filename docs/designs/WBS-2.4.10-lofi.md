# WBS-2.4.10 数据库迁移工具链 · 低保真设计
- 型态：非界面类（任务卡已标注）
- 对应规格：制度依据（基建任务，无 C-x.y 规格）=《C-TDS项目总体实施计划与WBS》2.4.10 产出定义（"迁移脚本规范、版本化管理、示例迁移"，工作量 1 天）+ ADR-001（技术栈冻结：MySQL 8 在栈内）+ 章程 4.1（技术栈平庸化：选最主流实现）
- 任务卡：WBS 2.4.10 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为低保真；高保真 = `docs/designs/WBS-2.4.10-hifi.md`（同批提交，一次确认）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 待确认 | | | |

## 低保真内容（结构草图）

### 场景判定（决定依赖选型，结论先行）

平台每个业务服务都会有数据库表结构，且表结构会随业务迭代**持续变化**。"谁改了表、改到哪一版、新环境怎么从零建到最新"必须由工具统一管理，不能靠人手工执行 SQL（人工改表不可追溯、不可复现、环境间必然漂移）。ADR-001 已冻结 MySQL 8，迁移工具选型判定：

**选 Flyway**（业界 Java/Spring 生态事实标准，业务语言：给数据库的变更写"带版本号的 SQL 文件"，工具按版本号顺序执行并记录到数据库里的"迁移历史表"）。理由：
1. **Spring Boot 原生集成**：官方 auto-config，配好数据源即自动执行迁移，零胶水代码；Spring Boot 官方文档的迁移管理默认讲的就是 Flyway；
2. **脚本就是纯 SQL**：本项目全员无编程经验，纯 SQL 文件最可读、可由 AI 生成后人工直读评审；Liquibase 用 XML/YAML 描述变更，多一层抽象、评审成本更高；
3. **全网资料最主流**：教程与 AI 语料密度最高，外部顾问接手成本最低（技术栈平庸化原则）；
4. 依赖核验：`flyway-core` 与 `flyway-mysql`（Flyway 8.2+ 拆分的 MySQL 方言包）版本由 Spring Boot 3.5.16 BOM 统一管理（编码期经阿里云镜像 `dependency:get` 实测解析成功后登记 `docs/dependencies.md`，沿 2.4.7 流程）。

**不新建 common 模块**：迁移能力 = "规范（文档）+ 每个服务自己的 SQL 脚本目录 + 两行配置"，没有可复用代码，做成 common 组件是过度设计（防蔓延）。共享的是**规范本身**，落 ADR-009（沿 2.4.x"边界进 ADR"口径，adrFieldsCheck 要求"备选"与"可替换性"两节）。

### 做什么（逐条对应产出定义）

1. **迁移脚本规范（产出定义-1）**：新建 `docs/adr/ADR-009-数据库迁移规范.md`，固化：脚本命名 `V{版本号}__{业务描述}.sql`（下划线双写）、位置 `src/main/resources/db/migration/`、一条脚本只做一件事、**已应用的脚本禁止修改**（Flyway 校验和防篡改，改动即启动失败）、只前进不回滚（回滚场景由反向脚本 V+1 承担）、脚本评审随 PR 走（脚本与代码同一评审链）；
2. **版本化管理（产出定义-2）**：example-service 引入 Flyway（`spring-boot-starter-jdbc` + `flyway-core` + `flyway-mysql` + `mysql-connector-j`，版本全由 Boot BOM 管理）；启动时自动按版本号顺序执行未应用的脚本，执行记录写入 `flyway_schema_history` 表（版本号/描述/校验和/执行人/耗时）；
3. **示例迁移（产出定义-3）**：两个示例脚本演示版本递进——`V1__create_demo_note.sql`（建演示表 demo_note）+ `V2__seed_demo_note.sql`（插入 2 行演示数据）；配一个只读演示端点 `GET /api/v1/demo-notes`（JdbcTemplate 直读，仅为证明"迁移后的表真实可用"；ORM 层随首个真实业务包引入 MyBatis-Plus，本任务不抢做）；
4. **无库兼容（保护既有演示）**：example-service 现在**不依赖任何数据库即可启动**（2.4.8 探活端点、2.4.9 前端联通都建立在此前提上）。本任务以 Spring profile 隔离：默认 profile 行为零变化（无库照常启动、既有测试全绿不碰数据库）；`mysql` profile 才启用数据源 + Flyway。**这是本任务最重要的兼容性决策**；
5. **条件化集成测试**：新增 `FlywayMigrationIT`（仅当环境变量 `CTDS_IT_MYSQL_URL` 存在时执行，否则跳过——沿 2.4.7 "真实中间件在配了它的环境跑"惯例，不提前引入 Testcontainers（2.4.11 的scope）、不引入 H2 依赖）；
6. **真实演练（人工/半自动）**：用 Docker 启动 MySQL 8 容器（本机已装 Docker Desktop）跑一遍"空库→V1→V2→端点可读→重启 no-op"完整流程，命令与结果附交付说明。

### 不做什么（V1.0 边界，防蔓延）

- **不建 common-migration 模块**：无可复用代码，规范走 ADR（见场景判定）；
- **不引入 Testcontainers/H2**：集成测试框架是 2.4.11 的 scope；本任务条件跳过 + Docker 实测覆盖；
- **不做回滚工具**：Flyway 社区版无 undo，"只前进不回滚"是业界默认纪律，反向修复走新版本脚本；
- **不做多数据库方言适配**：ADR-001 冻结 MySQL 8，不为"将来可能换库"预写方言层（可替换性见 ADR-009）；
- **不做 ORM 选型落地**：MyBatis-Plus 随首个真实业务包引入，本任务数据访问仅演示用 JdbcTemplate。

### 结构组成（涉及文件清单）

- `docs/adr/ADR-009-数据库迁移规范.md`（新建，规范正文）；
- `services/example-service/pom.xml`（+4 依赖，版本走 Boot BOM 不显式锁版）；
- `services/example-service/src/main/resources/db/migration/V1__create_demo_note.sql`、`V2__seed_demo_note.sql`（新建）；
- `services/example-service/src/main/resources/application-mysql.yml`（新建：mysql profile 的数据源 + Flyway 配置）；
- `services/example-service/src/main/java/.../interfaces/{DemoNoteController}` + `infrastructure/{DemoNoteJdbcRepository}`（新建，演示端点，四层分层沿 2.4.1 规则）；
- `services/example-service/src/test/java/.../FlywayMigrationIT.java`（新建，条件执行）。

### 主要流程（4 步）

1. 开发者写迁移脚本：新文件 `V{下一版本号}__{描述}.sql` 放入 `db/migration/`，随 PR 提交评审；
2. 服务启动（mysql profile）：Flyway 对照 `flyway_schema_history` 表找出未应用的脚本 → 按版本号顺序逐个执行 → 每条记录版本/校验和/耗时；
3. 应用脚本被改坏（校验和不符）→ 启动直接失败并明确报"哪个版本被篡改"，杜绝静默漂移；
4. 已是最新 → 启动 no-op（幂等），重复重启零副作用。

### 待确认问题（需要你裁决）

1. 【工具选型】迁移工具选 **Flyway**（Spring Boot 原生集成 + 脚本就是纯 SQL + 资料最主流）。**是否认可**？（备选 Liquibase：变更用 XML/YAML 描述、支持结构对比生成，但多一层抽象、对无编程团队评审不友好）
2. 【规范载体】脚本规范固化进 **ADR-009 数据库迁移规范**（沿 2.4.x"边界进 ADR"惯例，含备选与可替换性两节过 adrFieldsCheck 门禁）。**是否认可**？（备选：只写在设计文档里，不留 ADR——后续任务引用不便）
3. 【落地方式】**不建 common 模块**，规范 + 每服务自持脚本目录；示例落在 example-service（演示表 + 只读端点）。**是否认可**？（备选：新建 common-migration 组件统一装配——无可复用代码，属过度设计）
4. 【无库兼容】默认 profile **无数据库照常启动**（保护 2.4.8/2.4.9 既有演示与测试），`mysql` profile 才启用数据源与迁移。**是否认可**？（备选：example-service 强制接库——本机无 MySQL，既有演示全部被破坏）
5. 【新依赖预授权】引入 4 项（版本全由 Spring Boot 3.5.16 BOM 管理，编码期实测核验后登记）：`org.flywaydb:flyway-core`、`org.flywaydb:flyway-mysql`、`org.springframework.boot:spring-boot-starter-jdbc`、`com.mysql:mysql-connector-j`。**是否认可**？
6. 【验证方式】自动化测试默认跳过数据库（条件集成测试），真实迁移演练用 **Docker 启动 MySQL 8 容器**人工触发——届时需要您启动 Docker Desktop（与上次 Watt Toolkit 同类：本会话无法代启 GUI 程序）。**是否认可**？

### 规格缺口声明

1. 制度依据缺口：无。基建任务以 WBS 2.4.10 产出定义 + ADR-001 + 章程 4.1 为依据；接口契约与边界值随高保真定稿，并同步固化进 ADR-009。
2. 观察项（不阻塞本任务）：① 2.4.11 Testcontainers 建成后，FlywayMigrationIT 的条件跳过可升级为容器化自动执行；② 首个真实业务包引入 MyBatis-Plus 时，需把"实体/表结构一致性"纳入其设计；③ 本机 MySQL 未安装、Docker Desktop 未常驻——2.5.x 部署基建落地后，开发库以容器化标准交付。

### 问题确认：
（待 PO 填写）
