# WBS-3.2.7 逻辑空间测试与集成 · 高保真设计（编码契约）

| 字段 | 内容 |
| --- | --- |
| 任务卡 | `docs/tasks/WBS-3.2.7-逻辑空间测试与集成-2026-09-29.md` |
| 前置 | 3.2.1~3.2.6 + DB-28 全部关闭（DB-28 合并 `7b95d44`）；本设计**经编排师一次确认后生效**（lofi §3 Q1~Q5 + D1） |
| 日期 | 2026-09-29 |

## 1. 达标口径与工具形态

| 项 | 口径 |
| --- | --- |
| 达标线 | space-service 行覆盖 **≥70%**（整体线；space 非 AGENTS.md §4 核心模块，不适用 80% 核心线）；**用例数只增不减**（基线 145 例） |
| 工具 | jacoco 0.8.12 **命令行临时注入**（沿 WBS-3.1.12 先例：`mvn -B -ntp -pl services/space-service -Djacoco.skip=false test` 前用 `-javaagent` 方式或 jacoco CLI 注入，**不改 pom.xml**；agent jar 若需下载仅落 `target/precheck/`，不入库、不写入 `dependencies.md`） |
| 产物 | ① 实测行覆盖数字（分模块 space-service）+ 判定结论；② 若 <70% → 如实登记缺口类清单 + 补救计划（本卡内补测补救，补救不动业务代码） |
| 变异 | 本卡**不测变异**（沿 lofi §2③：space 域零先例；变异接入属 2.2.8/DB-06 职权，登记顺延） |
| 门禁边界 | **只测量不开门禁**：不改 `scripts/gates/gates-config.json`、不改 `run-gates.ps1`（红线 3）；全仓门禁复跑仅作回归证据 |

## 2. 盘点方法与映射表

**方法**：规格 C-2.1~2.3 行为 1~8 每条验收标准 → 既有测试锚点（145 例全清单）→ 缺口 → 补测项。立卡前已由只读子智能体完成全量盘点（逐类逐方法），结论：

### 2.1 行为 × 覆盖状态对照

| 规格行为 | 覆盖状态 | 主要承载 |
| --- | --- | --- |
| 行为 1 创建准入 | 覆盖充分 | Lifecycle T1~T5、T16~T17、T19~T23 + Migration uk 三例 |
| 行为 2 生命周期 | 覆盖充分 | Lifecycle T6~T11、T20~T23；Membership T20 留痕一致性 |
| 行为 3 准入申请 | 覆盖充分 | Membership T1~T5、T19、T22、T24 |
| 行为 4 成员与角色 | 覆盖充分 | Membership T6~T11、T16、T9b；SpaceAccessGuardPermissionMatrixTest 6 例 |
| 行为 5 退出移除 | 覆盖充分 | Membership T12~T15 |
| 行为 6 逻辑隔离 | **三缺口（本节 2.2）** | 第三方/公开可见性/404 同形已覆盖；跨空间字面用例缺、拒绝留痕"何时"未断言 |
| 行为 7 策略继承覆盖 | 覆盖充分 | Policy 15 例 + EffectivePolicyResolver/PolicyCatalog 16 例 |
| 行为 8 汇总待办 | 覆盖（端点级） | Membership T22 myAdmissions；前端 MyAdmissionsView 组件测试 |

### 2.2 缺口与处置

| # | 缺口 | 证据 | 处置 |
| --- | --- | --- | --- |
| G1 | **跨空间隔离字面用例缺**：现有"非成员"全为无身份第三方（outsider-t10 @Membership L448 / outsider-t18 @L702 / prober-t3），无"A 是甲空间成员、访问乙空间资源"用例 | 子智能体盘点 §3 | 补测 **T1** |
| G2 | 拒绝留痕**"何时（created_at）"未断言**（仅创建留痕断 createdAt） | Membership L456-460 / L705-709 无时间断言 | 补测 **T2**（断言强化） |
| G3 | **FROZEN 态重放字面用例缺**（DB-28 卡 §6.6 移交） | 重放用例载体仅 ACTIVE（T21）/ DISSOLVED（T20/T22） | 补测 **T3** |
| G4 | **T22 B 路径"无新 CREATE 留痕"断言缺**（DB-28 卡 §6.6 移交） | Lifecycle T22 仅断言重放回包与行数 | 补测 **T4**（断言强化） |
| G5 | **要素非法 + 名称锁命中优先序组合用例缺**（DB-28 卡 §6.6 移交） | Lifecycle T5 要素非法不含锁名组合 | 补测 **T5** |
| G6 | space-service **无 HEAD 行覆盖基线** | 在树 jacoco 报告陈旧（约 94.2%、缺 3.2.4/3.2.5 类） | §4 覆盖率实测 |
| G7 | subject 资格门**真链零自动验证**（集成测试全 mock） | 5 集成类 `@MockitoBean SubjectAdmissionPort` | §5 联调 |

