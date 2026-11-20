# WBS-3.10.4 高保真设计（AI 服务配置管理页 · 全栈编码契约）V1.0

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（编码契约，2026-11-20 编排师定稿确认）**——编排师会话指示原文 = **"定稿确认，三份转 V1.0（推荐）"**，本文件与规格 / lofi 同批转 V1.0，**编码契约即时生效**；V0.9 = 设计段草案（2026-11-20） |
| 上游 | 规格 `docs/specs/C-11.1A-AI服务配置管理页.md` V0.9（B1~B4）；lofi `docs/designs/WBS-3.10.4-lofi.md` V0.9；先例：3.9.5 界面惯例 / 3.9.4 权限增量 / space-service 配置留痕 / C-11.1 配置结构与判定纯函数 |
| 命名前缀 | 错误码 `1014C0001`（1014 = 3.10/AI 域）；权限点 `platform.ai.config.read` / `platform.ai.config.write`；表 `ai_gateway_config` / `ai_gateway_provider_config` / `ai_gateway_config_change_log` |

## 1. 端点契约（4 端点，evidence-service；出参字段 → 界面元素逐字段映射）

> 前缀 = **独立命名空间 `/api/v1/ai-config`**（编码段修正 E-01-1/2：不占用部署域读面封闭命名空间——两道既有守卫导出，见 §9 E-01；前端 vite proxy 新增 1 键 `/api/v1/ai-config` → 8090，键序先行）；响应封装统一 `ApiResult`（code="0" + data）；401/403 走 common-auth 公共码。

| # | 端点 | 权限点 | 入参 | 出参（data） | 界面承载 |
| --- | --- | --- | --- | --- | --- |
| E1 | `GET /api/v1/ai-config` | `platform.ai.config.read` | — | `AiConfigView`（见 §1.1） | 区块①③初值 + 预览依据 |
| E2 | `POST /api/v1/ai-config/save` | `platform.ai.config.write` | body `AiConfigSaveRequest`（§1.2）；操作者 = `X-Ctds-Subject` 头 | `AiConfigView`（保存后） | 保存按钮；成功后刷新①③④ |
| E3 | `GET /api/v1/ai-config/preview` | `platform.ai.config.read` | — | `AiConfigPreviewView { decision: "ALLOW"\|"DENIED_DISABLED"\|"DENIED_BUDGET_EXCEEDED", reason: 文案 }` | 区块②三态芯片 |
| E4 | `GET /api/v1/ai-config/changes` | `platform.ai.config.read` | `pageNum` / `pageSize`（可选，沿 PageResult 约定） | `PageResult<AiConfigChangeLogView>`（list/total/pageNum/pageSize/totalPages） | 区块④分页五列 |

**错误口径**：参数校验拒绝 = HTTP 400 + `1014C0001`（message 细分原因，见 §2.4）；无权限 = 403（common-auth 公共码）；其余沿全局异常处理惯例。**读响应（E1/E2 出参）不含 `apiKey` / `apiKeyRef` 任何密钥字段**（规格 B1-3 / B4-1 红线）。

### 1.1 AiConfigView（合并视图）

```json
{
  "enabled": false,
  "defaultProvider": "glm",
  "tokenBudget": 0,
  "providers": [
    { "providerId": "glm", "providerName": "智谱 GLM", "isDefaultProvider": true,
      "baseUrl": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4-flash", "configured": true },
    { "providerId": "deepseek", "providerName": "DeepSeek", "isDefaultProvider": false,
      "baseUrl": null, "model": null, "configured": false }
  ]
}
```

- 合并规则：DB 有保存值用 DB 值；无保存值（V6 seed 态）用 C-11.1 缺省（enabled=false / defaultProvider="glm" / tokenBudget=0 / providers 四行 baseUrl=null model=null configured=false）；
- `providers` 恒 4 行（封闭清单 glm/deepseek/qwen/kimi，顺序固定）；`configured = baseUrl 非空或 model 非空`；
- **无密钥字段**（api_key_ref 不出参，规格 B1-3）。

### 1.2 AiConfigSaveRequest（全量提交）

```json
{
  "enabled": true,
  "defaultProvider": "glm",
  "tokenBudget": 100000,
  "providers": [
    { "providerId": "glm", "baseUrl": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4-flash" },
    { "providerId": "deepseek", "baseUrl": null, "model": null },
    { "providerId": "qwen", "baseUrl": null, "model": null },
    { "providerId": "kimi", "baseUrl": null, "model": null }
  ]
}
```

