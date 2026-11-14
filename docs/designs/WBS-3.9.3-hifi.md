# WBS-3.9.3 高保真设计：多租户管理（部署域配置 + 版本/状态管理）— V1.0（✅ 2026-11-14 一次确认生效 = 编码契约）

- 来源：lofi V1.0（同批确认）+ 任务卡 `docs/tasks/WBS-3.9.3-多租户管理-2026-11-14.md` §二 Q1~Q8 + D1；规格 V1.2 B9（`:98-113`）+ 边界 8（`:213`）+ Q10~Q15（`:239-251`）；剧本 V1.3 S3（`:45-58`）
- 性质：✅ **2026-11-14 编排师一次确认**（会话回复"确认批准" = Q1~Q8 均采建议口径 A + D1 不拆分）；**V1.0 = 编码契约**，实现与本文不一致 = 打回项；实测不符时按 §9 E-勘误位回填（纯勘误，不补需求）

## §1 组件与文件落点

| 组件 | 模块 / 文件（新增除注明外） | 职责 |
| --- | --- | --- |
| 部署域配置属性 | `common/auth` `DomainProperties`（前缀 `ctds.domain`） | 标识/名称/版本/状态/变更登记键 + 豁免路径模式（见 §3） |
| 部署域状态过滤器（servlet） | `common/auth` `DomainStatusFilter`（`AuthAutoConfiguration` 注册，order = HIGHEST_PRECEDENCE+30，随 `ctds.auth.enabled` 挂链） | `RETIRED` 时业务请求 503 + `1013C0004`；豁免模式放行；缺省 `ACTIVE` 零行为 |
| 部署域状态过滤器（reactive） | `common/auth` `GatewayDomainStatusWebFilter`（网关侧装配，对齐 `GatewayAuthAutoConfiguration` 惯例；order 对齐 `GatewayFilterOrders` 白名单后、限流前，P5 实测定位） | 同上（统一入口锚点，CHG §3 防绕过） |
| 错误码 | `common/auth` `DomainErrorCodes` | `1013C0004` DEPLOYMENT_DOMAIN_RETIRED / `1013S0002` DEPLOYMENT_CONFIG_MISMATCH（+ MESSAGE 常量成对，沿 `RbacErrorCodes` 写法） |
| 生命周期审计事件 | `common/auth` `DomainLifecycleAuditEmitter`（`ApplicationReadyEvent` 触发，经既有 `AuditRecorder`） | 变更登记键齐备时落 `domain.lifecycle` 事件（§3）；登记不齐备且部分填写 → 启动失败（fail-closed） |
| 启动配置校验 | `evidence-service` `DomainConfigStartupValidator`（有 DataSource Bean 时生效，沿 `RbacJdbcAutoConfiguration` 双条件惯例） | 自身数据源 schema ∈ `ctds.domain.schema-list`，不一致 → 抛异常启动失败（B9-R4 fail-closed） |
| 部署域读面 | `evidence-service` `DomainGovernanceController`（`interfaces/`，`@RequirePermission("platform.domain.read")`） | `GET /api/v1/platform/domain`（契约见 §2）；每次请求计算配置校验结论 |
| 错误码映射 | `evidence-service` `EvidenceExceptionHandler` 增映射 | `1013S0002` → 503（沿 `1012S0001`→503 先例） |
| V4 迁移 | `evidence-service` `db/migration/V4__grant_platform_domain_read.sql` | `platform.domain.read` 授 `platform.sysadmin` + `role_permission_change_log` 变更登记（列清单以 P3 实测为准） |
| 部署脚本扩展 | `scripts/deploy/`（形式 P7 定：独立脚本或 `deploy.ps1` 扩展开关） | 全量 schema/服务清单一致性校验（声明 `ctds.domain.*` 值 vs 实际 datasource/k8s 清单/路由）+ 状态切换辅助输出（部署记录含前后值） |
| runbook | `deploy/runbook.md` 新增部署域操作段（§3.2，沿 §3.1 标题惯例） | 升级/退役/恢复三动作步骤 + 变更登记义务 + 回退口径 |

**不改**：`DefaultAccessControl` / `PermissionInterceptor` / `JdbcRolePermissionMapper` / `RbacJdbcAutoConfiguration` 判定与装配语义零改动；前端零改动；门禁配置零改动；既有 V1~V3 迁移零改动。

## §2 接口契约表

### GET /api/v1/platform/domain（部署域信息读面，唯一新增端点）

