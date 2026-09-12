# WBS-2.4.11 测试基建-集成测试（Testcontainers） · 低保真设计
- 型态：非界面类（基建任务，无界面）
- 对应规格：制度依据（基建任务，无 C-x.y 规格）=《C-TDS项目总体实施计划与WBS》2.4.11 产出定义（"Testcontainers 集成测试框架 + 示例"，工作量 1 天）+ ADR-001（技术栈冻结：MySQL 8 在栈内）+ ADR-009 §5（"2.4.11 Testcontainers 建成后，条件集成测试升级为容器化自动执行"）+ 章程 4.1（技术栈平庸化：选最主流实现）
- 任务卡：WBS 2.4.11 ｜ 工作量：1 天（章程 2.6.3：两级一并提交、一次确认）
- 关联设计：本文件为低保真；高保真 = `docs/designs/WBS-2.4.11-hifi.md`（同批提交，一次确认）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；沿用户画像既定"技术选型以判断原则+预授权下放，判定留痕即可、核验+登记义务不免"惯例 + 本会话开场"继续完成下一步任务"指令；AI 复评 6 项均无业务影响分歧项，判定留痕见下方"问题确认"节，表格由 AI 代录；设计确认询问未获编排师即时回复，按自治纪律不视为打回，**编码完成后随交付说明一并请编排师复核本代录**） | 2026-09-12 | 无 |

## 低保真内容（结构草图）

### 场景判定（决定框架选型，结论先行）

集成测试要验证"代码 + 真实中间件"协作正确（迁移真跑在 MySQL 8 上、端点真读写真库）。现状（2.4.10 交付）：条件集成测试靠环境变量 `CTDS_IT_MYSQL_URL` 门控——没配就跳过，真实验证靠人工起 Docker 容器演练。**缺口**：默认门禁环境（无库）下集成测试永远跳过 = 覆盖缺口（2.4.10 评审③已指认并接受，修复路径即本任务）；人工演练不进 `mvn test` 闭环，护栏场景（乱序拒绝、篡改 fail-fast）无法自动回归。

**选 Testcontainers**（Java 测试生态事实标准：测试运行时自动起"一次性真实容器"，测完自动销毁）。理由：
1. **Spring Boot 官方标准方案**：Boot 3 官方文档测试章节即讲 `spring-boot-testcontainers` + `@ServiceConnection`（容器连接参数自动注入，零胶水）；
2. **真实中间件**：测试连的是真 MySQL 8 容器，不是 H2 之类的替身——沿 2.4.7 "真实中间件在配了它的环境跑"原则，且把"配了它的环境"从人工降为自动；
3. **依赖核验**：`org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`、`org.springframework.boot:spring-boot-testcontainers` 版本全由 Spring Boot 3.5.16 BOM 统一管理（BOM 实测托管 testcontainers 1.21.4，本地仓库已有缓存），全部 test scope，**不进业务制品**；
4. **无 Docker 自动跳过**：`@Testcontainers(disabledWithoutDocker = true)`——本机 Docker Desktop 没运行时测试自动跳过、门禁不红（替代原环境变量门控，自动化程度只升不降）。

**不新建 common 测试模块**：当前只有 example-service 一个消费者，共享基类无二消费者属过度设计（沿 2.4.10"不建 common-migration"同口径）；共享的是**写法规范本身**，落 ADR-010。第二个真实消费者出现时再沉淀共享基类（沉淀建议写入 ADR-010 观察项）。

### 做什么（逐条对应产出定义）