校验规则（拒绝统一 `1014C0001`，message 可区分）：`providers` 必须恰含封闭清单 4 个编号且不得出现清单外编号；`defaultProvider` 必须在清单内；`tokenBudget` ≥0 整数；`baseUrl` null/空 或 http(s) URL（≤512）；`model` null/空 或 ≤128 字符。

## 2. 后端契约（evidence-service，落 `com.ctds.evidence`，沿分层惯例）

### 2.1 类清单（新增 5 + 测试 3）

| 类 | 层 | 职责 |
| --- | --- | --- |
| `interfaces.AiConfigController` | 接口层 | 4 端点；`@RequirePermission` 双点；操作者取 `X-Ctds-Subject`（required=false，缺省 "unknown"） |
| `application.AiConfigService` | 应用服务 | 合并视图组装 / 全量保存与校验 / 留痕写入（同事务）/ 判定预览（组 `ModelGatewayProperties` → `GatewayControl.check(props, 0)` → 文案映射） |
| `application.AiConfigViews` | 应用层 DTO | `AiConfigView` / `ProviderView` / `AiConfigSaveRequest` / `AiConfigPreviewView` / `AiConfigChangeLogView` |
| `infrastructure.JdbcAiConfigStore` | 基础设施 | 三表读写（`JdbcClient`/`JdbcTemplate` 沿 `JdbcRbacStore` 惯例）；留痕逐字段 diff |
| `domain.AiConfigErrorCodes` | 领域 | `PARAM_INVALID = ErrorCode.of("1014C0001")` + 5 条细分 message 常量（格式沿 `MonitorErrorCodes`） |
| 测试：`AiConfigContractTest`（零 Docker 结构锚）/ `AiConfigGovernanceIntegrationTest`（Testcontainers 行为面 T1~T6/T8 + E4）/ `AiConfigMigrationStructureIntegrationTest`（T7 迁移锚） | — | 命名沿仓内 Contract/Integration 惯例（E-01-6：原拟三名更名合并） |

### 2.2 Flyway V6 DDL 草案（`V6__create_ai_gateway_config.sql`；最终以实现为准，注释口径不变）

```sql
-- WBS-3.10.4 Q2-A/Q3-A：AI 网关配置面（模型/开关/预算配置管理页的数据承载）。
-- 红线：模型密钥值禁止入库（ADR-003）——api_key_ref 仅引用占位（seed 空值），页面与读 API 均不返回；
-- 诚实边界：V1.0 无运行时消费方，本配置"落库 ≠ 已生效"，生效对象 = 未来 V1.5/C-11.x 绑定（C-11.1 规格 :101）。

CREATE TABLE ai_gateway_config (
    id              INT          NOT NULL COMMENT '恒 1（单例行）',
    enabled         TINYINT      NOT NULL DEFAULT 0 COMMENT '网关总开关：1=启用 0=关闭（缺省关闭=V1.0 零消耗安全默认，C-11.1 B3）',
    default_provider VARCHAR(32) NOT NULL DEFAULT 'glm' COMMENT '默认供应商编号（封闭清单内）',
    token_budget    BIGINT       NOT NULL DEFAULT 0 COMMENT 'token 预算上限；0=未设限（预留位，C-11.1 B3）',
    updated_by      VARCHAR(64)  NULL COMMENT '最近修改人（X-Ctds-Subject）',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 网关总控配置（单例行；V1.0 配置即预留，无运行时消费方）';

CREATE TABLE ai_gateway_provider_config (
    provider_id  VARCHAR(32)  NOT NULL COMMENT '供应商编号（封闭清单：glm/deepseek/qwen/kimi，ADR-003 §2.1）',
    base_url     VARCHAR(512) NULL COMMENT 'OpenAI 兼容服务地址（http(s)；NULL=未配置；仓库零真实部署值）',
    api_key_ref  VARCHAR(256) NULL COMMENT '密钥引用占位列：真实密钥值禁止入库（红线），部署期经配置中心/KMS 注入；页面与读 API 均不返回本列',
    model        VARCHAR(128) NULL COMMENT '供应商默认模型名（NULL=未配置）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
    PRIMARY KEY (provider_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 供应商连接参数（封闭清单四行；密钥值永不入库）';

CREATE TABLE ai_gateway_config_change_log (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    changed_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更时间',
    changed_by   VARCHAR(64)   NOT NULL COMMENT '操作者（X-Ctds-Subject）',
    field_path   VARCHAR(64)   NOT NULL COMMENT '字段路径：enabled/defaultProvider/tokenBudget/providers.<id>.baseUrl/providers.<id>.model',
    before_value VARCHAR(1024) NULL COMMENT '变更前值（NULL 显示为空串口径）',
    after_value  VARCHAR(1024) NULL COMMENT '变更后值',
    PRIMARY KEY (id),
    KEY idx_changed_at (changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 配置变更留痕（只读；逐字段从何值→到何值；不提供界面回滚）';

-- seed：总控单例行（C-11.1 缺省态）+ 封闭清单四行（未配置态）
INSERT INTO ai_gateway_config (id, enabled, default_provider, token_budget) VALUES (1, 0, 'glm', 0);
INSERT INTO ai_gateway_provider_config (provider_id, base_url, api_key_ref, model) VALUES
 ('glm', NULL, NULL, NULL), ('deepseek', NULL, NULL, NULL),
 ('qwen', NULL, NULL, NULL), ('kimi', NULL, NULL, NULL);

-- 权限点增量（沿 V5 先例：授 platform.sysadmin；secadmin/audadmin 不持新点 → 负向 403 天然可演；
-- role_permission_change_log 同事务留痕；变更单编号 = 本任务卡登记）
INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.ai.config.read',1,1);
INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point, before_value, after_value) VALUES
 ('RCW3104AICONF01','CHG-WBS-3.10.4-AI-CONFIG-READ','wbs-3.10.4',NOW(3),'platform.sysadmin','platform.ai.config.read','0','1');
INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.ai.config.write',1,1);
INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point, before_value, after_value) VALUES
 ('RCW3104AICONF02','CHG-WBS-3.10.4-AI-CONFIG-WRITE','wbs-3.10.4',NOW(3),'platform.sysadmin','platform.ai.config.write','0','1');
```

