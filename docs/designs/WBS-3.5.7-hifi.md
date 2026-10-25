# WBS-3.5.7 高保真设计（hifi）：交付模块测试与集成

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（编码契约，已确认）**——2026-10-25 编排师一次确认 Q1~Q5 全采建议口径 A + D1 不拆分，确认记录落本文件末节 |
| 日期 | 2026-10-25 |
| 关联 | 任务卡 `docs/tasks/WBS-3.5.7-交付模块测试与集成-2026-10-25.md`；lofi `WBS-3.5.7-lofi.md`（Q1~Q5 详述在彼）；规格 C-5.1-5.2 V1.2；剧本 C-5.1 V1.2 / C-5.2 V1.3 |
| 性质 | 非界面测试与集成包；业务代码零改动（Q4-B 获裁除外）；只测量不开门禁 |

## 1. 达标口径与工具形态（Q2-A 展开）

- **判定式**：`delivery-service` 行覆盖 **≥70%**（ADR-018 overall 档现行口径；delivery/gateway 不在 `gates-config.json` V1.12 `coreModules` 属设计现状，**档位修正归门禁增强卡**）；
- **工具形态**（沿 3.2.7/3.3.7/3.4.8 先例，零 pom 变更）：
  ```
  mvn -pl services/delivery-service org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report
  ```
  jacoco agent 仅临时落 `target/`；读数 `target/site/jacoco/jacoco.csv`（LINE_COVERAGE = COVERED/TOTAL）；gateway-service 同款命令同批实测留痕（只留痕不设判定线）；
- **配套口径**：用例数只增不减（基线 delivery 194 / gateway 35 / 前端 548）+ 全仓 `run-gates.ps1` GREEN + ConfigSha256 零改动核验；
- **本卡零门禁改动声明**：不改 `gates-config.json` / `run-gates.ps1` / CI workflow / 任何 pom（红线 3）。

## 2. 映射骨架（行为级；立卡盘点已产出方法级全量映射存档，编码会话逐条勾稽回填任务卡 §三实测例数）

| 行为 | 验收标准数 | 锚点主干（类：例数） | 判定 |
| --- | --- | --- | --- |
| 1 交付准入与凭据 | 6 | DeliveryAdmissionIntegrationTest：21（幂等 5 例含并发同键/异 body 同键/缺键拒、形态不符、未生效、代发拒、防枚举） | 齐备 |
| 2 API 交付网关链 | 5 | gateway-service 7 类 35 例（GatewayDeliveryChainIntegrationTest：放行计量 1:1 对账 / 越界拒上游零触达 / 白名单 / 防枚举同形 / 失效合约透传）+ delivery 门面拒面 | 齐备（2.3 绕过网关 = §6-4 链路外不承诺口径性收口） |
| 3 文件交付 | 5 | Admission FILE 链 + FileSourceRegistryTest：14（穿越/符号链接/上限/产品匹配）+ FilePackageStoreTest：3 + Access 下载链（SM3/本体不入留痕） | 齐备 |
| 4 沙箱申请与开通 | 6（含 G1） | SandboxIntegrationTest：48（G1 双腿 apiProductFormRejected / reportAndModelFormsAccepted、配额、生命周期门、幂等开通、防枚举） | 齐备（双向） |
| 5 沙箱内使用与隔离 | 5 | Sandbox 数据面 403 族（含大小写/双编码/尾斜杠变体）+ 跨沙箱/跨合约 + 策略越界 + DockerCommandBuilderTest 7 + SandboxWorkspaceStoreTest 7 + SandboxPropertiesTest 10 + SandboxDockerE2eTest 4（条件） | 齐备 |
| 6 结果审核与导出 | 6 + G2 | SandboxResultIntegrationTest：49（状态机/G2 轮次重提交/导出四道防线/SM3 快照复核/幂等/同码同文）+ SandboxResultDockerE2eTest 1（条件，全链往返） | 齐备（双向） |
| 7 访问边界与治理例外 | 5 | Access/ActionLog/Audit（治理留痕含 G8 四端点、同码同文、零本体、越权拒留痕）+ 前端 DeliverySourceGuard：10 | 齐备 |
| §4-6/Q7 对账协同 | — | 链内已有零散断言（gateway meteringReconciles 1:1 / STUB checkCount / deliveries 行断言）；**跨形态收敛锚缺 → B2 本卡补建** | 部分承载 → B2 补齐 |