| 项 | 契约 |
| --- | --- |
| 权限 | `@RequirePermission("platform.domain.read")`（仅 `platform.sysadmin` 持有，V4 授予）；未认证 401 + `1000C0002`；无权限 403 + `1000C0005`（B3-2 同码同文） |
| 请求 | 无参数、无请求体（只读） |
| 成功响应 | 沿既有 `ApiResult` 包装（P2 实测字段名）；`data` 字段 = `domainId`（部署域标识）/ `domainName`（名称）/ `platformVersion`（平台版本）/ `lifecycleStatus`（`ACTIVE`｜`RETIRED`）/ `schemaList`（库 schema 清单，数组）/ `serviceList`（服务清单，数组）/ `configConsistency`（配置校验结论，见 §4）/ `scopeNote`（口径声明："V1.0 = 单实例单部署域，生命周期 = 配置版本与状态管理，不做多实例自动开通"） |
| 退役态 | 端点**不**被拦截（evidence 豁免模式含本端点）；`lifecycleStatus` = `RETIRED` 照常返回（S3-5 读面复核） |
| 错误 | `1013S0002` + 503：请求时校验结论为"配置不一致"（防御性运行期分支，正常情况下启动校验已 fail-closed 拦截） |

### 退役态业务请求（过滤器响应，非端点）

| 项 | 契约 |
| --- | --- |
| 状态码 | **503** |
| 响应体 | 沿 `ApiResult` 错误包装：`code = 1013C0004`、`message = "部署域已退役"`（B9-R6 语义原文） |
| 豁免 | §3 `permit-path-patterns` 命中 → 放行（健康读数 `/actuator/**`、evidence 审计读面、部署域读面） |

## §3 配置键表（声明式，Q3-A）

| 键 | 载体 / 缺省 | 说明 |
| --- | --- | --- |
| `ctds.domain.id` / `ctds.domain.name` | evidence 完整块；缺省不配置 | 部署域标识 / 名称（读面渲染） |
| `ctds.domain.platform-version` | 同上 | 平台版本（S3-3 升级 = 此值递增 + 滚动更新） |
| `ctds.domain.status` | 全服务可持；**缺省 `ACTIVE`** | 生命周期状态；仅 `ACTIVE`/`RETIRED` 合法，**非法值 = 启动失败**（fail-closed，不得静默当 ACTIVE） |
| `ctds.domain.schema-list` / `ctds.domain.service-list` | evidence 完整块 | 库 schema 清单（逗号分隔）/ 服务清单；evidence 启动校验与读面渲染依据 |
| `ctds.domain.action` | 部署变更时注入；缺省空 | 生命周期动作标记：`OPEN`/`UPGRADE`/`RETIRE`/`RESTORE`（审计事件"动作"字段） |
| `ctds.domain.change-order` / `ctds.domain.operator` | 部署变更时注入；缺省空 | 变更单编号 / 操作人（B9-R3 登记义务）；三者**要么全空要么全齐**，部分填写 = 启动失败 |
| `ctds.domain.retired.permit-path-patterns` | 全服务；缺省 `[/actuator/**]` | 退役态豁免路径模式（Ant 风格）；evidence 增配审计读面 + 部署域读面模式（具体值 P2/P7 定） |

- 审计事件 `domain.lifecycle`：`action` + `outcome=APPLIED`，detail = `domainId` / `action` / `changeOrder` / `operator` / `status` / `platformVersion`（时点由审计载体自带）；**前后版本与状态由部署记录承载**（三证合并满足 B9-4 五要素，Q5-A）。
- k8s 演示/部署注入沿 3.9.2 `CTDS_AUTH_RBAC_*` env 先例（`CTDS_DOMAIN_STATUS` 等）；本卡**不预注入**任何清单改动（缺省 ACTIVE 零行为），退役/升级按 runbook 操作段执行。

## §4 边界值与异常行为

