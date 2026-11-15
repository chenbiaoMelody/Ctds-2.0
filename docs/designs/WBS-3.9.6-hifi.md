# WBS-3.9.6 高保真设计：系统管理测试与集成（V0.9，2026-11-15）

> 状态:V0.9 待编排师一次确认(确认后转 V1.0 = 编码契约)。接口契约表 / 边界值与异常行为 / T 编号 / 前置检查 / §9 勘误位。实现与本文件不一致 = 打回项;前置检查发现失真按 §9 E-勘误位回填,不得私自补需求。

## §1 测试面契约(T1~T7)

### T1 RBAC 授予/收回写面行为测试(新增类,evidence-service)

- **载体**:`RbacGovernanceBehaviorIntegrationTest`(新,沿 `DomainGovernanceIntegrationTest` 范式:`@SpringBootTest(MOCK)` + `@AutoConfigureMockMvc` + Testcontainers MySQL(SharedMySqlContainer,disabledWithoutDocker)+ `ctds.auth.rbac.source=jdbc` 真跑 Flyway V1~V5)。
- **契约**(grant/revoke 挂 `connector.review`〔platform.secadmin〕;变更人 = `X-Ctds-Subject` 头;幂等键三元组 `{role, permission, changeOrderNo}`):

| # | 用例 | 请求 | 断言要点 |
| --- | --- | --- | --- |
| 1 | secadmin 授予成功 | `POST /api/v1/rbac/permissions/grant`,`X-Ctds-Roles: platform.secadmin` + 变更人头,`{role, permission, changeOrderNo}` | 200 + 变更号出参(3.9.5 E-2 口径);库表 `role_permission` 新行生效;`role_permission_change_log` 新行且八列齐备(changeNo/changeOrderNo/changedBy/changedAt/roleCode/permissionPoint/beforeValue/afterValue) |
| 2 | secadmin 收回成功 | `POST .../revoke` 同三元组 | 200;库表行消失;留痕行 afterValue = 原值 |
| 3 | 重复授予幂等 | 同 #1 原样重发 | 不产生第二条 `role_permission` 行 / 第二条留痕行(幂等键三元组);响应不报错 |
| 4 | 变更单号缺失 | grant 缺 `changeOrderNo` | 400(校验拒绝),库零写入(留痕行零新增) |
| 5 | roles 清单可读 | `GET /api/v1/rbac/roles`(sysadmin,挂 `connector.health.read`) | 200 + 角色→权限点集合(V3 15 角色 + V4/V5 点) |
| 6 | changes 留痕分页可读 | `GET /api/v1/rbac/changes`(audadmin,挂 `evidence.governance`) | 200 + 分页含 #1/#2 产生的行 |

- **达标挂钩**:本类 + 既有用例跑完后,jacoco 复测 `RbacPermissionService` / `RbacGovernanceController` / `JdbcRbacStore` 三类行覆盖 **≥80%**(基线 8.1% / 33.3% / 8.1%;若结构原因达不到,evidence 实测值 + 差距原因进 §9 勘误,如实登记不虚报)。

### T2 RBAC 三员矩阵代码化(新增类或扩展,evidence-service)

- **载体**:沿 `common/auth` `RbacThreeAdminMatrixTest` 扩展范式在 evidence 侧落矩阵(基座同 T1,可同类分层或独立类,编码段定)。
- **契约**:RBAC 4 端点 × {platform.sysadmin, platform.secadmin, platform.audadmin, 未认证} = **16 格轻断言矩阵**(仅状态码 200/403/401):grant+revoke 仅 secadmin 200 其余 403;roles 仅 sysadmin 200;changes 仅 audadmin 200;未认证全 401。
- **与真机留痕的关系**:3.9.5 真机 14 格矩阵(`build-output/w395-precheck-data/precheck-summary.md:66-79`)为留痕源;代码矩阵 16 格口径差(补未认证行/格定义差)在前置检查 P7 核对,差异进 §9 勘误。

### T3 V5 seed 专项断言(新增用例)

- **载体**:文件级断言(沿 `RbacGovernanceContractTest` 范式,断言 `V5__grant_monitor_permissions.sql` 存在且授 `platform.monitor.read` + `platform.alert.handle` 给 `platform.sysadmin` + 含 change_log 留痕行)+ 库级断言(T1 基座真迁移后查 `role_permission` 两点存在 + 留痕行落库)。

### T4 B7-AC3 演算脚本(新增,build-output gitignored + docs 留痕 1 份)

