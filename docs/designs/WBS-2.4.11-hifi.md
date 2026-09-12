# WBS-2.4.11 测试基建-集成测试（Testcontainers） · 高保真设计
- 型态：非界面类（基建任务，无界面）
- 对应规格：制度依据（基建任务，无 C-x.y 规格）=《C-TDS项目总体实施计划与WBS》2.4.11 产出定义（"Testcontainers 集成测试框架 + 示例"，工作量 1 天）+ ADR-001（MySQL 8 冻结）+ ADR-009 §5 + 章程 4.1（技术栈平庸化）；方向确认 = `docs/designs/WBS-2.4.11-lofi.md`（PO 签署见其确认记录）
- 任务卡：WBS 2.4.11 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为高保真（= 编码契约）；低保真 = `docs/designs/WBS-2.4.11-lofi.md`
- 本文件新引入 Maven 依赖将随编码登记 `docs/dependencies.md`（PO 授权留痕见 lofi 确认记录问题 5）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；与 lofi 同批一次确认，PO 预授权按判断原则自主选定，判定留痕见 lofi"问题确认"节，AI 代录，编码完成后随交付一并请编排师复核） | 2026-09-12 | 无 |

## 行为清单（8 项，逐条对应 lofi 已确认方向与计划测试）

| 编号 | 行为（业务可读） | lofi 出处 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 集成测试规范固化 `docs/adr/ADR-010-集成测试规范（Testcontainers 容器化）.md`（写法/镜像标签纪律/命名/跳过纪律/沉淀条件），含"备选"与"可替换性"两节（adrFieldsCheck 过门禁） | lofi 做什么-1 + 待确认 2 | 门禁 adrFieldsCheck PASS（9+1 ADR 全字段） |
| B2 | example-service 引入 Testcontainers 三件套（版本走 Boot 3.5.16 BOM，全部 test scope），`dependencies.md` 登记 3 项（审批栏注明 PO 预授权 + lofi 问题 5） | lofi 做什么-4 + 待确认 5 | mvn test-compile PASS + dependency:tree 实测解析留痕（test scope 不进业务制品） |
| B3 | `FlywayMigrationTest` 升级容器化：`MySQLContainer`（显式镜像 `mysql:8.0`，库名 ctds_demo，随机端口/随机口令）+ `@ServiceConnection` 自动注入连接参数；**断言集与 2.4.10 定稿完全一致**（V1/V2 依次应用、history 两行 success=1、demo_note 恰 2 行、标题契约、二次迁移 no-op、端点 200/401 双向）；环境变量门控 `CTDS_IT_MYSQL_URL/USER/PASSWORD` 移除 | lofi 做什么-2 示例① + 待确认 4 | Docker 运行时：2 用例真实执行 PASS；Docker 未运行：自动跳过留痕 |
| B4 | 新增护栏测试 `FlywayGuardrailTest`（纯 JUnit + Testcontainers + Flyway API，不起 Spring 上下文）：①乱序拒绝——history 已应用 V1/V3 后补入低版本 V2 脚本，`outOfOrder(false)` 下 `migrate()` 零执行、history 不增行（ADR-009 规则 7 自动回归）；②篡改 fail-fast——V1 应用后被修改，再 `migrate()` 抛校验和不符异常（ADR-009 规则 5 自动回归，2.4.10 人工演练第④步升级） | lofi 做什么-2 示例② | Docker 运行时：2 用例真实执行 PASS；Docker 未运行：自动跳过留痕 |
| B5 | 无 Docker 兼容：Docker Desktop 未运行时，B3/B4 全部用例由 `disabledWithoutDocker=true` 自动跳过，既有 44 测试（2 跳 → 跳过数增加）全绿不变，门禁不红 | lofi 做什么-3 | 默认环境 `mvn test` 全绿（跳过数留痕） |
| B6 | Docker 运行时全量真实执行：容器化 4 用例（B3×2 + B4×2）全部真实跑通，一次性容器测后自动销毁，不残留（不再使用 2.4.10 演练容器 ctds-mysql-demo 与固定端口 3307） | lofi 主要流程-2/4 | `mvn test`（Docker 运行中）输出 4 用例 PASS 留痕 + `docker ps` 无残留 |
| B7 | 密钥零入库：容器凭据由 Testcontainers 随机生成、仅存在于测试进程内存；代码/配置/日志不出现任何口令（沿章程红线 7） | 章程红线 7 | 人工核查 + 门禁 secretsScan PASS |
| B8 | 既有行为零回归：分层 ArchUnit 规则、其余单测、`std-adapter` 探活等不受影响；门禁脚本与配置零改动 | lofi 不做什么 | 全量门禁 GREEN（PASS 数与 2.4.10 时点一致） |