| # | 场景 | 行为 |
| --- | --- | --- |
| 1 | `ctds.domain.status` 未配置 / `ACTIVE` | 过滤器零行为（既有链零影响） |
| 2 | `status=RETIRED` + 非豁免路径 | 503 + `1013C0004` + "部署域已退役" |
| 3 | `status=RETIRED` + 豁免路径（actuator / 审计读面 / 部署域读面） | 放行 |
| 4 | `status=RETIRED` + 恢复 `ACTIVE`（配置翻回 + 滚动更新） | 业务自动恢复，无需代码动作 |
| 5 | `status` 非法值（如 `PAUSED`） | **启动失败**（fail-closed，不静默降级） |
| 6 | evidence `schema-list` 已配 + 数据源 schema ∉ 清单 | **启动失败**（B9-R4 fail-closed；S3-4 演示路径：改清单→重启→失败→还原→复验恢复） |
| 7 | evidence 无数据源（默认 profile）/ 未配 schema-list | 校验跳过；读面 `configConsistency` = `NOT_APPLICABLE`（诚实标注，不显示"正常"） |
| 8 | 校验通过 | 读面 `configConsistency` = `CONSISTENT` |
| 9 | `action`/`change-order`/`operator` 部分填写 | **启动失败**（登记不齐备 = fail-closed） |
| 10 | 三者全齐 | 启动时落 `domain.lifecycle` 审计事件（JSONL 或 metering-db sink，沿 `ctds.audit.*` 既有配置） |
| 11 | 业务角色（无 `platform.domain.read`）访问读面 | 403 + `1000C0005`（B3-2 同码同文） |
| 12 | 未认证访问读面 | 401 + `1000C0002`（ADR-005 :37） |
| 13 | 全仓结构 | 无 `tenant_id` / 租户表 / 在线租户生命周期 API（T8 结构断言守卫） |

## §5 测试清单（先红后绿；映射任务卡 §三）

