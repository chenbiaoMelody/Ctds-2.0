# WBS-3.10.1 模型网关抽象层 · 高保真设计（定稿确认稿 = 编码契约）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-11-16 编排师定稿一次确认，指示原文"定稿确认，三份转V1.0"）= 编码契约**；实现与本稿不一致 = 打回项；V0.9 = 设计段草案 |
| 任务卡 / 规格 / lofi | `docs/tasks/WBS-3.10.1-模型网关抽象层-2026-11-16.md`；`docs/specs/C-11.1-AI0模型网关抽象层.md` **V1.0（已批准）**（B1~B4）；`docs/designs/WBS-3.10.1-lofi.md` **V1.0（方向定稿）** |
| 确认状态 | Q1~Q6 已确认采 A（2026-11-16"确认Q1到Q6按建议执行"）；**定稿一次确认已完成**（2026-11-16"定稿确认，三份转V1.0"；§10 签署） |
| 技术基线 | JDK 17 + Maven（ADR-001 冻结）；父 POM `spring-boot-starter-parent:3.5.16`；构件版本 **2.0.0-SNAPSHOT**；parent `<relativePath>../../pom.xml</relativePath>`（AGENTS §3） |

## §1 模块与文件清单（新增面，精确）

模块目录 `common/model-gateway/`，artifactId **`common-model-gateway`**，包根 **`com.ctds.common.modelgateway`**（沿 `com.ctds.common.pagination` 先例）。

| 文件（相对模块根） | 类型 | 职责 |
| --- | --- | --- |
| `pom.xml` | 构建 | parent ctds-platform；依赖 = `com.fasterxml.jackson.core:jackson-databind`（受管版本，common/crypto 先例）+ `org.springframework.boot:spring-boot-starter-test`（test，受管）；**零其他依赖** |
| `src/main/java/com/ctds/common/modelgateway/api/ChatCompletionRequest.java` | record | B1 请求契约 |
| `.../api/ChatMessage.java` | record | B1 消息（role + content） |
| `.../api/ChatCompletionResponse.java` | record | B1 响应契约 |
| `.../api/Choice.java` | record | B1 候选结果 |
| `.../api/Usage.java` | record | B1 token 用量 |
| `.../api/ModelGateway.java` | interface | B1 统一调用入口（审计挂点，Q6-A） |
| `.../spi/ModelProviderAdapter.java` | interface | B2 供应商适配器实现位 |
| `.../config/ModelGatewayProperties.java` | class（JavaBean） | B2/B3 配置结构（含嵌套 `ProviderConfig`） |
| `.../config/ProviderIds.java` | final class | B2 供应商编号常量 glm/deepseek/qwen/kimi |
| `.../route/ProviderRouter.java` | class | B2 配置化路由 |
| `.../route/RouteDecision.java` | record | B2 路由判定结果 |
| `.../control/GatewayControl.java` | final class | B3 开关/预算纯函数判定 |
| `.../control/ControlDecision.java` | enum | B3 判定结果三态 |
| `src/test/java/com/ctds/common/modelgateway/ContractJsonTest.java` | test | T1 |
| `.../ProviderRouterTest.java` | test | T2~T4 |
| `.../GatewayControlTest.java` | test | T5~T6 |

根 `pom.xml` 改动（**唯一既有文件改动**）：① `<modules>` +1 行 `<module>common/model-gateway</module>`（置于 common 段尾）；② `<dependencyManagement>` +1 节 `common-model-gateway`（`${project.version}`，沿 common 7 模块先例）。

**零改动面**：既有 21 模块主源/测试、前端、std-adapter、connector-sdk、门禁配置（`scripts/gates/*`）、迁移、CI workflow。

## §2 接口契约（签名级）