**五专项核验结论（立卡盘点存档）**：①双向用例四条链路面（网关 API 链/交付发起/沙箱 check/导出面）双腿齐备；②防枚举同码同文各端点齐备；③幂等五写端点（交付发起/沙箱开通/作业/提交审核/导出）全覆盖；④对账锚部分承载（跨形态收敛锚缺 = B2）；⑤E2E 条件用例 5 例默认跳过、走查段启用实跑（沿 3.5.4/3.5.5 先例）。

## 3. 补测清单（全部落既有测试文件；红相即时归档 `build-output/w357-red-*`）

| 编号 | 落点 | 断言口径 | 先红形态 |
| --- | --- | --- | --- |
| **B2** 对账锚 | delivery 既有集成测试类（优先 DeliveryAdmissionIntegrationTest + SandboxResultIntegrationTest 各一锚方法，或单点收敛——编码会话按结构定，沿 B1 落既有类先例不新增测试基建副本） | N 次放行/成功路径（FILE 交付 + SANDBOX_EXPORT 导出）后：`deliveries` 按形态事件行数 ≡ 成功路径次数（STUB checkCount 与既有行断言信号收敛为单点锚）；拒绝/未放行路径零新增行；API 发起"记录不计数"语义显式锚定 | 反向探针（临时扰动一行 deliveries 行删除断言即红）或天然红相，归档后复绿 |
| **DB-50** | gateway 既有网关链测试类 | `DeliveryCredentialGlobalFilter` 非法 UTF-8 负向子路径 1~2 例（同码同文拒绝面；顺带覆盖统一时钟 `gatewayNanoClock` 注入口径） | 反向探针或天然红相 |
| **DB-53** 逐项核对 | delivery 既有各类 | 八项负向（TOCTOU / check 空体 / 预检拒绝审计 / pageNum 非数字 / 序号上界 / realpath / null body / 空号）逐项现状判定：**未覆盖才补、已覆盖登记收口**（部分或已被 3.5.5/3.5.6 批次承载，如下载空主体拦截）；核对表随交付留痕 | 未覆盖项先红 |
| **gateway 择项**（3.5.2 补登记 15） | gateway 既有测试类 | T3 后半：审计 FAILURE 事件字段断言补齐；T10：空白名单起点用例。其余边界缺口（畸形 JWT/空 roles/上游 5xx 计量口径/限流并发/`@Testcontainers` 静默跳过全仓口径）维持登记留痕 | 未覆盖项先红 |

**补测总纪律**：只增不弱化；禁止空洞断言与仅 happy-path；不动测试基建共享副本（SharedMySqlContainer 既有副本纪律）；用例数只增不减。

## 4. 联调方案（Q1-A：演示环境真链；证据 `build-output/w357-lt-*`，判定表 + 探针留痕）

### 4.1 联调前基线（快照留档）

- 运行态核验：8087（含 G8 三连差分探针 404/200/403）/ 5173 / 底座六服务 / **8086 网关**（未起按走查指引拉起）/ mysql 等容器；
- 演示库 SELECT 快照留档（只读）：`deliveries`/`sandbox_task`/`sandbox_job`/`sandbox_result`/`sandbox_result_review`/`sandbox_action_log`/`action_log`/`usage_log`/`counter` 各行数 + CO000026 used_count——联调后增量对照 = **DB-64 第⑤段登记数据**。

### 4.2 判定表骨架（全 PASS 为过；步号沿剧本）