**无锚点/条件性标准处置**：无（对照 3.1.12 §2.2 口径——145 例逐条盘点后无"完全无锚点"的验收标准）。

## 3. 后端补测清单（测试先行，全部带红锚）

| 编号 | 用例 | 落点 | 断言要点 | 红锚（删之必红） |
| --- | --- | --- | --- | --- |
| **T1** | `crossSpaceMemberAccessingOtherSpaceIsDenied` | `SpaceMembershipIntegrationTest`（沿用现有双空间载体） | 主体是**空间甲的有效成员**，直接请求**空间乙**的 members/admissions 端点 → 403 `1006C0007` + 恰 1 条 ACCESS_DENIED 留痕/端点 + 留痕**四要素含 createdAt**（谁/何时/目标/结果） | 删除服务端隔离拒绝（放行跨空间访问）→ 用例必红 |
| **T2** | 既有用例断言强化（`nonMemberDirectApiAccessIsDeniedServerSide` @L448、`nonMemberPrivateDetailKeeps404ShapeWithAccessDeniedLog` @L700、ActionLog T1） | 同上三处 | 每条拒绝留痕补 `createdAt` 非空 + ISO 秒级可解析断言（复用 `IsoSecondTimestamp` 先例，若 space 域无则新建支撑类） | 删除留痕写入时间戳赋值 → 必红 |
| **T3** | `frozenSpaceIdempotentReplayKeepsFirstResult` | `SpaceLifecycleIntegrationTest`（沿用 T21 载体结构） | FROZEN 态同键重放 → **仍返回首次结果**（空间未解散、名称未锁；沿 DB-28 方案 A 语义：仅 DISSOLVED/锁名才拒）+ 空间数恒 1 + 无新 CREATE 留痕 | 把"命中后复核"错改为遇 FROZEN 即拒 → 必红 |
| **T4** | 既有 T22 断言强化 | `SpaceLifecycleIntegrationTest` T22 | B 路径补"**无新 CREATE 留痕**"断言（重放前后 CREATE 留痕恒 1） | 删除该断言后人为造"重放误写留痕" → 必红 |
| **T5** | `invalidElementsTakePriorityOverLockedName` | `SpaceLifecycleIntegrationTest`（沿用 T5 载体 + 已锁名称） | 创建请求**同时**带非法要素（如名称超长）**且**命中已锁名称 → 断言 **400 参数错先行**（控制器参数门先于服务层锁判定）、无新空间、无新留痕（留痕归属不混淆） | 调换门序使锁名 409 先行 → 必红 |

**补测边界**：以上 5 项**全部落既有测试类**，不新建平行测试世界；**只增不弱化**；T1/T3 为新用例（预计 +2 例），T2/T4 为断言强化（不增例数），T5 为新用例（+1 例）——预计 145 → **148 例**。

## 4. 覆盖率实测方案

1. 前置：Docker 在线（集成用例真实执行、无跳过）；`mvn -B -ntp -pl services/space-service test` 基线全绿（145/145）后执行；
2. 注入：jacoco CLI（`target/precheck/jacoco/` 临时落盘）对 `mvn test` 加 `-javaagent`；报告输出 `target/site/jacoco/`（gitignored，不入库）；
3. 读数：从 `jacoco.csv` 聚合 space-service **行覆盖分母/分子**，判定 ≥70%；
4. 留痕：实测数字 + 判定写入任务卡 §三/§五 与日志；**不达标** → 缺口类清单 + 本卡内补救（补测，不动业务代码），仍不达标如实登记台账（不粉饰）。

## 5. 联调方案（Q1 = A 口径）

**环境**：Docker 三容器（sc-mysql / sc-redis / sc-minio）在线；subject 8080 + space 8083 以交付态构件启动（`CTDS_SPACE_SUBJECT_BASEURL=http://localhost:8080` 真链，沿 DB-28 复验口径；口令经编排师授权由容器内变量导入，不打印不落地）。