1. **集成测试框架（产出定义-1）**：新建 `docs/adr/ADR-010-集成测试规范.md`（编码期实际落盘文件名，原稿"（Testcontainers 容器化）"后缀从简省略，更正留痕 2026-09-12），固化：容器化测试写法（`@Testcontainers(disabledWithoutDocker=true)` + `@ServiceConnection`）、镜像显式标签纪律（禁止 `latest`）、命名 `*Test`（surefire 默认拾取，2.4.10 教训）、无 Docker 环境自动跳过纪律（跳过留痕、门禁不红）、测试源集允许使用中间件客户端 API 断言（沿 ADR-009 §6）、共享基类沉淀条件（≥2 消费者）；含"备选"与"可替换性"两节过 adrFieldsCheck 门禁；
2. **示例（产出定义-2）**：
   - **升级**既有 `FlywayMigrationTest`：环境变量门控 → Docker 容器自动供给（`MySQLContainer` 显式 `mysql:8.0` 与 2.4.10 演练镜像同源 + `@ServiceConnection`），**断言集一字不改**（V1/V2 应用、history 两行、数据标题契约、no-op 幂等、端点 200/401 双向）；
   - **新增**护栏测试 `FlywayGuardrailTest`（承接 2.4.10 评审④与 hifi 边界值表标注的"随 2.4.11 补齐"两项）：① 乱序拒绝——已应用 V1/V3 后补低版本 V2，`out-of-order=false` 下不被执行（history 不增行；编码期实测更正：Flyway 11 validateOnMigrate 默认开启下为 `FlywayValidateException` fail-fast，强于"静默忽略"，口径详见 hifi B4 与护栏脚本契约表，2026-09-12）；② 篡改 fail-fast——已应用脚本被改，再迁移报校验和不符（2.4.10 人工演练第④步升级为自动回归）；此测试纯 JUnit + Testcontainers + Flyway API（不起 Spring 上下文，快），ADR-009 §6 允许测试源集使用 Flyway API；
3. **无 Docker 兼容（保护既有演示）**：默认门禁环境（Docker 未运行）容器化测试自动跳过，既有 44 测试全绿不变；Docker 运行时容器化测试真实执行（跳过态与执行态都在 mvn 输出留痕）；
4. **依赖登记**：`docs/dependencies.md` Maven 表新增 3 项（test scope，PO 预授权见 lofi 待确认 5）。

### 不做什么（V1.0 边界，防蔓延）

- **不建 common-testing/common-testcontainers 模块**：单消费者，最小实现（见场景判定）；
- **不做 E2E 框架**：2.4.12 的 scope（登录冒烟示例走前端 E2E）；
- **不引入 H2/嵌入式数据库**：违背"真实中间件"原则（2.4.10 已拒绝过同一备选）；
- **不改门禁脚本**：容器化测试走既有 unitTest 阶段 `mvn test`，run-gates.ps1 零改动；
- **不做容器复用（reuse）/CI 远程 Docker**：本机一次性容器即满足；复用策略随 2.5.x 部署基建评估；
- **不把 2.4.10 演练容器 ctds-mysql-demo 纳入测试**：测试用自己的一次性容器（随机端口随机口令），与演示容器彻底隔离。

### 结构组成（涉及文件清单）

- `docs/adr/ADR-010-集成测试规范.md`（新建，规范正文；文件名更正留痕见上"做什么-1"，2026-09-12）；
- `services/example-service/pom.xml`（+3 test scope 依赖，版本走 Boot BOM 不显式锁版）；
- `services/example-service/src/test/java/.../FlywayMigrationTest.java`（改造：门控机制替换，断言不变）；
- `services/example-service/src/test/java/.../FlywayGuardrailTest.java`（新建，护栏双用例）；
- `docs/dependencies.md`（Maven 表 +3 行）；
- `docs/logs/Ctds-项目开发日志-*.md`（本任务日志）。

### 主要流程（4 步）

1. 开发者写集成测试：需要真实中间件 → 按 ADR-010 声明容器（显式镜像标签 + `@ServiceConnection`），类名 `*Test`，随 PR 评审；
2. `mvn test`（本机 Docker 运行中）：Testcontainers 自动拉起一次性 MySQL 8 容器（随机端口、随机口令，密钥零入库）→ 测试连真实库执行 → 容器自动销毁；
3. `mvn test`（本机 Docker 未运行）：`disabledWithoutDocker` 生效，容器化用例标记跳过，其余测试照常全绿——门禁不红；
4. 迁移护栏自动回归：乱序脚本被拒、篡改脚本 fail-fast 两个原人工演练场景，从此每次 `mvn test`（有 Docker 时）自动验证。

### 待确认问题（需要你裁决）