| 组 | 步骤（剧本步号） | 判定要点 |
| --- | --- | --- |
| G1 网关链补验（3.5.6 未覆盖面） | C-5.1 S1-2 ⚙ + 直调 8086 | 网关合规调用：鉴权头→限流→策略 check（真链 contract/policy）→转发→计量埋点 1:1；越界调用 1008C0020 上游零触达零计量；白名单外路径拒绝同形 |
| G2 C-5.1 关键链 | S1-1/S1-5 幂等、S2-1 文件交付（空要素拒→检索回填→约定要素过）、S2-5 SM3 校验+下载、S3-1 防枚举直调、S3-3 代发起拒 | 真链判定与剧本业务判定逐条对照；界面用词断言以"API 交付"为准（E-11） |
| G3 C-5.2 关键链 | S1-1 申请开通（G1 拒腿抽验）、S2-1 作业、S2-4 约定外权威文案逐字原样（1008C0020 三方对照）、S2-5 提交审核+防重复、S3-1 导出放行（SM3 复核+下载）、S3-5/S3-6 绕过被拒 | E2E 条件用例同批实跑（`CTDS_SANDBOX_E2E=1`，沿 3.5.5 先例） |
| G4 运营档 G8 专项抽验 | 治理详情四区 + 审核留痕入表 + 动作留痕区 | P1 修复界面回归不破 |
| G5 对账收口 | 真链只读 SELECT 对账 | `deliveries` 按形态增量 ≡ 合约 `usage_log` ALLOWED 流水 ≡ `counter.used_count` 增量（沿 3.5.5 走查对账口径；B2 自动化锚 + 本真链留痕双层） |

### 4.3 造数收口（DB-64 红线口径，与 3.4.8 的显式差异）

- **严禁**对演示库跑造数/清理脚本；库内接触面 = 只读 SELECT + 正常业务流（UI/API/curl 走业务端点）；
- 联调新增留置数据**并入 DB-64 第⑤段登记**（3.5.7 联调批：以 4.1 快照对照增量逐表列数），归下次真机演示前人工处置；
- **沙箱容器清场**（容器非演示库数据，沿 3.5.6 清场口径：UI 关闭/到期回收 → 容器 0 → RUNNING=0 复验）；
- 审计 JSONL 增量同样登记口径（文件留置，不清）。

## 5. 评估留痕件清单（零实施——结论随编码会话产出并落交付说明）

| 件 | 对象 | 产出物 |
| --- | --- | --- |
| ① | 受控执行端点（contract `usage-executions`）退役评估——ADR-021 §2.10 触发条件"C-5.x 上线"已成立 | 评估结论留痕（倾向"维持至 3.7.5 计量落库后再退役"，按演示/剧本依赖实证定稿）+ 复核清单更新 |
| ② | ADR-025 §6 演进债 4 项（到期翻转单点化/校验链提取/幂等键空间分域/DB 级异常统一 FAILURE 审计面） | 逐项评估结论（实施归下次 delivery 重构批次/common 沉淀卡） |
| ③ | DB-54 加密与持久化口径三件（GCM AAD/包级 SM3/fsync） | 评估留痕（属实现口径固化，归下次 delivery 实现改动批次） |
| ④ | DB-56~62 再排期建议（时限多已逾期） | 逐项归属批次建议表（DB-57/59/61→部署收口、DB-60→common 沉淀卡、DB-56/62→重构批次等），避免悬空 |
| ⑤ | DB-63/66 错误码分层展示裁决建议 | 建议维持现状（fail-closed 正确分层 + 最小泄露 + E-5 fail-closed 实证）；编排师裁决维持即双条关闭（台账随批更新） |
| ⑥ | DB-65 hifi §5 快照不一致拒绝文案勘正 | 纯文档一句勘正（与已确认状态机口径对齐）+ 台账关闭留痕 |
| ⑦ | 性能豁免留痕 | 交付链正式压测归 4.2.3（ADR-022 §2.9）、计量流水归 3.7.5；本卡零压测留痕（沿 3.4.8 Q5-A） |
| ⑧ | 台账计数残段补记 | L1-3 39/73→40/73、V1.0 68/136→69/136（3.5.6 +1 落账，grep 实测为准）+ DB-64 行补 ④ 段 + 关闭统计刷新 |

## 6. 交付门禁（命令与预期；全部 GREEN 才可提交评审）

