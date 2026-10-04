# WBS-3.3.7 高保真设计（编码契约）——目录模块测试与集成

- 版本：V1.0（2026-10-04，立卡会话起草）；配套任务卡 `docs/tasks/WBS-3.3.7-目录模块测试与集成-2026-10-04.md`；低保真 = `docs/designs/WBS-3.3.7-lofi.md`
- 确认记录：✅ **编排师已确认（2026-10-04 20:0x 会话回复"都按建议"）——Q0~Q5 全采建议口径 A + D1 不拆分，本文件即为编码契约**
- 立卡盘点证据：两路只读子智能体（2026-10-04）——① catalog 测试覆盖面逐项核实（18 类 186 例）；② 万级造数落点与基准先例（全仓无基准脚本，本卡为首例）

## 1. 达标口径与工具形态（对应 Q2）

1. **达标线**：catalog-service 行覆盖 **≥70%**（catalog 不在章程核心五模块清单〔policy-engine / metering-billing / crypto / auth / did〕，沿 3.2.7 space 同款；**编码开工先核读 ADR-018 coverage 段对 catalog 的归档档位，若 ADR-018 归 core 则按其口径执行并如实登记**）。
2. **工具形态 = jacoco 命令行临时注入**（沿 3.2.7 先例，零 pom 变更）：
   `mvn -B -ntp -pl services/catalog-service org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report`
   解析 `services/catalog-service/target/site/jacoco/jacoco.xml` 的 LINE 计数（INSTRUCTION_MISSED/COVERED 或 LINE_COUNTERS），报告不入库；数字与命令原文写入日志留痕。
3. **基线**：后端 18 类 **186 例**（grep 实测 2026-10-04）；编码开工先 `mvn test` 复跑取 mvn 实测数为准，预计补测后 **190± 例**，只增不减。
4. 前端回归口径：catalog 域 7 spec（≈78 例）+ 全量 vitest 全绿；若 Q5 采 A 则 +1 例。

## 2. 盘点方法与映射骨架（对应任务卡 §三）

1. **方法**：规格行为 1~7 每条验收标准 → 既有测试锚点（18 类对照）→ 缺口 → 补测项 → 剧本步骤；编码会话补测后回填任务卡 §三全量映射表。
2. **既有覆盖骨架**（盘点①结论）：
   | 行为 | 主要锚点 |
   | --- | --- |
   | 1 登记（含重要数据拒收、幂等、资格链序） | DatasetLifecycle 19 / TagVocabulary 16 / CategoryMembership 10 |
   | 2 变更注销（名称锁、from→to 留痕） | DatasetLifecycle / ActionLogs 13 |
   | 3 封装定价（幂等重放 S1-6） | Encapsulation 16 |
   | 4 上下架（状态机、越权 DENIED 留痕） | ProductLifecycle 17 |
   | 5 检索分类（仅上架/防枚举/分页） | ProductSearch 8 |
   | 6 收藏订阅（并发幂等 DB-34、状态门） | Interaction 12 + InteractionServiceUnit 2 |
   | 7 边界治理（R12/R13 GOVERNANCE_VIEW、治理例外） | Governance 7 + ChangeLog 5 + ActionLogs |
   | 迁移与基础设施 | Migration 12+8+4 / Domain 13 / 两个 Client 22 / ExceptionHandler 1 |
3. **净缺口**（已逐项核实）：T1 写面 UNAVAILABLE ×1；T2 CANCELLED 订阅锚；T3 R7/R8 零留痕锚（现状待核）；T4 R6 分页越界锚；（视 Q5）F1 前端正向例。
4. **口径核对项（先核实后动，红线 4）**：R7（详情三态同形 404）/ R8（非订阅者同形 404）被拒路径实现是否零留痕——若留痕 → 与 DB-37 落定口径冲突 → **停止上报编排师裁决**，本卡不擅自修；若零留痕 → 补零留痕断言锚定。

## 3. 补测清单 T1~T4（+F1）

