# WBS-2.4.10 数据库迁移工具链 · 高保真设计
- 型态：非界面类（任务卡已标注）
- 对应规格：制度依据（基建任务，无 C-x.y 规格）=《C-TDS项目总体实施计划与WBS》2.4.10 产出定义（"迁移脚本规范、版本化管理、示例迁移"，工作量 1 天）+ ADR-001（MySQL 8 冻结）+ 章程 4.1（技术栈平庸化）；方向确认 = `docs/designs/WBS-2.4.10-lofi.md`（PO 签署见其确认记录）
- 任务卡：WBS 2.4.10 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为高保真（= 编码契约）；低保真 = `docs/designs/WBS-2.4.10-lofi.md`
- 本文件新引入 Maven 依赖将随编码登记 `docs/dependencies.md`（PO 授权留痕见 lofi 确认记录问题 5）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 待确认 | | | |

## 行为清单（9 项，逐条对应 lofi 已确认方向与计划测试）

| 编号 | 行为（业务可读） | lofi 出处 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 迁移脚本规范固化 `docs/adr/ADR-009-数据库迁移规范.md`（命名/位置/一条一事/禁改已应用脚本/只前进不回滚/PR 评审链），含"备选"与"可替换性"两节（adrFieldsCheck 过门禁） | lofi 做什么-1 + 待确认 2 | 门禁 adrFieldsCheck PASS（8+1 ADR 全字段） |
| B2 | example-service 引入 Flyway 依赖四件套（版本走 Boot 3.5.16 BOM），`dependencies.md` 登记 4 项（审批栏注明 PO 预授权 + lofi 问题 5） | lofi 做什么-2 + 待确认 5 | mvn compile PASS + 依赖树实测解析留痕 |
| B3 | 默认 profile 行为零变化：不配数据源、无 `mysql` profile 时 Flyway 不装配，服务照常启动，既有测试与端点不受影响 | lofi 做什么-4 + 待确认 4 | 既有全量单测 PASS（不碰数据库）+ 无 profile 启动冒烟 |
| B4 | 示例迁移两脚本：`V1__create_demo_note.sql` 建 demo_note 表（id/标题/内容/创建时间）+ `V2__seed_demo_note.sql` 插入 2 行演示数据 | lofi 做什么-3 | 条件集成测试（B7）+ Docker 演练（B9） |
| B5 | 只读演示端点 `GET /api/v1/demo-notes`：JdbcTemplate 读 demo_note 全部行，按 id 升序，返回统一封套 `{code:"0", traceId, data:[...]}`（沿 ADR-005 封套规范） | lofi 做什么-3 | 端点行为验证并入 B7 条件集成测试与 B9 Docker 演练（不引入 H2，不为单测伪造数据源） |
| B6 | `mysql` profile 配置 `application-mysql.yml`：数据源（localhost:3306/ctds_demo，账号口令经环境变量注入，**密钥零入库**）+ `spring.flyway` 开启、`baseline-on-migrate=false` | lofi 做什么-2/4 | 配置装配单测（profile 激活后 Flyway bean 存在）+ Docker 演练 |
| B7 | 条件化集成测试 `FlywayMigrationIT`：环境变量 `CTDS_IT_MYSQL_URL` 存在才执行（验证 V1/V2 依次应用、flyway_schema_history 有两行记录、重复执行 no-op），否则标记跳过——默认门禁环境无库也全绿 | lofi 做什么-5 | @EnabledIfEnvironmentVariable 条件单测；跳过态在默认 mvn test 输出留痕 |
| B8 | 四层分层沿 2.4.1 ArchUnit 规则：DemoNoteController（interfaces）→ 不直连数据层（经 infrastructure 的 DemoNoteJdbcRepository）；JdbcTemplate 封装在 infrastructure | lofi 做什么-3 | 既有 ArchUnit 测试 PASS（新类自动纳入扫描） |
| B9 | 真实迁移演练（Docker MySQL 8）：空库启动 → V1/V2 依次应用 → 端点返回 2 行 → 重启 no-op → 篡改已应用脚本 → 启动失败报校验和错误；命令与输出附交付说明 | lofi 做什么-6 | 人工/半自动演练留痕（业务可读步骤） |

## 接口契约（B5，沿 ADR-005 封套）

`GET /api/v1/demo-notes`

| 项 | 契约 |
| --- | --- |
| 成功响应 | `{ "code": "0", "message": null, "traceId": "<链路ID>", "data": [ { "id": 1, "title": "...", "content": "...", "createdAt": "..." } ] }` |
| 未启用 mysql profile 时 | 端点不存在（404，控制器条件装配 `@ConditionalOnProperty(ctds.demo.db-enabled)` 或 `@Profile("mysql")`），不产生"服务不可用"误导 |
| 数据库不可达 | 服务启动失败（Spring 数据源初始化失败，错误不暴露口令等内部信息）——迁移场景"带库启动失败优于带病运行" |

## 配置契约（B6，application-mysql.yml）

```yaml
spring:
  datasource:
    url: ${CTDS_DB_URL:jdbc:mysql://localhost:3306/ctds_demo}
    username: ${CTDS_DB_USER:root}
    password: ${CTDS_DB_PASSWORD:}      # 口令只经环境变量，默认空即未配置不可用（密钥零入库）
  flyway:
    enabled: true                        # 仅 mysql profile 生效（默认 yml 不配置 flyway 键）
    locations: classpath:db/migration
    out-of-order: false                  # 禁止乱序补脚本（版本号必须递增）
```