| 用例 | 断言要点 | 红/**绿** |
| --- | --- | --- |
| T1 读面正向 | sysadmin 六字段 + `ACTIVE` + `CONSISTENT` + scopeNote | 红（端点不存在）→ 绿 |
| T2 读面负向 | 业务角色 403+`1000C0005` 同码同文；未认证 401+`1000C0002` | 红 → 绿 |
| T3 升级留痕 | 版本递增后读面反映新值；`domain.lifecycle` 事件字段齐（action=UPGRADE/change-order/operator） | 红 → 绿 |
| T4 fail-closed | 清单改不存在 schema → 启动失败；还原后启动成功（负向 + 双向） | 红 → 绿 |
| T5 退役拦截 | `RETIRED` 业务请求 503 + `1013C0004` + 精确文案 | 红 → 绿 |
| T6 豁免面 | `RETIRED` 下 actuator/审计读面/部署域读面放行；数据与审计保留断言 | 红 → 绿 |
| T7 恢复双向 | 翻回 `ACTIVE` 业务恢复 + 读面 `ACTIVE` | 红 → 绿 |
| T8 结构断言 | 全仓无 `tenant_id`/租户表/在线生命周期 API | 保持绿（回归护栏） |
| T9 护栏扩展 | `PermissionPointRegistryTest` 纳入 V4；`platform.domain.read` ∈ seed；读面注解值 ⊆ seed | 红（V4 未落）→ 绿 |
| T10 网关 WebFilter | `RETIRED` 代理业务路径 503、豁免路径放行、ACTIVE 缺省零行为 | 红 → 绿 |

覆盖要求：`common/auth` 行覆盖 ≥80%（coreModules）、整体 ≥70%；禁止空洞断言与仅 happy-path（T2/T4/T5/T6 负向腿齐备）。

## §6 前置检查（编码段第一动作，P1~P8 逐项实测留痕 `build-output/w393-precheck-*.md`，gitignored）

| # | 检查项 | 不成立则 |
| --- | --- | --- |
| P1 | `AuthAutoConfiguration` 过滤器注册位/order 可插入；11 业务服务 `ctds.auth.enabled` 齐备（grep 实测） | 停机上报告 |
| P2 | `ApiResult` 成功/错误字段名与 R1 读面实读；evidence 豁免路径精确值（审计读面端点清单） | 停机上报告 |
| P3 | V3 DDL 两表精确列名/默认值（V4 编写依据）；`role_permission_change_log` 填法沿 RbacPermissionService 既有写入 | 停机上报告 |
| P4 | `PermissionPointRegistryTest` / `RbacThreeAdminMatrixTest` seed 读取方式（V4 适配点） | 停机上报告 |
| P5 | 网关 WebFilter 装配惯例与 order 定位；`/api/v1/platform/**` 网关路由覆盖情况（如需补路由条目 = 配置行，呈报后补） | 停机上报告 |
| P6 | `EvidenceExceptionHandler` 503 映射先例与新增映射点 | 停机上报告 |
| P7 | 演示链注入方式（九服务手工启动 env / runbook 口径）；部署脚本扩展形式（独立脚本 vs deploy.ps1 开关） | 呈报编排师选择 |
| P8 | 工作树干净 / 端口占用 / Docker 可用（Testcontainers 实跑） | 停机上报告 |

## §7 剧本回填草案（V1.3 → V1.4，业务判定与步骤编号零变更；须 PO 批准）

1. **操作路径表回填**（6 处）：S2-1 与 S3-1/S3-2 = `GET /api/v1/platform/domain`（`platform.domain.read` = 系统管理员）；S3-3 = runbook 升级段（`ctds.domain.platform-version` 递增 + 滚动更新 + 读面复验）；S3-4 = runbook 受控构造段（改 `ctds.domain.schema-list` → 重启 → 启动失败 → 还原复验）；S3-5/S3-7 = runbook 退役/恢复段（`CTDS_DOMAIN_STATUS` 注入 + 滚动更新；业务请求 503+`1013C0004`）；S3-8 = 三证核对口径（变更单 + 部署记录 + `domain.lifecycle` 审计事件）+ 结构断言。
2. **陈旧表述勘正**（5 处，立卡盘点实测）：前提⑥"3.9.2 未开工"→ 已交付（2026-11-13 合并）；`:60`/`:62` 小节"V1.3 草案"残留 → 已批准口径；`:87` 维护说明"⏳ 待 PO 一次确认" → 随本批转态；`:88` 尾部"V1.2 尚未 PO 批准"旧文 → 更正；S3-5 引用"B9-6" → "B9-R6"（编号引用精确化）。
3. **维护说明**补 V1.4 段（沿 V1.2/V1.3 段落惯例）。

## §8 规格码值登记（边界 8 义务，本卡执行）

- 权限点：**`platform.domain.read`**（只读，授 `platform.sysadmin`）——本任务卡登记 + V4 seed + §5 T9 护栏；
- 错误码：**`1013C0004`**（DEPLOYMENT_DOMAIN_RETIRED）/ **`1013S0002`**（DEPLOYMENT_CONFIG_MISMATCH）——本任务卡登记 + `DomainErrorCodes` 常量类；
- 规格 B9-R6"码值由实施包落定并登记"由此闭环；规格文本不改（1013 段预留语义已覆盖）。

## §9 E-勘误位（前置检查回填区，V1.0 生效后仅纯勘误登记）

| 编号 | 勘误内容 | 性质（纯勘误 / 裁定呈报） |
| --- | --- | --- |
| E-1 | 网关 WebFilter order"对齐 GatewayFilterOrders 白名单后、限流前"——P5 实测该类全部为 **GlobalFilter** 顺序，其 javadoc 明示 WebFilter 链先于网关过滤器链执行（跨家族不可比较）→ 实取 WebFilter 家族内 **-150**（早于 Spring Security WebFilterChainProxy -100 → 退役态未认证请求也 503 非 401；晚于 HeaderStripFilter -200）。依据 `GatewayFilterOrders.java` 注释 | 纯勘误（实现位澄清，2026-11-14 P5） |
| E-2 | 审计事件"`outcome=APPLIED`"——P1 实测 `AuditOutcome` 枚举仅 `SUCCESS/DENIED/FAILURE` 三值（common/logging 冻结契约，不加值）→ 映射 **`outcome=SUCCESS`**，生命周期动作语义由 `action=domain.lifecycle` + detail 字段承载。依据 `AuditOutcome.java` | 纯勘误（枚举零改动，2026-11-14 P1） |
| E-3 | §1"DomainStatusFilter 随 `ctds.auth.enabled` 挂链"——**不挂该条件**：`ctds.auth.enabled=false` 为非生产调试开关（挂接即构成退役绕过面，与 CHG §3"防绕过"冲突）；servlet 侧随 `AuthAutoConfiguration` 无条件注册，`ctds.domain.status` 缺省 ACTIVE 零行为不变 | 纯勘误（防绕过口径优先，2026-11-14 P1） |
| E-4 | 确认项：evidence 读面 `@RequirePermission("platform.domain.read")` 注解值进入 `PermissionPointRegistryTest` 主源码扫描面（services 全扫）→ T9 的 seed 读取须改为 V3 ∪ V4 并集（否则护栏红） | 确认项（与 §5 T9 预期一致，2026-11-14 P4） |

## §10 纪律与豁免登记

- 零新第三方依赖 / 零 `tenant_id` / 零租户表 / 零在线生命周期 API / 零登录与令牌签发 / 零前端改动 / 零门禁配置改动 / 零既有判定改写；
- PR ≤400 行有效变更按登记制豁免（D1 不拆分，沿 3.8.6 / 3.9.2 先例，随 PR 描述登记）；
- 剧本回填与规格文本零改动（回填走剧本 V1.4，须 PO 批准）；发现规格缺口一律停机走变更流程。