- **载体**:`build-output/w396-演算/w396-algo.ps1`(PowerShell,**ASCII-only**,沿 3.8.6 探针单一工具链先例)。
- **契约**:
  1. 输入 = 演示链各服务 `/actuator/prometheus`(真实暴露面,12 服务统一,不依赖监控栈服务器);
  2. 两次采样 `http_server_requests_seconds_count`(按 `status` 标签),间隔 ≥60s;
  3. 演算 = 逐服务 `5xx 增量 / 总增量`(手工等价于规格 :156 PromQL `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` 的 5m 窗口均值口径,算式映射说明随留档);
  4. 留档 = 算式(规格 PromQL 原文 + 手工等价式)+ 每服务两次取值 + 演算结果 + 结论("当前占比 <5%,规则未触发;算式演算证明规则可判定"——演算非真机触发如实标注);
  5. 判定 = 全服务采样成功且演算完成且留档写入 → exit 0;任一失败 exit 1;
  6. docs 留痕 1 份:`docs/designs/WBS-3.9.6-演算留档.md`(算式 + 取值 + 时点 + 脚本路径,可版本管理)。

### T5 copySilenceId 独立用例(前端,AlertView.spec 追加)

- **契约**:静默成功响应回显含 silenceId → 复制动作独立断言(stub `navigator.clipboard`,断言 `writeText` 收到 silenceId 值 + 成功提示);既有"silenceId 回显文本"主链路断言不动。

### T6 MonitorView spanMethod 合并结构用例(前端,MonitorView.spec 追加)

- **契约**:四区块读数表 spanMethod 合并结构独立断言(合并跨度/合并条件——以实现实测结构为准,断言零业务语义变更;具体结构前置检查时从实现回读后落定)。

### T7 RbacView 空值拦截用例(前端,RbacView.spec 追加)

- **契约**:角色权限点为空值/空集时的拦截文案独立断言(既有"变更单号空值零请求"主链路断言不动,本例专测角色清单空值分支文案)。

## §2 剧本维护契约(两份,须 PO 批准)

| 剧本 | 版本 | 变更面 | 零变更承诺 |
| --- | --- | --- | --- |
| C-10.1 | V1.5→V1.6 | ① 追加《自动化覆盖对照》一节(lofi §3 底表裁剪落剧本);② S1-2 承载勘误:操作列"平台业务配置动作"改为"告警处置动作"(Q4-A,系统管理员唯一在线管理写面,B8-AC1 留痕四要素),操作路径表 :67 同步 + 标注"平台业务配置 V1.0 无在线写端点" | 步骤编号零变更;预期结果"动作成功 + 管理动作留痕(B2-1 正向)"语义零变更 |
| C-10.2 | V1.3→V1.4 | ① 追加《自动化覆盖对照》一节;② S2-5 承载注记:演算腿承载 = T4 脚本 + 演算留档(路径引用),真机触发腿维持演示期构造 | 步骤编号与业务判定零变更 |

## §3 真机联调走查契约(W7)

- **起线**:`build-output/w395-demo/w395-demo-start.ps1`(复用,196 行,11 后端 + 前端 5173,evidence 带 jdbc 运行期变量);走查前按 DB-64/DB-108 清单核对留置数据(只核对留痕,清理与否沿其自身口径)。
- **走查面**(两剧本全步骤,"监控栈未部署"诚实口径):
  1. C-10.1:S1 全幕(清单基线 → 处置动作承载〔Q4 勘误后口径〕→ 审计越权 400 → 授予越权 403 → secadmin 授予/收回 → 留痕查询)+ S2 全幕(部署域读面 + 跨空间)+ S3 读面/审计三证;走查中同步采集 S1-13 两两比对数据源;
  2. C-10.2:S1 全幕(监控读数 **503 错误态诚实呈现**〔E-1 口径〕/ 暴露面核对 / 未接入标注 / 三态列〔connector 直读面〕/ 越权 403)+ S2 全幕(告警列表 **503 诚实呈现** / 处置动作 **Alertmanager 不可达 503 无副作用**〔预期行为面〕/ 写面双向 / 观察点核对);S2-2~S2-4(kubectl scale 真机触发)演示期构造不属本包(如实标注);
  3. 走查留痕 = build-output gitignored 判定表(步号 + PASS/FAIL + 实测数字),fail 即停报告。
- **与 T4 衔接**:走查段内跑演算脚本(T4 需服务在跑)。

