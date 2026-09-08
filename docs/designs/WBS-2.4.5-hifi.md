# WBS-2.4.5 common-鉴权框架 · 高保真设计（定稿 = 编码契约）
- 型态：非界面类（任务卡已标注）
- 对应规格：制度依据 = WBS 2.4.5 产出定义（"统一认证/授权框架（RBAC 接口、Token 校验）"，1 天）+ 章程 4.3 + ADR-005 §3 第 4 项（JWT+RBAC，网关统一校验、服务内部只认上下文头）+ 低保真（已确认 2026-09-07，五问回答：1 认可 / 2 选 A / 3 同意 / 4 同意 / 5 可以）
- 任务卡：WBS 2.4.5 ｜ 工作量：1 天（章程 2.6.3：两级已分次确认，本文件为定稿确认）
- 关联设计：低保真 = `docs/designs/WBS-2.4.5-lofi.md`（已确认）；定稿后契约固化进 ADR-005 §3 第 7 项

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | PO | 2026-09-07 11:17（原件笔误"20260-09-07:11:17"，2026-09-07 评审③P3-7 更正留痕） | 无 |

## 评审修复记录（编码会话 4 视角会审后增补留痕，沿 2.4.4 先例；均为实现澄清/加固，不新增对外行为）

- ①P1-1：网关开关开启但应用未提供 `ServerHttpSecurity` 时由"静默跳过"改为**启动失败**（fail-fast，GatewayChainWiringTest 钉住）；①P2-1：B7 计划测试的"WebTestClient 集成"落地为**真实管线链级端到端测试**（strip→安全链→inject 依 Ordered 次序串联：合法令牌→放行+注入权威头 / 无令牌→401 封套）+ 链过滤器构成断言——因 ReactiveWebApplicationContextRunner 下 `@ConditionalOnWebApplication(REACTIVE)` 不成立（Boot 测试框架限制），改直调 Bean 方法构造真实链；
- ①P2-2：`io.projectreactor:reactor-core`（optional，编译适配）与 ②补 `org.springframework:spring-webflux`（test scope）已登记依赖簿；①P3-1/P3-2：鉴权异常实现为 `AuthException`（BizException 子类，构造限 1000C0002/1000C0005 两码），AuthAdvice 二值映射无兜底分支——hifi 契约表"抛 BizException"按此子类化落地澄清；
- ①P3-3：`AuthContext.user()`/`AuthUser` 为契约表外增量读取入口（返回不可变身份对象，测试用），行为不超契约；①P3-4：密钥强度新增"HS256 secret ≥32 字节即启动失败"；nbf 早于偏移归 EXPIRED（reason 枚举内无更贴切值，仅内部审计用）；
- ②P2-1：`ctds.auth.enabled=false` 启动打印 WARN（fail-open 提醒）；部署前提写入交付说明：**生产禁止关闭该开关，服务端口须网络隔离仅网关可达**；②P3-2：角色 claim 非字符串项跳过（读原始 claim，不注入无意义角色）；②P3-1/P3-3/P3-4：登记观察项——弱密钥熵检测归 KMS 下发策略（3.9.1 前提）、链上阻塞解码改反应式调度归 3.5.2 网关交付、生产日志级别 ≥INFO 写入部署基线；
- ③P2-1：封套 traceId 读取沉淀为 `ApiResult.currentTraceId()`（errorcode 增量公共方法，GlobalExceptionHandler/AuthAdvice 统一调用，既有行为不变）；③P3-2：ErrorCodes 增补常量时同步给既有 UNAUTHORIZED 补映射说明注释（文档性变更）；③P3-3/P3-4/P3-5：ADR 引用记法统一、`ctds.auth.audit.enabled` 与 `ctds.audit.enabled` 语义区分说明、模块内 normalize 合并——登记为 common 组件改进建议，未在本任务处理；
- ④P2-2：核心模块行覆盖 ≥80% 的实测待 JaCoCo 接入（门禁 PENDING-JACOCO，工具链变更已走 ADR 流程），交付说明不宣称覆盖数值；④P3-6/P3-8：roles-claim 配置化用例、exp/nbf 秒级边界用例登记为测试改进建议；
- 附带缺陷修复（非本任务范围，由本任务测试暴露）：2.4.4 审计按天滚动原以**写线程消费时刻**定日（队列积压/跨天竞态致事件落错日文件 + 测试偶发红），改为**入队时刻按事件 eventTime 定稿日文件名**（AsyncFileAuditRecorder.Line 携带 day），跨天用例转为确定性通过。