| 检查 | 命令 | 预期 |
| --- | --- | --- |
| 后端 delivery | `mvn -pl services/delivery-service test` | ≥194 例 0F/0E（只增不减） |
| 后端 gateway | `mvn -pl services/gateway-service test` | ≥35 例 0F/0E（只增不减） |
| checkstyle | 两模块 `mvn -pl … checkstyle:check`（`-Dmaven.multiModuleProjectDirectory` 指仓库根） | 0 违规 |
| 覆盖率 | jacoco 命令行注入两模块（§1 形态） | delivery ≥70% 达标数字 + gateway 留痕数字 |
| 交付态 | `mvn -pl services/delivery-service -am package -DskipTests` | BUILD SUCCESS（联调 jar 重建按需，拆包核验沿先例） |
| 前端 | `npm run test` / typecheck / lint / build | ≥548 例全过 + 三检 0 错（Q4-B 获裁则含 F-W1 修复后复跑） |
| 全仓门禁 | `run-gates.ps1` | GREEN（ConfigSha256 零改动逐字核验） |
| E2E | `CTDS_SANDBOX_E2E=1` 实跑 | 5 例全绿 + 容器零残留 |

## 7. 边界值与风险

1. **跨库自动化对账不做**（诚实边界）：合约 `usage_log`/`counter` 在 contract 域库，delivery 测试以 StubDependencyServer 承载合约依赖——跨库连接属新测试基建且超模块边界；跨域对账由 B2（模块内收敛锚）+ 联调真链只读 SELECT 留痕（G5）双层承载，本体转移 3.7.x 登记（沿 3.4.8 B1 同款边界声明）。
2. **E2E 5 例默认跳过**：门禁内不计入；联调段显式启用实跑留痕；容器清场收口。
3. **演示库只读红线**：SELECT 只读；一切库写仅经正常业务流；误触脚本即停（红线 1）。
4. **8086 网关链依赖**：contract/policy 链须在运；启动失败/环境不一致 → 停止上报，不擅自修环境配置（红线 4）。
5. **Windows 环境坑**：jar 文件锁/端口占用→"停→验→建→启→复验"四步；checkstyle 单模块参数；npm 后台假退出 netstat/curl 验证；中文日志文件 grep 假阴性→PowerShell Select-String。
6. **数字口径**：台账计数残段、DB-64 ⑤段、覆盖率数字一律以命令/grep 实测为准，禁记忆值。
7. **界面断言用词**："API 交付"（E-11 裁决后新词）；错误文案断言与 `ContractErrorCodes.java` 权威源三方对照（F3.2 口径）。

## 8. 前置检查项（编码会话开工前逐项核验，全过才动工）

1. git 状态干净 + 分支 `feat/WBS-3.5.7-交付模块测试与集成`（自 `cbd9f9a`）+ 两设计文件末节**确认记录在案**（V1.0）；
2. 基线复验：delivery 194 / gateway 35 / 前端 548（mvn/npm 实测非记忆值）；
3. 运行态：8087 含 G8 探针三连通过 / 5173 / 六服务 / 8086（未起按指引拉起）/ mysql 等容器在跑；
4. 演示库 SELECT 快照留档（联调前基线，§4.1 九表 + used_count）；
5. jacoco 命令行形态单模块干跑预检（prepare-agent + report 跑通出 csv）；
6. gates-config V1.12 现状 + ConfigSha256 基线核验（零改动声明前提）；
7. 红相/证据目录可写：`build-output/w357-red-*` / `build-output/w357-lt-*`（gitignored）；
8. 纪律声明在位（DB-64 禁脚本 / 只测量不开门禁 / 业务代码零改动）。

---

## V1.0 确认记录

- **确认时间 / 方式**：2026-10-25 18:5x~19:0x，立卡会话内编排师经由会话选项明确回复"**全采建议口径 A + D1 不拆分（推荐）**"——Q1~Q5 采行口径详见 lofi 末节确认记录（本文件 §1~§8 即按确认口径落定的编码契约，无修订）。
- **生效**：本文件 = **编码契约即时生效**；实现与设计逐条一致为 §五 自检单第 1 条的核验对象；确认批随本批提交并推送分支（沿 3.4.7/3.3.7 确认批先例）；编码段归新会话冷启动（测试先行，第一动作 = §8 前置检查八项逐项实测留痕）。