## 容器契约（B3/B4）

| 项 | 契约 |
| --- | --- |
| 镜像 | `mysql:8.0`（显式标签，与 2.4.10 演练镜像同源；禁止 `latest`——ADR-010 纪律；镜像 minor 升级随 ADR-001 变更流程） |
| 生命周期 | `@Container` 静态字段：每个测试类起 1 个容器，类内用例共享，类结束自动销毁（Testcontainers 默认 Ryuk 看护，异常退出也无残留） |
| 端口/凭据 | 随机宿主端口 + Testcontainers 随机生成用户/口令（密钥零入库，B7） |
| 库名 | `ctds_demo`（与 2.4.10 示例迁移的目标库名一致，脚本零改动） |
| 连接注入 | B3 用 `@ServiceConnection`（spring-boot-testcontainers 自动注入 `spring.datasource.*`，覆盖 application-mysql.yml 占位符）；B4 直接取 `container.getJdbcUrl()/getUsername()/getPassword()` 构建 Flyway 实例 |
| Flyway 行为 | B3 沿 Spring Boot auto-config（`@ActiveProfiles("mysql")` 上下文启动即迁移）；B4 程序化构建 `Flyway.configure().dataSource(...).locations(...).outOfOrder(false).load()`，locations 指向测试临时目录（护栏脚本与生产脚本物理隔离，不触碰 `src/main/resources/db/migration`） |

## 护栏测试脚本契约（B4，测试源集内动态生成于临时目录，非 main 源集文件）

| 用例 | 临时目录脚本序列 | 断言 |
| --- | --- | --- |
| 乱序拒绝 | 第一次 `migrate()`：`V1__a.sql` + `V3__c.sql`（跳版本模拟"他人先合了 V3"）→ history 2 行 success=1；随后补入 `V2__b.sql` 再 `migrate()` | 第二次 `migrationsExecuted == 0`；history 仍 2 行、无 version=2 记录（`out-of-order=false` 下低版本脚本被忽略——hifi 2.4.10 边界值表"被忽略并告警"口径的自动化验证） |
| 篡改 fail-fast | 第一次 `migrate()`：`V1__a.sql` 应用成功 → 追加注释行修改该文件 → 再 `migrate()` | 抛 `FlywayException`，异常消息含 "checksum"（校验和防篡改，ADR-009 规则 5） |

## 依赖锁定表（B2，Maven，版本由 Spring Boot 3.5.16 BOM 管理，编码期实测核验）

| 坐标 | 锁定版本 | 用途 | 核验来源与日期 | 审批记录 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `org.testcontainers:junit-jupiter` | 1.21.4（Boot 3.5.16 BOM 管理，pom 不显式锁版；dependency:tree 实测解析） | Testcontainers JUnit 5 扩展（`@Testcontainers`/`@Container`/Docker 探测） | Boot BOM testcontainers.version=1.21.4 + 本地仓库 1.21.4 缓存，2026-09-12 | PO 预授权（lofi 问题 5） | test scope |
| `org.testcontainers:mysql` | 1.21.4（同上） | MySQL 8 容器模块（`MySQLContainer`） | 同上，2026-09-12 | 同上 | test scope；内含 jdbc 传递依赖 |
| `org.springframework.boot:spring-boot-testcontainers` | 3.5.16（BOM） | `@ServiceConnection` 连接参数自动注入（Boot 3.1+ 官方集成） | Boot BOM 同版本，2026-09-12 | 同上 | test scope |