### 2.3 合并视图与预览算法

- **合并视图**：读 `ai_gateway_config` 单例行 + `ai_gateway_provider_config` 四行 → 组 `AiConfigView`（缺省态由 seed 承载，无运行时缺省分支；`providerName`/`isDefaultProvider` 由常量层组装，不落库）；
- **保存**：整体校验（§1.2）→ 事务内：逐字段 diff（新值 vs 库值）→ 有变化的字段写 `ai_gateway_config_change_log` → upsert 主表与供应商行（`updated_by` = 操作者）→ 返回保存后视图；
- **判定预览**：由合并视图组 `ModelGatewayProperties`（enabled/defaultProvider/tokenBudget；providers 不参与判定）→ `GatewayControl.check(properties, 0L)` → 决策映射：ALLOW→"允许（当前配置下网关放行）" / DENIED_DISABLED→"网关未启用" / DENIED_BUDGET_EXCEEDED→"超出预算"（V1.0 已用量恒 0，该分支不可触发，界面口径灰字明示）。

### 2.4 错误码（`AiConfigErrorCodes`，1014 段启用）

| 码值 | 常量 | message（细分可区分） |
| --- | --- | --- |
| `1014C0001` | `PARAM_INVALID` | 按 5 类细分常量：`MSG_PROVIDER_NOT_IN_LIST`（供应商编号不在封闭清单）/ `MSG_DEFAULT_PROVIDER_NOT_IN_LIST`（默认供应商不在封闭清单）/ `MSG_BUDGET_INVALID`（token 预算须为 ≥0 整数）/ `MSG_BASE_URL_INVALID`（服务地址须为 http(s) URL 或留空）/ `MSG_MODEL_INVALID`（默认模型名不得超过 128 字符） |

## 3. 前端文件清单（新增 4 + 修改 2）

| 动作 | 文件 | 内容 |
| --- | --- | --- |
| 新增 | `frontend/src/views/platform/AiConfigView.vue` | 单页四区块（lofi §4）；表单态集中；保存 = 全量 POST /save |
| 新增 | `frontend/src/views/platform/AiConfigView.spec.ts` | F1~F6（§7） |
| 新增 | `frontend/src/api/ai.ts` | 4 函数 + 类型 + URL 常量单点 |
| 新增 | `frontend/src/api/ai.spec.ts` | URL/payload 断言 |
| 新增 | `frontend/src/constants/ai.ts` | PROVIDER/DECISION 双 map + labelOf |
| 修改 | `frontend/src/router/index.ts` | +1 菜单项（menuOrder 32，permission 挂点） |
| 修改 | `frontend/vite.config.ts` | +1 proxy 键 `/api/v1/ai-config` → 8090（键序先行，E-01-2：独立命名空间导出；原"零 proxy 改动"表述作废） |
| 修改 | `frontend/src/layouts/MainLayout.spec.ts` + `frontend/src/router/router.spec.ts` + `frontend/src/views/platform/PlatformSourceGuard.spec.ts` | 计数断言 31→32（含 max）+ 权限路由清单/platform 视图源集清单各 +1（守卫白名单扩展登记） |