## 与低保真的差异声明（细化产生的三处澄清，无结构新增）

1. **网关侧校验的交付形态**：V1.0 未建网关服务模块（真实流量入口在 3.5.2"API 网关交付服务"落地），故本次交付"**引入即生效的 JWT 校验自动配置组件 + 组件级集成测试**"，3.5.2 届时引入组件并以 2 行配置启用。与低保真"可复用的 JWT 校验配置"一致，不新增范围；
2. **resource-server 依赖以 optional 方式引入**：Spring Security 类一经上到 classpath，Spring Boot 默认安全策略会**自动接管全部端点**（未配置即 401），会波及没有开启网关校验的普通服务（含 example-service）。因此 `spring-boot-starter-oauth2-resource-server` 声明为 `<optional>true</optional>`：只有网关服务显式再加一次该依赖并打开 `ctds.auth.gateway.enabled` 才生效；普通服务引入 common-auth 只得到上下文 + RBAC，classpath 不带入 Spring Security，零排除配置；
3. **错误码清单定稿**：未认证 = **复用** `ErrorCodes.UNAUTHORIZED（1000C0002）`→ HTTP 401；无权限 = **新增登记** `FORBIDDEN（1000C0005）`→ HTTP 403（在 `ErrorCodes` 常量表增量登记一个 C 型码，不改动任何既有码与行为——码表本就按"全平台唯一登记"制度扩展）。HTTP 状态映射由鉴权组件自带的异常处理器完成（`ErrorType` 既有注释已预留"特定语义错误码可在处理器映射更精确状态"的口径），`GlobalExceptionHandler` 与 errorcode 组件行为**零改动**。

## 行为清单（逐条对应产出定义与低保真确认）

| 编号 | 行为（业务语言） | 对应依据 | 计划测试 |
| --- | --- | --- | --- |
| B1 | 服务侧身份上下文：请求携带上下文头（默认 `X-Ctds-Subject` / `X-Ctds-Roles`）时，业务代码一行取用"当前用户是谁 + 角色清单"；不带头 = 空上下文；请求结束自动清理（防线程池串号，模式同 2.4.4 清理过滤器，**不受组件开关约束**） | lofi 做什么-2 | AuthContextFilter 单测：带头解析 / 不带头 / 空段与重复清理 / 超长拒绝（MockHttpServletRequest + ThreadLocal 断言） |
| B2 | 注解式 RBAC 强制：方法标 `@RequirePermission("greeting.delete")`，角色权限清单不含该权限 → HTTP 403 + 码 1000C0005 + 标准文案；含则放行 | lofi 做什么-3 | MockMvc：有权 200 / 无权 403 / 不带头 401 三路径（"策略生效 + 绕过被拒"双向用例） |
| B3 | 未认证口径（确认问题 3）：受保护端点（带注解）无身份头 → 401 + 码 1000C0002，文案"认证失败或身份已失效"；网关校验令牌失败返回**同码同文案** | lofi 做什么-3/4 + 问题3 | B2 单测 + B7 网关集成测试比对封套一致 |
| B4 | 代码内调用写法：注入 `AccessControl`——`hasPermission(权限)` 只判断不抛错；`require(权限)` 无权即抛与注解完全一致的错误与审计 | lofi 做什么-3 | DefaultAccessControl 单测（与注解共用判定核，断言两写法结果一致） |
| B5 | 角色→权限映射（模式 A，确认问题 2）：配置文件维护 `ctds.auth.permissions.<角色>=逗号分隔权限`；未知角色 = 无任何权限；映射来源接口可插拔，3.9.2 以 Bean 覆盖换库表来源（组件零改动） | lofi 结构组成 + 问题2A | ConfigRolePermissionMapperTest（含未知角色、空值、多角色并集） |
| B6 | 审计联动（确认问题 4，默认开启）：RBAC 拒绝 → `rbac.check` DENIED（actor=当前身份，detail 仅 permission）；令牌校验失败 → `auth.verify` FAILURE（detail 仅 reason 枚举）；`ctds.auth.audit.enabled=false` 可关 | lofi 做什么 + 问题4 | 临时审计目录断言 JSONL 行；开关关闭零写入 |
| B7 | 网关侧 JWT 校验（组件形态）：合法令牌 → 放行并向下游注入身份上下文头；过期 / 坏签名 / 签发方不符 → 401 统一封套 + 审计 FAILURE；**客户端伪造的身份头一律剥离**（信任边界） | lofi 做什么-4 | WebTestClient 集成测试：放行+注入 / 过期 401 / 坏签名 401 / 伪造头被剥 4 用例（测试令牌由 nimbus-jose-jwt 自签，无新依赖） |
| B8 | 校验密钥来源配置化：`ctds.auth.jwt.secret`（HMAC，演示/内网）或 `ctds.auth.jwt.jwk-set-uri`（公钥集，生产）二选一；同缺或同配 → 启动失败并给出明确原因；SM2 校验器扩展点 = 业务可覆盖 `JwtDecoder` Bean（V1.0 不实现，沿"可选增强"口径） | lofi 结构组成 / 不做什么-3 | AutoConfiguration 条件单测（启动失败消息断言） |
| B9 | example-service 三结果演示（确认问题 5）：GET 列表需 `greeting.read`，新增 DELETE 端点需 `greeting.delete`；映射 user→read，admin→read+delete；删除成功记 `greeting.delete` SUCCESS 审计（actor=真实身份，兑现 2.4.4"鉴权就绪后接真实身份"预留）；POST 创建不设限（保持 2.4.4 演示行为） | lofi 做什么-5 + 问题5 | 集成测试 4 用例：user 查 200 / user 删 403 / 无头查 401 / admin 删 200+审计 |
| B10 | 依赖登记：`docs/dependencies.md` 登记 spring-boot-starter-oauth2-resource-server（本设计会话已核验并登记，编码会话仅复核） | lofi 依赖核验与登记 | 人工核对（文档级） |
| B11 | 契约固化：ADR-005 §3 新增第 7 项"鉴权契约"（头命名、401/403 口径、错误码、配置前缀、信任边界），随编码会话提交 | lofi 规格缺口声明-1 | 人工核对（文档级） |