| 链路 | 内容 | 通过判据 |
| --- | --- | --- |
| 剧本 C-2.1 关键链 | 创建 → 同键重放（S1-7）→ 配置变更 → 冻结/恢复 → 解散（二次确认）→ 同名重建拒（S3-6） | 业务码/状态/库内三面一致（沿 DB-28 §6.4 口径） |
| 剧本 C-2.2 关键链 | 申请→审批（含拒绝留理由）/邀请→确认、member 越权六动作逐拒、退出/移除立即失效 | 三幕关键步骤通过判据逐条满足 |
| 剧本 C-2.3 关键链 | 跨空间隔离双向拒（A 成员访问 B 资源——即 T1 场景真机版）、红线放宽拒/收紧过、可见≠可访问 | 拒绝留痕四要素 + 红线 1006C0013；**运营方治理查看的 visit 留痕复验归 DB-29**，本卡只验"允许访问"面 |
| subject 资格门真链 | 以未入驻/已过期主体发起创建与准入动作 | NOT_ADMITTED / UNAVAILABLE → 403 `1006C0001` 统一文案真链生效（库内零单据） |

**留痕**：联调新增演示数据按 lofi Q5（裁定②口径）留置登记；原始证据（截图/文本）存 `frontend/test-results/walkthrough/`（gitignored），文本结论入日志。

## 6. 交付门禁与依赖声明

| 项 | 内容 |
| --- | --- |
| 本地门禁 | `mvn -B -ntp -pl services/space-service test`（预期 148/148）+ `mvn -B -ntp -pl services/space-service checkstyle:check`（0 违规） |
| 交付态 | 沿 3.2.6 口径：`mvn -B -ntp -pl services/space-service package` BUILD SUCCESS + 前端 `npm run build` 不受影响（本卡零前端改动） |
| 全仓门禁 | `powershell -File scripts/gates/run-gates.ps1` GREEN（只复跑、不改配置） |
| 依赖 | **零新增第三方依赖**（jacoco agent 仅临时落 `target/precheck/`） |
| 剧本 | 本卡不改业务行为与界面文案 → **剧本无需更新**（判定口径不变） |

## 7. 边界值与异常行为

- T1：跨空间访问覆盖 members/admissions 两个读端点 + 留痕四要素逐字段；已覆盖 effective/action-logs 读面（Membership T18 / ActionLog T1）不重复；
- T3：FROZEN 重放的对照面 = T20（DISSOLVED 拒）/ T21（ACTIVE 放行），三态齐备即状态机全语义钉死；
- T5：组合优先级口径 = 参数校验门（400）先于业务锁门（409）——与既有逐字段用例（T5）同序，不断言"锁名必须报 409"的相反分支；
- 联调资格真链：UNAVAILABLE 不冒充资格拒绝（沿 Lifecycle T2 文案口径）。

## 8. 实施前置检查项（编码会话第一步，逐项实测留痕）

1. 仓库状态：`main == origin/main`（含 DB-28 合并 `7b95d44`）、工作树干净、分支 `feat/WBS-3.2.7-逻辑空间测试与集成` 自建自 main；
2. Docker 三容器在线（集成用例真实执行前提）；
3. 基线门禁：`mvn -B -ntp -pl services/space-service test checkstyle:check` 145/145 + 0 违规（与 DB-28 收口口径一致）；
4. 测试载体清点：T1/T3/T5 所需双空间载体、已锁名称载体与既有用例（T20~T22）同源复用；
5. jacoco CLI/agent 获取路径（本地 `.m2` 或临时下载落 `target/precheck/`）；
6. 真实环境端口核验（8080/8083 在线；若不在线按 DB-28 卡 §6.6-4 环境观察口径先重启再联调）；
7. 与 DB-29 分支并行期零冲突核验（本卡只动 `src/test`，DB-29 只动 `src/main` 治理面 + 迁移——落点前再次确认）。

## 9. 确认记录（待回填）

- 2026-09-29 立卡：lofi/hifi 落盘，Q1~Q5 + D1 **待编排师一次确认**。
- 2026-09-29 22:5x **确认到达**（编排师会话回复"**都按建议**"）：**Q1~Q5 全采建议 A + D1 = 不拆分** → 本设计（§1~§8）转**编码契约生效**，编码归同会话（分支 `feat/WBS-3.2.7-逻辑空间测试与集成`）。确认留痕位置：任务卡 §二、lofi §3.1、台账「待编排师」行、编码分支首提交。