| 项 | 内容 | 建议落点 | 断言要点 |
| --- | --- | --- | --- |
| **T1** | 产品写面遇 UNAVAILABLE（主体资格门不可用）专属用例 ×1（DB-38 剩余份额） | 依实现实测定：封装或收藏/订阅写面 `CatalogAccessGuard.requireAdmitted` 分支（建议 `CatalogProductEncapsulationIntegrationTest` 或 `CatalogProductInteractionIntegrationTest`；stub `@MockitoBean` 抛 UNAVAILABLE 同登记面先例 `DatasetLifecycle` L150-169） | 503 / `1007S0001` 同形 + 零新行（产品/留痕不增）+ 与"资格拒绝"文案不同形（不可用 ≠ 拒绝） |
| **T2** | CANCELLED 产品新发起**订阅**专属锚（DB-38 对称补齐；收藏已由 3.3.5 T10 `cancelledProductNewFavoriteRejected` 覆盖） | `CatalogProductInteractionIntegrationTest`（状态直改 `UPDATE data_product SET status='CANCELLED'` 沿 L195 先例）或扩展 `newFavoriteOrSubscriptionOnNonListedProductRejectedSameShape` 遍历面 | 404 / `1007C0011` 同形 + DENIED 留痕含 denyReason + 订阅行不增 |
| **T3** | R7/R8 读面拒绝**零留痕**锚（DB-37 落定口径反向锚；先核实现状，§2.4） | `CatalogProductSearchIntegrationTest`（详情三态同形）+ `CatalogProductInteractionIntegrationTest`（R8 变更记录非订阅者同形） | 同形 404 + `product_action_log` / `dataset_action_log` 行数**不变**（零留痕断言） |
| **T4** | R6 检索主端点分页越界锚 | `CatalogProductSearchIntegrationTest` | pageSize=101 → 400 / `1000C0001`（沿 ActionLogs L189 同款断言）；pageSize=100 → 200 |
| **F1**（视 Q5） | 变更弹窗"改后确被提交"正向用例（3.3.6 评审④遗留 P3） | `DatasetManageView.spec.ts` 或 `ProductManageView.spec.ts`（走查 T12/T13 既有锚就近落） | 弹窗提交后请求体含修改后值 + 界面回显为改后值（测试先行 RED→GREEN） |

**可选强化（不占关闭条件，编码会话顺手则做，不做须登记原因）**：GOVERNANCE_VIEW 留痕"何时"要素显式断言（现锚定为"动作+操作者+对象恰 1 行"，时间列落库未显式断言，可沿 3.2.7 T2 的 IsoSecondTimestamp 同构补 createdAt 断言）。

## 4. 万级目录数据集构造与基准方案（对应 Q3；WBS 4.2.1 消费）

1. **交付物 = 两个 SQL 脚本（单一事实源）**，落 `scripts/benchmark/`：
   - `catalog-seed.sql`：占位参数（space_id 基段、行数）——MySQL 8 递归 CTE 批量生成；**造数构成：资源 10,000 + 产品 10,000（1:1），挂 4 个独立空间段（建议 990001~990004），24 类目均匀分布，产品名/简介含可检索词（约 20% 含固定关键词），LISTED 态，listed_at 递增**；不触留痕表（检索链路不涉）；
   - `catalog-cleanup.sql`：按 space_id 段 DELETE（先 SELECT COUNT 报数后删，删后核验 0 行）；**不入 `db/migration`**（零迁移承诺）。
   - 若 CTE 路线受阻（版本/语法实测不过）→ 降级 Java batchUpdate 生成并在本文件登记降级与原因。
2. **基准探针 = 集成测试**（test 树新增基准类，如 `CatalogSearchBenchmarkIT`，`disabledWithoutDocker`）：加载 `catalog-seed.sql`（替换占位）经 JdbcTemplate 执行 → MockMvc **全链打 R6**（`@MockitoBean` stub 资格门 ADMITTED）→ 五场景各 50 轮：
   ① 全量第一页（无过滤）② 类目过滤 ③ 关键词命中 ④ 关键词零命中 ⑤ 深翻页；
   记录每轮耗时（ms），算 **P50 / P95 / max**；结果写 `services/catalog-service/target/benchmark/w337-bench-*.json`（target 不入库）+ 数字摘要入日志（业务可读）。
3. **达标判定**：P95 ≤ **500ms**（章程指标锚）——容器内单机口径；**诚实边界（结果留痕必写）**：资格门为 stub（不含真实 subject HTTP 往返与生产网络）、无并发加压，正式并发压测与阶梯加压归 4.2.1。
4. **演示库零污染**：基准只在测试容器内跑（类级独立库名，Ryuk 自灭）；演示库 `ctds_catalog` 本卡零接触；`catalog-cleanup.sql` 本卡不对演示库执行（供 4.2.1 与未来真链压测使用）。
5. **与 4.2.1 的移交关系**：脚本 + 数据构成说明（本节）即"性能测试数据集与脚本"的 3.3.7 份额；阶梯加压、基线报告全量版归 4.2.1（移交说明写入交付说明）。