## 接口契约

### AuthContext（com.ctds.common.auth，静态工具，用法同 LogContext）

| 方法 | 行为 |
| --- | --- |
| `subject()` | 当前身份主体；空上下文返回 `null`（业务先判 `isAuthenticated()` 或容忍 null） |
| `roles()` | 角色集合（不可变；空上下文 = 空集，不抛错） |
| `isAuthenticated()` | `subject() != null` |

- 上下文由 `AuthContextFilter` 在请求入口按头解析、写入 ThreadLocal，`finally` 清理；**头命名可配置**（`ctds.auth.header.*`）。
- 解析规则：`X-Ctds-Roles` 逗号分隔，逐项 trim、去空段、去重；同名头多次出现取第一个；**超长即整头作废**（subject >128 字符或 roles 合计 >512 字符 → 空上下文 + WARN，日志不回显原值）。

### @RequirePermission + PermissionInterceptor（服务侧强制）

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();          // 权限名，命名"对象.动作"小写点分（与审计 action 同一命名规约），单权限
}
```

- `HandlerInterceptor.preHandle`：方法无注解 → 放行；有注解且上下文为空 → 抛 `BizException(UNAUTHORIZED)`；有身份无权限 → 抛 `BizException(FORBIDDEN)` 并联动 DENIED 审计。
- 注解值为空串 = 编码错误 → 抛 `IllegalStateException`（对外只见 500 通用文案）。
- 拦截器仅在 servlet Web 环境注册；`ctds.auth.enabled=false`（默认 true）→ 不注册（上下文读取不受此开关影响，始终可用）。

### AccessControl 与 RolePermissionMapper（判断内核）

```java
public interface AccessControl {
    boolean hasPermission(String permission);  // 只判断；不抛错、不记审计（调用方可自定义流程）
    void require(String permission);           // 无权 → 抛 BizException + 记 DENIED 审计（与注解一致）
}