## 边界值与异常行为

| 场景 | 行为 |
| --- | --- |
| Docker Desktop 未运行（默认门禁环境） | B3/B4 自动跳过（`disabledWithoutDocker`），其余 44 测试照常全绿，门禁不红；跳过数在 mvn 输出留痕 |
| Docker 运行中但首次拉取 `mysql:8.0` 镜像 | Testcontainers 自动拉取（本机已有该镜像——2.4.10 演练拉取过，秒级启动） |
| 测试进程异常中断 | Ryuk 看护容器自动回收一次性容器与卷，`docker ps`/`docker volume ls` 无残留 |
| 乱序脚本（history 最大版本后补低版本） | `outOfOrder=false` 下被忽略不执行（不报错不阻塞）——护栏用例①断言 |
| 已应用脚本被篡改 | `migrate()` 抛校验和不符异常 fail-fast——护栏用例②断言 |
| 容器内 MySQL 未就绪 | Testcontainers 内置就绪等待（log/message 探测），无需测试代码 sleep |
| 业务制品 | Testcontainers 三件套全 test scope，`mvn dependency:tree` compile/runtime 作用域不含——不进业务 jar |

## 测试计划（映射 B1–B8）

1. **无 Docker 态**（Docker Desktop 未运行）：`mvn -pl services/example-service -B test` → 全绿 + 容器化用例 skipped 留痕（B5）；
2. **有 Docker 态**（Docker Desktop 运行中，必要时经预授权代启）：同命令 → B3×2 + B4×2 共 4 用例真实 PASS，其余不变（B3/B4/B6）；
3. `docker ps -a --filter name=testcontainers` 无残留容器（B6）；`mvn dependency:tree -Dincludes=org.testcontainers` 与 `dependency:tree -Dincludes=org.springframework.boot:spring-boot-testcontainers` 确认 test scope（B2）；
4. 既有 ArchUnit 分层测试与其余单测全绿（B8）；
5. 门禁：全量 run-gates GREEN（adrFieldsCheck 覆盖 ADR-010，secretsScan 覆盖新增代码）（B1/B7/B8）。

## 复用声明（检索过程）

- 集成测试基座检索过 `common` 公共组件（errorcode/pagination/logging/auth/crypto/idempotency 均无测试基座职责）→ 无可复用代码；框架能力由 Testcontainers 官方库承担，不自研任何容器管理逻辑（"组件只封装、协议不自研"）；
- 容器化写法沿用 Spring Boot 官方文档标准模式（@ServiceConnection），不引入注解封装/基类抽象；
- 护栏脚本契约沿 ADR-009 既有规则（规则 5 篡改 fail-fast、规则 7 禁乱序），本任务只是把已验收的人工演练场景升级为自动回归；
- 规格外实现声明：无（本设计全部条目可回溯 WBS 2.4.11 产出定义 + ADR-001/ADR-009 + lofi 已确认方向）。

## 变更影响声明

- 验收剧本：现有剧本无集成测试节点，**无需更新**；如 PO 认可，可增补"容器化集成测试演示"节点（Docker 运行时 `mvn test` 输出 4 用例 PASS）；
- 追溯矩阵：基建任务无 C-x.y 规格文件，不涉及；
- 依赖登记簿：`docs/dependencies.md` Maven 区新增 3 项（本文件依赖锁定表为登记数据源）；
- ADR：**新建 ADR-010 集成测试规范（Testcontainers 容器化）**（含"备选"与"可替换性"两节，过 adrFieldsCheck 门禁）；ADR-009 §5 所述"2.4.11 建成后升级"随之达成（ADR-009 不改动，升级事实由本设计 + ADR-010 留痕）；
- 既有演示兼容：门禁环境无 Docker 时行为与 2.4.10 合并时点一致（全绿，仅跳过用例的跳过原因从"无环境变量"变为"无 Docker"）；2.4.10 演练容器 ctds-mysql-demo 不受影响（验收后处置由 PO 决定）。