## 4. api 层契约（`api/ai.ts`）

| 函数 | 对应端点 | 说明 |
| --- | --- | --- |
| `fetchAiConfig(): Promise<AiConfigView>` | E1 | 读合并视图 |
| `saveAiConfig(req: AiConfigSaveRequest): Promise<AiConfigView>` | E2 | 全量保存 |
| `fetchAiConfigPreview(): Promise<AiConfigPreviewView>` | E3 | 判定预览 |
| `fetchAiConfigChanges(pageNum?, pageSize?): Promise<PageResult<AiConfigChangeLogView>>` | E4 | 留痕分页 |

URL 常量单点：`AI_CONFIG_URL = '/api/v1/ai-config'`（+ `/save`、`/preview`、`/changes` 派生）；类型定义与出参逐字段一致（§1）；走既有 `apiJson`（身份头/错误码断言复用，`ApiError` 保留九位错误码）。

## 5. 视图组件契约（`AiConfigView.vue`）

- 挂载即并行拉 E1 + E3 + E4（第 1 页）；保存成功后重拉 E1/E4 并刷新预览；
- 表单态：`enabled: boolean` / `tokenBudget: number` / `defaultProvider: string` / `providers: {providerId, baseUrl, model}[]`（与 E2 同构）；"放弃修改" = 重置为最近一次服务端视图；
- 校验（前端前置，后端仍权威）：tokenBudget 为 ≥0 整数；baseUrl 空 或 /^https?:\/\//i；model ≤128；
- 密钥列：静态灰字节点，**不绑定任何数据字段**（规格 B1-3；测试断言渲染结果零密钥字段）；
- 三态芯片：constants/ai.ts DECISION map；未知枚举值原样显示；
- 诚实边界提示条常驻页面顶部（"配置即预留"口径原文见 lofi/原型）。

## 6. 权限与身份

- 读面（E1/E3/E4）挂 `platform.ai.config.read`；写面（E2）挂 `platform.ai.config.write`；两新点仅授 `platform.sysadmin`（V6 seed）；
- 菜单 meta.permission = `platform.ai.config.read`（hasPermission 占位实现下 = admin 演示档可见，沿 3.9.5 口径，真实权限点标注为诚实口径）；
- 操作者 = `X-Ctds-Subject` 头（client.ts `apiJson` 既有封装自动附；后端 required=false 缺省 "unknown"）；前端呈现不构成安全边界（后端 `@RequirePermission` 权威，负向 403 可演）。

## 7. 测试锚点（测试先行：红相归档 `build-output/w3104-red-*`）

**规格行为 → 剧本步骤 → 承载用例映射**：

| 规格行为 | 剧本步骤（Q7-A，待建） | 承载用例 |
| --- | --- | --- |
| B1 配置查看 | S1 配置查看 | **T1** 缺省视图（seed 态 = 关闭/glm/0/四供应商未配置）；**T2** 已保存视图回读；**T8** 读响应零密钥字段（api_key_ref 断言）；**F1** 页面渲染（缺省/未配置灰字/供应商标签）；**F4** 菜单与计数（32/三处同步） |
| B2 编辑与校验 | S2 编辑与校验 | **T3** 保存成功（落库 + 返回视图 + 逐字段留痕仅变化字段）；**T4** 校验拒绝四类参数化（清单外编号 / 默认供应商不在清单 / 负预算 / 非法 URL → 400 + 1014C0001 + 原因文案可区分 + 库与留痕零变化）；**T5** 权限守卫（无 read 403 / 无 write 403 双向）；**F2** 编辑保存（payload/成功反馈/刷新）；**F3** 前置校验拦截提示 |
| B3 判定预览 | S3 判定预览 | **T6** 预览（关闭→DENIED_DISABLED；开启+预算 0→ALLOW；props 组装正确性）；**F5** 预览芯片与口径灰字 |
| B4 密钥红线与边界 | S4 边界回归 | **T7** 迁移 IT（V6 三表结构 + seed 单例行/四供应商行 + 权限两点入库）；**T8**（同上红线断言）；**E1** secretsScan 零命中；**E2** diff 锚定（model-gateway/既有迁移/门禁配置零改动）；**E3** 全仓门禁 GREEN（ConfigSha256 逐字一致、coverage ≥92.48%）；**F6** 留痕渲染 |

后端 8 用例（T1~T8，含参数化展开）+ 前端 6 用例组（F1~F6）；禁止空洞断言与仅 happy-path（T4/T5 为负向主体）。

## 8. 边界值与异常行为