## §4 达标口径测量方法(Q2-A 三层)

| 层 | 口径 | 测量 |
| --- | --- | --- |
| a | 门禁 GREEN 不劣化 | 全仓 `run-gates.ps1` 单发;coverage overall ≥91.9%(基线)且 mutation did ≥69.34%(基线)——报告数字对基线比对留痕 |
| b | RBAC 写面三类 ≥80% | T1 落地后 `mvn -pl services/evidence-service test` + jacoco 复测三类行覆盖(同口径 jacoco.xml class 级 counter);达不到如实登记差距原因(§9) |
| c | AC 对照表成立 | lofi §3 底表 → 剧本对照节落盘(PO 批准)——每条 AC 有明确承载方式并留档 |

## §5 边界值与异常行为(关键 6 条)

| # | 边界/异常 | 预期 |
| --- | --- | --- |
| 1 | 变更单号缺失/空串 | 400 校验拒绝,库零写入(T1#4) |
| 2 | 重复授予同三元组 | 幂等:库不新增行(T1#3);重复**收回**同口径 |
| 3 | 未认证访问 RBAC 任意端点 | 401(T2 未认证行) |
| 4 | 演算脚本采样失败(服务未起/指标缺失) | exit 1,不留半份留档(§1 T4#5) |
| 5 | 演算 5xx 占比 ≥5%(极端) | 结论如实记录"达到触发条件,规则未接真流量";不掩盖不伪造 |
| 6 | DB-129 现状行为(自我授予) | 前置检查真机实测**只留痕不改语义**(P9);CI 用例不含该格(不固化缺陷行为);实测结果登记 DB-129 复核 |

## §6 体量预算

测试 ≈700(T1 350 + T2 150 + T3 60 + T5~T7 150)+ 演算脚本与留档 ≈120 + 剧本两份 ≈90 + 任务卡/台账/日志文档;总预估 **900~1300 行**(D1 不拆分,登记制豁免沿先例)。

## §7 前置检查(P1~P9,编码段第一动作,逐项实测留痕 build-output gitignored)

| # | 检查 | 通过判据 / 失真处置 |
| --- | --- | --- |
| P1 | evidence 全量基线复跑 | 129 例 0 失败;失真 → 停查 |
| P2 | common/auth 全量基线 | 全绿(RbacThreeAdminMatrixTest 6 例在位) |
| P3 | frontend 三关基线 | typecheck/lint/test 全绿(77 spec / 1011 用例口径) |
| P4 | jacoco 三类基线复测 | 8.1% / 33.3% / 8.1%(class 级);漂移 → §9 勘误 |
| P5 | RBAC 端点真机契约复核 | 演示链 jdbc 起线,实发 grant/revoke:幂等键三元组行为、变更号出参、change_log 落库八列——与 §1 T1 契约逐项比对,失真 → §9 勘误 |
| P6 | 演算源复核 | 各服务 `/actuator/prometheus` 可达 + `http_server_requests_seconds_count{status=...}` 指标存在;缺失 → §9 勘误(T4 源调整) |
| P7 | 矩阵口径核对 | 3.9.5 真机 14 格原文 vs 本卡 16 格;差异 → §9 勘误登记口径 |
| P8 | 演示链起线 + DB-64/108 清单核对 | 起线脚本可用;留置数据清单核对留痕 |
| P9 | DB-129 现状行为实测(真机,只留痕) | secadmin 自我授予实际行为(成功/被拒)实测留痕 → DB-129 复核登记;**不改语义,不进 CI** |

## §8 边界与红线(编码段必读)

1. `src/main` 与前端业务文件零改动(前端仅 spec 测试文件)——`git diff` 实证随提交留痕;
2. `scripts/gates/gates-config.json` 零改动(ConfigSha256 与基线 `adc3f98b…5cfb` 逐字一致);
3. 演示库只新增单据(沿 DB-64);走查 fail 即停报告,不跳步;
4. 测试暴露真实缺陷(含 DB-129 现状)→ 红相 + 报告走缺陷流程,不在本包改业务逻辑;
5. 剧本两份改动严格限 §2 契约面(对照节 + S1-2 勘误 + S2-5 注记),其余零改动,PO 批准前不视为生效;
6. PowerShell 脚本一律 ASCII-only(编码坑先例)。

## §9 设计勘误位(前置检查回填,不私自补需求)

| 编号 | 勘误 | 状态 |
| --- | --- | --- |
| E-1 | (预留) | 未启用 |