public interface RolePermissionMapper {
    Set<String> permissionsOf(String role);    // 未知角色返回空集；实现可整体替换（3.9.2 库表来源）
}
```

- 默认实现 `ConfigRolePermissionMapper` 绑定 `ctds.auth.permissions`（`Map<String,String>`，值为逗号分隔权限）；权限/角色名 >64 字符按"不命中"处理（不抛错）。
- 判定 = 当前上下文任一角色的权限并集含目标权限；`DefaultAccessControl` 以 `@ConditionalOnMissingBean(AccessControl.class)` 注册。

### AuthAdvice（对外表现，servlet 侧）

`@RestControllerAdvice` + `@Order(100)`（先于无优先级的 GlobalExceptionHandler），**只处理本组件两个码**，其余原样走全局默认（C/B→400），既有契约零改动：

| 错误码 | HTTP | 对外文案（服务端常量，不回显输入） |
| --- | --- | --- |
| `1000C0002` UNAUTHORIZED（复用） | 401 | 认证失败或身份已失效 |
| `1000C0005` FORBIDDEN（新增登记） | 403 | 无权限执行该操作 |

响应体 = 统一封套 `ApiResult{code, message, traceId, data:null}`（traceId 由既有 TraceIdFilter 提供）。

### 网关侧（reactive，`ctds.auth.gateway.enabled=true` 才装配）

- `SecurityWebFilterChain`：全部路由要求 JWT 认证（校验签名 + exp/nbf + 可选 iss），白名单 `ctds.auth.gateway.permit-paths`（默认空）；未认证 → 401，响应体同上行封套 JSON，并记 `auth.verify` FAILURE（detail.reason ∈ MISSING/EXPIRED/BAD_SIGNATURE/BAD_ISSUER/MALFORMED，不含令牌原文）；
- `HeaderStripFilter`（WebFilter，先于安全链）：无条件剥离入站 `X-Ctds-Subject`/`X-Ctds-Roles`——**服务只信网关注入的头**（信任边界，ADR-005 §3 第 4 项落地件）；
- `ContextHeaderInjectFilter`（认证通过后）：claim `sub` → subject 头；claim（名 = `ctds.auth.jwt.roles-claim`，默认 `roles`，兼容字符串或数组）→ 角色头（逗号连接）；
- `JwtDecoder` Bean：`secret`（HS256）或 `jwk-set-uri`（RS256 等 JWKS）二选一（同缺/同配 → 启动失败并指明原因）；`issuer` 配置则校验 iss；`@ConditionalOnMissingBean` = SM2 校验器扩展点。

### 配置项全表

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `ctds.auth.enabled` | true | 服务侧 RBAC 强制总开关（上下文读取不受其约束） |
| `ctds.auth.header.subject` | `X-Ctds-Subject` | 身份上下文头名 |
| `ctds.auth.header.roles` | `X-Ctds-Roles` | 角色上下文头名 |
| `ctds.auth.permissions.<角色>` | （空） | 角色→逗号分隔权限清单（模式 A 配置来源） |
| `ctds.auth.audit.enabled` | true | 拒绝/失败自动联动 2.4.4 审计（可关） |
| `ctds.auth.gateway.enabled` | false | 网关侧 JWT 校验开关（仅网关服务开启） |
| `ctds.auth.jwt.secret` | — | HMAC 密钥；与 jwk-set-uri 二选一；真实密钥只进配置中心/KMS，禁止入库（红线 7） |
| `ctds.auth.jwt.jwk-set-uri` | — | JWKS 公钥集地址（生产建议） |
| `ctds.auth.jwt.issuer` | （空） | 配置则校验 iss claim |
| `ctds.auth.jwt.roles-claim` | `roles` | 令牌中角色声明名 |
| `ctds.auth.gateway.permit-paths` | （空） | 免认证路径白名单（逗号分隔，Ant 风格） |

## 数据结构与边界值

| 项 | 值/规则 |
| --- | --- |
| 审计事件 | action：`rbac.check`（DENIED，actor=subject，detail={permission}）、`auth.verify`（FAILURE，actor=anonymous，detail={reason}）、演示删除 `greeting.delete`（SUCCESS）；均沿 2.4.4 约束（≤20 键 / 值 ≤512 字符 / **禁含令牌、密码、个人信息**） |
| 值长度上限 | subject ≤128、roles 头合计 ≤512（超限整头作废→空上下文）、角色/权限名 ≤64（超限按不命中）、roles 段数 >32 取前 32 + WARN |
| JWT 校验细则 | 签名、exp/nbf（默认允许 60 秒时钟偏移，Nimbus 默认值即采纳）；iss 仅在配置 issuer 时校验；三类失败对外一律 401 同文案（内部 reason 只进审计，不暴露细节） |
| 上下文线程边界 | ThreadLocal 仅在请求线程内有效；异步派生线程不携带（V1.0 已知限制，写入交付说明） |
| 网关 traceId | 响应式网关暂无 TraceIdFilter（servlet 组件），401 封套 traceId 输出 `"-"`；链路贯通属后续网关交付/可观测范围（观察项，不阻塞） |
| 模块归属 | `common/auth`，目录=模块=artifactId=`common-auth`（T10 口径）；根 pom `<modules>` 与 dependencyManagement 增条目；自动装配 imports 登记 `AuthAutoConfiguration`（servlet 守卫）与 `GatewayAuthAutoConfiguration`（reactive + optional 类守卫） |
| 依赖 | `common-errorcode`、`common-logging`、`spring-boot-starter-web`（沿 errorcode 惯例）；optional：`org.springframework.boot:spring-boot-starter-oauth2-resource-server`（版本由 Boot 3.5.16 BOM 管理，pom 不显式锁版；Maven Central 核验 2026-09-07，Apache-2.0，已登记 `docs/dependencies.md`；传递依赖 nimbus-jose-jwt / spring-security 均随 BOM） |
| ADR 增补 | ADR-005 §3 第 7 项"鉴权契约"（头命名、401/403 口径与错误码、配置前缀 ctds.auth、信任边界：内部服务只认网关注入头且网关剥外部同名头），随编码任务提交 |

## 验收演示（业务可读，编码会话交付说明附实测命令）

| # | 请求（对 example-service） | 预期 |
| --- | --- | --- |
| 1 | GET 列表，带 `X-Ctds-Subject: u1 / X-Ctds-Roles: user` | 200 正常返回 |
| 2 | DELETE 一条问候语，带 user 头 | 403 + 码 1000C0005"无权限执行该操作"，审计出现 rbac.check DENIED |
| 3 | GET 列表，不带头 | 401 + 码 1000C0002"认证失败或身份已失效" |
| 4 | DELETE，带 admin 头 | 200 删除成功，审计出现 greeting.delete SUCCESS（actor=u 身份） |

## 编码会话实施清单（定稿确认后按序执行）

1. `ErrorCodes` 增量登记 FORBIDDEN（1000C0005）；ADR-005 §3 增补第 7 项"鉴权契约"（B11）；
2. 根 pom：`<modules>` 增 `common/auth`，dependencyManagement 增 `common-auth`；
3. 测试先行：按 B1–B9 写失败测试（双向用例 + 审计断言 + B7/B8 异常路径；本模块为核心模块 auth，行覆盖 ≥80%）；
4. 实现 `common/auth`（结构见上，包 `com.ctds.common.auth` 单包，沿 logging 惯例）；
5. example-service 接入：DELETE 端点 + 两注解 + yml 映射（user/admin 演示角色）；**既有测试适配**：GreetingControllerTest、AuditTrailIntegrationTest 补合法上下文头（行为契约不变）；
6. 门禁全绿（compile/test/checkstyle）→ 4 视角评审（子智能体**串行**派发，沿 2.4.4 教训）→ 演示表 1–4 实测 → 交付说明。

## 变更影响声明

- **既有测试影响**：仅 example-service 两处用例补头（第 5 步），不改任何被测行为契约；
- **既有组件**：errorcode 仅码表增量登记一个常量；logging 零改动（直接复用 AuditRecorder/AuditEvent）；GlobalExceptionHandler、分页等行为不变；
- 无验收剧本（基建组件，演示即验收，命令见"验收演示"表）；
- 追溯矩阵：WBS 2.4.5 → lofi（PO 已确认 2026-09-07）→ 本 hifi → ADR-005 §3.7 → 代码/测试 → 依赖登记簿；
- 观察项承接：平台"令牌签发"归属仍按 lofi 声明在 3.9.1 澄清（不阻塞本任务）；网关 reactive 链路 traceId 贯通属后续可观测范围。