| 场景 | 期望 |
| --- | --- |
| tokenBudget = 0 | 合法（未设限）；预览 ALLOW |
| tokenBudget = -1 / 非整数 | 400 + `1014C0001`（MSG_BUDGET_INVALID）；零变化 |
| providers 含清单外编号 / 缺行 / 重复行 | 400 + `1014C0001`（MSG_PROVIDER_NOT_IN_LIST）；零变化 |
| defaultProvider = "gpt"（清单外） | 400 + `1014C0001`（MSG_DEFAULT_PROVIDER_NOT_IN_LIST） |
| baseUrl = "ftp://x" / "open.bigmodel.cn"（无协议） | 400 + `1014C0001`（MSG_BASE_URL_INVALID） |
| baseUrl/model = null | 合法（未配置态；configured=false） |
| model 长度 129 | 400 + `1014C0001`（MSG_MODEL_INVALID） |
| enabled=false 保存后预览 | DENIED_DISABLED（先开关后预算） |
| 保存载荷与库值完全一致 | 成功返回视图；**留痕零新行**（仅变化字段留痕） |
| 无 read 权限读 E1/E3/E4 | 403（common-auth 公共码） |
| 无 write 权限写 E2 | 403；库与留痕零变化 |
| evidence 库不可用 | 沿全局异常处理惯例（不静默假绿；预览 fail-closed 不放行文案） |

## 9. 剧本映射与诚实边界（+ E-勘误位）

- 剧本（Q7-A，待建）四步 S1~S4 与 §7 映射表逐条对应；剧本执行留痕随编码段回填；
- 诚实边界（复述规格 B4-3，页面与交付说明必须一致）：**配置落库 ≠ 配置已生效**（V1.0 无运行时消费方）；本页 ≠ AI 服务可用（零外呼）；预算上限 ≠ 已生效熔断；预览 ≠ 真实调用结果（已用量恒 0）；
- **E-01 勘误登记（编码段 2026-11-21，设计修正不改变规格 B1~B4 验收口径）**：
  - **E-01-1** E2 动词 PUT → **POST `/save`**（`EvidenceArchitectureTest.interfacesExposeNoMutableHttpVerbs`：evidence 接口层守卫禁 Put/Patch/Delete 改删动词；写面沿仓内 POST 惯例）；
  - **E-01-2** 前缀 `/api/v1/platform` → **独立命名空间 `/api/v1/ai-config`**（`DomainGovernanceContractTest` 双守卫导出：platform 命名空间持有人封闭清单 + `/api/v1/platform/` 与租户路径零写端点；**既有守卫零修改**，AI 域独立路径沿各域独立命名空间惯例）；vite proxy 由"零改动"改为 **+1 键**；
  - **E-01-3** `domain/AiConfigStore` 领域接口补增（沿 `RbacStore` 接口 + `JdbcRbacStore` 实现分层先例，原清单仅列 Jdbc 实现）；
  - **E-01-4** 错误码 message 细分常量 5 → **6 类**（补 `MSG_ENABLED_INVALID`：开关缺失口径，码值仍 1014C0001）；
  - **E-01-5** 供应商显示名由**后端视图 providerName 承载**（前端 constants 不再复制中文标签，仅保留判定三态与封闭清单守卫）；
  - **E-01-6** 测试类命名沿仓内 Contract/Integration 惯例（见 §2.1 更新）。

## 10. 体量与门禁

- **体量预估**：后端 V6 SQL ~90 行（含注释）+ 主源 5 类 ~350 有效行 + 后端测试 ~350；前端 View ~230 + spec ~180 + api ~70 + spec ~60 + constants ~40 + router/计数 ~15 → **合计约 1000~1300 有效行（不含文档）**，超 400 行/PR → 按任务卡 Q8-A 拆 **2 批链式**（批1 = 后端 V6+API+测试；批2 = 前端全套+计数同步）；编码段实测后呈报确切拆分（若单批仍超 400，呈报编排师裁定细分或登记豁免）；
- **门禁基线**：coverage overall 不劣于 **92.48%**（3.10.3 验收值）；ConfigSha256 与基线 `adc3f98b…5cfb` 逐字一致（零门禁配置改动）；mutation 仅 did 不受影响；前端既有测试基线全绿（计数 +F1~F6）；
- **零改动面**（diff 锚定项）：`common/model-gateway`、`common/guardrail`、`common/vector-store`、其余全部 services（除 evidence-service 本卡新增面）、gateway-service 路由、既有迁移脚本、门禁配置、`frontend/vite.config.ts`（复用既有 `/api/v1/platform` 键）。