1. 【框架选型】集成测试框架选 **Testcontainers**（测试时自动起一次性真实容器，Spring Boot 官方标准方案）。**是否认可**？（备选 A：维持现状——环境变量门控 + 人工演练，自动化缺口不补；备选 B：H2 内存库——不是真实 MySQL，迁移/方言行为会失真）
2. 【规范载体】容器化测试写法规范固化进 **ADR-010 集成测试规范**（沿 2.4.x"边界进 ADR"惯例，含备选与可替换性两节过 adrFieldsCheck）。**是否认可**？（备选：只写设计文档——后续业务服务引用不便）
3. 【落地方式】**不建 common 测试模块**，规范落 ADR-010、测试代码落 example-service 测试源集。**是否认可**？（备选：新建 common-testing 共享基类——当前仅 1 个消费者，属过度设计；沉淀条件写入 ADR-010）
4. 【既有测试升级】`FlywayMigrationTest` 的环境变量门控**替换**为容器自动供给（断言集不变）。**是否认可**？（备选：两套并存——同一批断言两套门控，维护双份无收益）
5. 【新依赖预授权】引入 3 项（版本全由 Spring Boot 3.5.16 BOM 管理，全部 test scope，不进业务制品，编码期实测核验后登记）：`org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`、`org.springframework.boot:spring-boot-testcontainers`。**是否认可**？
6. 【验证方式】容器化测试**真实执行**需要本机 Docker Desktop 处于运行状态（未运行则自动跳过、门禁照常绿）；真实执行演示时可能需要您启动 Docker Desktop（沿 2.4.10 惯例，computer-use 已在预授权范围）。**是否认可**？

### 规格缺口声明

1. 制度依据缺口：无。基建任务以 WBS 2.4.11 产出定义 + ADR-001 + ADR-009 §5 + 章程 4.1 为依据；行为清单与边界值随高保真定稿，容器化写法契约同步固化进 ADR-010。
2. 观察项（不阻塞本任务）：① 共享测试基类沉淀条件（≥2 真实消费者）写入 ADR-010；② 门禁 coverage/mutationTest 阶段（PENDING）启用时，容器化测试的覆盖率统计口径（是否计入）随 2.2.x 工具链任务裁决；③ 2.4.12 E2E 框架若也需容器（如浏览器容器），沿 ADR-010 扩展。

### 问题确认：

PO 预授权（用户画像既定惯例："技术选型以判断原则+预授权下放，判定留痕即可，核验+登记义务不免"；本会话指令"继续完成下一步任务"承接 2.4.11）。AI 复评结论：**6 项均无业务影响分歧项**（备选对最终用户完全无可见差异，差异仅工程内部自动化程度），按判断原则自主选定并留痕：

| 问题 | 判定 | 判定原则（为何无需 PO 二选一） |
| --- | --- | --- |
| 1 框架选型 | Testcontainers | 章程 4.1 技术栈平庸化：Spring Boot 官方文档标准方案即默认项；备选 A（维持现状）= 评审③已指认的覆盖缺口不补属纯负面，备选 B（H2）违背 2.4.7 既定"真实中间件"原则（2.4.10 已拒过同一备选） |
| 2 规范载体 | ADR-010 | 沿 2.4.5–2.4.10"边界进 ADR"既有惯例，无新惯例创设 |
| 3 落地方式 | 不建 common 测试模块 | 章程"最小实现/防蔓延"；单消费者是客观事实（沿 2.4.10"不建 common-migration"同口径），沉淀条件（≥2 消费者）写入 ADR-010 |
| 4 既有测试升级 | 门控替换（断言不变） | 断言集一字不改 = 行为零变化，仅门控机制自动化升级；两套并存 = 同一批断言双份维护，纯负担 |
| 5 新依赖 3 项 | 引入 | 全部为 Boot 3.5.16 BOM 生态必配（无栈内替代）、全 test scope 不进业务制品，核验+登记义务照常履行 |
| 6 验证方式 | Docker 运行时真实执行、未运行自动跳过 | 沿 2.4.10 PO 已确认"本机 Docker 环境可用"声明（条件成立）；未运行自动跳过是纯保护性默认（保门禁全绿） |