## 5. 联调方案（对应 Q1）

1. **环境**：演示环境保留运行（三容器 + subject 8080 / space 8083 / catalog 8084 + 前端 5173）；联调前核验服务存活（curl 探针）。
2. **链路**（curl + 演示身份角色头，沿 3.3.6 走查惯例；每步留 JSON 证据）：
   - **C-3.1 关键链**：登记（重要数据拒收探针 + 同请求幂等重放返回首次）→ 变更（留痕 from→to）→ 注销（二次确认 + 同空间名称锁）；
   - **C-3.2 关键链**：检索（防枚举拒 + 关键词前缀匹配口径 + 分页）→ 详情（三态同形）→ 收藏/订阅（CANCELLED 拒绝面 = T2 对应判定）；
   - **C-3.3 关键链**：封装（S1-4 幂等两路：重放返回首次 / 非重放同名拒 `1007C0017`）→ 上架（S2-5 链序：属主门槛先行 `1007C0015`）→ 下架 / 治理强制下架（留痕含理由）；
   - **F1/F2 修复点抽验**：F1 = 演示身份切换后数据重拉（5173 浏览器抽验或接口面复验）；F2 = 详情页无价格数值行（5173 浏览器抽验）。
3. **数据处置**：联调新增写数据**留置并登记清单**（沿裁定②：空间/资源/产品/留痕计数入日志，下次真机演示/走查前统一清理）；零删改历史演示数据。
4. 证据落 `frontend/test-results/walkthrough/w337lt-*.json`（gitignored，沿 3.3.6 惯例）+ 判定表入日志。

## 6. 交付门禁（全绿 + 用例只增不减）

1. `mvn -B -ntp -pl services/catalog-service test checkstyle:check` → 全绿 + 0 违规（186 → 190± 例）；
2. jacoco 行覆盖实测 ≥70%（命令原文 + 数字留痕）；
3. 前端 `npm run test`（29 files 全绿）+ `typecheck` + `lint` + `build`（若 Q5 采 A 有前端改动；零前端改动则 build 一次佐证）；
4. 交付态 `mvn -B -ntp -pl services/catalog-service -am package` BUILD SUCCESS；
5. 全仓 `scripts/gates/run-gates.ps1` GREEN；
6. 基准结果达标（P95 ≤500ms，容器内口径）留痕。

## 7. 边界值

| 维度 | 取值 |
| --- | --- |
| 分页 | pageNum=0（拒）/ pageSize=0（拒）/ 100（过）/ 101（400+`1000C0001`，T4）/ 深翻页 offset 逼近万级末页（基准场景⑤） |
| 关键词 | 含 `%` / `_` 通配转义（既有 ESCAPE 口径回归）/ 零命中（基准场景④）/ 前缀匹配（联调口径复验） |
| 造数 | space 段 990001~990004 之外零插入；cleanup 后 SELECT 核验 0 行；资源:产品 = 1:1 对齐（逻辑引用不悬空） |
| 状态 | CANCELLED（T2）/ UNAVAILABLE（T1）/ LISTED（基准常态）三分支互斥不混淆 |

## 8. 前置检查项（编码开工第一动作）

1. 冷启动复述 + 本文件契约锁定（Q0~Q5 确认留痕）；
2. 环境核验：Docker 三容器存活 + 8080/8083/8084/5173 探针 + 工作树 clean + 分支基点（按 Q0 裁决执行：若 Q0-A 含合并指示 → 先按 3.3.5 先例合并 3.3.6 入 main + 台账计数〔L1-3 24/73 → 25/73、V1.0 53/136 → 54/136〕+ 推送 + 树一致性核验，再开新分支）；
3. 基线复跑：`mvn test`（186± 例实测）+ 前端全量 vitest（29 files）取开工基线数；
4. ADR-018 coverage 段核读（catalog 归档档位锁定，写入日志）；
5. `scripts/benchmark/` 目录现状核验（应为空/不存在）+ `db/migration` 零改动基线记录。