```java
// api/ModelGateway.java —— 未来所有 AI 调用唯一入口；V1.0 无实现类（B1）
public interface ModelGateway {
    ChatCompletionResponse chat(ChatCompletionRequest request);
}

// spi/ModelProviderAdapter.java —— 供应商适配器实现位；V1.0 无实现类（B2）
public interface ModelProviderAdapter {
    String providerId();
    ChatCompletionResponse chat(ChatCompletionRequest request);
}

// route/ProviderRouter.java —— 构造注入配置 + 已注册适配器表（B2）
public final class ProviderRouter {
    public ProviderRouter(ModelGatewayProperties properties,
                          Map<String, ModelProviderAdapter> adapters);
    public RouteDecision route(String providerId); // null/空白 → 用默认供应商
}

// route/RouteDecision.java
public record RouteDecision(Status status, ModelProviderAdapter adapter) {
    public enum Status { OK, UNKNOWN_PROVIDER }
    // OK ⇒ adapter 非空；UNKNOWN_PROVIDER ⇒ adapter 为 null
}

// control/GatewayControl.java —— 纯函数，无状态（B3）
public final class GatewayControl {
    public static ControlDecision check(ModelGatewayProperties properties, long usedTokens);
}

// control/ControlDecision.java
public enum ControlDecision { ALLOW, DENIED_DISABLED, DENIED_BUDGET_EXCEEDED }
```

**判定语义（真理表）**：

| 输入 | 输出 | 说明 |
| --- | --- | --- |
| `enabled=false`（含缺省） | `DENIED_DISABLED` | 先开关，与预算/用量无关 |
| `enabled=true`，`tokenBudget<=0` | `ALLOW` | 0/负 = 未设限（预留位） |
| `enabled=true`，`tokenBudget=N>0`，`usedTokens<N` | `ALLOW` | 未达上限 |
| `enabled=true`，`tokenBudget=N>0`，`usedTokens>=N` | `DENIED_BUDGET_EXCEEDED` | **上限语义（含等号，保守）** |
| route：指定/默认供应商在注册表 | `OK` + 对应适配器 | 指定优先于默认 |
| route：指定/默认供应商不在注册表 | `UNKNOWN_PROVIDER` + null | 不静默 fallback |

路由与判定**相互独立**（路由不查开关；消费方先 `GatewayControl.check` 后 `route`，顺序由 V1.5 运行时承接）；`usedTokens` 为入参，V1.0 无用量累计来源（lofi §6-2 诚实边界）。

## §3 OpenAI 兼容 DTO 字段表（B1）