演示端点开关：`ctds.demo.db-enabled`（mysql profile 内置 true；控制器与仓储条件装配于该开关，与 profile 双保险）。

## 示例脚本契约（B4）

```sql
-- V1__create_demo_note.sql
CREATE TABLE demo_note (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(64)  NOT NULL COMMENT '标题',
  content VARCHAR(512) NOT NULL COMMENT '内容',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迁移工具链演示表（WBS 2.4.10）';

-- V2__seed_demo_note.sql
INSERT INTO demo_note (title, content) VALUES
  ('迁移演示', '本行数据由 V2__seed_demo_note.sql 写入（Flyway 版本化管理演示）'),
  ('版本递进', 'V1 建表 → V2 灌数据：每次结构变更都是新版本脚本');
```

## 依赖锁定表（B2，Maven，版本由 Spring Boot 3.5.16 BOM 管理，编码期实测核验）

| 坐标 | 锁定版本 | 用途 | 核验来源与日期 | 审批记录 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `org.flywaydb:flyway-core` | Boot 3.5.16 BOM 管理（编码期 `dependency:get` 实测解析） | 迁移引擎（版本化管理 + 校验和防篡改 + flyway_schema_history） | 阿里云镜像实测，2026-09-12 | PO 预授权（lofi 问题 5） | 选型判定见 lofi 场景判定 |
| `org.flywaydb:flyway-mysql` | 同上 | Flyway 8.2+ 拆分的 MySQL 方言支持（缺它 MySQL 连接不识别） | 同上 | 同上 | 与 flyway-core 同版本 |
| `org.springframework.boot:spring-boot-starter-jdbc` | 3.5.16（BOM） | DataSource + JdbcTemplate + Flyway 自动装配前提 | Boot BOM，2026-09-12 | 同上 | — |
| `com.mysql:mysql-connector-j` | 同上 | MySQL 8 JDBC 驱动（ADR-001 冻结栈配套） | 同上 | 同上 | runtime scope |

## 边界值与异常行为

| 场景 | 行为 |
| --- | --- |
| 数据库已是最新（重启/重复启动） | Flyway no-op，启动正常（幂等），零副作用 |
| 已应用脚本被修改（校验和不符） | 启动失败，报"Detected applied migration not resolved locally / 校验和验证失败"并指明版本号（篡改防护；修复须走 ADR-009 规定的处理流程，禁止改历史脚本） |
| 脚本版本号倒退/乱序提交 | out-of-order=false 下新低版本脚本被忽略并告警（规范要求版本号必须取 history 最大值+1） |
| 数据库不可达（口令错/库未启动） | 服务启动失败（fail-fast），错误信息不含口令；演示端点不产生部分可用状态 |
| 未启用 mysql profile | 数据源/Flyway/演示端点全部不装配，行为与 2.4.9 合并时点完全一致 |
| V2 灌数据脚本重复执行 | 不会发生（flyway_schema_history 已记录版本，Flyway 跳过已应用脚本）——演示表无唯一键冲突风险 |

## 测试计划（映射 B1–B9）

1. 既有全量单测（默认 profile）：全部 PASS 且零数据库依赖（B3）；
2. `mvn -pl services/example-service -B test`：新增/修改类纳入 ArchUnit 分层扫描（B8）；
3. `FlywayMigrationIT`（B7）：`@EnabledIfEnvironmentVariable(named = "CTDS_IT_MYSQL_URL", matches = ".+")`；断言 V1/V2 应用、history 两行、二次执行 no-op、demo_note 恰 2 行；
4. Docker 演练（B9，人工触发，PO 启动 Docker Desktop 后执行）：`docker run -d --name ctds-mysql-demo -e MYSQL_ROOT_PASSWORD=<演练口令> -p 3306:3306 mysql:8` → `CREATE DATABASE ctds_demo` → 以 mysql profile 启动服务 → curl 端点验 2 行 → 重启验 no-op → 篡改脚本验 fail-fast；全部命令与输出摘要附交付说明；
5. 门禁：全量 run-gates GREEN（adrFieldsCheck 覆盖 ADR-009）。

## 复用声明（检索过程）

- 迁移能力检索过 `common` 公共组件（errorcode/pagination/logging/auth/crypto/idempotency 均无数据库 schema 管理职责）→ 无可复用代码，规范落 ADR-009、能力由 Spring Boot 官方 Flyway 集成承担，不自研任何迁移逻辑；
- 统一封套/TraceId/错误码复用 common-errorcode + 既有 Controller 惯例（沿 example-service 现有端点写法）；
- 条件集成测试模式沿 2.4.7"真实中间件在配了它的环境跑"惯例（lofi 规格缺口声明-2 同源）；
- 规格外实现声明：无（本设计全部条目可回溯 WBS 2.4.10 产出定义 + ADR-001 + lofi 已确认方向）。

## 变更影响声明

- 验收剧本：现有剧本无数据库节点，**无需更新**；新增 Docker 迁移演练步骤建议入剧本（由 PO 定，见交付说明建议文本）；
- 追溯矩阵：基建任务无 C-x.y 规格文件，不涉及；
- 依赖登记簿：`docs/dependencies.md` Maven 区新增 4 项（本文件依赖锁定表为登记数据源）；
- ADR：**新建 ADR-009 数据库迁移规范**（含"备选"与"可替换性"两节，过 adrFieldsCheck 门禁）；
- 既有演示兼容：默认 profile 零变化——2.4.8 探活、2.4.9 前端联通演示不受影响。