序列化约定：全部 record 标注 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` + `@JsonInclude(JsonInclude.Include.NON_NULL)`；反序列化忽略未知字段（`ObjectMapper` 消费侧配置，测试用 `FAIL_ON_UNKNOWN_PROPERTIES=false`）；**字段集合 = Chat Completions 最小常用集**，扩展归 V1.5。

| record | Java 分量 | JSON 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- | --- |
| ChatCompletionRequest | model | model | String | 是 | 模型名（供应商侧标识） |
| | messages | messages | List\<ChatMessage\> | 是 | 多轮消息 |
| | temperature | temperature | Double | 否 | 采样温度 |
| | maxTokens | max_tokens | Integer | 否 | 单次生成上限 |
| | stream | stream | Boolean | 否 | **字段位保留，V1.0 不实现流式**（N5） |
| ChatMessage | role | role | String | 是 | 取值常量 `system`/`user`/`assistant`（类内字符串常量，不做枚举——OpenAI 原文为字符串） |
| | content | content | String | 是 | 消息内容 |
| ChatCompletionResponse | id | id | String | 是 | 响应标识 |
| | object | object | String | 是 | 固定 `chat.completion`（常量） |
| | created | created | Long | 是 | Unix 秒 |
| | model | model | String | 是 | 实际模型名 |
| | choices | choices | List\<Choice\> | 是 | 候选列表 |
| | usage | usage | Usage | 否 | 用量（可缺省） |
| Choice | index | index | Integer | 是 | 序号 |
| | message | message | ChatMessage | 是 | 生成消息 |
| | finishReason | finish_reason | String | 否 | 结束原因（如 `stop`，字符串不做枚举） |
| Usage | promptTokens | prompt_tokens | Integer | 是 | 提示用量 |
| | completionTokens | completion_tokens | Integer | 是 | 生成用量 |
| | totalTokens | total_tokens | Integer | 是 | 合计 |

## §4 配置键表（B2/B3；前缀常量 `ModelGatewayProperties.PREFIX = "ctds.ai.gateway"`）

配置类为**普通 JavaBean**（getter/setter，无 Spring 注解，N7）；绑定由消费方以 `@ConfigurationProperties(prefix = PREFIX)` 承接（3.10.4 / V1.5）。

| 配置键 | 类型 | 缺省 | 说明 |
| --- | --- | --- | --- |
| `ctds.ai.gateway.enabled` | boolean | **false** | 网关总开关（V1.0 零消耗安全默认） |
| `ctds.ai.gateway.default-provider` | String | `"glm"` | 默认供应商（ADR-003 §2.1） |
| `ctds.ai.gateway.token-budget` | long | **0** | token 预算上限；0=未设限（预留位） |
| `ctds.ai.gateway.providers.<id>.base-url` | String | null | 供应商 OpenAI 兼容服务地址（部署期注入，仓库零真实值） |
| `ctds.ai.gateway.providers.<id>.api-key` | String | null | **密钥字段位**：值只经配置中心/KMS/环境注入（红线）；仓库/测试仅占位符 |
| `ctds.ai.gateway.providers.<id>.model` | String | null | 供应商默认模型名（部署期配置，代码不硬编码型号） |

`ProviderIds` 常量：`GLM="glm"` / `DEEPSEEK="deepseek"` / `QWEN="qwen"` / `KIMI="kimi"`（私有构造防实例化）。`providers` 为 `Map<String, ProviderConfig>`；`ProviderConfig` = 嵌套 JavaBean（baseUrl/apiKey/model）。

## §5 测试用例表（T1~T6，先红后绿；JUnit 5，离线，无 Spring 上下文，无 Testcontainers）

| # | 用例（类.方法族） | 断言（字面/结构） | 规格锚 |
| --- | --- | --- | --- |
| **T1** | ContractJsonTest：请求/响应序列化往返 | ① 请求 JSON 含字面键 `model`/`messages`/`max_tokens`（snake_case），可选 null 字段**不出现**；② 响应 JSON 含 `finish_reason`/`prompt_tokens`/`completion_tokens`/`total_tokens`/`object="chat.completion"`；③ 多轮消息（system+user+assistant）往返逐字一致；④ 未知字段反序列化不报错 | B1-AC1 |
| **T2** | ProviderRouterTest：默认路由 | 缺省配置 + 注册桩适配器（glm）→ `route(null)` = OK 且 `adapter.providerId()=="glm"`；`route("")`/空白同默认 | B2-AC1 |
| **T3** | ProviderRouterTest：配置切换 | `default-provider` 改 deepseek/qwen/kimi（各注册对应桩）→ 路由到对应适配器；指定 providerId 优先于默认；**全程零代码改动（仅配置对象变化）** | B2-AC2 |
| **T4** | ProviderRouterTest：未注册拒绝 | 指定 `"unknown"` 或默认供应商未注册 → `UNKNOWN_PROVIDER` 且 adapter=null；**不 fallback 到其他已注册供应商** | B2-AC3 |
| **T5** | GatewayControlTest：开关 | 缺省配置（enabled=false）→ `DENIED_DISABLED`（任意 usedTokens）；enabled=true 且 budget=0 → `ALLOW` | B3-AC1/AC2 |
| **T6** | GatewayControlTest：预算边界 | budget=100：used=99 → ALLOW；used=100 → `DENIED_BUDGET_EXCEEDED`（等号拒绝）；used=101 → 同拒；budget=0/-1 → ALLOW（未设限）；enabled=false 优先于预算（used=0 也拒） | B3-AC3 |

**证据项（非单测，验收材料）**：
- **E1 零外呼**：模块 pom 依赖清单（无 HTTP 客户端/SDK）+ 主源零 `java.net`/http import + 全部测试断网可跑（`mvn -B -ntp test` 离线实证）→ B4-AC1；
- **E2 零真实密钥**：测试夹具密钥值 = 占位符（如 `"placeholder-not-a-real-key"`）；门禁 secretsScan GREEN + 全库人工核对 → B2-AC4；
- **E3 既有面零改动**：`git diff --stat` 锚定（除新模块目录 + 根 pom 外零文件）+ 既有模块测试全绿（门禁 unitTest 段）→ B4-AC2；
- **E4 门禁 GREEN**：全仓单发，ConfigSha256 与基线逐字一致，coverage overall ≥92.34%（新模块目标 ≥90%，纯逻辑代码易达标）→ B4-AC3。

红相口径：T1~T6 先写（编译失败/断言失败均计红相留痕），实现后全绿；红相日志落 `build-output/w3101-red-phase.log`（gitignored）。

## §6 边界值与异常行为

| 场景 | 行为 | 依据 |
| --- | --- | --- |
| `route(null)` / `route("")` / 空白串 | 按默认供应商解析 | §2 签名注释 |
| 默认供应商配置为 null/空白 | 回退常量 `ProviderIds.GLM`（ADR-003 默认智谱） | B2 规则 2 |
| 注册表含未配置供应商的适配器 | 仍可路由（注册表为准；配置表供连接参数，V1.0 路由不校验 base-url） | 最小实现；连接参数校验归 V1.5 真实适配器 |
| `usedTokens` 为负 | 按 <上限 处理（ALLOW，若开关开）；不做入参校验异常（纯判定，调用方负责用量真实性） | 最小实现 |
| DTO 可选字段 null | 序列化不输出（NON_NULL）；反序列化缺失 → null | §3 |
| 未知 JSON 字段 | 反序列化忽略（消费侧 ObjectMapper 配置；测试锚定） | OpenAI 兼容前向兼容 |
| 开关/预算/路由拒绝 | 返回判定枚举/记录，**不抛异常、不出错误码**（库无出站面；错误码映射归消费方，N6） | §2 |

## §7 对接点预留（Q6-A，仅签名/键名，不建类型）

| 下游 | 挂点 | 本卡交付 | 下游承接 |
| --- | --- | --- | --- |
| 3.10.3 AI 审计 | `ModelGateway.chat` 唯一入口 | 接口签名 + 契约测试锚定稳定性 | 审计装饰器/拦截 + 日志表结构 |
| 3.10.3 护栏 | 同上（调用前后挂点） | 同上 | 输入/输出护栏框架 |
| 3.10.4 配置页 | `ctds.ai.gateway.*` 键表（§4） | 配置 JavaBean + PREFIX 常量 | 页面绑定与读写 |
| V1.5 C-11.x | `ModelProviderAdapter` / `ModelGateway` | 两接口 + 路由/判定骨架 | 真实适配器、运行时拦截、计量、流式 |

## §8 验证命令（编码段交付口径）

1. 模块：`mvn -B -ntp -pl common/model-gateway test`（红相 → 绿相）+ `mvn -B -ntp -pl common/model-gateway checkstyle:check`；
2. 全仓：`mvn -B -ntp compile` / `test` / `checkstyle:check`（既有面回归）；
3. 门禁：`scripts/gates/run-gates.ps1` 单发（运行期仓库静止、禁并行 mvn/npm；ConfigSha256 逐字核验）；
4. 零外呼实证：断网跑模块测试（E1）；密钥核对（E2）；diff 锚定（E3）。

## §9 勘误位（评审/复审段回填）

（空——V0.9 无勘误）

## §10 确认记录

| 版本 | 日期 | 确认人 | 结论 |
| --- | --- | --- | --- |
| V0.9 | 2026-11-16 | 编排师 | Q1~Q6 采建议 A（"确认Q1到Q6按建议执行"）；契约细节呈报 |
| **V1.0** | 2026-11-16 | 编排师 | **定稿一次确认**（"定稿确认，三份转V1.0"）= 编码契约即时生效（与规格 V1.0 / lofi V1.0 一并批准） |